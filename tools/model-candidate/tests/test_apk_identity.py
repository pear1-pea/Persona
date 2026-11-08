import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock

DIRECTORY = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(DIRECTORY))
from apk_identity import find_aapt2, verify_apk_identities

SPEC = importlib.util.spec_from_file_location("identity_driver", DIRECTORY / "run_probe.py")
driver = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(driver)


class ApkIdentityTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=Path(tempfile.gettempdir()).resolve())
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.sdk = self.base / "SDK with spaces"
        self.adb = self.sdk / "platform-tools" / "adb"
        self.adb.parent.mkdir(parents=True)
        self.adb.write_bytes(b"synthetic-adb-not-executed")
        self.adb.chmod(0o755)
        self.aapt2 = self.make_tool("36.0.0")
        self.repo = self.base / "repo"
        for folder, package in (("debug", driver.TARGET), ("androidTest/debug", driver.TEST)):
            directory = self.repo / "app/build/outputs/apk" / folder
            directory.mkdir(parents=True)
            (directory / "app-debug.apk").write_bytes(b"synthetic-apk-" + package.encode())
            (directory / "output-metadata.json").write_text(json.dumps({
                "applicationId": package, "elements": [{"outputFile": "app-debug.apk"}]}))
        self.apks = driver.checked_apks(self.repo)
        self.output = self.base / "output"
        self.output.mkdir()
        args = type("Args", (), {"adb": str(self.adb), "serial": "synthetic"})()
        self.host = driver.Host(args, self.output, time.monotonic() + 30)

    def make_tool(self, version, name="aapt2"):
        path = self.sdk / "build-tools" / version / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b"synthetic-aapt2-not-executed")
        path.chmod(0o755)
        return path

    def process(self, stdout, code=0, stderr=b""):
        process = mock.Mock()
        process.communicate.return_value = (stdout, stderr)
        process.returncode = code
        return process

    def verify(self):
        return verify_apk_identities(self.host, self.apks, driver.read_file)

    def test_both_manifest_ids_are_checked_and_bound_to_real_apk_hashes(self):
        with mock.patch.object(subprocess, "Popen", side_effect=[
            self.process(driver.TARGET.encode() + b"\n"),
            self.process(driver.TEST.encode() + b"\r\n"),
        ]) as external:
            verified = self.verify()
        self.assertEqual({driver.TARGET, driver.TEST}, set(verified))
        for index, (package, (path, digest)) in enumerate(verified.items()):
            self.assertEqual(self.apks[package], path)
            self.assertEqual(driver.read_file(path)[0], digest)
            args, options = external.call_args_list[index]
            self.assertEqual([str(self.aapt2), "dump", "packagename", str(path)], args[0])
            self.assertIs(False, options["shell"])
        self.assertEqual(2, external.call_count)

    def test_real_manifest_original_app_is_rejected_despite_correct_sidecar(self):
        self.assertEqual(driver.TARGET, json.loads(
            (self.apks[driver.TARGET].parent / "output-metadata.json").read_bytes())["applicationId"])
        with mock.patch.object(subprocess, "Popen", return_value=self.process(b"com.example.persona\n")) as external:
            with self.assertRaises(ValueError):
                self.verify()
            self.assertEqual(1, external.call_count)
            self.assertEqual("packagename", external.call_args.args[0][2])

    def test_test_apk_wrong_id_is_rejected_after_target_passes(self):
        with mock.patch.object(subprocess, "Popen", side_effect=[
            self.process(driver.TARGET.encode()), self.process(driver.TARGET.encode()),
        ]) as external:
            with self.assertRaises(ValueError):
                self.verify()
            self.assertEqual(2, external.call_count)

    def test_duplicate_blank_diagnostic_and_non_ascii_outputs_are_rejected(self):
        package = driver.TARGET.encode()
        for stdout in (b"", package + b"\n" + package + b"\n", b"\n" + package,
                       package + b"\n\n", b"warning\n" + package, package + b" ", b"\xff"):
            with self.subTest(stdout=stdout), mock.patch.object(
                subprocess, "Popen", return_value=self.process(stdout)
            ):
                with self.assertRaises(ValueError):
                    self.verify()

    def test_failed_tool_and_stderr_do_not_establish_identity(self):
        for code, stderr in ((1, b"private-error-path"), (0, b"private-diagnostic")):
            with self.subTest(code=code), mock.patch.object(subprocess, "Popen", return_value=self.process(
                driver.TARGET.encode() + b"\n", code, stderr
            )):
                with self.assertRaises(ValueError):
                    self.verify()

    def test_no_tool_fails_closed_before_any_command(self):
        self.aapt2.unlink()
        with mock.patch.object(subprocess, "Popen") as external:
            with self.assertRaises(FileNotFoundError):
                self.verify()
            external.assert_not_called()

    def test_existing_numeric_versions_are_selected_deterministically(self):
        self.make_tool("9.0.0")
        newest = self.make_tool("36.0.10")
        self.make_tool("36.0.2")
        self.make_tool("37.0.0-rc1")
        self.make_tool("untrusted-name")
        self.assertEqual(newest, find_aapt2(self.adb, driver.read_file, self.host.deadline))

    def test_windows_executable_names_are_discovered_without_running_them(self):
        adb = self.adb.with_name("adb.exe")
        self.adb.rename(adb)
        tool = self.make_tool("36.0.0", "aapt2.exe")
        self.assertEqual(tool, find_aapt2(adb, driver.read_file, self.host.deadline))

    def test_linked_tool_and_parent_are_rejected(self):
        outside = self.base / "outside-aapt2"
        self.aapt2.rename(outside)
        self.aapt2.symlink_to(outside)
        with self.assertRaises(ValueError):
            find_aapt2(self.adb, driver.read_file, self.host.deadline)
        self.aapt2.unlink()
        outside.rename(self.aapt2)
        version = self.aapt2.parent
        destination = self.base / "outside-version"
        version.rename(destination)
        version.symlink_to(destination, target_is_directory=True)
        with self.assertRaises(ValueError):
            find_aapt2(self.adb, driver.read_file, self.host.deadline)

    def test_apk_changed_during_dump_is_rejected(self):
        def changed(command, **_options):
            Path(command[-1]).write_bytes(b"replaced-during-manifest-check")
            return self.process(driver.TARGET.encode() + b"\n")
        with mock.patch.object(subprocess, "Popen", side_effect=changed):
            with self.assertRaises(ValueError):
                self.verify()

    def test_unexpected_package_map_is_rejected_without_commands(self):
        self.apks = {"com.example.persona": self.apks[driver.TARGET]}
        with mock.patch.object(subprocess, "Popen") as external:
            with self.assertRaises(ValueError):
                self.verify()
            external.assert_not_called()


if __name__ == "__main__":
    unittest.main()
