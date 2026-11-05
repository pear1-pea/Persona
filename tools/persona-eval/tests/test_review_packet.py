import base64
import hashlib
import html
import json
from pathlib import Path
import re
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from evaluate import suite_digest
from review_packet import create_packet, normalize, validate
from schema import VERSION, load_suite


def fixture():
    # Tooling fixtures are synthetic placeholders, never claimed as model evidence.
    cases = [case for case in load_suite() if case["track"] == "llm_subjective" and case["split"] == "dev"]
    return {"kind": "actual_mnn_subjective_batch", "dataset_version": VERSION,
            "dataset_sha256": suite_digest(), "split": "dev", "synthetic_data_only": True,
            "execution_status": "completed", "config_snapshots": {"baseline": {}, "candidate": {}},
            "records": [{"case_id": case["id"], "arm": arm, "repetition": repetition,
                         "status": "completed", "response": "<script>FORBIDDEN</script>&合成单测",
                         "authorized_evidence_refs": [], "duration_ms": 1}
                        for case in cases for arm in ("baseline", "candidate") for repetition in (1, 2, 3)]}


class ReviewPacketTest(unittest.TestCase):
    def test_missing_duplicate_and_wrong_digest_are_rejected(self):
        for mutate in (lambda r: r["records"].pop(),
                       lambda r: r["records"].append(r["records"][0]),
                       lambda r: r.update(dataset_sha256="0" * 64)):
            report = fixture()
            mutate(report)
            with self.assertRaises(ValueError):
                validate(report)

    def test_unknown_evidence_and_false_success_are_rejected(self):
        report = fixture()
        report["records"][0]["authorized_evidence_refs"] = ["not-authorized"]
        with self.assertRaises(ValueError):
            validate(report)
        report = fixture()
        report["records"][0]["response"] = None
        with self.assertRaises(ValueError):
            validate(report)

    def test_blind_packet_escapes_outputs_retains_failure_and_never_scores(self):
        report = fixture()
        report["records"][0].update(status="failed", response=None, failure_class="SyntheticTestFailure")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "synthetic-tooling-only.json"
            source.write_text(json.dumps(report), encoding="utf-8")
            out = root / "packet"
            result = create_packet(source, out)
            page = (out / "review.html").read_text(encoding="utf-8")
            review = json.loads((out / "human-review.json").read_text(encoding="utf-8"))
            mapping = json.loads((out / "private-mapping.json").read_text(encoding="utf-8"))
            self.assertEqual(result["pairs"], 30)
            self.assertEqual(result["execution_counts"]["failed"], 1)
            self.assertNotIn("<script>FORBIDDEN", page)
            self.assertIn("&lt;script&gt;", page)
            self.assertIn("script-src 'sha256-", page)
            self.assertIn("SyntheticTestFailure", page)
            self.assertEqual(mapping["packet_id"], review["packet_id"])
            self.assertTrue(all({row["A"], row["B"]} == {"baseline", "candidate"} for row in mapping["mapping"]))
            self.assertTrue(all(pair["human_reviewer"] is None and pair["winner"] is None for pair in review["cases"]))
            self.assertEqual(review["summary"]["release_decision"], "pending_human_review")
            with self.assertRaises(FileExistsError):
                create_packet(source, out)

    def test_non_synthetic_or_incomplete_batch_is_rejected(self):
        for field, value in (("synthetic_data_only", False), ("execution_status", "running")):
            report = fixture()
            report[field] = value
            with self.assertRaises(ValueError):
                validate(report)

    def test_real_cloud_single_configuration_never_fabricates_a_second_arm(self):
        report = fixture()
        report.update(kind="actual_cloud_subjective_probe", split="heldout", case_id="heldout-12")
        report.pop("config_snapshots")
        report["records"] = [{"case_id": "heldout-12", "repetition": repetition, "status": "completed",
                              "response": "合成工具单测占位，不是模型结果", "authorized_evidence_refs": []}
                             for repetition in (1, 2, 3)]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "tooling-only.json"
            source.write_text(json.dumps(report), encoding="utf-8")
            result = create_packet(source, root / "packet")
            self.assertEqual(result["pairs"], 0)
            self.assertEqual(result["items"], 3)
            mapping = json.loads((root / "packet/private-mapping.json").read_text(encoding="utf-8"))
            self.assertTrue(all(row["A"] == "candidate" and "B" not in row for row in mapping["mapping"]))
            page = (root / "packet/review.html").read_text(encoding="utf-8")
            self.assertIn("仅有一个云端配置", page)

    def test_arbitrary_cloud_subset_cannot_masquerade_as_full_heldout(self):
        from review_packet import normalize
        report = fixture()
        report.update(kind="actual_cloud_subjective_probe", split="heldout", case_id="heldout-09")
        with self.assertRaises(ValueError):
            normalize(report)

    def test_same_export_gets_distinct_bound_packets(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "synthetic-tooling-only.json"
            source.write_text(json.dumps(fixture()), encoding="utf-8")
            reviews = []
            for name in ("first", "second"):
                output = root / name
                create_packet(source, output)
                review = json.loads((output / "human-review.json").read_text())
                mapping = json.loads((output / "private-mapping.json").read_text())
                self.assertRegex(review["packet_id"], r"^[0-9a-f]{32}$")
                self.assertEqual(mapping["packet_id"], review["packet_id"])
                page = (output / "review.html").read_text()
                embedded = re.search(r'id="review-data">(.*?)</script>', page).group(1)
                self.assertEqual(json.loads(embedded)["packet_id"], review["packet_id"])
                reviews.append(review)
            self.assertEqual(reviews[0]["input_sha256"], reviews[1]["input_sha256"])
            self.assertNotEqual(reviews[0]["packet_id"], reviews[1]["packet_id"])

    def test_local_batch_cannot_contract_to_single_cloud_case(self):
        report = fixture()
        report.update(split="heldout", comparison_mode="single_configuration")
        report["config_snapshots"] = {"candidate": {}}
        report["records"] = [dict(case_id="heldout-12", arm="candidate", repetition=i,
                                  status="completed", response="Synthetic tooling fixture",
                                  authorized_evidence_refs=[]) for i in (1, 2, 3)]
        with self.assertRaisesRegex(ValueError, "mode"):
            validate(report)

    def test_unknown_or_conflicting_comparison_modes_are_rejected(self):
        report = fixture()
        report["comparison_mode"] = "unknown"
        with self.assertRaisesRegex(ValueError, "mode"):
            validate(report)
        report.update(kind="actual_cloud_subjective_probe", split="heldout", case_id="heldout-12",
                      comparison_mode="blind_pairwise")
        with self.assertRaisesRegex(ValueError, "mode"):
            normalize(report)
        with self.assertRaisesRegex(ValueError, "mode"):
            validate(report)

    def test_cloud_case_scope_is_checked_even_without_normalization(self):
        report = fixture()
        report.update(kind="actual_cloud_subjective_probe", comparison_mode="single_configuration",
                      split="heldout", case_id="heldout-09")
        report["config_snapshots"] = {"candidate": {}}
        report["records"] = [dict(case_id="heldout-12", arm="candidate", repetition=i,
                                  status="completed", response="Synthetic tooling fixture",
                                  authorized_evidence_refs=[]) for i in (1, 2, 3)]
        with self.assertRaisesRegex(ValueError, "scope"):
            validate(report)

    def test_page_shows_only_actual_generation_profile_and_escapes_it(self):
        for case_id in ("dev-19", "heldout-12"):
            with self.subTest(case_id=case_id):
                suite = load_suite()
                case = next(case for case in suite if case["id"] == case_id)
                case["persona_profile"].update(name='<script>NAME</script>&',
                    personaBackstory='<img src=x onerror="BACKSTORY">',
                    language="METADATA_LANGUAGE", style="METADATA_STYLE", fiction="METADATA_FICTION",
                    traits=["METADATA_TRAIT"])
                report = fixture()
                if case_id == "heldout-12":
                    report.update(kind="actual_cloud_subjective_probe", split="heldout", case_id=case_id)
                    report.pop("config_snapshots")
                    report["records"] = [dict(case_id=case_id, repetition=i, status="completed",
                        response="Synthetic cloud tooling fixture", authorized_evidence_refs=[])
                        for i in (1, 2, 3)]
                with tempfile.TemporaryDirectory() as directory:
                    root = Path(directory)
                    source = root / "synthetic-tooling-only.json"
                    source.write_text(json.dumps(report), encoding="utf-8")
                    with patch("review_packet.load_suite", return_value=suite):
                        create_packet(source, root / "packet")
                    page = (root / "packet/review.html").read_text()
                    profiles = re.findall(r'<pre class="generation-persona">(.*?)</pre>', page, re.S)
                    actual = json.loads(html.unescape(profiles[0]))
                    self.assertEqual(actual, {
                        "name": '<script>NAME</script>&', "backstory": '<img src=x onerror="BACKSTORY">',
                        "traits": []})
                    if case_id == "heldout-12":
                        self.assertEqual(len(profiles), 3)
                        self.assertTrue(all(json.loads(html.unescape(profile)) == actual for profile in profiles))
                    self.assertNotIn('<script>NAME', page)
                    self.assertNotIn('<img src=x', page)
                    for value in ("METADATA_LANGUAGE", "METADATA_STYLE", "METADATA_FICTION", "METADATA_TRAIT"):
                        self.assertNotIn(value, page)
                    self.assertIn("未作为独立字段传入模型", page)
                    script = re.search(r'<script>(.*?)</script></html>', page, re.S).group(1)
                    script_hash = base64.b64encode(hashlib.sha256(script.encode()).digest()).decode()
                    self.assertIn("script-src 'sha256-" + script_hash + "'", page)


if __name__ == "__main__":
    unittest.main()
