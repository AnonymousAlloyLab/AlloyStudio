"""Constructed mutations of the selected initialization translation boundary."""
import json
import hashlib
from functools import lru_cache
from pathlib import Path
import sys
import unittest

WORKSPACE=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(WORKSPACE),str(WORKSPACE/'scripts')]
from service_initial_bridge import extract,BridgeRejected


@lru_cache(maxsize=4)
def verified_historical_root(closure_id, verifier, helpers):
    """Use immutable proof inputs; a newer source tree is a different claim."""
    archive=WORKSPACE/'closure/traffic-refinement/evidence'/closure_id
    root=archive/'inputs'
    index=json.loads((archive/'archive.json').read_text())
    report=json.loads((archive/'report.json').read_text())
    manifest=json.loads((archive/'manifest.json').read_text())
    digest=lambda data:hashlib.sha256(data).hexdigest()
    canonical=json.dumps(manifest,sort_keys=True,separators=(',',':')).encode()
    if (report['status']!='VERIFIED' or report['id']!=closure_id
            or report['inputRootHash']!=digest(canonical)
            or index['inputRootHash']!=report['inputRootHash']):
        raise AssertionError('Historical closure manifest/report binding changed')
    for name,expected in manifest.items():
        if digest((root/name).read_bytes())!=expected:
            raise AssertionError('Historical input changed: '+name)
    for name in ('report.json','manifest.json'):
        if digest((archive/name).read_bytes())!=index['files'][name]['sha256']:
            raise AssertionError('Historical evidence archive changed: '+name)
    if report['verifier']['sha256']!=manifest[verifier]:
        raise AssertionError('Historical verifier identity changed')
    for name in (verifier,*helpers):
        if digest((WORKSPACE/name).read_bytes())!=manifest[name]:
            raise AssertionError('Current historical verifier/helper differs: '+name)
    return root


ROOT=WORKSPACE/'closure/traffic-refinement/evidence/tcfg03-20261004T170110Z-0c2dd9e4/inputs'

class ServiceInitialBridgeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        assert verified_historical_root('tcfg03-20261004T170110Z-0c2dd9e4',
            'scripts/verify_service_profile.py',('scripts/service_initial_bridge.py',))==ROOT

    def mutate(self,path,before,after):
        source=(ROOT/path).read_text()
        self.assertIn(before,source)
        return extract(ROOT,{path:source.replace(before,after,1)})

    def test_exact_graph_fixture_has_no_ambiguous_field(self):
        result=extract(ROOT)
        fixture=json.loads((ROOT/'closure/traffic-refinement/initial-graph.json').read_text())['graph']
        self.assertEqual(result['graph'],fixture)
        self.assertEqual(len({r[0] for r in result['graph']}),len(result['graph']))

    def test_historical_bridge_does_not_certify_new_current_initialization(self):
        try:
            current=extract(WORKSPACE)
        except BridgeRejected:
            return
        # The interpreter may admit new constructors, but their changed graph
        # cannot satisfy the frozen exact-graph correspondence obligation.
        self.assertNotEqual(current['graph'],extract(ROOT)['graph'])

    def test_wrong_catalogue_root_is_not_erased(self):
        with self.assertRaisesRegex(BridgeRejected,'Snapshot root'):
            self.mutate('server.py','load_store(self.root)','load_store(Path("wrong-catalogue-root"))')

    def test_wrong_one_shot_admission_root_is_not_erased(self):
        with self.assertRaisesRegex(BridgeRejected,'Admission root'):
            self.mutate('server.py','open_engine_admission(self.root)','open_engine_admission(Path("wrong-root"))')

    def test_actual_counter_arithmetic_changes_generated_state(self):
        result=self.mutate('traffic_scheduler.py','self.input_bytes = self.subscribers = self.sequence = 0',
                           'self.input_bytes = self.subscribers = self.sequence = 1 + 1')
        rows={p:(k,v) for p,k,v in result['graph']}
        self.assertEqual(rows['Scheduler#1.subscribers'],('int',2))
        self.assertNotEqual(result['graph'],extract(ROOT)['graph'])

    def test_alias_miswire_is_preserved(self):
        result=self.mutate('server.py',"self.cache = self.scheduler.caches['feedback']", "self.cache = self.scheduler.caches['behavior']")
        rows={p:v for p,k,v in result['graph']}
        self.assertEqual(rows['Portal#1.cache'],rows['Portal#1.behavior_cache'])
        self.assertNotEqual(result['graph'],extract(ROOT)['graph'])

    def test_initial_owner_is_not_erased(self):
        result=self.mutate('runtime_dependencies.py','self.owners = {}',"self.owners = {1: 'feedback'}")
        self.assertIn(['ProcessBudget#1.owners/1','str','feedback'],result['graph'])

    def test_unknown_expression_cannot_be_mapped_by_name(self):
        with self.assertRaisesRegex(BridgeRejected,'Unsupported call'):
            self.mutate('traffic_scheduler.py','self.jobs, self.channels = {}, OrderedDict()',
                        'self.jobs, self.channels = unexpected(), OrderedDict()')

    def test_extra_mutation_statement_is_rejected(self):
        with self.assertRaisesRegex(BridgeRejected,'Unsupported statement'):
            self.mutate('traffic_scheduler.py','self.stopped = False','self.stopped = False\n        del self.stopped')

    def test_changed_entropy_input_is_rejected(self):
        with self.assertRaisesRegex(BridgeRejected,'random hex'):
            self.mutate('server.py','secrets.token_hex(16)','secrets.token_hex(8)')

    def test_invalid_event_argument_not_ignored(self):
        with self.assertRaisesRegex(BridgeRejected,'Event set arguments'):
            self.mutate('admin_service.py','self.idle.set()','self.idle.set(1)')

    def test_clock_is_an_explicit_input_and_locks_have_no_ownership_claim(self):
        rows=extract(ROOT)['graph']
        self.assertIn(['TokenBucket#1.last','external','clock'],rows)
        self.assertFalse(any('$owned' in p for p,k,v in rows))

    def test_exact_default_objects_have_no_worker_or_provider_process(self):
        rows=extract(ROOT)['graph']
        self.assertEqual(sum(k=='class' and v=='threading.Thread' for p,k,v in rows),3)
        self.assertFalse(any(k=='class' and v=='_Worker' for p,k,v in rows))


if __name__=='__main__':unittest.main()
