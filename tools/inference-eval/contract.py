"""Strict validator for the current JournalJson.export wire format."""

import hashlib
import json
import re
from pathlib import Path

INT_MAX = 2**31 - 1
LONG_MAX = 2**63 - 1
MAX_BYTES = 512 * 1024
DIGEST = re.compile(r"[0-9a-fA-F]{64}\Z")
ENUMS = {
    "task": ("CHAT", "MEMORY_EXTRACTION"),
    "backend": ("MNN", "CLOUD"),
    "outcome": ("COMPLETED", "FAILED", "STOPPED", "CANCELLED", "ABANDONED"),
    "contractStatus": ("NOT_EVALUATED", "ACCEPTED", "REJECTED"),
    "trigger": ("INITIAL", "RUNTIME_FALLBACK", "CONTRACT_FALLBACK"),
    "errorType": ("NOT_CONFIGURED", "HTTP", "NETWORK", "MODEL_NOT_READY",
                  "MODEL_LOAD_FAILED", "MODEL_RUNTIME", "EMPTY_RESPONSE",
                  "INPUT_BUDGET_EXCEEDED", "UNKNOWN"),
    "stopReason": ("USER_REQUESTED", "SESSION_REPLACED", "ENGINE_RELEASED",
                   "REPETITION_LOOP"),
}
NUMBERS = {
    "attempt": INT_MAX, "startedAtEpochMs": LONG_MAX, "totalLatencyMs": LONG_MAX,
    "firstTokenLatencyMs": LONG_MAX, "outputTokens": INT_MAX,
    "nativeInputTokens": LONG_MAX, "nativeOutputTokens": LONG_MAX,
    "prefillMicros": LONG_MAX, "decodeMicros": LONG_MAX, "samplingMicros": LONG_MAX,
}
NULLABLE = {
    "modelFingerprint", "firstTokenLatencyMs", "outputTokens", "nativeInputTokens",
    "nativeOutputTokens", "prefillMicros", "decodeMicros", "samplingMicros",
    "errorType", "stopReason",
}
FIELDS = set(ENUMS) | set(NUMBERS) | {"requestHash", "modelFingerprint"}


class Invalid(ValueError):
    pass


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def _reject_number(_):
    raise Invalid("JSON requires integer numbers; nonfinite values are forbidden")


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise Invalid("duplicate JSON object field")
        result[key] = value
    return result


def read_json(path):
    with Path(path).open("rb") as stream:
        raw = stream.read(MAX_BYTES + 1)
    if len(raw) > MAX_BYTES:
        raise Invalid("input exceeds 512 KiB")
    try:
        data = json.loads(raw.decode("utf-8"), object_pairs_hook=_unique_object,
                          parse_constant=_reject_number, parse_float=_reject_number)
    except (ValueError, UnicodeError, RecursionError):
        raise Invalid("invalid JSON document (values withheld)") from None
    return data, {"sha256": sha256(raw), "bytes": len(raw)}


def validate_export(rows):
    if type(rows) is not list:
        raise Invalid("export must be a JSON array")
    seen = set()
    tasks = {}
    for index, row in enumerate(rows):
        prefix = f"record {index}: "
        if type(row) is not dict or set(row) != FIELDS:
            raise Invalid(prefix + "expected exactly the journal export fields")
        for field, value in row.items():
            if value is None and field in NULLABLE:
                continue
            if field in NUMBERS:
                if type(value) is not int or not 0 <= value <= NUMBERS[field]:
                    raise Invalid(prefix + field + " outside Kotlin integer bounds")
            elif field in ENUMS:
                if type(value) is not str or value not in ENUMS[field]:
                    raise Invalid(prefix + field + " has invalid enum")
            elif type(value) is not str or not DIGEST.fullmatch(value):
                raise Invalid(prefix + field + " requires 64 hex characters")
        key = (row["requestHash"], row["attempt"])
        if key in seen:
            raise Invalid(prefix + "duplicate attempt identity")
        seen.add(key)
        request = row["requestHash"]
        if request in tasks and tasks[request] != row["task"]:
            raise Invalid(prefix + "inconsistent task within logical request")
        tasks[request] = row["task"]
    return rows


def validate_metadata(data):
    if type(data) is not dict or set(data) != {"label", "workload_sha256", "source_revision"}:
        raise Invalid("metadata requires exactly label, workload_sha256, source_revision")
    if type(data["label"]) is not str or not re.fullmatch(r"[A-Za-z0-9._-]{1,80}", data["label"]):
        raise Invalid("metadata label requires 1-80 ASCII letters, digits, dot, underscore or dash")
    if type(data["workload_sha256"]) is not str or not DIGEST.fullmatch(data["workload_sha256"]):
        raise Invalid("metadata workload_sha256 requires 64 hex characters")
    if type(data["source_revision"]) is not str or not re.fullmatch(r"(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})", data["source_revision"]):
        raise Invalid("metadata source_revision requires full 40 or 64 hex revision")
    return data
