"""Real SQLite and SQLeanParser-template boundary regression witnesses."""
from __future__ import annotations

import importlib.util
import hashlib
import json
from pathlib import Path
import shutil
import sqlite3
import tempfile
import unittest
from unittest import mock

import exercise_sql as sql


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('compile_sql_queries', ROOT / 'scripts/compile_sql_queries.py')
GENERATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GENERATOR)


class _UntouchedConnection:
    def execute(self, *_args):
        raise AssertionError('SQLite must not receive rejected queries or parameters')


def exercise_values(**changes):
    values = dict.fromkeys(sql.FIELDS['exercises'], '')
    values.update(id='example', ordinal=0, origin='authored', title='Example',
                  model_group='Examples', predicate_name='inv', description='A fixture.',
                  environment_before='sig Node {}\n', predicate_header='pred inv ',
                  starter='some Node', primary_token='token', metadata='{}', validation='{}')
    values.update(changes)
    return tuple(values[name] for name in sql.FIELDS['exercises'])


class SqlQueryTests(unittest.TestCase):
    def setUp(self):
        self.connection = sqlite3.connect(':memory:')
        self.addCleanup(self.connection.close)
        sql.create_schema(self.connection)

    def test_registry_matches_every_table_and_field(self):
        registry = sql._registry()
        self.assertEqual(set(registry), {'select_schema', 'select_schema_sql'} |
                         {prefix + table for prefix in ('select_', 'insert_') for table in sql.FIELDS})
        for table, fields in sql.FIELDS.items():
            entry = registry['insert_' + table]
            self.assertEqual(tuple(item['name'] for item in entry['parameters']), fields)
            self.assertEqual(entry['sql'].count('?'), len(fields))
            self.assertTrue(entry['roundTrip'])
            self.assertTrue(entry['certificate'].startswith('Valid INSERT'))
            self.assertIn('SQLean.Statement.insert', entry['ast'])

    def test_sql_looking_values_round_trip_without_changing_structure(self):
        attack = "x'); DELETE FROM exercises; -- 'λ😺'\n/* still data */"
        sql.execute(self.connection, 'insert_exercises', exercise_values(title=attack))
        solution = ('example', 0, 'oracle', attack, attack, 'token', '{}')
        sql.execute(self.connection, 'insert_solutions', solution)
        sql.execute(self.connection, 'insert_auxiliary', ('witness', 0, '{}', attack))
        sql.execute(self.connection, 'insert_metadata', (attack, attack))
        self.assertEqual(sql.execute(self.connection, 'select_exercises').fetchone()[3], attack)
        self.assertEqual(tuple(sql.execute(self.connection, 'select_solutions').fetchone()), solution)
        self.assertEqual(tuple(sql.execute(self.connection, 'select_metadata').fetchone()), (attack, attack))
        self.assertEqual(sql.execute(self.connection, 'select_auxiliary').fetchone()[3], attack)
        sql.validate_schema(self.connection)

    def test_unknown_queries_fail_before_sqlite(self):
        for query in ('SELECT * FROM exercises', 'delete_exercises', 'select_metadata; DROP TABLE metadata', None, 1):
            with self.subTest(query=query), self.assertRaises(sql.QueryError):
                sql.execute(_UntouchedConnection(), query)

    def test_parameter_arity_type_unicode_and_size_fail_before_sqlite(self):
        invalid = [(), ('key',), ('key', 'value', 'extra'), 'key', ('key', None),
                   ('key', float('nan')), ('key', b'value'), ('key', '\0'),
                   ('key', '\ud800'), ('key', 'x' * (4 * 1024 * 1024 + 1))]
        for parameters in invalid:
            with self.subTest(types=tuple(type(value).__name__ for value in parameters)), self.assertRaises(sql.QueryError):
                sql.execute(_UntouchedConnection(), 'insert_metadata', parameters)
        for value in (True, False, 0.0, '0', None, 2 ** 63, -(2 ** 63) - 1):
            with self.subTest(value=value), self.assertRaises(sql.QueryError):
                sql.execute(_UntouchedConnection(), 'insert_auxiliary', ('kind', value, '{}', ''))

    def test_exact_parameter_boundaries_count_utf8_bytes(self):
        parameter = [{'type': 'text', 'maxBytes': 8}]
        for value in ('x' * 7, 'x' * 8, 'é' * 4):
            self.assertEqual(sql._parameters(parameter, (value,)), (value,))
        for value in ('x' * 9, 'é' * 5):
            with self.assertRaises(sql.QueryError):
                sql._parameters(parameter, (value,))
        for value in (-(2 ** 63), 2 ** 63 - 1):
            self.assertEqual(sql._parameters([{'type': 'int'}], (value,)), (value,))

    def test_artifact_tampering_and_late_tampering_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for relative in sql.ARTIFACT_HASHES:
                target = root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(ROOT / relative, target)
            with mock.patch.object(sql, 'ROOT', root):
                self.assertEqual(sql.execute(self.connection, 'select_metadata').fetchall(), [])
                for relative in sql.ARTIFACT_HASHES:
                    target = root / relative
                    original = target.read_bytes()
                    target.write_bytes(original + b' ')
                    with self.subTest(path=relative), self.assertRaises(sql.QueryError):
                        sql.execute(_UntouchedConnection(), 'select_metadata')
                    target.write_bytes(original)
                artifact = root / 'sql/compiled-queries.json'
                artifact.unlink()
                with self.assertRaises(sql.QueryError):
                    sql.execute(_UntouchedConnection(), 'select_metadata')

    def test_linked_artifact_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for relative in sql.ARTIFACT_HASHES:
                target = root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(ROOT / relative, target)
            target = root / 'sql/queries.json'
            target.unlink()
            try:
                target.symlink_to(ROOT / 'sql/queries.json')
            except OSError:
                self.skipTest('This operating system does not permit the link fixture')
            with mock.patch.object(sql, 'ROOT', root), self.assertRaises(sql.QueryError):
                sql.execute(_UntouchedConnection(), 'select_metadata')

    def test_schema_objects_constraints_and_duplicate_creation_are_rejected(self):
        additions = [
            'CREATE TABLE extra (value TEXT)',
            'CREATE VIEW extra AS SELECT id FROM exercises',
            'CREATE INDEX extra ON exercises(title)',
            "CREATE TRIGGER extra AFTER INSERT ON exercises BEGIN UPDATE exercises SET title = 'changed' WHERE id = NEW.id; END",
        ]
        for addition in additions:
            with self.subTest(ddl=addition), sqlite3.connect(':memory:') as connection:
                sql.create_schema(connection)
                connection.execute(addition)
                with self.assertRaises(sql.QueryError):
                    sql.validate_schema(connection)
        with sqlite3.connect(':memory:') as connection:
            for statement in sql.DDL:
                connection.execute(statement.replace('CHECK (ordinal >= 0)', 'CHECK (ordinal >= -1)'))
            with self.assertRaises(sql.QueryError):
                sql.validate_schema(connection)
        sql.execute(self.connection, 'insert_metadata', ('preserve', 'value'))
        with self.assertRaises(sql.QueryError):
            sql.create_schema(self.connection)
        self.assertEqual(sql.execute(self.connection, 'select_metadata').fetchall(), [('preserve', 'value')])

    def test_fixed_relational_constraints_and_rollback(self):
        sql.execute(self.connection, 'insert_exercises', exercise_values())
        sql.execute(self.connection, 'insert_solutions', ('example', 0, 'oracle', 'some Node', '', 'token', '{}'))
        self.connection.commit()
        for query, parameters in (
                ('insert_solutions', ('missing', 0, 'oracle', 'some Node', '', 'token', '{}')),
                ('insert_solutions', ('example', -1, 'oracle', 'some Node', '', 'another', '{}')),
                ('insert_solutions', ('example', 1, 'oracle', 'some Node', '', 'token', '{}')),
                ('insert_exercises', exercise_values(id='other', ordinal=0)),
                ('insert_exercises', exercise_values(id='other', ordinal=1, origin='unknown'))):
            with self.subTest(query=query), self.assertRaises(sqlite3.IntegrityError):
                sql.execute(self.connection, query, parameters)
            self.connection.rollback()
        self.assertEqual(len(sql.execute(self.connection, 'select_exercises').fetchall()), 1)
        self.assertEqual(len(sql.execute(self.connection, 'select_solutions').fetchall()), 1)

    def test_readonly_connection_cannot_insert(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'private.sqlite'
            with sqlite3.connect(path) as writer:
                sql.create_schema(writer)
            with sqlite3.connect(path.as_uri() + '?mode=ro', uri=True) as reader:
                sql.validate_schema(reader)
                with self.assertRaises(sqlite3.OperationalError):
                    sql.execute(reader, 'insert_metadata', ('key', 'value'))

    def test_schema_creation_rejects_transaction_with_disabled_foreign_keys(self):
        with sqlite3.connect(':memory:') as connection:
            connection.execute('BEGIN')
            with self.assertRaises(sql.QueryError):
                sql.create_schema(connection)
            self.assertEqual(sql.execute(connection, 'select_schema').fetchall(), [])
        with sqlite3.connect(':memory:') as connection:
            connection.execute('PRAGMA foreign_keys = ON')
            connection.execute('BEGIN')
            sql.create_schema(connection)
            self.assertTrue(connection.in_transaction)
            connection.rollback()
            self.assertEqual(sql.execute(connection, 'select_schema').fetchall(), [])


class QueryCompilerTests(unittest.TestCase):
    def setUp(self):
        self.parameter = {'name': 'body', 'type': 'text', 'sentinel': '__SQL_BIND_BODY__', 'maxBytes': 8192}

    def test_complete_tokens_preserve_string_escapes_and_identifiers(self):
        statement = 'SELECT "a""b", \'O\'\'Brien\' FROM "examples" WHERE "body" = \'__SQL_BIND_BODY__\';'
        expected = 'SELECT "a""b", \'O\'\'Brien\' FROM "examples" WHERE "body" = ?;'
        self.assertEqual(GENERATOR.parameterize(statement, [self.parameter]), expected)

    def test_missing_duplicate_embedded_unknown_and_out_of_order_slots_fail(self):
        invalid = [
            'SELECT "__SQL_BIND_BODY__" FROM "examples";',
            "SELECT '__SQL_BIND_BODY__', '__SQL_BIND_BODY__' FROM examples;",
            "SELECT 'prefix __SQL_BIND_BODY__ suffix' FROM examples;",
            "SELECT '__SQL_BIND_UNKNOWN__' FROM examples;",
            "SELECT '__SQL_BIND_BODY__' FROM examples -- comment",
            "SELECT '__SQL_BIND_BODY__' FROM examples WHERE x = ?;",
            "SELECT '__SQL_BIND_BODY__ FROM examples;",
        ]
        for statement in invalid:
            with self.subTest(statement=statement), self.assertRaises(ValueError):
                GENERATOR.parameterize(statement, [self.parameter])
        second = dict(self.parameter, name='other', sentinel='__SQL_BIND_OTHER__')
        with self.assertRaises(ValueError):
            GENERATOR.parameterize("SELECT '__SQL_BIND_OTHER__', '__SQL_BIND_BODY__' FROM examples;",
                                   [self.parameter, second])
        with self.assertRaises(ValueError):
            GENERATOR.parameterize("SELECT '__SQL_BIND_BODY__' FROM examples;", [self.parameter, self.parameter])

    def test_wrong_type_and_integer_fragment_slots_fail(self):
        integer = {'name': 'ordinal', 'type': 'int', 'sentinel': 9000000000000001}
        self.assertEqual(GENERATOR.parameterize('SELECT 9000000000000001 FROM examples;', [integer]),
                         'SELECT ? FROM examples;')
        for statement in ("SELECT '9000000000000001' FROM examples;", 'SELECT 9000000000000001.0 FROM examples;'):
            with self.subTest(statement=statement), self.assertRaises(ValueError):
                GENERATOR.parameterize(statement, [integer])
        for invalid in (True, 9000000000000001.0, '9000000000000001'):
            with self.assertRaises(ValueError):
                GENERATOR.parameterize('SELECT 9000000000000001 FROM examples;', [dict(integer, sentinel=invalid)])

    def test_generated_region_cannot_be_ambiguous(self):
        for source in ('', GENERATOR.START, GENERATOR.START + GENERATOR.END + GENERATOR.START):
            with self.assertRaises(ValueError):
                GENERATOR.integrity_source(source, {})

    def test_checked_parser_artifacts_bind_schema_registry_and_source_provenance(self):
        # This checks frozen generation evidence, not a fresh parser execution.
        # Mechanical closure separately invokes compile_sql_queries.py --check.
        artifact = json.loads((ROOT / 'sql/compiled-queries.json').read_bytes())
        for relative, expected in artifact['inputs'].items():
            self.assertEqual(hashlib.sha256((ROOT / relative).read_bytes()).hexdigest(), expected)
        provenance = json.loads((ROOT / 'vendor/sqlean/provenance.json').read_bytes())
        for entry in provenance['files']:
            source = ROOT / 'vendor/sqlean' / entry['path']
            self.assertEqual(hashlib.sha256(source.read_bytes()).hexdigest(), entry['sha256'])
        self.assertEqual(provenance['upstreamCommit'], '6da54ef2874cbe7e0069bf8c192f12de3a8644f9')
        self.assertEqual(provenance['leanToolchain'], 'leanprover/lean4:v4.34.0')
        records = artifact['queries']
        self.assertEqual(len(records), 10)
        self.assertTrue(all(record['roundTrip'] for record in records))
        self.assertTrue(all(record['ast'].startswith('SQLean.Statement.') for record in records))

    def test_prepared_statements_compile_in_actual_sqlite(self):
        with sqlite3.connect(':memory:') as connection:
            sql.create_schema(connection)
            for entry in sql._registry().values():
                parameters = tuple(parameter['sentinel'] for parameter in entry['parameters'])
                # EXPLAIN compiles without executing mutations. This test-only
                # prefix is not part of the application query interface.
                result = connection.execute('EXPLAIN ' + entry['sql'], parameters).fetchall()
                self.assertTrue(result, entry['id'])


if __name__ == '__main__':
    unittest.main()
