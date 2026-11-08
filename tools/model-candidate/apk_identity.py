"""Verify isolated APK manifest IDs using an existing SDK AAPT2 binary."""

from pathlib import Path
import os
import re
import shutil

QA_IDS = ("com.example.persona.validation", "com.example.persona.validation.test")


def _require(value):
    if not value:
        raise ValueError("ApkIdentityRejected")


def _version(name):
    match = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)(?:-(alpha|beta|rc)(\d+))?", name)
    if match is None:
        return None
    major, minor, patch, stage, sequence = match.groups()
    return (stage is None, int(major), int(minor), int(patch),
            {None: 3, "rc": 2, "beta": 1, "alpha": 0}[stage], int(sequence or 0))


def find_aapt2(adb, inspect_file, deadline):
    # Keep paths lexical: inspect_file rejects links and reparse-point ancestors.
    path = Path(shutil.which(os.fspath(adb)) or adb).absolute()
    _require(path.name.lower() in {"adb", "adb.exe"}
             and path.parent.name.lower() == "platform-tools")
    inspect_file(path, deadline=deadline)
    tools = path.parent.parent / "build-tools"
    executable = "aapt2.exe" if path.name.lower().endswith(".exe") else "aapt2"
    candidates = []
    for directory in tools.iterdir():
        version = _version(directory.name)
        candidate = directory / executable
        if version is not None and candidate.is_file():
            candidates.append((version, candidate))
    if not candidates:
        raise FileNotFoundError("ApkIdentityToolUnavailable")
    tool = max(candidates, key=lambda item: item[0])[1]
    inspect_file(tool, deadline=deadline)
    return tool


def verify_apk_identities(host, apks, inspect_file):
    """Return paths and verified SHA256s only after both actual manifest IDs pass."""
    _require(set(apks) == set(QA_IDS))
    tool = find_aapt2(host.args.adb, inspect_file, host.deadline)
    tool_state = inspect_file(tool, deadline=host.deadline)
    verified = {}
    for index, package in enumerate(QA_IDS):
        path = apks[package]
        before = inspect_file(path, deadline=host.deadline)
        result = host.command([tool, "dump", "packagename", path],
                              f"private-apk-manifest-{index}.log", timeout=30)
        _require(result.returncode == 0 and not result.stderr
                 and re.fullmatch(re.escape(package.encode("ascii")) + rb"(?:\r?\n)?", result.stdout))
        _require(inspect_file(path, deadline=host.deadline) == before
                 and inspect_file(tool, deadline=host.deadline) == tool_state)
        verified[package] = (path, before[0])
    return verified
