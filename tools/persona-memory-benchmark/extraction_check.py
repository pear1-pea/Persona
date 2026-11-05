#!/usr/bin/env python3
"""Compile the framework-free memory cores and run JUnit in a fresh external output."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--deps", required=True, type=Path)
    parser.add_argument("--java-home", required=True, type=Path)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    output = args.output.resolve()
    if output.is_relative_to(repo) or output.exists():
        parser.error("--output must be a new directory outside the repository")
    names = [
        "kotlin-compiler-embeddable-2.0.21.jar", "kotlin-stdlib-2.0.21.jar",
        "kotlin-script-runtime-2.0.21.jar", "kotlin-reflect-1.6.10.jar",
        "kotlin-daemon-embeddable-2.0.21.jar", "trove4j-1.0.20200330.jar",
        "kotlinx-coroutines-core-jvm-1.8.1.jar", "annotations-13.0.jar",
        "junit-4.13.2.jar", "hamcrest-core-1.3.jar", "gson-2.8.5.jar",
    ]
    jars = [args.deps.resolve() / name for name in names]
    java = args.java_home.resolve() / "bin/java"
    javap = args.java_home.resolve() / "bin/javap"
    if not all(path.is_file() for path in [java, javap] + jars):
        parser.error("Missing JDK or pinned existing compiler/JUnit/Gson jars")
    output.mkdir(parents=True)
    classes = output / "classes"
    classes.mkdir()
    package = "com/example/persona/"
    main_root = "app/src/main/java/" + package
    test_root = "app/src/test/java/" + package
    paths = [
        main_root + "domain/model/Memory.kt", main_root + "domain/model/Message.kt",
        main_root + "core/memory/MemoryExtraction.kt", main_root + "core/memory/MemorySummary.kt",
        test_root + "core/memory/MemoryExtractionTest.kt", test_root + "core/memory/MemorySummaryTest.kt",
    ]
    benchmark = "tools/persona-memory-benchmark/extractionBenchmark.kt"
    paths += [benchmark, "tools/persona-memory-benchmark/extraction_samples.jsonl"]
    manifest = {
        "source_sha256": {}, "dependency_sha256": {p.name: sha256(p) for p in jars},
        "commands": [], "android_build": "unrun", "device_mnn": "unrun",
        "compiler": "Kotlin 2.0.21", "jvm_target": "11", "junit": "4.13.2",
        "java_sha256": sha256(java), "javap_sha256": sha256(javap),
    }
    for relative in paths:
        source = repo / relative
        copied = output / "source" / relative
        copied.parent.mkdir(parents=True, exist_ok=True)
        copied.write_bytes(source.read_bytes())
        manifest["source_sha256"][relative] = sha256(copied)
    for source in Path(__file__).parent.glob("extraction*"):
        if source.is_file():
            manifest.setdefault("tool_sha256", {})[source.name] = sha256(source)
    manifest_path = output / "manifest.json"

    def execute(command, log):
        manifest["commands"].append([str(value) for value in command])
        try:
            result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                    text=True, timeout=120, cwd=output)
            text, code = result.stdout, result.returncode
        except subprocess.TimeoutExpired:
            text, code = "Timed out after 120 seconds\n", 124
        (output / log).write_text(text)
        print(text, end="", flush=True)
        manifest[log + "_exit"] = code
        manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
        return code

    classpath = os.pathsep.join(str(path) for path in jars)
    runtime = str(classes) + os.pathsep + classpath
    print("Output " + str(output), flush=True)
    execute([str(java), "-version"], "java.log")
    sources = [str(output / "source" / relative) for relative in paths if relative.endswith(".kt")]
    code = execute([
        str(java), "-cp", classpath, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", "-jvm-target", "11", "-classpath", classpath,
        "-d", str(classes), *sources,
    ], "compile.log")
    if code == 0:
        code = execute([
            str(java), "-cp", runtime, "org.junit.runner.JUnitCore",
            "com.example.persona.core.memory.MemoryExtractionTest",
            "com.example.persona.core.memory.MemorySummaryTest",
        ], "junit.log")
        api_code = execute([
            str(javap), "-public", "-classpath", runtime,
            "com.example.persona.core.memory.MemoryExtraction",
            "com.example.persona.core.memory.MemoryExtraction$Suggestion",
            "com.example.persona.core.memory.MemorySummary",
            "com.example.persona.core.memory.MemorySummary$Source",
            "com.example.persona.core.memory.MemorySummary$Result",
        ], "api.log")
        code = code or api_code
    if code == 0:
        code = execute([
            str(java), "-cp", runtime, "com.example.persona.benchmark.ExtractionBenchmarkKt",
            str(output / "source/tools/persona-memory-benchmark/extraction_samples.jsonl"),
            str(output / "benchmark.json"),
        ], "benchmark.log")
    manifest["sources_changed_during_run"] = [
        relative for relative, digest in manifest["source_sha256"].items()
        if sha256(repo / relative) != digest
    ]
    if manifest["sources_changed_during_run"]:
        print("Sources changed during verification. Use the frozen output snapshot.", flush=True)
        code = code or 1
    manifest["artifact_sha256"] = {
        name: sha256(output / name) for name in ["compile.log", "junit.log", "api.log", "benchmark.json"]
        if (output / name).exists()
    }
    manifest["exit"] = code
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
    print("Exit " + str(code), flush=True)
    return code


if __name__ == "__main__":
    sys.exit(main())
