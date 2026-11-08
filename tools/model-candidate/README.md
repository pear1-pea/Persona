# Fixed Qwen3 native research probe

The current production validator does not admit QWEN3. These tools prepare an
independent, validation-package-only JNI experiment before product integration.
They do not change the default model, production family registry or memory consent.

The fixed candidate is Qwen/Qwen3-0.6B at
`c1899de289a04d12100db370d81485cdf75e47ca`, with MNN
`d407447ed56c4121a11ccbd266dc184ca1ead0c2`, CPU INT8 weights/lm and a separate
BF16 embedding. `source-lock.json` pins the ten HF source files and the exact
11-file P4 package receipt. This local experiment lock is not a signed release.

## Prepare on Mac

Use Python 3.11+ for the standard-library contracts. The actual reference run was
Python 3.14 in an independent venv, with the three versions in
`requirements-reference.txt`. It loads only the local tokenizer, never weights,
remote code or a model execution framework.

```sh
python3 -m venv /path/to/new-reference-venv
/path/to/new-reference-venv/bin/python -m pip install -r tools/model-candidate/requirements-reference.txt
mkdir -p build/verification/qwen3
/path/to/new-reference-venv/bin/python -B tools/model-candidate/prepare.py \
  --snapshot /path/to/fixed-hf-snapshot \
  --package-receipt /path/to/p4-package-fullfile-receipt.json \
  --receipt-only \
  --deps /path/to/existing-kotlin-junit-gson-jars \
  --java-home /path/to/jdk17 \
  --output build/verification/qwen3/new-reference-run
```

The snapshot must contain the ten exact files from the lock. The receipt is copied
byte-for-byte: changing its newline convention changes its pinned SHA-256.
`--receipt-only` explicitly reports `receipt_only_model_files_not_checked`.
Use mutually exclusive `--package /path/to/model` when the actual package is
available locally. Full package validation uses POSIX no-follow descriptors on
macOS/Linux; the future host driver has independent portable preflight checks.

Preparation compiles the actual `MemoryExtraction.prompt`, parser and current
planning renderer with the supplied cached Kotlin compiler/Gson jars. It renders
HF references with `enable_thinking=False`, checks both tokenization paths agree,
and reserves output + 56 tokens within a conservative 4096-token experiment window.
The window is a test budget, not the model's maximum supported context.

The fresh output contains `bundle.json`, `receipt.json`, `reference-summary.json`,
compiler logs, source/dependency hashes and provenance. Raw reference text and IDs
are synthetic and private; put outputs under ignored `build/` or `runs/`.
Output directories are never reused. The source revision hashes `app/src/main`, `app/src/test`, `app/src/androidTest`
and five build files through the existing snapshot tool. It is not a Git commit,
complete build-input attestation or APK verification.

## Mac checks

```sh
python3 -B -m unittest discover -s tools/model-candidate/tests -v
python3 -B tools/model-candidate/check_mac.py \
  --java-home /path/to/jdk17 --deps /path/to/cached-jars \
  --bundle-run build/verification/qwen3/new-reference-run \
  --output build/verification/qwen3/new-kotlin-check
```

The checker compiles actual portable Kotlin sources, including the strict Android
probe protocol, and reads the generated HF bundle through that protocol. It uses
an explicit `BuildConfig.DEBUG` boundary in both variants. This is not Android
compilation, native loading, or device/model quality acceptance. A source change
invalidates the bundle; prepare a fresh run after the final source is frozen.

## Windows and phone execution

Synchronize the exact candidate source snapshot into the isolated QA checkout.
Use the existing pinned P4 model directory and its receipt. Configure the Android
SDK/NDK/JDK as usual, unlock and authorize an arm64 phone, and run from the repo:

```powershell
python -B tools/model-candidate/run_probe.py `
  --adb "C:/path/to/Android/Sdk/platform-tools/adb.exe" `
  --serial DEVICE_SERIAL --run-id qwen3-new-unique-run `
  --bundle "C:/path/to/reference-run/bundle.json" `
  --receipt "C:/path/to/reference-run/receipt.json" `
  --package "C:/path/to/p4-qwen3-int8-model" `
  --output "C:/path/to/new-private-result-directory" --timeout 2100
```

The driver builds with `persona.isolatedValidation=true` using argument lists,
checks source/bundle/package identity, verifies isolated APKs, refuses existing QA
installs, provisions only its private run directory and hashes the transferred files.
Actual APK manifest package names are checked with SDK `aapt2 dump packagename`
before installation. Only packages it successfully installed are stopped/uninstalled. Existing ADB servers
are preserved. Timeout cleanup force-stops QA and retains available observations.
`apk-receipt.json` records APK and arm64 JNI SO hashes. The runtime revision remains
`declared_target_only` until the actual CMake source/override is separately verified.
Windows metadata stamps use birthtime instead of deprecated ctime for cross-API
comparison. An owned ADB listener uses `tcp:5037`, with loopback clients and a
bounded 15-second startup handshake. Existing ADB servers are preserved.

The instrumentation entry is
`com.example.persona.core.ai.mnn.Qwen3NativeProbeInstrumentedTest` with explicit
`runQwen3Probe`, `qwen3RunId`, `qwen3BundleSha256`, `qwen3SourceRevision` arguments.
It calls `NativeMnnSession` directly, without product admission, routing or persistence.
Battery >=20%, thermal below SEVERE, power saving off and no system low-memory
condition are required. Available RAM, native PSS and process RSS/VmHWM observations
are recorded separately; process VmHWM is not a model-specific memory peak. Waits and
native calls are bounded. The host deadline is independent of the 30-minute JUnit
limit; unresolved JNI work requires force-stop of the validation package.

## Read results

Every case first compares actual native chat-template IDs with the HF reference
and checks the exact input/output budget. Three smoke cases cover Chinese, emoji,
multiple turns and `/think`. Six extraction cases reuse the fixed P3 inputs and
production strict decoder. No repair, markdown stripping or prompt retuning is used.
Callback stop and reset/recovery are separate engineering checks.

- `engineering_status` covers template, budget, generation and cleanup.
- `quality_status=passed_fixed_six` requires six completed exact type/content
  matches, no semantic-negative false extraction and no reached output cap.
- `not_evaluated_incomplete` preserves missing execution; it is not a quality score.
- A zero driver exit means an engineering experiment completed. Read quality
  separately; `candidateAdopted` remains false even if these six cases pass.

These small fixtures do not establish general quality or full App integration.
Product admission, context planning, account/persona boundaries and comparative
resources still need their own gates. No new human scoring is required.

The [2026-11-08 device experiment](../../docs/qa/qwen3-native-probe-windows-2026-11-08.md)
completed engineering checks after fixing stale cancellation at the new-request
boundary. All six extraction outputs failed strict decoding; the candidate was
not adopted. Retain that failure rather than repairing outputs or retuning the fixtures.

## Sources

- [Fixed Qwen model/template contract](https://huggingface.co/Qwen/Qwen3-0.6B/blob/c1899de289a04d12100db370d81485cdf75e47ca/README.md)
- [Fixed MNN public LLM API](https://github.com/alibaba/MNN/blob/d407447ed56c4121a11ccbd266dc184ca1ead0c2/transformers/llm/engine/include/llm/llm.hpp)
- [Pinned Transformers template implementation](https://github.com/huggingface/transformers/blob/v4.51.3/src/transformers/utils/chat_template_utils.py)
