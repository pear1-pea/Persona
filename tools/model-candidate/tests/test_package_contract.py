from __future__ import annotations

import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

TOOL = Path(__file__).resolve().parents[1] / "package_contract.py"
SPEC = importlib.util.spec_from_file_location("candidate_contract", TOOL)
contract = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(contract)

MODEL_REVISION = "c1899de289a04d12100db370d81485cdf75e47ca"
MNN_REVISION = "d407447ed56c4121a11ccbd266dc184ca1ead0c2"
TEMPLATE = "private-template {{ messages }}\n{{ enable_thinking }}"


class PackageContractTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(
            dir=Path(tempfile.gettempdir()).resolve()
        )
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.root = self.base / "package"
        self.root.mkdir()
        self.receipt = self.base / "receipt.json"
        self.cli_tool = TOOL
        self.config = {
            "llm_model": "llm.mnn", "llm_weight": "llm.mnn.weight",
            "tokenizer_file": "tokenizer.mtok", "context_file": "context.json",
            "backend_type": "cpu",
        }
        self.llm = {"jinja": {"chat_template": TEMPLATE}}
        self.args = {
            "quant_bit": 8, "lm_quant_bit": 8, "embed_bit": 16,
            "seperate_embed": True, "path": "private-export-source",
        }
        for name, data in {
            "llm.mnn": b"synthetic-graph\x00",
            "llm.mnn.weight": b"synthetic-weight\xff",
            "tokenizer.mtok": b"synthetic-tokenizer\x00",
            "embeddings_bf16.bin": b"\x00\x3f\x80\x3f",
            "LICENSE": b"synthetic-license",
            "README.md": b"private-model-card",
        }.items():
            (self.root / name).write_bytes(data)
        self.write_json("config.json", self.config)
        self.write_json("llm_config.json", self.llm)
        self.write_json("context.json", {"enable_thinking": False})
        self.write_json("export_args.json", self.args)
        self.refresh_receipt()

    def write_json(self, name, value):
        (self.root / name).write_text(json.dumps(value), encoding="utf-8")

    def refresh_receipt(self):
        self.manifest = {
            "model_revision": MODEL_REVISION, "mnn_revision": MNN_REVISION,
            "quant_bits": [8, 8, 16],
            "files": [
                {"path": p.name, "size": p.stat().st_size,
                 "sha256": hashlib.sha256(p.read_bytes()).hexdigest()}
                for p in sorted(self.root.iterdir()) if p.is_file()
            ],
        }
        self.save_receipt()

    def save_receipt(self):
        self.receipt.write_text(json.dumps(self.manifest), encoding="utf-8")

    def source_lock(self):
        return {"modelRepo": "Qwen/Qwen3-0.6B", "modelRevision": MODEL_REVISION,
                "runtimeRevision": MNN_REVISION,
                "packageReceiptSha256": hashlib.sha256(self.receipt.read_bytes()).hexdigest()}

    def validate(self, root=None, receipt=None):
        return contract.validate_package(
            root or self.root, receipt or self.receipt, source_lock=self.source_lock()
        )

    def rejected(self, root=None, receipt=None):
        with self.assertRaisesRegex(
            contract.PackageContractError, "^package validation failed$"
        ):
            self.validate(root, receipt)

    def cli(self, output, *extra):
        return subprocess.run(
            [sys.executable, "-B", str(self.cli_tool), "--package", str(self.root),
             "--receipt", str(self.receipt), "--output", str(output), *extra],
            capture_output=True, text=True, check=False,
        )

    def test_valid_package_report_is_exact_and_contains_no_raw_content(self):
        report = self.validate()
        self.assertEqual({
            "schema_version": 1,
            "files": self.manifest["files"],
            "file_count": 10,
            "total_size_bytes": sum(f["size"] for f in self.manifest["files"]),
            "model": {"id": "Qwen/Qwen3-0.6B", "revision": MODEL_REVISION,
                      "quant_bits": [8, 8, 16]},
            "runtime": {"name": "MNN", "revision": MNN_REVISION,
                        "backend": "cpu"},
            "template_sha256": hashlib.sha256(TEMPLATE.encode()).hexdigest(),
            "enable_thinking": False,
            "budget": "not_device_test",
            "receipt_sha256": self.source_lock()["packageReceiptSha256"],
            "artifact_scope": "fixed_experiment_artifact",
            "release_trust": "not_signature_verified",
        }, report)
        rendered = json.dumps(report)
        for secret in (TEMPLATE, "private-export-source", "private-model-card",
                       str(self.base), "chat_template"):
            self.assertNotIn(secret, rendered)
        self.assertEqual(report, self.validate(str(self.root), str(self.receipt)))

    def test_optional_graph_json_and_false_jinja_context(self):
        self.write_json("llm.mnn.json", {"oplists": []})
        self.llm["jinja"]["context"] = {"enable_thinking": False, "other": "ok"}
        self.write_json("llm_config.json", self.llm)
        self.refresh_receipt()
        self.assertEqual(11, self.validate()["file_count"])

    def test_receipt_schema_and_numeric_types(self):
        original = copy.deepcopy(self.manifest)
        for field, values in {
            "model_revision": [None, "wrong", True],
            "mnn_revision": [None, "wrong", 123],
            "quant_bits": [[8, 8, True], [8.0, 8, 16], [4, 8, 16], [], None],
            "files": [[], {}, None],
        }.items():
            for value in values:
                with self.subTest(field=field, value=value):
                    self.manifest = copy.deepcopy(original)
                    self.manifest[field] = value
                    self.save_receipt()
                    self.rejected()
        for field in original:
            with self.subTest(missing=field):
                self.manifest = copy.deepcopy(original)
                del self.manifest[field]
                self.save_receipt()
                self.rejected()
        self.manifest = dict(original, unexpected="private-value")
        self.save_receipt()
        self.rejected()

    def test_invalid_file_entries(self):
        original = copy.deepcopy(self.manifest)
        for field, values in {
            "size": [True, False, 1.0, "1", None, -1, 0, 2147483649,
                     9223372036854775808],
            "sha256": [None, 42, "0" * 63, "g" * 64, "A" * 64],
            "path": [None, True, "", "/llm.mnn", "C:/llm.mnn", "C:llm.mnn",
                     "../llm.mnn", "a/../llm.mnn", "a\\llm.mnn",
                     "//host/share", "./config.json", "a//b", "a/./b",
                     "config.json\x00", "config.json:stream", "config.json/"],
        }.items():
            for value in values:
                with self.subTest(field=field, value=value):
                    self.manifest = copy.deepcopy(original)
                    self.manifest["files"][0][field] = value
                    self.save_receipt()
                    self.rejected()
        for entry in (None, [], {}, {"path": "config.json"},
                      dict(original["files"][0], extra=True)):
            with self.subTest(entry=entry):
                self.manifest = copy.deepcopy(original)
                self.manifest["files"][0] = entry
                self.save_receipt()
                self.rejected()

    def test_duplicate_missing_extra_and_case_alias_files(self):
        original = copy.deepcopy(self.manifest)
        self.manifest["files"].append(copy.deepcopy(self.manifest["files"][0]))
        self.save_receipt()
        self.rejected()
        for item in original["files"]:
            with self.subTest(missing=item["path"]):
                self.manifest = copy.deepcopy(original)
                self.manifest["files"].remove(item)
                self.save_receipt()
                self.rejected()
        self.manifest = original
        self.save_receipt()
        (self.root / "unlisted.bin").write_bytes(b"unexpected")
        self.rejected()
        self.refresh_receipt()
        self.rejected()
        (self.root / "unlisted.bin").unlink()
        self.refresh_receipt()
        self.manifest["files"][0]["path"] = "CONFIG.JSON"
        self.save_receipt()
        self.rejected()

    def test_size_and_hash_must_match_actual_bytes(self):
        path = self.root / "llm.mnn"
        original = path.read_bytes()
        path.write_bytes(b"X" * len(original))
        self.rejected()
        path.write_bytes(original + b"X")
        self.rejected()
        path.unlink()
        self.rejected()

    def test_duplicate_keys_in_every_json_including_optional_graph(self):
        for name in ("receipt", "config.json", "context.json", "llm_config.json",
                     "export_args.json", "llm.mnn.json"):
            with self.subTest(name=name):
                path = self.receipt if name == "receipt" else self.root / name
                original = path.read_bytes() if path.exists() else None
                valid = original or b'{"oplists":[]}'
                duplicate = (b'{"model_revision":"wrong",' + valid[1:]
                             if name == "receipt" else
                             valid[:-1] + b',"nested":{"x":1,"\\u0078":2}}')
                path.write_bytes(duplicate)
                if name != "receipt":
                    self.refresh_receipt()
                self.rejected()
                if original is None:
                    path.unlink()
                else:
                    path.write_bytes(original)
                self.refresh_receipt()

    def test_malformed_nonobject_nonfinite_and_out_of_range_json(self):
        for name in ("receipt", "config.json", "context.json", "llm_config.json",
                     "export_args.json"):
            path = self.receipt if name == "receipt" else self.root / name
            original = path.read_bytes()
            bad_numbers = [original[:-1] + b',"extra":' + number + b'}'
                           for number in (b"NaN", b"Infinity", b"1e999",
                                          b"9223372036854775808",
                                          b"-9223372036854775809")]
            for data in (b"[]", b"null", b"true", b"{", b"\xff", *bad_numbers):
                with self.subTest(name=name, data=data):
                    path.write_bytes(data)
                    if name != "receipt":
                        self.refresh_receipt()
                    self.rejected()
            path.write_bytes(original)
            self.refresh_receipt()

    def test_runtime_references_and_backend_are_exact(self):
        for key in self.config:
            for value in (None, True, "wrong", "./" + self.config[key]):
                with self.subTest(key=key, value=value):
                    changed = dict(self.config, **{key: value})
                    self.write_json("config.json", changed)
                    self.refresh_receipt()
                    self.rejected()
            changed = dict(self.config)
            del changed[key]
            self.write_json("config.json", changed)
            self.refresh_receipt()
            self.rejected()
        self.write_json("config.json", self.config)
        for key in self.config:
            with self.subTest(override=key):
                self.write_json("llm_config.json", dict(self.llm, **{key: "wrong"}))
                self.refresh_receipt()
                self.rejected()

    def test_thinking_switch_and_template(self):
        for value in (True, 0, 1, "false", None):
            with self.subTest(context=value):
                self.write_json("context.json", {"enable_thinking": value})
                self.refresh_receipt()
                self.rejected()
        self.write_json("context.json", {})
        self.refresh_receipt()
        self.rejected()
        self.write_json("context.json", {"enable_thinking": False})
        for jinja in (None, [], {}, {"chat_template": ""}, {"chat_template": "  "},
                      {"chat_template": 1}, {"chat_template": True},
                      {"chat_template": TEMPLATE, "context": None},
                      {"chat_template": TEMPLATE, "context": []},
                      *({"chat_template": TEMPLATE, "context": {"enable_thinking": v}}
                        for v in (True, 0, "false", None))):
            with self.subTest(jinja=jinja):
                self.write_json("llm_config.json", {"jinja": jinja})
                self.refresh_receipt()
                self.rejected()

    def test_export_args_are_typed_and_required(self):
        for key in self.args:
            if key == "path":
                continue
            for value in (None, False, 1, "8", 8.0):
                with self.subTest(key=key, value=value):
                    self.write_json("export_args.json", dict(self.args, **{key: value}))
                    self.refresh_receipt()
                    self.rejected()
            changed = dict(self.args)
            del changed[key]
            self.write_json("export_args.json", changed)
            self.refresh_receipt()
            self.rejected()

    def test_all_links_directories_and_special_files_are_rejected(self):
        target = self.root / "llm.mnn"
        external = self.base / "graph"
        target.rename(external)
        for destination in (external, self.base / "missing", self.root / "config.json"):
            with self.subTest(link=destination.name):
                target.symlink_to(destination)
                self.rejected()
                target.unlink()
        external.rename(target)
        (self.root / "extra-dir").mkdir()
        self.rejected()
        (self.root / "extra-dir").rmdir()
        (self.root / "linked-dir").symlink_to(self.base, target_is_directory=True)
        self.rejected()
        (self.root / "linked-dir").unlink()
        target.unlink()
        os.mkfifo(target)
        self.rejected()

    def test_root_receipt_and_their_parents_cannot_be_links(self):
        alias = self.base / "alias"
        alias.symlink_to(self.root, target_is_directory=True)
        self.rejected(root=alias)
        alias.unlink()
        alias.symlink_to(self.base, target_is_directory=True)
        self.rejected(root=alias / "package")
        self.rejected(receipt=alias / "receipt.json")
        alias.unlink()
        alias.symlink_to(self.receipt)
        self.rejected(receipt=alias)

    def test_hardlinked_file_alias_is_rejected(self):
        source = self.root / "llm.mnn"
        destination = self.root / "llm.mnn.weight"
        destination.unlink()
        os.link(source, destination)
        self.refresh_receipt()
        self.rejected()

    def test_receipt_digest_pin_and_default_lock_reject_self_attestation(self):
        trusted_lock = self.source_lock()
        self.receipt.write_bytes(self.receipt.read_bytes() + b"\n")
        with self.assertRaises(contract.PackageContractError):
            contract.validate_package(self.root, self.receipt, source_lock=trusted_lock)
        # Rehashing an altered artifact and its receipt cannot change the default pin.
        (self.root / "llm.mnn").write_bytes(b"self-attested-replacement")
        self.refresh_receipt()
        with self.assertRaises(contract.PackageContractError):
            contract.validate_package(self.root, self.receipt)
        for key, value in (("modelRepo", "other"), ("modelRevision", "other"),
                           ("runtimeRevision", "other"), ("packageReceiptSha256", None)):
            with self.subTest(lock_key=key):
                bad_lock = dict(self.source_lock(), **{key: value})
                with self.assertRaises(contract.PackageContractError):
                    contract.validate_package(self.root, self.receipt, source_lock=bad_lock)

    def test_receipt_and_json_size_limits_with_real_padded_files(self):
        receipt = self.receipt.read_bytes()
        self.receipt.write_bytes(receipt + b" " * (1048576 - len(receipt)))
        self.validate()
        with self.receipt.open("ab") as stream:
            stream.write(b" ")
        self.rejected()
        self.receipt.write_bytes(receipt)
        path = self.root / "config.json"
        config = path.read_bytes()
        path.write_bytes(config + b" " * (4194304 - len(config)))
        self.refresh_receipt()
        self.validate()
        with path.open("ab") as stream:
            stream.write(b" ")
        self.refresh_receipt()
        self.rejected()

    def test_sparse_file_and_aggregate_limits_before_hashing(self):
        graph = self.root / "llm.mnn"
        with graph.open("wb") as stream:
            stream.truncate(2147483649)
        item = next(f for f in self.manifest["files"] if f["path"] == "llm.mnn")
        item["size"] = 2147483649
        self.save_receipt()
        self.rejected()
        for name in ("llm.mnn", "llm.mnn.weight", "embeddings_bf16.bin"):
            with (self.root / name).open("wb") as stream:
                stream.truncate(2147483648)
            item = next(f for f in self.manifest["files"] if f["path"] == name)
            item["size"] = 2147483648
        self.save_receipt()
        self.rejected()

    def test_bool_size_is_not_a_one_byte_integer(self):
        (self.root / "llm.mnn").write_bytes(b"x")
        self.refresh_receipt()
        item = next(f for f in self.manifest["files"] if f["path"] == "llm.mnn")
        item["size"] = True
        self.save_receipt()
        self.rejected()

    def prepare_cli_fixture(self):
        tool_dir = self.base / "synthetic-tool"
        tool_dir.mkdir()
        self.cli_tool = tool_dir / "package_contract.py"
        self.cli_tool.write_bytes(TOOL.read_bytes())
        (tool_dir / "source-lock.json").write_text(json.dumps(self.source_lock()))

    def assert_cli_failure(self, result):
        self.assertEqual(1, result.returncode)
        self.assertEqual("", result.stdout)
        self.assertEqual("package validation failed\n", result.stderr)

    def test_cli_new_directory_writes_report_and_refuses_second_run(self):
        self.prepare_cli_fixture()
        output = self.base / "result"
        result = self.cli(output)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("package validation passed\n", result.stdout)
        self.assertEqual("", result.stderr)
        self.assertEqual(["report.json"], [p.name for p in output.iterdir()])
        report = output / "report.json"
        self.assertEqual(self.validate(), json.loads(report.read_bytes()))
        original = report.read_bytes()
        self.assert_cli_failure(self.cli(output))
        self.assertEqual(original, report.read_bytes())

    def test_cli_refuses_existing_files_directories_and_all_output_links(self):
        self.prepare_cli_fixture()
        output = self.base / "result"
        output.mkdir()
        self.assert_cli_failure(self.cli(output))
        self.assertEqual([], list(output.iterdir()))
        output.rmdir()
        output.write_bytes(b"do-not-overwrite")
        self.assert_cli_failure(self.cli(output))
        self.assertEqual(b"do-not-overwrite", output.read_bytes())
        output.unlink()
        for target in (self.root, self.base / "nonexistent"):
            output.symlink_to(target, target_is_directory=True)
            self.assert_cli_failure(self.cli(output))
            self.assertTrue(output.is_symlink())
            output.unlink()
        alias = self.base / "alias"
        alias.symlink_to(self.base, target_is_directory=True)
        self.assert_cli_failure(self.cli(alias / "result"))
        self.assertFalse(output.exists())
        self.assert_cli_failure(self.cli(self.root / "result"))
        self.assertFalse((self.root / "result").exists())

    def test_cli_failures_redact_paths_json_and_tracebacks(self):
        output = self.base / "private-output"
        self.assert_cli_failure(self.cli(output))
        self.assertFalse(output.exists())
        self.prepare_cli_fixture()
        (self.root / "config.json").write_bytes(b'{"private-prompt":')
        self.assert_cli_failure(self.cli(output))
        self.assertFalse(output.exists())
        self.assert_cli_failure(self.cli(output, "--unknown-private-argument"))
        result = subprocess.run([sys.executable, "-B", str(self.cli_tool)],
                                capture_output=True, text=True, check=False)
        self.assert_cli_failure(result)

    def test_import_has_no_cli_or_production_integration_side_effect(self):
        code = (
            "import importlib.util, sys; "
            "s=importlib.util.spec_from_file_location('isolated',sys.argv[1]); "
            "m=importlib.util.module_from_spec(s); s.loader.exec_module(m); "
            "assert not any('model_package' in n for n in sys.modules)"
        )
        result = subprocess.run([sys.executable, "-B", "-c", code, str(TOOL)],
                                cwd=self.base, capture_output=True, text=True, check=False)
        self.assertEqual((0, "", ""), (result.returncode, result.stdout, result.stderr))


if __name__ == "__main__":
    unittest.main()
