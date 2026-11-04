#!/usr/bin/env python3
"""Prepare offline deterministic Room adapter inputs; no application run is claimed."""

import argparse
import hashlib
import json
from pathlib import Path
import sys

from evaluate import suite_digest
from schema import ROOT, VERSION, load_suite, require

REPO_ROOT = ROOT.parents[1]
SOURCE_TREES = ("app/src/main", "app/src/test", "app/src/androidTest")
BUILD_FILES = ("app/build.gradle.kts", "build.gradle.kts", "gradle/libs.versions.toml",
               "settings.gradle.kts", "gradle.properties")


def source_snapshot(repo_root=REPO_ROOT):
    """Hash actual source inputs, including untracked files; this is not build proof."""
    repo_root = Path(repo_root).resolve()
    files = [repo_root / name for name in BUILD_FILES]
    for name in SOURCE_TREES:
        tree = repo_root / name
        require(tree.is_dir() and not tree.is_symlink(), f"missing or linked source tree: {name}")
        for path in tree.rglob("*"):
            require(not path.is_symlink(), f"linked source input: {path.relative_to(repo_root)}")
            if path.is_file():
                files.append(path)
    snapshot = {}
    for path in sorted(files, key=lambda p: p.relative_to(repo_root).as_posix().encode("utf-8")):
        name = path.relative_to(repo_root).as_posix()
        require(not any(parent.is_symlink() for parent in (path, *path.parents)
                        if parent != repo_root and repo_root in parent.parents),
                f"linked source input: {name}")
        snapshot[name] = hashlib.sha256(path.read_bytes()).hexdigest()
    return snapshot


def snapshot_digest(snapshot):
    """Hash sorted UTF-8 paths, NUL separators and raw 32-byte file digests."""
    digest = hashlib.sha256()
    for name in sorted(snapshot, key=lambda value: value.encode("utf-8")):
        digest.update(name.encode("utf-8") + b"\x00")
        digest.update(bytes.fromhex(snapshot[name]))
    return digest.hexdigest()


def build_bundle(split="dev", repo_root=REPO_ROOT):
    require(split in {"dev", "heldout", "all"}, "invalid split")
    suite = load_suite()
    require(len(suite) == 40, "expected the current 40 fixed fixtures")
    dataset_sha256 = suite_digest()
    snapshot = source_snapshot(repo_root)
    # Preserve all input memories and events; only remove the answer and human rubric.
    cases = [{key: value for key, value in case.items() if key not in {"expected", "rubric"}}
             for case in suite if case["track"] == "deterministic"
             and (split == "all" or case["split"] == split)]
    return {"bundle_version": "m5-room-adapter-input-v1", "dataset_version": VERSION,
            "dataset_sha256": dataset_sha256, "split": split,
            "implementation_revision": "sha256:" + snapshot_digest(snapshot),
            "source_snapshot": snapshot, "cases": cases}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--split", choices=("dev", "heldout", "all"), default="dev")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        bundle = build_bundle(args.split)
        raw = (json.dumps(bundle, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8")
        with args.output.open("xb") as handle:
            handle.write(raw)
        print(json.dumps({"file": args.output.name, "cases": len(bundle["cases"]),
                          "bundle_sha256": hashlib.sha256(raw).hexdigest(),
                          "status": "not_run"}, ensure_ascii=False))
        return 0
    except (ValueError, KeyError, TypeError, AttributeError, OSError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
