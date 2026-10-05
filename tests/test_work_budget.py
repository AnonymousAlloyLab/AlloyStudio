"""AP01-C01/C02 work budget: Java boundary self-test and real persistent-worker witnesses.

A draft that exhausts the request-global budget must return an explicit
unsupported result quickly, carry no partial distance or hints, and leave the
persistent JVM alive for the next request. These are finite regressions on the
bundled catalogue, not a wall-clock or RSS proof.
"""
import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import tempfile
import time
import unittest
from unittest.mock import patch

import server
import engine_workers
from engine_workers import EnginePool, LaneLimits
from runtime_dependencies import ProcessBudget, open_engine_admission, clean_java_environment
from scripts import calibrate_work_budget

ROOT = Path(__file__).resolve().parents[1]
CLASSPATH = os.pathsep.join((str(ROOT / 'build/engine/classes'), str(ROOT / 'vendor/acgn/lib/*')))
PARTIAL = {'distance', 'breakdown', 'operations', 'canonicalForm', 'comparison', 'trace', 'astSize'}


class CalibrationBindingTests(unittest.TestCase):
    def reusable_phase(self):
        base = ROOT / 'build/work-calibration'
        base.mkdir(parents=True, exist_ok=True)
        owned = tempfile.TemporaryDirectory(prefix='resume-test-', dir=base)
        self.addCleanup(owned.cleanup)
        old, fresh = Path(owned.name) / 'old', Path(owned.name) / 'fresh'
        for root in (old, fresh):
            for name in ('classes', 'engine-classes'):
                (root / name).mkdir(parents=True)
                (root / name / 'Fixture.class').write_bytes(b'fixture class bytes')
        item = {'id': 'fixture-inv', 'kind': 'starter', 'metric': 'canonical',
                'bytes': 6, 'pool': 1, 'request': {'studentSource': 'some A'}}
        result = {key: item[key] for key in ('id', 'kind', 'metric', 'bytes', 'pool')}
        result.update(requestSha256='a' * 64, responseSha256='b' * 64, status='ok', units=10, allocated=0)
        (old / 'catalogue-requests.jsonl').write_text(json.dumps(item) + '\n')
        (old / 'catalogue-results.jsonl').write_text(json.dumps(result) + '\n')
        return old, fresh, item

    def test_resume_retains_only_identical_complete_phase(self):
        old, fresh, item = self.reusable_phase()
        before = (old / 'catalogue-results.jsonl').read_bytes()
        results, report = calibrate_work_budget.reuse_catalogue(fresh, old, [item])
        self.assertEqual((len(results), report['requests']), (1, 1))
        self.assertEqual((fresh / 'catalogue-results.jsonl').read_bytes(), before)
        self.assertEqual((old / 'catalogue-results.jsonl').read_bytes(), before)

    def test_resume_refuses_changed_classes(self):
        old, fresh, item = self.reusable_phase()
        (fresh / 'classes/Fixture.class').write_bytes(b'changed fixture')
        with self.assertRaises(RuntimeError):
            calibrate_work_budget.reuse_catalogue(fresh, old, [item])

    def test_resume_refuses_changed_requests(self):
        old, fresh, item = self.reusable_phase()
        with self.assertRaises(RuntimeError):
            calibrate_work_budget.reuse_catalogue(fresh, old, [dict(item, metric='ast')])

    def test_resume_refuses_incomplete_results(self):
        old, fresh, item = self.reusable_phase()
        (old / 'catalogue-results.jsonl').write_text('')
        with self.assertRaises(RuntimeError):
            calibrate_work_budget.reuse_catalogue(fresh, old, [item])

    def test_resume_refuses_results_that_required_more_than_production_fuel(self):
        old, fresh, item = self.reusable_phase()
        path = old / 'catalogue-results.jsonl'
        result = json.loads(path.read_text())
        result['units'] = calibrate_work_budget.production_constants()['WORK_BUDGET'] + 1
        path.write_text(json.dumps(result) + '\n')
        with self.assertRaises(RuntimeError):
            calibrate_work_budget.reuse_catalogue(fresh, old, [item])

    def test_complete_response_hash_mismatch_is_not_preserved(self):
        row = {'id': 'fixture-inv', 'kind': 'starter', 'metric': 'canonical',
               'requestSha256': 'a' * 64, 'responseSha256': 'b' * 64}
        self.assertEqual(calibrate_work_budget.preservation([row], [dict(row)])['byteIdenticalResponses'], 1)
        changed = dict(row, responseSha256='c' * 64)
        result = calibrate_work_budget.preservation([row], [changed])
        self.assertEqual(result['byteIdenticalResponses'], 0)
        self.assertEqual(result['mismatches'][0]['index'], 0)

    def test_different_or_missing_requests_cannot_claim_preservation(self):
        row = {'id': 'fixture-inv', 'kind': 'starter', 'metric': 'canonical',
               'requestSha256': 'a' * 64, 'responseSha256': 'b' * 64}
        with self.assertRaises(RuntimeError):
            calibrate_work_budget.preservation([row], [])
        with self.assertRaises(RuntimeError):
            calibrate_work_budget.preservation([row], [dict(row, requestSha256='c' * 64)])


def catalogue_request(exercise, conjuncts, metric):
    """Join the first N known-correct bodies: valid Alloy, superlinear canonical work."""
    with sqlite3.connect(f'file:{ROOT}/exercises/exercises.sqlite3?mode=ro', uri=True) as db:
        before, after, header, predicate = db.execute(
            'select environment_before, environment_after, predicate_header, predicate_name '
            'from exercises where id=?', (exercise,)).fetchone()
        pool = [row[0] for row in db.execute(
            'select body from solutions where exercise_id=? order by ordinal', (exercise,))]
    body = ' and\n'.join('(' + candidate.strip() + ')' for candidate in pool[:conjuncts])
    assert server.validate_body(body) is None
    record = {'environmentBefore': before, 'environmentAfter': after, 'predicateHeader': header}
    return {'studentSource': server.model(record, body), 'referenceBodies': pool,
            'referencePrefix': before + header + '{\n', 'referenceSuffix': '\n}' + after,
            'predicate': predicate, 'metric': metric}


@unittest.skipUnless(shutil.which('java') and shutil.which('javac')
                     and (ROOT / 'build/engine/classes/live/LiveFeedback.class').is_file(),
                     'Compiled engine and JDK required')
class WorkBudgetTests(unittest.TestCase):
    def test_java_boundary_self_test(self):
        scratch = ROOT / 'build/work-budget-tests'
        scratch.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='work-budget-', dir=scratch) as directory:
            compiled = subprocess.run(
                ['javac', '-encoding', 'UTF-8', '--release', '17', '-cp', CLASSPATH,
                 '-d', directory, str(ROOT / 'engine/test/WorkBudgetSelfTest.java')],
                cwd=ROOT, capture_output=True, timeout=60, check=False, env=clean_java_environment())
            self.assertEqual(compiled.returncode, 0, 'Work budget self-test did not compile: '
                             + compiled.stderr.decode(errors='replace'))
            checked = subprocess.run(
                ['java', '-Xmx256m', '-XX:ActiveProcessorCount=2', '-Djava.io.tmpdir=' + directory, '-cp',
                 directory + os.pathsep + CLASSPATH, 'live.WorkBudgetSelfTest'],
                cwd=ROOT, text=True, capture_output=True, timeout=300, check=False, env=clean_java_environment())
        self.assertEqual(checked.returncode, 0, 'Work budget self-test failed: ' + checked.stderr)
        self.assertEqual(checked.stderr, '')
        self.assertRegex(checked.stdout.strip(), r'^WorkBudgetSelfTest passed \([0-9]+ checks\)$')

    def test_slow_draft_is_bounded_and_worker_survives(self):
        open_engine_admission(ROOT)
        pool = EnginePool(ROOT, shutil.which('java'), feedback_workers=1, behavior_workers=1,
                          budget=ProcessBudget(), lane_limits={
                              'feedback': LaneLimits(1, 12, 3, 60), 'behavior': LaneLimits(1, 6, 3, 60)})
        self.addCleanup(pool.close)
        started = time.monotonic()
        # 19 joined correct bodies exceeded 60 s before the budget existed.
        limited = pool.evaluate('feedback', catalogue_request('socialMedia-inv4', 19, 'canonical'), 12)
        elapsed = time.monotonic() - started
        self.assertEqual(limited['status'], 'unsupported')
        self.assertEqual([item['code'] for item in limited['diagnostics']], ['WORK_LIMIT'])
        self.assertFalse(PARTIAL & set(limited))
        self.assertLess(elapsed, 12)
        normal = pool.evaluate('feedback', catalogue_request('socialMedia-inv4', 1, 'canonical'), 12)
        self.assertEqual((normal['status'], normal['distance']), ('ok', 0))
        stats = pool.stats()
        self.assertEqual(stats['launches'], 1, 'a work limit must not retire the persistent JVM')
        self.assertEqual(stats['failures'], 0)
        self.assertEqual(stats['lanes']['feedback']['launchCredits'], 11)

    def test_server_projection_keeps_work_limit_explicit(self):
        raw = {'status': 'unsupported', 'metric': server.METRICS['canonical'],
               'diagnostics': [{'code': 'WORK_LIMIT', 'message': 'This predicate needs more analysis work '
                                'than live feedback allows. Shorten or simplify it, then check again.'}]}

        class Stub:
            def _engine(self, kind, payload):
                return json.loads(json.dumps(raw))

        record = {'environmentBefore': 'sig A {}\n', 'predicateHeader': 'pred inv ', 'environmentAfter': ''}
        payload = {'referenceBodies': ['some A']}
        result = server.Portal._feedback(Stub(), record, 'some A', 'canonical', payload)
        self.assertEqual(result['status'], 'unsupported')
        self.assertEqual(result['diagnostics'][0]['code'], 'WORK_LIMIT')
        self.assertFalse(PARTIAL & set(result))

    def test_cooperative_deadline_is_a_normal_result_and_worker_survives(self):
        open_engine_admission(ROOT)
        pool = EnginePool(ROOT, shutil.which('java'), feedback_workers=1, behavior_workers=1,
                          budget=ProcessBudget())
        self.addCleanup(pool.close)
        encode = engine_workers._encoded

        def tiny_private_allowance(value):
            if isinstance(value, dict) and value.get('protocol') == 1 and value.get('kind') == 'feedback':
                value = dict(value, workMillis=1)
            return encode(value)

        ordinary = catalogue_request('socialMedia-inv4', 1, 'canonical')
        with patch.object(engine_workers, '_encoded', side_effect=tiny_private_allowance):
            limited = pool.evaluate('feedback', ordinary, 12)
        self.assertEqual(limited['status'], 'unsupported')
        self.assertEqual([row['code'] for row in limited['diagnostics']], ['WORK_LIMIT'])
        self.assertFalse(PARTIAL & set(limited))
        normal = pool.evaluate('feedback', ordinary, 12)
        self.assertEqual((normal['status'], normal['distance']), ('ok', 0))
        self.assertEqual((pool.stats()['launches'], pool.stats()['failures']), (1, 0))


if __name__ == '__main__':
    unittest.main()
