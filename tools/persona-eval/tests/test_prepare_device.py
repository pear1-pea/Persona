import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from schema import ROOT, VERSION, load_suite
from evaluate import suite_digest
from prepare_device import build_bundle, source_snapshot, snapshot_digest


class PrepareDeviceTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.repo = Path(self.directory.name)
        self.files = {
            "app/src/main/java/测试.kt": b"production\x00code",
            "app/src/androidTest/java/Adapter.kt": b"instrumentation",
            "app/src/test/java/BehaviorTest.kt": b"jvm behavior test",
            "app/build.gradle.kts": b"app",
            "build.gradle.kts": b"root",
            "gradle/libs.versions.toml": b"versions",
            "settings.gradle.kts": b"settings",
            "gradle.properties": b"properties",
        }
        for name, content in self.files.items():
            path = self.repo / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)

    def test_exact_snapshot_scope_and_binary_digest_algorithm(self):
        for name in (".env", "local.properties", "models/secret.bin", "other/key.pem"):
            path = self.repo / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"excluded secret")
        snapshot = source_snapshot(self.repo)
        self.assertEqual(snapshot, {
            name: hashlib.sha256(content).hexdigest() for name, content in self.files.items()})
        entries = sorted(self.files, key=lambda name: name.encode("utf-8"))
        preimage = b"".join(name.encode("utf-8") + b"\x00" +
                            hashlib.sha256(self.files[name]).digest() for name in entries)
        self.assertEqual(snapshot_digest(snapshot), hashlib.sha256(preimage).hexdigest())

    def test_snapshot_detects_content_path_addition_and_deletion(self):
        original = snapshot_digest(source_snapshot(self.repo))
        path = self.repo / "app/src/main/java/new.kt"
        path.write_bytes(b"new")
        added = snapshot_digest(source_snapshot(self.repo))
        self.assertNotEqual(added, original)
        path.write_bytes(b"changed")
        self.assertNotEqual(snapshot_digest(source_snapshot(self.repo)), added)
        path.rename(path.with_name("renamed.kt"))
        self.assertNotEqual(snapshot_digest(source_snapshot(self.repo)), added)
        path.with_name("renamed.kt").unlink()
        self.assertEqual(snapshot_digest(source_snapshot(self.repo)), original)

    def test_missing_required_source_and_symlink_are_rejected(self):
        path = self.repo / "app/build.gradle.kts"
        path.unlink()
        with self.assertRaises((ValueError, OSError)):
            source_snapshot(self.repo)
        path.symlink_to(self.repo / "build.gradle.kts")
        with self.assertRaises(ValueError):
            source_snapshot(self.repo)

    def test_bundle_split_defaults_and_unfiltered_inputs(self):
        suite = load_suite()
        self.assertEqual(len(suite), 40)
        for split, count in (("dev", 18), ("heldout", 8), ("all", 26)):
            bundle = build_bundle(split, self.repo)
            self.assertEqual(set(bundle), {
                "bundle_version", "dataset_version", "dataset_sha256", "split",
                "implementation_revision", "source_snapshot", "cases"})
            self.assertEqual(bundle["bundle_version"], "m5-room-adapter-input-v1")
            self.assertEqual(bundle["dataset_version"], VERSION)
            self.assertEqual(bundle["dataset_sha256"], suite_digest())
            self.assertEqual(bundle["split"], split)
            self.assertEqual(len(bundle["cases"]), count)
            expected = [{k: v for k, v in case.items() if k != "expected"}
                        for case in suite if case["track"] == "deterministic"
                        and (split == "all" or case["split"] == split)]
            self.assertEqual(bundle["cases"], expected)
            self.assertTrue(all("expected" not in case and "rubric" not in case
                                for case in bundle["cases"]))
            self.assertEqual(bundle["implementation_revision"], "sha256:" +
                             snapshot_digest(bundle["source_snapshot"]))
        self.assertEqual(build_bundle(repo_root=self.repo)["split"], "dev")

    def run_cli(self, *args):
        return subprocess.run([sys.executable, str(ROOT / "prepare_device.py"), *args],
                              capture_output=True, text=True)

    def test_cli_deterministic_bytes_digest_and_private_stdout(self):
        outputs = []
        for name in ("first.json", "second.json"):
            path = self.repo / name
            process = self.run_cli("--output", str(path))
            self.assertEqual(process.returncode, 0, process.stderr)
            summary = json.loads(process.stdout)
            raw = path.read_bytes()
            outputs.append(raw)
            self.assertEqual(summary, {"file": name, "cases": 18,
                                      "bundle_sha256": hashlib.sha256(raw).hexdigest(),
                                      "status": "not_run"})
            bundle = json.loads(raw)
            self.assertEqual(bundle["split"], "dev")
            self.assertNotIn("memories", process.stdout)
            self.assertNotIn("personaBackstory", process.stdout)
            self.assertEqual(process.stderr, "")
        self.assertEqual(outputs[0], outputs[1])

    def test_cli_split_counts(self):
        for split, count in (("heldout", 8), ("all", 26)):
            path = self.repo / (split + ".json")
            process = self.run_cli("--split", split, "--output", str(path))
            self.assertEqual(process.returncode, 0, process.stderr)
            bundle = json.loads(path.read_bytes())
            self.assertEqual(bundle["split"], split)
            self.assertEqual(len(bundle["cases"]), count)

    def test_cli_requires_output_and_refuses_overwrite(self):
        process = self.run_cli()
        self.assertEqual(process.returncode, 2)
        path = self.repo / "existing.json"
        path.write_bytes(b"preserve")
        process = self.run_cli("--output", str(path))
        self.assertEqual(process.returncode, 2)
        self.assertEqual(path.read_bytes(), b"preserve")
        self.assertEqual(process.stdout, "")


if __name__ == "__main__":
    unittest.main()
