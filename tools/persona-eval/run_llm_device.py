#!/usr/bin/env python3
"""Run synthetic subjective cases through actual ChatViewModel/Room/MNN, without cloud calls."""

import argparse
import json
import os
from pathlib import Path, PurePosixPath
import re
import socket
import subprocess
import sys

from evaluate import suite_digest
from prepare_device import REPO_ROOT, source_snapshot, snapshot_digest
from run_device import TARGET, TEST, digest, execute, isolated_apks
from schema import VERSION, load_suite, require

MODEL_MANIFESTS = {
    "primary": "fca5ad06d6976688872c279cec0e98a4fe116fe8dc9853195b3caeaaa6180e3e",
    "secondary": "7f37c6fa1577e5fb8d841b48ac4d4f811a8241ed38d594945458e4587ff861b2",
}
MIN_EVALUATION_BATTERY_PERCENT = 20


def require_evaluation_battery(output):
    levels = re.findall(rb"(?m)^\s*level:\s*(\d+)\s*$", output)
    require(len(levels) == 1 and int(levels[0]) <= 100, "Missing or invalid physical battery level")
    level = int(levels[0])
    require(level >= MIN_EVALUATION_BATTERY_PERCENT,
            "Evaluation requires at least 20% physical battery; charge the device and retry")
    return level


def bundle(split, repo=REPO_ROOT):
    require(split in {"dev", "heldout"}, "Invalid evaluation split")
    snapshot = source_snapshot(repo)
    cases = [{key: value for key, value in case.items() if key not in {"expected", "rubric"}}
             for case in load_suite() if case["track"] == "llm_subjective" and case["split"] == split]
    return {"bundle_version": "actual-chat-mnn-batch-v1", "dataset_version": VERSION,
            "dataset_sha256": suite_digest(), "split": split, "cases": cases,
            "source_snapshot": snapshot, "implementation_revision": "sha256:" + snapshot_digest(snapshot)}


def model_files(model_root):
    result = {}
    for name, manifest_sha in MODEL_MANIFESTS.items():
        source = model_root / name
        require(source.is_dir() and not source.is_symlink(), "Missing cached model directory")
        manifest_path = source / "manifest.json"
        require(digest(manifest_path) == manifest_sha, "Cached model manifest differs from frozen model")
        manifest = json.loads(manifest_path.read_text(encoding="utf-8-sig"))
        entries = {entry["path"]: entry for entry in manifest["files"]}
        require(len(entries) == len(manifest["files"]), "Duplicate model file")
        entries.update({"manifest.json": {}, "READY": {}})
        verified = []
        for relative, entry in entries.items():
            parts = PurePosixPath(relative)
            require(not parts.is_absolute() and all(part not in {".", ".."} for part in parts.parts)
                    and "\\" not in relative and ":" not in relative, "Unsafe model path")
            path = source / relative
            require(path.is_file() and not any(p.is_symlink() for p in (path, *path.parents)
                    if p == source or source in p.parents), "Linked or missing model file")
            sha = digest(path)
            if "sha256" in entry:
                require(sha == entry["sha256"].lower(), "Cached model content failed manifest hash")
            verified.append((relative, path, sha))
        result[name] = verified
    return result


def run(args, repo=REPO_ROOT):
    repo = Path(repo)
    require(re.fullmatch(r"[a-z0-9-]{1,40}", args.run_id) is not None, "Invalid run ID")
    require(re.fullmatch(r"[A-Za-z0-9._:-]+", args.serial) is not None, "Invalid serial")
    directory = repo / "build/verification" / ("m5-llm-" + args.run_id)
    directory.mkdir(parents=True, exist_ok=False)
    owned, temporary = [], set()
    adb = [args.adb, "-s", args.serial]
    summary = {"kind": "actual_mnn_subjective_batch", "run_id": args.run_id,
               "build": "not_run", "execution": "not_run", "human_review": "pending",
               "real_cloud": False, "cleanup_errors": []}
    code = 2
    server = None
    server_log = None
    try:
        # An SSH channel can outlive an ADB daemon inherited from an earlier channel.
        # Own a foreground server for this run when the standard port is available.
        with socket.socket() as connection:
            external_server = connection.connect_ex(("127.0.0.1", 5037)) == 0
        summary["adb_server"] = "external" if external_server else "owned_foreground"
        if not external_server:
            server_log = (directory / "adb-server.log").open("wb")
            server = subprocess.Popen([args.adb, "nodaemon", "server"],
                                      stdout=server_log, stderr=subprocess.STDOUT)
        execute(adb + ["wait-for-device"], directory, "device-wait.log", timeout=30)
        require(execute(adb + ["get-state"], directory, "device.log").stdout.strip() == b"device",
                "Device is not authorized")
        battery = execute(adb + ["shell", "dumpsys", "battery"], directory, "battery-preflight.log")
        summary["initial_battery_percent"] = require_evaluation_battery(battery.stdout)
        for index, package in enumerate((TARGET, TEST)):
            check = execute(adb + ["shell", "pm", "path", package], directory,
                            f"preflight-{index}.log", check=False)
            require(not check.stdout.strip() and check.returncode in (0, 1), "Refusing existing validation package")
        models = model_files(args.model_root)
        data = bundle(args.split, repo)
        path = directory / "bundle.json"
        path.write_text(json.dumps(data, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        build = ([repo / "gradlew.bat"] if os.name == "nt" else ["sh", repo / "gradlew"])
        build += ["-Ppersona.isolatedValidation=true", "testDebugUnitTest", "assembleDebug",
                  "assembleDebugAndroidTest", "--console=plain", "--stacktrace"]
        print("M5_LLM_BUILD_STARTED", flush=True)
        execute(build, directory, "build.log", timeout=1800)
        summary["build"] = "passed"
        require(source_snapshot(repo) == data["source_snapshot"], "Source changed during build")
        apks = isolated_apks(repo)
        summary["apk_sha256"] = {package: digest(apk) for package, apk in apks.items()}
        summary["source_revision"] = data["implementation_revision"]
        summary["input_bundle_sha256"] = digest(path)
        # Freeze these receipts before dev or heldout generation begins.
        (directory / "frozen-config.json").write_text(json.dumps({
            "source_revision": data["implementation_revision"], "models": MODEL_MANIFESTS,
            "parameters": {"temperature": 0.6, "topP": 0.8, "maxTokens": 256, "seed": None},
            "prompt": "production-ChatViewModel-persona-json-v2", "context_assembly": "quoted-user-facts-v5",
            "retrieval": "latest-authorized-v1-top4-1200utf16", "repetitions": 3,
            "other_sampler_parameters": "model config and pinned runtime defaults",
            "actual_cloud": False}, indent=2) + "\n", encoding="utf-8")
        for index, (package, apk) in enumerate(apks.items()):
            def installed(result, package=package):
                if result.returncode == 0 and b"Success" in result.stdout.splitlines():
                    owned.append(package)
            execute(adb + ["install", "-t", apk], directory, f"install-{index}.log", on_result=installed)
            require(package in owned, "APK installation was not confirmed")
        print("M5_LLM_APKS_INSTALLED", flush=True)
        counter = 0

        def push(local, target):
            nonlocal counter
            counter += 1
            name = "/data/local/tmp/persona-m5-llm-" + args.run_id + "-" + str(counter) + ".bin"
            temporary.add(name)
            execute(adb + ["shell", "run-as", TARGET, "mkdir", "-p", str(PurePosixPath(target).parent)],
                    directory, f"mkdir-{counter}.log")
            execute(adb + ["push", local, name], directory, f"push-{counter}.log", timeout=300)
            execute(adb + ["shell", "run-as", TARGET, "cp", name, target], directory, f"copy-{counter}.log", timeout=180)
            actual = execute(adb + ["shell", "run-as", TARGET, "sha256sum", target], directory, f"hash-{counter}.log")
            require(actual.stdout.split()[0].decode("ascii") == digest(local), "Device fixture hash mismatch")
            execute(adb + ["shell", "rm", "-f", name], directory, f"rm-{counter}.log")
            temporary.remove(name)

        for name, files in models.items():
            for relative, local, _ in files:
                push(local, "files/model-sources/" + name + "/" + relative)
            print("M5_LLM_MODEL_READY=" + name, flush=True)
        private = "files/m5-llm/" + args.run_id
        push(path, private + "/bundle.json")
        summary["execution"] = "started"
        result = None
        try:
            result = execute(adb + ["shell", "am", "instrument", "-w", "-e", "class",
                "com.example.persona.evaluation.M5LlmBatchInstrumentedTest", "-e", "runM5LlmBatch", "true",
                "-e", "m5RunId", args.run_id, "-e", "m5BundleSha256", digest(path),
                "-e", "m5SourceRevision", data["implementation_revision"],
                TEST + "/androidx.test.runner.AndroidJUnitRunner"], directory, "instrumentation.log", timeout=10900, check=False)
        except subprocess.TimeoutExpired:
            summary["instrumentation_timeout"] = True
            execute(adb + ["shell", "am", "force-stop", TARGET], directory, "timeout-stop.log")
        finally:
            exported = execute(adb + ["exec-out", "run-as", TARGET, "cat", private + "/responses.json"],
                               directory, "responses.json", check=False)
            # Package removal clears exit history on some devices. Capture it first.
            execute(adb + ["shell", "dumpsys", "activity", "exit-info", TARGET],
                    directory, "application-exit-info.log", check=False)
            if exported.returncode == 0:
                process_id = json.loads(exported.stdout).get("process_id")
                if type(process_id) is int and process_id > 0:
                    execute(adb + ["logcat", "-d", "-b", "crash", "--pid=" + str(process_id)],
                            directory, "target-process-crash.log", check=False)
        require(exported.returncode == 0, "Response evidence is missing")
        report = json.loads(exported.stdout)
        require(report["source_revision"] == data["implementation_revision"]
                and report["input_bundle_sha256"] == digest(path), "Response source binding mismatch")
        require(report["target_apk_sha256"] == summary["apk_sha256"][TARGET]
                and report["test_apk_sha256"] == summary["apk_sha256"][TEST], "Installed APK differs from build")
        require(source_snapshot(repo) == data["source_snapshot"], "Source changed during model execution")
        counts = {status: sum(row["status"] == status for row in report["records"])
                  for status in ("completed", "failed", "unsupported")}
        summary["counts"] = counts
        summary["execution"] = report["execution_status"]
        require(result is not None and result.returncode == 0 and re.search(rb"(?m)^OK\s*\(1 test\)\s*$", result.stdout),
                "Model instrumentation failed; private responses retained")
        expected = {(case["id"], arm, repetition) for case in data["cases"]
                    for arm in ("baseline", "candidate") for repetition in (1, 2, 3)}
        actual = [(row["case_id"], row["arm"], row["repetition"]) for row in report["records"]]
        require(len(actual) == len(expected) and set(actual) == expected, "Incomplete batch export")
        code = 1  # Human decisions are deliberately never synthesized by this runner.
    except (ValueError, KeyError, TypeError, OSError, subprocess.SubprocessError) as error:
        summary.update(error_class=type(error).__name__, error=str(error))
    finally:
        for index, package in enumerate(reversed(owned)):
            try:
                execute(adb + ["shell", "am", "force-stop", package], directory, f"stop-{index}.log")
                execute(adb + ["uninstall", package], directory, f"uninstall-{index}.log")
                check = execute(adb + ["shell", "pm", "path", package], directory, f"removed-{index}.log", check=False)
                require(not check.stdout.strip() and check.returncode in (0, 1), "Validation package remains")
            except (ValueError, OSError, subprocess.SubprocessError):
                summary["cleanup_errors"].append(package)
        for index, name in enumerate(temporary):
            try:
                execute(adb + ["shell", "rm", "-f", name], directory, f"temp-cleanup-{index}.log")
            except (ValueError, OSError, subprocess.SubprocessError):
                summary["cleanup_errors"].append("temporary_fixture")
        if summary["cleanup_errors"]:
            code = 2
        if server is not None:
            server.terminate()
            try:
                server.wait(timeout=10)
            except subprocess.TimeoutExpired:
                server.kill()
                server.wait(timeout=10)
        if server_log is not None:
            server_log.close()
        summary["exit_code"] = code
        (directory / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(summary, ensure_ascii=True), flush=True)
    return code


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--split", choices=("dev", "heldout"), required=True)
    parser.add_argument("--model-root", type=Path, required=True)
    default = str(Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/platform-tools/adb.exe") if os.name == "nt" else "adb"
    parser.add_argument("--adb", default=default)
    return run(parser.parse_args())


if __name__ == "__main__":
    sys.exit(main())
