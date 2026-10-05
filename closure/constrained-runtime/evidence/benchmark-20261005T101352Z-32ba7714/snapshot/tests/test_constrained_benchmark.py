"""Counterexamples for constrained benchmark identity, completeness and staging."""
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
import benchmark_constrained_runtime as bench


class ConstrainedBenchmarkTests(unittest.TestCase):
    def setUp(self):
        scratch = ROOT / 'build/v005-refinement/tests'
        scratch.mkdir(parents=True, exist_ok=True)
        temporary = tempfile.TemporaryDirectory(prefix='benchmark-', dir=scratch)
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)

    def fixture(self):
        for name in bench.JAR_FILES:
            path = self.root / 'vendor/acgn/lib' / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b'bundled public dependency')
        classes = self.root / 'prior/engine-classes'
        classes.mkdir(parents=True)
        (classes / 'Worker.class').write_bytes(b'preserved compiled artifact')
        report = {'compiledClasses': {'engine-classes/Worker.class': bench.sha((classes/'Worker.class').read_bytes())},
                  'inputs': {'vendor/acgn/lib/' + name: bench.sha((self.root/'vendor/acgn/lib'/name).read_bytes())
                             for name in bench.JAR_FILES},
                  'preservation': {'mismatches': [], 'byteIdenticalResponses': 4274}}
        path = self.root / 'prior/summary.json'
        path.write_text(json.dumps(report))
        return path, classes

    def test_baseline_rejects_changed_missing_and_extra_class_inputs(self):
        report, classes = self.fixture()
        bench.verify_baseline(report, self.root)
        original = (classes/'Worker.class').read_bytes()
        (classes/'Worker.class').write_bytes(b'changed')
        with self.assertRaises(bench.Refused):
            bench.verify_baseline(report, self.root)
        (classes/'Worker.class').write_bytes(original)
        (classes/'Extra.class').write_bytes(b'extra')
        with self.assertRaises(bench.Refused):
            bench.verify_baseline(report, self.root)
        (classes/'Extra.class').unlink()
        (classes/'Worker.class').unlink()
        with self.assertRaises(bench.Refused):
            bench.verify_baseline(report, self.root)

    def test_baseline_rejects_changed_runtime_dependencies(self):
        report, _ = self.fixture()
        (self.root/'vendor/acgn/lib'/bench.JAR_FILES[0]).write_bytes(b'changed')
        with self.assertRaises(bench.Refused):
            bench.verify_baseline(report, self.root)

    def test_complete_response_and_case_identity_are_required(self):
        rows = [{'case':'A/starter/canonical','inputSha256':'input','responseSha256':'full','status':'ok'},
                {'case':'A/starter/ast','inputSha256':'other','responseSha256':'full2','status':'ok'}]
        self.assertEqual(bench.parity(rows, rows)['equalCompleteResponses'], 2)
        for changed in (rows[:1], list(reversed(rows)), rows + rows,
                        [dict(rows[0], responseSha256='different'), rows[1]],
                        [dict(rows[0], inputSha256='different'), rows[1]],
                        [dict(rows[0], status='unsupported'), rows[1]]):
            with self.subTest(changed=changed), self.assertRaises(bench.Refused):
                bench.parity(rows, changed)
        with self.assertRaises(bench.Refused):
            bench.parity([rows[0], rows[0]], [rows[0], rows[0]])

    def test_both_metrics_and_both_drafts_cover_each_exercise(self):
        records = {name: {'id':name, 'environmentBefore':'sig A {}\n', 'environmentAfter':'',
                         'predicateHeader':'pred inv', 'predicate':'inv', 'starter':'some A'}
                   for name in ('one', 'two')}
        snapshot = SimpleNamespace(exercises=records, correct_pools={name:['no A'] for name in records})
        cohort = bench.cases(snapshot)
        self.assertEqual(len(cohort), 8)
        self.assertEqual({row['case'] for row in cohort},
                         {name+'/'+kind+'/'+metric for name in records for kind in ('starter','correct')
                          for metric in ('canonical','ast')})

    def test_legacy_adapter_removes_only_outer_metadata(self):
        payload = {'studentSource':'public fixture', 'workMillis':987}
        frame = {'protocol':1,'incarnation':'one','ticket':1,'context':'unchanged',
                 'kind':'feedback','request':payload,'workMillis':20}
        changed = bench.adjust_frame(frame, legacy=True)
        self.assertEqual(changed, {key:value for key,value in frame.items() if key!='workMillis'})
        self.assertIs(changed['request'], payload)
        self.assertEqual(frame['workMillis'], 20)
        self.assertEqual(bench.adjust_frame(payload, legacy=True), payload)
        self.assertEqual(bench.adjust_frame(frame, work_millis=5)['context'], 'unchanged')

    def test_affinity_cannot_claim_an_unavailable_cpu_count(self):
        self.assertEqual(bench.choose_cpus({8,9,10,11}, 2), [8,9])
        for available, count in (({8},2), ({8,9},4), ({8,9},True), ({8,9,10,11},3)):
            with self.assertRaises(bench.Refused):
                bench.choose_cpus(available,count)

    def test_staging_excludes_local_configuration_and_other_source_files(self):
        _, classes = self.fixture()
        (self.root/'exercises').mkdir()
        (self.root/'exercises/exercises.sqlite3').write_bytes(b'public database fixture')
        (self.root/'web').mkdir()
        (self.root/'web/index.html').write_text('public portal')
        for name in ('admin.local.json', 'openai.local.json', '.env', 'unrelated.py'):
            (self.root/name).write_text('private fixture must never be copied')
        destination = self.root/'staged'
        bench.stage_public(self.root, destination, classes)
        self.assertTrue((destination/'web/index.html').is_file())
        self.assertTrue((destination/'build/engine/classes/Worker.class').is_file())
        for name in ('admin.local.json', 'openai.local.json', '.env', 'unrelated.py'):
            self.assertFalse((destination/name).exists())


if __name__ == '__main__':
    unittest.main()
