# Persona model packager

The Android app requires a `manifest.json` with **size + SHA-256 for every package
file**, plus a `READY` marker. MNN model downloads do not include this file. This tool
is the missing authoring step.

It does not download or convert models. It takes an existing MNN model directory and
produces the package metadata the app expects.

## Requirements

- Python 3.8+
- An MNN model directory containing at least:
  - `config.json`
  - the file referenced by `config.json.llm_model`
  - the file referenced by `config.json.llm_weight`
  - a tokenizer (`tokenizer.txt`, `tokenizer.mtok`, `tokenizer.json`, …)

## Generate

From the repository root:

```bash
python3 tools/model-packager/generate_manifest.py \
  /path/to/Qwen2.5-0.5B-Instruct-MNN \
  --id qwen2.5-0.5b-instruct-mnn \
  --name "Qwen2.5 0.5B Instruct" \
  --version 1.0.0 \
  --family QWEN2_5 \
  --prompt-format QWEN_CHATML_TEXT \
  --context-window 32768 \
  --min-ram-gb 4 \
  --min-sdk 24 \
  --required-abi arm64-v8a
```

This writes:

```text
/path/to/Qwen2.5-0.5B-Instruct-MNN/
├── config.json
├── manifest.json     # new
├── READY             # new
├── llm.mnn
├── llm.mnn.weight
└── tokenizer...
```

Useful flags:

| Flag | Purpose |
| --- | --- |
| `--tokenizer` | Explicit tokenizer path; otherwise auto-detect |
| `--output` | Write the manifest somewhere other than the model dir |
| `--force` | Overwrite an existing manifest |
| `--no-ready` | Do not write `READY` (the SAF installer writes it) |
| `--json` | Machine-readable result |

The generator:

1. Parses `config.json`.
2. Requires `llm_model` / `llm_weight`.
3. Auto-detects the tokenizer.
4. Rejects absolute paths, `..`, symlinks and files outside the model directory.
5. Hashes **every regular file** (not only the required ones) with SHA-256.
6. Writes a deterministic (sorted) manifest.
7. Writes `READY`.

Runtime compatibility is represented by `schemaVersion` / `backend`; the current app is
pinned to MNN 3.6.1. The packager does not inspect the `.mnn` binary for operator or
runtime compatibility.

## Validate

```bash
python3 tools/model-packager/validate_package.py \
  /path/to/Qwen2.5-0.5B-Instruct-MNN \
  --require-ready
```

It checks:

- `manifest.json` schema and required fields.
- `id` / ABI / backend / family / prompt format.
- `config.json` entry matches `manifest.entry`.
- Every listed file exists, has the expected size and SHA-256.
- Required paths (`config.json`, entry, weight, tokenizer) are covered.
- Path traversal, duplicate paths and symlinks.
- `READY` when `--require-ready` is passed.

Exit code is `0` only when there are no errors. Warnings (for example, missing
`READY`) do not fail validation.

## Install into Persona

### Option A: in-app SAF import (recommended)

1. Copy the whole model directory to the device (Downloads/Documents).
2. Open **Profile → Settings → 本地模型管理 → 安装模型**.
3. Pick the model directory.
4. The app copies to staging, validates, writes `READY`, and atomically renames it.

### Option B: manual side-load

Copy the **whole** directory (including `manifest.json` and `READY`) to:

```text
/storage/emulated/0/Android/data/com.example.persona/files/models/<model-id>/
```

Then open the model manager and refresh.

Do not copy only `config.json` / `.mnn` files. The app will report `NotInstalled` or
`缺少 manifest.json`.

## Manifest shape

```json
{
  "schemaVersion": 1,
  "id": "qwen2.5-0.5b-instruct-mnn",
  "name": "Qwen2.5 0.5B Instruct",
  "version": "1.0.0",
  "backend": "MNN",
  "family": "QWEN2_5",
  "promptFormat": "QWEN_CHATML_TEXT",
  "entry": "llm.mnn",
  "tokenizer": "tokenizer.txt",
  "contextWindow": 32768,
  "minRamGb": 4,
  "minSdk": 24,
  "requiredAbis": ["arm64-v8a"],
  "files": [
    {
      "path": "config.json",
      "role": "config",
      "size": 123,
      "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
  ],
  "signature": null
}
```

`manifest.example.json` is a template with placeholder values. It is **not
installable**; run the generator against a real model directory to produce a real
manifest.

A complete generated example lives in
[`examples/minimal-fixture/`](examples/minimal-fixture/). It is a structural fixture
(tiny text files, not a runnable LLM) used to demonstrate a real manifest and to keep
the validator testable:

```bash
python3 tools/model-packager/validate_package.py \
  tools/model-packager/examples/minimal-fixture --require-ready
```

The manifest hashes bytes, so the repository marks this fixture as `-text` in
`.gitattributes`. If you commit your own package fixture, do the same; otherwise Git's
CRLF conversion on Windows will invalidate the hashes.

## Signature

`signature` is reserved in the current schema and the Android app does not verify it
yet. The manifest’s SHA-256 values only prove that files match the manifest; if an
attacker can replace both the files and the manifest, they can bypass that check.
For distribution, put the manifest or its hash in a trusted channel and verify it
outside the model package.

## Tests

```bash
python3 -m unittest discover -s tools/model-packager/tests -v
```
