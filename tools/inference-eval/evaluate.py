#!/usr/bin/env python3
"""Offline, stdlib-only inference telemetry journal analysis."""

import argparse
import json
from pathlib import Path
import sys

from contract import Invalid, read_json, sha256, validate_export, validate_metadata
from metrics import observed_deltas, summarize

TOOL = Path(__file__).resolve().parent
ROOT = TOOL.parent.parent
AI = "app/src/main/java/com/example/persona/core/ai/"
CONTRACT_SOURCES = (
    AI + "telemetry/InferenceTelemetry.kt", AI + "telemetry/JournalJson.kt",
    AI + "telemetry/FileInferenceJournal.kt", AI + "telemetry/JournalCommit.kt",
    AI + "InferenceAttemptTracker.kt", AI + "BufferedInferenceTelemetry.kt",
    AI + "Backend.kt", AI + "GenerationEvent.kt",
)


def source_hashes():
    paths = sorted(TOOL.glob("*.py"))
    paths += [ROOT / name for name in CONTRACT_SOURCES]
    return {str(path.relative_to(ROOT)): sha256(path.read_bytes()) for path in paths}


def load_export(path):
    rows, provenance = read_json(path)
    return validate_export(rows), provenance


def load_metadata(path):
    metadata, provenance = read_json(path)
    return validate_metadata(metadata), provenance


def write_report(output, report):
    encoded = json.dumps(report, indent=2, sort_keys=True, allow_nan=False) + "\n"
    directory = Path(output)
    # mkdir refuses existing directories, files and symlinks, even empty ones.
    directory.mkdir()
    try:
        with (directory / "report.json").open("x", encoding="utf-8") as stream:
            stream.write(encoded)
    except OSError:
        # Do not remove a directory containing any concurrent writer's files.
        try:
            directory.rmdir()
        except OSError:
            pass
        raise


def parser():
    cli = argparse.ArgumentParser(description=__doc__)
    commands = cli.add_subparsers(dest="command", required=True)
    summary = commands.add_parser("summarize", help="summarize all rows of one export")
    summary.add_argument("export", type=Path)
    summary.add_argument("--output", type=Path, required=True, help="new directory; parent must exist")
    compare = commands.add_parser("compare", help="compare two declared workload runs")
    compare.add_argument("left_export", type=Path)
    compare.add_argument("right_export", type=Path)
    compare.add_argument("--left-metadata", type=Path, required=True)
    compare.add_argument("--right-metadata", type=Path, required=True)
    compare.add_argument("--output", type=Path, required=True, help="new directory; parent must exist")
    return cli


def main(argv=None):
    args = parser().parse_args(argv)
    try:
        report = {
            "format_version": 1, "command": args.command, "python_version": sys.version.split()[0],
            "semantic_quality": "not_evaluated",
            "limitations": [
                "Not a human or LLM quality judgment; contract acceptance is structural only.",
                "Retained export only; no guarantee of a complete workload or request history.",
                "Descriptive observations only; no causal effect or better-quality claim.",
                "Missing values and zero-duration throughput are never imputed as zero.",
            ],
            "provenance": {"source_sha256": source_hashes(), "inputs": {}},
            "parameters": {key: str(value) if isinstance(value, Path) else value
                           for key, value in vars(args).items()},
        }
        inputs = report["provenance"]["inputs"]
        if args.command == "summarize":
            rows, inputs["export"] = load_export(args.export)
            report["summary"] = summarize(rows)
        else:
            left, inputs["left_export"] = load_export(args.left_export)
            right, inputs["right_export"] = load_export(args.right_export)
            lm, inputs["left_metadata"] = load_metadata(args.left_metadata)
            rm, inputs["right_metadata"] = load_metadata(args.right_metadata)
            report.update(left={"metadata": lm, "summary": summarize(left)},
                          right={"metadata": rm, "summary": summarize(right)},
                          observed_deltas=observed_deltas(left, right, lm, rm))
        write_report(args.output, report)
    except Invalid as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    except OSError:
        print("error: cannot read sources/inputs or create a new output directory", file=sys.stderr)
        return 2
    print("report saved; semantic quality not_evaluated")
    return 0


if __name__ == "__main__":
    sys.exit(main())
