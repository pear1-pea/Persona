"""Descriptive statistics only; no model routing or semantic quality evaluation."""

from collections import Counter, defaultdict

from contract import ENUMS

GROUP_FIELDS = ("task", "backend", "trigger")
METRICS = (
    "totalLatencyMs", "firstTokenLatencyMs", "outputTokens", "nativeInputTokens",
    "nativeOutputTokens", "prefillMicros", "decodeMicros", "samplingMicros",
)


def distribution(values, total):
    ordered = sorted(values)
    n = len(ordered)
    return {
        "n": n, "missing_or_undefined_n": total - n,
        "percentile_method": "nearest-rank: sorted[ceil(p*n)-1]",
        "p50": ordered[(50 * n + 99) // 100 - 1] if n else None,
        "p95": ordered[(95 * n + 99) // 100 - 1] if n else None,
        "mean": sum(ordered) / n if n else None,
    }


def counts(rows, field):
    counter = Counter(row[field] for row in rows)
    n = len(rows)
    return {value: {"count": counter[value], "denominator": n,
                    "rate": counter[value] / n if n else None}
            for value in ENUMS[field]}


def attempt_stats(rows):
    n = len(rows)
    metrics = {field: distribution([row[field] for row in rows if row[field] is not None], n)
               for field in METRICS}
    rates = [row["outputTokens"] * 1000 / row["totalLatencyMs"] for row in rows
             if row["outputTokens"] is not None and row["totalLatencyMs"] > 0]
    metrics["overall_output_tokens_per_second"] = distribution(rates, n)
    return {
        "attempt_count": n,
        "logical_requests_touched": len({row["requestHash"] for row in rows}),
        "execution_outcome": counts(rows, "outcome"),
        "contract_status": counts(rows, "contractStatus"),
        "semantic_quality": "not_evaluated",
        "metrics": metrics,
        "model_fingerprints": dict(sorted(Counter(
            row["modelFingerprint"] or "unknown" for row in rows).items())),
    }


def grouped(rows):
    groups = defaultdict(list)
    for row in rows:
        groups[tuple(row[field] for field in GROUP_FIELDS)].append(row)
    return groups


def request_profiles(rows):
    requests = defaultdict(list)
    for row in rows:
        requests[row["requestHash"]].append(row)
    return requests


def summarize(rows):
    requests = request_profiles(rows)
    last = [max(attempts, key=lambda row: row["attempt"]) for attempts in requests.values()]
    starts = [row["startedAtEpochMs"] for row in rows]
    attempts = [row["attempt"] for row in rows]
    return {
        "scope": {
            "record_indices": {"start_inclusive": 0, "end_exclusive": len(rows)},
            "startedAtEpochMs": {"min": min(starts) if starts else None,
                                 "max": max(starts) if starts else None},
            "attempt_index": {"min": min(attempts) if attempts else None,
                              "max": max(attempts) if attempts else None},
            "coverage": "all exported rows; retention may omit earlier or later attempts",
        },
        "attempts": attempt_stats(rows),
        "logical_requests": {
            "count": len(requests),
            "observed_attempts_per_request": dict(sorted(Counter(
                str(len(items)) for items in requests.values()).items())),
            "last_observed_execution_outcome": counts(last, "outcome"),
            "last_observed_contract_status": counts(last, "contractStatus"),
            "semantic_quality": "not_evaluated",
            "scope": "highest observed attempt index; not proof of complete request history",
        },
        "groups": [dict(zip(GROUP_FIELDS, key), statistics=attempt_stats(items))
                   for key, items in sorted(grouped(rows).items())],
        "group_request_counts": "non-additive: a request can touch multiple groups",
        "throughput_definition": "per-attempt outputTokens*1000/totalLatencyMs; overall, not decode",
    }


def coverage_profile(rows):
    # Compare whole request route shapes, without matching session hashes across runs.
    return Counter(
        tuple((row["task"], row["backend"], row["trigger"], row["attempt"])
              for row in sorted(items, key=lambda row: row["attempt"]))
        for items in request_profiles(rows).values()
    )


def observed_deltas(left, right, left_metadata, right_metadata):
    result = {
        "status": "unmatched", "reason": None, "groups": None,
        "direction": "right minus left",
        "interpretation": "unpaired descriptive observations; no causality or quality ranking",
        "semantic_quality": "not_evaluated",
    }
    if left_metadata["workload_sha256"].lower() != right_metadata["workload_sha256"].lower():
        result["reason"] = "workload_sha256_mismatch"
        return result
    if not left or not right:
        result["reason"] = "empty_export"
        return result
    if coverage_profile(left) != coverage_profile(right):
        result["reason"] = "full_group_or_request_attempt_coverage_mismatch; no partial comparison"
        return result
    left_groups, right_groups = grouped(left), grouped(right)
    deltas = []
    for key in sorted(left_groups):
        a, b = attempt_stats(left_groups[key]), attempt_stats(right_groups[key])
        metric_deltas = {}
        for metric, av in a["metrics"].items():
            bv = b["metrics"][metric]
            complete = av["n"] == bv["n"] == a["attempt_count"]
            metric_deltas[metric] = {
                "left_n": av["n"], "right_n": bv["n"],
                "status": "observed" if complete else "not_comparable",
                "reason": None if complete else "missing_or_undefined_samples; no subset delta",
                "delta": {stat: bv[stat] - av[stat] for stat in ("mean", "p50", "p95")}
                if complete else None,
            }
        entry = dict(zip(GROUP_FIELDS, key), attempt_denominator=a["attempt_count"],
                     metrics=metric_deltas)
        for field in ("execution_outcome", "contract_status"):
            entry[field] = {
                value: {"count_delta": b[field][value]["count"] - a[field][value]["count"],
                        "rate_delta": b[field][value]["rate"] - a[field][value]["rate"]}
                for value in a[field]
            }
        deltas.append(entry)
    result.update(status="matched", reason="same declared workload and full observed coverage",
                  groups=deltas)
    return result
