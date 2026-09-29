#!/usr/bin/env python3
"""Fail-closed extraction of the current production SQL/value boundary.

This restricted source checker is trusted translation infrastructure, not a
general Python or JavaScript verifier. Its output distinguishes structural
checks from Lean proofs and concrete execution witnesses.
"""
from __future__ import annotations
import ast
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EXECUTOR = '''
def execute(connection, query_id, params=()):
    registry = _registry()
    if type(query_id) is not str or query_id not in registry:
        raise QueryError('Unknown exercise SQL query.')
    entry = registry[query_id]
    values = _parameters(entry['parameters'], params)
    return connection.execute(entry['sql'], values)
'''
PARAMETERS = '''
def _parameters(parameters, values):
    if type(values) not in (tuple, list) or len(parameters) != len(values):
        raise QueryError('Invalid exercise SQL parameters.')
    for parameter, value in zip(parameters, values):
        kind = parameter['type']
        if kind == 'int':
            valid = type(value) is int and INT_MIN <= value <= INT_MAX
        elif kind == 'text':
            valid = type(value) is str and '\\0' not in value
            if valid:
                try:
                    valid = len(value.encode('utf-8')) <= parameter['maxBytes']
                except UnicodeError:
                    valid = False
        else:
            valid = False
        if not valid:
            raise QueryError('Invalid exercise SQL parameters.')
    return tuple(values)
'''
ROWS = '''
def _rows(connection, table):
    return [dict(row) for row in sql.execute(connection, 'select_' + table)]
'''
INSERT = '''
def _insert(connection, table, values):
    sql.execute(connection, 'insert_' + table, tuple(values[field] for field in sql.FIELDS[table]))
'''
ROOT_MODULES = ('server.py', 'luna.py', 'runtime_dependencies.py', 'exercise_sql.py',
                'exercise_store.py', 'admin_auth.py', 'admin_service.py', 'admin_upload.py', 'admin_luna.py')
SINKS = {'execute', 'executemany', 'executescript', 'cursor', 'load_extension', 'enable_load_extension'}
FORBIDDEN_CALLS = {'eval', 'exec', '__import__', 'setattr', 'delattr', 'globals', 'locals', 'vars'}
PROTECTED = {'execute','_registry','_parameters','_read_artifact','_strict_json',
             'DDL','CONTROLS','FIELDS','ARTIFACT_HASHES'}


class BridgeError(ValueError):
    pass


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':')).encode()


def sha(data):
    return hashlib.sha256(data).hexdigest()


def read(root, relative):
    path = Path(root) / relative
    if path.is_symlink() or not path.is_file():
        raise BridgeError('Missing or linked bridge input: ' + relative)
    return path.read_bytes()


def literals(tree, name):
    rows = [node.value for node in tree.body if isinstance(node, ast.Assign)
            and len(node.targets) == 1 and isinstance(node.targets[0], ast.Name)
            and node.targets[0].id == name]
    if len(rows) != 1:
        raise BridgeError('Missing or ambiguous constant: ' + name)
    try:
        return ast.literal_eval(rows[0])
    except (ValueError, TypeError):
        raise BridgeError('Nonliteral constant: ' + name) from None


def function(tree, name):
    rows = [node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == name]
    if len(rows) != 1:
        raise BridgeError('Missing or ambiguous adapter: ' + name)
    return rows[0]


def require_frozen_sources(extraction, inputs):
    if any(inputs.get(path) != expected for path, expected in extraction['sources'].items()):
        raise BridgeError('Production correspondence depends on unfrozen or changed source')


def body(node):
    return [item for i, item in enumerate(node.body) if not
            (i == 0 and isinstance(item, ast.Expr) and isinstance(item.value, ast.Constant)
             and isinstance(item.value.value, str))]


def check_function(tree, name, expected):
    actual = function(tree, name)
    target = function(ast.parse(expected), name)
    if (ast.dump(ast.Module(body=body(actual), type_ignores=[])) !=
            ast.dump(ast.Module(body=body(target), type_ignores=[]))):
        raise BridgeError('Unsupported adapter semantics: ' + name)
    # Annotations are not runtime inputs. Argument names/defaults/decorators are.
    if ([(a.arg) for a in actual.args.args] != [a.arg for a in target.args.args]
            or actual.args.posonlyargs or actual.args.kwonlyargs or actual.args.vararg
            or actual.args.kwarg or actual.decorator_list
            or [ast.dump(x) for x in actual.args.defaults] != [ast.dump(x) for x in target.args.defaults]):
        raise BridgeError('Unsupported adapter signature: ' + name)
    return sha(ast.dump(actual).encode())


def extract(root=ROOT):
    root = Path(root)
    package = ast.parse(read(root, 'scripts/package_iis.py'))
    delivered = set(ROOT_MODULES)
    for group in ('ADMIN_MODULES', 'STORE_FILES'):
        delivered.update(name for name in literals(package, group) if name.endswith('.py'))
    delivered.update('scripts/' + name for name in literals(package, 'RUNTIME_HELPERS') if name.endswith('.py'))
    delivered.update('deploy/iis/' + name for name in literals(package, 'DEPLOY_FILES') if name.endswith('.py'))
    # Follow project-local imports as well as the deployed allowlist. New local
    # helpers cannot silently escape the source inventory.
    trees = {}
    pending = list(delivered)
    while pending:
        relative = pending.pop()
        if relative in trees:
            continue
        tree = ast.parse(read(root, relative))
        trees[relative] = tree
        for node in ast.walk(tree):
            names = []
            if isinstance(node,ast.Import):
                names = [item.name for item in node.names]
            elif isinstance(node,ast.ImportFrom):
                parts = []
                if node.level:
                    parts = list(Path(relative).parts[:-1])
                    if node.level > len(parts):
                        raise BridgeError('Unsupported relative import')
                    parts = parts[:len(parts)-node.level+1]
                if node.module:
                    parts.extend(node.module.split('.'))
                prefix = '.'.join(parts)
                if prefix:
                    names.append(prefix)
                    names.extend(prefix+'.'+item.name for item in node.names if item.name != '*')
                if any(item.name == '*' for item in node.names):
                    raise BridgeError('Unregistered wildcard import')
            for name in names:
                pieces = name.split('.')
                candidates = [name.replace('.', '/') + '.py']
                candidates.extend('/'.join(pieces[:i])+'/__init__.py' for i in range(1,len(pieces)+1))
                for local in candidates:
                    if (root / local).is_file() and local not in trees:
                        pending.append(local)
    sql, store = trees['exercise_sql.py'], trees['exercise_store.py']
    adapters = {name: check_function(tree, name, expected) for tree, name, expected in (
        (sql, 'execute', EXECUTOR), (sql, '_parameters', PARAMETERS),
        (store, '_rows', ROWS), (store, '_insert', INSERT))}
    # These larger integrity routines are explicit reviewed translation trust,
    # fixed as normalized ASTs. An arbitrary new _registry implementation is not
    # certified merely because its artifact files still contain good SQL.
    policy_path = 'formal/sql/bridge-policy.json'
    policy = json.loads(read(root, policy_path))
    if set(policy['trustedIntegrityFunctions']) != {'_registry','_read_artifact','_strict_json'}:
        raise BridgeError('Incomplete integrity routine policy')
    for name, expected in policy['trustedIntegrityFunctions'].items():
        if sha(ast.dump(function(sql,name)).encode()) != expected:
            raise BridgeError('Unregistered integrity routine semantics: ' + name)
    fields, controls, ddl = literals(sql, 'FIELDS'), literals(sql, 'CONTROLS'), literals(sql, 'DDL')
    hashes = literals(sql, 'ARTIFACT_HASHES')
    if set(hashes) != {'sql/compiled-queries.json','sql/queries.json','sql/schema.json','vendor/sqlean/provenance.json'}:
        raise BridgeError('Incomplete query artifact inventory')
    for relative, expected in hashes.items():
        if sha(read(root, relative)) != expected:
            raise BridgeError('SQL artifact integrity failure')
    compiled = json.loads(read(root, 'sql/compiled-queries.json'))
    queries = compiled['queries']
    identifiers = [entry['id'] for entry in queries]
    if (len(set(identifiers)) != len(identifiers) or set(identifiers) !=
            {'select_schema','select_schema_sql', *(prefix+table for prefix in ('select_','insert_') for table in fields)}):
        raise BridgeError('Unmapped registered query')
    if any(type(value) is not str for value in (*controls, *ddl)):
        raise BridgeError('Nonliteral control statement')
    mappings = []
    for relative, tree in sorted(trees.items()):
        parents = {id(child): parent for parent in ast.walk(tree) for child in ast.iter_child_nodes(parent)}
        module_aliases = {alias.asname or alias.name: alias.name for node in ast.walk(tree)
                          if isinstance(node,ast.Import) for alias in node.names}
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                for alias in node.names:
                    if alias.name == 'sqlite3' and (alias.asname or relative not in
                            {'exercise_sql.py','exercise_store.py','scripts/prepare_private_data.py'}):
                        raise BridgeError('Unregistered SQLite import')
                    if alias.name == 'exercise_sql' and (relative != 'exercise_store.py' or alias.asname != 'sql'):
                        raise BridgeError('Unregistered database adapter alias')
            if isinstance(node, ast.ImportFrom) and node.module == 'sqlite3':
                raise BridgeError('Unregistered SQLite capability alias')
            if isinstance(node, ast.ImportFrom) and any(alias.name in
                    SINKS | FORBIDDEN_CALLS | {'getattr','_rows','_insert','_registry','_parameters'} for alias in node.names):
                raise BridgeError('Unregistered imported execution capability')
            if relative == 'exercise_sql.py':
                if isinstance(node,(ast.Import,ast.ImportFrom)) and any(
                        (alias.asname or alias.name.split('.')[0]) in PROTECTED for alias in node.names):
                    raise BridgeError('Imported SQL authority replacement')
                if isinstance(node,ast.Name) and isinstance(node.ctx,(ast.Store,ast.Del)) and node.id in PROTECTED:
                    parent = parents[id(node)]
                    if not (isinstance(parent,ast.Assign) and parent in tree.body and
                            len(parent.targets)==1 and parent.targets[0] is node and
                            node.id in {'DDL','CONTROLS','FIELDS','ARTIFACT_HASHES'}):
                        raise BridgeError('Rebound SQL authority')
                if isinstance(node,ast.arg) and node.arg in PROTECTED:
                    raise BridgeError('Shadowed SQL authority')
                if isinstance(node,(ast.FunctionDef,ast.AsyncFunctionDef,ast.ClassDef)) and node.name in PROTECTED:
                    if not isinstance(node,ast.FunctionDef) or node not in tree.body:
                        raise BridgeError('Shadowed SQL authority')
                if isinstance(node,(ast.ExceptHandler,ast.MatchAs,ast.MatchStar)) and node.name in PROTECTED:
                    raise BridgeError('Pattern-bound SQL authority')
            if isinstance(node,ast.Name) and isinstance(node.ctx,ast.Load):
                if node.id in FORBIDDEN_CALLS:
                    raise BridgeError('Escaped dynamic execution capability')
                if node.id == 'getattr':
                    parent = parents[id(node)]
                    if not isinstance(parent,ast.Call) or parent.func is not node:
                        raise BridgeError('Escaped reflection capability')
            if isinstance(node,ast.Attribute) and node.attr in FORBIDDEN_CALLS | {'getattr','__getattribute__','__getattr__','__dict__','__builtins__'}:
                raise BridgeError('Unregistered reflection capability')
            if isinstance(node, ast.Call) and isinstance(node.func, ast.Name):
                if node.func.id in FORBIDDEN_CALLS:
                    raise BridgeError('Unregistered dynamic execution capability')
                if node.func.id == 'getattr' and len(node.args) > 1 and (
                        not isinstance(node.args[1],ast.Constant) or node.args[1].value in SINKS | {'connect'}):
                    raise BridgeError('Unregistered reflected database capability')
            if isinstance(node, ast.Attribute) and isinstance(node.ctx, (ast.Store,ast.Del)):
                if node.attr in PROTECTED or (isinstance(node.value,ast.Name) and (node.value.id in {'sql','sqlite3','exercise_sql'}
                        or module_aliases.get(node.value.id) in {'exercise_sql','exercise_store','sqlite3'})):
                    raise BridgeError('Mutation of the database adapter')
            if isinstance(node, ast.Name) and isinstance(node.ctx,ast.Load) and node.id in {'_rows','_insert'}:
                parent = parents[id(node)]
                if not isinstance(parent,ast.Call) or parent.func is not node:
                    raise BridgeError('Escaped row adapter')
                if (relative != 'exercise_store.py' or len(parent.args) < 2 or
                        not isinstance(parent.args[1],ast.Constant) or parent.args[1].value not in fields):
                    raise BridgeError('Caller-controlled table selector')
                mappings.append({'file':relative,'line':node.lineno,'kind':'literal-table-selector',
                                 'table':parent.args[1].value,'helper':node.id})
            if not isinstance(node,ast.Attribute) or node.attr not in SINKS | {'connect'}:
                continue
            parent = parents[id(node)]
            if not isinstance(parent,ast.Call) or parent.func is not node:
                raise BridgeError('Escaped database execution capability')
            receiver = ast.unparse(node.value)
            expression = ast.unparse(parent)
            kind = None
            if node.attr == 'connect':
                if receiver != 'sqlite3' or relative != 'exercise_store.py':
                    # Other connection APIs (HTTP sockets) have no SQL semantics.
                    if receiver == 'sqlite3': raise BridgeError('Unregistered SQLite connection')
                    continue
                if expression not in (
                    "sqlite3.connect(path.as_uri() + ('?mode=rw' if writable else '?mode=ro'), uri=True, timeout=LOCK_SECONDS, isolation_level=None)",
                    'sqlite3.connect(temporary)', 'sqlite3.connect(destination)'):
                    raise BridgeError('Unregistered SQLite URI construction')
                kind = 'fixed-database-connection'
            elif relative == 'exercise_sql.py' and receiver == 'connection' and node.attr == 'execute':
                if expression == "connection.execute(entry['sql'], values)": kind = 'registered-bound-query'
                elif (len(parent.args) == 1 and not parent.keywords and isinstance(parent.args[0],ast.Subscript)
                      and isinstance(parent.args[0].value,ast.Name) and parent.args[0].value.id == 'CONTROLS'
                      and isinstance(parent.args[0].slice,ast.Constant)
                      and type(parent.args[0].slice.value) is int and 0 <= parent.args[0].slice.value < len(controls)):
                    kind = 'fixed-control'
                elif expression == 'connection.execute(statement)':
                    ancestor = parents[id(parent)]
                    while id(ancestor) in parents and not isinstance(ancestor,ast.For): ancestor = parents[id(ancestor)]
                    if not (isinstance(ancestor,ast.For) and ast.unparse(ancestor.target) == 'statement'
                            and ast.unparse(ancestor.iter) == 'DDL' and
                            ast.dump(ast.Module(body=ancestor.body,type_ignores=[])) ==
                            ast.dump(ast.parse('connection.execute(statement)'))):
                        raise BridgeError('Unregistered schema execution loop')
                    kind = 'fixed-schema'
            elif relative == 'exercise_store.py' and receiver == 'connection' and node.attr == 'execute':
                if (len(parent.args)==1 and not parent.keywords and isinstance(parent.args[0],ast.Constant)
                        and parent.args[0].value in controls): kind = 'fixed-control'
            elif relative == 'exercise_store.py' and receiver == 'sql' and node.attr == 'execute':
                if expression in ("sql.execute(connection, 'select_' + table)",
                    "sql.execute(connection, 'insert_' + table, tuple((values[field] for field in sql.FIELDS[table])))"):
                    kind = 'registered-row-adapter'
            if kind is None:
                raise BridgeError('Unregistered SQL execution shape: ' + relative)
            mappings.append({'file':relative,'line':node.lineno,'kind':kind,'expression':expression})
    # Body equality above establishes a restricted lowering. Validation is
    # conservatively modeled as either rejecting or returning these same values;
    # it can never supply or rewrite SQL bytes.
    kinds = [row['kind'] for row in mappings]
    if kinds.count('registered-bound-query') != 1 or kinds.count('registered-row-adapter') != 2:
        raise BridgeError('Missing or repeated SQL adapter')
    sources = {path:sha(read(root,path)) for path in sorted(trees)}
    for path in ('web/admin/app.js','web/app.js','scripts/package_iis.py',policy_path,*hashes):
        sources[path] = sha(read(root,path))
    return {'schemaVersion':1,'classification':'CHECKED_RESTRICTED_SOURCE_EXTRACTION',
            'adapters':adapters,'sources':sources,'mappings':mappings,
            'registry':queries,'controls':list(controls)+list(ddl),
            'semanticBoundary':'Extractor, host-language semantics, JSON codecs and SQLite binding are trusted; arbitrary backend values are modeled.'}


if __name__ == '__main__':
    try:
        value = extract()
        print(json.dumps({'status':'PASS','queries':len(value['registry']),'mappings':len(value['mappings'])}))
    except (OSError,ValueError,KeyError,TypeError):
        print(json.dumps({'status':'FAIL','reason':'SQL separation source extraction rejected'}))
        raise SystemExit(1)
