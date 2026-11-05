from argparse import Namespace
from contextlib import redirect_stdout
from io import StringIO
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import run_device as runner


class DeviceRunnerTests(unittest.TestCase):
    # These fabricated subprocess responses test orchestration, never device conformance.
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.args = Namespace(run_id="test-run", serial="fixture-device", adb="fake-adb", split="dev")
        self.bundle = {"source_snapshot": {"app/src/main/X.kt": "a" * 64},
                       "implementation_revision": "sha256:" + "b" * 64,
                       "dataset_sha256": "c" * 64, "split": "dev"}
        self.commands = []
        self.existing, self.fail_test_install, self.bad_export = False, False, False
        self.adb_startup, self.fail_stop, self.fail_uninstall = False, False, False
        self.install_log_error = False
        self.fail_instrumentation = False
        self.real_execute = runner.execute
        for folder, package in (("debug", runner.TARGET), ("androidTest/debug", runner.TEST)):
            path = self.root / "app/build/outputs/apk" / folder
            path.mkdir(parents=True)
            (path / "fixture.apk").write_bytes(package.encode())
            (path / "output-metadata.json").write_text(json.dumps({
                "applicationId": package, "elements": [{"outputFile": "fixture.apk"}]}))

    def fake_execute(self, command, directory, name, **kwargs):
        command = list(map(str, command))
        self.commands.append(command)
        if "install" in command and self.install_log_error:
            result = subprocess.CompletedProcess(command, 0, b"Success\n")
            with patch.object(runner.subprocess, "run", return_value=result):
                return self.real_execute(command, directory, name, **kwargs)
        output, code = b"", 0
        if command[-1] == "get-state":
            output = b"* daemon started successfully\ndevice\n" if self.adb_startup else b"device\n"
        elif "path" in command:
            output = b"package:/fixture/existing.apk\n" if self.existing else b""
        elif "install" in command:
            if self.fail_test_install and "androidTest" in command[-1]:
                raise ValueError("Synthetic install failure")
            output = b"Success\n"
        elif "uninstall" in command:
            if self.fail_uninstall:
                raise ValueError("Synthetic uninstall failure")
            output = b"Success\n"
        elif "force-stop" in command and self.fail_stop:
            raise subprocess.TimeoutExpired(command, 30)
        elif "instrument" in command:
            output = b"FAILURES!!!\n" if self.fail_instrumentation else b"OK (1 test)\n"
        elif "exec-out" in command:
            apk_sha = {package: runner.digest(path) for package, path in runner.isolated_apks(self.root).items()}
            report = {"execution_status": "completed", "evidence_kind": "application_instrumentation",
                      "adapter_scope": "room_and_request_lifecycle" if getattr(self.args, "with_lifecycle", False)
                      else "room_repository_prompt", "llm_quality": "not_run",
                      "input_bundle_sha256": runner.digest(directory / "bundle.json"),
                      "implementation_revision": self.bundle["implementation_revision"],
                      "dataset_sha256": self.bundle["dataset_sha256"], "split": "dev",
                      "target_apk_sha256": apk_sha[runner.TARGET], "test_apk_sha256": apk_sha[runner.TEST]}
            if self.bad_export:
                report["test_apk_sha256"] = "0" * 64
            if getattr(self.args, "controlled_network", False):
                report["network_state_mode"] = "controlled_fallback_with_metered_denial"
                report["metered_denial_control"] = {"status": "passed"}
            output = json.dumps(report).encode()
        elif "compare" in command:
            (directory / "comparison.json").write_text(json.dumps({"counts": {"unsupported": 18}}))
            code = 1
        result = subprocess.CompletedProcess(command, code, output)
        if kwargs.get("on_result"):
            kwargs["on_result"](result)
        (directory / name).write_bytes(output)
        return result

    def invoke(self, snapshot=None):
        snapshot = self.bundle["source_snapshot"] if snapshot is None else snapshot
        with patch.object(runner, "build_bundle", return_value=self.bundle), \
                patch.object(runner, "source_snapshot", return_value=snapshot), \
                patch.object(runner, "execute", side_effect=self.fake_execute), redirect_stdout(StringIO()):
            code = runner.run(self.args, self.root)
        report = json.loads((self.root / "build/verification/m5-room-test-run/summary.json").read_text())
        return code, report

    def test_existing_validation_package_blocks_build_and_install(self):
        self.existing = True
        code, report = self.invoke()
        self.assertEqual(code, 2)
        self.assertEqual(report["build"], "not_run")
        self.assertFalse(any("install" in command or "uninstall" in command for command in self.commands))

    def test_lifecycle_mode_is_explicitly_enabled_and_receipt_scope_is_checked(self):
        self.args.with_lifecycle = True
        code, report = self.invoke()
        self.assertEqual(code, 1)
        command = next(c for c in self.commands if "instrument" in c)
        self.assertIn("runM5Lifecycle", command)
        self.assertEqual(report["adapter_scope"], "room_and_request_lifecycle")

    def test_controlled_network_is_opt_in_and_recorded_as_synthetic_policy_input(self):
        self.args.with_lifecycle = True
        self.args.controlled_network = True
        code, report = self.invoke()
        self.assertEqual(code, 1)
        command = next(c for c in self.commands if "instrument" in c)
        self.assertIn("runM5ControlledNetwork", command)
        self.assertEqual(report["network_state_mode"], "controlled_fallback_with_metered_denial")

    def test_source_drift_blocks_install(self):
        code, report = self.invoke({"app/src/main/X.kt": "d" * 64})
        self.assertEqual(code, 2)
        self.assertEqual(report["build"], "passed")
        self.assertFalse(any("install" in command for command in self.commands))

    def test_original_application_id_is_never_installed(self):
        path = self.root / "app/build/outputs/apk/debug/output-metadata.json"
        metadata = json.loads(path.read_text())
        metadata["applicationId"] = "com.example.persona"
        path.write_text(json.dumps(metadata))
        code, report = self.invoke()
        self.assertEqual(code, 2)
        self.assertEqual(report["owned_packages"], [])
        self.assertFalse(any("install" in command for command in self.commands))

    def test_unsupported_result_is_incomplete_and_only_owned_packages_are_cleaned(self):
        code, report = self.invoke()
        self.assertEqual(code, 1)
        self.assertEqual(report["comparison"], "incomplete")
        self.assertFalse(report["model_execution"])
        self.assertEqual(report["llm_quality"], "not_run")
        removed = [command[-1] for command in self.commands if "uninstall" in command]
        self.assertEqual(removed, [runner.TEST, runner.TARGET])
        self.assertNotIn([self.args.adb, "kill-server"], self.commands)
        self.assertFalse(any(command[-1] == "com.example.persona" for command in self.commands))

    def test_export_apk_mismatch_is_rejected_before_comparison(self):
        self.bad_export = True
        code, report = self.invoke()
        self.assertEqual(code, 2)
        self.assertEqual(report["comparison"], "not_run")
        self.assertEqual(len([c for c in self.commands if "uninstall" in c]), 2)
        self.assertFalse(report["device_apk_hashes_verified"])
        self.assertNotEqual(report["source_binding"], "snapshot_unchanged_across_build_and_device_apk_hashes_checked")

    def test_adb_startup_messages_do_not_hide_authorized_state(self):
        self.adb_startup = True
        code, report = self.invoke()
        self.assertEqual(code, 1)
        self.assertEqual(report["adapter_execution"], "completed")

    def test_force_stop_timeout_does_not_skip_owned_package_uninstall(self):
        self.fail_stop = True
        code, report = self.invoke()
        self.assertEqual(code, 2)
        self.assertEqual([c[-1] for c in self.commands if "uninstall" in c], [runner.TEST, runner.TARGET])
        self.assertTrue(report["cleanup_errors"])

    def test_uninstall_failure_overrides_comparison_result(self):
        self.fail_uninstall = True
        code, report = self.invoke()
        self.assertEqual(code, 2)
        self.assertTrue(report["cleanup_errors"])

    def test_install_log_failure_still_cleans_confirmed_installation(self):
        self.install_log_error = True
        original_write = Path.write_bytes

        def fail_install_log(path, data):
            if path.name == "install-0.log":
                raise OSError("Synthetic log write failure")
            return original_write(path, data)

        with patch.object(Path, "write_bytes", fail_install_log):
            code, report = self.invoke()
        self.assertEqual(code, 2)
        self.assertEqual(report["owned_packages"], [runner.TARGET])
        self.assertEqual([c[-1] for c in self.commands if "uninstall" in c], [runner.TARGET])

    def test_failed_install_does_not_grant_ownership_of_unknown_package(self):
        self.fail_test_install = True
        code, report = self.invoke()
        self.assertEqual(code, 2)
        self.assertEqual(report["owned_packages"], [runner.TARGET])
        removed = [command[-1] for command in self.commands if "uninstall" in command]
        self.assertEqual(removed, [runner.TARGET])

    def test_failed_instrumentation_exports_diagnostics_before_owned_packages_are_removed(self):
        self.fail_instrumentation = True
        code, report = self.invoke()
        self.assertEqual(code, 2)
        self.assertEqual(report["adapter_execution"], "failed")
        self.assertTrue((self.root / "build/verification/m5-room-test-run/observations.json").is_file())
        export_index = next(i for i, c in enumerate(self.commands) if "exec-out" in c)
        cleanup_index = next(i for i, c in enumerate(self.commands) if "uninstall" in c)
        self.assertLess(export_index, cleanup_index)

    def test_invalid_run_id_and_existing_directory_never_execute_commands(self):
        self.args.run_id = "../original"
        with self.assertRaises(ValueError):
            self.invoke()
        self.assertEqual(self.commands, [])
        self.args.run_id = "test-run"
        destination = self.root / "build/verification/m5-room-test-run"
        destination.mkdir(parents=True)
        marker = destination / "keep.txt"
        marker.write_text("keep")
        with self.assertRaises(FileExistsError):
            self.invoke()
        self.assertEqual(marker.read_text(), "keep")
        self.assertEqual(self.commands, [])


if __name__ == "__main__":
    unittest.main()
