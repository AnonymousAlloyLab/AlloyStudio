"""Real parser/JVM witnesses for structural learner source correspondence.

These tests check original-source positions and explicit uncertainty. They do not
claim that canonical edits identify unique defects or executable source patches.
"""

import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from test_engine_corpus import ROOT, invoke


ENVIRONMENT = "module source_fixture\nsig A { r: set A }\n"


def utf16_length(value):
    return len(value.encode("utf-16-le")) // 2


def utf16_slice(value, start, end):
    return value.encode("utf-16-le")[start * 2:end * 2].decode("utf-16-le")


class SourceLocationTests(unittest.TestCase):
    def compare(self, learner, reference, environment=ENVIRONMENT, reference_environment=None):
        prefix = environment + "pred target { "
        source = prefix + learner + " }\n"
        target = (reference_environment or environment) + "pred target { " + reference + " }\n"
        result = invoke(source, target)
        self.assertEqual(result.get("status"), "ok", "Source-location fixture did not complete")
        self.assertTrue(result["trace"]["matrixReplayVerified"])
        for operation in result["operations"]:
            location = operation["sourceLocation"]
            self.assertEqual(set(location), {"status", "precision", "coordinateSystem", "offsetEncoding", "reason", "ranges"})
            self.assertEqual(location["coordinateSystem"], "module")
            self.assertEqual(location["offsetEncoding"], "utf-16")
            self.assertIn(location["precision"], ("node", "related"))
            if location["precision"] == "node":
                self.assertEqual(location["status"], "located")
            self.assertIn(location["status"], ("located", "ambiguous", "unavailable"))
            self.assertTrue(location["reason"])
            ranges = location["ranges"]
            if location["status"] == "unavailable":
                self.assertEqual(ranges, [])
            elif location["status"] == "located":
                self.assertEqual(len(ranges), 1)
            else:
                self.assertGreater(len(ranges), 1)
            self.assertLessEqual(len(ranges), 16)
            for span in ranges:
                self.assertEqual(set(span), {"start", "end"})
                self.assertIs(type(span["start"]), int)
                self.assertIs(type(span["end"]), int)
                self.assertGreaterEqual(span["start"], utf16_length(prefix))
                self.assertLessEqual(span["end"], utf16_length(prefix + learner))
                self.assertGreater(span["end"], span["start"])
                self.assertTrue(utf16_slice(source, span["start"], span["end"]))
            canonical = operation["canonicalLocation"]
            self.assertEqual(set(canonical), {"status", "precision", "coordinateSystem", "offsetEncoding", "reason", "ranges"})
            self.assertEqual(canonical["coordinateSystem"], "canonical")
            self.assertEqual(canonical["offsetEncoding"], "utf-16")
            self.assertIn(canonical["precision"], ("node", "related", "form"))
            if canonical["precision"] == "node":
                self.assertEqual(canonical["status"], "located")
            self.assertIn(canonical["status"], ("located", "ambiguous", "unavailable"))
            if canonical["status"] == "unavailable":
                self.assertEqual(canonical["ranges"], [])
            elif canonical["status"] == "located":
                self.assertEqual(len(canonical["ranges"]), 1)
            else:
                self.assertGreater(len(canonical["ranges"]), 1)
            for span in canonical["ranges"]:
                self.assertEqual(set(span), {"formIndex", "start", "end"})
                self.assertGreaterEqual(span["formIndex"], 0)
                self.assertLess(span["formIndex"], len(result["canonicalForm"]))
                form = result["canonicalForm"][span["formIndex"]]
                self.assertGreaterEqual(span["start"], 0)
                self.assertLessEqual(span["end"], utf16_length(form))
                self.assertGreater(span["end"], span["start"])
                self.assertTrue(utf16_slice(form, span["start"], span["end"]))
        return result, source

    def snippets(self, operation, source):
        return [utf16_slice(source, span["start"], span["end"])
                for span in operation["sourceLocation"]["ranges"]]

    def only_operation(self, learner, reference, **kwargs):
        result, source = self.compare(learner, reference, **kwargs)
        self.assertEqual(result["distance"], 1)
        self.assertEqual(len(result["operations"]), 1)
        return result["operations"][0], source

    def test_simple_unary_expression_uses_recorded_original_source(self):
        operation, source = self.only_operation("no A", "some A")
        self.assertEqual(operation["sourceLocation"]["status"], "located")
        self.assertEqual(self.snippets(operation, source), ["no A"])

    def test_comments_never_create_operator_occurrences(self):
        learner = "/* no A */\n// no A\n-- no A\nno /* repeated no A */ A"
        operation, source = self.only_operation(learner, "some A")
        self.assertEqual(operation["sourceLocation"]["status"], "located")
        self.assertEqual(self.snippets(operation, source), ["no /* repeated no A */ A"])

    def test_tabs_crlf_and_supplementary_unicode_use_utf16(self):
        learner = "// 😀 no A\r\n\t/* 😀 */\tno A.r"
        operation, source = self.only_operation(learner, "some A.r", environment=ENVIRONMENT.replace("\n", "\r\n"))
        self.assertEqual(self.snippets(operation, source), ["no A.r"])
        span = operation["sourceLocation"]["ranges"][0]
        self.assertEqual(span["start"], utf16_length(source[:source.rindex("no A.r")]))

    def test_repeated_expression_selects_normalizations_retained_occurrence(self):
        operation, source = self.only_operation("some A and some A", "no A")
        location = operation["sourceLocation"]
        self.assertEqual(location["precision"], "node")
        self.assertEqual(location["status"], "located")
        self.assertEqual(self.snippets(operation, source), ["some A"])
        self.assertEqual(location["ranges"][0]["start"], utf16_length(source[:source.index("some A")]))

    def test_same_operator_different_operands_selects_expression(self):
        prefix = "some A and no A.r and one A.r.r and "
        operation, source = self.only_operation(prefix + "lone A.r.r.r", prefix + "some A.r.r.r")
        self.assertEqual(self.snippets(operation, source), ["lone A.r.r.r"])

    def test_repeated_leaves_follow_selected_branch_after_canonical_reordering(self):
        for learner, reference, selected in (
                ("some A.r or lone A.r", "some A.r or lone r.A", "lone"),
                ("lone A.r or some A.r", "lone r.A or some A.r", "lone"),
                ("some A.r or lone A.r", "some r.A or lone A.r", "some")):
            with self.subTest(learner=learner, selected=selected):
                result, source = self.compare(learner, reference)
                self.assertEqual(result["distance"], 2)
                for operation in result["operations"]:
                    term = operation["sourceTerm"]
                    self.assertIn(term, ("A", "r"))
                    raw = operation["sourceLocation"]
                    canonical = operation["canonicalLocation"]
                    self.assertEqual(raw["precision"], "node")
                    self.assertEqual(canonical["precision"], "node")
                    self.assertEqual(self.snippets(operation, source), [term])
                    expected = source.index(selected + " A.r") + len(selected) + (1 if term == "A" else 3)
                    self.assertEqual(raw["ranges"], [{"start": expected, "end": expected + 1}])
                    span = canonical["ranges"][0]
                    form = result["canonicalForm"][span["formIndex"]]
                    expected = form.index("(" + selected.upper() + " (A . r))") + len(selected) + (3 if term == "A" else 7)
                    self.assertEqual(span["start"], expected)
                    self.assertEqual(utf16_slice(form, span["start"], span["end"]), term)

    def test_repeated_call_arguments_select_second_ordered_operand(self):
        result, source = self.compare("p[A,A]", "p[A,none]",
                environment=ENVIRONMENT + "pred p[a,b: set A] { a in b }\n")
        self.assertEqual(result["distance"], 1)
        operation = result["operations"][0]
        self.assertTrue(operation["path"].endswith(".child[1]"))
        self.assertEqual(operation["sourceLocation"]["precision"], "node")
        expected = source.index("p[A,A]") + 4
        self.assertEqual(operation["sourceLocation"]["ranges"], [{"start": expected, "end": expected + 1}])
        span = operation["canonicalLocation"]["ranges"][0]
        form = result["canonicalForm"][span["formIndex"]]
        self.assertEqual(span["start"], form.index("p(A, A)") + 5)

    def test_identical_shadowed_binding_headers_select_inner_quantifier(self):
        operation, source = self.only_operation(
                "all x: A | some x.r and (all x: A | lone x.r)",
                "all x: A | some x.r and (some x: A | lone x.r)")
        self.assertEqual(operation["component"], "quantifier")
        self.assertEqual(operation["sourceLocation"]["precision"], "node")
        expected = source.rindex("all x: A")
        self.assertEqual(operation["sourceLocation"]["ranges"], [{"start": expected, "end": expected + 8}])
        self.assertEqual(operation["canonicalLocation"]["ranges"][0]["start"], 34)

    def test_deleted_repeated_leaves_keep_their_original_occurrence(self):
        result, source = self.compare("some A.r or lone A.r", "lone A.r")
        leaves = [operation for operation in result["operations"]
                  if operation["kind"] == "delete" and operation.get("sourceTerm") == "A"]
        self.assertEqual(len(leaves), 2)
        self.assertTrue(all(operation["sourceLocation"]["precision"] == "node" for operation in leaves))
        self.assertEqual({operation["sourceLocation"]["ranges"][0]["start"] for operation in leaves},
                         {source.index("some A.r") + 5, source.index("lone A.r") + 5})
        self.assertEqual(len({operation["canonicalLocation"]["ranges"][0]["start"] for operation in leaves}), 2)

    def test_nested_grouping_does_not_invent_ambiguity(self):
        operation, source = self.only_operation("(((no A)))", "some A")
        self.assertEqual(operation["sourceLocation"]["status"], "located")
        self.assertEqual(self.snippets(operation, source), ["no A"])

    def test_binding_header_is_located_without_body_or_declaration(self):
        operation, source = self.only_operation("all x: A | some x.r", "some x: A | some x.r")
        self.assertEqual(operation["component"], "quantifier")
        self.assertEqual(self.snippets(operation, source), ["all x: A"])

    def test_relational_comparison_uses_original_expression(self):
        result, source = self.compare("A.r in A", "A.r = A")
        related = [operation for operation in result["operations"] if operation.get("sourceOperator") == "in"]
        self.assertTrue(related)
        for operation in related:
            self.assertEqual(self.snippets(operation, source), ["A.r in A"])

    def test_called_helper_body_is_not_a_candidate(self):
        environment = ENVIRONMENT + "pred helper { no A }\npred other { some A }\n"
        operation, source = self.only_operation("helper", "other", environment=environment)
        self.assertEqual(self.snippets(operation, source), ["helper"])

    def test_let_expansion_uses_origin_even_when_canonical_text_differs(self):
        operation, source = self.only_operation("let z=A | no z", "let z=A | some z")
        self.assertEqual(operation["sourceLocation"]["precision"], "node")
        self.assertEqual(self.snippets(operation, source), ["no z"])

    def test_normalization_to_constant_has_no_guessed_source_range(self):
        result, _ = self.compare("no (A - A)", "some (A - A)")
        self.assertTrue(result["operations"])
        for operation in result["operations"]:
            self.assertEqual(operation["sourceLocation"]["status"], "unavailable")

    def test_insertions_use_related_learner_context(self):
        result, source = self.compare("no A", "no A and some A.r")
        insertions = [operation for operation in result["operations"] if operation["kind"] == "insert"]
        self.assertTrue(insertions)
        for operation in insertions:
            self.assertEqual(operation["sourceRole"], "insertion-anchor")
            self.assertIn(operation["sourceLocation"]["status"], ("located", "ambiguous", "unavailable"))
            for snippet in self.snippets(operation, source):
                self.assertIn(snippet, ("A", "no A"))
            if operation["sourceLocation"]["status"] == "located":
                self.assertIn("does not pinpoint where to add it", operation["sourceLocation"]["reason"])

    def test_repeated_occurrences_use_retained_origin_without_candidate_limit(self):
        operation, source = self.only_operation(" and ".join(["some A"] * 17), "no A")
        self.assertEqual(operation["sourceLocation"]["precision"], "node")
        self.assertEqual(self.snippets(operation, source), ["some A"])
        self.assertEqual(operation["sourceLocation"]["ranges"][0]["start"],
                         utf16_length(source[:source.index("some A")]))

    def test_reference_name_changes_do_not_change_learner_locations(self):
        locations = []
        for target in ("PRIVATE_TARGET_FIRST", "PRIVATE_TARGET_SECOND"):
            operation, _ = self.only_operation("some LEARNER_ATOM", "some " + target,
                    environment=ENVIRONMENT + "sig LEARNER_ATOM {}\n",
                    reference_environment=ENVIRONMENT + "sig " + target + " {}\n")
            locations.append(operation["sourceLocation"])
            self.assertNotIn(target, json.dumps(operation))
        self.assertEqual(locations[0], locations[1])

    def test_canonical_operator_fragment_matches_printed_form(self):
        result, _ = self.compare("no A.r", "some A.r")
        operation = result["operations"][0]
        location = operation["canonicalLocation"]
        self.assertEqual(location["precision"], "node")
        self.assertEqual(location["status"], "located")
        span = location["ranges"][0]
        self.assertEqual(utf16_slice(result["canonicalForm"][span["formIndex"]], span["start"], span["end"]), "(NO (A . r))")

    def test_canonical_binding_fragment_matches_printed_form(self):
        result, _ = self.compare("all x:A | some x.r", "some x:A | some x.r")
        location = result["operations"][0]["canonicalLocation"]
        self.assertEqual(location["precision"], "node")
        span = location["ranges"][0]
        self.assertEqual(utf16_slice(result["canonicalForm"][span["formIndex"]], span["start"], span["end"]), "ALL x : one A")

    def test_canonical_temporal_context_is_explicit_without_a_term(self):
        result, _ = self.compare("always no A", "eventually some A")
        temporal = [operation for operation in result["operations"] if operation["component"] == "temporal"]
        self.assertTrue(temporal)
        for operation in temporal:
            self.assertEqual(operation["canonicalLocation"]["precision"], "form")
        matrix = [operation for operation in result["operations"] if operation["component"] == "matrix"]
        self.assertTrue(matrix)
        for operation in matrix:
            self.assertTrue(all(span["formIndex"] == 1 for span in operation["canonicalLocation"]["ranges"]))

    def test_uppercase_learner_identifier_is_not_folded_as_operator(self):
        result, _ = self.compare("some NO", "some OTHER", environment=ENVIRONMENT + "sig NO, OTHER {}\n")
        operation = result["operations"][0]
        location = operation["canonicalLocation"]
        self.assertEqual(location["status"], "located")
        self.assertEqual(location["precision"], "node")
        span = location["ranges"][0]
        self.assertEqual(utf16_slice(result["canonicalForm"][span["formIndex"]], span["start"], span["end"]), "NO")

    def test_canonical_literal_tokens_preserve_whitespace_escapes_and_parentheses(self):
        # String constants are currently unsupported by the metric importer.
        # Exercise the presentation locator directly so that its syntax handling
        # does not inherit that unrelated limitation or a mocked parser result.
        harness = """package live;
import org.json.JSONArray;
import org.json.JSONObject;
public final class CanonicalLocatorHarness {
  public static void main(String[] args) throws Exception {
    JSONObject request = new JSONObject(new String(System.in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
    JSONArray operations = request.getJSONArray("operations");
    CanonicalLocator.attach(request.getJSONArray("forms"), operations);
    System.out.println(operations);
  }
}
"""
        literal = '"(NO   A) \\" )"'
        forms = ['root normal form := target((SOME ' + literal + '))',
                 'root normal form := target((SOME "NO A"))']
        operations = [
            {"path": "normalForm[0].matrix", "sourceTerm": "(some " + literal + ")"},
            {"path": "normalForm[0].matrix", "sourceTerm": literal},
            {"path": "normalForm[1].matrix", "sourceTerm": '"NO   A"'},
        ]
        with tempfile.TemporaryDirectory(prefix="canonical-locator-test-") as directory:
            source = Path(directory) / "CanonicalLocatorHarness.java"
            source.write_text(harness, encoding="utf-8")
            classpath = str(ROOT / "build/engine/classes") + os.pathsep + str(ROOT / "vendor/acgn/lib/*")
            compiled = subprocess.run(["javac", "-encoding", "UTF-8", "-cp", classpath,
                                       "-d", directory, str(source)], capture_output=True, timeout=15)
            self.assertEqual(compiled.returncode, 0, "Canonical locator harness did not compile")
            run = subprocess.run(["java", "-cp", directory + os.pathsep + classpath, "live.CanonicalLocatorHarness"],
                                 input=json.dumps({"forms": forms, "operations": operations}),
                                 text=True, capture_output=True, timeout=15)
        self.assertEqual(run.returncode, 0, "Canonical locator harness did not run")
        projected = json.loads(run.stdout)
        for operation, expected in zip(projected[:2], ("(SOME " + literal + ")", literal)):
            location = operation["canonicalLocation"]
            self.assertEqual(location["precision"], "related")
            self.assertEqual(location["status"], "located")
            span = location["ranges"][0]
            self.assertEqual(utf16_slice(forms[span["formIndex"]], span["start"], span["end"]), expected)
        self.assertEqual(projected[2]["canonicalLocation"]["precision"], "form",
                         "Whitespace inside literals must not be normalized into a match")


if __name__ == "__main__":
    unittest.main(verbosity=2)
