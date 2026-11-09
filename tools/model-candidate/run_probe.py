"""Future isolated Qwen3 host driver; private evidence, no production admission."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path, PureWindowsPath
import re
import signal
import socket
import stat
import subprocess
import sys
import time
import zipfile
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).parent))
sys.path.insert(0, str(ROOT / "tools/persona-eval"))
from prepare_device import source_snapshot, snapshot_digest
from run_device import isolated_apks, TARGET, TEST
from apk_identity import verify_apk_identities
RECEIPT_SHA = "1775292d3ecaa87a0d776cc4e773a8452294b9b3a4fdd4e3b1f82ff413e39b32"
MODEL = "c1899de289a04d12100db370d81485cdf75e47ca"
RUNTIME = "d407447ed56c4121a11ccbd266dc184ca1ead0c2"
FILES = frozenset(("config.json", "context.json", "embeddings_bf16.bin", "export_args.json",
                   "LICENSE", "llm.mnn", "llm.mnn.json", "llm.mnn.weight",
                   "llm_config.json", "README.md", "tokenizer.mtok"))

def require(value):
    if not value:
        raise ValueError("ProbeContractRejected")

def lexical(value, windows=False):
    raw = os.fspath(value)
    require(isinstance(raw, str) and raw and "\x00" not in raw)
    path = PureWindowsPath(raw) if windows else Path(raw)
    require(".." not in path.parts and (windows or "\\" not in raw))
    require(not windows or not path.drive or path.is_absolute())
    for part in path.parts:
        if part == path.anchor:
            continue
        require(not re.search(r'[<>:"|?*\x00-\x1f]', part) and not part.endswith((".", " ")))
        require(not re.fullmatch(r"(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\..*)?", part))
    return path

def safe(value, missing=False):
    path = Path(lexical(value, os.name == "nt")).absolute()
    for item in reversed((path, *path.parents)):
        if missing and item == path and not os.path.lexists(item):
            continue
        info = item.lstat()
        require(not stat.S_ISLNK(info.st_mode)
                and not getattr(info, "st_file_attributes", 0) & 0x400)
    return path

def stamp(info, *, windows=None):
    windows = os.name == "nt" if windows is None else windows
    # Windows path and descriptor APIs can report different deprecated ctime values.
    timestamp = getattr(info, "st_birthtime_ns", None) if windows else info.st_ctime_ns
    return (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, timestamp)

def read_file(value, limit=2 * 1024**3, capture=False, deadline=math.inf):
    path = safe(value)
    before = path.lstat()
    require(stat.S_ISREG(before.st_mode) and 0 < before.st_size <= limit)
    flags = os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0)
    flags |= getattr(os, "O_NONBLOCK", 0)
    digest, chunks, count = hashlib.sha256(), [], 0
    with os.fdopen(os.open(path, flags), "rb") as stream:
        require(stamp(before) == stamp(os.fstat(stream.fileno())))
        while True:
            if time.monotonic() >= deadline:
                raise TimeoutError()
            block = stream.read(min(1024**2, limit - count + 1))
            if not block:
                break
            count += len(block)
            require(count <= limit)
            digest.update(block)
            if capture:
                chunks.append(block)
        require(count == before.st_size and stamp(before) == stamp(os.fstat(stream.fileno())))
    require(stamp(before) == stamp(safe(path).lstat()))
    return digest.hexdigest(), b"".join(chunks), stamp(before)

def pairs(items):
    result = {}
    for key, value in items:
        require(key not in result)
        result[key] = value
    return result

def document(data):
    value = json.loads(data.decode("utf-8"), object_pairs_hook=pairs,
                       parse_constant=lambda _: require(False))
    require(type(value) is dict)
    return value

def inventory(root):
    root = safe(root)
    result = {}
    for path in root.iterdir():
        require(path.name in FILES and len(result) < 11)
        info = safe(path).lstat()
        require(stat.S_ISREG(info.st_mode))
        result[path.name] = stamp(info)
    require(result.keys() == FILES)
    return result

def snapshot(repo):
    current = source_snapshot(repo)
    require(all(safe(repo / name) for name in current))
    return current

def preflight(args, repo=ROOT, *, source_lock=None, deadline=math.inf):
    require(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,63}", args.run_id))
    require(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}", args.serial))
    if source_lock is None:
        _, raw, _ = read_file(Path(__file__).with_name("source-lock.json"), 1024**2, True)
        source_lock = document(raw)
        require(source_lock.get("packageReceiptSha256") == RECEIPT_SHA)
    require(source_lock.get("modelRepo") == "Qwen/Qwen3-0.6B"
            and source_lock.get("modelRevision") == MODEL and source_lock.get("runtimeRevision") == RUNTIME)
    receipt_sha, raw, _ = read_file(args.receipt, 1024**2, True, deadline)
    require(receipt_sha == source_lock["packageReceiptSha256"])
    receipt = document(raw)
    require(set(receipt) == {"model_revision", "mnn_revision", "quant_bits", "files"})
    require(receipt["model_revision"] == MODEL and receipt["mnn_revision"] == RUNTIME)
    require(receipt["quant_bits"] == [8, 8, 16]
            and all(type(bit) is int for bit in receipt["quant_bits"]))
    require(type(receipt["files"]) is list and len(receipt["files"]) == 11)
    expected, total = {}, 0
    for row in receipt["files"]:
        require(type(row) is dict and set(row) == {"path", "size", "sha256"})
        name, size = row["path"], row["size"]
        require(isinstance(name, str) and name in FILES and name not in expected)
        require(type(size) is int and 0 < size <= 2 * 1024**3 and size <= 4 * 1024**3 - total)
        require(isinstance(row["sha256"], str) and re.fullmatch(r"[0-9a-f]{64}", row["sha256"]))
        expected[name], total = row, total + size
    root, identities = safe(args.package), set()
    before = inventory(root)
    for name, row in expected.items():
        digest, _, metadata = read_file(root / name, deadline=deadline)
        require(metadata == before[name] and metadata[2] == row["size"] and digest == row["sha256"])
        require(metadata[:2] not in identities)
        identities.add(metadata[:2])
    require(inventory(root) == before)
    bundle_sha, raw, _ = read_file(args.bundle, 16 * 1024**2, True, deadline)
    bundle = document(raw)
    source = snapshot(repo)
    revision = "sha256:" + snapshot_digest(source)
    require(bundle.get("sourceRevision") == revision and bundle.get("packageReceiptSha256") == receipt_sha)
    require(bundle.get("kind") == "qwen3-native-probe-v1" and type(bundle.get("formatVersion")) is int
            and bundle["formatVersion"] == 1)
    require(time.monotonic() < deadline)
    return {"snapshot": source, "sourceRevision": revision, "bundleSha256": bundle_sha,
            "receiptSha256": receipt_sha, "files": expected, "inventory": before}

def new_output(args):
    output = safe(args.output, missing=True)
    package = Path(lexical(args.package, os.name == "nt")).absolute()
    require(output != package and package not in output.parents and not os.path.lexists(output))
    output.mkdir(mode=0o700)
    return safe(output)

def build_command(repo):
    if os.name == "nt":
        java = Path(os.environ["JAVA_HOME"]) / "bin/java.exe" if os.environ.get("JAVA_HOME") else "java"
        command = [str(java), "-cp", str(repo / "gradle/wrapper/gradle-wrapper.jar"), "org.gradle.wrapper.GradleWrapperMain"]
    else:
        command = ["sh", str(repo / "gradlew")]
    return command + ["-Ppersona.isolatedValidation=true", "testDebugUnitTest", "assembleDebug",
                      "assembleDebugAndroidTest", "--no-daemon", "--console=plain"]

def checked_apks(repo):
    metadata = {}
    for folder, package in (("debug", TARGET), ("androidTest/debug", TEST)):
        path = repo / "app/build/outputs/apk" / folder / "output-metadata.json"
        digest, raw, _ = read_file(path, 1024**2, True)
        data = document(raw)
        require(data.get("applicationId") == package and len(data["elements"]) == 1)
        require(re.fullmatch(r"[A-Za-z0-9_-]+\.apk", data["elements"][0]["outputFile"]))
        metadata[path] = digest
    apks = isolated_apks(repo)
    require(set(apks) == {TARGET, TEST} and all(read_file(p, 1024**2)[0] == h for p, h in metadata.items()))
    return {package: safe(path) for package, path in apks.items()}
def native_receipt(apk, deadline=math.inf):
    digest, _, before = read_file(apk, deadline=deadline)
    name, native_hash, size = "lib/arm64-v8a/libpersona_mnn.so", hashlib.sha256(), 0
    with zipfile.ZipFile(safe(apk)) as archive:
        entries = [entry for entry in archive.infolist() if entry.filename == name]
        require(len(entries) == 1 and 0 < entries[0].file_size <= 256 * 1024**2
                and not stat.S_ISLNK(entries[0].external_attr >> 16))
        with archive.open(entries[0]) as stream:
            for block in iter(lambda: stream.read(1024**2), b""):
                require(time.monotonic() < deadline and size + len(block) <= entries[0].file_size)
                native_hash.update(block)
                size += len(block)
        require(size == entries[0].file_size)
    require(stamp(safe(apk).lstat()) == before)
    return {"apk_sha256": digest, "native_library": {"path": name, "size": size, "sha256": native_hash.hexdigest()},
            "runtimeRevision": RUNTIME, "runtime_revision_evidence": "declared_target_only",
            "runtime_source_verified": False, "candidateAdopted": False}

class Host:
    def __init__(self, args, output, deadline):
        self.args, self.output, self.deadline = args, output, deadline
        self.owned, self.temporary, self.server = [], [], None
        self.env = dict(os.environ, ADB_SERVER_SOCKET="tcp:127.0.0.1:5037")

    def command(self, command, label, *, deadline=None, check=True, timeout=3600):
        remaining = min(timeout, (self.deadline if deadline is None else deadline) - time.monotonic())
        if remaining <= 0:
            raise TimeoutError()
        options = {"creationflags": subprocess.CREATE_NEW_PROCESS_GROUP} if os.name == "nt" else {"start_new_session": True}
        process = subprocess.Popen(list(map(str, command)), cwd=ROOT, env=self.env, shell=False,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, **options)
        try:
            stdout, stderr = process.communicate(timeout=remaining)
        except (subprocess.TimeoutExpired, KeyboardInterrupt) as error:
            stdout = getattr(error, "output", None) or b""
            stderr = getattr(error, "stderr", None) or b""
            try:
                try:
                    if os.name == "nt":
                        subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"],
                                       capture_output=True, timeout=5, check=False)
                    else:
                        try:
                            os.killpg(process.pid, signal.SIGKILL)
                        except ProcessLookupError:
                            pass
                finally:
                    if process.poll() is None:
                        process.kill()
                    try:
                        stdout, stderr = process.communicate(timeout=5)
                    except subprocess.TimeoutExpired as pending:
                        stdout, stderr = pending.output or b"", pending.stderr or b""
                        raise
            finally:
                (self.output / label).write_bytes(stdout + stderr)
            if isinstance(error, subprocess.TimeoutExpired):
                raise TimeoutError() from None
            raise
        (self.output / label).write_bytes(stdout + stderr)
        if check:
            require(process.returncode == 0)
        return subprocess.CompletedProcess(command, process.returncode, stdout, stderr)

    def adb(self, command, label, **options):
        return self.command([self.args.adb, "-H", "127.0.0.1", "-P", "5037", "-s",
                             self.args.serial, *command], label, **options)

    def start_server(self):
        version = self.command([self.args.adb, "version"], "private-adb-version.log").stdout
        match = re.search(rb"Android Debug Bridge version 1\.0\.(\d+)", version)
        require(match is not None)
        with socket.socket() as connection:
            connection.settimeout(1)
            external = connection.connect_ex(("127.0.0.1", 5037)) == 0
        if not external:
            with (self.output / "private-adb-server.log").open("wb") as log:
                self.server = subprocess.Popen([self.args.adb, "-L", "tcp:5037", "nodaemon", "server"],
                                               env=self.env, stdout=log, stderr=subprocess.STDOUT, shell=False)
        startup_deadline = min(self.deadline, time.monotonic() + 15)
        while True:
            require(self.server is None or self.server.poll() is None)
            if time.monotonic() >= startup_deadline:
                raise TimeoutError()
            try:
                with socket.create_connection(("127.0.0.1", 5037), timeout=1) as connection:
                    connection.sendall(b"000chost:version")
                    response = connection.makefile("rb")
                    require(response.read(4) == b"OKAY" and response.read(4) == b"0004"
                            and int(response.read(4), 16) == int(match[1]))
                return
            except (ConnectionRefusedError, TimeoutError):
                time.sleep(0.1)

    def push(self, local, remote, digest, index):
        temporary = f"/data/local/tmp/persona-qwen3-{self.args.run_id}-{index}"
        for flag in ("-e", "-L"):
            self.adb(["shell", "test", "!", flag, temporary], f"private-tmp-check-{index}{flag}.log")
        require(read_file(local, deadline=self.deadline)[0] == digest)
        self.temporary.append(temporary)
        self.adb(["push", local, temporary], f"private-push-{index}.log")
        self.adb(["shell", "run-as", TARGET, "cp", temporary, remote], f"private-copy-{index}.log")
        result = self.adb(["shell", "run-as", TARGET, "sha256sum", remote], f"private-hash-{index}.log")
        require(result.stdout.split()[0].decode("ascii") == digest)
        self.adb(["shell", "rm", "-f", temporary], f"private-rm-{index}.log")
        self.temporary.remove(temporary)

def observations(raw, binding, run_id):
    report = document(raw)
    require(report.get("run_id") == run_id and report.get("bundle_sha256") == binding["bundleSha256"]
            and report.get("source_revision") == binding["sourceRevision"]
            and report.get("package_receipt_sha256") == binding["receiptSha256"])
    require(report.get("quality_status") in {"not_evaluated", "not_evaluated_incomplete",
                                           "passed_fixed_six", "not_passed_fixed_six"})
    return report

def engineering_passed(result, report):
    return (result is not None and result.returncode == 0 and report.get("engineering_status") == "passed"
            and re.search(rb"(?m)^OK\s*\(1 test\)\s*$", result.stdout) is not None)

def run(args, repo=ROOT, *, _source_lock=None):
    require(type(args.timeout) is int and 0 < args.timeout <= 3600)
    deadline = time.monotonic() + args.timeout
    output = new_output(args)
    host = Host(args, output, deadline - min(60, args.timeout / 4))
    private = "files/qwen3-probe/" + args.run_id
    summary = {"candidateAdopted": False, "engineering_status": "failed", "quality_status": "not_evaluated",
               "runtime_source_verified": False, "runtimeRevision": RUNTIME, "runtime_revision_evidence": "declared_target_only",
               "release_trust": "not_signature_verified", "cleanup_errors": [], "observations_available": False}
    (output / "privateinstrumentation.log").write_bytes(b"")
    (output / "observations.json").write_text('{"engineering_status":"not_run","quality_status":"not_evaluated"}\n')
    binding, instrumentation = None, None
    try:
        binding = preflight(args, repo, source_lock=_source_lock, deadline=host.deadline)
        summary.update({key: binding[key] for key in ("sourceRevision", "bundleSha256", "receiptSha256")})
        host.start_server()
        require(host.adb(["get-state"], "private-state.log").stdout.strip() == b"device")
        installed = host.adb(["shell", "pm", "list", "packages", "com.example.persona"], "private-installed.log")
        names = installed.stdout.decode("utf-8").splitlines()
        require(all(line.startswith("package:") and not line[8:].startswith("com.example.persona.") for line in names))
        host.command(build_command(repo), "private-build.log")
        require(snapshot(repo) == binding["snapshot"])
        apks = verify_apk_identities(host, checked_apks(repo), read_file)
        summary["apk_sha256"] = {}
        for index, (package, (apk, manifest_digest)) in enumerate(apks.items()):
            digest = read_file(apk, deadline=host.deadline)[0]
            require(digest == manifest_digest)
            if package == TARGET:
                native = native_receipt(apk, host.deadline)
                require(native["apk_sha256"] == digest)
                (output / "apk-receipt.json").write_text(json.dumps(native, indent=2) + "\n", encoding="utf-8")
            result = host.adb(["install", "-t", apk], f"private-install-{index}.log", check=False)
            require(result.returncode == 0 and b"Success" in result.stdout.splitlines())
            host.owned.append(package)
            result = host.adb(["shell", "pm", "path", package], f"private-apk-path-{index}.log")
            remote = result.stdout.decode("ascii").strip()
            require(re.fullmatch(r"package:/data/app/[A-Za-z0-9_./+=~-]+/base\.apk", remote))
            result = host.adb(["shell", "sha256sum", remote[8:]], f"private-apk-hash-{index}.log")
            require(result.stdout.split()[0].decode("ascii") == digest)
            summary["apk_sha256"][package] = digest
        host.adb(["shell", "run-as", TARGET, "mkdir", "-p", "files/qwen3-probe"], "private-parent.log")
        host.adb(["shell", "run-as", TARGET, "mkdir", private], "private-directory.log")
        host.adb(["shell", "run-as", TARGET, "mkdir", private + "/model"], "private-model.log")
        inputs = [(args.bundle, private + "/bundle.json", binding["bundleSha256"]),
                  (args.receipt, private + "/receipt.json", binding["receiptSha256"])]
        inputs += [(Path(args.package) / name, private + "/model/" + name, row["sha256"])
                   for name, row in sorted(binding["files"].items())]
        for index, (local, remote, digest) in enumerate(inputs):
            host.push(local, remote, digest, index)
        require(inventory(args.package) == binding["inventory"] and snapshot(repo) == binding["snapshot"])
        instrumentation = host.adb(["shell", "am", "instrument", "-w", "-e", "class",
            "com.example.persona.core.ai.mnn.Qwen3NativeProbeInstrumentedTest", "-e", "runQwen3Probe", "true",
            "-e", "qwen3RunId", args.run_id, "-e", "qwen3BundleSha256", binding["bundleSha256"],
            "-e", "qwen3SourceRevision", binding["sourceRevision"], TEST + "/androidx.test.runner.AndroidJUnitRunner"],
            "privateinstrumentation.log", check=False)
    except (Exception, KeyboardInterrupt) as error:
        summary["error_type"] = type(error).__name__
    finally:
        host.deadline = deadline
        if TARGET in host.owned:
            try:
                host.adb(["shell", "am", "force-stop", TARGET], "private-evidence-stop.log", timeout=3)
            except (Exception, KeyboardInterrupt) as error:
                summary["cleanup_errors"].append(type(error).__name__)
            try:
                result = host.adb(["exec-out", "run-as", TARGET, "cat", private + "/observations.json"],
                                  "private-evidence-fetch.log", check=False, timeout=7)
                if result.returncode == 0:
                    (output / "observations.json").write_bytes(result.stdout)
                    report = observations(result.stdout, binding, args.run_id)
                    summary.update(quality_status=report["quality_status"], observations_available=True)
                    require(engineering_passed(instrumentation, report) and snapshot(repo) == binding["snapshot"])
                    summary["engineering_status"] = "passed"
            except (Exception, KeyboardInterrupt) as error:
                summary["evidence_error_type"] = type(error).__name__
        for index, command in enumerate([command for package in reversed(host.owned) for command in
                   (["shell", "am", "force-stop", package], ["uninstall", package])]
                   + [["shell", "rm", "-f", path] for path in host.temporary]):
            try:
                result = host.adb(command, f"private-cleanup-{index}.log", timeout=2)
                require(command[0] != "uninstall" or b"Success" in result.stdout.splitlines())
            except (Exception, KeyboardInterrupt) as error:
                summary["cleanup_errors"].append(type(error).__name__)
        if host.server is not None and host.server.poll() is None:
            try:
                host.server.terminate()
                try:
                    host.server.wait(timeout=max(0.01, min(5, deadline - time.monotonic())))
                except subprocess.TimeoutExpired:
                    host.server.kill()
                    host.server.wait(timeout=1)
            except (Exception, KeyboardInterrupt) as error:
                summary["cleanup_errors"].append(type(error).__name__)
        summary["exit_code"] = int(summary["engineering_status"] != "passed" or bool(summary["cleanup_errors"]))
        (output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    if summary.get("error_type") or summary.get("evidence_error_type"):
        print(summary.get("error_type", summary.get("evidence_error_type")), file=sys.stderr)
    return summary["exit_code"]

def main(argv=None):
    try:
        parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
        parser.error = lambda _message: require(False)
        for name in ("adb", "serial", "run-id", "bundle", "receipt", "package", "output"):
            parser.add_argument("--" + name, required=True)
        parser.add_argument("--timeout", type=int, default=1800)
        return run(parser.parse_args(argv))
    except (Exception, KeyboardInterrupt) as error:
        print(type(error).__name__, file=sys.stderr)
        return 1

if __name__ == "__main__":
    sys.exit(main())
