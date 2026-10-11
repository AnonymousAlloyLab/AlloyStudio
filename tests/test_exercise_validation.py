"""Private import gate witnesses using the real installed Alloy API and solver."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCRATCH = ROOT / 'build/exercise-validation-tests'
COMMAND = ['java', '-Xmx256m', '-XX:ActiveProcessorCount=2', '-cp',
           os.pathsep.join((str(ROOT / 'build/engine/classes'), str(ROOT / 'vendor/acgn/lib/*'))),
           'live.ExerciseValidator']


def request(oracles=('some Node',), correct=(), *, starter='some Node', environment='sig Node {}\n',
            scope=5, header='pred target '):
    return {'predicate': 'target', 'sourcePrefix': environment + header + '{\n', 'sourceSuffix': '\n}\n',
            'starter': starter, 'oracleBodies': list(oracles), 'correctBodies': list(correct), 'scope': scope}


def invoke(payload, *, command=COMMAND, cwd=ROOT):
    encoded = payload if isinstance(payload, bytes) else json.dumps(payload).encode('utf-8')
    SCRATCH.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='validator-', dir=SCRATCH) as folder:
        owned_command = list(command)
        if not any(value.startswith('-Djava.io.tmpdir=') for value in owned_command):
            owned_command.insert(1, '-Djava.io.tmpdir=' + folder)
        try:
            result = subprocess.run(owned_command, input=encoded, cwd=cwd, capture_output=True, timeout=60, check=False)
        except subprocess.TimeoutExpired:
            raise AssertionError('Private validation exceeded its process deadline') from None
    if result.returncode or result.stderr:
        raise AssertionError('Private validator violated its clean JSON boundary')
    try:
        return json.loads(result.stdout)
    except (ValueError, UnicodeError):
        raise AssertionError('Private validator did not return JSON') from None


class ExerciseValidationTests(unittest.TestCase):
    def accepted(self, payload):
        result = invoke(payload)
        self.assertEqual(result.get('status'), 'ok', 'The valid bounded-equivalence fixture was rejected')
        self.assertEqual(result['scope'], payload.get('scope', 5))
        self.assertEqual(result['maxSequence'], payload.get('scope', 5))
        self.assertEqual(result['bitwidth'], 5)
        self.assertEqual((result['minTrace'], result['maxTrace']), (1, 10))
        self.assertEqual(result['solver'], 'SAT4J')
        self.assertTrue(result['moduleFacts'])
        self.assertTrue(result['factsSatisfiable'])
        self.assertEqual(result['equivalence'], 'bounded')
        count = len(payload['oracleBodies']) + len(payload['correctBodies'])
        self.assertEqual(result['evaluatedCandidates'], count)
        self.assertEqual(result['equivalenceChecks'], count - 1)
        self.assertEqual(result['oracleCount'], len(payload['oracleBodies']))
        self.assertEqual(result['correctCount'], len(payload['correctBodies']))
        self.assertEqual(set(result), {'status', 'scope', 'bitwidth', 'maxSequence', 'minTrace', 'maxTrace',
                                      'solver', 'moduleFacts', 'factsSatisfiable', 'equivalence',
                                      'evaluatedCandidates', 'equivalenceChecks', 'oracleCount', 'correctCount'})
        return result

    def rejected(self, payload, code=None, index=None):
        result = invoke(payload)
        self.assertEqual(result.get('status'), 'rejected')
        self.assertEqual(set(result) - {'candidateIndex'}, {'status', 'code'})
        if code is not None:
            self.assertEqual(result['code'], code)
        if index is not None:
            self.assertEqual(result.get('candidateIndex'), index)
        return result

    def test_multiple_oracles_and_correct_bodies_are_all_checked(self):
        self.accepted(request(('some Node', 'not no Node'), ('#Node > 0',), starter='no Node'))

    def test_default_scope_and_empty_starter_are_valid(self):
        payload = request(starter='')
        del payload['scope']
        self.accepted(payload)

    def test_model_facts_make_otherwise_different_bodies_equivalent(self):
        self.accepted(request(('some Node', 'one Node'), environment='sig Node {}\nfact { one Node }\n'))

    def test_inconsistent_module_facts_are_rejected_before_equivalence(self):
        self.rejected(request(('some Node', 'no Node'),
                              environment='sig Node {}\nfact { some Node and no Node }\n'),
                      'INCONSISTENT_FACTS')

    def test_unsatisfiable_primary_is_allowed_when_facts_are_consistent(self):
        self.accepted(request(('some Node and no Node', 'not (some Node or no Node)')))

    def test_inequivalent_oracle_and_correct_candidate_are_rejected(self):
        self.rejected(request(('some Node', 'no Node')), 'NOT_EQUIVALENT', 1)
        self.rejected(request(('some Node', 'not no Node'), ('no Node',)), 'NOT_EQUIVALENT', 2)

    def test_scope_five_agreement_does_not_hide_scope_six_counterexample(self):
        self.accepted(request(('#Node > 5', 'some Node and no Node'), scope=5))
        self.rejected(request(('#Node > 5', 'some Node and no Node'), scope=6), 'NOT_EQUIVALENT', 1)

    def test_later_invalid_candidate_cannot_be_skipped_after_earlier_equivalence(self):
        self.rejected(request(('some Node', 'not no Node'), ('some MissingPrivateName',)), 'INVALID_MODEL', 2)

    def test_starter_must_parse_even_when_every_oracle_is_valid(self):
        self.rejected(request(starter='Node = some'), 'INVALID_MODEL')

    def test_escaping_body_is_rejected_in_every_position(self):
        escaped = 'some Node } fact injected { no Node } pred extra { some Node'
        with self.subTest(position='starter'):
            self.rejected(request(starter=escaped), 'BODY_CONTAINMENT')
        with self.subTest(position='primary'):
            self.rejected(request((escaped,)), 'BODY_CONTAINMENT', 0)
        with self.subTest(position='additional'):
            self.rejected(request(correct=(escaped,)), 'BODY_CONTAINMENT', 1)

    def test_lexical_containment_rejects_open_and_mismatched_delimiters(self):
        for body in ('some Node /* hidden closing brace', '"unterminated', '(some Node]',
                     '{ some Node', 'some Node }'):
            with self.subTest(case=body[:1]):
                self.rejected(request((body,)), 'BODY_CONTAINMENT', 0)

    def test_comments_and_string_braces_are_not_body_boundaries(self):
        self.accepted(request(('some Node /* } ] ) */ and ("}" = "}")', 'some Node // }'),
                              ('(some Node) and ("}" = "}")',)))
        self.accepted(request(('some Node -- }', 'some Node /* { */')))

    def test_new_string_literal_cannot_make_equivalence_vacuous(self):
        # Previously the extra literal B forced two String atoms, making
        # #String=1 false in the equivalence query despite SAT primary facts.
        self.rejected(request(('"A" in String', '"B" in String and no Node'),
                              environment='sig Node {}\nfact { #String = 1 and "A" in String }\n'),
                      'UNSUPPORTED_STRING_UNIVERSE', 1)
        self.rejected(request(('"A" in String', '"B" in String and no Node'),
                              environment='sig Node {}\nfact { #String = 1 }\n'),
                      'UNSUPPORTED_STRING_UNIVERSE', 1)

    def test_facts_satisfiability_uses_the_same_primary_string_universe(self):
        self.accepted(request(('"A" in String', 'some Node or no Node'),
                              environment='sig Node {}\nfact { #String = 1 }\n'))
        self.rejected(request(('"A" in String', 'no Node'),
                              environment='sig Node {}\nfact { #String = 1 }\n'),
                      'NOT_EQUIVALENT', 1)

    def test_selected_ast_body_must_be_the_supplied_body(self):
        payload = request(environment='sig Node {}\npred other { some Node }\n')
        payload['predicate'] = 'other'
        self.rejected(payload, 'BODY_CONTAINMENT')

    def test_explicit_parameterized_bodies_are_type_checked_and_pointwise_equivalent(self):
        cases = [('[n: Node]', 'some n', 'not no n'),
                 ('[n: set Node]', 'some n', '#n > 0'),
                 ('[n: lone Node]', 'some n', 'not no n'),
                 ('[r: Node -> Node]', 'some r', '#r > 0'),
                 ('[disj n, m: Node]', 'n != m', 'some n and some m'),
                 ('[n: Node, m: n.edge]', 'm in n.edge', 'some m')]
        for parameters, primary, candidate in cases:
            with self.subTest(parameters=parameters):
                self.accepted(request((primary, candidate), starter='',
                    header='pred target' + parameters + ' ', environment='sig Node { edge: set Node }\n', scope=3))

    def test_pointwise_check_does_not_compare_separate_universal_closures(self):
        # Both universal closures are false under these facts; nevertheless
        # the predicates disagree for every supplied argument.
        environment = 'sig Node {}\nsig Marker in Node {}\nfact { some Marker and some Node - Marker }\n'
        self.rejected(request(('n in Marker', 'n not in Marker'), starter='', environment=environment,
            header='pred target[n: Node] ', scope=3), 'NOT_EQUIVALENT', 1)

    def test_exact_bound_parameter_aliases_preserve_their_assignment_constraints(self):
        environment = 'sig Node { edge: set Node }\n'
        cases = [('[n = Node]', 'no n', 'no Node'),
                 ('[n: Node, m = n.edge]', 'no m', 'no n.edge'),
                 ('[r = Node->Node]', 'some r', 'some Node')]
        for parameters, primary, alternative in cases:
            with self.subTest(parameters=parameters):
                self.accepted(request((primary, alternative), starter='', environment=environment,
                                      header='pred target' + parameters + ' ', scope=3))
        # Both bodies are false when Node is empty, but differ on nonempty Node.
        # Allowing arbitrary subsets for n instead of the exact Node value also
        # breaks the accepted equivalent pair above.
        self.rejected(request(('some n', 'no n'), starter='', environment=environment,
                              header='pred target[n = Node] ', scope=3), 'NOT_EQUIVALENT', 1)

    def test_parameterized_temporal_equivalence_uses_one_argument_for_the_trace(self):
        environment = 'sig Node {}\nvar sig Active in Node {}\n'
        self.accepted(request(('eventually n in Active', 'not always n not in Active'),
            starter='', environment=environment, header='pred target[n: Node] ', scope=2))
        self.rejected(request(('eventually n in Active', 'n in Active'), starter='',
            environment=environment, header='pred target[n: Node] ', scope=2), 'NOT_EQUIVALENT', 1)

    def test_parameterized_equivalence_enforces_model_facts(self):
        environment = 'sig Node {}\nsig Marker in Node {}\nfact { Node = Marker }\n'
        self.accepted(request(('n in Marker', 'some n'), starter='', environment=environment,
            header='pred target[n: Node] ', scope=3))

    def test_fixed_parameter_domains_pin_the_string_universe(self):
        self.accepted(request(('some n', 'n in String'), starter='',
            environment='sig Node {}\nfact { #String = 1 }\n', header='pred target[n: "A"] '))
        self.rejected(request(('n = "A"', 'n = "B"'), starter='',
            environment='sig Node {}\nfact { #String = 1 and "A" in String }\n',
            header='pred target[n: String] '), 'UNSUPPORTED_STRING_UNIVERSE', 1)

    def test_unused_arguments_do_not_hide_pointwise_disagreement(self):
        for parameters in ('[n: Node]', '[n: set Node]', '[r: Node -> Node]'):
            with self.subTest(parameters=parameters):
                self.rejected(request(('some Node or no Node', 'some Node and no Node'), starter='',
                    header='pred target' + parameters + ' ', scope=2), 'NOT_EQUIVALENT', 1)

    def test_later_parameterized_body_is_independently_type_checked(self):
        self.rejected(request(('some n', 'not no n'), ('some missingParameter',), starter='',
            header='pred target[n: Node] '), 'INVALID_MODEL', 2)

    def test_parameter_limits_and_implicit_receiver_fail_closed(self):
        admitted = ', '.join(f'n{i}: Node' for i in range(16))
        self.accepted(request(header='pred target[' + admitted + '] ', scope=1))
        arguments = ', '.join(f'n{i}: Node' for i in range(17))
        self.rejected(request(header='pred target[' + arguments + '] '), 'UNSUPPORTED_PARAMETERS')
        self.rejected(request(header='pred target[n: Node] /*' + ' ' * 8192 + '*/ '),
                      'UNSUPPORTED_PARAMETERS')
        self.rejected(request(('some edge',), starter='', header='pred Node.target ',
            environment='sig Node { edge: set Node }\n'), 'UNSUPPORTED_PARAMETERS')

    def test_recursive_or_context_dependent_selected_predicate_is_rejected(self):
        for payload in [request(('target',), starter='target'),
                        request(environment='sig Node {}\nfact { target }\n'),
                        request(environment='sig Node {}\npred helper { helper }\n',
                                starter='helper', oracles=('helper',))]:
            with self.subTest(kind=payload['starter']):
                result = self.rejected(payload)
                self.assertIn(result['code'], {'UNSUPPORTED_DEPENDENCY', 'INVALID_MODEL'})

    def test_inactive_nonrecursive_callers_do_not_change_generated_query_context(self):
        self.accepted(request(environment='sig Node {}\npred helper { target }\n'
            'assert demonstration { helper }\ncheck demonstration\n'))

    def test_independent_helpers_are_available_in_the_common_world(self):
        self.accepted(request(('helper', 'not no Node'), environment='sig Node {}\npred helper { some Node }\n'))

    def test_builtin_alloy_modules_remain_available(self):
        self.accepted(request(('some Node', '#Node > 0'),
                              environment='open util/ordering[Node]\nsig Node {}\n'))

    def test_external_modules_beside_the_generated_source_cannot_be_certified(self):
        # parseEverything_fromString writes a root file to java.io.tmpdir. A
        # sibling module previously entered the certificate without a hash or
        # a deployment copy; ordinary working-directory tests miss this case.
        SCRATCH.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='alloy-external-module-', dir=SCRATCH) as directory:
            folder = Path(directory)
            (folder / 'helper.als').write_text(
                'module helper\nsig Node {}\npred accepts { some Node }\n', encoding='utf-8')
            command = [COMMAND[0], '-Djava.io.tmpdir=' + directory, *COMMAND[1:]]
            result = invoke(request(('accepts', 'some Node'), environment='open helper\n'),
                            command=command, cwd=folder)
            self.assertEqual(result, {'status': 'rejected', 'code': 'UNSUPPORTED_EXTERNAL_MODULE'})

    def test_filesystem_shadow_of_builtin_module_is_not_a_bundled_dependency(self):
        SCRATCH.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='alloy-shadow-module-', dir=SCRATCH) as directory:
            folder = Path(directory)
            (folder / 'util').mkdir()
            (folder / 'util/ordering.als').write_text(
                'module util/ordering[elem]\npred spoof { some elem }\n', encoding='utf-8')
            command = [COMMAND[0], '-Djava.io.tmpdir=' + directory, *COMMAND[1:]]
            result = invoke(request(('some Node', 'not no Node'),
                                    environment='open util/ordering[Node]\nsig Node {}\n'),
                            command=command, cwd=folder)
            self.assertEqual(result, {'status': 'rejected', 'code': 'UNSUPPORTED_EXTERNAL_MODULE'})

    def test_temporal_bounds_support_equivalence_and_counterexamples(self):
        self.accepted(request(('always some Node', 'not eventually no Node'),
                              environment='var sig Node {}\n', scope=1))
        self.rejected(request(('some Node', 'always some Node'),
                              environment='var sig Node {}\n', scope=1), 'NOT_EQUIVALENT', 1)

    def test_scope_type_and_range_are_strict(self):
        for scope in (0, 9, True, 5.0, '5', None):
            with self.subTest(scope=scope):
                self.rejected(request(scope=scope), 'INVALID_REQUEST')
        self.accepted(request(scope=8))

    def test_reference_inventory_and_utf8_constraints_fail_closed(self):
        for oracles, correct in [((), ()), (('some Node', 'some Node'), ()),
                                 (('some Node',), ('some Node',)), (('',), ()),
                                 ((True,), ()), (('some Node\0',), ()), (('\ud800',), ())]:
            with self.subTest(count=len(oracles) + len(correct)):
                self.rejected(request(oracles, correct), 'INVALID_REQUEST')
        payload = request()
        payload['unknown'] = 'not accepted'
        self.rejected(payload, 'INVALID_REQUEST')

    def test_request_body_and_source_limits_and_duplicate_keys(self):
        self.rejected(request(('a' * 8193,)), 'INVALID_REQUEST')
        self.rejected(request(tuple(f'some Node // {index}' for index in range(257))), 'INVALID_REQUEST')
        self.rejected(request(environment=' ' * 262144), 'INVALID_REQUEST')
        self.rejected(b' ' * 1048577, 'REQUEST_TOO_LARGE')
        self.rejected(b'{"predicate":"target","predicate":"other"}', 'INVALID_REQUEST')
        self.rejected(b'{"predicate":"\xff"}', 'INVALID_REQUEST')


class ParameterizedSourceInspectionTests(unittest.TestCase):
    @staticmethod
    def inspect(source):
        return invoke(dict(source=source, equivalenceScope=3, variantGroups=[['inv1C0', 'inv1C1']]),
                      command=COMMAND[:-1] + ['live.UploadInspector'])

    def test_original_marked_variants_are_compared_at_the_same_argument_assignment(self):
        source = ('sig Node {}\nsig Marker in Node {}\n'
                  'fact { some Marker and some Node - Marker }\n'
                  'pred inv1C0[n: Node] { n in Marker }\n'
                  'pred inv1C1[n: Node] { n not in Marker }\n')
        self.assertEqual(self.inspect(source), {'status': 'rejected', 'code': 'NOT_EQUIVALENT'})

    def test_original_explicit_parameterized_equivalent_variants_are_accepted(self):
        for parameters, primary, alternate in [('[n: set Node]', 'some n', 'not no n'),
                ('[r: Node -> Node]', 'some r', '#r > 0'),
                ('[disj n, m: Node]', 'n != m', 'some n and some m'),
                ('[n: Node, m: n.edge]', 'm in n.edge', 'some m')]:
            with self.subTest(parameters=parameters):
                source = ('sig Node { edge: set Node }\n'
                          f'pred inv1C0{parameters} {{ {primary} }}\n'
                          f'pred inv1C1{parameters} {{ {alternate} }}\n')
                observed = self.inspect(source)
                self.assertEqual(observed['status'], 'ok')
                self.assertEqual(observed['variantEquivalence']['groupCount'], 1)
                self.assertEqual(observed['variantEquivalence']['variantCount'], 2)

    def test_original_variant_parameter_signatures_cannot_change(self):
        cases = [('[n: Node]', '[m: Node]', 'some n', 'some m'),
                 ('[n: Node]', '[n: Other]', 'some n', 'some n'),
                 ('[n: set Node]', '[n: lone Node]', 'some n', 'some n'),
                 ('[n, m: Node]', '[n: Node, m: Node]', 'some n', 'some n'),
                 ('[disj n, m: Node]', '[n, m: Node]', 'some n', 'some n'),
                 ('[n: Node, m: n.edge]', '[n: Node, m: Node]', 'some n', 'some n')]
        for first, second, primary, alternate in cases:
            with self.subTest(first=first, second=second):
                source = ('sig Node { edge: set Node }\nsig Other {}\n'
                          f'pred inv1C0{first} {{ {primary} }}\n'
                          f'pred inv1C1{second} {{ {alternate} }}\n')
                self.assertEqual(self.inspect(source),
                                 {'status': 'rejected', 'code': 'PARAMETER_SIGNATURE_MISMATCH'})


if __name__ == '__main__':
    unittest.main(verbosity=2)
