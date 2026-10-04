"""Constructed mutations of the selected initialization translation boundary."""
import json
from pathlib import Path
import sys
import unittest

ROOT=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(ROOT),str(ROOT/'scripts')]
from service_initial_bridge import extract,BridgeRejected


class ServiceInitialBridgeTests(unittest.TestCase):
    def mutate(self,path,before,after):
        source=(ROOT/path).read_text()
        self.assertIn(before,source)
        return extract(ROOT,{path:source.replace(before,after,1)})

    def test_exact_graph_fixture_has_no_ambiguous_field(self):
        result=extract(ROOT)
        fixture=json.loads((ROOT/'closure/traffic-refinement/initial-graph.json').read_text())['graph']
        self.assertEqual(result['graph'],fixture)
        self.assertEqual(len({r[0] for r in result['graph']}),len(result['graph']))

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
