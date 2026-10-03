"""Constructed negative witnesses for the SQLite/navigation verification gates."""
from contextlib import redirect_stderr
from io import StringIO
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('sqlite_closure_generator', ROOT / 'scripts/compile_sql_queries.py')
GENERATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GENERATOR)


class SqlGenerationEvidenceTests(unittest.TestCase):
    def test_execution_failures_do_not_discharge_negative_controls(self):
        failures = [(127, 'constructed executable failure'),
                    (1, 'unshare: Operation not permitted'),
                    (2, 'Usage: sqlean [SQL]'),
                    (-9, ''), (1, '')]
        for code, stderr in failures:
            with self.subTest(code=code, stderr=stderr), \
                    mock.patch.object(GENERATOR.subprocess, 'run',
                                      return_value=subprocess.CompletedProcess([], code, '', stderr)), \
                    self.assertRaises(GENERATOR.ParserInfrastructureError):
                GENERATOR.check_parser_rejections(Path('/constructed-parser'))

    def test_only_explicit_parse_or_validation_rejections_count(self):
        for stderr in ('parse error: 1:1: unsupported character',
                       'validation error: unknown column'):
            with self.subTest(stderr=stderr), \
                    mock.patch.object(GENERATOR.subprocess, 'run',
                                      return_value=subprocess.CompletedProcess([], 1, '', stderr)) as process:
                self.assertEqual(GENERATOR.check_parser_rejections(Path('/constructed-parser')), 7)
                self.assertEqual(process.call_count, 7)
        # An invalid schema is not proof that the supplied SQL was rejected.
        with mock.patch.object(GENERATOR.subprocess, 'run',
                               return_value=subprocess.CompletedProcess([], 1, '', 'schema error: invalid JSON')), \
                self.assertRaises(ValueError) as raised:
            GENERATOR.check_parser_rejections(Path('/constructed-parser'))
        self.assertNotIsInstance(raised.exception, GENERATOR.ParserRejected)
        with mock.patch.object(GENERATOR.subprocess, 'run',
                               return_value=subprocess.CompletedProcess([], 0, 'accepted', '')), \
                self.assertRaisesRegex(ValueError, 'accepted a negative control'):
            GENERATOR.check_parser_rejections(Path('/constructed-parser'))

    def test_cli_distinguishes_infrastructure_and_rejected_artifacts(self):
        cases = [(GENERATOR.ParserInfrastructureError('PRIVATE_SENTINEL'), 2),
                 (FileNotFoundError('PRIVATE_SENTINEL'), 2),
                 (subprocess.TimeoutExpired('PRIVATE_SENTINEL', 1), 2),
                 (GENERATOR.ParserRejected('PRIVATE_SENTINEL'), 1),
                 (ValueError('PRIVATE_SENTINEL'), 1)]
        for failure, expected in cases:
            output = StringIO()
            with self.subTest(kind=type(failure).__name__), \
                    mock.patch.object(sys, 'argv', ['compile_sql_queries.py', '--parser', '/constructed', '--check']), \
                    mock.patch.object(GENERATOR, 'compile_registry', side_effect=failure), redirect_stderr(output):
                self.assertEqual(GENERATOR.main(), expected)
            self.assertNotIn('PRIVATE_SENTINEL', output.getvalue())


class NavigationEvidenceTests(unittest.TestCase):
    def run_fixture(self, reports, *, declared=('one', 'two')):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'tests').mkdir()
            shutil.copyfile(ROOT / 'tests/browser-suite.mjs', root / 'tests/browser-suite.mjs')
            for name, report in zip(('browser.mjs', 'navigation.mjs', 'traffic-browser.mjs', 'persistent-browser.mjs'), (*reports, reports[0], reports[0])):
                # Literal declarations are the frozen scenario inventory. The
                # child is constructed to emit a controlled report instead.
                declarations = ''.join("// await check('" + item + "', async () => {});\n" for item in declared)
                (root / 'tests' / name).write_text(declarations + 'console.log(' + json.dumps(json.dumps(report)) + ');\n')
            return subprocess.run(['node', 'tests/browser-suite.mjs'], cwd=root, capture_output=True,
                                  text=True, timeout=10, check=False)

    def test_empty_missing_duplicate_or_unexpected_browser_witnesses_fail(self):
        good = {'status': 'PASS', 'checks': 2, 'passed': ['one', 'two']}
        invalid = [{'status': 'PASS', 'checks': 0, 'passed': []},
                   {'status': 'PASS', 'checks': 1, 'passed': ['one']},
                   {'status': 'PASS', 'checks': 2, 'passed': ['one', 'one']},
                   {'status': 'PASS', 'checks': 2, 'passed': ['two', 'one']},
                   {'status': 'PASS', 'checks': 2, 'passed': ['one', 'other']}]
        for report in invalid:
            with self.subTest(report=report):
                self.assertNotEqual(self.run_fixture((good, report)).returncode, 0)
        self.assertNotEqual(self.run_fixture((invalid[0], invalid[0]), declared=()).returncode, 0)

    def test_both_complete_scenario_reports_are_required_and_combined(self):
        good = {'status': 'PASS', 'checks': 2, 'passed': ['one', 'two']}
        result = self.run_fixture((good, good))
        self.assertEqual(result.returncode, 0, result.stderr)
        report = json.loads(result.stdout)
        self.assertEqual(report['checks'], 8)
        self.assertEqual(report['passed'], ['tests/browser.mjs:one', 'tests/browser.mjs:two',
                                            'tests/navigation.mjs:one', 'tests/navigation.mjs:two',
                                            'tests/traffic-browser.mjs:one', 'tests/traffic-browser.mjs:two',
                                            'tests/persistent-browser.mjs:one', 'tests/persistent-browser.mjs:two'])


if __name__ == '__main__':
    unittest.main()
