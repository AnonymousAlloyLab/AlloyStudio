"""Real Alloy approval and both distance engines against an isolated SQLite store."""
from pathlib import Path
import json
import tempfile
import unittest
from unittest.mock import patch

import candidate_store as cache
import exercise_store as store
from runtime_dependencies import runtime_classpath, run_engine
from server import project_behavior
from test_admin_candidates import allowed
from test_sqlite_store import ROOT, fixture


class CandidateAlloyIntegrationTests(unittest.TestCase):
    def setUp(self):
        scratch=ROOT/'build/admin-features/alloy-regression'
        scratch.mkdir(parents=True,exist_ok=True)
        self.folder=tempfile.TemporaryDirectory(dir=scratch)
        self.root=Path(self.folder.name)
        fixture(self.root)
        self.snapshot=store.load_store(self.root)
        self.record=next(iter(self.snapshot.exercises.values()))
        self.body='all n: Node | n not in n.adj'
        self.classpath=runtime_classpath(ROOT)
        self.path_patch=patch('exercise_store.runtime_classpath',return_value=self.classpath)
        self.path_patch.start()

    def tearDown(self):
        self.path_patch.stop()
        self.folder.cleanup()

    def engine(self,main,payload):
        completed=run_engine(['java','-Dfile.encoding=UTF-8','-Xmx256m','-XX:ActiveProcessorCount=2',
                              '-cp',self.classpath,main],input=json.dumps(payload),capture_output=True,
                             text=True,encoding='utf-8',timeout=60,root=self.root)
        self.assertEqual(completed.returncode,0)
        return store.parse_json(completed.stdout)

    def feedback(self,snapshot,metric):
        record=self.record
        return self.engine('live.LiveFeedback',dict(studentSource=store.model(record,self.body),
            referenceBodies=snapshot.correct_pools[record['id']],
            referencePrefix=record['environmentBefore']+record['predicateHeader']+'{\n',
            referenceSuffix='\n}'+record['environmentAfter'],predicate=record['predicate'],metric=metric))

    def test_real_bounded_candidate_approval_changes_both_nearest_correct_distances_to_zero(self):
        record=self.record
        behavior=project_behavior(self.engine('live.BehaviorFeedback',dict(studentSource=store.model(record,self.body),
            studentBody=self.body,oracleSource=store.model(record,record['oracleBody']),predicate=record['predicate'])))
        candidate=cache.capture(self.root,record,store.exercise_version(record),self.snapshot.pool_tokens[record['id']],self.body,behavior)
        self.assertIsNotNone(candidate)
        for metric in ('canonical','ast'):
            before=self.feedback(self.snapshot,metric)
            self.assertEqual(before['status'],'ok')
            self.assertGreater(before['distance'],0)
        certificate=store.prepare_approval(self.root,record,self.body)
        self.assertEqual(certificate['result']['scope'],5)
        self.assertTrue(certificate['result']['moduleFacts'])
        result=cache.approve(self.root,candidate['id'],candidate['candidateVersion'],candidate['exerciseVersion'],certificate,guard=allowed)
        self.assertEqual(result.pools_document,self.snapshot.pools_document)
        for metric in ('canonical','ast'):
            after=self.feedback(result,metric)
            self.assertEqual(after['status'],'ok')
            self.assertEqual(after['distance'],0)

    def test_real_malformed_and_inequivalent_candidates_never_change_pool(self):
        for candidate in ('missing_name','some Node'):
            with self.assertRaises(store.StoreError):store.prepare_approval(self.root,self.record,candidate)
        self.assertEqual(store.load_store(self.root).correct_pools,self.snapshot.correct_pools)

    def test_real_equivalence_uses_module_facts_and_rejects_inconsistent_facts(self):
        record=dict(self.record,environmentBefore=self.record['environmentBefore']+'fact NoSelf { no iden & adj }\n')
        certificate=store.validate_import(self.root,store._approval_document(record,'some Node or no Node',5))
        self.assertTrue(certificate['result']['moduleFacts'])
        self.assertTrue(certificate['result']['factsSatisfiable'])
        inconsistent=dict(self.record,environmentBefore=self.record['environmentBefore']+'fact Impossible { some Node and no Node }\n')
        with self.assertRaises(store.StoreError):
            store.validate_import(self.root,store._approval_document(inconsistent,self.body,5))


if __name__=='__main__':unittest.main()
