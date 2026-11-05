#!/usr/bin/env python3
"""Compare the production Kotlin ranker with recency and host SQLite FTS4 experiments."""
import argparse
import hashlib
import json
import os
import platform
from pathlib import Path
import sqlite3
import subprocess
from datetime import datetime, timezone


ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent


def execute(command, output, name):
    result = subprocess.run(command, cwd=ROOT, text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, timeout=120)
    (output / name).write_text(result.stdout)
    if result.returncode:
        print(result.stdout)
        raise SystemExit(result.returncode)
    return result.stdout


def fts_ids(case, pretokenized):
    if not case["query"].strip():
        return case["recent"]
    with sqlite3.connect(":memory:") as db:
        db.execute("CREATE VIRTUAL TABLE facts USING fts4(id, content, tokenize=unicode61)")
        for row in case["candidates"]:
            content = " ".join(row["terms"]) if pretokenized else row["content"]
            db.execute("INSERT INTO facts(id, content) VALUES (?, ?)", (row["id"], content))
        terms = case["queryTerms"] if pretokenized else [case["query"]]
        terms = [term for term in terms if term.strip()]
        if not terms:
            return []
        expression = " OR ".join('"' + term.replace('"', '""') + '"' for term in terms)
        matches = {row[0] for row in db.execute(
            "SELECT id FROM facts WHERE content MATCH ?", (expression,))}
        selected = []
        characters = 0
        for row in [row for row in case["candidates"] if row["id"] in matches][:4]:
            # Count JVM UTF-16 code units for the production content budget.
            size = len(row["content"].encode("utf-16-le")) // 2
            if characters + size <= 1200:
                selected.append(row["id"])
                characters += size
        return selected


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--samples", type=Path, default=HERE / "retrieval_cases.json")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--deps", type=Path, default=ROOT / "build/verification/p2-mac/deps")
    parser.add_argument("--warmup", type=int, default=200)
    parser.add_argument("--repetitions", type=int, default=1000)
    parser.add_argument("--java-home", type=Path, default=Path(os.environ.get(
        "JAVA_HOME", "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home")))
    args = parser.parse_args()
    if args.warmup < 0 or args.repetitions < 1:
        parser.error("--warmup must be nonnegative and --repetitions must be positive")
    output = (args.output or ROOT / "build/verification/p3-retrieval-agent" / (
        "benchmark-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + str(os.getpid()))).resolve()
    args.deps = args.deps.resolve()
    args.java_home = args.java_home.resolve()
    output.mkdir(parents=True, exist_ok=False)
    sources = [
        ROOT / "app/src/main/java/com/example/persona/core/memory/MemoryRanker.kt",
        ROOT / "app/src/main/java/com/example/persona/domain/model/Memory.kt",
        HERE / "RetrievalBenchmark.kt",
    ]
    dependencies = sorted(args.deps.glob("*.jar"))
    if not dependencies:
        raise SystemExit("Existing Kotlin/JUnit runner jars are required through --deps")
    classpath = os.pathsep.join(map(str, dependencies))
    classes = output / "classes"
    classes.mkdir()
    snapshot_sources = []
    hashes = {}
    for source in sources + [args.samples.resolve()]:
        data = source.read_bytes()
        copied = output / source.name
        copied.write_bytes(data)
        hashes[str(source)] = hashlib.sha256(data).hexdigest()
        if source.suffix == ".kt":
            snapshot_sources.append(copied)
    java = str(args.java_home / "bin/java")
    java_version = execute([java, "-version"], output, "java-version.log").strip()
    execute([java, "-cp", classpath, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib",
             "-no-reflect", "-jvm-target", "11", "-classpath", classpath, "-d", str(classes)] +
            list(map(str, snapshot_sources)), output, "compile.log")
    cases = json.loads(execute([java, "-cp", str(classes) + os.pathsep + classpath,
        "com.example.persona.benchmark.RetrievalBenchmarkKt", str(output / args.samples.name),
        str(args.warmup), str(args.repetitions)],
        output, "kotlin-results.json"))
    for case in cases:
        case["fts4_unicode61"] = fts_ids(case, False)
        case["fts4_kotlin_terms"] = fts_ids(case, True)
    strategies = ("recent", "ranker", "fts4_unicode61", "fts4_kotlin_terms")
    counts = {strategy: sum(case[strategy] == case["expected"] for case in cases) for strategy in strategies}
    report = {
        "scope": "Synthetic host Kotlin ranker comparison. FTS4 is Python SQLite only. "
                 "Candidate permissions are fixture filtering, without Room, Android or concurrent writes.",
        "host": {"platform": platform.platform(), "machine": platform.machine(), "java": java_version},
        "timing_scope": "Per-case sequential warmup followed by repeated MemoryRanker.rank calls. "
                        "System.nanoTime deltas include clock overhead. Excludes fixture filtering, budget, "
                        "Room IO and generation. Small fixed corpus only, without device performance claims.",
        "deferred_experiments": [{"name": "embedding", "status": "deferred",
            "revisit_when": "A larger reviewed Chinese corpus exposes lexical recall failures in fixed cases"}],
        "sqlite_version": sqlite3.sqlite_version, "source_sha256": hashes,
        "dependency_sha256": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in dependencies},
        "exact_ordered_matches": counts, "case_count": len(cases), "cases": cases,
        "changed_after_snapshot": [str(p) for p in sources + [args.samples.resolve()]
            if hashlib.sha256(p.read_bytes()).hexdigest() != hashes[str(p)]],
    }
    (output / "report.json").write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
    print("Actual Kotlin ranker comparison", counts, "cases", len(cases))
    print("Host JVM ranker timing; warmup", args.warmup, "measured repetitions", args.repetitions, "per case")
    for case in cases:
        timing = case["host_timings"]
        print(case["id"], "candidates", timing["candidate_count"], "p50_ns", timing["p50_ns"],
              "p95_ns", timing["p95_ns"], "p99_ns", timing["p99_ns"])
    print("Report", output / "report.json")
    return int(counts["ranker"] != len(cases) or bool(report["changed_after_snapshot"]))


if __name__ == "__main__":
    raise SystemExit(main())
