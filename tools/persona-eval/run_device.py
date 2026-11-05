#!/usr/bin/env python3
"""Build and run the isolated Room component adapter; never invoke a model."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import uuid

from prepare_device import REPO_ROOT, build_bundle, source_snapshot
from schema import ROOT, require

TARGET = "com.example.persona.validation"
TEST = TARGET + ".test"


def digest(path):
    with Path(path).open("rb") as stream:
        sha = hashlib.sha256()
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            sha.update(block)
    return sha.hexdigest()


def isolated_apks(repo):
    result = {}
    for folder, package in (("debug", TARGET), ("androidTest/debug", TEST)):
        directory = repo / "app/build/outputs/apk" / folder
        metadata = json.loads((directory / "output-metadata.json").read_text(encoding="utf-8"))
        require(metadata["applicationId"] == package, "Build is not an isolated validation package")
        require(len(metadata["elements"]) == 1, "Split APKs are not supported by this runner")
        name = metadata["elements"][0]["outputFile"]
        require(Path(name).name == name and "\\" not in name, "APK path escapes output directory")
        path = directory / name
        require(path.is_file(), "Built APK is missing")
        result[package] = path
    return result


def validate_export(report, bundle, bundle_sha, apk_sha, scope="room_repository_prompt", controlled_network=False):
    expected = {
        "execution_status": "completed", "evidence_kind": "application_instrumentation",
        "adapter_scope": scope, "llm_quality": "not_run",
        "input_bundle_sha256": bundle_sha,
        "implementation_revision": bundle["implementation_revision"],
        "dataset_sha256": bundle["dataset_sha256"], "split": bundle["split"],
        "target_apk_sha256": apk_sha[TARGET], "test_apk_sha256": apk_sha[TEST]
    }
    require(all(report.get(key) == value for key, value in expected.items()),
            "Device export does not match the source/bundle/APK receipt")
    if controlled_network:
        require(report.get("network_state_mode") == "controlled_fallback_with_metered_denial"
                and report.get("metered_denial_control", {}).get("status") == "passed",
                "Controlled network receipt or metered denial control is missing")


def execute(command, directory, name, *, timeout=180, check=True, on_result=None):
    result = subprocess.run([str(arg) for arg in command], cwd=REPO_ROOT,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
    if on_result is not None:
        on_result(result)
    (directory / name).write_bytes(result.stdout)
    if check:
        require(result.returncode == 0, f"Command failed; inspect {name}")
    return result


def run(args, repo=REPO_ROOT):
    repo = Path(repo)
    require(re.fullmatch(r"[a-z0-9-]{1,40}", args.run_id) is not None, "Invalid run ID")
    require(re.fullmatch(r"[A-Za-z0-9._:-]+", args.serial) is not None, "Invalid device serial")
    controlled_network = getattr(args, "controlled_network", False)
    require(not controlled_network or getattr(args, "with_lifecycle", False),
            "Controlled network mode requires lifecycle evaluation")
    directory = repo / "build/verification" / ("m5-room-" + args.run_id)
    directory.mkdir(parents=True, exist_ok=False)
    owned, cleanup_errors = [], []
    temporary = "/data/local/tmp/persona-m5-" + args.run_id + "-" + uuid.uuid4().hex + ".json"
    temporary_owned = False
    adb = [args.adb, "-s", args.serial]
    with_lifecycle = getattr(args, "with_lifecycle", False)
    scope = "room_and_request_lifecycle" if with_lifecycle else "room_repository_prompt"
    summary = {"kind": "room_component_adapter", "run_id": args.run_id,
               "build": "not_run", "adapter_execution": "not_run", "comparison": "not_run",
               "device_execution": False, "device_apk_hashes_verified": False,
               "model_execution": False, "llm_quality": "not_run", "adapter_scope": scope}
    summary["network_state_mode"] = "controlled_fallback_with_metered_denial" if controlled_network else "physical"
    exit_code = 2
    try:
        state = execute(adb + ["get-state"], directory, "device-state.log")
        require(state.stdout.splitlines() and state.stdout.splitlines()[-1].strip() == b"device",
                "Device must be connected and authorized")
        for index, package in enumerate((TARGET, TEST)):
            existing = execute(adb + ["shell", "pm", "path", package], directory,
                               f"package-preflight-{index}.log", check=False)
            require(not existing.stdout.strip() and existing.returncode in (0, 1),
                    "Validation package already exists or its state is unknown; refusing overwrite")
        bundle = build_bundle(args.split, repo)
        bundle_path = directory / "bundle.json"
        bundle_path.write_text(json.dumps(bundle, ensure_ascii=False, sort_keys=True, indent=2) + "\n",
                               encoding="utf-8")
        bundle_sha = digest(bundle_path)
        build = ([repo / "gradlew.bat"] if os.name == "nt" else ["sh", repo / "gradlew"])
        build += ["-Ppersona.isolatedValidation=true", "testDebugUnitTest", "assembleDebug",
                  "assembleDebugAndroidTest", "--stacktrace", "--console=plain"]
        (directory / "build-command.json").write_text(json.dumps(list(map(str, build))) + "\n")
        execute(build, directory, "build.log", timeout=1800)
        summary["build"] = "passed"
        require(source_snapshot(repo) == bundle["source_snapshot"], "Source changed during build; no APK was installed")
        apks = isolated_apks(repo)
        apk_sha = {package: digest(path) for package, path in apks.items()}
        summary.update(implementation_revision=bundle["implementation_revision"],
                       input_bundle_sha256=bundle_sha, apk_sha256=apk_sha,
                       tool_sha256={name: digest(ROOT / name) for name in
                                    ("run_device.py", "prepare_device.py", "evaluate.py", "schema.py", "oracle.py")},
                       source_binding="snapshot_unchanged_after_build; device_apk_check_pending")
        for index, (package, path) in enumerate(apks.items()):
            def record_install(result, installed_package=package):
                if result.returncode == 0 and b"Success" in result.stdout.splitlines():
                    owned.append(installed_package)

            execute(adb + ["install", "-t", path], directory, f"install-{index}.log", on_result=record_install)
            require(package in owned, "Install success was not confirmed")
        remote_directory = "files/m5-eval/" + args.run_id
        execute(adb + ["shell", "run-as", TARGET, "mkdir", "-p", remote_directory], directory, "mkdir.log")
        temporary_owned = True
        execute(adb + ["push", bundle_path, temporary], directory, "push.log")
        execute(adb + ["shell", "run-as", TARGET, "cp", temporary, remote_directory + "/bundle.json"],
                directory, "copy.log")
        summary["device_execution"] = True
        instrumentation = execute(adb + ["shell", "am", "instrument", "-w", "-e", "class",
            "com.example.persona.evaluation.M5RoomAdapterInstrumentedTest", "-e", "runM5RoomAdapter", "true",
            "-e", "m5RunId", args.run_id, "-e", "m5BundleSha256", bundle_sha,
            "-e", "m5SourceRevision", bundle["implementation_revision"],
            *(["-e", "runM5Lifecycle", "true"] if with_lifecycle else []),
            *(["-e", "runM5ControlledNetwork", "true"] if controlled_network else []),
            TEST + "/androidx.test.runner.AndroidJUnitRunner"], directory, "instrumentation.log", timeout=300, check=False)
        exported = execute(adb + ["exec-out", "run-as", TARGET, "cat", remote_directory + "/observations.json"],
                           directory, "observations.json", check=False)
        require(instrumentation.returncode == 0, "Instrumentation process failed; available private diagnostics retained")
        require(re.search(rb"(?m)^OK\s*\(1 test\)\s*$", instrumentation.stdout) is not None,
                "Instrumentation did not complete; available private diagnostics retained")
        require(exported.returncode == 0, "Adapter export file is unavailable")
        validate_export(json.loads(exported.stdout), bundle, bundle_sha, apk_sha, scope, controlled_network)
        require(source_snapshot(repo) == bundle["source_snapshot"], "Source changed during device run")
        summary.update(device_apk_hashes_verified=True,
                       source_binding="snapshot_unchanged_across_build_and_device_apk_hashes_checked")
        summary["adapter_execution"] = "completed"
        comparison = execute([sys.executable, ROOT / "evaluate.py", "compare", "--split", args.split,
            "--observations", directory / "observations.json", "--output", directory / "comparison.json"],
            directory, "comparison.log", check=False)
        require(comparison.returncode in (0, 1), "Comparison input or tool failed")
        counts = json.loads((directory / "comparison.json").read_text(encoding="utf-8"))["counts"]
        require(comparison.returncode == int(bool(counts.get("fail") or counts.get("unsupported"))),
                "Comparison exit code disagrees with reported counts")
        summary["counts"] = counts
        summary["comparison"] = "failed" if counts.get("fail") else "incomplete" if counts.get("unsupported") else "passed"
        exit_code = comparison.returncode
    except (ValueError, KeyError, TypeError, OSError, subprocess.SubprocessError) as error:
        summary.update(error_class=type(error).__name__, error=str(error))
        if summary["device_execution"] and summary["adapter_execution"] == "not_run":
            summary["adapter_execution"] = "failed"
    finally:
        for index, package in enumerate(reversed(owned)):
            try:
                execute(adb + ["shell", "am", "force-stop", package], directory,
                        f"stop-{index}.log", timeout=30)
            except (ValueError, OSError, subprocess.SubprocessError) as error:
                cleanup_errors.append({"package": package, "stage": "stop", "error_class": type(error).__name__})
            try:
                result = execute(adb + ["uninstall", package], directory, f"uninstall-{index}.log", timeout=60)
                require(b"Success" in result.stdout.splitlines(), "Uninstall success was not confirmed")
            except (ValueError, OSError, subprocess.SubprocessError) as error:
                cleanup_errors.append({"package": package, "error_class": type(error).__name__})
        if temporary_owned:
            try:
                execute(adb + ["shell", "rm", "-f", temporary], directory, "temporary-cleanup.log", timeout=30)
            except (ValueError, OSError, subprocess.SubprocessError) as error:
                cleanup_errors.append({"temporary": True, "error_class": type(error).__name__})
        summary.update(owned_packages=owned, cleanup_errors=cleanup_errors)
        if cleanup_errors:
            exit_code = 2
        summary["exit_code"] = exit_code
        (directory / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"directory": str(directory), "build": summary["build"],
                      "adapter_execution": summary["adapter_execution"], "comparison": summary["comparison"],
                      "exit_code": exit_code, "llm_quality": "not_run"}, ensure_ascii=False))
    return exit_code


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--split", choices=("dev", "heldout", "all"), default="dev")
    parser.add_argument("--with-lifecycle", action="store_true",
                        help="Use real request orchestration with controlled engine/transport boundaries")
    parser.add_argument("--controlled-network", action="store_true",
                        help="Use controlled policy inputs and require a metered denial control; no real cloud requests")
    sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not sdk and os.name == "nt":
        sdk = str(Path(os.environ.get("LOCALAPPDATA", "")) / "Android/Sdk")
    default_adb = str(Path(sdk) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")) if sdk else "adb"
    parser.add_argument("--adb", default=default_adb)
    args = parser.parse_args()
    try:
        return run(args)
    except (ValueError, OSError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
