"""Corpus provenance and fail-closed lexical extraction tests.

These tests establish the finite preservation claim for the bundled catalogue;
they do not claim correctness of an arbitrary Alloy parser or oracle semantics.
"""

from copy import deepcopy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("portal_import_exercises", ROOT / "scripts" / "import_exercises.py")
IMPORTER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = IMPORTER
SPEC.loader.exec_module(IMPORTER)


FIXTURE = b'''// untouched { pred inv1c { THIS IS A COMMENT }
sig Node { adj: set Node }
/* braces } { and Unicode follow: \xc3\xa9 */
fact words { "brace } and escaped \\\" quote" = "brace } and escaped \\\" quote" }
pred helper { some { n: Node | no n.adj } }
pred inv1 /* header remains byte-exact */ {
  some Node // ignore }
}
pred inv1c { all n: Node | n not in n.adj }
check correct { inv1 <=> inv1c}
pred under { inv1 and !inv1c}
pred over { !inv1 and inv1c}
run over
run under
// trailing environment
pred retained { some Node }
'''


class ExtractionTests(unittest.TestCase):
    def test_comments_strings_nested_braces_and_utf8_are_preserved(self):
        result = IMPORTER.extract_model(FIXTURE, "inv1")
        self.assertIn('fact words { "brace }', result["environmentBefore"])
        self.assertIn("Unicode follow: é", result["environmentBefore"])
        self.assertIn("pred helper { some { n: Node | no n.adj } }", result["environmentBefore"])
        self.assertEqual(result["predicateHeader"], "pred inv1 /* header remains byte-exact */ ")
        self.assertEqual(result["starter"], "\n  some Node // ignore }\n")
        self.assertTrue(result["environmentAfter"].endswith("pred retained { some Node }\n"))
        self.assertEqual(result["oracleBody"], " all n: Node | n not in n.adj ")
        self.assertNotIn(result["oracleBody"], result["environmentBefore"] + result["environmentAfter"])
        for name in ("environmentBefore", "environmentAfter"):
            spans = result["preservation"][name + "Segments"]
            self.assertEqual(result[name].encode(), b"".join(FIXTURE[s["start"]:s["end"]] for s in spans))

    def test_same_split_with_crlf(self):
        source = FIXTURE.replace(b"\n", b"\r\n")
        result = IMPORTER.extract_model(source, "inv1")
        self.assertIn("\r\n", result["environmentBefore"])
        self.assertEqual(result["originalSource"].encode(), source)

    def test_oracle_and_harness_can_precede_student(self):
        source = b'''sig Node {}\npred inv1c { no Node }
check correct { inv1 <=> inv1c}
pred under { inv1 and !inv1c}
pred over { !inv1 and inv1c}
run over
run under
pred helper { some Node }
pred inv1 { some Node }
'''
        result = IMPORTER.extract_model(source, "inv1")
        self.assertIn("pred helper { some Node }", result["environmentBefore"])
        self.assertNotIn("inv1c", result["environmentBefore"])

    def test_rejects_ambiguous_or_malformed_sources(self):
        fixtures = {
            "duplicate oracle": FIXTURE + b"\npred inv1c { no Node }",
            "missing oracle": FIXTURE.replace(b"pred inv1c { all n: Node | n not in n.adj }", b""),
            "unterminated block comment": FIXTURE + b"\n/*",
            "unterminated string": FIXTURE + b'\nfact broken { "}',
            "nested comments": FIXTURE + b"\n/* /* */ */",
            "unmatched brace": FIXTURE + b"\n}",
            "changed harness": FIXTURE.replace(b"inv1 and !inv1c", b"inv1 or !inv1c"),
            "missing run": FIXTURE.replace(b"run over", b""),
            "duplicate run": FIXTURE + b"\nrun over",
            "unrecognized run suffix": FIXTURE.replace(b"run over", b"run over for 5"),
            "surviving oracle reference": FIXTURE + b"\npred leak { inv1c }",
            "argument predicate": FIXTURE.replace(b"pred inv1 /*", b"pred inv1[n: Node] /*"),
        }
        for case, source in fixtures.items():
            with self.subTest(case=case), self.assertRaises(IMPORTER.ExtractionError):
                IMPORTER.extract_model(source, "inv1")


class CatalogueTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.catalogue = json.loads((ROOT / "exercises" / "catalogue.json").read_text())

    def test_complete_group_coverage_and_private_fields(self):
        c = self.catalogue
        records = c["exercises"]
        self.assertEqual(c["schemaVersion"], 1)
        self.assertEqual(c["droppedGroups"], [])
        self.assertEqual(c["ignoredFiles"], [])
        self.assertEqual(c["provenance"]["discoveredGroups"], 181)
        self.assertEqual(c["provenance"]["importedGroups"], 181)
        self.assertEqual(len(records), 181)
        self.assertEqual(len({r["id"] for r in records}), 181)
        self.assertEqual(len({r["group"] for r in records}), 17)
        self.assertEqual(c["provenance"]["publicFields"], list(IMPORTER.PUBLIC_FIELDS))
        private = {"oracleBody", "originalSource", "preservation", "descriptionProvenance", "sourceClassification"}
        self.assertTrue(private.isdisjoint(IMPORTER.PUBLIC_FIELDS))
        for record in records:
            with self.subTest(exercise=record["id"]):
                self.assertEqual(set(record), set(IMPORTER.PUBLIC_FIELDS) | private)
                self.assertNotEqual(record["starter"].strip(), record["oracleBody"].strip())
                self.assertIn(record["sourceClassification"], ("under", "over", "both"))

    def test_every_environment_is_an_exact_partition_of_its_hashed_original(self):
        for record in self.catalogue["exercises"]:
            with self.subTest(exercise=record["id"]):
                IMPORTER.verify_record(record)
                original = record["originalSource"].encode()
                self.assertEqual(hashlib.sha256(original).hexdigest(), record["source"]["sha256"])
                preservation = record["preservation"]
                self.assertEqual(preservation["sourceBytes"], len(original))
                for field in ("environmentBefore", "environmentAfter"):
                    segments = preservation[field + "Segments"]
                    expected = b"".join(original[s["start"]:s["end"]] for s in segments)
                    self.assertEqual(record[field].encode(), expected)
                    self.assertEqual(hashlib.sha256(expected).hexdigest(), preservation[field + "Sha256"])
                removed = preservation["removedSpans"]
                self.assertEqual(len(removed), 6)
                self.assertEqual(len({s["reason"] for s in removed}), 6)
                self.assertNotIn(record["oracleBody"].strip(), record["environmentBefore"] + record["environmentAfter"])

    def test_ordering_and_temporal_environment_are_not_lost(self):
        records = {r["id"]: r for r in self.catalogue["exercises"]}
        temporal = records["trainStationOld-inv5"]
        self.assertIn("fact Layout {", temporal["environmentBefore"])
        self.assertIn("var sig Green in Signal {}", temporal["environmentBefore"])
        self.assertIn("var pos : lone Track", temporal["environmentBefore"])
        courses = records["coursesNew-inv1"]
        self.assertIn("open util/ordering[Grade]", courses["environmentBefore"])
        self.assertIn("grades : Person -> Grade", courses["environmentBefore"])

    def test_preservation_verifier_detects_mutation(self):
        original = self.catalogue["exercises"][0]
        for field in ("environmentBefore", "environmentAfter", "starter", "oracleBody", "predicateHeader", "originalSource"):
            with self.subTest(field=field):
                record = deepcopy(original)
                record[field] += " "
                with self.assertRaises(IMPORTER.ExtractionError):
                    IMPORTER.verify_record(record)
        record = deepcopy(original)
        record["preservation"]["environmentBeforeSegments"][0]["end"] -= 1
        with self.assertRaises(IMPORTER.ExtractionError):
            IMPORTER.verify_record(record)

    def test_all_exercises_have_source_bound_prose_requirements(self):
        descriptions = IMPORTER.load_descriptions()
        self.assertEqual(set(descriptions), {r["id"] for r in self.catalogue["exercises"]})
        self.assertEqual(len(descriptions), 181)
        for record in self.catalogue["exercises"]:
            with self.subTest(exercise=record["id"]):
                expected = descriptions[record["id"]]
                self.assertEqual(expected["sourceSha256"], record["source"]["sha256"])
                self.assertEqual(record["description"], expected["description"])
                self.assertEqual(record["descriptionProvenance"], IMPORTER.DESCRIPTION_PROVENANCE)
                self.assertTrue(record["description"].strip())
                self.assertNotRegex(record["description"], r"[{}|`]|<=>|=>|->|\binv\d+c\b|\bpred\s")
                self.assertNotIn("no natural-language requirement", record["description"])
                self.assertNotIn("not been reviewed", record["description"])

    def test_description_refresh_preserves_every_non_description_field(self):
        catalogue = deepcopy(self.catalogue)
        for record in catalogue["exercises"]:
            record["description"] = "Old placeholder"
            record["descriptionProvenance"] = "Old provenance"
        IMPORTER.refresh_descriptions(catalogue, IMPORTER.load_descriptions())
        self.assertEqual(catalogue, self.catalogue)
        IMPORTER.refresh_descriptions(catalogue, IMPORTER.load_descriptions())
        self.assertEqual(catalogue, self.catalogue, "Refreshing must be idempotent")

    def test_changed_source_cannot_reuse_requirement_for_same_inv_number(self):
        descriptions = IMPORTER.load_descriptions()
        record = self.catalogue["exercises"][0]
        self.assertIsNone(IMPORTER.description_for(record["id"], "0" * 64, descriptions))
        self.assertIsNone(IMPORTER.description_for("newModel-inv1", record["source"]["sha256"], descriptions))

    def test_invalid_late_binding_does_not_partially_refresh_catalogue(self):
        catalogue = deepcopy(self.catalogue)
        for record in catalogue["exercises"]:
            record["description"] = "Old placeholder"
        before = deepcopy(catalogue)
        descriptions = IMPORTER.load_descriptions()
        descriptions[catalogue["exercises"][-1]["id"]]["sourceSha256"] = "0" * 64
        with self.assertRaisesRegex(IMPORTER.ExtractionError, "description binding"):
            IMPORTER.refresh_descriptions(catalogue, descriptions)
        self.assertEqual(catalogue, before)

    def test_guide_matches_public_descriptions_without_private_record_fields(self):
        guide = (ROOT / "docs/exercise-descriptions.md").read_text(encoding="utf-8")
        self.assertEqual(guide, IMPORTER.render_description_guide(self.catalogue))
        self.assertEqual(guide.count("### "), 181)
        public_only = {"exercises": [{key: record[key] for key in ("id", "group", "description")}
                                     for record in self.catalogue["exercises"]]}
        self.assertEqual(guide, IMPORTER.render_description_guide(public_only))
        self.assertNotRegex(guide, r"oracleBody|originalSource|\bpred\s+inv|[{}]")

    def test_description_refresh_cli_needs_no_original_corpus_and_writes_private_catalogue(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            catalogue_path = root / "catalogue.json"
            guide_path = root / "guide.md"
            catalogue = deepcopy(self.catalogue)
            for record in catalogue["exercises"]:
                record["description"] = "Old placeholder"
                record["descriptionProvenance"] = "Old provenance"
            catalogue_path.write_text(json.dumps(catalogue), encoding="utf-8")
            command = [sys.executable, str(ROOT / "scripts/import_exercises.py"),
                       "--refresh-descriptions", "--output", str(catalogue_path),
                       "--source-root", str(root / "no-original-corpus"), "--guide", str(guide_path)]
            completed = subprocess.run(command, cwd=temporary, capture_output=True,
                                       text=True, timeout=20, check=False)
            self.assertEqual(completed.returncode, 0, completed.stderr)
            self.assertEqual(json.loads(catalogue_path.read_text()), self.catalogue)
            self.assertEqual(guide_path.read_text(), IMPORTER.render_description_guide(self.catalogue))
            if os.name != "nt":
                self.assertEqual(catalogue_path.stat().st_mode & 0o777, 0o600)
            checked = subprocess.run(command + ["--check"], cwd=temporary,
                                     capture_output=True, text=True, timeout=20, check=False)
            self.assertEqual(checked.returncode, 0, checked.stderr)
            self.assertFalse(list(root.glob(".catalogue-*")))


if __name__ == "__main__":
    unittest.main()
