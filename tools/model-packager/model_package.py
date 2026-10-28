"""Shared logic for building and validating Persona MNN model packages.

The Android app intentionally performs its own validation at install/load time.
This tool is the missing authoring step: it computes the SHA-256 manifest that the
app requires, checks the MNN config, and writes the READY marker.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import sys
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Tuple

SCHEMA_VERSION = 1
DEFAULT_BACKEND = "MNN"
DEFAULT_FAMILY = "QWEN2_5"
DEFAULT_PROMPT_FORMAT = "QWEN_CHATML_TEXT"
DEFAULT_CONTEXT_WINDOW = 32768
DEFAULT_MIN_RAM_GB = 4
DEFAULT_MIN_SDK = 24
DEFAULT_REQUIRED_ABIS = ["arm64-v8a"]
KNOWN_FAMILIES = {"QWEN2_5"}
KNOWN_PROMPT_FORMATS = {"QWEN_CHATML_TEXT", "MNN_CHAT_MESSAGES"}
MODEL_ID_RE = re.compile(r"^[A-Za-z0-9._-]+$")
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
EXCLUDED_NAMES = {"manifest.json", "READY", ".DS_Store", "Thumbs.db"}
EXCLUDED_DIRS = {".git", "__pycache__", ".staging"}


class PackageError(Exception):
    """Raised when a model directory cannot be turned into a valid package."""


def sha256_file(path: Path, chunk_size: int = 1024 * 1024) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while True:
            chunk = handle.read(chunk_size)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def slugify(value: str) -> str:
    slug = re.sub(r"[^A-Za-z0-9._-]+", "-", value.strip()).strip("-._")
    return slug or "model-package"


def safe_relative_path(value: str, label: str = "path") -> str:
    """Normalize a manifest path and reject absolute/traversal paths."""
    if not isinstance(value, str):
        raise PackageError(f"{label} must be a string")
    normalized = value.strip().replace("\\", "/")
    if not normalized or normalized in {".", ".."}:
        raise PackageError(f"{label} is empty or unsafe: {value!r}")
    if normalized.startswith("/") or re.match(r"^[A-Za-z]:", normalized):
        raise PackageError(f"{label} must be relative: {value!r}")
    parts = normalized.split("/")
    if any(part in {"", ".", ".."} for part in parts):
        raise PackageError(f"{label} contains an unsafe component: {value!r}")
    return normalized


def ensure_file(root: Path, relative: str, label: str) -> Path:
    relative = safe_relative_path(relative, label)
    raw_candidate = root / relative
    if raw_candidate.is_symlink():
        raise PackageError(f"{label} must not be a symbolic link: {relative}")
    candidate = raw_candidate.resolve()
    try:
        candidate.relative_to(root.resolve())
    except ValueError as error:
        raise PackageError(f"{label} escapes the model directory: {relative}") from error
    if not candidate.is_file():
        raise PackageError(f"{label} does not exist or is not a file: {relative}")
    return candidate


def check_tokenizer_line_endings(path: Path) -> None:
    if path.suffix.lower() != ".txt":
        return
    # MNN's text vocabulary loader retains CR, making otherwise valid tokens miss.
    with path.open("rb") as handle:
        tail = b""
        while True:
            chunk = handle.read(1024 * 1024)
            if not chunk:
                return
            if b"\r\n" in tail + chunk:
                raise PackageError("tokenizer.txt contains CRLF; restore LF and regenerate the manifest")
            tail = chunk[-1:]


def load_mnn_config(root: Path) -> Dict[str, Any]:
    config_path = root / "config.json"
    if config_path.is_symlink() or not config_path.is_file():
        raise PackageError("config.json is missing or is a symbolic link")
    try:
        data = json.loads(config_path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise PackageError(f"config.json cannot be parsed: {error}") from error
    if not isinstance(data, dict):
        raise PackageError("config.json must contain a JSON object")
    return data


def detect_tokenizer(
    root: Path,
    config: Dict[str, Any],
    explicit: Optional[str] = None
) -> str:
    if explicit:
        return safe_relative_path(explicit, "tokenizer")
    for key in ("tokenizer", "tokenizer_path", "tokenizer_model"):
        value = config.get(key)
        if isinstance(value, str) and value.strip():
            return safe_relative_path(value, f"config.json {key}")
    for candidate in (
        "tokenizer.txt",
        "tokenizer.mtok",
        "tokenizer.json",
        "tokenizer.model",
        "tokenizer_config.json",
    ):
        if (root / candidate).is_file():
            return candidate
    raise PackageError(
        "tokenizer was not found; pass --tokenizer or add a tokenizer.* file"
    )


def iter_package_files(root: Path) -> Iterable[Tuple[Path, str]]:
    root = root.resolve()
    for path in sorted(root.rglob("*")):
        relative = path.relative_to(root).as_posix()
        parts = relative.split("/")
        if any(part in EXCLUDED_DIRS for part in parts):
            continue
        if path.is_symlink():
            raise PackageError(f"model directory must not contain symbolic links: {relative}")
        if path.is_dir():
            continue
        if path.name in EXCLUDED_NAMES:
            continue
        if not path.is_file():
            continue
        yield path, relative


def _role_for(relative: str, entry: str, weight: str, tokenizer: str) -> str:
    if relative == "config.json":
        return "config"
    if relative == entry:
        return "model"
    if relative == weight:
        return "weight"
    if relative == tokenizer:
        return "tokenizer"
    return "file"


def build_manifest(
    root: Path,
    model_id: Optional[str] = None,
    name: Optional[str] = None,
    version: str = "1.0.0",
    backend: str = DEFAULT_BACKEND,
    family: str = DEFAULT_FAMILY,
    prompt_format: str = DEFAULT_PROMPT_FORMAT,
    context_window: int = DEFAULT_CONTEXT_WINDOW,
    min_ram_gb: int = DEFAULT_MIN_RAM_GB,
    min_sdk: int = DEFAULT_MIN_SDK,
    required_abis: Optional[List[str]] = None,
    tokenizer: Optional[str] = None,
) -> Dict[str, Any]:
    root = root.resolve()
    if not root.is_dir():
        raise PackageError(f"model directory does not exist: {root}")

    config = load_mnn_config(root)
    entry_value = config.get("llm_model")
    weight_value = config.get("llm_weight")
    if not isinstance(entry_value, str) or not entry_value.strip():
        raise PackageError("config.json is missing llm_model")
    if not isinstance(weight_value, str) or not weight_value.strip():
        raise PackageError("config.json is missing llm_weight")

    entry = safe_relative_path(entry_value, "config.json llm_model")
    weight = safe_relative_path(weight_value, "config.json llm_weight")
    tokenizer = detect_tokenizer(root, config, tokenizer)

    ensure_file(root, entry, "entry")
    ensure_file(root, weight, "weight")
    check_tokenizer_line_endings(ensure_file(root, tokenizer, "tokenizer"))

    model_id = model_id or slugify(root.name)
    name = name or root.name
    required_abis = required_abis or list(DEFAULT_REQUIRED_ABIS)
    if not MODEL_ID_RE.match(model_id):
        raise PackageError(f"invalid model id: {model_id!r}")
    if family not in KNOWN_FAMILIES:
        raise PackageError(
            f"unsupported family {family!r}; known: {sorted(KNOWN_FAMILIES)}"
        )
    if prompt_format not in KNOWN_PROMPT_FORMATS:
        raise PackageError(
            f"unsupported prompt format {prompt_format!r}; known: {sorted(KNOWN_PROMPT_FORMATS)}"
        )
    if not 1 <= int(context_window) <= 1_000_000:
        raise PackageError("contextWindow must be between 1 and 1,000,000")
    if int(min_ram_gb) < 0 or int(min_sdk) < 0:
        raise PackageError("minRamGb and minSdk must be non-negative")
    if not required_abis or any(not isinstance(value, str) or not value for value in required_abis):
        raise PackageError("requiredAbis must contain at least one non-empty string")

    files = []
    seen = set()
    for path, relative in iter_package_files(root):
        if relative in seen:
            raise PackageError(f"duplicate package path: {relative}")
        seen.add(relative)
        files.append(
            {
                "path": relative,
                "role": _role_for(relative, entry, weight, tokenizer),
                "size": path.stat().st_size,
                "sha256": sha256_file(path),
            }
        )

    required_paths = {"config.json", entry, weight, tokenizer}
    missing = sorted(required_paths - seen)
    if missing:
        raise PackageError(f"manifest file set is missing required paths: {missing}")

    manifest = {
        "schemaVersion": SCHEMA_VERSION,
        "id": model_id,
        "name": name,
        "version": version,
        "backend": backend,
        "family": family,
        "promptFormat": prompt_format,
        "entry": entry,
        "tokenizer": tokenizer,
        "contextWindow": int(context_window),
        "minRamGb": int(min_ram_gb),
        "minSdk": int(min_sdk),
        "requiredAbis": list(required_abis),
        "files": files,
        "signature": None,
    }
    return manifest


def _require_string(manifest: Dict[str, Any], key: str, errors: List[str]) -> Optional[str]:
    value = manifest.get(key)
    if not isinstance(value, str) or not value.strip():
        errors.append(f"{key} must be a non-empty string")
        return None
    return value


def _require_int(manifest: Dict[str, Any], key: str, errors: List[str]) -> Optional[int]:
    value = manifest.get(key)
    if not isinstance(value, int) or isinstance(value, bool):
        errors.append(f"{key} must be an integer")
        return None
    return value


def validate_manifest(
    root: Path,
    manifest: Dict[str, Any],
    require_ready: bool = False,
) -> Tuple[List[str], List[str]]:
    """Validate a manifest against a model directory.

    Returns (errors, warnings). The Android app is still the final authority;
    this mirrors its essential checks for package authoring.
    """
    errors: List[str] = []
    warnings: List[str] = []
    root = root.resolve()
    if not root.is_dir():
        return [f"model directory does not exist: {root}"], warnings
    if not isinstance(manifest, dict):
        return ["manifest must be a JSON object"], warnings

    if manifest.get("schemaVersion") != SCHEMA_VERSION:
        errors.append(f"schemaVersion must be {SCHEMA_VERSION}")
    model_id = _require_string(manifest, "id", errors)
    if model_id and not MODEL_ID_RE.match(model_id):
        errors.append("id contains unsupported characters")
    _require_string(manifest, "name", errors)
    _require_string(manifest, "version", errors)
    backend = _require_string(manifest, "backend", errors)
    if backend and backend.upper() != DEFAULT_BACKEND:
        errors.append(f"backend must be {DEFAULT_BACKEND}")
    family = _require_string(manifest, "family", errors)
    if family and family not in KNOWN_FAMILIES:
        errors.append(f"unsupported family: {family}")
    prompt_format = _require_string(manifest, "promptFormat", errors)
    if prompt_format and prompt_format not in KNOWN_PROMPT_FORMATS:
        errors.append(f"unsupported promptFormat: {prompt_format}")

    context_window = _require_int(manifest, "contextWindow", errors)
    if context_window is not None and not 1 <= context_window <= 1_000_000:
        errors.append("contextWindow must be between 1 and 1,000,000")
    min_ram_gb = _require_int(manifest, "minRamGb", errors)
    if min_ram_gb is not None and min_ram_gb < 0:
        errors.append("minRamGb must be non-negative")
    min_sdk = _require_int(manifest, "minSdk", errors)
    if min_sdk is not None and min_sdk < 0:
        errors.append("minSdk must be non-negative")

    required_abis = manifest.get("requiredAbis")
    if not isinstance(required_abis, list) or not required_abis:
        errors.append("requiredAbis must be a non-empty array")
        required_abis = []
    elif any(not isinstance(value, str) or not value for value in required_abis):
        errors.append("requiredAbis must contain non-empty strings")

    entry = _require_string(manifest, "entry", errors)
    tokenizer = _require_string(manifest, "tokenizer", errors)
    if entry:
        try:
            entry = safe_relative_path(entry, "entry")
        except PackageError as error:
            errors.append(str(error))
            entry = None
    if tokenizer:
        try:
            tokenizer = safe_relative_path(tokenizer, "tokenizer")
        except PackageError as error:
            errors.append(str(error))
            tokenizer = None

    config_path = root / "config.json"
    if config_path.is_symlink() or not config_path.is_file():
        errors.append("config.json is missing or is a symbolic link")
        config: Dict[str, Any] = {}
    else:
        try:
            config = json.loads(config_path.read_text(encoding="utf-8"))
            if not isinstance(config, dict):
                errors.append("config.json must contain a JSON object")
                config = {}
        except (OSError, ValueError) as error:
            errors.append(f"config.json cannot be parsed: {error}")
            config = {}

    config_entry = config.get("llm_model")
    config_weight = config.get("llm_weight")
    if not isinstance(config_entry, str) or not config_entry.strip():
        errors.append("config.json is missing llm_model")
        config_entry = None
    if isinstance(config_entry, str) and entry and config_entry != entry:
        errors.append(
            f"manifest entry ({entry}) does not match config.json llm_model ({config_entry})"
        )
    if not isinstance(config_weight, str) or not config_weight.strip():
        errors.append("config.json is missing llm_weight")
        config_weight = None

    if entry:
        try:
            ensure_file(root, entry, "entry")
        except PackageError as error:
            errors.append(str(error))
    if tokenizer:
        try:
            check_tokenizer_line_endings(ensure_file(root, tokenizer, "tokenizer"))
        except PackageError as error:
            errors.append(str(error))
    if isinstance(config_weight, str):
        try:
            weight_path = ensure_file(root, config_weight, "weight")
        except PackageError as error:
            errors.append(str(error))
            weight_path = None
    else:
        weight_path = None

    files = manifest.get("files")
    seen = set()
    if not isinstance(files, list) or not files:
        errors.append("files must be a non-empty array")
        files = []
    for index, item in enumerate(files):
        if not isinstance(item, dict):
            errors.append(f"files[{index}] must be an object")
            continue
        raw_path = item.get("path")
        try:
            relative = safe_relative_path(raw_path, f"files[{index}].path")
        except PackageError as error:
            errors.append(str(error))
            continue
        if relative in seen:
            errors.append(f"duplicate files path: {relative}")
            continue
        seen.add(relative)
        if relative in {"manifest.json", "READY"}:
            errors.append(f"files must not include generated file: {relative}")
            continue
        role = item.get("role")
        if not isinstance(role, str) or not role.strip():
            errors.append(f"files[{index}].role must be a non-empty string")
        size = item.get("size")
        if not isinstance(size, int) or isinstance(size, bool) or size < 0:
            errors.append(f"files[{index}].size must be a non-negative integer")
            continue
        digest = item.get("sha256")
        if not isinstance(digest, str) or not SHA256_RE.match(digest.lower()):
            errors.append(f"files[{index}].sha256 must be 64 lowercase hex characters")
            continue
        try:
            path = ensure_file(root, relative, f"files[{index}]")
        except PackageError as error:
            errors.append(str(error))
            continue
        if path.stat().st_size != size:
            errors.append(f"size mismatch: {relative}")
        if sha256_file(path) != digest.lower():
            errors.append(f"SHA256 mismatch: {relative}")

    required_paths = {"config.json"}
    if entry:
        required_paths.add(entry)
    if tokenizer:
        required_paths.add(tokenizer)
    if weight_path is not None:
        required_paths.add(weight_path.relative_to(root).as_posix())
    missing = sorted(required_paths - seen)
    if missing:
        errors.append(f"files does not cover required paths: {missing}")

    ready_path = root / "READY"
    if require_ready and not ready_path.is_file():
        errors.append("READY marker is missing")
    elif not ready_path.is_file():
        warnings.append("READY marker is missing; the Android installer will create it")

    if manifest.get("signature") is None:
        warnings.append("signature is empty; the current app does not verify a package signature")

    return errors, warnings


def write_manifest(
    root: Path,
    manifest: Dict[str, Any],
    output: Optional[Path] = None,
    force: bool = False,
) -> Path:
    root = root.resolve()
    output = (output or (root / "manifest.json")).resolve()
    if output.exists() and not force:
        raise PackageError(f"manifest already exists: {output}; pass --force to overwrite")
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_name(output.name + ".tmp")
    temporary.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    os.replace(str(temporary), str(output))
    return output


def write_ready(root: Path) -> Path:
    root = root.resolve()
    ready = root / "READY"
    ready.write_text("ready\n", encoding="utf-8")
    return ready


def summary(manifest: Dict[str, Any]) -> str:
    files = manifest.get("files") or []
    total = sum(item.get("size", 0) for item in files if isinstance(item, dict))
    return (
        f"id={manifest.get('id')} "
        f"entry={manifest.get('entry')} "
        f"tokenizer={manifest.get('tokenizer')} "
        f"files={len(files)} "
        f"total={total} bytes"
    )
