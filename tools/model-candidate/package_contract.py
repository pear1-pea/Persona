"""Isolated fixed Qwen3 experiment contract; no production package integration."""

from __future__ import annotations

import argparse
from contextlib import contextmanager
import hashlib
import json
import math
import os
from pathlib import Path
import re
import stat
import sys

MODEL_REVISION = "c1899de289a04d12100db370d81485cdf75e47ca"
MNN_REVISION = "d407447ed56c4121a11ccbd266dc184ca1ead0c2"
MAX_FILE_BYTES = 2 * 1024**3
MAX_TOTAL_BYTES = 4 * 1024**3
MAX_JSON_BYTES = 4 * 1024**2
MAX_RECEIPT_BYTES = 1024**2
MAX_INTEGER = 2**63 - 1
REQUIRED = frozenset({
    "llm.mnn", "llm.mnn.weight", "config.json", "llm_config.json",
    "tokenizer.mtok", "embeddings_bf16.bin", "context.json", "LICENSE",
    "README.md", "export_args.json",
})
ALLOWED = REQUIRED | {"llm.mnn.json"}
REFERENCES = {
    "llm_model": "llm.mnn", "llm_weight": "llm.mnn.weight",
    "tokenizer_file": "tokenizer.mtok", "context_file": "context.json",
    "backend_type": "cpu",
}
FAILURE = "package validation failed"


class PackageContractError(ValueError):
    def __init__(self):
        super().__init__(FAILURE)


def _require(condition):
    if not condition:
        raise PackageContractError()


def _pairs(pairs):
    result = {}
    for key, value in pairs:
        _require(key not in result)
        result[key] = value
    return result


def _integer(text):
    _require(len(text.lstrip("-")) <= 19)
    value = int(text)
    _require(-MAX_INTEGER - 1 <= value <= MAX_INTEGER)
    return value


def _float(text):
    value = float(text)
    _require(math.isfinite(value))
    return value


def _constant(_text):
    raise PackageContractError()


def _json(data):
    value = json.loads(data.decode("utf-8"), object_pairs_hook=_pairs,
                       parse_int=_integer, parse_float=_float,
                       parse_constant=_constant)
    _require(type(value) is dict)
    return value


def _host_path(value):
    raw = os.fspath(value)
    _require(isinstance(raw, str) and raw and "\x00" not in raw
             and "\\" not in raw and ":" not in raw
             and ".." not in raw.split("/"))
    path = Path(raw)
    return path if path.is_absolute() else Path.cwd() / path


@contextmanager
def _directory(path):
    # Open each component relative to its verified parent, never resolving links.
    descriptor = os.open("/", os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        for part in path.parts[1:]:
            child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW,
                            dir_fd=descriptor)
            os.close(descriptor)
            descriptor = child
        yield descriptor
    finally:
        os.close(descriptor)


def _signature(info):
    return (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns,
            info.st_ctime_ns, info.st_nlink)


def _read(parent, name, limit, *, capture=False, expected_size=None):
    descriptor = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK,
                         dir_fd=parent)
    with os.fdopen(descriptor, "rb") as stream:
        before = os.fstat(stream.fileno())
        _require(stat.S_ISREG(before.st_mode) and 0 < before.st_size <= limit)
        if expected_size is not None:
            _require(before.st_size == expected_size)
        digest = hashlib.sha256()
        chunks = []
        total = 0
        while True:
            block = stream.read(min(1024**2, limit - total + 1))
            if not block:
                break
            total += len(block)
            _require(total <= limit)
            digest.update(block)
            if capture:
                chunks.append(block)
        _require(total == before.st_size
                 and _signature(before) == _signature(os.fstat(stream.fileno())))
    return b"".join(chunks), digest.hexdigest(), before


def _read_path(path, limit):
    with _directory(path.parent) as parent:
        return _read(parent, path.name, limit, capture=True)


def _inventory(parent):
    observed = {}
    with os.scandir(parent) as scan:
        for entry in scan:
            _require(entry.name in ALLOWED and len(observed) < len(ALLOWED))
            info = entry.stat(follow_symlinks=False)
            _require(stat.S_ISREG(info.st_mode))
            observed[entry.name] = _signature(info)
    return observed


def _digest(value):
    return isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value) is not None


def _receipt(data):
    receipt = _json(data)
    _require(set(receipt) == {"model_revision", "mnn_revision", "quant_bits", "files"})
    _require(receipt["model_revision"] == MODEL_REVISION
             and receipt["mnn_revision"] == MNN_REVISION)
    bits = receipt["quant_bits"]
    _require(type(bits) is list and all(type(bit) is int for bit in bits)
             and bits == [8, 8, 16])
    entries = receipt["files"]
    _require(type(entries) is list and len(REQUIRED) <= len(entries) <= len(ALLOWED))
    files = {}
    total = 0
    for entry in entries:
        _require(type(entry) is dict and set(entry) == {"path", "size", "sha256"})
        name, size = entry["path"], entry["size"]
        # This candidate has a closed, flat filename set; aliases are forbidden.
        _require(isinstance(name, str) and name in ALLOWED and name not in files)
        _require(type(size) is int and 0 < size <= MAX_FILE_BYTES)
        _require(_digest(entry["sha256"]) and size <= MAX_TOTAL_BYTES - total)
        total += size
        files[name] = entry
    _require(REQUIRED <= files.keys())
    return files, total


def _settings(documents):
    config = documents["config.json"]
    llm = documents["llm_config.json"]
    for key, value in REFERENCES.items():
        _require(config.get(key) == value)
        if key in llm:
            _require(llm[key] == value)
    _require(documents["context.json"].get("enable_thinking") is False)
    jinja = llm.get("jinja")
    _require(type(jinja) is dict)
    template = jinja.get("chat_template")
    _require(isinstance(template, str) and bool(template.strip()))
    if "context" in jinja:
        context = jinja["context"]
        _require(type(context) is dict)
        if "enable_thinking" in context:
            _require(context["enable_thinking"] is False)
    args = documents["export_args.json"]
    for key, value in {"quant_bit": 8, "lm_quant_bit": 8, "embed_bit": 16}.items():
        _require(type(args.get(key)) is int and args[key] == value)
    _require(args.get("seperate_embed") is True)
    return hashlib.sha256(template.encode("utf-8")).hexdigest()


def validate_package(root, receipt_path, *, source_lock=None) -> dict:
    """Validate files only. Explicit source_lock is a trusted synthetic-test seam."""
    try:
        if source_lock is None:
            lock_path = _host_path(Path(__file__).with_name("source-lock.json"))
            lock_bytes, _, _ = _read_path(lock_path, MAX_JSON_BYTES)
            source_lock = _json(lock_bytes)
        _require(type(source_lock) is dict
                 and source_lock.get("modelRepo") == "Qwen/Qwen3-0.6B"
                 and source_lock.get("modelRevision") == MODEL_REVISION
                 and source_lock.get("runtimeRevision") == MNN_REVISION
                 and _digest(source_lock.get("packageReceiptSha256")))
        data, receipt_digest, _ = _read_path(_host_path(receipt_path), MAX_RECEIPT_BYTES)
        _require(receipt_digest == source_lock["packageReceiptSha256"])
        files, total = _receipt(data)
        documents, identities = {}, set()
        with _directory(_host_path(root)) as parent:
            actual = _inventory(parent)
            _require(actual.keys() == files.keys())
            for name, item in sorted(files.items()):
                is_json = name.endswith(".json")
                limit = MAX_JSON_BYTES if is_json else MAX_FILE_BYTES
                content, digest, info = _read(parent, name, limit, capture=is_json,
                                             expected_size=item["size"])
                _require(digest == item["sha256"])
                identity = (info.st_dev, info.st_ino)
                _require(identity not in identities)
                identities.add(identity)
                _require(actual[name] == _signature(info))
                if is_json:
                    documents[name] = _json(content)
            _require(_inventory(parent) == actual)
        template_digest = _settings(documents)
        return {
            "schema_version": 1,
            "files": [dict(files[name]) for name in sorted(files)],
            "file_count": len(files), "total_size_bytes": total,
            "model": {"id": "Qwen/Qwen3-0.6B", "revision": MODEL_REVISION,
                      "quant_bits": [8, 8, 16]},
            "runtime": {"name": "MNN", "revision": MNN_REVISION, "backend": "cpu"},
            "template_sha256": template_digest, "enable_thinking": False,
            "budget": "not_device_test", "receipt_sha256": receipt_digest,
            "artifact_scope": "fixed_experiment_artifact",
            "release_trust": "not_signature_verified",
        }
    except (OSError, ValueError, TypeError, RecursionError, OverflowError):
        raise PackageContractError() from None


class _Parser(argparse.ArgumentParser):
    def error(self, _message):
        raise PackageContractError()


def main(argv=None) -> int:
    try:
        parser = _Parser(description="Validate an isolated fixed experiment package.",
                         allow_abbrev=False)
        parser.add_argument("--package", required=True)
        parser.add_argument("--receipt", required=True)
        parser.add_argument("--output", required=True, help="new report directory")
        args = parser.parse_args(argv)
        output = _host_path(args.output)
        package = _host_path(args.package)
        _require(output != package and package not in output.parents)
        # Validate first; invalid input must not create an output directory.
        report = validate_package(package, args.receipt)
        with _directory(output.parent) as parent:
            os.mkdir(output.name, mode=0o700, dir_fd=parent)
            descriptor = os.open(output.name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW,
                                 dir_fd=parent)
            try:
                report_fd = os.open("report.json", os.O_WRONLY | os.O_CREAT | os.O_EXCL
                                    | os.O_NOFOLLOW, 0o600, dir_fd=descriptor)
                with os.fdopen(report_fd, "w", encoding="utf-8") as stream:
                    json.dump(report, stream, indent=2, sort_keys=True, allow_nan=False)
                    stream.write("\n")
            finally:
                os.close(descriptor)
        print("package validation passed")
        return 0
    except (Exception, KeyboardInterrupt):
        print(FAILURE, file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
