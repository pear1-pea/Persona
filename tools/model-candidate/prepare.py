#!/usr/bin/env python3
"""Prepare pinned synthetic references; no Android build or model execution."""
import argparse
import importlib.metadata
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

from references import CONTEXT_WINDOW, DATASET_SHA256, digest, no_links, read_json, reference_case, verify_snapshot

ROOT = Path(__file__).resolve().parents[2]
SOURCE_LOCK = Path(__file__).with_name("source-lock.json")
CORE = ROOT / "app/src/main/java/com/example/persona"
SOURCES = [CORE / name for name in (
    "domain/model/Memory.kt", "core/memory/MemoryExtraction.kt", "core/ai/Backend.kt",
    "core/ai/ChatMessage.kt", "core/ai/GenerationParams.kt", "core/ai/InstalledModel.kt",
    "core/ai/prompt/ContextPlanner.kt", "core/ai/prompt/PromptHistoryTrimmer.kt",
    "core/ai/prompt/CloudContextPlanner.kt", "core/ai/prompt/Tokenizer.kt")]
SOURCES.append(Path(__file__).with_name("kotlin") / "ExportProbeCases.kt")


def android_snapshot():
    sys.path.insert(0, str(ROOT / "tools/persona-eval"))
    from prepare_device import source_snapshot, snapshot_digest
    files = source_snapshot(ROOT)
    return files, "sha256:" + snapshot_digest(files)


def run_logged(command, output, label):
    with (output / (label + ".log")).open("wb") as log:
        result = subprocess.run(list(map(str, command)), cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, timeout=180)
    if result.returncode:
        raise ValueError("Reference subprocess failed")


def export_cases(args, output):
    java = no_links(args.java_home.resolve(strict=True) / "bin/java")
    dependencies = sorted(no_links(args.deps).glob("*.jar"))
    if not java.is_file() or not dependencies:
        raise ValueError("Missing explicit compiler dependencies")
    files = []
    for source in SOURCES:
        relative = source.relative_to(ROOT)
        target = output / "source" / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(no_links(source), target)
        files.append(target)
    classes = output / "classes"
    classes.mkdir()
    cp = os.pathsep.join(str(no_links(p)) for p in dependencies)
    compile_command = [java, "-cp", cp, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
                       "-jvm-target", "11", "-classpath", cp, "-d", classes, *files]
    run_logged(compile_command, output, "compile")
    command = [java, "-cp", str(classes) + os.pathsep + cp, "com.example.persona.candidate.ExportProbeCasesKt",
               ROOT / "tools/persona-memory-benchmark/extraction_samples.jsonl", output / "kotlin-cases.json"]
    run_logged(command, output, "export")
    return {"commands": [list(map(str, compile_command)), list(map(str, command))],
            "sources": {str(p.relative_to(ROOT)): digest(p) for p in SOURCES},
            "dependencies": {p.name: digest(p) for p in dependencies}}


def prepare(args):
    output = no_links(args.output)
    if output.exists() or not output.parent.is_dir() or any(output.is_relative_to(no_links(p)) for p in [args.snapshot, CORE]):
        raise ValueError("Output must be a fresh independent directory")
    lock = read_json(SOURCE_LOCK)
    receipt = no_links(args.package_receipt)
    if digest(receipt) != lock["packageReceiptSha256"]:
        raise ValueError("Fixed package receipt mismatch")
    if digest(ROOT / "tools/persona-memory-benchmark/extraction_samples.jsonl") != DATASET_SHA256:
        raise ValueError("Fixed dataset changed")
    output.mkdir()
    try:
        before, revision = android_snapshot()
        snapshot_report = verify_snapshot(args.snapshot, lock)
        versions = {name: importlib.metadata.version(name) for name in ("transformers", "tokenizers", "Jinja2")}
        if versions != {"transformers": "4.51.3", "tokenizers": "0.21.4", "Jinja2": "3.1.6"}:
            raise ValueError("Reference library versions differ from tested host")
        os.environ.update(HF_HUB_OFFLINE="1", TRANSFORMERS_OFFLINE="1", HF_HUB_DISABLE_IMPLICIT_TOKEN="1")
        from transformers import AutoTokenizer
        tokenizer = AutoTokenizer.from_pretrained(str(args.snapshot), local_files_only=True, trust_remote_code=False)
        exported = export_cases(args, output)
        cases = read_json(output / "kotlin-cases.json")
        if cases["extractionVersion"] != "memory-extraction-v1" or len(cases["cases"]) != 9:
            raise ValueError("Unexpected production extraction contract")
        references, summaries = zip(*(reference_case(tokenizer, row) for row in cases["cases"]))
        if args.package is not None:
            from package_contract import validate_package
            package_report = validate_package(args.package, receipt)
            package_status = "full_files_checked_not_device_execution"
        else:
            package_report = None
            package_status = "receipt_only_model_files_not_checked"
        bundle = {"formatVersion": 1, "kind": "qwen3-native-probe-v1", "sourceRevision": revision,
                  "extractionVersion": cases["extractionVersion"], "datasetSha256": DATASET_SHA256,
                  "packageReceiptSha256": digest(receipt), "contextWindow": CONTEXT_WINDOW, "cases": references}
        after, _ = android_snapshot()
        if before != after:
            raise ValueError("Source changed during reference preparation")
        (output / "receipt.json").write_bytes(receipt.read_bytes())
        (output / "bundle.json").write_text(json.dumps(bundle, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        summary = {"status": "references_prepared", "modelExecution": "not_run", "androidBuild": "not_run",
                   "packageStatus": package_status, "packageReport": package_report, "snapshot": snapshot_report,
                   "sourceRevision": revision, "bundleSha256": digest(output / "bundle.json"), "cases": summaries,
                   "semanticQuality": "not_evaluated", "candidateAdopted": False}
        (output / "reference-summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
        provenance = {"python": sys.version, "versions": versions, "sourceLockSha256": digest(SOURCE_LOCK),
                      "androidSourceSnapshot": before, "kotlin": exported,
                      "toolSources": {p.name: digest(p) for p in Path(__file__).parent.glob("*.py")}}
        (output / "provenance.json").write_text(json.dumps(provenance, indent=2) + "\n", encoding="utf-8")
        return summary
    except Exception as error:
        (output / "failure.json").write_text(json.dumps({"status": "failed", "errorClass": type(error).__name__,
                                                        "modelExecution": "not_run"}) + "\n", encoding="utf-8")
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--snapshot", required=True, type=Path)
    parser.add_argument("--package-receipt", required=True, type=Path)
    parser.add_argument("--deps", required=True, type=Path)
    parser.add_argument("--java-home", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--package", type=Path)
    mode.add_argument("--receipt-only", action="store_true")
    args = parser.parse_args()
    try:
        prepare(args)
    except Exception:
        print("Candidate reference preparation failed; inspect private run artifacts", file=sys.stderr)
        return 2
    print("Nine synthetic references prepared; Android and candidate quality not run")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
