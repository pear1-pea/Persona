#!/usr/bin/env python3
"""Generate a Persona manifest.json for an MNN model directory."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import List, Optional

from model_package import (
    DEFAULT_BACKEND,
    DEFAULT_CONTEXT_WINDOW,
    DEFAULT_FAMILY,
    DEFAULT_MIN_RAM_GB,
    DEFAULT_MIN_SDK,
    DEFAULT_PROMPT_FORMAT,
    DEFAULT_REQUIRED_ABIS,
    PackageError,
    build_manifest,
    summary,
    validate_manifest,
    write_manifest,
    write_ready,
)


def parse_args(argv: Optional[List[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Build manifest.json + READY for a Persona MNN model directory. "
            "The manifest records every package file with size and SHA-256."
        )
    )
    parser.add_argument("model_dir", type=Path, help="MNN model directory (contains config.json)")
    parser.add_argument("--id", dest="model_id", help="Model id (default: directory name)")
    parser.add_argument("--name", help="Human readable name (default: directory name)")
    parser.add_argument("--version", default="1.0.0")
    parser.add_argument("--backend", default=DEFAULT_BACKEND)
    parser.add_argument("--family", default=DEFAULT_FAMILY)
    parser.add_argument("--prompt-format", default=DEFAULT_PROMPT_FORMAT)
    parser.add_argument("--context-window", type=int, default=DEFAULT_CONTEXT_WINDOW)
    parser.add_argument("--min-ram-gb", type=int, default=DEFAULT_MIN_RAM_GB)
    parser.add_argument("--min-sdk", type=int, default=DEFAULT_MIN_SDK)
    parser.add_argument(
        "--required-abi",
        action="append",
        dest="required_abis",
        help=f"Repeatable; default: {', '.join(DEFAULT_REQUIRED_ABIS)}",
    )
    parser.add_argument(
        "--tokenizer",
        help="Tokenizer path relative to model_dir (default: auto-detect)",
    )
    parser.add_argument("--output", type=Path, help="Manifest path (default: <model_dir>/manifest.json)")
    parser.add_argument("--force", action="store_true", help="Overwrite an existing manifest")
    parser.add_argument("--no-ready", action="store_true", help="Do not write READY")
    parser.add_argument("--json", action="store_true", help="Print a machine-readable summary")
    return parser.parse_args(argv)


def main(argv: Optional[List[str]] = None) -> int:
    args = parse_args(argv)
    try:
        manifest = build_manifest(
            root=args.model_dir,
            model_id=args.model_id,
            name=args.name,
            version=args.version,
            backend=args.backend,
            family=args.family,
            prompt_format=args.prompt_format,
            context_window=args.context_window,
            min_ram_gb=args.min_ram_gb,
            min_sdk=args.min_sdk,
            required_abis=args.required_abis or list(DEFAULT_REQUIRED_ABIS),
            tokenizer=args.tokenizer,
        )
        errors, warnings = validate_manifest(args.model_dir, manifest)
        if errors:
            for error in errors:
                print(f"ERROR: {error}", file=sys.stderr)
            return 1
        output = write_manifest(
            args.model_dir,
            manifest,
            output=args.output,
            force=args.force,
        )
        ready = None if args.no_ready else write_ready(args.model_dir)
        warnings = [warning for warning in warnings if "READY marker" not in warning]
        result = {
            "manifest": str(output),
            "ready": str(ready) if ready else None,
            "warnings": warnings,
            "summary": summary(manifest),
        }
        if args.json:
            print(json.dumps(result, ensure_ascii=False, indent=2))
        else:
            print(f"Wrote {output}")
            if ready:
                print(f"Wrote {ready}")
            for warning in warnings:
                print(f"WARNING: {warning}")
            print(summary(manifest))
        return 0
    except PackageError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
