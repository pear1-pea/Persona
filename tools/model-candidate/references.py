"""Pinned, local-only HF reference inputs; this module never runs a model."""
import hashlib
import json
from pathlib import Path

DATASET_SHA256 = "9345975b9f5763e718cc2ae063c958ea2a10decdb2697e33ea3cbd577c7abcdd"
CONTEXT_WINDOW = 4096
RESERVE_MARGIN = 56
MAX_JSON_BYTES = 1024 * 1024


def reject_duplicates(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON key")
        result[key] = value
    return result


def read_json(path):
    path = Path(path)
    if path.is_symlink() or not path.is_file() or path.stat().st_size > MAX_JSON_BYTES:
        raise ValueError("Invalid reference document")
    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=reject_duplicates,
                      parse_constant=lambda _: (_ for _ in ()).throw(ValueError("Invalid JSON constant")))


def digest(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(8 * 1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def no_links(path):
    path = Path(path).absolute()
    if any(p.is_symlink() for p in (path, *path.parents)):
        raise ValueError("Linked input path")
    return path


def verify_snapshot(snapshot, lock):
    snapshot = no_links(snapshot)
    expected = {row["path"]: row for row in lock["snapshotFiles"]}
    if len(expected) != 10 or not snapshot.is_dir():
        raise ValueError("Invalid fixed snapshot")
    actual = set()
    for path in snapshot.rglob("*"):
        no_links(path)
        if path.is_file():
            actual.add(path.relative_to(snapshot).as_posix())
        elif not path.is_dir():
            raise ValueError("Unsupported snapshot entry")
    if actual != set(expected):
        raise ValueError("Snapshot inventory mismatch")
    for name, row in expected.items():
        if Path(name).name != name or name in (".", ".."):
            raise ValueError("Invalid snapshot lock path")
        path = snapshot / name
        if path.stat().st_size != row["size"] or digest(path) != row["sha256"]:
            raise ValueError("Snapshot digest mismatch")
        if row["hf_lfs_sha256"] is not None:
            if row["hf_lfs_sha256"] != row["sha256"]:
                raise ValueError("LFS identity mismatch")
        else:
            h = hashlib.sha1(b"blob " + str(row["size"]).encode("ascii") + b"\0" + path.read_bytes())
            if h.hexdigest() != row["hf_blob_id"]:
                raise ValueError("Git blob identity mismatch")
    return {"files": len(expected), "modelRevision": lock["modelRevision"], "completeVerified": True}


def reference_case(tokenizer, case):
    if set(case) != {"id", "kind", "source", "messages", "legacyPlanningText", "params", "expected"}:
        raise ValueError("Invalid exported case fields")
    params = case["params"]
    cap = 24 if case["kind"] == "SMOKE" else 256
    if case["kind"] not in ("SMOKE", "EXTRACTION") or not isinstance(params, dict) or set(params) != {"temperature", "topP", "maxTokens"}:
        raise ValueError("Invalid case generation contract")
    if type(params["maxTokens"]) is not int or params["maxTokens"] != cap or any(
        type(params[k]) not in (int, float) or params[k] != value for k, value in [("temperature", 0.0), ("topP", 0.8)]
    ):
        raise ValueError("Unexpected case generation parameters")
    messages = case["messages"]
    if not isinstance(messages, list) or not 1 <= len(messages) <= 128 or any(
        not isinstance(m, dict) or set(m) != {"role", "content"} or
        m["role"] not in ("system", "user", "assistant") or not isinstance(m["content"], str) or not m["content"].strip()
        for m in messages
    ):
        raise ValueError("Invalid reference messages")
    if sum(len((m["role"] + m["content"]).encode("utf-16-le")) // 2 for m in messages) > 16384:
        raise ValueError("Reference messages exceed inspection limit")
    text = tokenizer.apply_chat_template(messages, tokenize=False, add_generation_prompt=True, enable_thinking=False)
    ids = tokenizer(text, add_special_tokens=False)["input_ids"]
    direct = tokenizer.apply_chat_template(messages, tokenize=True, add_generation_prompt=True, enable_thinking=False)
    if not isinstance(text, str) or not text.endswith("<|im_start|>assistant\n<think>\n\n</think>\n\n") or ids != direct:
        raise ValueError("Reference template contract failed")
    if not ids or any(type(i) is not int or not 0 <= i <= 2**31 - 1 for i in ids):
        raise ValueError("Invalid reference token IDs")
    reserved = case["params"]["maxTokens"] + RESERVE_MARGIN
    if len(ids) + reserved > CONTEXT_WINDOW:
        raise ValueError("Reference exceeds deployment test budget")
    legacy = tokenizer(case["legacyPlanningText"], add_special_tokens=False)["input_ids"]
    result = {k: v for k, v in case.items() if k != "legacyPlanningText"}
    result.update(referenceText=text, referenceIds=ids)
    summary = {"id": case["id"], "referenceInputTokens": len(ids), "legacyPlanningTokens": len(legacy),
               "templateDifferenceTokens": len(ids) - len(legacy), "reservedOutputTokens": case["params"]["maxTokens"],
               "marginTokens": RESERVE_MARGIN, "contextWindow": CONTEXT_WINDOW,
               "remainingTokens": CONTEXT_WINDOW - len(ids) - reserved,
               "referenceTextSha256": hashlib.sha256(text.encode("utf-8")).hexdigest()}
    return result, summary
