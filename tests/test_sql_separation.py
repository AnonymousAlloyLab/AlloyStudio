"""Fail-closed source-bridge mutation controls and the real browser JSON adapter.

The mutated application source is inspected, never imported or executed. The
browser witness executes the current production api() function in Node's VM.
These checks test the trusted extractor; they do not replace Lean's kernel.
"""
import ast
import copy
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from scripts import sql_separation_bridge as bridge
from test_sql_injection import PAYLOADS


ROOT = Path(__file__).resolve().parents[1]


class StableAstTests(unittest.TestCase):
    def test_empty_python312_type_parameters_preserve_identity(self):
        original = ast.parse('def f(x):\n    return x\n').body[0]
        older = copy.deepcopy(original)
        older._fields = tuple(name for name in older._fields if name != 'type_params')
        newer = copy.deepcopy(older)
        newer._fields += ('type_params',)
        newer.type_params = []
        self.assertEqual(bridge.syntax(older), bridge.syntax(newer))

    def test_nonempty_type_parameters_are_not_erased(self):
        node = ast.parse('def f(x):\n    return x\n').body[0]
        node._fields = tuple(name for name in node._fields if name != 'type_params') + ('type_params',)
        node.type_params = [ast.Name(id='T', ctx=ast.Load())]
        with self.assertRaises(bridge.BridgeError):
            bridge.syntax(node)

    def test_semantic_fields_and_constant_types_remain_distinct(self):
        sources = ('return x', 'return y', 'return None', "return 'None'", 'return 0', 'return False', "return b'0'")
        identities = {bridge.syntax(ast.parse(source)) for source in sources}
        self.assertEqual(len(identities), len(sources))

    def test_astral_character_and_surrogate_pair_have_distinct_identities(self):
        scalar = ast.parse(r"x = '\U00010000'")
        surrogates = ast.parse(r"x = '\ud800\udc00'")
        self.assertNotEqual(scalar.body[0].value.value, surrogates.body[0].value.value)
        self.assertNotEqual(bridge.syntax(scalar), bridge.syntax(surrogates))

    def test_string_identity_preserves_code_points_and_type(self):
        values = ('', '\0', 'ASCII', '\ud800', '\udc00', '\U00010000',
                  '\ud800\udc00', '\u00e9', 'e\u0301')
        self.assertEqual(len({bridge.syntax(ast.Constant(value=value)) for value in values}),
                         len(values))
        for value in values:
            with self.subTest(value=ascii(value)):
                # Non-Constant string fields use the same lossless identity.
                encoded = json.loads(bridge.syntax(ast.Name(id=value, ctx=ast.Load())))
                code_points = encoded['fields']['id']['str']
                self.assertEqual(''.join(chr(point) for point in code_points), value)
                self.assertNotEqual(bridge.syntax(value), bridge.syntax(code_points))
        self.assertNotEqual(bridge.syntax('ASCII'), bridge.syntax(b'ASCII'))
        with self.assertRaises(bridge.BridgeError):
            bridge.syntax({'str': [65]})


class SqlSeparationMutationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.baseline = bridge.extract(ROOT)

    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        for relative in set(self.baseline['sources']) | {'scripts/package_iis.py'}:
            destination = self.root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / relative, destination)

    def append(self, relative, source):
        path = self.root / relative
        path.write_text(path.read_text(encoding='utf-8') + '\n' + source, encoding='utf-8')

    def replace(self, relative, before, after):
        path = self.root / relative
        source = path.read_text(encoding='utf-8')
        self.assertEqual(source.count(before), 1, 'Mutation must affect exactly the intended construct')
        path.write_text(source.replace(before, after), encoding='utf-8')

    def rejected(self):
        with self.assertRaises(bridge.BridgeError):
            bridge.extract(self.root)

    def test_current_production_maps_registered_queries_and_source_inventory(self):
        result = bridge.extract(self.root)
        self.assertEqual(result, self.baseline)
        self.assertEqual(len(result['registry']), 16)
        self.assertEqual(len(result['mappings']), 48)
        self.assertIn('candidate_store.py', result['sources'])
        self.assertIn('candidate_review.py', result['sources'])
        self.assertIn('web/admin/app.js', result['sources'])
        self.assertIn('exercise_sql.py', result['sources'])
        self.assertIn('scripts/package_iis.py', result['sources'])

    def test_imported_initializer_must_be_in_frozen_manifest(self):
        (self.root / 'scripts/__init__.py').write_text('NEW_PRODUCTION_IMPORT = True\n')
        result = bridge.extract(self.root)
        self.assertIn('scripts/__init__.py', result['sources'])
        with self.assertRaises(bridge.BridgeError):
            bridge.require_frozen_sources(result,self.baseline['sources'])

    def test_frozen_manifest_must_bind_exact_extracted_bytes(self):
        bridge.require_frozen_sources(self.baseline,self.baseline['sources'])
        changed = dict(self.baseline['sources'], **{'server.py':'0'*64})
        with self.assertRaises(bridge.BridgeError):
            bridge.require_frozen_sources(self.baseline,changed)

    def test_interpolated_sql_in_executor_is_rejected(self):
        self.replace('exercise_sql.py', "return connection.execute(entry['sql'], values)",
                     "return connection.execute(entry['sql'] % values)")
        self.rejected()

    def test_extra_unregistered_sql_sink_is_rejected(self):
        self.append('server.py', 'def new_route(connection, request):\n    connection.executescript(request["sql"])\n')
        self.rejected()

    def test_escaped_connection_execute_is_rejected(self):
        self.append('server.py', 'def new_route(connection, request):\n    run = connection.execute\n    run(request["sql"])\n')
        self.rejected()

    def test_reflected_execute_is_rejected(self):
        self.append('server.py', 'def new_route(connection, request):\n    getattr(connection, "execute")(request["sql"])\n')
        self.rejected()

    def test_aliased_reflection_is_rejected(self):
        self.append('server.py', 'def new_route(connection, request):\n    lookup = getattr\n    lookup(connection, "execute")(request["sql"])\n')
        self.rejected()

    def test_sqlite_import_alias_is_rejected(self):
        self.append('exercise_store.py', 'import sqlite3 as database\n')
        self.rejected()

    def test_sqlite_from_import_is_rejected(self):
        self.append('exercise_store.py', 'from sqlite3 import connect as open_database\n')
        self.rejected()

    def test_executor_value_rewrite_is_rejected(self):
        self.replace('exercise_sql.py', "return connection.execute(entry['sql'], values)",
                     "values = params[1:]\n    return connection.execute(entry['sql'], values)")
        self.rejected()

    def test_executor_statement_rewrite_is_rejected(self):
        self.replace('exercise_sql.py', "return connection.execute(entry['sql'], values)",
                     "entry['sql'] = params[0]\n    return connection.execute(entry['sql'], values)")
        self.rejected()

    def test_request_selected_table_is_rejected(self):
        self.append('exercise_store.py', 'def new_route(connection, request):\n    return _rows(connection, request["table"])\n')
        self.rejected()

    def test_candidate_selected_query_identifier_is_rejected(self):
        self.append('candidate_store.py', 'def injected(connection, request):\n'
                    '    sql.execute(connection, request["query"], (request["value"],))\n')
        self.rejected()

    def test_candidate_selected_qualified_table_is_rejected(self):
        self.append('candidate_store.py', 'def injected(connection, request):\n'
                    '    store._insert(connection, request["table"], request["values"])\n')
        self.rejected()

    def test_candidate_raw_sql_is_rejected(self):
        self.append('candidate_store.py', 'def injected(connection, request):\n'
                    '    connection.execute(request["sql"])\n')
        self.rejected()

    def test_escaped_rows_adapter_is_rejected(self):
        self.append('exercise_store.py', 'select_rows = _rows\n')
        self.rejected()

    def test_escaped_insert_adapter_is_rejected(self):
        self.append('exercise_store.py', 'insert_rows = _insert\n')
        self.rejected()

    def test_imported_row_adapter_alias_is_rejected(self):
        self.append('server.py', 'from exercise_store import _rows as select_rows\n'
                    'def new_route(connection, request):\n    return select_rows(connection, request["table"])\n')
        self.rejected()

    def test_valid_json_artifact_replacing_statement_is_rejected(self):
        path = self.root / 'sql/compiled-queries.json'
        document = json.loads(path.read_bytes())
        document['queries'][0]['sql'] = 'DROP TABLE exercises;'
        path.write_text(json.dumps(document), encoding='utf-8')
        self.rejected()

    def test_imported_project_local_module_sink_is_rejected(self):
        (self.root / 'local_sql_helper.py').write_text('def raw(connection, value):\n    connection.execute(value)\n')
        self.append('server.py', 'import local_sql_helper\n')
        self.rejected()

    def test_imported_project_local_package_sink_is_rejected(self):
        package = self.root / 'local_sql_helper'
        package.mkdir()
        (package / '__init__.py').write_text('def raw(connection, value):\n    connection.execute(value)\n')
        self.append('server.py', 'import local_sql_helper\n')
        self.rejected()

    def test_from_package_submodule_sink_is_rejected(self):
        (self.root / 'scripts/ignored_sql_sink.py').write_text(
            'def raw(connection, value):\n    connection.execute(value)\n')
        self.append('server.py', 'from scripts import ignored_sql_sink\n')
        self.rejected()

    def test_relative_package_submodule_sink_is_rejected(self):
        package = self.root / 'local_sql_helper'
        package.mkdir()
        (package / '__init__.py').write_text('from . import nested\n')
        (package / 'nested.py').write_text('def raw(connection, value):\n    connection.execute(value)\n')
        self.append('server.py', 'import local_sql_helper\n')
        self.rejected()

    def test_dunder_reflection_is_rejected(self):
        self.append('server.py', 'def new_route(connection, request):\n'
                    '    connection.__getattribute__("execute")(request["sql"])\n')
        self.rejected()

    def test_new_deployment_module_sink_is_rejected(self):
        (self.root / 'new_admin_helper.py').write_text('def raw(connection, value):\n    connection.execute(value)\n')
        self.replace('scripts/package_iis.py', "ADMIN_MODULES = (", "ADMIN_MODULES = ('new_admin_helper.py', ")
        self.rejected()

    def test_shadowed_control_cannot_be_classified_as_fixed_sql(self):
        self.append('exercise_sql.py', 'def unregistered_control(connection, request):\n'
                    '    CONTROLS = (request["sql"],)\n    connection.execute(CONTROLS[0])\n')
        self.rejected()

    def test_shadowed_schema_cannot_be_classified_as_fixed_sql(self):
        self.append('exercise_sql.py', 'def unregistered_schema(connection, request):\n'
                    '    DDL = (request["sql"],)\n    for statement in DDL:\n        connection.execute(statement)\n')
        self.rejected()

    def test_augmented_schema_authority_is_rejected(self):
        self.append('exercise_sql.py', 'DDL += ("DROP TABLE exercises;",)\n')
        self.rejected()

    def test_aliased_sql_module_cannot_replace_registry(self):
        self.append('server.py', 'import exercise_sql as query_runtime\n'
                    'query_runtime._registry = lambda: {"select_metadata": '
                    '{"parameters": [], "sql": "DROP TABLE metadata"}}\n')
        self.rejected()

    def test_rebound_registry_cannot_override_verified_artifacts(self):
        self.append('exercise_sql.py', '_registry = lambda: {"select_exercises": '
                    '{"parameters": [], "sql": "DROP TABLE exercises"}}\n')
        self.rejected()

    def test_registry_body_cannot_rewrite_verified_statement(self):
        self.replace('exercise_sql.py', '        return result\n    except (OSError, KeyError, TypeError, ValueError, UnicodeError):',
                     '        result["select_metadata"]["sql"] = "DROP TABLE metadata"\n'
                     '        return result\n    except (OSError, KeyError, TypeError, ValueError, UnicodeError):')
        self.rejected()

    def test_class_cannot_shadow_registry_function(self):
        self.append('exercise_sql.py', 'class _registry:\n'
                    '    def __contains__(self, key): return True\n'
                    '    def __getitem__(self, key): return {"sql":"DROP TABLE metadata", "parameters":[]}\n')
        self.rejected()

    def test_module_qualified_reflection_is_rejected(self):
        self.append('server.py','import builtins\n'
                    'def raw(connection, request):\n    builtins.getattr(connection,"execute")(request["sql"])\n')
        self.rejected()


class SqlSeparationBrowserAdapterTests(unittest.TestCase):
    def test_actual_admin_api_serializes_sql_shaped_values_without_rewriting_them(self):
        source = (ROOT / 'web/admin/app.js').read_text(encoding='utf-8')
        start, end = 'async function api(path, data) {', '\nasync function bootstrap('
        self.assertEqual(source.count(start), 1)
        self.assertEqual(source.count(end), 1)
        adapter = source[source.index(start):source.index(end)]
        documents = [{'id': 'test-draft', 'revision': 1,
                      'exercises': [{'predicate': 'inv1', 'title': payload, 'question': payload}]} for payload in PAYLOADS]
        # Evaluate the real source slice, with only browser effects supplied by
        # the harness. fetch records the JSON bytes actually sent by api().
        script = '''
const vm = require('node:vm');
let incoming = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', chunk => incoming += chunk);
process.stdin.on('end', async () => {
  try {
    const input = JSON.parse(incoming), requests = [];
    const context = {
      csrf: 'fixture-csrf',
      AbortController,
      setTimeout: () => 1,
      clearTimeout: () => {},
      fetch: async (path, options) => {
        requests.push({path, options: {...options, signal: undefined}});
        return {ok: true, status: 200,
          headers: {get: () => 'application/json; charset=utf-8'},
          json: async () => ({status: 'ok'})};
      }
    };
    vm.runInNewContext(input.adapter + '; globalThis.callApi = api;', context);
    for (const document of input.documents) await context.callApi('commit', document);
    process.stdout.write(JSON.stringify(requests));
  } catch (error) { process.stderr.write(String(error)); process.exitCode = 1; }
});
'''
        result = subprocess.run(['node', '-e', script], input=json.dumps({'adapter': adapter, 'documents': documents}),
                                text=True, capture_output=True, timeout=10, check=False)
        self.assertEqual(result.returncode, 0, result.stderr)
        requests = json.loads(result.stdout)
        self.assertEqual(len(requests), len(documents))
        for request, document in zip(requests, documents):
            self.assertEqual(request['path'], '../api/admin/commit')
            options = request['options']
            self.assertEqual(options['method'], 'POST')
            self.assertEqual(options['headers'], {'Content-Type': 'application/json', 'X-CSRF-Token': 'fixture-csrf'})
            self.assertEqual(options['credentials'], 'same-origin')
            self.assertEqual(options['redirect'], 'error')
            self.assertEqual(json.loads(options['body']), document)


if __name__ == '__main__':
    unittest.main()
