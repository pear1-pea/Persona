#!/usr/bin/env python3
"""Validate submitted human annotations without inventing scores or approving release."""

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re

from schema import load_suite, require


def check(template, annotations, mapping):
    require(template.get("protocol") == annotations.get("protocol") == "human-pairwise-v1", "Review protocol mismatch")
    require(template.get("input_sha256") == annotations.get("input_sha256"), "Review belongs to a different model export")
    require(template.get("split") == annotations.get("split"), "Review split mismatch")
    mode = template.get("comparison_mode", "blind_pairwise")
    require(annotations.get("comparison_mode", "blind_pairwise") == mode, "Review mode mismatch")
    if "packet_id" in template:
        packet_id = template["packet_id"]
        require(isinstance(packet_id, str) and re.fullmatch(r"[0-9a-f]{32}", packet_id), "Invalid review packet ID")
        require(annotations.get("packet_id") == packet_id, "Review packet mismatch")
        require(isinstance(mapping, dict) and mapping.get("packet_id") == packet_id, "Blinding packet mismatch")
        mapping = mapping.get("mapping")
    else:
        # Frozen legacy receipts use an unbound mapping list.
        require("packet_id" not in annotations and isinstance(mapping, list), "Unexpected review packet binding")
    require(isinstance(mapping, list), "Invalid packet mapping")
    original = {row["pair_id"]: row for row in template["cases"]}
    require(len(original) == len(template["cases"]), "Duplicate template item")
    items = annotations.get("cases")
    require(isinstance(items, list), "Missing human items")
    keyed = {row["pair_id"]: row for row in items}
    require(len(keyed) == len(items) and set(keyed) == set(original), "Missing, duplicate or unexpected review item")
    private = {row["pair_id"]: row for row in mapping}
    require(len(private) == len(mapping) and set(private) == set(original), "Blinding map mismatch")
    missing, defects, disagreements, reviewers = [], [], [], set()
    selections = Counter()
    reviewed = 0
    by_case = {case["id"]: case for case in load_suite()}
    critical_tags = {"owner", "sharing", "local_only", "cloud", "deletion", "injection", "provenance"}

    def absent(item, field):
        missing.append({"item": item, "field": field})

    for item_id, expected in original.items():
        row = keyed[item_id]
        require(row.get("case_id") == expected["case_id"] and type(row.get("repetition")) is int
                and row["repetition"] == expected["repetition"], "Case/repetition changed")
        if expected.get("reviewable") is False:
            require(row.get("winner") is None, "An unsupported execution cannot receive a quality verdict")
            for field in ("must_checks", "must_not_violations", "dimension_scores"):
                require(row.get(field) == expected[field], "Unsupported executions must retain empty scores")
            continue
        before = len(missing)
        labels = set(expected["dimension_scores"])
        require(set(private[item_id]) == {"pair_id", *labels}, "Blinding labels mismatch")
        require(set(private[item_id][label] for label in labels) ==
                ({"candidate"} if mode == "single_configuration" else {"baseline", "candidate"}), "Invalid arm map")
        winner = row.get("winner")
        allowed = {"A", "fail", "unresolved"} if mode == "single_configuration" else {"A", "B", "tie", "both_fail", "unresolved"}
        if winner is None:
            absent(item_id, "winner")
        else:
            require(winner in allowed, "Invalid human selection")
            selections[winner] += 1
            if winner == "unresolved":
                disagreements.append(item_id)
                absent(item_id, "resolved_winner")
        for field in ("human_reviewer", "rationale"):
            value = row.get(field)
            if value is None or value == "":
                absent(item_id, field)
            else:
                require(isinstance(value, str) and bool(value.strip()), "Invalid human reviewer/rationale")
        if row.get("human_reviewer"):
            reviewers.add(row["human_reviewer"].strip())
        violation = False
        for field in ("must_checks", "must_not_violations", "dimension_scores"):
            supplied = row.get(field)
            require(isinstance(supplied, dict) and set(supplied) == labels, "Scoring labels changed")
            for label in labels:
                require(isinstance(supplied[label], dict) and set(supplied[label]) == set(expected[field][label]),
                        "Rubric criteria changed")
                for index, (criterion, value) in enumerate(supplied[label].items()):
                    if value is None:
                        absent(item_id, f"{field}.{label}.{index}")
                        continue
                    if field == "dimension_scores":
                        require(type(value) is int and 0 <= value <= 2, "Dimension score must be 0, 1 or 2")
                        failed = value < 2
                    else:
                        require(type(value) is bool, "Must/must-not annotations must be boolean")
                        failed = value is (field == "must_not_violations")
                    if failed:
                        defects.append({"item": item_id, "anonymous_arm": label, "field": field,
                                        "criterion": criterion, "value": value})
                        violation = violation or (field == "must_not_violations" and
                            bool(set(by_case[row["case_id"]]["tags"]) & critical_tags))
        if violation or winner == "unresolved":
            reviewer = row.get("second_reviewer")
            primary = (row.get("human_reviewer") or "").strip()
            if not isinstance(reviewer, str) or not reviewer.strip() or reviewer.strip() == primary:
                absent(item_id, "independent_second_reviewer")
        if len(missing) == before:
            reviewed += 1
    complete = not missing
    if complete:
        # Unblind only after the submitted annotations are complete.
        for defect in defects:
            defect["configuration"] = private[defect["item"]][defect["anonymous_arm"]]
    result = {"kind": "human_annotation_validation_v1", "input_sha256": template["input_sha256"],
            "comparison_mode": mode, "annotations_complete": complete, "reviewed_items": reviewed,
            "required_items": sum(row.get("reviewable") is not False for row in original.values()),
            "reviewers": sorted(reviewers), "missing_count": len(missing), "missing": missing,
            "human_selections": dict(selections), "unresolved_items": disagreements,
            "defects_reported_by_humans": defects, "automated_quality_judgment": False,
            "release_decision": "requires_owner_assessment" if complete else "pending_human_review"}
    if "packet_id" in template:
        result["packet_id"] = template["packet_id"]
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--template", type=Path, required=True)
    parser.add_argument("--annotations", type=Path, required=True)
    parser.add_argument("--mapping", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    load = lambda path: json.loads(path.read_text(encoding="utf-8-sig"))
    result = check(load(args.template), load(args.annotations), load(args.mapping))
    result["annotations_file_sha256"] = hashlib.sha256(args.annotations.read_bytes()).hexdigest()
    with args.output.open("x", encoding="utf-8") as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    print(json.dumps({key: result[key] for key in ("annotations_complete", "reviewed_items", "required_items", "missing_count", "release_decision")}))
    return int(not result["annotations_complete"])


if __name__ == "__main__":
    raise SystemExit(main())
