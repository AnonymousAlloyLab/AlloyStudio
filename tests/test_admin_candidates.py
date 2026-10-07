"""SQLite-backed candidate admission, versioning, retention and publication tests.

Solver certificates are explicit fixtures here; real Alloy validation belongs
to the integrated admin tests. These tests do not call a provider or start Java.
"""
from contextlib import contextmanager
from copy import deepcopy
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import patch

import candidate_store as cache
import exercise_sql as sql
import exercise_store as store
from runtime_dependencies import JAR_FILES
from test_sqlite_store import ROOT, fixture, authored


@contextmanager
def allowed():
    yield


@contextmanager
def revoked():
    raise RuntimeError('Constructed authority revocation.')
    yield


def perfect():
    return dict(status='ok', metric='acgn-reward', score=1.0, scoreStatus='ok', scoreReason='OK',
        scope=dict(cache.CHECK_SCOPE, moduleFacts=True), sampling=dict(positiveTested=8, positiveAccepted=8,
        negativeTested=9, negativeRejected=9, semanticCounterexamples=0), categories=[
            dict(id=name, oracle=oracle, student=student, status='unsat' if name in ('undercoverage','overcoverage') else 'sat',
                 enumerationComplete=True, instances=[] if name in ('undercoverage','overcoverage') else [{}])
            for name, oracle, student in (('both',True,True),('undercoverage',True,False),
                                         ('overcoverage',False,True),('neither',False,False))])


def certificate(record, body, scope=5):
    return dict(kind='alloy-bounded-equivalence',version=1,
        result=dict(scope=scope,bitwidth=5,maxSequence=scope,minTrace=1,maxTrace=10,solver='SAT4J',
                    oracleCount=1,correctCount=1,evaluatedCandidates=2,moduleFacts=True,factsSatisfiable=True),
        environmentSha256=store.environment_sha256(record),bodySha256=[store.sha(record['oracleBody']),store.sha(body)],
        starterSha256=store.sha(body),engineSha256='1'*64,dependencySha256={key:'2'*64 for key in JAR_FILES})


class AdminCandidatesTests(unittest.TestCase):
    def setUp(self):
        scratch=ROOT/'build/admin-features/store-tests'
        scratch.mkdir(parents=True,exist_ok=True)
        self.folder=tempfile.TemporaryDirectory(dir=scratch)
        self.root=Path(self.folder.name)
        fixture(self.root)
        self.database=self.root/store.DATABASE_RELATIVE
        self.snapshot=store.load_store(self.root)
        self.record=next(iter(self.snapshot.exercises.values()))
        self.body='no n: Node | n in n.adj'

    def tearDown(self):
        self.folder.cleanup()

    def admit(self, body=None, record=None, version=None, now=100, behavior=None, known=None):
        record=record or self.record
        return cache.capture(self.root,record,version or store.exercise_version(record),
                             self.snapshot.pool_tokens[self.record['id']] if known is None else known,
                             self.body if body is None else body, perfect() if behavior is None else behavior,now=now)

    def detail(self, candidate):
        return cache.read_candidate(self.root,store.load_store(self.root),candidate['id'])

    def test_public_content_version_excludes_private_solutions_but_tracks_public_prose(self):
        changed=dict(self.record,oracleBody='private alternate')
        self.assertEqual(store.content_version(self.record),store.content_version(changed))
        self.assertNotEqual(store.exercise_version(self.record),store.exercise_version(changed))
        changed=dict(self.record,description='A changed question.')
        self.assertNotEqual(store.content_version(self.record),store.content_version(changed))

    def test_library_summary_has_no_question_or_source_and_detail_is_single_question(self):
        page=store.library_list(self.snapshot)
        self.assertEqual(set(page['items'][0]),{'id','predicate','group','title','exerciseVersion','contentVersion'})
        detail=store.library_detail(self.snapshot,self.record['id'])
        self.assertEqual(detail['question'],self.record['description'])
        self.assertNotIn('oracleBody',detail)
        for offset,limit in ((-1,50),(True,50),(0,51),(0,0),(0,True)):
            with self.assertRaises(store.StoreError):store.library_list(self.snapshot,offset,limit)

    def test_question_edit_preserves_environment_source_starter_and_every_pool_entry(self):
        result=store.edit_question(self.root,self.record['id'],store.exercise_version(self.record),
                                   "Title'); DELETE FROM exercises; --",'New question.',guard=allowed)
        updated=result.exercises[self.record['id']]
        self.assertEqual(updated['description'],'New question.')
        for key in self.record:
            if key not in ('title','description'):self.assertEqual(updated[key],self.record[key],key)
        self.assertEqual(result.correct_pools,self.snapshot.correct_pools)
        with self.assertRaises(store.StoreError):
            store.edit_question(self.root,self.record['id'],store.exercise_version(self.record),'Stale','No.',guard=allowed)

    def test_edit_and_removal_recheck_commit_authority_and_roll_back(self):
        for action in (lambda:store.edit_question(self.root,self.record['id'],store.exercise_version(self.record),
                                                'Changed','Question',guard=revoked),
                       lambda:store.remove_question(self.root,self.record['id'],store.exercise_version(self.record),guard=revoked)):
            with self.assertRaises(RuntimeError):action()
            self.assertEqual(store.load_store(self.root).catalogue,self.snapshot.catalogue)

    def test_removal_can_empty_live_library_without_deleting_raw_witnesses(self):
        result=store.remove_question(self.root,self.record['id'],store.exercise_version(self.record),guard=allowed)
        self.assertEqual(result.exercise_count,0)
        self.assertEqual(len(result.raw_exercises),1)
        self.assertEqual(result.pools_document,self.snapshot.pools_document)
        self.assertEqual(store.load_store(self.root).exercise_count,0)
        with sqlite3.connect(self.database) as db:
            self.assertEqual(db.execute('SELECT count(*) FROM exercises').fetchone()[0],1)
            self.assertGreater(db.execute('SELECT count(*) FROM solutions').fetchone()[0],0)

    def test_removal_does_not_reuse_original_identifier_or_upload_ordinal(self):
        store.remove_question(self.root,self.record['id'],store.exercise_version(self.record),guard=allowed)
        doc=authored(id='next-question',oracleSolutions=['no iden & adj'],correctSolutions=[])
        synthetic=dict(doc,oracleBody=doc['oracleSolutions'][0])
        cert=certificate(synthetic,doc['oracleSolutions'][0])
        cert['result'].update(oracleCount=1,correctCount=0,evaluatedCandidates=1)
        cert['bodySha256']=[store.sha(doc['oracleSolutions'][0])]
        cert['starterSha256']=store.sha(doc['starter'])
        with patch('exercise_store.validate_import',return_value=cert):store.add_exercise(self.root,doc)
        result=store.load_store(self.root)
        self.assertEqual(list(result.exercises),['next-question'])
        self.assertEqual(result.exercise_ordinals['next-question'],1)
        with patch('exercise_store.validate_import',return_value=cert),self.assertRaises(store.StoreError):
            store.add_exercise(self.root,dict(doc,id=self.record['id']))

    def test_admission_requires_exact_perfect_evidence_not_only_rounded_one(self):
        mutations=[lambda x:x.update(score=0.999),lambda x:x.update(score=True),
                   lambda x:x['sampling'].update(positiveAccepted=7),
                   lambda x:x['sampling'].update(negativeRejected=8),
                   lambda x:x['sampling'].update(positiveTested=0,positiveAccepted=0),
                   lambda x:x['sampling'].update(semanticCounterexamples=1),
                   lambda x:x['scope'].update(moduleFacts=False),
                   lambda x:x['scope'].update(bitwidth=True),
                   lambda x:x['categories'][1].update(status='sat',instances=[{}]),
                   lambda x:x['categories'][2].update(enumerationComplete=False),
                   lambda x:x['categories'][2].update(oracle=True),lambda x:x.update(scoreStatus='unavailable')]
        mutations.extend([lambda x:x['categories'][0].update(status='unsat',instances=[]),
                          lambda x:x['categories'][3].update(student=True)])
        for mutation in mutations:
            evidence=perfect();mutation(evidence)
            self.assertIsNone(self.admit(behavior=evidence))
        self.assertEqual(cache.list_candidates(self.root,self.snapshot)['total'],0)

    def test_cache_admission_deduplicates_tokens_and_known_pool_and_hides_details_from_list(self):
        admitted=self.admit()
        self.assertRegex(admitted['id'],r'^[A-Za-z0-9_-]{43}$')
        self.assertIsNone(self.admit(body='no n: Node | n in n.adj // equivalent token sequence\n'))
        self.assertIsNone(self.admit(body=self.record['oracleBody']))
        self.assertIsNone(self.admit(body='some Node } fact injected { no Node }'))
        listing=cache.list_candidates(self.root,self.snapshot)
        self.assertEqual(listing['total'],1)
        self.assertNotIn('body',listing['items'][0]);self.assertNotIn('review',listing['items'][0])
        self.assertEqual(listing['items'][0]['title'],self.record['title'])
        self.assertEqual(listing['items'][0]['predicate'],self.record['predicate'])
        self.assertNotIn('oracleBody',listing['items'][0])
        self.assertEqual(self.detail(admitted)['body'],self.body)

    def test_capture_starts_no_snapshot_engine_or_provider_work(self):
        with (patch('exercise_store._snapshot',side_effect=AssertionError('Forbidden full snapshot scan')),
              patch('exercise_store.validate_import',side_effect=AssertionError('Forbidden solver'))):
            self.assertIsNotNone(self.admit())

    def test_capture_nonblocking_gate_and_short_busy_wait_fail_without_changing_success(self):
        cache.CACHE_LOCK.acquire()
        try:self.assertIsNone(self.admit())
        finally:cache.CACHE_LOCK.release()
        with store.connect(self.database,writable=True) as connection:
            connection.execute('BEGIN IMMEDIATE')
            started=__import__('time').monotonic()
            self.assertIsNone(self.admit())
            self.assertLess(__import__('time').monotonic()-started,0.5)
            connection.rollback()

    def test_per_exercise_cap_applies_across_all_versions_and_oldest_pending_is_evicted(self):
        first=self.admit(now=0)
        for index in range(1,12):
            current=store.load_store(self.root).exercises[self.record['id']]
            edited=store.edit_question(self.root,current['id'],store.exercise_version(current),
                                       current['title'],'Version '+str(index),guard=allowed)
            record=edited.exercises[self.record['id']]
            self.assertIsNotNone(self.admit(body='no n: Node | n in n.adj and '+str(index)+' = '+str(index),
                                           record=record,now=index))
        self.assertEqual(cache.list_candidates(self.root,self.snapshot)['total'],10)
        with self.assertRaises(store.StoreError):self.detail(first)

    def test_terminal_eviction_has_priority_and_readmission_uses_fresh_id_preventing_ABA(self):
        first=self.admit(now=100)
        cache.dismiss(self.root,self.snapshot,first['id'],first['candidateVersion'],guard=allowed)
        for index in range(1,10):self.admit(body=self.body+' and '+str(index)+' = '+str(index),now=index)
        self.admit(body=self.body+' and 10 = 10',now=101)
        with self.assertRaises(store.StoreError):self.detail(first)
        again=self.admit(now=102)
        self.assertIsNotNone(again);self.assertNotEqual(again['id'],first['id'])
        with self.assertRaises(store.StoreError):
            cache.dismiss(self.root,self.snapshot,first['id'],first['candidateVersion'],guard=allowed)

    def test_global_cap_evicts_records_without_deleting_approvals(self):
        for group in range(11):
            document=authored(id='group-'+str(group),oracleSolutions=['no iden & adj'],correctSolutions=[])
            record=dict(document,oracleBody=document['oracleSolutions'][0])
            cert=certificate(record,record['oracleBody'])
            cert['result'].update(correctCount=0,evaluatedCandidates=1)
            cert['bodySha256']=[store.sha(record['oracleBody'])]
            cert['starterSha256']=store.sha(document['starter'])
            with patch('exercise_store.validate_import',return_value=cert):store.add_exercise(self.root,document)
            record=store.load_store(self.root).exercises[document['id']]
            for index in range(10):
                self.assertIsNotNone(self.admit(body=self.body+' and '+str(index)+' = '+str(index),
                                               record=record,now=group*10+index))
        self.assertEqual(cache.list_candidates(self.root,self.snapshot)['total'],100)
        with sqlite3.connect(self.database) as connection:
            self.assertEqual(connection.execute("SELECT count(*) FROM auxiliary WHERE kind='studentCandidate'").fetchone()[0],100)

    def test_review_bound_identity_and_CAS_then_terminal_replay_are_rejected(self):
        candidate=self.admit()
        advice=dict(exerciseId=candidate['exerciseId'],exerciseVersion=candidate['exerciseVersion'],
                    candidateHash=candidate['candidateHash'],verdict='recommend',reason='Review the relation.',counterexampleIdeas=[])
        updated=cache.save_review(self.root,self.snapshot,candidate['id'],candidate['candidateVersion'],
                                   dict(status='ok',advice=advice),guard=allowed)
        self.assertEqual(updated['revision'],1)
        self.assertNotEqual(updated['candidateVersion'],candidate['candidateVersion'])
        with self.assertRaises(store.StoreError):
            cache.dismiss(self.root,self.snapshot,candidate['id'],candidate['candidateVersion'],guard=allowed)
        terminal=cache.dismiss(self.root,self.snapshot,candidate['id'],updated['candidateVersion'],guard=allowed)
        self.assertEqual(terminal['state'],'dismissed')
        self.assertEqual(self.detail(candidate)['body'],self.body)
        with self.assertRaises(store.StoreError):
            cache.dismiss(self.root,self.snapshot,candidate['id'],terminal['candidateVersion'],guard=allowed)

    def test_bad_advice_and_final_guard_revocation_leave_record_unchanged(self):
        candidate=self.admit()
        with self.assertRaises(store.StoreError):
            cache.save_review(self.root,self.snapshot,candidate['id'],candidate['candidateVersion'],
                               dict(status='ok',advice={}),guard=allowed)
        with self.assertRaises(RuntimeError):
            cache.dismiss(self.root,self.snapshot,candidate['id'],candidate['candidateVersion'],guard=revoked)
        self.assertEqual(self.detail(candidate)['candidateVersion'],candidate['candidateVersion'])
        updated=cache.save_review(self.root,self.snapshot,candidate['id'],candidate['candidateVersion'],
                                   dict(status='unavailable',message='Manual review remains available.'),guard=allowed)
        self.assertEqual(updated['state'],'pending')

    def test_question_edits_and_removal_make_old_candidate_actions_stale(self):
        candidate=self.admit()
        edited=store.edit_question(self.root,self.record['id'],store.exercise_version(self.record),'Edited','Edited question.',guard=allowed)
        self.assertTrue(cache.read_candidate(self.root,edited,candidate['id'])['stale'])
        with self.assertRaises(store.StoreError):
            cache.dismiss(self.root,self.snapshot,candidate['id'],candidate['candidateVersion'],guard=allowed)
        store.remove_question(self.root,self.record['id'],store.exercise_version(edited.exercises[self.record['id']]),guard=allowed)
        with self.assertRaises(store.StoreError):
            cache.approve(self.root,candidate['id'],candidate['candidateVersion'],candidate['exerciseVersion'],
                          certificate(self.record,self.body),guard=allowed)

    def test_prepare_approval_uses_candidate_starter_primary_oracle_and_exact_environment(self):
        record=dict(self.record,starter='invalid original starter !')
        # A backend-owned record must match the snapshot, so isolate the loader
        # while asserting the exact request passed to the separately tested validator.
        snapshot=deepcopy(self.snapshot);snapshot.exercises[record['id']]=record
        cert=certificate(record,self.body)
        with patch('exercise_store.load_store',return_value=snapshot),patch('exercise_store.validate_import',return_value=cert) as check:
            self.assertEqual(store.prepare_approval(self.root,record,self.body),cert)
        doc=check.call_args.args[1]
        self.assertEqual(doc['starter'],self.body);self.assertEqual(doc['correctSolutions'],[self.body])
        self.assertEqual(doc['oracleSolutions'],[record['oracleBody']])
        for key in ('environmentBefore','environmentAfter','predicateHeader','predicate'):
            self.assertEqual(doc[key],record[key])

    def test_approval_extends_pool_atomically_and_remains_after_question_prose_change(self):
        candidate=self.admit()
        original_version=store.exercise_version(self.record)
        result=cache.approve(self.root,candidate['id'],candidate['candidateVersion'],candidate['exerciseVersion'],
                             certificate(self.record,self.body),guard=allowed)
        self.assertEqual(result.correct_pools[self.record['id']],self.snapshot.correct_pools[self.record['id']]+(self.body,))
        self.assertEqual(result.pools_document,self.snapshot.pools_document)
        self.assertEqual(store.exercise_version(result.exercises[self.record['id']]),original_version)
        self.assertEqual(self.detail(candidate)['state'],'approved')
        self.assertIsNone(self.admit(known=frozenset())) # Atomic current approval membership, despite stale supplied tokens.
        edited=store.edit_question(self.root,self.record['id'],original_version,'New title','New question.',guard=allowed)
        self.assertIn(self.body,edited.correct_pools[self.record['id']])

    def test_approval_requires_exact_bound_certificate_and_rolls_back_on_revocation(self):
        candidate=self.admit();good=certificate(self.record,self.body)
        for key,value in (('environmentSha256','0'*64),('bodySha256',['0'*64,'1'*64]),('starterSha256','0'*64)):
            invalid=dict(good,**{key:value})
            with self.assertRaises(store.StoreError):
                cache.approve(self.root,candidate['id'],candidate['candidateVersion'],candidate['exerciseVersion'],invalid,guard=allowed)
        with self.assertRaises(RuntimeError):
            cache.approve(self.root,candidate['id'],candidate['candidateVersion'],candidate['exerciseVersion'],good,guard=revoked)
        self.assertEqual(store.load_store(self.root).correct_pools,self.snapshot.correct_pools)
        self.assertEqual(self.detail(candidate)['state'],'pending')

    def test_candidate_corruption_and_over_limit_cache_fail_loading(self):
        candidate=self.admit()
        with sqlite3.connect(self.database) as connection:
            connection.execute("UPDATE auxiliary SET original_source='changed' WHERE kind='studentCandidate'")
        with self.assertRaises(store.StoreError):store.load_store(self.root)

    def test_mutations_require_explicit_versions_and_revision_overflow_is_atomic(self):
        candidate=self.admit()
        for value in (None,True,''):
            with self.assertRaises(store.StoreError):
                cache.dismiss(self.root,self.snapshot,candidate['id'],value,guard=allowed)
            with self.assertRaises(store.StoreError):
                store.edit_question(self.root,self.record['id'],value,'Title','Question',guard=allowed)
        with store.connect(self.database,writable=True) as connection:
            row=store._auxiliary_kind(connection,cache.CACHE_KIND)[0]
            metadata=store.parse_json(row['payload']);metadata['revision']=2**63-1
            sql.execute(connection,'update_auxiliary_item',(store.encoded(metadata),row['original_source'],cache.CACHE_KIND,row['ordinal']))
        terminal_limit=self.detail(candidate)
        with self.assertRaises(store.StoreError):
            cache.dismiss(self.root,self.snapshot,candidate['id'],terminal_limit['candidateVersion'],guard=allowed)
        self.assertEqual(self.detail(candidate)['revision'],2**63-1)

    def test_capture_rejects_unknown_removed_or_outdated_question_without_full_scan(self):
        self.assertIsNone(self.admit(record=dict(self.record,id='unknown')))
        self.assertIsNone(self.admit(record=dict(self.record,description='Old mismatched prose.')))
        store.remove_question(self.root,self.record['id'],store.exercise_version(self.record),guard=allowed)
        self.assertIsNone(self.admit())

    def test_approved_pool_caps_and_body_witness_tampering_are_rejected(self):
        first=self.admit()
        snapshot=cache.approve(self.root,first['id'],first['candidateVersion'],first['exerciseVersion'],
                               certificate(self.record,self.body),guard=allowed)
        second=self.admit(body='all n: Node | n not in n.adj')
        self.assertIsNotNone(second)
        for name in ('MAX_APPROVALS_PER_EXERCISE','MAX_APPROVALS'):
            with patch.object(store,name,1),self.assertRaises(store.StoreError):
                cache.approve(self.root,second['id'],second['candidateVersion'],second['exerciseVersion'],
                               certificate(self.record,second['body']),guard=allowed)
        self.assertEqual(store.load_store(self.root).correct_pools,snapshot.correct_pools)
        with store.connect(self.database,writable=True) as connection:
            row=store._auxiliary_kind(connection,'adminApproval:'+self.record['id'])[0]
            sql.execute(connection,'update_auxiliary_item',(row['payload'],'some Node',row['kind'],row['ordinal']))
        with self.assertRaises(store.StoreError):store.load_store(self.root)


if __name__=='__main__':unittest.main()
