"""Closed-subset and publication correspondence checks for LP05-WORK.

Semantic counterexamples are separately checked by compiling changed Java
projections and requiring the independent Lean refinement to fail.
"""
from pathlib import Path
import unittest
import sys
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from scripts import work_budget_bridge as bridge

ROOT = Path(__file__).resolve().parents[1]


class WorkBudgetBridgeTests(unittest.TestCase):
    def setUp(self):
        self.source = (ROOT / bridge.SOURCE).read_text()

    def test_production_methods_generate_registered_semantics(self):
        self.assertEqual(bridge.check(ROOT)['status'], 'PASS')

    def test_actual_numeric_guard_and_updates_are_translated(self):
        for old, new in (('units > state.remaining', 'units >= state.remaining'),
                         ('elapsed >= state.limitNanos', 'elapsed > state.limitNanos'),
                         ('state.remaining -= units;', 'state.remaining += units;'),
                         ('CLOCK_CHECK_CALLS = 1024;', 'CLOCK_CHECK_CALLS = 1025;'),
                         ('private int clockChecks = CLOCK_CHECK_CALLS;', 'private int clockChecks = 0;')):
            with self.subTest(old=old):
                self.assertEqual(self.source.count(old), 1)
                self.assertNotEqual(bridge.generate(self.source.replace(old, new)), bridge.generate(self.source))

    def test_unregistered_statements_are_refused_instead_of_omitted(self):
        changed = self.source.replace('state.remaining -= units;', 'System.exit(0); state.remaining -= units;')
        with self.assertRaises(bridge.BridgeRejected):
            bridge.generate(changed)

    def test_unknown_methods_bindings_and_field_types_are_refused(self):
        for changed in (
                self.source.replace('private WorkBudget() { }',
                                    'private WorkBudget() { } public static void charge(int n) { }'),
                self.source.replace('private long remaining;', 'private int remaining;'),
                self.source.replace('new ThreadLocal<>()', 'new OtherThreadLocal<>()')):
            with self.subTest(source=changed[:40]), self.assertRaisesRegex(bridge.BridgeRejected, 'Unregistered WorkBudget'):
                bridge.generate(changed)

    def test_java_prelexical_unicode_escapes_are_refused(self):
        with self.assertRaisesRegex(bridge.BridgeRejected, 'Unicode escapes'):
            bridge.generate(self.source.replace('units > state.remaining', r'units \u003e state.remaining'))

    def test_missing_or_early_publication_checkpoint_is_refused(self):
        source = (ROOT / bridge.FEEDBACK).read_text()
        template = (ROOT / bridge.PUBLICATION).read_text()
        for changed in (source.replace('            WorkBudget.checkpoint();\n', ''),
                        source.replace('            WorkBudget.checkpoint();\n            return response;',
                                       '            return response;\n            WorkBudget.checkpoint();')):
            with self.assertRaisesRegex(bridge.BridgeRejected, 'Publication checkpoint'):
                bridge.check_publication(changed, template)

    def test_unbounded_calibration_entry_is_not_the_default_public_entry(self):
        source = (ROOT / bridge.FEEDBACK).read_text()
        self.assertIn('return evaluate(request, WORK_BUDGET, configuredWorkMillis());', source)
        self.assertIn('return evaluateBudgeted(request, fuel, 0);', source)


if __name__ == '__main__':
    unittest.main()
