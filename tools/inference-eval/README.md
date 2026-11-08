# Inference telemetry observations

Python 3.11+ standard library CLI for the JSON **array** from
`FileInferenceJournal.exportJson()`. No new dependencies, Android build, network,
real model, or chat content needed. The persisted journal wrapper is not an export.
The small fixtures contain invented counters and hashes only; their workload and
revision hashes are placeholders, not real runs.

These reports are **not human or LLM quality judgments**. Semantic quality is
always `not_evaluated`. `ACCEPTED` means the recorded structural contract accepted
an attempt; it does not imply a correct, useful, safe, or higher-quality answer.
Execution outcomes and contract status have separate counts and rates.

Times cover one executor flow and its downstream collection, excluding route
selection, model preparation and context planning before that flow. The records
are a bounded best-effort sample; queue pressure, storage failure, process death
and retention can omit attempts. They do not establish application-wide success
rates or cloud billing. Missing provider identity and native counters remain null.

## Commands

Run from the repository root. The output must be a **new directory**, and its
parent must already exist. Existing directories (even empty), files, and symlinks
are refused. Each invocation writes only `report.json`; stdout prints a generic
success line, errors withhold input values, and raw export records are not copied.
`runs/` is ignored by git. Do not reuse output names.

```sh
mkdir -p tools/inference-eval/runs
python3 -B tools/inference-eval/evaluate.py summarize \
  tools/inference-eval/fixtures/baseline.json \
  --output tools/inference-eval/runs/summary-example

python3 -B tools/inference-eval/evaluate.py compare \
  tools/inference-eval/fixtures/baseline.json \
  tools/inference-eval/fixtures/candidate.json \
  --left-metadata tools/inference-eval/fixtures/baseline.metadata.json \
  --right-metadata tools/inference-eval/fixtures/candidate.metadata.json \
  --output tools/inference-eval/runs/compare-example

python3 -B -m unittest discover -s tools/inference-eval/tests -v
python3 -B tools/inference-eval/check_fixture.py
```

Exit 0 means a report was saved (including an explicitly `unmatched` comparison).
Exit 2 means validation or I/O failed. Invalid records fail the entire export;
there is no skip/filter mode and validation occurs before output creation.

## Input contract

All 19 fields emitted by current `JournalJson.writeAttempts` must occur exactly
once. Missing fields, extra fields (including Prompt, Response, owner), duplicate
JSON keys and duplicate `(requestHash, attempt)` identities fail. A request hash
must keep one task. No values are inferred from names or content.

- `requestHash` and non-null `modelFingerprint`: exactly 64 ASCII hex characters.
- `attempt`, `outputTokens`: integer 0 through `2^31-1`; all other numeric fields:
  integer 0 through `2^63-1`. Reject bool, floats/exponents, strings, NaN/Infinity,
  negative values and overflow. Zero is legal, including attempt index zero.
- Only the Kotlin nullable fields accept null: `modelFingerprint`,
  `firstTokenLatencyMs`, `outputTokens`, `nativeInputTokens`, `nativeOutputTokens`,
  `prefillMicros`, `decodeMicros`, `samplingMicros`, `errorType`, `stopReason`.
- Enums are exact and case sensitive, matching `InferenceTelemetry.kt`,
  `Backend.kt` and `GenerationEvent.kt`. Tests check wire field/enum drift.
- Each file is UTF-8 JSON, bounded to 512 KiB, matching the current journal size
  ceiling. Attempt indices need not be contiguous: retention can truncate history.
  The tool enforces numeric bounds, not a wall-clock age rule or routing policy.

Run sidecars require exactly these keys:

```json
{
  "label": "candidate-a",
  "workload_sha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
  "source_revision": "1111111111111111111111111111111111111111"
}
```

`label` is 1-80 ASCII letters, digits, dots, underscores or dashes;
`workload_sha256` is 64 hex characters; `source_revision` is a full 40- or 64-hex
revision. Hash the same canonical workload bytes for both runs. These are supplied
declarations: the export cannot prove workload identity, revision cleanliness,
identical hardware/settings, or complete workload coverage.

## Statistics and comparison limits

Summary includes all attempts, distinct logical requests, and joint
`task / backend / trigger` groups. Group request counts are non-additive: an
INITIAL MNN attempt and a CONTRACT_FALLBACK CLOUD attempt can belong to one
logical request. Logical request outcomes/statuses use the highest **observed**
attempt index, not an inferred final result. No Kotlin routing is reimplemented.

All five execution outcomes (`COMPLETED`, `FAILED`, `STOPPED`, `CANCELLED`,
`ABANDONED`) and all three contract statuses (`ACCEPTED`, `REJECTED`,
`NOT_EVALUATED`) include counts, denominators and rates. Empty denominators yield
null rates. Contract evaluation does not enter the execution completion rate.

Each metric reports mean, p50, p95, observed `n`, and missing/undefined count.
Percentiles use **nearest-rank**: sort n samples, select `ceil(p*n)-1` (zero-based);
empty distributions yield null. Null latency/token values never become zero.
All outcomes are included in metric distributions; these are not success-only
latencies. Fields retain their Kotlin names and units (Ms, Micros, token counts).

`overall_output_tokens_per_second` is the distribution of per-attempt
`outputTokens * 1000 / totalLatencyMs`, not decode speed or aggregate workload
throughput. A missing numerator or zero duration makes this rate undefined and
reduces n. An observed zero numerator with positive duration remains a valid zero.
`outputTokens` is the executor-supplied count. The current cloud executor uses
a conservative text estimate, not the provider tokenizer or billing usage;
`nativeOutputTokens` remains separate and unknown cloud native fields stay null.
Native prefill/decode/sampling timings are summarized separately; no decode
tokens/sec is inferred from overall duration or native timings.

Comparison emits **observed**, unpaired `right minus left` deltas only when:

1. Declared workload SHA-256 values match (hex case ignored).
2. Both exports are nonempty and the complete multiset of observed request route
   shapes matches: ordered `(task, backend, trigger, attempt index)` sequences.
   This also requires matching groups, their counts and attempts-per-request.
   Session hashes may differ across runs. No common-group intersection or partial
   matching is used. Any coverage mismatch makes the entire comparison `unmatched`
   with null delta groups, even if some groups would match.
3. A metric is observed/defined for **every attempt** in that group on both sides.
   Otherwise its delta is null / `not_comparable`; equal partial sample counts
   are insufficient. Other fully observed metrics may still have deltas.

Each matched group includes metric mean/p50/p95 deltas with sample counts and
separate execution/contract count/rate deltas with the attempt denominator.
Outcome distributions may change; that is an observation, not proof of a model
effect. Both full summaries remain available even on mismatch. There is no
overall mixed-group delta, significance test, causal claim or quality ranking.
Backend/routing changes therefore intentionally yield unmatched comparisons.

## Reproducibility and privacy

Reports save SHA-256 and byte lengths of the exact raw input files (including
sidecars), SHA-256 of the tool's root Python sources and the telemetry contract
sources (`InferenceTelemetry.kt`, `JournalJson.kt`, `FileInferenceJournal.kt`,
`Backend.kt`, `GenerationEvent.kt`), Python version, parsed CLI parameters, all-row
index bounds, observed start-time range and attempt-index range. Source hashes
identify the files used locally; sidecar revision is a separate run declaration.
There is no hidden sampling or filtering. Journal retention/size caps may already
have removed attempts before export; counts describe the retained sample only.

Reports contain aggregate metrics, model fingerprints, safe sidecar identifiers
and supplied paths; they omit request hashes and raw records. Keep run paths and
sidecar labels free of sensitive data. CI runs only this tool's unittest suite and
the synthetic CLI examples; it prints no export payloads and invokes no models.
