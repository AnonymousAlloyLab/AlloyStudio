"""Finite SQLeanParser-generated SQL, with a separately trusted schema boundary.

The parser runs during generation, never on a deployment machine. Runtime values
are bound parameters; this module exposes no caller-supplied SQL interface.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import sqlite3


ROOT = Path(__file__).resolve().parent
MAX_ARTIFACT_BYTES = 4 * 1024 * 1024
MAX_SQL_BYTES = 64 * 1024
INT_MIN, INT_MAX = -(2 ** 63), 2 ** 63 - 1
FIELDS = {
    'metadata': ('key', 'value'),
    'exercises': ('id', 'ordinal', 'origin', 'title', 'model_group', 'predicate_name',
                  'description', 'environment_before', 'environment_after',
                  'predicate_header', 'starter', 'original_source', 'primary_token',
                  'metadata', 'validation'),
    'solutions': ('exercise_id', 'ordinal', 'kind', 'body', 'original_source',
                  'token_hash', 'metadata'),
    'auxiliary': ('kind', 'ordinal', 'payload', 'original_source'),
}

# BEGIN GENERATED SQL INTEGRITY
ARTIFACT_HASHES = {'sql/compiled-queries.json': 'aaafd190668ec205e90c26984ef6b020c4f065f20a3a952d605f0b4e0d8a8838', 'sql/queries.json': '3daeb186f197052eaaf7150626e105c4ebd6006ee2cb0d0c0fad1f971c245e34', 'sql/schema.json': '3a534942cea24e6fe9434d61384c7f0fad5b9eb1d93984758fa64a369de17746', 'vendor/sqlean/provenance.json': '7ff723b346bfda65d335b07d8a6f11853497366781cb860141a210af3e9746b9'}
# END GENERATED SQL INTEGRITY

# Explicitly trusted operations: SQLeanParser does not accept DDL or controls.
DDL = (
    '''CREATE TABLE metadata (
    key TEXT NOT NULL PRIMARY KEY,
    value TEXT NOT NULL
)''',
    '''CREATE TABLE exercises (
    id TEXT NOT NULL PRIMARY KEY,
    ordinal INTEGER NOT NULL UNIQUE CHECK (ordinal >= 0),
    origin TEXT NOT NULL CHECK (origin IN ('legacy', 'authored')),
    title TEXT NOT NULL,
    model_group TEXT NOT NULL,
    predicate_name TEXT NOT NULL,
    description TEXT NOT NULL,
    environment_before TEXT NOT NULL,
    environment_after TEXT NOT NULL,
    predicate_header TEXT NOT NULL,
    starter TEXT NOT NULL,
    original_source TEXT NOT NULL,
    primary_token TEXT NOT NULL,
    metadata TEXT NOT NULL,
    validation TEXT NOT NULL
)''',
    '''CREATE TABLE solutions (
    exercise_id TEXT NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    kind TEXT NOT NULL,
    body TEXT NOT NULL,
    original_source TEXT NOT NULL,
    token_hash TEXT NOT NULL,
    metadata TEXT NOT NULL,
    PRIMARY KEY (exercise_id, ordinal),
    UNIQUE (exercise_id, token_hash),
    FOREIGN KEY (exercise_id) REFERENCES exercises(id)
)''',
    '''CREATE TABLE auxiliary (
    kind TEXT NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    payload TEXT NOT NULL,
    original_source TEXT NOT NULL,
    PRIMARY KEY (kind, ordinal)
)''',
)
CONTROLS = (
    'PRAGMA foreign_keys = ON', 'PRAGMA foreign_keys', 'BEGIN',
    'PRAGMA trusted_schema = OFF', 'PRAGMA query_only = ON',
    'BEGIN IMMEDIATE', 'PRAGMA page_count', 'PRAGMA page_size',
    'PRAGMA busy_timeout = 100',
)


class QueryError(ValueError):
    """A fixed, credential-free diagnostic; callers may sanitize further."""


def _strict_json(source):
    if isinstance(source, bytes):
        source = source.decode('utf-8')
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError('Duplicate JSON key')
            result[key] = value
        return result
    return json.loads(source, object_pairs_hook=pairs,
                      parse_constant=lambda _: (_ for _ in ()).throw(ValueError('Nonfinite JSON')))


def _read_artifact(relative):
    path = ROOT
    for part in Path(relative).parts:
        path /= part
        if path.is_symlink():
            raise QueryError('Exercise SQL integrity check failed.')
    try:
        with path.open('rb') as stream:
            contents = stream.read(MAX_ARTIFACT_BYTES + 1)
    except OSError:
        raise QueryError('Exercise SQL integrity check failed.') from None
    if len(contents) > MAX_ARTIFACT_BYTES:
        raise QueryError('Exercise SQL integrity check failed.')
    return contents


def _registry():
    """Recheck bytes on every call, including after an earlier successful call."""
    try:
        if set(ARTIFACT_HASHES) != {
                'sql/schema.json', 'sql/queries.json', 'sql/compiled-queries.json',
                'vendor/sqlean/provenance.json'}:
            raise ValueError('Incomplete artifact inventory')
        contents = {}
        for path, expected in ARTIFACT_HASHES.items():
            data = _read_artifact(path)
            if hashlib.sha256(data).hexdigest() != expected:
                raise ValueError('Changed artifact')
            contents[path] = data
        artifact = _strict_json(contents['sql/compiled-queries.json'])
        queries = _strict_json(contents['sql/queries.json'])
        if artifact['schemaVersion'] != 1 or queries['schemaVersion'] != 1:
            raise ValueError('Unsupported query registry')
        if artifact['inputs'] != {
                name: ARTIFACT_HASHES[name] for name in
                ('sql/schema.json', 'sql/queries.json', 'vendor/sqlean/provenance.json')}:
            raise ValueError('Unbound query input')
        result = {query['id']: query for query in artifact['queries']}
        if len(result) != len(artifact['queries']):
            raise ValueError('Repeated query ID')
        originals = {query['id']: query for query in queries['queries']}
        if set(result) != set(originals):
            raise ValueError('Unregistered query')
        for name, entry in result.items():
            if entry['parameters'] != originals[name]['parameters']:
                raise ValueError('Changed parameter registry')
        return result
    except (OSError, KeyError, TypeError, ValueError, UnicodeError):
        raise QueryError('Exercise SQL integrity check failed.') from None


def _parameters(parameters, values):
    if type(values) not in (tuple, list) or len(parameters) != len(values):
        raise QueryError('Invalid exercise SQL parameters.')
    for parameter, value in zip(parameters, values):
        kind = parameter['type']
        if kind == 'int':
            valid = type(value) is int and INT_MIN <= value <= INT_MAX
        elif kind == 'text':
            valid = type(value) is str and '\0' not in value
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


def execute(connection: sqlite3.Connection, query_id: str, params=()):
    """Execute one registered statement, returning its SQLite cursor."""
    registry = _registry()
    if type(query_id) is not str or query_id not in registry:
        raise QueryError('Unknown exercise SQL query.')
    entry = registry[query_id]
    values = _parameters(entry['parameters'], params)
    return connection.execute(entry['sql'], values)


def _expected_schema():
    sql_rows = []
    object_rows = []
    for table, ddl in zip(FIELDS, DDL):
        sql_rows.append(('table', table, table, ddl))
        object_rows.append(('table', table, table))
        indexes = 2 if table in ('exercises', 'solutions') else 1
        for number in range(1, indexes + 1):
            object_rows.append(('index', f'sqlite_autoindex_{table}_{number}', table))
    return sorted(object_rows), sorted(sql_rows)


def validate_schema(connection: sqlite3.Connection):
    """Reject any missing/changed table constraint or unexpected schema object."""
    objects, sql = _expected_schema()
    actual_objects = [tuple(row) for row in execute(connection, 'select_schema')]
    actual_sql = [tuple(row) for row in execute(connection, 'select_schema_sql')]
    if actual_objects != objects or actual_sql != sql:
        raise QueryError('Invalid exercise database schema.')


def create_schema(connection: sqlite3.Connection):
    """Create the fixed schema only in an empty database; never repair a schema."""
    if execute(connection, 'select_schema').fetchone() is not None:
        raise QueryError('Exercise database schema already exists.')
    connection.execute(CONTROLS[0])
    # SQLite ignores the setter inside an existing transaction. Never proceed
    # with silently disabled foreign keys in that case.
    if tuple(connection.execute(CONTROLS[1]).fetchone()) != (1,):
        raise QueryError('Exercise database foreign keys must be enabled.')
    owns_transaction = not connection.in_transaction
    if owns_transaction:
        connection.execute(CONTROLS[2])
    try:
        for statement in DDL:
            connection.execute(statement)
        validate_schema(connection)
        if owns_transaction:
            connection.commit()
    except Exception:
        if owns_transaction:
            connection.rollback()
        raise
