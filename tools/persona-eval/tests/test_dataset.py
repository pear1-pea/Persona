from copy import deepcopy
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from schema import ROOT, load_suite, validate_case


class DatasetTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cases = load_suite()

    def check_modified_suite(self, mutate, expected_error):
        cases = deepcopy(self.cases)
        mutate(cases)
        with tempfile.TemporaryDirectory(dir=ROOT / "results") as directory:
            root = Path(directory)
            (root / "datasets").mkdir()
            for split in ("dev", "heldout"):
                text = "\n".join(json.dumps(c) for c in cases if c["split"] == split)
                (root / "datasets" / f"{split}.jsonl").write_text(text + "\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, expected_error):
                load_suite(root)

    def test_shared_family_is_rejected(self):
        def mutate(cases):
            cases[-1]["family"] = cases[0]["family"]
        self.check_modified_suite(mutate, "family leaks")

    def test_identical_conversation_is_rejected(self):
        def mutate(cases):
            cases[-1]["messages"] = cases[0]["messages"]
        self.check_modified_suite(mutate, "duplicate conversation")

    def test_duplicate_case_id_is_rejected(self):
        def mutate(cases):
            cases[1]["id"] = cases[0]["id"]
        self.check_modified_suite(mutate, "duplicate case id")

    def test_missing_risk_coverage_is_rejected(self):
        def mutate(cases):
            for case in cases:
                case["tags"] = [t for t in case["tags"] if t != "injection"] or ["style"]
        self.check_modified_suite(mutate, "missing risk coverage")

    def test_naive_time_is_rejected(self):
        case = deepcopy(self.cases[0])
        case["context"]["as_of"] = "2026-10-29T08:00:00"
        with self.assertRaisesRegex(ValueError, "timezone"):
            validate_case(case, "dev")

    def test_missing_source_is_rejected(self):
        case = deepcopy(self.cases[0])
        case["memories"][0].pop("save_source")
        with self.assertRaisesRegex(ValueError, "fields differ"):
            validate_case(case, "dev")

    def test_corpus_uses_fixed_chinese_persona(self):
        profiles = [case["persona_profile"] for case in self.cases]
        self.assertTrue(all(p == profiles[0] for p in profiles))
        self.assertEqual(profiles[0]["language"], "zh-CN")
        self.assertTrue(all(case["synthetic"] for case in self.cases))


if __name__ == "__main__":
    unittest.main()
