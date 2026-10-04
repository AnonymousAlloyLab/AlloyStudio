"""Mutation controls for actual guard extraction and closed work interpretation."""
import ast
import copy
from pathlib import Path
import sys
import unittest
WORKSPACE=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(WORKSPACE/'scripts'),str(WORKSPACE/'tests')]
import ingress_deadline_bridge as bridge
from test_service_initial_bridge import verified_historical_root
ROOT=WORKSPACE/'closure/traffic-refinement/evidence/ting01-20261004T172620Z-16bb5a5f/inputs'

class BridgeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        assert verified_historical_root('ting01-20261004T172620Z-16bb5a5f',
            'scripts/verify_ingress_deadlines.py',('scripts/ingress_deadline_bridge.py',))==ROOT

    def setUp(self):
        self.traffic=(ROOT/'traffic_http.py').read_text()
        self.server=(ROOT/'server.py').read_text()
    def mutate_method(self,source,cls,name,mutate):
        tree=ast.parse(source)
        node=next(n for c in tree.body if isinstance(c,ast.ClassDef) and c.name==cls
                  for n in c.body if isinstance(n,ast.FunctionDef) and n.name==name)
        mutate(node)
        return ast.unparse(ast.fix_missing_locations(tree))
    def test_exact_historical_bridge(self):
        report=bridge.check(ROOT)
        self.assertEqual(report['status'],'PASS')
        self.assertEqual(len(report['consumerAstSha256']),14)
        self.assertEqual(len(report['programs']),6)
    def test_historical_bridge_rejects_changed_current_parser(self):
        with self.assertRaises(bridge.BridgeRejected):bridge.check(WORKSPACE)
    def test_equality_weakening_changes_generated_program(self):
        changed=self.traffic.replace('if now >= self.deadline:', 'if now > self.deadline:')
        generated=bridge.generate(ROOT,changed)
        self.assertIn('if now > deadline then false else true',generated)
        self.assertNotEqual(generated,bridge.generate(ROOT))
    def test_each_missing_return_guard_is_exposed(self):
        for name in ('readline','read1','_recv'):
            with self.subTest(name=name):
                def remove(n):
                    index=max(i for i,x in enumerate(n.body) if bridge.guard_statement(x,'self'))
                    n.body.pop(index)
                changed=self.mutate_method(self.traffic,'DeadlineReader',name,remove)
                program=bridge.extraction(ROOT,changed)['programs'][bridge.NAMES['DeadlineReader.'+name]]
                self.assertEqual(program[-2:],['work','deliver'])
    def test_missing_json_postdecode_guard_exposed(self):
        def remove(n):
            body=next(x for x in n.body if isinstance(x,ast.Try)).body
            body.pop(max(i for i,x in enumerate(body) if bridge.guard_statement(x,'self.rfile')))
        changed=self.mutate_method(self.server,'Handler','read_json_body',remove)
        self.assertEqual(bridge.extraction(ROOT,server_source=changed)['programs']['bodyProgram'][-2:],['work','deliver'])
    def test_missing_parse_guard_exposed(self):
        def remove(n):next(x for x in n.body if isinstance(x,ast.If)).body=[ast.Pass()]
        changed=self.mutate_method(self.server,'Handler','parse_request',remove)
        self.assertEqual(bridge.extraction(ROOT,server_source=changed)['programs']['parseProgram'],['work','deliver'])
    def test_missing_phase_guard_exposed(self):
        def remove(n):n.body.pop(0)
        changed=self.mutate_method(self.traffic,'DeadlineReader','begin_body',remove)
        self.assertEqual(bridge.extraction(ROOT,changed)['programs']['beginBodyProgram'],['reset'])
    def test_second_reset_clock_rejected(self):
        changed=self.traffic.replace('self.deadline = now + self.profile.body_seconds','self.deadline = self.clock() + self.profile.body_seconds')
        with self.assertRaises(bridge.BridgeRejected):bridge.generate(ROOT,changed)
    def test_rechecking_after_reset_is_not_mapped_to_old_phase_guard(self):
        def duplicate(n):n.body.insert(2,copy.deepcopy(n.body[0]))
        changed=self.mutate_method(self.traffic,'DeadlineReader','begin_body',duplicate)
        with self.assertRaises(bridge.BridgeRejected):bridge.generate(ROOT,changed)
    def test_deadline_extension_before_return_rejected(self):
        def add(n):n.body.insert(-1,ast.parse('self.deadline += 100').body[0])
        changed=self.mutate_method(self.traffic,'DeadlineReader','readline',add)
        with self.assertRaises(bridge.BridgeRejected):bridge.generate(ROOT,changed)
    def test_wrong_clock_sample_return_rejected(self):
        changed=self.traffic.replace('        return now\n','        return self.clock()\n')
        with self.assertRaises(bridge.BridgeRejected):bridge.generate(ROOT,changed)
    def test_wrong_reader_guard_rejected(self):
        changed=self.server.replace('self.rfile.check_deadline()','self.other_reader.check_deadline()')
        with self.assertRaises(bridge.BridgeRejected):bridge.generate(ROOT,server_source=changed)
    def test_changed_decoder_or_early_return_rejected(self):
        for old,new in [('data = bounded_json(', 'data = json.loads('),('            return data\n','            return {}\n')]:
            with self.subTest(new=new):
                changed=self.server.replace(old,new)
                with self.assertRaises(bridge.BridgeRejected):bridge.generate(ROOT,server_source=changed)
    def test_duplicate_method_rejected(self):
        changed=self.traffic.replace('    def check_deadline(self):','    def check_deadline(self):\n        pass\n\n    def check_deadline(self):')
        with self.assertRaises(bridge.BridgeRejected):bridge.generate(ROOT,changed)

if __name__=='__main__':unittest.main()
