"""SQLite authority, atomic imports and private deployment boundary witnesses."""
import copy
import json
from pathlib import Path
import shutil
import sqlite3
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import exercise_store as store
import exercise_sql
from runtime_dependencies import runtime_classpath
from scripts.import_exercises import build_catalogue
from scripts.import_correct_pools import build_document

ROOT = Path(__file__).resolve().parents[1]
MODEL = '''sig Node { adj: set Node }
pred inv1 { some Node }
pred inv1c { no iden & adj }
check correct { inv1 <=> inv1c }
pred under { inv1 and !inv1c }
pred over { !inv1 and inv1c }
run over
run under
'''


def fixture(root):
    corpus = root / 'source/classified-data/graphs/under'
    corpus.mkdir(parents=True)
    (corpus / 'fixture_inv1.als').write_text(MODEL)
    correct = corpus.parent / 'correct'
    correct.mkdir()
    (correct / 'fixture_inv1.als').write_text(MODEL.replace('pred inv1 { some Node }','pred inv1 { no iden & adj }'))
    catalogue = build_catalogue(root / 'source')
    pools = build_document(catalogue, root / 'source')
    (root / 'exercises').mkdir()
    (root / 'exercises/catalogue.json').write_text(json.dumps(catalogue))
    (root / 'exercises/correct-pools.json').write_text(json.dumps(pools))
    store.migrate_legacy(root)
    directory = root / 'build/engine/classes/live'
    directory.mkdir(parents=True)
    shutil.copy2(ROOT / 'build/engine/classes/live/ExerciseValidator.class', directory)
    library = root / 'vendor/acgn/lib'
    library.mkdir(parents=True)
    shutil.copy2(ROOT / 'vendor/acgn/lib/alloy.jar',library)
    return catalogue, pools


def authored(**updates):
    result = dict(id='private-one',title='No self edges',group='Private',predicate='inv',
                  description='Prevent self loops.',environmentBefore='sig Node { adj: set Node }\n',
                  environmentAfter='\n',predicateHeader='pred inv ',starter='some Node',
                  oracleSolutions=['no iden & adj','all n: Node | n not in n.adj'],
                  correctSolutions=['no n: Node | n in n.adj'])
    result.update(updates)
    return result


class SQLiteStoreTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.catalogue, self.pools = fixture(self.root)
        self.path = self.root / store.DATABASE_RELATIVE
        self.classpath = patch('exercise_store.runtime_classpath', return_value=runtime_classpath(ROOT))
        self.classpath.start()

    def tearDown(self):
        self.classpath.stop()
        self.temporary.cleanup()

    def test_migration_parity_and_normalized_columns(self):
        result = store.load_store(self.root)
        self.assertEqual(result.catalogue, self.catalogue)
        self.assertEqual(result.pools_document, self.pools)
        with sqlite3.connect(self.path) as connection:
            self.assertEqual(connection.execute('SELECT body FROM solutions ORDER BY ordinal').fetchall(),
                             [(x['body'],) for x in self.pools['pools'][0]['candidates']])
            self.assertNotIn('oracleBody', connection.execute('SELECT metadata FROM exercises').fetchone()[0])

    def test_bundled_seed_exact_order_and_all_7731_references(self):
        snapshot = store.load_store(ROOT)
        self.assertEqual(snapshot.catalogue, json.loads((ROOT / 'exercises/catalogue.json').read_bytes()))
        self.assertEqual(snapshot.pools_document, json.loads((ROOT / 'exercises/correct-pools.json').read_bytes()))
        self.assertEqual((snapshot.exercise_count,snapshot.candidate_count),(181,7731))
        self.assertTrue(all(p['candidates'][-1]['kind']=='oracle' for p in snapshot.pools_document['pools']))

    def test_sqlite_authoritative_without_legacy_json(self):
        for path in (self.root / 'exercises').glob('*.json'):
            path.unlink()
        self.assertEqual(store.ensure_store(self.root).exercise_count,1)
        (self.root / 'exercises/catalogue.json').write_text('invalid ignored data')
        self.assertEqual(store.ensure_store(self.root).exercise_count,1)

    def test_existing_invalid_db_never_falls_back_or_overwrites(self):
        self.path.write_bytes(b'not SQLite')
        for operation in (store.load_store,store.ensure_store,store.migrate_legacy):
            with self.assertRaises((store.StoreError,sqlite3.Error)):
                operation(self.root)
            self.assertEqual(self.path.read_bytes(),b'not SQLite')

    def test_readonly_web_connection(self):
        connection = store.connect(self.path)
        try:
            with self.assertRaises(sqlite3.OperationalError):
                connection.execute("UPDATE exercises SET title='changed'")
        finally:
            connection.close()

    def test_multiple_oracles_are_atomic_and_primary_is_first(self):
        report = store.add_exercise(self.root,authored())
        self.assertEqual(report['oracleCount'],2)
        result = store.load_store(self.root)
        self.assertEqual(result.exercises['private-one']['oracleBody'],authored()['oracleSolutions'][0])
        self.assertEqual(result.correct_pools['private-one'],tuple(authored()['oracleSolutions']+authored()['correctSolutions']))
        self.assertEqual(result.exercise_count,2)
        with sqlite3.connect(self.path) as connection:
            certificate = json.loads(connection.execute("SELECT validation FROM exercises WHERE id='private-one'").fetchone()[0])
        self.assertEqual(certificate['kind'],'alloy-bounded-equivalence')
        self.assertTrue(certificate['result']['factsSatisfiable'])

    def test_duplicate_id_never_replaces(self):
        store.add_exercise(self.root,authored())
        before = store.load_store(self.root).catalogue
        with self.assertRaisesRegex(store.StoreError,'already exists'):
            store.add_exercise(self.root,authored(title='overwrite'))
        self.assertEqual(before,store.load_store(self.root).catalogue)

    def test_invalid_later_oracle_rejected_before_publication(self):
        for bad in ('no Node','unknown relation','some Node } fact injected { no Node } pred extra { some Node'):
            with self.subTest(kind=bad[:12]),self.assertRaises(store.StoreError):
                store.add_exercise(self.root,authored(oracleSolutions=['some Node',bad],correctSolutions=[]))
            self.assertEqual(store.load_store(self.root).exercise_count,1)

    def test_import_failure_rolls_back_all_rows(self):
        execute = exercise_sql.execute
        def fail(connection, identifier, params=()):
            if identifier == 'insert_solutions' and params[1] == 1:
                raise sqlite3.OperationalError('constructed write interruption')
            return execute(connection,identifier,params)
        before = self.path.read_bytes()
        with patch.object(exercise_sql,'execute',side_effect=fail),self.assertRaises(store.StoreError):
            store.add_exercise(self.root,authored())
        self.assertEqual(store.load_store(self.root).exercise_count,1)
        self.assertEqual(self.path.read_bytes(),before)

    def test_alloy_timeout_and_unknown_fail_closed(self):
        for effect in (subprocess.TimeoutExpired('java',60),None):
            with patch('exercise_store.subprocess.run',side_effect=effect,
                       return_value=subprocess.CompletedProcess([],0,'{"status":"unknown"}','')):
                with self.assertRaises(store.StoreError):
                    store.add_exercise(self.root,authored())
        self.assertEqual(store.load_store(self.root).exercise_count,1)

    def test_unknown_fields_duplicate_json_keys_and_types(self):
        for changes in (dict(secret='x'),dict(equivalenceScope=True),dict(equivalenceScope=9),
                        dict(oracleSolutions=[]),dict(id='../bad'),dict(predicateHeader='pred inv[x: Node] '),
                        dict(title='\ud800'),dict(starter='a\0b'),dict(oracleSolutions=['some Node','some   Node'])):
            with self.subTest(fields=list(changes)),self.assertRaises(store.StoreError):
                store.normalize_import(authored(**changes))
        with self.assertRaises(store.StoreError):
            store.parse_json('{"id":"a","id":"b"}')

    def test_aggregate_engine_limit_and_individual_bounds(self):
        # All references meet individual 8KiB limits but the collection exceeds1MiB.
        references = ['some Node // ' + str(i) + 'x' * 8000 for i in range(256)]
        with self.assertRaisesRegex(store.StoreError,'aggregate'):
            store.normalize_import(authored(oracleSolutions=references,correctSolutions=[]))
        for length in (8193,10000):
            with self.assertRaises(store.StoreError):
                store.normalize_import(authored(starter='x'*length))
        path = self.root / 'too-large.json'
        path.write_bytes(b' '*(store.MAX_IMPORT+1))
        with self.assertRaises(store.StoreError):
            store.read_import(path)

    def test_unexpected_trigger_view_index_and_changed_constraint_rejected(self):
        original = self.path.read_bytes()
        for query in ("CREATE TRIGGER rewrite AFTER INSERT ON exercises BEGIN UPDATE exercises SET title='changed'; END",
                      'CREATE VIEW private_view AS SELECT * FROM solutions',
                      'CREATE INDEX unregistered ON solutions(body)'):
            self.path.write_bytes(original)
            with sqlite3.connect(self.path) as connection:
                connection.execute(query)
            with self.assertRaises(store.StoreError):
                store.load_store(self.root)
        self.path.write_bytes(original)
        with sqlite3.connect(self.path) as connection:
            connection.execute('PRAGMA writable_schema=ON')
            connection.execute("UPDATE sqlite_master SET sql=replace(sql,'ordinal >= 0','ordinal >= -1') WHERE name='solutions'")
        with self.assertRaises(store.StoreError):
            store.load_store(self.root)

    def test_import_read_bound_survives_growth_after_size_precheck(self):
        path = self.root / 'growing-import.json'
        path.write_text(json.dumps(authored()))
        inspected = path.stat()
        # Deterministic witness of a producer growing the file after inspection.
        with path.open('ab') as stream:
            stream.write(b' ' * store.MAX_IMPORT)
        original_stat = Path.stat
        def stale_size(candidate, *args, **kwargs):
            return inspected if candidate == path else original_stat(candidate, *args, **kwargs)
        with patch.object(Path,'stat',autospec=True,side_effect=stale_size):
            with self.assertRaisesRegex(store.StoreError,'byte limit'):
                store.read_import(path)

    def test_corrupt_schema_version_orphan_primary_and_ordinals(self):
        original = self.path.read_bytes()
        queries = ["UPDATE metadata SET value='\"9\"' WHERE key='schemaVersion'",
                   "UPDATE solutions SET exercise_id='orphan'", "UPDATE exercises SET primary_token='absent'",
                   'UPDATE solutions SET ordinal=99',"UPDATE solutions SET body='no Node'"]
        for query in queries:
            self.path.write_bytes(original)
            with sqlite3.connect(self.path) as connection:
                connection.execute(query)
            with self.assertRaises(store.StoreError):
                store.load_store(self.root)

    def test_linked_database_parent_and_import_rejected(self):
        link = self.root / 'alias'
        link.symlink_to(self.root,target_is_directory=True)
        for path in (link / store.DATABASE_RELATIVE,):
            with self.assertRaises(store.StoreError):
                store.load_store(self.root,path)
        source = self.root / 'input.json'
        source.write_text(json.dumps(authored()))
        linked = self.root / 'input-link.json'
        linked.symlink_to(source)
        with self.assertRaises(store.StoreError):
            store.read_import(linked)

    def test_snapshot_preserves_added_exercises_and_refuses_overwrite(self):
        store.add_exercise(self.root,authored())
        snapshot = self.root / 'snapshot.sqlite3'
        result = store.backup_store(self.root,snapshot)
        self.assertEqual(result.exercise_count,2)
        with self.assertRaises(store.StoreError):
            store.backup_store(self.root,snapshot)
        other = self.root / 'relocated'
        restored = store.restore_store(other,snapshot,expected_exercises=2,expected_pools=2)
        self.assertEqual(restored.correct_pools,result.correct_pools)
        with self.assertRaises(store.StoreError):
            store.restore_store(other,snapshot)

    def test_backup_busy_has_monotonic_deadline(self):
        connection = sqlite3.connect(self.path)
        connection.execute('BEGIN EXCLUSIVE')
        destination = self.root / 'busy.sqlite3'
        try:
            with patch.object(store,'LOCK_SECONDS',0.1), self.assertRaisesRegex(store.StoreError,'timed out'):
                store.backup_store(self.root,destination)
        finally:
            connection.rollback()
            connection.close()
        self.assertFalse(destination.exists())

    def test_loader_reads_one_transaction_during_concurrent_commit(self):
        with sqlite3.connect(self.path) as writer:
            writer.execute('PRAGMA journal_mode=WAL')
        execute = exercise_sql.execute
        changed = False
        def race(connection,identifier,params=()):
            nonlocal changed
            if identifier == 'select_exercises' and not changed:
                changed = True
                with sqlite3.connect(self.path) as writer:
                    writer.execute("UPDATE exercises SET title='new generation'")
            return execute(connection,identifier,params)
        with patch.object(exercise_sql,'execute',side_effect=race):
            old = store.load_store(self.root)
        self.assertNotEqual(old.exercises['graphs-inv1']['title'],'new generation')
        self.assertEqual(store.load_store(self.root).exercises['graphs-inv1']['title'],'new generation')

    def test_sql_looking_title_remains_bound_data(self):
        title = "x'); DELETE FROM exercises; --"
        store.add_exercise(self.root,authored(title=title))
        result = store.load_store(self.root)
        self.assertEqual(result.exercise_count,2)
        self.assertEqual(result.exercises['private-one']['title'],title)


if __name__ == '__main__':
    unittest.main()
