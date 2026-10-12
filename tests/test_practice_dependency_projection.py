"""Pure projection/witness contracts; actual Alloy interpretation stays independent."""
import copy
import hashlib
import json
from contextlib import nullcontext
from pathlib import Path
import tempfile
import unittest

import admin_upload as upload
import exercise_store as store
from practice_dependencies import (dependency_plan_sha, public_plan, public_slots,
                                   render_context, validate_plan, validation_record)


def project(source, calls, *, parameters=None, parameter_calls=None, facts=(), selected=None):
    declarations, groups = upload._groups(source)
    parameters, parameter_calls = parameters or {}, parameter_calls or {}
    variants = [group for group in groups if group['kind'] == 'group']
    inspection = dict(status='ok', sourceSha256=upload._hash(source), moduleName='test',
        declarations=[dict(item, parameterCount=len(parameters.get(item['name'], [])),
                           calls=calls.get(item['name'], []), parameterNames=parameters.get(item['name'], []),
                           parameterCalls=parameter_calls.get(item['name'], [])) for item in declarations],
        factCalls=list(facts), assertionCalls=[], commandCalls=[], variantEquivalence=dict(
            groupCount=len(variants), variantCount=sum(len(group['variants']) for group in variants),
            scope=5, bitwidth=5, maxSequence=5, minTrace=1, maxTrace=10, solver='SAT4J',
            moduleFacts=True, factsSatisfiable=True, equivalence='bounded'))
    return upload._plan(source, 'test.als', 'test', 5, selected, projection_version=3, inspection=inspection)


class ProjectionTests(unittest.TestCase):
    def test_chain_and_hidden_validation_environment(self):
        source = 'sig Node {}\npred A {some Node}\npred B {A[]}\npred C {B[]}\n'
        documents, witness, _ = project(source, {'B': ['A'], 'C': ['B']})
        self.assertTrue(upload.validate_witness(witness, documents))
        a, b, c = documents
        self.assertEqual(a['practicePlan']['dependencies'], [])
        self.assertEqual(b['practicePlan']['direct'], ['test-A'])
        self.assertEqual(c['practicePlan']['direct'], ['test-B'])
        self.assertEqual(c['practicePlan']['dependencies'], ['test-A', 'test-B'])
        self.assertNotIn('some Node', c['environmentBefore'])
        self.assertIn('some Node', validation_record(c)['environmentBefore'])
        self.assertIn('A[]', validation_record(c)['environmentBefore'])
        self.assertNotIn('validationEnvironmentBefore', public_plan(c))
        resolved = render_context(c, {'test-A': 'some Node', 'test-B': 'A[]'})
        self.assertNotIn('practicePlan', resolved)
        self.assertIn('some Node', resolved['environmentBefore'])
        with self.assertRaises(ValueError):
            render_context(c, {'test-B': 'A[]'})

    def test_parameterized_forwarding_aliases_use_inspected_order(self):
        source = ('sig Node {}\npred inv1C0[x, y: Node] {x = y}\n'
                  'pred inv1C1[x, y: Node] {x = y}\npred inv2C0[x, y: Node] {inv1C1[x,y]}\n')
        documents, witness, _ = project(source, {'inv2C0': ['inv1C1']},
            parameters={name: ['x', 'y'] for name in ('inv1C0', 'inv1C1', 'inv2C0')})
        dependent = documents[1]
        self.assertEqual(dependent['practicePlan']['dependencies'], ['test-inv1'])
        self.assertIn('pred inv1C1[x, y: Node] {\ninv1[x, y]\n}', dependent['environmentBefore'])
        self.assertNotIn('pred inv1C0', dependent['environmentBefore'])
        self.assertTrue(upload.validate_witness(witness, documents))
        # The live guard requires every blocked name to resolve. An unused C
        # variant is deliberately absent from the public generated environment.
        self.assertEqual(documents[0]['practicePlan']['blockedPredicates'], ['inv2'])

    def test_shadowed_predicate_name_does_not_create_a_dependency(self):
        source = 'sig Node {}\npred A {some Node}\npred B {some A: Node | some A}\n'
        documents, _, _ = project(source, {})
        self.assertEqual(documents[1]['practicePlan']['dependencies'], [])

    def test_cycles_facts_and_parameter_domains_fail_closed(self):
        for source, calls, extras in (
            ('sig Node {}\npred A {B[]}\npred B {A[]}\n', {'A': ['B'], 'B': ['A']}, {}),
            ('sig Node {}\npred A {some Node}\nfact {A[]}\n', {}, {'facts': ['A']}),
            ('sig Node {}\npred A {some Node}\nfun domain: set Node { {n: Node | A[]} }\n'
             'pred B[n: domain] {some n}\n', {'domain': ['A'], 'B': ['domain']},
             {'parameters': {'B': ['n']}, 'parameter_calls': {'B': ['domain']}})
        ):
            with self.subTest(source=source), self.assertRaises(upload.UploadError):
                project(source, calls, **extras)

    def test_helper_transitivity_and_unselected_source_preserved(self):
        source = 'sig Node {}\npred A {some Node}\npred helper {A[]}\npred B {helper[]}\n'
        documents, witness, _ = project(source, {'helper': ['A'], 'B': ['helper']}, selected=['A', 'B'])
        self.assertEqual(documents[1]['practicePlan']['direct'], ['test-A'])
        self.assertIn('pred helper {A[]}', documents[1]['environmentBefore'])
        self.assertEqual(witness['originalSource'], source)

    def test_graph_and_plan_changes_break_witness_and_certificate(self):
        source = 'sig Node {}\npred A {some Node}\npred B {A[]}\n'
        documents, witness, _ = project(source, {'B': ['A']})
        identity = {'engineSha256': '1' * 64, 'dependencySha256': {name: '2' * 64 for name in store.JAR_FILES}}
        result = dict(status='ok', scope=5, bitwidth=5, maxSequence=5, minTrace=1, maxTrace=10,
                      solver='SAT4J', oracleCount=1, correctCount=0, evaluatedCandidates=1,
                      moduleFacts=True, factsSatisfiable=True)
        certificate = store._validation_certificate(documents[1], result, identity)
        self.assertEqual(certificate['version'], 2)
        self.assertEqual(certificate['dependencyPlanSha256'], dependency_plan_sha(documents[1]))
        self.assertEqual(certificate['sourceInspectionSha256'], documents[1]['sourceInspectionSha256'])
        changed = copy.deepcopy(witness)
        changed['inspection']['declarations'][1]['calls'] = []
        with self.assertRaises(upload.UploadError):
            upload.validate_witness(changed, documents)
        changed = copy.deepcopy(documents)
        changed[1]['practicePlan']['validationEnvironmentBefore'] += '\n// altered'
        with self.assertRaises(upload.UploadError):
            upload.validate_witness(witness, changed)
        with self.assertRaises(store.StoreError):
            store._validate_certificate_dependency_context(changed[1], certificate)

    def test_public_slot_offsets_are_utf16_not_unicode_codepoints(self):
        source = 'sig Node {}\n// 🎯 unicode\npred A {some Node}\npred B {A[]}\n'
        documents, _, _ = project(source, {'B': ['A']})
        document = documents[1]
        slot = public_slots(document)[0]
        self.assertEqual(slot['part'], 'environmentBefore')
        utf16 = document['environmentBefore'].encode('utf-16-le')
        self.assertEqual(utf16[slot['start'] * 2:slot['end'] * 2].decode('utf-16-le'), upload.GENERATED_STARTER)

    def test_v2_projection_reconstruction_keeps_exact_old_shape(self):
        source = 'sig Node {}\npred A {some Node}\npred B {no Node}\n'
        documents, witness, _ = upload._plan(source, 'test.als', 'test', 5, projection_version=2)
        self.assertTrue(upload.validate_witness(witness, documents))
        self.assertNotIn('practicePlan', documents[0])
        self.assertNotIn('inspection', witness)

    def test_sqlite_commit_reload_private_approval_and_removal_guards(self):
        from tests.test_sqlite_store import fixture
        root = Path(__file__).resolve().parents[1]
        scratch = root / 'build/practice-dependency-tests'
        scratch.mkdir(parents=True, exist_ok=True)
        source = 'sig Node {}\npred A {some Node}\npred B {A[]}\n'
        documents, witness, _ = project(source, {'B': ['A']})
        identity = {'engineSha256': '1' * 64, 'dependencySha256': {name: '2' * 64 for name in store.JAR_FILES}}
        result = dict(status='ok', scope=5, bitwidth=5, maxSequence=5, minTrace=1, maxTrace=10,
                      solver='SAT4J', oracleCount=1, correctCount=0, evaluatedCandidates=1,
                      moduleFacts=True, factsSatisfiable=True)
        prepared = dict(documents=documents, witness=witness,
                        certificates=[store._validation_certificate(document, result, identity) for document in documents])
        metadata = [dict(predicate=document['predicate'], title=document['title'], question=document['description'])
                    for document in documents]
        with tempfile.TemporaryDirectory(prefix='sqlite-', dir=scratch) as folder:
            destination = Path(folder)
            fixture(destination)
            store.commit_upload(destination, prepared, metadata, guard=nullcontext)
            snapshot = store.load_store(destination)
            a, b = snapshot.exercises['test-A'], snapshot.exercises['test-B']
            self.assertEqual(snapshot.exercise_count, 3)
            self.assertNotIn('some Node', b['environmentBefore'])
            approval = store._approval_document(b, 'A[] and some Node', 5)
            payload = store._validation_payload(approval)
            self.assertIn('some Node', payload['sourcePrefix'])
            self.assertEqual(approval['sourceInspectionSha256'], b['sourceInspectionSha256'])
            with self.assertRaisesRegex(store.StoreError, 'dependent'):
                store.remove_question(destination, a['id'], store.exercise_version(a), guard=nullcontext)
            store.remove_question(destination, b['id'], store.exercise_version(b), guard=nullcontext)
            store.remove_question(destination, a['id'], store.exercise_version(a), guard=nullcontext)
            self.assertEqual(store.load_store(destination).exercise_count, 1)


if __name__ == '__main__':
    unittest.main()
