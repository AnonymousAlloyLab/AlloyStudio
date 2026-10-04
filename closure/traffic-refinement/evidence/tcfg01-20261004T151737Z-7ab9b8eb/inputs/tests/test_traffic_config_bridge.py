"""Negative controls for the restricted production numeric guard translator."""
import ast
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
import traffic_config_bridge as bridge


class ConfigBridgeTests(unittest.TestCase):
    def setUp(self):
        self.source = (ROOT / 'traffic_limits.py').read_text()

    def test_actual_source_has_exact_registered_lowering(self):
        self.assertEqual(bridge.check(ROOT)['status'], 'PASS')

    def test_return_mutation_is_rejected(self):
        with self.assertRaises(bridge.BridgeRejected):
            bridge.generate(self.source.replace('return value', 'return 1', 1))

    def test_malformed_syntax_is_a_closed_rejection(self):
        with self.assertRaises(bridge.BridgeRejected):
            bridge.generate('def missing(')

    def test_type_or_normalization_mutation_is_rejected(self):
        for old, new in [('type(value) is not int', 'not isinstance(value, int)'),
                         ('value.as_integer_ratio()', '(1, 1)'),
                         ('(ValueError, OverflowError)', '(ValueError,)'),
                         ('denominator <= 0', 'denominator < 0')]:
            with self.subTest(old=old), self.assertRaises(bridge.BridgeRejected):
                bridge.generate(self.source.replace(old, new))

    def test_extra_execution_shadowing_and_default_changes_are_rejected(self):
        for mutated in [self.source + '\nx = 1\n', 'int = str\n' + self.source,
                        self.source.replace('return value', 'print(value)\n    return value', 1),
                        self.source.replace('maximum=86400', 'maximum=86401')]:
            with self.assertRaises(bridge.BridgeRejected):
                bridge.generate(mutated)

    def test_dynamic_diagnostics_are_rejected(self):
        with self.assertRaises(bridge.BridgeRejected):
            bridge.generate(self.source.replace("'Invalid integer bounds.'", 'str(value)'))

    def test_tuple_membership_cannot_replace_builtin_type_identity(self):
        mutated = self.source.replace('type(value) is not int and type(value) is not float',
                                      'type(value) not in (int, float)')
        self.assertNotEqual(mutated, self.source)
        with self.assertRaises(bridge.BridgeRejected):
            bridge.generate(mutated)

    def test_production_guard_rejects_metaclass_equality_without_calling_user_code(self):
        # Execute only the source already accepted by the closed AST recognizer.
        bridge.extract(self.source)
        namespace = {}
        exec(compile(self.source, 'traffic_limits.py', 'exec'), namespace)
        class Impersonator(type):
            def __eq__(cls, other):
                return other is int or other is float
        class ForgedNumber(metaclass=Impersonator):
            def as_integer_ratio(self):
                self.fail_if_called = True
                return (1, 1)
        value = ForgedNumber()
        with self.assertRaises(ValueError):
            namespace['validated_seconds'](value)
        self.assertFalse(hasattr(value, 'fail_if_called'))

    def test_actual_arithmetic_changes_the_generated_program(self):
        before = bridge.generate(self.source)
        after = bridge.generate(self.source.replace('numerator == 0', 'numerator < 0'))
        self.assertNotEqual(before, after)
        self.assertIn('less numerator 0', after)

    def test_arbitrary_calls_or_unknown_variables_in_guard_are_rejected(self):
        for condition in ('unknown', 'bool(value)', 'numerator / denominator > maximum',
                          'numerator ** 2 > maximum', 'numerator is None'):
            with self.subTest(condition=condition), self.assertRaises(bridge.BridgeRejected):
                bridge.generate(self.source.replace('numerator > maximum * denominator', condition))

    def test_builtin_identity_and_ratio_exception_controls_are_present(self):
        guards = bridge.extract(self.source)
        self.assertEqual(set(guards), {'integer_bounds', 'integer_value', 'seconds_bounds', 'seconds_value'})
        self.assertIn('Not', ast.dump(guards['integer_value']))


if __name__ == '__main__':
    unittest.main()
