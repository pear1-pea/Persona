#!/usr/bin/env python3
"""Validate a Persona MNN model package without the Android app."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import List, Optional

from model_package import PackageError, summary, validate_manifest


def parse_args(argv: Optional[List[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Check manifest.json, config.json, file sizes, SHA-256 hashes and "
            "the READY marker for a Persona MNN model package."
        )
    )
    parser.add_argument("model_dir", type=Path, help="Model directory to validate")
    parser.add_argument("--manifest", type=Path, help="Manifest path (default: <model_dir>/manifest.json)")
    parser.add_argument("--require-ready", action="store_true", help="Treat a missing READY as an error")
    parser.add_argument("--json", action="store_true", help="Print a machine-readable report")
    return parser.parse_args(argv)


def main(argv: Optional[List[str]] = None) -> int:
    args = parse_args(argv)
    root = args.model_dir.resolve()
    manifest_path = (args.manifest or (root / "manifest.json")).resolve()
    if not manifest_path.is_file():
        print(f"ERROR: manifest not found: {manifest_path}", file=sys.stderr)
        return 1
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        print(f"ERROR: cannot parse manifest: {error}", file=sys.stderr)
        return 1
    if not isinstance(manifest, dict):
        print("ERROR: manifest must be a JSON object", file=sys.stderr)
        return 1

    errors, warnings = validate_manifest(root, manifest, require_ready=args.require_ready)
    report = {
        "ok": not errors,
        "manifest": str(manifest_path),
        "errors": errors,
        "warnings": warnings,
        "summary": summary(manifest),
    }
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        for error in errors:
            print(f"ERROR: {error}")
        for warning in warnings:
            print(f"WARNING: {warning}")
        print("OK" if not errors else "INVALID")
        print(summary(manifest))
    return 0 if not errors else 1


if __name__ == "__main__":
    raise SystemExit(main())
