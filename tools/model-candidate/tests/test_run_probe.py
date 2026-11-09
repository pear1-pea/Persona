import argparse
import contextlib
import copy
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path, PureWindowsPath
import signal
import subprocess
import sys
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest import mock
import zipfile

TOOL = Path(__file__).resolve().parents[1] / "run_probe.py"
SPEC = importlib.util.spec_from_file_location("qwen3_host_driver", TOOL)
driver = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(driver)
MODEL = "c1899de289a04d12100db370d81485cdf75e47ca"
RUNTIME = "d407447ed56c4121a11ccbd266dc184ca1ead0c2"
NAMES = ("config.json", "context.json", "embeddings_bf16.bin", "export_args.json", "LICENSE",
         "llm.mnn", "llm.mnn.json", "llm.mnn.weight", "llm_config.json", "README.md", "tokenizer.mtok")


class RunProbeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=Path(tempfile.gettempdir()).resolve())
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.repo = self.base / "repo"
        for name in ("app/src/main", "app/src/test", "app/src/androidTest", "gradle"):
            (self.repo / name).mkdir(parents=True, exist_ok=True)
        for name in ("app/build.gradle.kts", "build.gradle.kts", "gradle/libs.versions.toml",
                     "settings.gradle.kts", "gradle.properties", "app/src/main/Native.kt"):
            (self.repo / name).write_text("synthetic source\n")
        self.package = self.base / "package"
        self.package.mkdir()
        for name in NAMES:
            (self.package / name).write_bytes(name.encode())
        self.args = argparse.Namespace(adb="never-real-adb", serial="synthetic-serial", run_id="Probe_01",
            bundle=self.base / "bundle.json", receipt=self.base / "receipt.json", package=self.package,
            output=self.base / "output", timeout=1800)
        self.refresh_receipt()
        self.bundle = {"formatVersion": 1, "kind": "qwen3-native-probe-v1",
            "sourceRevision": "sha256:" + driver.snapshot_digest(driver.source_snapshot(self.repo)),
            "packageReceiptSha256": self.lock["packageReceiptSha256"]}
        self.save_bundle()

    def refresh_receipt(self):
        self.receipt = {"model_revision": MODEL, "mnn_revision": RUNTIME, "quant_bits": [8, 8, 16],
            "files": [{"path": p.name, "size": p.stat().st_size,
                       "sha256": hashlib.sha256(p.read_bytes()).hexdigest()}
                      for p in sorted(self.package.iterdir())]}
        self.save_receipt()

    def save_receipt(self):
        self.args.receipt.write_text(json.dumps(self.receipt))
        self.repin_synthetic_receipt()

    def repin_synthetic_receipt(self):
        self.lock = {"modelRepo": "Qwen/Qwen3-0.6B", "modelRevision": MODEL, "runtimeRevision": RUNTIME,
                     "packageReceiptSha256": hashlib.sha256(self.args.receipt.read_bytes()).hexdigest()}
        if hasattr(self, "bundle"):
            self.bundle["packageReceiptSha256"] = self.lock["packageReceiptSha256"]
            self.save_bundle()

    def save_bundle(self):
        self.args.bundle.write_text(json.dumps(self.bundle))

    def preflight(self):
        return driver.preflight(self.args, self.repo, source_lock=self.lock)

    def reject(self):
        with self.assertRaises((ValueError, OSError, TypeError)):
            self.preflight()

    def test_valid_fixed_inventory_and_source_binding(self):
        binding = self.preflight()
        self.assertEqual(11, len(binding["files"]))
        self.assertEqual(self.bundle["sourceRevision"], binding["sourceRevision"])
        self.assertEqual(hashlib.sha256(self.args.bundle.read_bytes()).hexdigest(), binding["bundleSha256"])
        self.assertEqual(self.lock["packageReceiptSha256"], binding["receiptSha256"])
        self.assertEqual(driver.source_snapshot(self.repo), binding["snapshot"])

    def test_default_pin_rejects_self_attested_receipt_and_changed_bytes(self):
        with self.assertRaises(ValueError):
            driver.preflight(self.args, self.repo)
        self.args.receipt.write_bytes(self.args.receipt.read_bytes() + b"\n")
        self.reject()
        (self.package / "llm.mnn").write_bytes(b"attacker-replacement")
        self.refresh_receipt()
        with self.assertRaises(ValueError):
            driver.preflight(self.args, self.repo)

    def test_source_and_bundle_revision_must_match(self):
        path = self.repo / "app/src/main/Native.kt"
        path.write_text("changed source")
        self.reject()
        self.bundle["sourceRevision"] = "sha256:" + driver.snapshot_digest(driver.source_snapshot(self.repo))
        self.save_bundle()
        self.preflight()
        for key, value in (("sourceRevision", "wrong"), ("packageReceiptSha256", "0" * 64),
                           ("kind", "other"), ("formatVersion", True)):
            old = self.bundle[key]
            self.bundle[key] = value
            self.save_bundle()
            self.reject()
            self.bundle[key] = old
        self.save_bundle()

    def test_identity_quantization_and_duplicate_json_keys(self):
        original = copy.deepcopy(self.receipt)
        for key, value in (("model_revision", "wrong"), ("mnn_revision", "wrong"),
                           ("quant_bits", [8.0, 8, 16]), ("quant_bits", [4, 8, 16])):
            self.receipt = dict(original, **{key: value})
            self.save_receipt()
            self.reject()
        self.receipt = original
        self.save_receipt()
        raw = self.args.receipt.read_bytes()
        self.args.receipt.write_bytes(b'{"model_revision":"wrong",' + raw[1:])
        self.repin_synthetic_receipt()
        self.reject()
        self.save_receipt()
        raw = self.args.bundle.read_bytes()
        self.args.bundle.write_bytes(b'{"sourceRevision":"wrong",' + raw[1:])
        self.reject()

    def test_exact_eleven_files_no_duplicates_extras_or_missing(self):
        original = copy.deepcopy(self.receipt)
        for rows in (original["files"][:-1], original["files"] + [original["files"][0]],
                     original["files"][:-1] + [original["files"][0]]):
            self.receipt = dict(original, files=rows)
            self.save_receipt()
            self.reject()
        self.receipt = original
        self.save_receipt()
        extra = self.package / "extra.bin"
        extra.write_bytes(b"unlisted")
        self.reject()
        extra.unlink()
        missing = self.package / "llm.mnn.json"
        data = missing.read_bytes()
        missing.unlink()
        self.reject()
        missing.write_bytes(data)
        (self.package / "extra-directory").mkdir()
        self.reject()

    def test_receipt_paths_and_integer_limits(self):
        original = copy.deepcopy(self.receipt)
        for value in ("../llm.mnn", "/llm.mnn", "C:/llm.mnn", "C:llm.mnn", "a\\llm.mnn",
                      "./llm.mnn", "llm.mnn:stream", "llm.mnn/", "LLM.MNN", None):
            self.receipt = copy.deepcopy(original)
            self.receipt["files"][0]["path"] = value
            self.save_receipt()
            self.reject()
        for value in (-1, 0, True, 1.0, "1", 2147483649, 9223372036854775808):
            self.receipt = copy.deepcopy(original)
            self.receipt["files"][0]["size"] = value
            self.save_receipt()
            self.reject()
        self.receipt = copy.deepcopy(original)
        for item in self.receipt["files"][:3]:
            item["size"] = 2147483648
        self.save_receipt()
        self.reject()

    def test_size_and_hash_tampering_and_hardlink_alias(self):
        graph = self.package / "llm.mnn"
        original = graph.read_bytes()
        graph.write_bytes(b"X" * len(original))
        self.reject()
        graph.write_bytes(original + b"extra")
        self.reject()
        graph.write_bytes(original)
        weight = self.package / "llm.mnn.weight"
        weight.unlink()
        os.link(graph, weight)
        self.refresh_receipt()
        self.reject()

    def test_symlinks_in_files_root_and_parents(self):
        graph = self.package / "llm.mnn"
        outside = self.base / "graph"
        graph.rename(outside)
        for target in (outside, self.base / "missing"):
            graph.symlink_to(target)
            self.reject()
            graph.unlink()
        outside.rename(graph)
        alias = self.base / "alias"
        alias.symlink_to(self.base, target_is_directory=True)
        original = self.args.package
        self.args.package = alias / "package"
        self.reject()
        self.args.package = original
        self.args.receipt = alias / "receipt.json"
        self.reject()

    def test_native_windows_path_syntax_is_supported_without_traversal_or_ads(self):
        self.assertEqual(PureWindowsPath(r"C:\Model Packages\Qwen3"),
                         driver.lexical(r"C:\Model Packages\Qwen3", windows=True))
        self.assertEqual(PureWindowsPath(r"\\host\share\Models"),
                         driver.lexical(r"\\host\share\Models", windows=True))
        for value in (r"C:\safe\..\escape", r"C:relative", r"C:\file:stream", r"C:\NUL.txt",
                      "C:\\bad.\\file", "C:\\bad \\file", "C:\\bad?\\file"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                driver.lexical(value, windows=True)

    def test_windows_identity_uses_birthtime_instead_of_deprecated_ctime(self):
        fields = dict(st_dev=1, st_ino=2, st_size=3, st_mtime_ns=4, st_birthtime_ns=5)
        path = SimpleNamespace(**fields, st_ctime_ns=6)
        descriptor = SimpleNamespace(**fields, st_ctime_ns=7)
        self.assertEqual(driver.stamp(path, windows=True), driver.stamp(descriptor, windows=True))
        self.assertNotEqual(driver.stamp(path, windows=False), driver.stamp(descriptor, windows=False))
        for field in fields:
            changed = SimpleNamespace(**(fields | {field: 9}), st_ctime_ns=7)
            with self.subTest(field=field):
                self.assertNotEqual(driver.stamp(path, windows=True), driver.stamp(changed, windows=True))

    def test_owned_adb_startup_retries_initial_socket_timeout(self):
        self.args.output.mkdir()
        host = driver.Host(self.args, self.args.output, time.monotonic() + 30)
        connection = mock.MagicMock()
        connection.__enter__.return_value.makefile.return_value = io.BytesIO(b"OKAY00040029")
        server = mock.Mock()
        server.poll.return_value = None
        with mock.patch.object(host, "command", return_value=SimpleNamespace(
                stdout=b"Android Debug Bridge version 1.0.41\n")), \
                mock.patch.object(driver.socket, "socket") as listener, \
                mock.patch.object(driver.socket, "create_connection", side_effect=[TimeoutError(), connection]), \
                mock.patch.object(driver.subprocess, "Popen", return_value=server) as launch, \
                mock.patch.object(driver.time, "sleep"):
            listener.return_value.__enter__.return_value.connect_ex.return_value = 1
            host.start_server()
        self.assertIs(host.server, server)
        self.assertEqual([self.args.adb, "-L", "tcp:5037", "nodaemon", "server"], launch.call_args.args[0])

    def test_read_detects_real_file_change_during_hashing(self):
        path = self.package / "llm.mnn"
        real_factory = hashlib.sha256
        class MutatingDigest:
            def __init__(self):
                self.inner = real_factory()
            def update(self, block):
                self.inner.update(block)
                path.write_bytes(b"changed-during-read")
            def hexdigest(self):
                return self.inner.hexdigest()
        with mock.patch.object(driver.hashlib, "sha256", MutatingDigest):
            with self.assertRaises(ValueError):
                driver.read_file(path)

    def test_output_new_directory_refuses_existing_and_linked_destinations(self):
        output = driver.new_output(self.args)
        self.assertTrue(output.is_dir())
        sentinel = output / "retain.txt"
        sentinel.write_bytes(b"keep")
        with self.assertRaises(ValueError):
            driver.new_output(self.args)
        self.assertEqual(b"keep", sentinel.read_bytes())
        self.args.output = self.base / "output-file"
        self.args.output.write_bytes(b"keep")
        with self.assertRaises(ValueError):
            driver.new_output(self.args)
        for target in (output, self.base / "missing"):
            self.args.output = self.base / "alias-output"
            self.args.output.symlink_to(target, target_is_directory=True)
            with self.assertRaises(ValueError):
                driver.new_output(self.args)
            self.args.output.unlink()
        alias = self.base / "alias-parent"
        alias.symlink_to(self.base, target_is_directory=True)
        self.args.output = alias / "new"
        with self.assertRaises(ValueError):
            driver.new_output(self.args)
        self.args.output = self.package / "result"
        with self.assertRaises(ValueError):
            driver.new_output(self.args)

    def test_apk_metadata_id_and_filename_are_verified_before_reuse(self):
        for folder, package in (("debug", driver.TARGET), ("androidTest/debug", driver.TEST)):
            directory = self.repo / "app/build/outputs/apk" / folder
            directory.mkdir(parents=True)
            (directory / "app-debug.apk").write_bytes(b"synthetic-apk")
            (directory / "output-metadata.json").write_text(json.dumps({
                "applicationId": package, "elements": [{"outputFile": "app-debug.apk"}]}))
        apks = driver.checked_apks(self.repo)
        self.assertEqual({driver.TARGET, driver.TEST}, set(apks))
        metadata = self.repo / "app/build/outputs/apk/debug/output-metadata.json"
        for package, name in (("com.example.persona", "app-debug.apk"),
                              (driver.TARGET, "../outside.apk"), (driver.TARGET, "C:other.apk")):
            metadata.write_text(json.dumps({"applicationId": package, "elements": [{"outputFile": name}]}))
            with self.assertRaises(ValueError):
                driver.checked_apks(self.repo)

    def test_engineering_pass_and_quality_failure_are_independent(self):
        binding = self.preflight()
        observation = {"run_id": self.args.run_id, "bundle_sha256": binding["bundleSha256"],
            "source_revision": binding["sourceRevision"], "package_receipt_sha256": binding["receiptSha256"],
            "engineering_status": "passed", "quality_status": "not_passed_fixed_six"}
        report = driver.observations(json.dumps(observation).encode(), binding, self.args.run_id)
        result = subprocess.CompletedProcess([], 0, b"OK (1 test)\n", b"")
        self.assertTrue(driver.engineering_passed(result, report))
        self.assertEqual("not_passed_fixed_six", report["quality_status"])
        for code, output in ((1, b"OK (1 test)"), (0, b"OK (2 tests)"), (0, b"FAILURES!!!")):
            self.assertFalse(driver.engineering_passed(subprocess.CompletedProcess([], code, output), report))
        for key in ("run_id", "bundle_sha256", "source_revision", "package_receipt_sha256"):
            with self.subTest(key=key), self.assertRaises(ValueError):
                driver.observations(json.dumps(dict(observation, **{key: "other"})).encode(), binding, self.args.run_id)

    def test_failed_preflight_retains_output_and_never_reaches_adb(self):
        self.bundle["sourceRevision"] = "wrong"
        self.save_bundle()
        stderr = io.StringIO()
        with mock.patch.object(driver.Host, "start_server") as server, contextlib.redirect_stderr(stderr):
            self.assertEqual(1, driver.run(self.args, self.repo, _source_lock=self.lock))
            server.assert_not_called()
        self.assertEqual("ValueError\n", stderr.getvalue())
        output = self.args.output
        self.assertTrue((output / "privateinstrumentation.log").exists())
        self.assertTrue((output / "observations.json").exists())
        report = json.loads((output / "summary.json").read_bytes())
        self.assertFalse(report["candidateAdopted"])
        self.assertEqual("failed", report["engineering_status"])
        self.assertFalse(report["observations_available"])
        original = (output / "summary.json").read_bytes()
        with self.assertRaises(ValueError):
            driver.run(self.args, self.repo, _source_lock=self.lock)
        self.assertEqual(original, (output / "summary.json").read_bytes())

    def test_run_id_serial_and_timeout_bounds(self):
        for key, value in (("run_id", "../bad"), ("run_id", "a" * 65),
                           ("serial", "-option"), ("serial", "phone;echo private")):
            old = getattr(self.args, key)
            setattr(self.args, key, value)
            self.reject()
            setattr(self.args, key, old)
        for value in (0, -1, 3601, True):
            self.args.timeout = value
            with self.assertRaises(ValueError):
                driver.run(self.args, self.repo)
        self.assertFalse(self.args.output.exists())

    def test_subprocess_timeout_preserves_partial_private_evidence(self):
        output = driver.new_output(self.args)
        host = driver.Host(self.args, output, time.monotonic() + 5)
        command = [sys.executable, "-u", "-c", "import time; print('private-model-raw'); time.sleep(10)"]
        with self.assertRaises(TimeoutError):
            host.command(command, "privateinstrumentation.log", timeout=0.3)
        self.assertIn(b"private-model-raw", (output / "privateinstrumentation.log").read_bytes())
        with self.assertRaises(TimeoutError):
            driver.read_file(self.args.bundle, deadline=time.monotonic() - 1)

    def test_keyboard_interrupt_reaps_command_and_preserves_partial_output(self):
        output = driver.new_output(self.args)
        host = driver.Host(self.args, output, time.monotonic() + 10)
        ready = self.base / "command-ready"
        command = [sys.executable, "-u", "-c",
                   "import pathlib, subprocess, sys, time; "
                   "subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(30)']); "
                   f"print('private-started'); pathlib.Path({str(ready)!r}).touch(); time.sleep(30)"]
        children = []
        popen = subprocess.Popen

        def launch(*args, **kwargs):
            child = popen(*args, **kwargs)
            if args[0] != command:
                return child
            children.append(child)
            communicate = child.communicate
            first = True

            def interrupted(*args, **kwargs):
                nonlocal first
                if first:
                    first = False
                    deadline = time.monotonic() + 5
                    while not ready.exists() and time.monotonic() < deadline:
                        time.sleep(0.01)
                    self.assertTrue(ready.exists(), "Synthetic command never became ready")
                    try:
                        communicate(timeout=0.05)
                    except subprocess.TimeoutExpired:
                        raise KeyboardInterrupt() from None
                    self.fail("Synthetic command ended before interruption")
                return communicate(*args, **kwargs)

            child.communicate = interrupted
            return child

        try:
            with mock.patch.object(driver.subprocess, "Popen", side_effect=launch):
                with self.assertRaises(KeyboardInterrupt):
                    host.command(command, "private-interrupted.log")
            self.assertIsNotNone(children[0].poll(), "Interrupted command is still running")
            self.assertIn(b"private-started", (output / "private-interrupted.log").read_bytes())
        finally:
            for child in children:
                if os.name == "nt":
                    subprocess.run(["taskkill", "/PID", str(child.pid), "/T", "/F"],
                                   capture_output=True, timeout=5, check=False)
                    if child.poll() is None:
                        child.kill()
                else:
                    try:
                        os.killpg(child.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                child.communicate(timeout=5)

    def test_windows_interrupt_kills_owned_process_tree_before_collecting_log(self):
        output = driver.new_output(self.args)
        host = driver.Host(self.args, output, time.monotonic() + 10)
        child = mock.Mock(pid=123)
        child.communicate.side_effect = [KeyboardInterrupt(), (b"partial out", b"partial err")]
        child.poll.return_value = 1
        with mock.patch.object(driver.os, "name", "nt"), \
                mock.patch.object(driver.subprocess, "CREATE_NEW_PROCESS_GROUP", 512, create=True), \
                mock.patch.object(driver.subprocess, "Popen", return_value=child) as popen, \
                mock.patch.object(driver.subprocess, "run") as kill:
            with self.assertRaises(KeyboardInterrupt):
                host.command(["synthetic-build"], "private-interrupted.log")
        self.assertEqual(512, popen.call_args.kwargs["creationflags"])
        kill.assert_called_once_with(["taskkill", "/PID", "123", "/T", "/F"],
                                     capture_output=True, timeout=5, check=False)
        self.assertEqual(b"partial outpartial err", (output / "private-interrupted.log").read_bytes())

    def test_gradle_arguments_are_a_list_with_intact_property(self):
        command = driver.build_command(self.repo)
        self.assertIsInstance(command, list)
        self.assertEqual(1, command.count("-Ppersona.isolatedValidation=true"))
        for task in ("testDebugUnitTest", "assembleDebug", "assembleDebugAndroidTest"):
            self.assertIn(task, command)

    def test_native_library_receipt_hashes_zip_member_without_extracting(self):
        apk = self.base / "synthetic.apk"
        native = b"synthetic-native-library\x00\xff"
        name = "lib/arm64-v8a/libpersona_mnn.so"
        with zipfile.ZipFile(apk, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr(name, native)
        receipt = driver.native_receipt(apk)
        self.assertEqual({"path": name, "size": len(native), "sha256": hashlib.sha256(native).hexdigest()},
                         receipt["native_library"])
        self.assertEqual(hashlib.sha256(apk.read_bytes()).hexdigest(), receipt["apk_sha256"])
        self.assertEqual(RUNTIME, receipt["runtimeRevision"])
        self.assertEqual("declared_target_only", receipt["runtime_revision_evidence"])
        self.assertFalse(receipt["runtime_source_verified"])
        self.assertFalse(receipt["candidateAdopted"])
        self.assertEqual([], list(self.base.rglob("*.so")))
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("lib/other.so", native)
        with self.assertRaises(ValueError):
            driver.native_receipt(apk)

    def test_simulated_windows_reparse_attribute_rejects_parent(self):
        original = driver.Path.lstat
        def reparse_info(path, *args, **kwargs):
            info = original(path, *args, **kwargs)
            return (SimpleNamespace(st_mode=info.st_mode, st_file_attributes=0x400)
                    if path == self.package else info)
        with mock.patch.object(driver.Path, "lstat", reparse_info):
            with self.assertRaises(ValueError):
                driver.safe(self.package / "llm.mnn")

    def test_help_and_argument_errors_have_no_device_side_effects(self):
        result = subprocess.run([sys.executable, "-B", str(TOOL), "--help"], capture_output=True, check=False)
        self.assertEqual(0, result.returncode)
        self.assertIn(b"--serial", result.stdout)
        self.assertNotIn(b"--source-lock", result.stdout)
        self.assertEqual(b"", result.stderr)
        result = subprocess.run([sys.executable, "-B", str(TOOL), "--private-unknown"], capture_output=True, check=False)
        self.assertEqual((1, b"", b"ValueError\n"), (result.returncode, result.stdout, result.stderr))


if __name__ == "__main__":
    unittest.main()
