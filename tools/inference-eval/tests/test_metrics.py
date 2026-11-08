import copy
import json
from pathlib import Path
import sys
import unittest

TOOL = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOL))
from metrics import distribution, observed_deltas, summarize


def fixture():
    return json.loads((TOOL / "fixtures/baseline.json").read_text())


def metadata():
    return json.loads((TOOL / "fixtures/baseline.metadata.json").read_text())


class MetricTests(unittest.TestCase):
    def test_two_attempt_groups_and_logical_requests_are_separate(self):
        report = summarize(fixture())
        self.assertEqual(report["attempts"]["attempt_count"], 3)
        self.assertEqual(report["logical_requests"]["count"], 2)
        self.assertEqual(report["logical_requests"]["observed_attempts_per_request"], {"1": 1, "2": 1})
        chat = [group for group in report["groups"] if group["task"] == "CHAT"]
        self.assertEqual(len(chat), 2)
        self.assertEqual({g["backend"] for g in chat}, {"MNN", "CLOUD"})
        self.assertEqual({g["trigger"] for g in chat}, {"INITIAL", "CONTRACT_FALLBACK"})
        self.assertTrue(all(g["statistics"]["logical_requests_touched"] == 1 for g in chat))
        self.assertEqual(report["scope"]["record_indices"]["end_exclusive"], 3)

    def test_execution_contract_rates_and_semantic_quality_are_independent(self):
        report = summarize(fixture())
        stats = report["attempts"]
        self.assertEqual(stats["execution_outcome"]["COMPLETED"]["rate"], 2 / 3)
        self.assertEqual(stats["execution_outcome"]["CANCELLED"]["rate"], 1 / 3)
        for status in ("ACCEPTED", "REJECTED", "NOT_EVALUATED"):
            self.assertEqual(stats["contract_status"][status]["rate"], 1 / 3)
        self.assertEqual(stats["semantic_quality"], "not_evaluated")
        self.assertEqual(report["logical_requests"]["last_observed_contract_status"]["ACCEPTED"]["count"], 1)

    def test_nulls_not_zero_and_overall_throughput_not_decode(self):
        metrics = summarize(fixture())["attempts"]["metrics"]
        self.assertEqual(metrics["firstTokenLatencyMs"]["n"], 1)
        self.assertEqual(metrics["firstTokenLatencyMs"]["mean"], 20)
        self.assertEqual(metrics["outputTokens"]["n"], 2)
        self.assertEqual(metrics["outputTokens"]["mean"], 5)
        rate = metrics["overall_output_tokens_per_second"]
        self.assertEqual((rate["n"], rate["missing_or_undefined_n"], rate["mean"]), (1, 2, 100))
        self.assertNotIn("decode_tokens_per_second", metrics)
        row = fixture()[0]
        row.update(outputTokens=0, totalLatencyMs=1)
        self.assertEqual(summarize([row])["attempts"]["metrics"]["overall_output_tokens_per_second"]["mean"], 0)

    def test_nearest_rank_and_sample_count(self):
        stats = distribution(list(range(1, 21)), 23)
        self.assertEqual((stats["p50"], stats["p95"], stats["n"]), (10, 19, 20))
        self.assertEqual(stats["missing_or_undefined_n"], 3)
        self.assertIn("nearest-rank", stats["percentile_method"])
        self.assertEqual(distribution([7], 1)["p95"], 7)
        self.assertIsNone(distribution([], 0)["p50"])
        empty = summarize([])
        self.assertIsNone(empty["attempts"]["execution_outcome"]["COMPLETED"]["rate"])

    def test_matched_comparison_and_no_missing_subset_deltas(self):
        left = fixture()
        right = copy.deepcopy(left)
        right[0]["totalLatencyMs"] = 120
        result = observed_deltas(left, right, metadata(), metadata())
        self.assertEqual(result["status"], "matched")
        initial = next(g for g in result["groups"] if g["task"] == "CHAT" and g["trigger"] == "INITIAL")
        self.assertEqual(initial["metrics"]["totalLatencyMs"]["delta"]["p50"], 20)
        cloud = next(g for g in result["groups"] if g["backend"] == "CLOUD")
        self.assertIsNone(cloud["metrics"]["outputTokens"]["delta"])
        self.assertEqual(cloud["metrics"]["outputTokens"]["status"], "not_comparable")

    def test_workload_mismatch_produces_no_deltas(self):
        other = dict(metadata(), workload_sha256="d" * 64)
        result = observed_deltas(fixture(), fixture(), metadata(), other)
        self.assertEqual(result["status"], "unmatched")
        self.assertEqual(result["reason"], "workload_sha256_mismatch")
        self.assertIsNone(result["groups"])

    def test_partial_group_or_request_coverage_never_compared(self):
        left = fixture()
        variants = [left[:-1], left[:1], []]
        changed = copy.deepcopy(left)
        changed[1]["trigger"] = "RUNTIME_FALLBACK"
        variants.append(changed)
        changed = copy.deepcopy(left)
        changed[1]["requestHash"] = "d" * 64
        variants.append(changed)
        changed = copy.deepcopy(left)
        changed[1]["attempt"] = 3
        variants.append(changed)
        for right in variants:
            with self.subTest(n=len(right)):
                result = observed_deltas(left, right, metadata(), metadata())
                self.assertEqual(result["status"], "unmatched")
                self.assertIsNone(result["groups"])

    def test_partial_nullable_distribution_is_suppressed_even_when_counts_equal(self):
        left = fixture()[:1]
        another = dict(left[0], requestHash="e" * 64, outputTokens=None)
        left.append(another)
        right = copy.deepcopy(left)
        right[0]["outputTokens"], right[1]["outputTokens"] = None, 50
        result = observed_deltas(left, right, metadata(), metadata())
        metric = result["groups"][0]["metrics"]["outputTokens"]
        self.assertEqual((metric["left_n"], metric["right_n"]), (1, 1))
        self.assertIsNone(metric["delta"])

    def test_different_session_hashes_can_compare_and_outcomes_can_change(self):
        right = fixture()
        for row in right:
            row["requestHash"] = "e" * 64 if row["task"] == "CHAT" else "f" * 64
        right[0].update(outcome="FAILED", errorType="MODEL_RUNTIME")
        result = observed_deltas(fixture(), right, metadata(), metadata())
        self.assertEqual(result["status"], "matched")
        group = next(g for g in result["groups"] if g["task"] == "CHAT" and g["backend"] == "MNN")
        self.assertEqual(group["execution_outcome"]["FAILED"]["rate_delta"], 1)
        self.assertEqual(group["contract_status"]["REJECTED"]["rate_delta"], 0)


if __name__ == "__main__":
    unittest.main()
