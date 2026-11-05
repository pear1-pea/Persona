#!/usr/bin/env python3
"""Validate fixtures or compare instrumented application observations offline."""

import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import sys

from oracle import evaluate
from schema import ROOT, VERSION, load_suite, require


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def suite_digest():
    digest = hashlib.sha256()
    for split in ("dev", "heldout"):
        digest.update((ROOT / "datasets" / f"{split}.jsonl").read_bytes())
    return digest.hexdigest()


def compare(cases, observations):
    require(observations["dataset_version"] == VERSION, "observation version mismatch")
    require(observations["dataset_sha256"] == suite_digest(), "dataset digest mismatch")
    require(observations["evidence_kind"] == "application_instrumentation",
            "reference/synthetic observations cannot establish application conformance")
    require(all(isinstance(observations[k], str) and observations[k].strip()
                for k in ("implementation_revision", "adapter_version", "evidence_ref")),
            "application revision, adapter version and evidence ref are required")
    expected_cases = {c["id"]: c for c in cases if c["track"] == "deterministic"}
    rows = observations["observations"]
    require(isinstance(rows, list), "observations must be a list")
    require(len(rows) == len(expected_cases)
            and {r["case_id"] for r in rows} == expected_cases.keys(),
            "missing, duplicate or out-of-split observations")
    results = []
    for row in rows:
        if row["status"] == "unsupported":
            require(row["actual"] is None, "unsupported actual must be null")
            reason = row.get("reason")
            require(isinstance(reason, str) and reason.strip(),
                    "unsupported reason must be a nonempty string")
            results.append({"case_id": row["case_id"], "status": "unsupported",
                            "reason": reason})
            continue
        require(row["status"] == "observed", f"{row['case_id']}: observation not run")
        actual = row["actual"]
        require(isinstance(actual, dict) and set(actual) == set(expected_cases[row["case_id"]]["expected"]),
                "observation fields must include both boundaries and rejected writes")
        for values in actual.values():
            require(isinstance(values, list) and all(isinstance(v, str) for v in values)
                    and len(values) == len(set(values)), "invalid observed refs")
        expected = expected_cases[row["case_id"]]["expected"]
        passed = all(sorted(actual[key]) == sorted(expected[key]) for key in expected)
        results.append({"case_id": row["case_id"], "status": "pass" if passed else "fail"})
    return results


def template(cases):
    return {
        "dataset_version": VERSION, "dataset_sha256": suite_digest(),
        "evidence_kind": "application_instrumentation",
        "implementation_revision": "", "adapter_version": "", "evidence_ref": "",
        "observations": [{"case_id": c["id"], "status": "not_run", "actual": None}
                         for c in cases if c["track"] == "deterministic"],
        "llm_quality": "not_run",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("validate", "reference", "template", "compare"))
    parser.add_argument("--split", choices=("dev", "heldout", "all"), default="dev")
    parser.add_argument("--observations", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        suite = load_suite()
        config = read_json(ROOT / "config" / "baseline.v2.json")
        require(config["dataset_version"] == VERSION, "config dataset version mismatch")
        cases = [c for c in suite if args.split == "all" or c["split"] == args.split]
        results = []
        if args.command == "validate":
            report = {"dataset_version": VERSION, "dataset_sha256": suite_digest(),
                      "schema": "valid", "total_cases": len(suite),
                      "split_counts": dict(Counter(c["split"] for c in suite)),
                      "track_counts": dict(Counter(c["track"] for c in suite)),
                      "llm_quality": "not_run", "application_conformance": "not_run"}
        elif args.command == "template":
            report = template(cases)
        else:
            observations = None
            if args.command == "reference":
                results = [{"case_id": c["id"], "status": "pass" if evaluate(c) == c["expected"]
                            else "fail"} for c in cases if c["track"] == "deterministic"]
            else:
                require(args.observations is not None, "--observations is required")
                observations = read_json(args.observations)
                results = compare(cases, observations)
            report = {"dataset_version": VERSION, "dataset_sha256": suite_digest(),
                      "config_version": config["config_version"], "split": args.split,
                      "run_at": datetime.now(timezone.utc).isoformat(),
                      "evidence_kind": "reference_contract" if args.command == "reference"
                      else "application_instrumentation_comparison",
                      "results": results, "counts": dict(Counter(r["status"] for r in results)),
                      "application_conformance": "not_run" if args.command == "reference"
                      else ("reported_observations_incomplete"
                            if any(r["status"] == "unsupported" for r in results)
                            else "reported_observations_only"), "llm_quality": "not_run"}
            if observations is not None:
                report["application_evidence"] = {
                    key: observations[key] for key in (
                        "implementation_revision", "adapter_version", "evidence_ref")}
                if "adapter_scope" in observations:
                    report["application_evidence"]["adapter_scope"] = observations["adapter_scope"]
                report["observations_sha256"] = hashlib.sha256(
                    args.observations.read_bytes()).hexdigest()
        output = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
        if args.output:
            # Never silently overwrite an earlier experiment or a tracked fixture.
            with args.output.open("x", encoding="utf-8") as handle:
                handle.write(output)
        print(output, end="")
        return 1 if any(r["status"] in {"fail", "unsupported"} for r in results) else 0
    except (ValueError, KeyError, TypeError, AttributeError, OSError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
