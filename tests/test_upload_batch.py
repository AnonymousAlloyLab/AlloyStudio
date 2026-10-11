"""Real, bounded batch parity and import certificate boundary regressions."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
from unittest.mock import patch

import exercise_store as store
from runtime_dependencies import clean_java_environment, run_engine, runtime_classpath
import runtime_dependencies as runtime


ROOT = Path(__file__).resolve().parents[1]
SCRATCH = ROOT / 'build/upload-capacity-validation'
FRAME_BYTES = 1048576
BATCH_BYTES = 16 * FRAME_BYTES


def document(**changes):
    result = dict(id='batch-inv', title='Node existence', group='Batch', predicate='inv',
                  description='Require a node.', environmentBefore='sig Node {}\n',
                  environmentAfter='\n', predicateHeader='pred inv ', starter='',
                  oracleSolutions=['some Node'], correctSolutions=[])
    result.update(changes)
    return result


def validator(data, *, batch=True):
    classpath = runtime_classpath(ROOT)
    override = os.environ.get('ALLOY_TEST_UPLOAD_CLASSES')
    if override:
        classpath = os.pathsep.join([str(Path(override).resolve()), *classpath.split(os.pathsep)[1:]])
    # Portal shutdown tests intentionally close their admission roots. This
    # standalone fixture owns a separate root instead of reopening that service.
    SCRATCH.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='batch-', dir=SCRATCH) as folder:
        result = run_engine(['java', '-Dfile.encoding=UTF-8', '-Xmx256m',
                            '-XX:ActiveProcessorCount=2', '-cp', classpath,
                            'live.ExerciseValidator', *(['--batch'] if batch else [])],
                           root=Path(folder), input=data, capture_output=True, check=False,
                           timeout=60, env=clean_java_environment())
    if result.returncode or result.stderr:
        raise AssertionError('Validator failed its sanitized process boundary.')
    return [json.loads(row) for row in result.stdout.decode('utf-8').splitlines()]


def frame(value):
    return json.dumps(value, ensure_ascii=False).encode('utf-8') + b'\n'


class UploadBatchTests(unittest.TestCase):
    def test_real_batch_timeout_reaps_child_cleans_scratch_and_allows_retry(self):
        import admin_upload as upload
        scratch = ROOT / 'build/upload-capacity-validation'
        scratch.mkdir(parents=True, exist_ok=True)
        payload = store._validation_payload(document())
        classpath = runtime_classpath(ROOT)
        with tempfile.TemporaryDirectory(prefix='deadline-', dir=scratch) as folder:
            root = Path(folder)
            owned = root / 'engine-tmp'
            with patch.dict(os.environ, ALLOY_ENGINE_TMP_ROOT=str(owned)), \
                    patch.object(upload, 'run_engine', wraps=upload.run_engine) as worker:
                # Control the dispatched execution budget, so slow Python JSON
                # serialization cannot turn this into a pre-launch rejection.
                with patch.object(upload, '_remaining', return_value=0.02), self.assertRaises(upload.UploadError):
                    upload._batch_worker(classpath, [payload] * 100, 'java',
                                         time.monotonic() + 10, root=root)
                self.assertEqual(worker.call_count, 1)
                self.assertEqual(runtime.PROCESS_BUDGET.stats()['reserved'], 0)
                self.assertEqual(list(owned.iterdir()), [])
                results = upload._batch_worker(classpath, [payload], 'java',
                                                time.monotonic() + 10, root=root)
                self.assertEqual(results[0]['status'], 'ok')
                self.assertEqual(runtime.PROCESS_BUDGET.stats()['reserved'], 0)
                self.assertEqual(list(owned.iterdir()), [])

    def test_batch_parity_for_each_independent_context_and_certificate(self):
        documents = [document(), document(id='batch-other', predicate='other',
            predicateHeader='pred other ', environmentBefore='sig Person {}\n',
            oracleSolutions=['some Person', 'not no Person']),
            document(id='batch-edges', environmentBefore='sig Node {edge: set Node}\n',
                oracleSolutions=['no iden & edge', 'all n: Node | n not in n.edge'],
                correctSolutions=['no n: Node | n in n.edge'])]
        payloads = [store._validation_payload(value) for value in documents]
        observed = validator(b''.join(frame(value) for value in payloads))
        expected = [validator(json.dumps(value).encode('utf-8'), batch=False)[0]
                    for value in payloads]
        self.assertEqual(observed, expected)
        identity = {'engineSha256': 'a' * 64, 'dependencySha256': {'fixture.jar': 'b' * 64}}
        for value, result in zip(documents, observed):
            certificate = store._validation_certificate(value, result, identity)
            self.assertEqual(certificate['bodySha256'],
                             [store.sha(body) for body in value['oracleSolutions'] + value['correctSolutions']])
            self.assertEqual(certificate['result']['evaluatedCandidates'],
                             len(value['oracleSolutions']) + len(value['correctSolutions']))
            self.assertEqual(certificate['environmentSha256'], store.environment_sha256(value))
            self.assertEqual(certificate['engineSha256'], identity['engineSha256'])
            self.assertNotIn('equivalenceChecks', certificate['result'])

    def test_contexts_do_not_share_declarations(self):
        first = store._validation_payload(document())
        second = store._validation_payload(document(id='batch-person', predicate='person',
            predicateHeader='pred person ', environmentBefore='sig Person {}\n',
            oracleSolutions=['some Node']))
        results = validator(frame(first) + frame(second) + frame(first))
        self.assertEqual(len(results), 2)
        self.assertEqual(results[0]['status'], 'ok')
        self.assertEqual(results[1]['status'], 'rejected')
        self.assertEqual(results[1]['code'], 'INVALID_MODEL')

    def test_non_equivalent_late_reference_rejects_and_stops_before_next_frame(self):
        good = store._validation_payload(document())
        bad = store._validation_payload(document(oracleSolutions=['some Node', 'no Node']))
        results = validator(frame(good) + frame(bad) + frame(good))
        self.assertEqual(len(results), 2)
        self.assertEqual(results[0]['status'], 'ok')
        self.assertEqual(results[1]['code'], 'NOT_EQUIVALENT')
        self.assertEqual(results[1]['candidateIndex'], 1)

    def test_malformed_late_reference_cannot_be_skipped_by_primary_match(self):
        good = store._validation_payload(document())
        bad = dict(good, oracleBodies=['some Node', 'some Node and unknown'])
        results = validator(frame(good) + frame(bad) + frame(good))
        self.assertEqual(len(results), 2)
        self.assertEqual(results[-1]['code'], 'INVALID_MODEL')
        self.assertEqual(results[-1]['candidateIndex'], 1)

    def test_strict_utf8_empty_and_blank_frames_fail_closed(self):
        good = frame(store._validation_payload(document()))
        for malformed in (b'', b'\n', b'\xff\n', b'{"predicate":"\xc0\xaf"}\n', b'{unclosed\n'):
            with self.subTest(frame=malformed[:30]):
                results = validator(malformed + (good if malformed else b''))
                self.assertEqual(len(results), 1)
                self.assertEqual(results[0]['status'], 'rejected')
                self.assertEqual(results[0]['code'], 'INVALID_REQUEST')

    def test_final_frame_without_lf_and_crlf_separator_are_accepted(self):
        value = frame(store._validation_payload(document()))
        for encoded in (value[:-1], value[:-1] + b'\r\n'):
            with self.subTest(ending=encoded[-2:]):
                self.assertEqual(validator(encoded)[0]['status'], 'ok')

    def test_one_complete_rfc_json_object_is_required_per_frame(self):
        value = frame(store._validation_payload(document()))[:-1]
        for suffix in (b' ignored trailing input', b' {}', b' null', b'\x00', b','):
            with self.subTest(suffix=suffix):
                results = validator(value + suffix + b'\n' + value + b'\n')
                self.assertEqual(len(results), 1)
                self.assertEqual(results[0]['code'], 'INVALID_REQUEST')
        malformed = (value[:1] + b'"predicate":"inv",' + value[1:],
                     value.replace(b'"predicate"', b"'predicate'", 1),
                     value.replace(b'"predicate"', b'predicate', 1),
                     value[:-1] + b',}')
        for encoded in malformed:
            with self.subTest(malformed=encoded[:45]):
                self.assertEqual(validator(encoded + b'\n')[0]['code'], 'INVALID_REQUEST')

    def test_frame_byte_limit_accepts_exact_maximum_and_rejects_one_more(self):
        value = frame(store._validation_payload(document()))[:-1]
        exact = value + b' ' * (FRAME_BYTES - len(value))
        self.assertEqual(validator(exact + b'\n')[0]['status'], 'ok')
        results = validator(exact + b' \n')
        self.assertEqual(len(results), 1)
        self.assertEqual(results[0]['code'], 'REQUEST_TOO_LARGE')

    def test_aggregate_byte_limit_includes_separators_and_stops_on_overflow(self):
        value = frame(store._validation_payload(document()))[:-1]
        row = value + b' ' * (FRAME_BYTES - 1 - len(value)) + b'\n'
        exact = row * 16
        self.assertEqual(len(exact), BATCH_BYTES)
        accepted = validator(exact)
        self.assertEqual(len(accepted), 16)
        self.assertTrue(all(value['status'] == 'ok' for value in accepted))
        rejected = validator(exact + frame(store._validation_payload(document())))
        self.assertEqual(len(rejected), 17)
        self.assertEqual(rejected[-1]['code'], 'BATCH_TOO_LARGE')

    def test_frame_count_accepts_200_and_rejects_201_without_evaluation(self):
        row = frame(store._validation_payload(document()))
        accepted = validator(row * 200)
        self.assertEqual(len(accepted), 200)
        self.assertTrue(all(value['status'] == 'ok' for value in accepted))
        rejected = validator(row * 201)
        self.assertEqual(len(rejected), 201)
        self.assertEqual(rejected[-1]['code'], 'TOO_MANY_REQUESTS')

    def test_certificate_rejects_wrong_counts_types_and_sanitizes_worker_strings(self):
        value = document()
        result = validator(frame(store._validation_payload(value)))[0]
        identity = {'engineSha256': 'a' * 64, 'dependencySha256': {}}
        for key, expected in result.items():
            if key not in ('status', 'equivalence', 'equivalenceChecks'):
                changed = dict(result, **{key: not expected if type(expected) is bool else None})
                with self.subTest(field=key), self.assertRaises(store.StoreError):
                    store._validation_certificate(value, changed, identity)
        malformed = dict(result, status='rejected', code='private source /path/token')
        with self.assertRaisesRegex(store.StoreError, 'validation_failed') as failure:
            store._validation_certificate(value, malformed, identity)
        self.assertNotIn('private source', str(failure.exception))
        # Existing certificates retain only their approved projection, including
        # when an otherwise complete worker adds an unrelated string field.
        extended = dict(result, privateDiagnostic='not retained')
        self.assertNotIn('privateDiagnostic',
                         store._validation_certificate(value, extended, identity)['result'])

    def test_legacy_validate_import_uses_same_helpers_and_engine_change_gate(self):
        value = document()
        identity = {'engineSha256': 'a' * 64, 'dependencySha256': {}}
        result = validator(frame(store._validation_payload(value)))[0]
        completed = subprocess.CompletedProcess([], 0, json.dumps(result), '')
        with patch.object(store, '_engine_identity', return_value=identity), \
                patch.object(store, 'run_engine', return_value=completed) as process:
            actual = store.validate_import(ROOT, value)
            self.assertEqual(actual, store._validation_certificate(value, result, identity))
            self.assertEqual(json.loads(process.call_args.kwargs['input']), store._validation_payload(value))
            self.assertNotIn('--batch', process.call_args.args[0])
        changed = dict(identity, engineSha256='c' * 64)
        with patch.object(store, '_engine_identity', side_effect=[identity, changed]), \
                patch.object(store, 'run_engine', return_value=completed), \
                self.assertRaisesRegex(store.StoreError, 'failed or timed out'):
            store.validate_import(ROOT, value)
        with patch.object(store, '_engine_identity', return_value=identity), \
                patch.object(store, 'run_engine', return_value=subprocess.CompletedProcess([], 1, json.dumps(result), '')), \
                self.assertRaisesRegex(store.StoreError, 'validation_failed'):
            store.validate_import(ROOT, value)


if __name__ == '__main__':
    unittest.main()
