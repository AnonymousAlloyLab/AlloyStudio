"""Real Alloy-validated upload batches, immutable witnesses and rollback."""
from contextlib import contextmanager
from copy import deepcopy
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import patch

import admin_upload
from admin_auth import AuthError
import exercise_store as store
from runtime_dependencies import runtime_classpath
from test_sqlite_store import ROOT, fixture

SOURCE = ('module uploaded\r\n// unchanged UTF-8: λ 🙂\r\n'
          'sig Node { edge: set Node }\r\n'
          'pred inv1 {}\r\n'
          'private pred inv1C0 { some Node }\r\n'
          'pred inv1C1 { not no Node }\r\n'
          'pred inv2C0 { no iden & edge }\r\n'
          'pred inv2C1 { all n: Node | n not in n.edge }\r\n')


@contextmanager
def permitted():
    yield


class AdminStoreTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.prepared = admin_upload.prepare_upload(ROOT,dict(source=SOURCE,filename='uploaded.als',modelId='uploaded'))
        cls.metadata = [{'predicate':doc['predicate'],'title':doc['predicate']+' practice',
                         'question':'Inspect the relation and constrain this property.'}
                        for doc in cls.prepared['documents']]

    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.root = Path(self.folder.name)
        fixture(self.root)
        self.database = self.root / store.DATABASE_RELATIVE

    def tearDown(self):
        self.folder.cleanup()

    def publish(self, prepared=None, metadata=None, guard=permitted):
        return store.commit_upload(self.root,prepared or deepcopy(self.prepared),
                                   deepcopy(self.metadata) if metadata is None else metadata,guard=guard)

    def test_multiple_groups_publish_atomically_with_every_oracle_and_exact_original(self):
        snapshot = self.publish()
        self.assertEqual(snapshot.exercise_count,3)
        for doc in self.prepared['documents']:
            record = snapshot.exercises[doc['id']]
            self.assertEqual(snapshot.correct_pools[doc['id']],tuple(doc['oracleSolutions']))
            self.assertEqual(record['oracleBody'],doc['oracleSolutions'][0])
            self.assertEqual(record['predicate'],doc['predicate'])
            self.assertNotIn('pred inv1C0',record['environmentBefore']+record['environmentAfter'])
            self.assertNotIn('pred inv2C1',record['environmentBefore']+record['environmentAfter'])
        self.assertEqual(snapshot.exercises['uploaded-inv1']['starter'],'')
        self.assertEqual(snapshot.admin_uploads[0]['originalSource'],SOURCE)
        with sqlite3.connect(self.database) as db:
            source, metadata = db.execute("SELECT original_source,payload FROM auxiliary WHERE kind='adminUpload'").fetchone()
            self.assertEqual(source.encode('utf-8'),SOURCE.encode('utf-8'))
            self.assertNotIn('originalSource',json.loads(metadata))
            self.assertNotIn('some Node',metadata)
        self.assertEqual(store.load_store(self.root).admin_uploads,snapshot.admin_uploads)

    def test_final_authority_rejection_rolls_back_every_group_and_archive(self):
        @contextmanager
        def revoked():
            raise AuthError(401,'Session expired.')
            yield
        with self.assertRaises(AuthError):
            self.publish(guard=revoked)
        snapshot = store.load_store(self.root)
        self.assertEqual(snapshot.exercise_count,1)
        self.assertEqual(snapshot.admin_uploads,[])
        with sqlite3.connect(self.database) as db:
            self.assertEqual(db.execute("SELECT count(*) FROM exercises WHERE origin='authored'").fetchone()[0],0)

    def test_duplicate_id_in_later_group_does_not_publish_earlier_group(self):
        with patch('exercise_store.runtime_classpath',return_value=runtime_classpath(ROOT)):
            store.add_exercise(self.root,self.prepared['documents'][1])
        before = store.load_store(self.root)
        with self.assertRaisesRegex(store.StoreError,'already exists'):
            self.publish()
        after = store.load_store(self.root)
        self.assertEqual(after.catalogue,before.catalogue)
        self.assertNotIn('uploaded-inv1',after.exercises)
        self.assertEqual(after.admin_uploads,[])

    def test_review_cannot_change_code_names_or_omit_an_exercise(self):
        variants = [self.metadata[:1],
                    [dict(self.metadata[0],predicate='changed'),self.metadata[1]],
                    [dict(self.metadata[0],source='pred hacked {}'),self.metadata[1]],
                    [self.metadata[0],self.metadata[0]]]
        for metadata in variants:
            with self.subTest(metadata=metadata),self.assertRaises(store.StoreError):
                self.publish(metadata=metadata)
            self.assertEqual(store.load_store(self.root).exercise_count,1)

    def test_tampered_prepared_certificate_or_source_is_rejected_before_publication(self):
        changed = deepcopy(self.prepared)
        changed['certificates'][1]['bodySha256'][0] = '0'*64
        with self.assertRaises(store.StoreError):
            self.publish(prepared=changed)
        changed = deepcopy(self.prepared)
        changed['witness']['originalSource'] += '\n'
        with self.assertRaises(ValueError):
            self.publish(prepared=changed)
        self.assertEqual(store.load_store(self.root).exercise_count,1)

    def test_committed_source_or_body_span_corruption_fails_loading(self):
        self.publish()
        with sqlite3.connect(self.database) as db:
            row = db.execute("SELECT payload FROM auxiliary WHERE kind='adminUpload'").fetchone()[0]
            value = json.loads(row)
            value['groups'][0]['variants'][0]['bodyStartByte'] += 1
            db.execute("UPDATE auxiliary SET payload=? WHERE kind='adminUpload'",(json.dumps(value),))
        with self.assertRaises(store.StoreError):
            store.load_store(self.root)

    def test_missing_archive_cannot_leave_unwitnessed_exercises(self):
        self.publish()
        with sqlite3.connect(self.database) as db:
            db.execute("DELETE FROM auxiliary WHERE kind='adminUpload'")
        with self.assertRaises(store.StoreError):
            store.load_store(self.root)

    def test_removed_archive_cannot_be_disguised_as_unbound_reviewed_exercise(self):
        self.publish()
        with sqlite3.connect(self.database) as db:
            db.execute("DELETE FROM auxiliary WHERE kind='adminUpload'")
            for identifier, metadata in db.execute("SELECT id,metadata FROM exercises WHERE origin='authored'").fetchall():
                record = json.loads(metadata)
                record['preservation'] = {}
                db.execute('UPDATE exercises SET metadata=? WHERE id=?',(json.dumps(record),identifier))
        with self.assertRaises(store.StoreError):
            store.load_store(self.root)

    def test_backup_roundtrips_added_groups_and_original_upload_without_json(self):
        self.publish()
        for path in (self.root / 'exercises').glob('*.json'):
            path.unlink()
        backup = self.root / 'snapshot.sqlite3'
        snapshot = store.backup_store(self.root,backup)
        self.assertEqual(snapshot.exercise_count,3)
        self.assertEqual(snapshot.admin_uploads[0]['originalSource'],SOURCE)
        target = self.root / 'relocated'
        restored = store.restore_store(target,backup)
        self.assertEqual(restored.admin_uploads,snapshot.admin_uploads)
        self.assertEqual(restored.correct_pools,snapshot.correct_pools)

    def test_empty_provided_starter_is_preserved_while_empty_oracle_is_rejected(self):
        self.assertEqual(self.prepared['documents'][0]['starter'],'')
        invalid = dict(self.prepared['documents'][0],oracleSolutions=[''])
        with self.assertRaises(store.StoreError):
            store.normalize_import(invalid)
