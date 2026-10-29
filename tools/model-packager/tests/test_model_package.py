from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

TOOL_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOL_DIR))

from model_package import (  # noqa: E402
    PackageError,
    build_manifest,
    sha256_file,
    validate_manifest,
    write_manifest,
    write_ready,
)


class ModelPackageTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        (self.root / "config.json").write_text(
            json.dumps({"llm_model": "llm.mnn", "llm_weight": "llm.mnn.weight"}),
            encoding="utf-8",
        )
        (self.root / "llm.mnn").write_text("model", encoding="utf-8")
        (self.root / "llm.mnn.weight").write_text("weight", encoding="utf-8")
        (self.root / "tokenizer.txt").write_text("tokenizer", encoding="utf-8")
        (self.root / "vocab.txt").write_text("vocab", encoding="utf-8")

    def tearDown(self) -> None:
        self.temp.cleanup()

    def test_build_manifest_hashes_all_files_and_roles(self) -> None:
        manifest = build_manifest(self.root, model_id="qwen-test")

        self.assertEqual("qwen-test", manifest["id"])
        self.assertEqual("llm.mnn", manifest["entry"])
        self.assertEqual("tokenizer.txt", manifest["tokenizer"])
        paths = {item["path"]: item for item in manifest["files"]}
        self.assertEqual({"config.json", "llm.mnn", "llm.mnn.weight", "tokenizer.txt", "vocab.txt"}, set(paths))
        self.assertEqual("config", paths["config.json"]["role"])
        self.assertEqual("model", paths["llm.mnn"]["role"])
        self.assertEqual("weight", paths["llm.mnn.weight"]["role"])
        self.assertEqual("tokenizer", paths["tokenizer.txt"]["role"])
        self.assertEqual("file", paths["vocab.txt"]["role"])
        self.assertEqual(sha256_file(self.root / "vocab.txt"), paths["vocab.txt"]["sha256"])

    def test_write_and_validate_ready_package(self) -> None:
        manifest = build_manifest(self.root, model_id="qwen-test")
        manifest_path = write_manifest(self.root, manifest)
        write_ready(self.root)

        errors, warnings = validate_manifest(self.root, json.loads(manifest_path.read_text()), require_ready=True)

        self.assertEqual([], errors)
        self.assertTrue(any("signature" in warning for warning in warnings))

    def test_validate_detects_bad_hash(self) -> None:
        manifest = build_manifest(self.root, model_id="qwen-test")
        write_ready(self.root)
        (self.root / "llm.mnn").write_text("tampered", encoding="utf-8")

        errors, _ = validate_manifest(self.root, manifest)

        self.assertTrue(any("SHA256" in error for error in errors))

    def test_build_rejects_crlf_text_tokenizer(self) -> None:
        (self.root / "tokenizer.txt").write_bytes(b"430 3\r\n!\r\n")
        with self.assertRaisesRegex(PackageError, "CRLF"):
            build_manifest(self.root, model_id="qwen-test")

    def test_validate_rejects_crlf_even_with_matching_hash(self) -> None:
        manifest = build_manifest(self.root, model_id="qwen-test")
        tokenizer = self.root / "tokenizer.txt"
        tokenizer.write_bytes(b"x" * (1024 * 1024 - 1) + b"\r\n")
        item = next(item for item in manifest["files"] if item["path"] == "tokenizer.txt")
        item.update(size=tokenizer.stat().st_size, sha256=sha256_file(tokenizer))

        errors, _ = validate_manifest(self.root, manifest)

        self.assertTrue(any("CRLF" in error for error in errors))

    def test_lf_text_tokenizer_is_accepted(self) -> None:
        (self.root / "tokenizer.txt").write_bytes(b"430 3\n!\n")
        manifest = build_manifest(self.root, model_id="qwen-test")
        errors, _ = validate_manifest(self.root, manifest)
        self.assertEqual([], errors)

    def test_build_rejects_entry_mismatch(self) -> None:
        (self.root / "config.json").write_text(
            json.dumps({"llm_model": "other.mnn", "llm_weight": "llm.mnn.weight"}),
            encoding="utf-8",
        )

        with self.assertRaises(PackageError):
            build_manifest(self.root, model_id="qwen-test")

    def test_validate_rejects_path_traversal(self) -> None:
        manifest = build_manifest(self.root, model_id="qwen-test")
        write_ready(self.root)
        manifest["files"].append(
            {
                "path": "../escape",
                "role": "file",
                "size": 0,
                "sha256": "0" * 64,
            }
        )

        errors, _ = validate_manifest(self.root, manifest)

        self.assertTrue(any("files[5].path" in error or "unsafe" in error for error in errors))

    def test_validate_rejects_duplicate_path(self) -> None:
        manifest = build_manifest(self.root, model_id="qwen-test")
        write_ready(self.root)
        manifest["files"].append(dict(manifest["files"][0]))

        errors, _ = validate_manifest(self.root, manifest)

        self.assertTrue(any("duplicate" in error.lower() for error in errors))

    def test_validate_requires_ready(self) -> None:
        manifest = build_manifest(self.root, model_id="qwen-test")

        errors, warnings = validate_manifest(self.root, manifest, require_ready=True)

        self.assertTrue(any("READY" in error for error in errors))
        self.assertFalse(any("READY" in warning for warning in warnings))

    def test_missing_tokenizer_is_reported(self) -> None:
        (self.root / "tokenizer.txt").unlink()

        with self.assertRaises(PackageError):
            build_manifest(self.root, model_id="qwen-test")

    def test_tokenizer_mtok_is_auto_detected(self) -> None:
        (self.root / "tokenizer.txt").unlink()
        (self.root / "tokenizer.mtok").write_text("mtok", encoding="utf-8")

        manifest = build_manifest(self.root, model_id="qwen-test")

        self.assertEqual("tokenizer.mtok", manifest["tokenizer"])

    @unittest.skipIf(os.name == "nt", "symlink creation needs privileges on Windows")
    def test_build_rejects_symlink(self) -> None:
        (self.root / "linked.mnn").symlink_to(self.root / "llm.mnn")

        with self.assertRaises(PackageError):
            build_manifest(self.root, model_id="qwen-test")

    def test_cli_generates_and_validates(self) -> None:
        result = subprocess.run(
            [
                sys.executable,
                str(TOOL_DIR / "generate_manifest.py"),
                str(self.root),
                "--id",
                "qwen-cli",
                "--context-window",
                "32768",
            ],
            cwd=str(TOOL_DIR),
            check=True,
            capture_output=True,
            text=True,
        )
        self.assertIn("Wrote", result.stdout)
        self.assertTrue((self.root / "manifest.json").is_file())
        self.assertTrue((self.root / "READY").is_file())

        validate = subprocess.run(
            [
                sys.executable,
                str(TOOL_DIR / "validate_package.py"),
                str(self.root),
                "--require-ready",
            ],
            cwd=str(TOOL_DIR),
            check=True,
            capture_output=True,
            text=True,
        )
        self.assertIn("OK", validate.stdout)


if __name__ == "__main__":
    unittest.main()
