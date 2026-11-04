# Actual MNN evaluation and human review

The reference oracle and controlled lifecycle adapter do not establish model quality.
`run_llm_device.py` executes the subjective fixtures using production ChatViewModel,
Room repositories, context assembly, Hybrid routing and the native MNN engine.
It only uses synthetic data and never calls a real cloud or authentication service.

## Prerequisites

- Windows Android build environment or an equivalent configured local environment.
- One authorized arm64 device. Keep its existing network configuration.
- At least 20% physical battery and power-saving mode disabled. Device preflight
  rejects a low or unreadable battery level before installation or model transfer.
- No installed `com.example.persona.validation` or `.validation.test` package.
- Cached `primary` and `secondary` model source directories. Their manifest hashes
  are pinned in the runner; all files are checked before transfer and again on device.
  They correspond to the previously accepted Qwen2.5 0.5B and 1.5B int4 packages.
- Enough device storage for source packages and installed copies. Nothing is downloaded.

The original `com.example.persona` application is never installed over, stopped or
uninstalled. Only packages successfully installed by this invocation are cleaned up.
If the standard ADB port is free, the runner owns a foreground server for the entire
SSH invocation and terminates only that child at cleanup. An existing server is reused.

## Run

From the repository root, using a fresh run ID each time:

```powershell
python -B tools/persona-eval/run_llm_device.py `
  --serial DEVICE_SERIAL --run-id m5-local-dev-01 --split dev `
  --model-root build/m1-model-sources
```

The runner builds the isolated APKs, preserves the source snapshot and APK hashes,
checks the installed APK hashes, and records a frozen configuration before generation.
It does not modify production model selection or sampling defaults.

The device uses the actual application prompt, not the planned prompt in
`config/baseline.v2.json`. Both arms use production GenerationParams:
temperature 0.6, topP 0.8, maxTokens 256. Other sampler fields retain the pinned
runtime and model configuration defaults. There is no supported seed control.
The complete implementation snapshot, model manifest/config hashes and intercepted
GenerationParams bind the result; this is not a claim of bit-identical reproducibility.

Each fixture/arm runs three times. Each round writes synthetic snapshots and preceding
conversation turns into a separate disk database, closes it, reopens it, and starts a
fresh ChatViewModel. The production engine resets the Raw ChatML request context.
The batch keeps the isolated app's CreatePersonaActivity visible with
`FLAG_KEEP_SCREEN_ON`. This activity hosts the foreground window while each round
uses its own Room/ChatViewModel fixture. Cleanup clears the window flag and finishes
the host. Receipts identify this foreground condition. Background survival and
process recovery remain separate measurements.
Expired/deleted/private records are filtered by production code, not the host oracle.
The latest revision of each fixture memory represents its single mutable database row.

Frames and failures are different measurements: a completed generation is not a quality
pass. Failed/stopped replies and unsupported cloud cases remain in the export.
Thermal admission waits are recorded; waiting for cooling is not sustained-load acceptance.
The batch checks battery, power-saving mode and thermal state before each model and
round. It waits up to ten minutes for valid resources, preserving the production
router's admission rules. New receipts record `resource_wait_ms` with before/after
battery and thermal values. This duration includes battery or power-saving waits.
Older `cooling_wait_ms` receipts retain their original thermal-only meaning.
The cloud-only case remains unsupported until a real cloud runner exists. It is never
silently executed with a local model or controlled fake response.

Dev is for development. Run heldout only after freezing the configuration:

```powershell
python -B tools/persona-eval/run_llm_device.py `
  --serial DEVICE_SERIAL --run-id m5-local-heldout-01 --split heldout `
  --model-root build/m1-model-sources
```

Do not tune against heldout failures and continue claiming the same heldout as unseen.

## Private artifacts and review

Artifacts are written under ignored `build/verification/m5-llm-RUN_ID/`.
`responses.json` contains full synthetic outputs; do not commit or print it.
The console only emits execution counts, statuses and hashes. Exit 1 means human
review is pending, not a successful release. Exit 2 indicates a tool/build/device error.

Create a fresh offline blind-review packet:

```sh
python3 tools/persona-eval/review_packet.py \
  --input build/verification/m5-llm-RUN_ID/responses.json \
  --output build/verification/m5-review-RUN_ID
```

Open `review.html` and expand the scoring form for each pair. It stores form input
locally when the browser allows it; use the download button to preserve annotations.
Alternatively edit `human-review.json`. Keep `private-mapping.json` and
`private-configs.json` closed until the blind review is complete.

The page escapes model text, has no external requests, and restricts its sole script
using a CSP hash. It never infers scores or approves a release. Partial exports retain
`pending_human_review`; they must not be renamed to a passing result.
Follow [HUMAN_REVIEW.md](HUMAN_REVIEW.md) for must/must-not checks, 0–2 dimensions,
reviewer identity and second review of critical violations or disagreements.

Validate a submitted annotation file without inventing missing scores:

```sh
python3 tools/persona-eval/check_human_review.py \
  --template build/verification/m5-review-RUN_ID/human-review.json \
  --annotations /path/to/downloaded-human-review.json \
  --mapping build/verification/m5-review-RUN_ID/private-mapping.json \
  --output build/verification/m5-review-RUN_ID/annotation-validation.json
```

The checker preserves human-reported defects individually. Complete annotations still
require an owner assessment; scores are never filled from keywords or another model.
Cloud-only probe packets contain one configuration and cannot establish an A/B winner.

Debug UI frame observations from `P2LongReplyInstrumentedTest` are separate from
model outputs and release benchmarks. A release performance comparison should use
a representative build and fixed workload; see the
[Android Macrobenchmark guidance](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview).
