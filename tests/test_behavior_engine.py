"""Real JVM witnesses for bounded ACGN reward and four fact-aware categories.

These finite tests do not establish unbounded equivalence or representative
sampling. Private fixture input and raw engine streams are never in failures.
"""
import json
import math
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
CLASSPATH = str(ROOT / 'build/engine/classes') + ':' + str(ROOT / 'vendor/acgn/lib/*')
COMMAND = ['java', '-Xmx256m', '-XX:ActiveProcessorCount=2', '-cp', CLASSPATH,
           'live.BehaviorFeedback']


def request(student, oracle, environment='sig A {}\nsig B {}\n'):
    prefix = environment + 'pred target {\n'
    return {'studentSource': prefix + student + '\n}\n',
            'oracleSource': prefix + oracle + '\n}\n',
            'studentBody': student, 'predicate': 'target'}


def invoke(payload, command=COMMAND):
    try:
        run = subprocess.run(command, input=json.dumps(payload), text=True,
                             capture_output=True, timeout=25, cwd=ROOT, check=False)
    except subprocess.TimeoutExpired:
        raise AssertionError('Behavior JVM timed out') from None
    if run.returncode or run.stderr:
        raise AssertionError('Behavior JVM failed its clean JSON boundary')
    try:
        return json.loads(run.stdout)
    except ValueError:
        raise AssertionError('Behavior JVM returned invalid JSON') from None


class BehaviorEngineTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not (ROOT / 'build/engine/classes/live/BehaviorFeedback.class').is_file():
            raise AssertionError('Build the behavioral JVM engine before testing')
        cls.temporary = tempfile.TemporaryDirectory(prefix='alloy-behavior-authority-')
        cls.addClassCleanup(cls.temporary.cleanup)
        helper = Path(cls.temporary.name) / 'RewardAuthority.java'
        helper.write_text('''
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.*;
import is.fivefivefive.ACGN.learn.Rewarder;
import org.json.JSONObject;
public class RewardAuthority {
 public static void main(String[] args) throws Exception {
  var wire = System.out;
  System.setOut(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
  System.setErr(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
  var q = new JSONObject(new String(System.in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
  CompModule m = CompUtil.parseEverything_fromString(A4Reporter.NOP, q.getString("oracleSource"));
  var pools = Rewarder.instances(m, "target", 100);
  double reward = Rewarder.computeReward(m, pools, "target", "learner", "{\\n" + q.getString("studentBody") + "\\n}", 100);
  wire.println(new JSONObject().put("score", Math.round(reward * 1000.0) / 1000.0));
 }
}
''')
        run = subprocess.run(['javac', '-encoding', 'UTF-8', '--release', '17', '-cp', CLASSPATH,
                              '-sourcepath', str(ROOT / 'vendor/acgn/src'), '-d', cls.temporary.name,
                              str(helper)], capture_output=True, timeout=25, cwd=ROOT)
        if run.returncode:
            raise AssertionError('Independent ACGN reward authority did not compile')
        cls.authority = COMMAND[:]
        cls.authority[-2] = cls.temporary.name + ':' + CLASSPATH
        cls.authority[-1] = 'RewardAuthority'

    def checked(self, payload):
        result = invoke(payload)
        self.assertEqual(result.get('status'), 'ok', 'Behavior fixture did not finish')
        self.assertEqual(set(result), {'status', 'metric', 'score', 'scoreStatus', 'scoreReason',
                                      'scope', 'sampling', 'categories'})
        self.assertEqual(result['metric'], 'acgn-reward')
        self.assertEqual(result['scope'], {'overall': 3, 'bitwidth': 3, 'maxSequence': 3,
                                          'poolSize': 100, 'minTrace': 1, 'maxTrace': 10,
                                          'moduleFacts': True})
        counts = result['sampling']
        self.assertEqual(set(counts), {'positiveTested', 'positiveAccepted', 'negativeTested',
                                      'negativeRejected', 'semanticCounterexamples'})
        for count in counts.values():
            self.assertIs(type(count), int)
            self.assertGreaterEqual(count, 0)
            self.assertLessEqual(count, 100)
        self.assertLessEqual(counts['positiveAccepted'], counts['positiveTested'])
        self.assertLessEqual(counts['negativeRejected'], counts['negativeTested'])
        available = counts['positiveTested'] > 0 and counts['negativeTested'] > 0
        self.assertEqual(result['scoreStatus'], 'ok' if available else 'unavailable')
        categories = result['categories']
        self.assertEqual([category['id'] for category in categories],
                         ['both', 'undercoverage', 'overcoverage', 'neither'])
        directions = [(True, True), (True, False), (False, True), (False, False)]
        for category, polarity in zip(categories, directions):
            self.assertEqual(set(category), {'id', 'oracle', 'student', 'status',
                                              'enumerationComplete', 'instances'})
            self.assertEqual((category['oracle'], category['student']), polarity)
            self.assertIs(type(category['oracle']), bool)
            self.assertIs(type(category['student']), bool)
            self.assertIs(type(category['enumerationComplete']), bool)
            instances = category['instances']
            self.assertLessEqual(len(instances), 3)
            self.assertEqual(category['status'], 'sat' if instances else 'unsat')
            if len(instances) < 3:
                self.assertTrue(category['enumerationComplete'])
            for instance in instances:
                self.assertEqual(set(instance), {'traceLength', 'loopState', 'states', 'truncated', 'stringsAnonymized'})
                self.assertIs(type(instance['truncated']), bool)
                self.assertIs(type(instance['stringsAnonymized']), bool)
                self.assertGreaterEqual(instance['traceLength'], 1)
                self.assertLessEqual(len(instance['states']), 10)
                self.assertLess(instance['loopState'], instance['traceLength'])
                self.assertGreaterEqual(instance['loopState'], -1)
                for index, state in enumerate(instance['states']):
                    self.assertEqual(set(state), {'index', 'signatures', 'relations'})
                    self.assertEqual(state['index'], index)
                    self.assertLessEqual(len(state['signatures']), 128)
                    self.assertLessEqual(len(state['relations']), 128)
                    for signature in state['signatures']:
                        self.assertEqual(set(signature), {'label', 'atoms'})
                        self.assertLessEqual(len(signature['atoms']), 128)
                    for relation in state['relations']:
                        self.assertEqual(set(relation), {'label', 'arity', 'tuples'})
                        self.assertLessEqual(len(relation['tuples']), 512)
                        self.assertTrue(all(len(t) == relation['arity'] for t in relation['tuples']))
        perfect = available and counts['positiveAccepted'] == counts['positiveTested'] and counts['negativeRejected'] == counts['negativeTested']
        penalty = sum(categories[i]['status'] == 'sat' for i in (1, 2)) if perfect else 0
        self.assertEqual(counts['semanticCounterexamples'], penalty)
        if available:
            expected = counts['positiveAccepted'] * counts['negativeRejected'] / (
                counts['positiveTested'] * counts['negativeTested'] + penalty)
            expected = math.floor(expected * 1000 + 0.5) / 1000
            self.assertEqual(result['score'], expected)
            self.assertEqual(result['scoreReason'], 'OK')
        else:
            self.assertIsNone(result['score'])
            self.assertEqual(result['scoreReason'], 'ORACLE_POSITIVE_UNSAT' if counts['positiveTested'] == 0 else 'ORACLE_NEGATIVE_UNSAT')
        return result

    def test_four_categories_have_correct_concrete_instances_and_enumeration_caps(self):
        result = self.checked(request('some B', 'some A'))
        self.assertEqual(result['score'], 0.188)
        for category in result['categories']:
            self.assertEqual(category['status'], 'sat')
            for instance in category['instances']:
                sets = {s['label']: s['atoms'] for s in instance['states'][0]['signatures']}
                self.assertEqual(bool(sets['A']), category['oracle'])
                self.assertEqual(bool(sets['B']), category['student'])
        self.assertFalse(result['categories'][0]['enumerationComplete'])
        self.assertTrue(result['categories'][3]['enumerationComplete'])
        self.assertEqual(len(result['categories'][3]['instances']), 1)

    def test_fact_free_score_matches_vendored_rewarder_on_five_behaviors(self):
        for student in ('some A', 'no A', 'some A and some B', 'some A or some B', 'some B'):
            with self.subTest(behavior=['some A', 'no A', 'some A and some B', 'some A or some B', 'some B'].index(student)):
                payload = request(student, 'some A')
                actual = self.checked(payload)
                authority = invoke(payload, self.authority)
                self.assertEqual(actual['score'], authority['score'])

    def test_identical_and_inverted_predicates(self):
        same = self.checked(request('some A', 'some A'))
        self.assertEqual(same['score'], 1.0)
        self.assertEqual([c['status'] for c in same['categories']], ['sat', 'unsat', 'unsat', 'sat'])
        inverted = self.checked(request('no A', 'some A'))
        self.assertEqual(inverted['score'], 0.0)
        self.assertEqual([c['status'] for c in inverted['categories']], ['unsat', 'sat', 'sat', 'unsat'])

    def test_stronger_and_weaker_predicates_identify_direction(self):
        stronger = self.checked(request('some A and some B', 'some A'))
        weaker = self.checked(request('some A or some B', 'some A'))
        self.assertEqual([c['status'] for c in stronger['categories']], ['sat', 'sat', 'unsat', 'sat'])
        self.assertEqual([c['status'] for c in weaker['categories']], ['sat', 'unsat', 'sat', 'sat'])

    def test_module_facts_apply_to_samples_and_every_category(self):
        result = self.checked(request('some B', 'some A', 'sig A {}\nsig B {}\nfact { some A iff some B }\n'))
        self.assertEqual(result['score'], 1.0)
        self.assertEqual([c['status'] for c in result['categories']], ['sat', 'unsat', 'unsat', 'sat'])
        for category in result['categories']:
            for instance in category['instances']:
                sets = {s['label']: s['atoms'] for s in instance['states'][0]['signatures']}
                self.assertEqual(bool(sets['A']), bool(sets['B']))

    def test_declaration_multiplicity_and_signature_facts_are_preserved(self):
        result = self.checked(request('some A.r', 'some A', 'sig A {r: set A} {no r}\n'))
        self.assertEqual([c['status'] for c in result['categories']], ['unsat', 'sat', 'unsat', 'sat'])
        for category in result['categories']:
            for instance in category['instances']:
                self.assertEqual(instance['states'][0]['relations'][0]['tuples'], [])
        declared = self.checked(request('some A', 'some A', 'one sig A {}\n'))
        self.assertIsNone(declared['score'])
        self.assertEqual(declared['scoreReason'], 'ORACLE_NEGATIVE_UNSAT')

    def test_unsatisfiable_oracle_polarities_keep_witnesses_but_no_fabricated_score(self):
        for oracle, expected in [('some A or no A', 'ORACLE_NEGATIVE_UNSAT'),
                                  ('some A and no A', 'ORACLE_POSITIVE_UNSAT')]:
            result = self.checked(request('some B', oracle))
            self.assertIsNone(result['score'])
            self.assertEqual(result['scoreReason'], expected)
            self.assertEqual(sum(c['status'] == 'sat' for c in result['categories']), 2)
        inconsistent = self.checked(request('some B', 'some A', 'sig A {}\nsig B {}\nfact {some A and no A}\n'))
        self.assertIsNone(inconsistent['score'])
        self.assertTrue(all(c['status'] == 'unsat' for c in inconsistent['categories']))

    def test_tuples_expose_only_declared_signature_and_field_data(self):
        result = self.checked(request('some A.r', 'some A', 'sig A {r: set A}\n'))
        for category in result['categories']:
            for instance in category['instances']:
                state = instance['states'][0]
                self.assertEqual([s['label'] for s in state['signatures']], ['A'])
                self.assertEqual([r['label'] for r in state['relations']], ['A.r'])
                atoms = set(state['signatures'][0]['atoms'])
                self.assertTrue(all(set(row) <= atoms for row in state['relations'][0]['tuples']))
                self.assertEqual(bool(state['relations'][0]['tuples']), category['student'])
                self.assertEqual(bool(atoms), category['oracle'])
        self.assertNotIn('target', json.dumps(result))
        self.assertNotIn('some A', json.dumps(result))

    def test_temporal_instances_include_ordered_states_and_loop(self):
        result = self.checked(request('some A', 'eventually some A', 'var sig A {}\n'))
        witnesses = result['categories'][1]['instances']
        self.assertTrue(witnesses)
        for instance in witnesses:
            self.assertGreaterEqual(instance['traceLength'], 2)
            self.assertEqual(len(instance['states']), instance['traceLength'])
            self.assertFalse(instance['states'][0]['signatures'][0]['atoms'])
            self.assertTrue(any(s['signatures'][0]['atoms'] for s in instance['states'][1:]))

    def test_temporal_facts_are_enforced_over_the_trace(self):
        result = self.checked(request('some A', 'eventually some A',
                                      'var sig A {}\nfact {always no A}\n'))
        self.assertEqual(result['scoreReason'], 'ORACLE_POSITIVE_UNSAT')
        self.assertEqual([c['status'] for c in result['categories']], ['unsat', 'unsat', 'unsat', 'sat'])
        for instance in result['categories'][3]['instances']:
            self.assertTrue(all(not s['signatures'][0]['atoms'] for s in instance['states']))

    def test_helpers_have_the_same_meaning_in_the_shared_signature_context(self):
        result = self.checked(request('nonempty[A]', 'some A',
                                      'sig A {}\npred nonempty[x:set A] {some x}\n'))
        self.assertEqual(result['score'], 1.0)

    def test_recursive_learner_calls_cannot_become_private_oracle_calls(self):
        for student, environment in [('target[]', 'sig A {}\n'),
                                     ('helper[]', 'sig A {}\npred helper {target[]}\n'),
                                     ('helper[]', 'sig A {}\npred helper {other[]}\npred other {helper[]}\n')]:
            result = invoke(request(student, 'some A', environment))
            self.assertIn(result['status'], ['invalid', 'unsupported'])
            self.assertNotIn('score', result)
            self.assertNotIn('some A', json.dumps(result))

    def test_environment_dependencies_on_selected_predicate_are_explicitly_unsupported(self):
        for environment in ['sig A {}\nfact {target[]}\n',
                            'sig A {}\npred helper {target[]}\nfact {helper[]}\n',
                            'sig A {} {target[]}\n']:
            result = invoke(request('no A', 'some A', environment))
            self.assertEqual(result['status'], 'unsupported')
            self.assertEqual(result['diagnostics'][0]['code'], 'RECURSIVE_OR_CONTEXT_DEPENDENCY')

    def test_type_errors_mismatched_context_and_body_are_redacted(self):
        bad = invoke(request('secretUndefined', 'some A'))
        self.assertEqual(bad['status'], 'invalid')
        self.assertNotIn('secretUndefined', json.dumps(bad))
        altered = request('some A', 'some A')
        altered['studentSource'] = altered['studentSource'].replace('sig B', 'sig C')
        self.assertEqual(invoke(altered)['diagnostics'][0]['code'], 'CONTEXT_MISMATCH')
        altered = request('some A', 'some A')
        altered['studentBody'] = 'no A'
        self.assertEqual(invoke(altered)['diagnostics'][0]['code'], 'CONTEXT_MISMATCH')

    def test_comments_braces_and_unicode_do_not_break_body_identity(self):
        result = self.checked(request('// 😀 { } target[]\n\tsome A /* } */', 'some A'))
        self.assertEqual(result['score'], 1.0)

    def test_empty_body_is_a_valid_tautology(self):
        result = self.checked(request('', 'some A'))
        self.assertEqual(result['score'], 0.0)
        self.assertEqual([c['status'] for c in result['categories']], ['sat', 'unsat', 'sat', 'unsat'])

    def test_parameterized_selected_predicate_is_rejected(self):
        payload = request('some A', 'some A')
        payload['studentSource'] = payload['studentSource'].replace('pred target', 'pred target[x:A]')
        result = invoke(payload)
        self.assertEqual(result['status'], 'unsupported')

    def test_perfect_samples_receive_independent_semantic_counterexample_penalty(self):
        environment = 'sig A { r: set A }\nsig B { s: set B }\n'
        for student, expected_penalty in [
                ('some A and not (#A=2 and r=A->A)', 1),
                ('some A or (#B=2 and s=B->B)', 1),
                ('(some A and not (#A=2 and r=A->A)) or (#B=2 and s=B->B)', 2)]:
            result = self.checked(request(student, 'some A', environment))
            counts = result['sampling']
            self.assertEqual(counts['positiveTested'], 100)
            self.assertEqual(counts['negativeTested'], 100)
            self.assertEqual(counts['positiveAccepted'], 100)
            self.assertEqual(counts['negativeRejected'], 100)
            self.assertEqual(counts['semanticCounterexamples'], expected_penalty)
            # Rounding to three decimals can hide this small penalty; SAT
            # counterexample categories must still remain visible.
            self.assertEqual(result['score'], 1.0)
            self.assertEqual(result['score'], invoke(request(student, 'some A', environment), self.authority)['score'])

    def test_model_facts_remove_out_of_model_semantic_penalties(self):
        environment = 'sig A {r: set A}\nsig B {s: set B}\nfact {not (#A=2 and r=A->A)}\n'
        result = self.checked(request('some A and not (#A=2 and r=A->A)', 'some A', environment))
        self.assertEqual(result['score'], 1.0)
        self.assertEqual(result['sampling']['semanticCounterexamples'], 0)
        self.assertEqual(result['categories'][1]['status'], 'unsat')
        self.assertEqual(result['categories'][2]['status'], 'unsat')

    def test_private_string_literals_are_consistently_anonymized(self):
        secret = 'PRIVATE_LITERAL_MUST_NOT_APPEAR'
        result = self.checked(request('some A.value', f'some a:A | a.value="{secret}"',
                                      'sig A {value: lone String}\n'))
        self.assertNotIn(secret, json.dumps(result))
        self.assertNotIn('target', json.dumps(result))
        found = False
        for category in result['categories']:
            for instance in category['instances']:
                for state in instance['states']:
                    for relation in state['relations']:
                        for row in relation['tuples']:
                            self.assertRegex(row[1], r'^String\$[0-9]+$')
                            self.assertTrue(instance['stringsAnonymized'])
                            found = True
        self.assertTrue(found)

    def test_integer_tuple_values_remain_numeric(self):
        result = self.checked(request('some A', 'some A', 'sig A {number: one Int}\n'))
        for category in result['categories']:
            for instance in category['instances']:
                self.assertFalse(instance['stringsAnonymized'])
                for row in instance['states'][0]['relations'][0]['tuples']:
                    self.assertRegex(row[1], r'^-?[0-9]+$')

    def test_excessive_labels_and_relation_arity_are_explicitly_truncated(self):
        name = 'A' * 257
        result = self.checked(request(f'some {name}', f'some {name}', f'sig {name} {{}}\n'))
        for category in result['categories']:
            for instance in category['instances']:
                self.assertTrue(instance['truncated'])
                self.assertEqual(instance['states'][0]['signatures'], [])
        result = self.checked(request('some A.r', 'some A.r', 'one sig A {r: A->A->A->A->A->A->A->A}\n'))
        for category in result['categories']:
            for instance in category['instances']:
                self.assertTrue(instance['truncated'])
                self.assertEqual(instance['states'][0]['relations'], [])

    def test_learner_only_string_literals_do_not_get_evaluated_in_the_wrong_universe(self):
        payload = request('some a:A | a.value="LEARNER_ONLY"', 'some A.value',
                          'sig A {value:lone String}\n')
        result = invoke(payload)
        self.assertEqual(result['status'], 'unsupported')
        self.assertEqual(result['diagnostics'][0]['code'], 'STUDENT_STRING_UNIVERSE')
        self.assertNotIn('LEARNER_ONLY', json.dumps(result))
        self.assertNotIn('score', result)
        # Even if the original String universe makes all module facts UNSAT,
        # adding a learner literal must not create category-only satisfiability.
        payload = request('some a:A | a.value="LEARNER_ONLY"', 'some A',
                          'sig A {value:lone String}\nfact {some String}\n')
        self.assertEqual(invoke(payload)['diagnostics'][0]['code'], 'STUDENT_STRING_UNIVERSE')

    def test_real_graph_and_temporal_catalogue_exercises(self):
        from server import model
        records = {record['id']: record for record in
                   json.loads((ROOT / 'exercises/catalogue.json').read_text())['exercises']}
        for identifier in ('graphs-inv5', 'trainStationOld-inv1', 'trash_ltl-inv1'):
            with self.subTest(exercise=identifier):
                record = records[identifier]
                body = 'some (iden & adj)' if identifier == 'graphs-inv5' else record['starter']
                payload = {'studentSource': model(record, body),
                           'oracleSource': model(record, record['oracleBody']),
                           'studentBody': body, 'predicate': record['predicate']}
                result = self.checked(payload)
                self.assertTrue(any(category['instances'] for category in result['categories']))
                if identifier == 'graphs-inv5':
                    self.assertEqual(result['score'], 0.0)
                    self.assertEqual([c['status'] for c in result['categories']],
                                     ['unsat', 'sat', 'sat', 'unsat'])
