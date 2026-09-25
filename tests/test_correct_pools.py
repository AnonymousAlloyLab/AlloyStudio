"""Finite offline provenance and oracle-inclusion checks for private pools."""
from copy import deepcopy
import json
from pathlib import Path
import unittest

from scripts.import_correct_pools import (PoolError, body_token_sha256, candidate,
    environment_sha256, sha, verify_document, verify_pool)


ROOT = Path(__file__).resolve().parents[1]


class CorrectPoolTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.catalogue = json.loads((ROOT / "exercises/catalogue.json").read_text())
        cls.document = json.loads((ROOT / "exercises/correct-pools.json").read_text())
        cls.records = {r["id"]: r for r in cls.catalogue["exercises"]}
        cls.pools = {p["exerciseId"]: p for p in cls.document["pools"]}

    def fixture(self):
        return self.records["graphs-inv1"], deepcopy(self.pools["graphs-inv1"])

    def test_all_181_pools_have_valid_offline_source_and_context_witnesses(self):
        verify_document(self.catalogue, self.document)
        self.assertEqual(len(self.pools), 181)
        p = self.document["provenance"]
        self.assertEqual(p["sourceFiles"], 23694)
        self.assertEqual(p["eligibleSourceFiles"], 23602)
        self.assertEqual(p["excludedSources"], 92)
        self.assertEqual(p["candidateCount"], 7731)
        self.assertEqual(p["correctStudentCandidates"], 7550)

    def test_explicit_oracle_is_included_in_every_pool_and_never_deduplicated_away(self):
        for identifier, pool in self.pools.items():
            with self.subTest(exercise=identifier):
                candidates = pool["candidates"]
                self.assertEqual(sum(item["kind"] == "oracle" for item in candidates), 1)
                self.assertEqual(candidates[-1]["kind"], "oracle")
                self.assertEqual(candidates[-1]["body"], self.records[identifier]["oracleBody"])
                self.assertEqual(len({item["tokenSha256"] for item in candidates}), len(candidates))
        oracle_only = {identifier for identifier, pool in self.pools.items() if len(pool["candidates"]) == 1}
        self.assertEqual(oracle_only, {"coursesNew-inv15", "trainStationOld-inv5", "trainStationOld-inv9",
                                     "trainStationOld-inv14", "trainStationOld-inv17"})

    def test_removing_or_relabelling_oracle_fails_closed(self):
        record, pool = self.fixture()
        pool["candidates"].pop()
        with self.assertRaises(PoolError): verify_pool(record, pool)
        record, pool = self.fixture()
        pool["candidates"][-1]["kind"] = "correct-student"
        with self.assertRaises(PoolError): verify_pool(record, pool)
        record, pool = self.fixture()
        pool["candidates"].append(deepcopy(pool["candidates"][-1]))
        with self.assertRaises(PoolError): verify_pool(record, pool)

    def test_candidate_body_must_be_the_witnessed_correct_submission(self):
        record, pool = self.fixture()
        original = pool["candidates"][0]
        changed = candidate("correct-student", "some Node // unwitnessed body", pool["environmentSha256"],
                            original["source"], original["originalSource"])
        pool["candidates"][0] = changed
        with self.assertRaises(PoolError): verify_pool(record, pool)

    def test_rehashed_context_mutation_cannot_be_transplanted(self):
        record, pool = self.fixture()
        item = pool["candidates"][0]
        item["originalSource"] = "fact ADDED_RESTRICTION { no Node }\n" + item["originalSource"]
        item["source"]["sha256"] = sha(item["originalSource"].encode())
        with self.assertRaises(PoolError): verify_pool(record, pool)
        record, pool = self.fixture()
        altered_record = dict(record, environmentBefore=record["environmentBefore"] + "\n")
        self.assertNotEqual(environment_sha256(record), environment_sha256(altered_record))
        with self.assertRaises(PoolError): verify_pool(altered_record, pool)

    def test_correct_status_group_and_source_hash_are_required(self):
        for mutation in ("status", "group", "hash"):
            record, pool = self.fixture()
            item = pool["candidates"][0]
            if mutation == "status": item["source"]["status"] = "under"
            elif mutation == "group": item["source"]["path"] = item["source"]["path"].replace("/graphs/", "/trash_fol/")
            else: item["source"]["sha256"] = "0" * 64
            with self.subTest(mutation=mutation), self.assertRaises(PoolError): verify_pool(record, pool)

    def test_lexical_dedup_ignores_comment_text_but_preserves_compound_operator_boundaries(self):
        self.assertEqual(body_token_sha256("some Node"), body_token_sha256("some  Node // ignored"))
        self.assertEqual(body_token_sha256("some /* { harmless } */ Node"), body_token_sha256("some Node"))
        self.assertNotEqual(body_token_sha256("Node->Node"), body_token_sha256("Node - > Node"))
        self.assertNotEqual(body_token_sha256('"some Node"'), body_token_sha256('"no Node"'))

    def test_all_context_exclusions_have_hashed_original_witnesses(self):
        self.assertEqual(len(self.document["excludedSources"]), 92)
        for item in self.document["excludedSources"]:
            self.assertEqual(item["reason"], "support-context-mismatch")
            self.assertEqual(item["source"]["sha256"], sha(item["originalSource"].encode()))
            self.assertEqual(item["source"]["status"], "correct")

    def test_missing_fields_and_false_counts_are_rejected(self):
        record, pool = self.fixture()
        del pool["candidates"][0]["body"]
        with self.assertRaises(PoolError): verify_pool(record, pool)
        document = dict(self.document, provenance=dict(self.document["provenance"], candidateCount=1))
        with self.assertRaises(PoolError): verify_document(self.catalogue, document)


if __name__ == "__main__":
    unittest.main(verbosity=2)
