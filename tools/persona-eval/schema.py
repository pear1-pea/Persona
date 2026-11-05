"""Strict local fixture validation; no third-party packages or network calls."""

import json
from pathlib import Path

from oracle import identity, timestamp

ROOT = Path(__file__).resolve().parent
VERSION = "m5-zh-explicit-v2"
TAGS = {"owner", "persona", "sharing", "local_only", "cloud", "fallback",
        "revision", "deletion", "time", "unknown", "conflict", "provenance",
        "relevance", "cancel", "injection", "language", "style", "consistency",
        "repetition", "recall", "budget"}
# persona=None maps to Memory.personaId=null: explicit account-wide sharing.
# key/query_keys and save_source are evaluation metadata, never SQL predicates/columns.
MEMORY_FIELDS = {"ref", "memory_id", "owner", "persona", "key", "content",
                 "version", "deleted", "source_message_id", "save_source",
                 "created_at", "updated_at", "expires_at", "cloud_allowed", "kind"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def strings(values):
    return isinstance(values, list) and all(isinstance(v, str) and v for v in values)


def validate_memory(memory):
    require(set(memory) == MEMORY_FIELDS, "memory fields differ from M6 v2 contract")
    for key in ("ref", "memory_id", "owner", "key"):
        require(isinstance(memory[key], str) and bool(memory[key]), f"invalid {key}")
    require(isinstance(memory["content"], str), "invalid content")
    require(memory["persona"] is None or isinstance(memory["persona"], str)
            and bool(memory["persona"]), "invalid personaId")
    require(type(memory["version"]) is int and memory["version"] > 0, "invalid version")
    require(memory["kind"] in {"preference", "fact", "goal", "relationship"}, "invalid kind")
    require(type(memory["deleted"]) is bool and type(memory["cloud_allowed"]) is bool,
            "deletion/consent must be boolean")
    require(memory["source_message_id"] is None or isinstance(memory["source_message_id"], str)
            and bool(memory["source_message_id"]), "invalid source message ID")
    source = memory["save_source"]
    require(set(source) == {"mode", "confirmed", "owner", "persona", "is_from_user",
                            "status", "provenance"}, "invalid save source metadata")
    require(source["mode"] in {"manual", "message", "assistant_inference",
            "role_fiction", "mock_feed", "failed_output"}, "invalid source mode")
    require(type(source["confirmed"]) is bool and type(source["is_from_user"]) is bool,
            "source flags must be boolean")
    require(all(isinstance(source[k], str) and source[k] for k in
                ("owner", "persona", "status", "provenance")), "invalid source fields")
    for key in ("created_at", "updated_at"):
        timestamp(memory[key])
    require(timestamp(memory["created_at"]) <= timestamp(memory["updated_at"]),
            "revision before creation")
    if memory["expires_at"] is not None:
        timestamp(memory["expires_at"])


def validate_case(case, split):
    require(case["dataset_version"] == VERSION, "dataset version mismatch")
    require(case["split"] == split, "split mismatch")
    require(case["id"].startswith(split + "-"), "case id must identify split")
    require(case["track"] in {"deterministic", "llm_subjective"}, "invalid track")
    require(case["synthetic"] is True, "only synthetic fixtures are allowed")
    require(strings(case["tags"]) and case["tags"] and set(case["tags"]) <= TAGS,
            "invalid coverage tags")
    require(isinstance(case["family"], str) and case["family"], "missing split family")
    require(isinstance(case["title"], str) and case["title"], "missing title")
    context = case["context"]
    require(context["executor"] in {"local", "cloud"}, "invalid executor")
    require(all(isinstance(context[k], str) and context[k] for k in ("owner", "persona")),
            "invalid identity")
    require(strings(context["query_keys"]), "invalid query keys")
    timestamp(context["as_of"])
    profile = case["persona_profile"]
    require(isinstance(profile["personaBackstory"], str) and profile["personaBackstory"],
            "missing personaBackstory")
    require(case["messages"] and all(m["role"] in {"user", "assistant"}
            and isinstance(m["content"], str) and m["content"] for m in case["messages"]),
            "invalid conversation")
    refs, versions = {}, set()
    for memory in case["memories"]:
        validate_memory(memory)
        require(memory["ref"] not in refs, "duplicate evidence ref")
        version_key = identity(memory) + (memory["version"],)
        require(version_key not in versions, "ambiguous memory revision")
        refs[memory["ref"]] = memory
        versions.add(version_key)
    validate_events(case, refs, versions)
    if case["track"] == "deterministic":
        expected = case["expected"]
        require(set(expected) == {"candidates", "injected", "rejected_writes"},
                "invalid deterministic expectation")
        for values in expected.values():
            require(strings(values) and len(values) == len(set(values))
                    and set(values) <= refs.keys(), "invalid expected evidence refs")
    else:
        require("expected" not in case, "subjective cases cannot have automatic verdicts")
        rubric = case["rubric"]
        require(set(rubric) == {"must", "must_not", "dimensions"}
                and all(strings(v) and v for v in rubric.values()), "invalid human rubric")


def validate_events(case, refs, versions):
    for event in case["events"]:
        action = event["action"]
        if action == "executor":
            require(set(event) == {"action", "value"}
                    and event["value"] in {"local", "cloud"}, "invalid fallback")
            continue
        if action in {"cancel", "resend"}:
            require(set(event) == {"action"}, "invalid cancellation")
            continue
        require(action in {"delete", "revoke_cloud", "revise", "replay"}, "unknown event")
        require(event["target"] in refs, "event target is unknown")
        if action not in {"revise", "replay"}:
            require(set(event) == {"action", "target"}, "invalid event fields")
            continue
        require(set(event) == {"action", "target", "replacement"}, "invalid write event")
        replacement = event["replacement"]
        require(set(replacement) <= {"ref", "version", "content", "updated_at", "deleted"},
                "write cannot change ownership, scope or permissions")
        target = refs[event["target"]]
        new = dict(target, **replacement)
        validate_memory(new)
        require(new["ref"] not in refs, "write needs a fresh evidence ref")
        require(new["version"] > target["version"], "write version must increase")
        version_key = identity(new) + (new["version"],)
        require(version_key not in versions, "duplicate write revision")
        require(timestamp(new["updated_at"]) >= timestamp(target["updated_at"]),
                "write time must not go backwards")
        refs[new["ref"]] = new
        versions.add(version_key)


def load_suite(root=ROOT):
    cases = []
    for split in ("dev", "heldout"):
        path = root / "datasets" / f"{split}.jsonl"
        for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            try:
                case = json.loads(line)
                validate_case(case, split)
            except (ValueError, KeyError, TypeError, AttributeError) as error:
                raise ValueError(f"{path.name}:{line_number}: {error}") from error
            cases.append(case)
    require(30 <= len(cases) <= 50, "suite must contain 30-50 cases")
    require(len({c["id"] for c in cases}) == len(cases), "duplicate case id")
    dev_families = {c["family"] for c in cases if c["split"] == "dev"}
    held_families = {c["family"] for c in cases if c["split"] == "heldout"}
    require(not dev_families & held_families, "scenario family leaks across splits")
    fingerprints = [json.dumps(c["messages"], sort_keys=True, ensure_ascii=False) for c in cases]
    require(len(set(fingerprints)) == len(cases), "duplicate conversation across cases")
    require(set.union(*(set(c["tags"]) for c in cases)) == TAGS, "missing risk coverage")
    for split in ("dev", "heldout"):
        require({c["track"] for c in cases if c["split"] == split}
                == {"deterministic", "llm_subjective"}, "split needs both tracks")
    return cases
