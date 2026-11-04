from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import run_llm_device as runner


class LlmInputTest(unittest.TestCase):
    def test_battery_preflight_accepts_ready_physical_levels(self):
        for level in (20, 55, 100):
            output = f"Current Battery Service state:\r\n  level: {level}\r\n  scale: 100\r\n".encode()
            self.assertEqual(runner.require_evaluation_battery(output), level)

    def test_battery_preflight_rejects_low_or_unavailable_levels(self):
        for level in (0, 15, 19):
            with self.subTest(level=level), self.assertRaisesRegex(ValueError, "at least 20"):
                runner.require_evaluation_battery(f"  level: {level}\n".encode())
        for output in (b"", b"level: -1\n", b"level: 101\n", b"level: 20\nlevel: 90\n"):
            with self.subTest(output=output), self.assertRaisesRegex(ValueError, "battery level"):
                runner.require_evaluation_battery(output)

    def test_dev_input_never_includes_rubric_or_expected_and_keeps_raw_memories(self):
        bundle = runner.bundle("dev")
        self.assertEqual(len(bundle["cases"]), 10)
        self.assertTrue(bundle["implementation_revision"].startswith("sha256:"))
        self.assertTrue(all("rubric" not in case and "expected" not in case for case in bundle["cases"]))
        revisions = next(case for case in bundle["cases"] if case["id"] == "dev-24")["memories"]
        self.assertEqual([row["version"] for row in revisions], [1, 2])
        self.assertEqual([row["deleted"] for row in revisions], [True, False])

    def test_cloud_fixture_is_preserved_and_cannot_be_silently_mapped_to_local(self):
        bundle = runner.bundle("heldout")
        self.assertEqual(len(bundle["cases"]), 4)
        cloud = next(case for case in bundle["cases"] if case["id"] == "heldout-12")
        self.assertEqual(cloud["context"]["executor"], "cloud")
        self.assertFalse(cloud["memories"][0]["cloud_allowed"])

    def test_model_directory_with_unfrozen_manifest_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "primary"
            path.mkdir()
            (path / "manifest.json").write_text("{}")
            with self.assertRaisesRegex(ValueError, "differs from frozen"):
                runner.model_files(Path(directory))


if __name__ == "__main__":
    unittest.main()
