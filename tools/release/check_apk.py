"""Inspect arm64 Release ELF alignment; signing and device execution are separate gates."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import sys
import zipfile

PAGE = 16384


def require(condition):
    if not condition:
        raise ValueError("ReleaseArtifactRejected")


def inspect_elf(data):
    require(len(data) >= 64 and data[:7] == b"\x7fELF\x02\x01\x01")
    header = struct.unpack_from("<HHIQQQIHHHHHH", data, 16)
    kind, machine, version, _, offset, _, _, header_size, entry_size, count, *_ = header
    require(kind == 3 and machine == 183 and version == 1 and header_size == 64)
    require(entry_size == 56 and 0 < count <= 256 and offset >= 64)
    require(offset + entry_size * count <= len(data))
    alignments = []
    for index in range(count):
        kind, _, file_offset, address, _, file_size, memory_size, alignment = struct.unpack_from(
            "<IIQQQQQQ", data, offset + index * entry_size)
        if kind != 1:
            continue
        require(file_size <= memory_size and file_offset + file_size <= len(data))
        require(alignment >= PAGE and alignment & (alignment - 1) == 0)
        require(file_offset % alignment == address % alignment)
        alignments.append(alignment)
    require(alignments)
    return alignments


def inspect_apk(path, source_revision):
    require(re.fullmatch(r"[0-9a-f]{40}", source_revision))
    path = Path(path)
    with path.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    native = []
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        require(len(entries) == len({entry.filename for entry in entries}))
        for entry in entries:
            if not entry.filename.endswith(".so"):
                continue
            require(re.fullmatch(r"lib/arm64-v8a/[A-Za-z0-9_.+-]+\.so", entry.filename))
            require(0 < entry.file_size <= 256 * 1024**2)
            data = archive.read(entry)
            alignments = inspect_elf(data)
            native.append({"path": entry.filename, "size": len(data),
                           "sha256": hashlib.sha256(data).hexdigest(),
                           "load_segment_alignment_bytes": alignments,
                           "zip_compressed": entry.compress_type != zipfile.ZIP_STORED})
    require(any(row["path"] == "lib/arm64-v8a/libpersona_mnn.so" for row in native))
    return {"format": "persona-release-static-v1", "source_revision": source_revision,
            "apk_sha256": digest, "native_libraries": native, "elf_16kb_alignment": "passed",
            "zip_alignment": "requires_separate_zipalign_check",
            "signing": "not_verified", "backend": "not_verified", "device_execution": "not_run",
            "ready_for_distribution": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--source-revision", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        report = inspect_apk(args.apk, args.source_revision)
        args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    except (ValueError, OSError, struct.error, zipfile.BadZipFile) as error:
        print(type(error).__name__, file=sys.stderr)
        return 1
    print("Release ELF alignment passed; signing, service and device gates remain separate")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
