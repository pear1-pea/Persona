from copy import deepcopy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from evaluate import compare, suite_digest, template
from schema import ROOT, load_suite


class RunnerTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cases = [c for c in load_suite() if c["split"] == "dev"]

    def observations(self):
        # Test-only fabricated adapter observations, never application evidence.
        result = template(self.cases)
        result.update(implementation_revision="unit-test-only", adapter_version="fake-v1",
                      evidence_ref="synthetic:unit-test")
        expected = {c["id"]: c["expected"] for c in self.cases if c["track"] == "deterministic"}
        for row in result["observations"]:
            row.update(status="observed", actual=deepcopy(expected[row["case_id"]]))
        return result

    def test_comparison_matches_exact_evidence(self):
        results = compare(self.cases, self.observations())
        self.assertEqual(len(results), 18)
        self.assertTrue(all(r["status"] == "pass" for r in results))

    def test_extra_private_evidence_is_a_failure(self):
        observations = self.observations()
        observations["observations"][0]["actual"]["injected"].append("leaked-owner:v1")
        self.assertEqual(compare(self.cases, observations)[0]["status"], "fail")

    def test_missing_allowed_evidence_is_also_a_failure(self):
        observations = self.observations()
        observations["observations"][0]["actual"]["injected"] = []
        self.assertEqual(compare(self.cases, observations)[0]["status"], "fail")

    def test_template_is_not_an_executed_run(self):
        observations = self.observations()
        observations["observations"][0]["status"] = "not_run"
        with self.assertRaisesRegex(ValueError, "not run"):
            compare(self.cases, observations)

    def test_missing_duplicate_and_wrong_split_are_rejected(self):
        for mutation in ("missing", "duplicate", "split"):
            observations = self.observations()
            if mutation == "missing":
                observations["observations"].pop()
            elif mutation == "duplicate":
                observations["observations"][-1] = observations["observations"][0]
            else:
                observations["observations"][0]["case_id"] = "heldout-01"
            with self.subTest(mutation=mutation), self.assertRaisesRegex(ValueError, "observations"):
                compare(self.cases, observations)

    def test_version_digest_and_evidence_are_required(self):
        for key, value in (("dataset_version", "old"), ("dataset_sha256", "bad"),
                           ("evidence_kind", "reference_contract"), ("evidence_ref", ""),
                           ("implementation_revision", ""), ("adapter_version", "")):
            observations = self.observations()
            observations[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                compare(self.cases, observations)

    def test_missing_boundary_or_repeated_refs_are_rejected(self):
        for mutation in ("boundary", "refs"):
            observations = self.observations()
            actual = observations["observations"][0]["actual"]
            if mutation == "boundary":
                del actual["candidates"]
            else:
                actual["injected"] *= 2
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                compare(self.cases, observations)

    def test_cli_default_avoids_heldout_model_runs_and_preserves_limits(self):
        process = subprocess.run([sys.executable, str(ROOT / "evaluate.py"), "reference"],
                                 capture_output=True, text=True, check=True)
        report = json.loads(process.stdout)
        self.assertEqual(report["split"], "dev")
        self.assertEqual(report["counts"], {"pass": 18})
        self.assertEqual(report["llm_quality"], "not_run")
        self.assertEqual(report["application_conformance"], "not_run")

    def test_cli_refuses_overwrite(self):
        with tempfile.TemporaryDirectory(dir=ROOT / "results") as directory:
            output = Path(directory) / "result.json"
            output.write_text("original", encoding="utf-8")
            process = subprocess.run([sys.executable, str(ROOT / "evaluate.py"), "template",
                                      "--output", str(output)], capture_output=True, text=True)
            self.assertEqual(process.returncode, 2)
            self.assertEqual(output.read_text(encoding="utf-8"), "original")

    def test_llm_template_remains_pending(self):
        template_data = json.loads((ROOT / "templates" / "llm-run.v2.json").read_text())
        self.assertEqual(template_data["status"], "not_run")
        self.assertEqual(template_data["cases"], [])
        self.assertEqual(template_data["summary"]["release_decision"], "pending_human_review")
        self.assertEqual(len(suite_digest()), 64)

    def test_unsupported_is_never_a_pass_and_preserves_reason(self):
        observations = self.observations()
        observations["adapter_scope"] = "room_repository_prompt"
        observations["observations"][0].update(
            status="unsupported", actual=None, reason="No production confirmation API")
        results = compare(self.cases, observations)
        self.assertEqual(results[0], {
            "case_id": observations["observations"][0]["case_id"],
            "status": "unsupported", "reason": "No production confirmation API"})
        self.assertEqual(sum(r["status"] == "pass" for r in results), 17)

    def test_unsupported_requires_null_actual_and_nonblank_reason(self):
        for actual, reason in (({}, "Unavailable"), (None, ""), (None, " \n"),
                               (None, None), (None, 1)):
            observations = self.observations()
            observations["observations"][0].update(
                status="unsupported", actual=actual, reason=reason)
            with self.subTest(actual=actual, reason=reason), self.assertRaises(ValueError):
                compare(self.cases, observations)
        observations = self.observations()
        observations["observations"][0].update(status="unsupported", actual=None)
        with self.assertRaises(ValueError):
            compare(self.cases, observations)

    def test_cli_unsupported_exits_one_and_reports_incomplete(self):
        observations = self.observations()
        observations["adapter_scope"] = "room_repository_prompt"
        observations["observations"][0].update(
            status="unsupported", actual=None, reason="No automatic fallback API")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "observations.json"
            path.write_text(json.dumps(observations), encoding="utf-8")
            process = subprocess.run(
                [sys.executable, str(ROOT / "evaluate.py"), "compare",
                 "--observations", str(path)], capture_output=True, text=True)
        self.assertEqual(process.returncode, 1, process.stderr)
        report = json.loads(process.stdout)
        self.assertEqual(report["counts"], {"unsupported": 1, "pass": 17})
        self.assertEqual(report["application_conformance"], "reported_observations_incomplete")
        self.assertEqual(report["results"][0]["reason"], "No automatic fallback API")
        self.assertEqual(report["application_evidence"]["adapter_scope"], "room_repository_prompt")

    def test_cli_not_run_remains_rejected(self):
        observations = self.observations()
        observations["observations"][0].update(status="not_run", actual=None)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "observations.json"
            path.write_text(json.dumps(observations), encoding="utf-8")
            process = subprocess.run(
                [sys.executable, str(ROOT / "evaluate.py"), "compare",
                 "--observations", str(path)], capture_output=True, text=True)
        self.assertEqual(process.returncode, 2)
        self.assertIn("not run", process.stderr)


if __name__ == "__main__":
    unittest.main()
