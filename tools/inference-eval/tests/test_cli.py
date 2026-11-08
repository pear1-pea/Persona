import contextlib
import hashlib
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

TOOL = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOL))
from evaluate import ROOT, main


class CliTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.fixture = TOOL / "fixtures/baseline.json"

    def invoke(self, *args):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            status = main([str(arg) for arg in args])
        return status, out.getvalue(), err.getvalue()

    def test_cli_summarize_provenance_parameters_and_range(self):
        output = self.base / "summary"
        process = subprocess.run(
            [sys.executable, "-B", str(TOOL / "evaluate.py"), "summarize",
             str(self.fixture), "--output", str(output)],
            capture_output=True, text=True, cwd=ROOT, check=False)
        self.assertEqual(process.returncode, 0, process.stderr)
        report = json.loads((output / "report.json").read_text())
        digest = hashlib.sha256(self.fixture.read_bytes()).hexdigest()
        self.assertEqual(report["provenance"]["inputs"]["export"]["sha256"], digest)
        for path, digest in report["provenance"]["source_sha256"].items():
            self.assertEqual(hashlib.sha256((ROOT / path).read_bytes()).hexdigest(), digest)
        self.assertEqual(report["parameters"]["export"], str(self.fixture))
        self.assertEqual(report["summary"]["scope"]["startedAtEpochMs"], {"min": 1000, "max": 2000})
        self.assertNotIn("requestHash", (output / "report.json").read_text())

    def test_output_existing_file_directory_and_symlink_are_never_overwritten(self):
        directory = self.base / "exists"
        directory.mkdir()
        file = self.base / "file"
        file.write_text("keep")
        link = self.base / "link"
        link.symlink_to(directory, target_is_directory=True)
        dangling = self.base / "dangling"
        dangling.symlink_to(self.base / "absent")
        for output in (directory, file, link, dangling):
            with self.subTest(output=output.name):
                code, out, _ = self.invoke("summarize", self.fixture, "--output", output)
                self.assertEqual((code, out), (2, ""))
        self.assertEqual(file.read_text(), "keep")
        self.assertEqual(list(directory.iterdir()), [])
        self.assertEqual(self.invoke("summarize", self.fixture, "--output", self.fixture)[0], 2)

    def test_invalid_record_fails_whole_run_without_payload_or_output(self):
        rows = json.loads(self.fixture.read_text())
        rows[-1]["Prompt-SECRET"] = "PRIVATE-PAYLOAD"
        bad = self.base / "bad.json"
        bad.write_text(json.dumps(rows))
        output = self.base / "output"
        code, out, err = self.invoke("summarize", bad, "--output", output)
        self.assertEqual(code, 2)
        self.assertFalse(output.exists())
        self.assertNotIn("PRIVATE", out + err)
        self.assertNotIn("SECRET", out + err)

    def test_bad_json_does_not_echo_values(self):
        bad = self.base / "bad.json"
        bad.write_text('[PRIVATE-PAYLOAD]')
        code, out, err = self.invoke("summarize", bad, "--output", self.base / "output")
        self.assertEqual(code, 2)
        self.assertNotIn("PRIVATE", out + err)

    def test_missing_input_and_output_parent_fail_without_creating_output(self):
        output = self.base / "output"
        self.assertEqual(self.invoke("summarize", self.base / "missing", "--output", output)[0], 2)
        nested = self.base / "missing-parent" / "child"
        self.assertEqual(self.invoke("summarize", self.fixture, "--output", nested)[0], 2)
        self.assertFalse(output.exists())
        self.assertFalse(nested.parent.exists())

    def test_compare_sidecars_mismatch_is_explicit_and_replay_refuses_overwrite(self):
        fixture_dir = TOOL / "fixtures"
        metadata = json.loads((fixture_dir / "candidate.metadata.json").read_text())
        metadata["workload_sha256"] = "f" * 64
        sidecar = self.base / "right.metadata.json"
        sidecar.write_text(json.dumps(metadata))
        output = self.base / "compare"
        args = ("compare", self.fixture, fixture_dir / "candidate.json",
                "--left-metadata", fixture_dir / "baseline.metadata.json",
                "--right-metadata", sidecar, "--output", output)
        self.assertEqual(self.invoke(*args)[0], 0)
        raw = (output / "report.json").read_bytes()
        report = json.loads(raw)
        self.assertEqual(report["observed_deltas"]["status"], "unmatched")
        self.assertIsNone(report["observed_deltas"]["groups"])
        self.assertEqual(set(report["provenance"]["inputs"]), {
            "left_export", "right_export", "left_metadata", "right_metadata"})
        self.assertEqual(self.invoke(*args)[0], 2)
        self.assertEqual((output / "report.json").read_bytes(), raw)


if __name__ == "__main__":
    unittest.main()
