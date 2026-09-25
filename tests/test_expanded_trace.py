"""Finite witnesses for concrete matrix operations and learner-only context.

The private Java replay check validates the reconstructed internal plan before its
redacted public projection is emitted. These tests assert that flag and inspect
actual operations; they do not promote replay to semantic equivalence or a proof.
"""

import json
import re
import unittest

from test_engine_corpus import invoke


ENVIRONMENT = "module trace_fixture\nsig A { r: set A }\n"


def source(body, environment=ENVIRONMENT):
    return environment + "pred target { " + body + " }\n"


class ExpandedTraceTests(unittest.TestCase):
    def compare(self, learner, reference, learner_env=ENVIRONMENT, reference_env=None):
        answer = invoke(source(learner, learner_env), source(reference, reference_env or learner_env))
        self.assertEqual(answer.get("status"), "ok", "Expanded trace fixture did not complete")
        self.assertTrue(answer["trace"]["matrixReplayVerified"], "Private matrix reconstruction failed replay")
        operations = answer["operations"]
        self.assertEqual(sum(op["cost"] for op in operations), answer["distance"])
        matrix = [op for op in operations if op["component"] == "matrix"]
        self.assertEqual(sum(op["cost"] for op in matrix), answer["breakdown"]["matrix"])
        for operation in matrix:
            self.assertFalse(operation["aggregate"])
            self.assertEqual(operation["cost"], 1)
            self.assertIn(operation["kind"], ("insert", "delete", "replace"))
            self.assertIn(operation["sourceRole"], ("affected", "insertion-anchor"))
            for field in ("sourceTerm", "sourceOperator", "sourceNodeKind", "action", "reason", "nextStep"):
                self.assertIsInstance(operation[field], str)
                self.assertTrue(operation[field], "Concrete learner context is empty")
            forbidden = {"target", "targetTerm", "targetLabel", "targetExpression", "oracleBody", "oracleSource", "after"}
            self.assertTrue(forbidden.isdisjoint(operation), "Unregistered target-bearing field escaped")
        return answer, matrix

    def test_unary_replacement_identifies_learner_operator(self):
        answer, operations = self.compare("no A", "some A")
        self.assertEqual(answer["distance"], 1)
        self.assertEqual(len(operations), 1)
        operation = operations[0]
        self.assertEqual(operation["kind"], "replace")
        self.assertEqual(operation["sourceOperator"], "no")
        self.assertEqual(operation["replacementOperator"], "some")
        self.assertIn("no", operation["sourceTerm"])
        self.assertIn("A", operation["sourceTerm"])

    def test_unordered_assignment_exposes_one_real_edit_instead_of_sixteen(self):
        prefix = "some A and no A.r and one A.r.r and "
        answer, operations = self.compare(prefix + "lone A.r.r.r", prefix + "some A.r.r.r")
        # Independently-sorted upstream Canonical.edits returned 16 operations
        # for this one-edit assignment witness in the previous adapter.
        self.assertEqual(answer["distance"], 1)
        self.assertEqual(len(operations), 1)
        self.assertEqual(operations[0]["sourceOperator"], "lone")
        self.assertEqual(operations[0]["replacementOperator"], "some")
        self.assertIn("lone", operations[0]["sourceTerm"])
        self.assertEqual(operations[0]["sourceRole"], "affected")

    def test_call_identity_edit_has_learner_context_but_no_target_identity(self):
        learner_env = ENVIRONMENT + "pred learnerCall { some A }\n"
        reference_env = ENVIRONMENT + "pred PRIVATE_CALL_SENTINEL { no A }\n"
        answer, operations = self.compare("learnerCall", "PRIVATE_CALL_SENTINEL", learner_env, reference_env)
        self.assertEqual(answer["distance"], 1)
        self.assertEqual(len(operations), 1)
        self.assertEqual(operations[0]["kind"], "replace")
        self.assertIn("learnerCall", operations[0]["sourceTerm"])
        self.assertNotIn("replacementOperator", operations[0])
        self.assertNotIn("PRIVATE_CALL_SENTINEL", json.dumps(answer))

    def test_target_only_binding_is_hidden_and_source_term_is_learner_derived(self):
        learner_env = ENVIRONMENT + "sig LEARNER_ATOM_SENTINEL {}\n"
        reference_env = ENVIRONMENT + "sig PRIVATE_ATOM_SENTINEL {}\n"
        answer, operations = self.compare("some LEARNER_ATOM_SENTINEL", "some PRIVATE_ATOM_SENTINEL", learner_env, reference_env)
        self.assertEqual(answer["distance"], 1)
        self.assertEqual(len(operations), 1)
        self.assertIn("LEARNER_ATOM_SENTINEL", operations[0]["sourceTerm"])
        self.assertNotIn("replacementOperator", operations[0])
        self.assertNotIn("PRIVATE_ATOM_SENTINEL", json.dumps(answer))

    def test_target_numeric_constant_is_not_exposed(self):
        answer, operations = self.compare("#A = 2", "#A = 173")
        self.assertGreater(answer["distance"], 0)
        self.assertTrue(operations)
        self.assertIsNone(re.search(r"(?<!\d)173(?!\d)", json.dumps(answer)))
        for operation in operations:
            self.assertNotIn("173", operation["sourceTerm"])
            if operation["kind"] == "replace" and operation["sourceNodeKind"].lower() == "constant":
                self.assertNotIn("replacementOperator", operation)

    def test_insertions_use_learner_anchor_without_target_symbols(self):
        reference_env = ENVIRONMENT + "sig PRIVATE_INSERT_SENTINEL {}\n"
        answer, operations = self.compare("some A", "some A and some PRIVATE_INSERT_SENTINEL", ENVIRONMENT, reference_env)
        insertions = [op for op in operations if op["kind"] == "insert"]
        self.assertTrue(insertions)
        for operation in insertions:
            self.assertEqual(operation["sourceRole"], "insertion-anchor")
            self.assertNotIn("replacementOperator", operation)
        self.assertNotIn("PRIVATE_INSERT_SENTINEL", json.dumps(answer))

    def test_renaming_hidden_target_does_not_change_learner_context(self):
        learner_env = ENVIRONMENT + "sig LEARNER_FIXED {}\n"
        contexts = []
        for hidden in ("PRIVATE_TARGET_ONE", "PRIVATE_TARGET_TWO"):
            answer, operations = self.compare("some LEARNER_FIXED", "some " + hidden,
                learner_env, ENVIRONMENT + "sig " + hidden + " {}\n")
            self.assertNotIn(hidden, json.dumps(answer))
            contexts.append([(op["sourceTerm"], op["sourceOperator"], op["sourceNodeKind"], op["sourceRole"])
                             for op in operations])
        self.assertEqual(contexts[0], contexts[1])

    def test_cross_phase_alpha_alignment_replays_without_variable_repairs(self):
        answer, operations = self.compare(
            "all x:A | (always some x.r and eventually no x.r)",
            "all y:A | (always some y.r and eventually some y.r)")
        self.assertEqual(answer["breakdown"]["matrix"], 1)
        self.assertEqual(len(operations), 1)
        self.assertEqual(operations[0]["sourceOperator"], "no")
        self.assertEqual(operations[0]["replacementOperator"], "some")


if __name__ == "__main__":
    unittest.main(verbosity=2)
