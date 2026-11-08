#!/usr/bin/env python3
"""Compile actual portable probe sources; Android/JNI execution is not claimed."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
TOOL = Path(__file__).parent


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--java-home", required=True, type=Path)
    p.add_argument("--deps", required=True, type=Path)
    p.add_argument("--output", required=True, type=Path)
    p.add_argument("--bundle-run", type=Path)
    args = p.parse_args()
    output = args.output.absolute()
    if output.exists() or not output.parent.is_dir() or any(x.is_symlink() for x in (output, *output.parents)):
        raise SystemExit("Output must be fresh with no linked ancestors")
    output.mkdir()
    sys.path.insert(0, str(ROOT / "tools/persona-eval"))
    from prepare_device import source_snapshot, snapshot_digest
    android_before = source_snapshot(ROOT)
    if args.bundle_run:
        summary = json.loads((args.bundle_run / "reference-summary.json").read_text())
        if "sha256:" + snapshot_digest(android_before) != summary["sourceRevision"]:
            raise SystemExit("Reference bundle belongs to a different Android source snapshot")
    core = ROOT / "app/src/main/java/com/example/persona"
    sources = [core / n for n in ["core/ai/GenerationParams.kt", "core/ai/prompt/NativeMessage.kt",
               "core/ai/prompt/NativePromptPayload.kt", "core/ai/mnn/NativeMnnSession.kt",
               "core/memory/MemoryExtraction.kt", "domain/model/Memory.kt"]]
    probe = ROOT / "app/src/androidTest/java/com/example/persona/core/ai/mnn"
    sources += sorted(probe.glob("Qwen3Probe*.kt"))
    tests = sorted((TOOL / "kotlin").glob("*Test.kt"))
    tests.append(ROOT / "app/src/test/java/com/example/persona/core/ai/mnn/NativeMnnSessionTest.kt")
    sources += tests + [TOOL / "kotlin/CheckProbeBundle.kt"]
    frozen, hashes = [], {}
    for source in sources:
        relative = source.relative_to(ROOT)
        target = output / "source" / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
        frozen.append(target)
        hashes[str(relative)] = sha(source)
    cp = os.pathsep.join(str(x) for x in sorted(args.deps.resolve().glob("*.jar")))
    if not cp:
        raise SystemExit("Missing explicit compiler dependencies")
    java = args.java_home.resolve() / "bin/java"
    test_names = ["com.example.persona.core.ai.mnn." + x.stem for x in tests]
    commands = []
    for debug in [True, False]:
        variant = output / ("debug" if debug else "release")
        variant.mkdir()
        stub = variant / "BuildConfig.kt"
        stub.write_text("package com.example.persona\nobject BuildConfig { const val DEBUG = " + str(debug).lower() + " }\n")
        classes = variant / "classes"
        classes.mkdir()
        operations = [("compile", [java, "-cp", cp, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
                       "-jvm-target", "11", "-classpath", cp, "-d", classes, *frozen, stub]),
                      ("junit", [java, "-ea", "-cp", str(classes) + os.pathsep + cp, "org.junit.runner.JUnitCore", *test_names])]
        if args.bundle_run and debug:
            summary = json.loads((args.bundle_run / "reference-summary.json").read_text())
            operations.append(("interop", [java, "-ea", "-cp", str(classes) + os.pathsep + cp,
                "com.example.persona.candidate.CheckProbeBundleKt", args.bundle_run / "bundle.json",
                summary["bundleSha256"], summary["sourceRevision"]]))
        for label, command in operations:
            command = list(map(str, command))
            commands.append(command)
            r = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, timeout=180)
            (variant / (label + ".log")).write_text(r.stdout + r.stderr)
            print(str(variant.name), label, "exit=" + str(r.returncode))
            if label != "compile":
                print(r.stdout, end="")
            if r.returncode:
                raise SystemExit(r.returncode)
    changed = [n for n, h in hashes.items() if sha(ROOT / n) != h]
    if android_before != source_snapshot(ROOT):
        changed.append("android_source_snapshot")
    report = {"sources": hashes, "commands": commands, "changedAfterSnapshot": changed,
              "androidSourceRevision": "sha256:" + snapshot_digest(android_before),
              "scope": "actual portable Kotlin with DEBUG BuildConfig boundary; no Android/JNI runtime"}
    (output / "receipt.json").write_text(json.dumps(report, indent=2) + "\n")
    raise SystemExit(bool(changed))


if __name__ == "__main__":
    main()
