"""Executable evaluation contract, independent of the Android implementation."""

from copy import deepcopy
from datetime import datetime

POLICY_VERSION = "m6-latest-explicit-memory-v2"
MAX_RECORDS = 4
MAX_CHARACTERS = 1200
MAX_RECORD_CHARACTERS = 400


def timestamp(value):
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError("timestamps must include a timezone")
    return parsed


def identity(memory):
    return (memory["owner"], memory["memory_id"])


def characters(content):
    # Match Kotlin String.length, including two code units per supplementary character.
    return len(content.encode("utf-16-le")) // 2


def save_allowed(memory):
    source = memory["save_source"]
    valid_content = bool(memory["content"].strip()) and characters(memory["content"]) <= 400
    if not valid_content:
        return False
    if source["mode"] == "manual":
        return source["confirmed"] and memory["source_message_id"] is None
    return (
        source["mode"] == "message" and memory["source_message_id"] is not None
        and source["is_from_user"] and source["status"] == "NORMAL"
        and source["provenance"] == "user"
        and source["owner"] == memory["owner"]
        and (memory["persona"] is None or source["persona"] == memory["persona"])
    )


def eligible(memory, context):
    now = timestamp(context["as_of"])
    return (
        memory["owner"] == context["owner"]
        and not memory["deleted"]
        and (memory["persona"] is None or memory["persona"] == context["persona"])
        and (context["executor"] == "local" or memory["cloud_allowed"])
        and (memory["expires_at"] is None or now < timestamp(memory["expires_at"]))
    )


def select(memories, context):
    # Fixture revisions represent snapshots of a single mutable production row.
    latest = {}
    for memory in memories:
        key = identity(memory)
        if key not in latest or memory["version"] > latest[key]["version"]:
            latest[key] = memory
    rows = [m for m in latest.values() if eligible(m, context)]
    rows.sort(key=lambda m: (-timestamp(m["updated_at"]).timestamp(), m["memory_id"]))
    return [m["ref"] for m in rows[:MAX_RECORDS]]


def inject(memories, context):
    refs = select(memories, context)
    by_ref = {m["ref"]: m for m in memories}
    used, accepted = 0, []
    for ref in refs:
        content = by_ref[ref]["content"]
        size = characters(content)
        if content.strip() and 1 <= size <= MAX_RECORD_CHARACTERS and used + size <= MAX_CHARACTERS:
            used += size
            accepted.append(ref)
    return accepted


def evaluate(case):
    """Return exact evidence refs at retrieval and injection boundaries."""
    memories = [deepcopy(m) for m in case["memories"] if m["deleted"] or save_allowed(m)]
    rejected = [m["ref"] for m in case["memories"] if not m["deleted"] and not save_allowed(m)]
    context = deepcopy(case["context"])
    candidates = select(memories, context)
    tombstones = {identity(m) for m in memories if m["deleted"]}
    cancelled = False
    for event in case["events"]:
        action = event["action"]
        if action == "executor":
            context["executor"] = event["value"]
        elif action == "cancel":
            cancelled = True
        elif action == "resend":
            cancelled = False
        else:
            cancelled = True
            target = next(m for m in memories if m["ref"] == event["target"])
            if action == "delete":
                tombstones.add(identity(target))
                for memory in memories:
                    if identity(memory) == identity(target):
                        memory.update(deleted=True, content="", source_message_id=None,
                                      cloud_allowed=False)
            elif action == "revoke_cloud":
                for memory in memories:
                    if identity(memory) == identity(target):
                        memory["cloud_allowed"] = False
            elif action in {"revise", "replay"}:
                new = deepcopy(target)
                new.update(event["replacement"])
                if identity(new) in tombstones or not save_allowed(new):
                    rejected.append(new["ref"])
                else:
                    memories.append(new)
            else:
                raise ValueError(f"unknown event: {action}")
    # Rebuild after edits or executor fallback, rather than reuse the old cache.
    injected = [] if cancelled else inject(memories, context)
    return {"candidates": candidates, "injected": injected,
            "rejected_writes": sorted(rejected)}
