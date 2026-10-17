"""Census every bundled public callable; exercise every parser-valid library.

One 256-MiB, two-CPU JVM uses typed symbolic arguments and owned build scratch.
The jar's declarations independently determine membership, kind and arity; the
production ledger is not used to generate the comparison fixtures.
"""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from runtime_dependencies import clean_java_environment, run_engine, runtime_classpath


ROOT = Path(__file__).resolve().parents[1]


@unittest.skipUnless(shutil.which('java') and shutil.which('javac')
                     and (ROOT / 'build/engine/classes/live/LiveFeedback.class').is_file(),
                     'Compiled engine and JDK required')
class LibraryCallFeedbackTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        scratch = ROOT / 'build/library-call-feedback-tests'
        scratch.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='probe-', dir=scratch) as directory:
            folder = Path(directory)
            classpath = runtime_classpath(ROOT)
            compiled = subprocess.run(
                [shutil.which('javac'), '-J-Xmx256m', '-encoding', 'UTF-8', '--release', '17',
                 '-cp', classpath, '-d', directory,
                 str(ROOT / 'engine/test/LibraryCallableSelfTest.java')], cwd=ROOT,
                capture_output=True, timeout=45, check=False, env=clean_java_environment())
            if compiled.returncode:
                raise AssertionError('Library callable probe did not compile: '
                                     + compiled.stderr.decode('utf-8', errors='replace'))
            command = [shutil.which('java'), '-Dfile.encoding=UTF-8', '-Xmx256m',
                       '-XX:ActiveProcessorCount=2', '-cp', directory + os.pathsep + classpath,
                       'live.LibraryCallableSelfTest', str(ROOT / 'vendor/acgn/lib/alloy.jar')]
            with patch.dict(os.environ, ALLOY_ENGINE_TMP_ROOT=str(folder / 'owned-tmp')):
                result = run_engine(command, root=folder, cwd=ROOT, text=True, capture_output=True,
                                    timeout=180, check=False, env=clean_java_environment())
            if result.returncode or result.stderr:
                raise AssertionError('Library callable probe failed: ' + result.stderr)
            cls.observations = json.loads(result.stdout)

    def assert_feedback(self, result, *, zero):
        self.assertEqual(result['status'], 'ok', result.get('diagnostics'))
        self.assertEqual(result['diagnostics'], [])
        self.assertTrue(result['comparison']['complete'])
        self.assertEqual(result['comparison']['evaluatedCandidates'], 1)
        self.assertTrue(result['trace']['matchesDistance'])
        self.assertTrue(result['trace']['matrixReplayVerified'])
        self.assertFalse(result['trace']['hasAggregates'])
        self.assertEqual(sum(step['cost'] for step in result['operations']), result['distance'])
        if zero:
            self.assertEqual((result['distance'], result['operations']), (0, []))
        else:
            self.assertGreater(result['distance'], 0)
            self.assertTrue(result['operations'])

    def test_ledger_exactly_matches_all_public_bundled_declarations(self):
        self.assertEqual(self.observations['resources'], 11)
        self.assertEqual(self.observations['counts'], {
            'boolean': 9, 'graph': 13, 'integer': 31, 'natural': 12, 'ordering': 14,
            'relation': 20, 'seqrel': 22, 'sequence': 25, 'sequniv': 20, 'ternary': 9,
            'time': 0,
        })
        self.assertEqual(len(self.observations['declarations']), 175)
        self.assertEqual(self.observations['ledger'],
                         dict(missing=[], extra=[], accepted=175, rejected=525))

    def test_every_public_callable_in_each_parser_valid_module_has_zero_self_distance(self):
        records = self.observations['identities']
        expected = {key for key in self.observations['declarations']
                    if not key.startswith('util/sequence/')
                    and not (os.name == 'nt' and key.startswith('util/natural/'))}
        self.assertEqual({record['declaration'] for record in records}, expected)
        self.assertEqual(len(records), 138 if os.name == 'nt' else 150)
        for record in records:
            with self.subTest(declaration=record['declaration']):
                self.assert_feedback(record['feedback'], zero=True)
                self.assert_feedback(record['mutationFeedback'], zero=False)
                self.assertEqual(len(record['calls']), 1)
                call = record['calls'][0]
                _, _, member, kind, arity = record['declaration'].split('/')
                self.assertEqual((call['kind'], call['arity']), (kind, int(arity)))
                self.assertTrue(call['callee'].endswith('/' + member))

    def test_implicit_sequence_and_transitive_imports_preserve_lexical_bindings(self):
        for name in ('transitive-relation', 'transitive-ordering', 'nested-formula',
                     'nested-alias-synonyms', 'implicit-sequence', 'time-macro'):
            with self.subTest(name=name):
                self.assert_feedback(self.observations['controls'][name], zero=True)

    def test_changed_imported_callee_and_argument_order_remain_distinct(self):
        for name in ('sequence-relation-parameter', 'changed-relation-callee',
                     'changed-ordering-arguments', 'changed-boolean-callee', 'nested-instantiation'):
            with self.subTest(name=name):
                self.assert_feedback(self.observations['controls'][name], zero=False)

    def test_forged_nested_alias_member_arity_and_private_import_fail_closed(self):
        self.assertEqual(self.observations['forgedNestedCallsRejected'], 12)

    def test_natural_library_windows_parser_boundary_is_explicit(self):
        blocked = self.observations['parserBlocked']
        if os.name == 'nt':
            self.assertEqual(set(blocked), {'natural'})
            self.assertEqual(len(blocked['natural']['declarations']), 12)
            self.assertCountEqual(blocked['natural']['declarations'],
                                  [key for key in self.observations['declarations']
                                   if key.startswith('util/natural/')])
            feedback = blocked['natural']['feedback']
            self.assertEqual(feedback['status'], 'invalid')
            self.assertNotIn('distance', feedback)
            self.assertNotIn('operations', feedback)
        else:
            self.assertEqual(blocked, {})
            self.assertEqual(sum(record['declaration'].startswith('util/natural/')
                                 for record in self.observations['identities']), 12)

    def test_native_windows_module_filename_metadata_preserves_callable_identity(self):
        records = self.observations['windowsModuleMetadata']
        self.assertEqual(len(records), 2)
        self.assertEqual({record['mapped'] for record in records}, {True, False})
        for record in records:
            with self.subTest(mapped=record['mapped']):
                self.assertGreater(record['rootChanged'], 0)
                self.assertGreater(record['nestedChanged'], 0)
                self.assertCountEqual(record['actual'], record['expected'])
                self.assertCountEqual([call['callee'] for call in record['actual']],
                                      ['util/graph<Node>/util/relation/dom', 'util/integer/signum'])

    def test_upstream_sequence_type_error_is_explicit_and_does_not_poison_feedback(self):
        # The bundled sequence.als line125 applies integer add to SeqIdx because
        # its cardinality expression is missing parentheses. It is not valid
        # input to either distance adapter; an allowlist cannot repair its body.
        result = self.observations['controls']['sequence-upstream-invalid']
        self.assertEqual(result['status'], 'invalid')
        self.assertNotIn('distance', result)
        self.assertNotIn('operations', result)
        self.assert_feedback(self.observations['controls']['after-invalid'], zero=True)


if __name__ == '__main__':
    unittest.main()
