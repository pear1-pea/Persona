# Testing

## Android unit tests

Location: `app/src/test/java/com/example/persona/`, mirroring the production package.

Stack: JUnit 4, Mockito-Kotlin, `kotlinx-coroutines-test`, `androidx.arch.core:core-testing`.

```bash
./gradlew testDebugUnitTest
```

Coverage currently includes repositories (`HybridAiRepository`, `CloudChatRepository`,
`RoomChatRepository`, `SwitchingPersonaRepository`, …), mappers, `AuthManager`,
`SettingsManager`, prompt adapters, and feature ViewModels.

## Instrumented tests

Location: `app/src/androidTest/java/com/example/persona/`.

```bash
./gradlew connectedDebugAndroidTest   # needs a connected device/emulator
```

The MNN/JNI plan is in [`docs/qa/native-test-plan.md`](../qa/native-test-plan.md).
JVM tests use `NativeSessionFactory` to exercise the engine lifecycle without loading
the native library.
The opt-in isolated package and real dual-model lifecycle command are documented in
the native plan; dated [M1 acceptance](../qa/m1-status-2026-10-28.md) records the initial
lifecycle results. [P1 acceptance](../qa/p1-status-2026-10-28.md) records the subsequent
session, SAF and performance acceptance separately.
[P2 first-batch acceptance](../qa/p2-product-flow-status-2026-10-29.md) records product
refresh, strict creation validation, recreation and interrupted-message recovery.

The isolated device classes `data.local.InterruptedMessageRecoveryInstrumentedTest`
and `features.ProductFlowInstrumentedTest` cover six cases. Run
`features.ChatProcessRecoveryInstrumentedTest` separately with
`-e p2RecoveryPhase prepare`, force-stop only `com.example.persona.validation`, then
run with `-e p2RecoveryPhase verify`. Require each phase's `OK (1 test)`; preparation
alone does not establish recovery. Fixtures are synthetic and validation-package-only.

## Backend

```bash
cd backend
npm ci
npm run typecheck
npm test          # validation schemas, security helpers, DTO mapping
TEST_DATABASE_URL=postgres://test_user@127.0.0.1:5432/persona_test npm run test:integration
npm run smoke     # two-user authorization/ownership acceptance, needs a running backend
```

`npm test` has no database dependency. `npm run smoke` creates two temporary users and
verifies public/private persona isolation, cross-user deletion rejection, session
listing and account deletion.

`test:integration` requires a disposable PostgreSQL database with schema/extension
creation rights. It uses random schemas, a fake key and a controllable local upstream;
it never calls DeepSeek. It covers stream termination, cancellation, backpressure,
parameter forwarding and atomic budget accounting, plus the existing auth/persona
smoke. Without `TEST_DATABASE_URL` the database tests skip, which is not acceptance.

## CI

`.github/workflows/ci.yml` runs:

- Android `assembleDebug` + `assembleDebugAndroidTest` + `testDebugUnitTest`.
- Backend `npm run typecheck` + `npm test`.
- Backend Postgres service + `npm run test:integration` + `npm run smoke`.
- Model packager `python -m unittest discover`.
- Synthetic Persona evaluation schema, Python tests and reference contracts
  (candidate workflow; no P2 remote run yet).
- A manual `workflow_dispatch` Windows build and JVM tests.

Available logs and reports are retained on both success and failure. Configuration
inspection alone does not establish that a remote workflow passed.

The Windows build is manual because it is slower and primarily validates the local
NDK/CMake path; it is not required for every pull request.

## Baseline

Fill [`docs/qa/baseline.md`](../qa/baseline.md) on the Windows machine after the first
run. The Mac used for source editing does not have an Android SDK, so it cannot verify
the Android build or execute its Gradle JVM suite. Framework-free Kotlin tests may
run separately with the matching compiler/JUnit versions; those results do not replace
Android compilation, repository/ViewModel tests, or device acceptance.

Use `scripts/verify-windows.ps1` for timestamped command logs and a JSON summary.
`-RunLint` is advisory. `-RunBackendSmoke` also requires an explicitly configured,
running test backend; otherwise smoke remains Pending.

## Rules

P2 memory candidate checks and Windows handoff are recorded in
[p2-memory-status-2026-10-29.md](../qa/p2-memory-status-2026-10-29.md).
The synthetic evaluation job runs schema validation, Python tool tests and reference
contracts only; it never invokes a model or establishes Android application conformance.
Run locally with `python3 -m unittest discover -s tools/persona-eval/tests -v`.

The M5 component adapter candidate and its Mac checks are recorded in
[p2-adapter-status-2026-10-30.md](../qa/p2-adapter-status-2026-10-30.md).
`tools/persona-eval/run_device.py --serial <serial> --run-id <new-id>` builds and
runs the isolated `evaluation.M5RoomAdapterInstrumentedTest`; no model is needed.
It refuses preinstalled validation packages, binds source/bundle/APK receipts and
only removes packages it successfully installed. Compare exits 1 for unsupported
cases even when the observable static cases pass. Component observations are not
executor dispatch or model-quality results. The v2 adapter passed 14 static device
cases with 12 unsupported; [Windows acceptance](../qa/p2-adapter-windows-status-2026-10-30.md)
records the initial null-serialization failure, fix, APK receipts and cleanup.

Add `--with-lifecycle --controlled-network` for v4 request orchestration checks on
a metered hotspot. Production still reads the physical network; only the explicit
test mode injects policy states, with a mandatory metered-denial control and no
real cloud/model execution. [Lifecycle acceptance](../qa/p2-lifecycle-status-2026-10-30.md)
records 20 pass / 6 unsupported, 412 JVM tests and the late-notification/source-deletion fixes.

`P2MemoryRuntimeInstrumentedTest` is opt-in (`runP2MemoryRuntime=true`) and only
accepts `com.example.persona.validation`. It uses real Room, ChatViewModel and MNN,
with cloud blocked. Provision a verified private `files/model-sources/primary`
package first; `memoryModelSource=secondary` selects the existing 1.5B fixture.
`memoryProbeTemperature=0` is a diagnostic test override, never a production result.
The test reopens its database between save/revise/delete stages and waits at most
three minutes per stage for severe heat to subside, without bypassing admission.
Run IDs preserve both failures and successes; export private synthetic artifacts
before uninstalling the isolated packages. This one scenario does not cover the
full M5 dataset or replace human review.

For fixed subjective fixtures, `tools/persona-eval/run_llm_device.py` builds the isolated
APKs and executes the real Room/ChatViewModel/MNN path using cached pinned model packages.
It freezes configuration/source receipts and retains failed or unsupported rows.
See [the actual-model workflow](../../tools/persona-eval/REAL_MODEL.md) and
[P2 closure checks](../qa/p2-closure-status-2026-10-30.md). The CI reference job does not run it.
`review_packet.py` creates local anonymous human forms; `check_human_review.py` validates
submitted annotations without assigning scores or approving a release.
The [2026-11-04 Mac checks and human results](../qa/p2-review-status-2026-11-04.md)
record the subsequent network-policy race regression and remaining model-quality defects.
The earlier Windows build applies to its recorded source snapshot. Rebuild and rerun the
full Gradle suite after the new production fix.

No-content endpoint regression tests use real Retrofit with a controlled HTTP transport:
`NoContentResponseIntegrationTest` covers 204 success and rejected HTTP requests.
Do not replace these with only mocked AuthApi success; that misses Retrofit's body adapter.

- When changing app behavior, add or update a unit test in the matching package.
- When changing auth, ownership, or chat proxying, also run the backend smoke test.
- Do not land changes that fail `./gradlew testDebugUnitTest`.

## P3 memory acceptance

P3 adds default-off candidate curation, strict model extraction, keyword retrieval
and extractive summaries. Run Room migrations and actual repository boundaries on
an isolated validation install before UI or native acceptance.

- `data.local.MemoryCurationInstrumentedTest`, `MemoryCurationFlowInstrumentedTest`,
  `MemoryExtractionBoundaryInstrumentedTest`, `MemoryMigrationInstrumentedTest`
  and `MemoryDaoInstrumentedTest` cover storage, authorization and invalidation.
- `features.P3UiAcceptanceInstrumentedTest` requires `runP3Ui=true`. It exercises
  real Espresso input, source dialogs, expiry, Activity recreation, conflicts and
  explicit confirmation. The shared fixture keeps taps clear of system gestures.
- `features.P3CurationRecoveryInstrumentedTest` uses `p3RecoveryPhase=prepare` and
  `verify`, with `runP3Ui=true`. After READY, force-stop only the validation package
  and pass its old PID as `p3ForceStoppedPid`. Its RUNNING fixture is synthetic.
- `features.P3ModelRuntimeInstrumentedTest` requires `runP3Model=true`, a fresh
  `p3RunId`, pinned private model sources and the fixed synthetic extraction corpus.
  Parser success and semantic correctness are reported separately.
- `features.P3NativeRecoveryInstrumentedTest` requires `runP3Native=true`, a fresh
  `p3NativeRunId`, `p3NativePhase=prepare|verify` and the pinned secondary model.
  Prepare tests real MNN preemption, then emits READY during another live extraction.
  Force-stop validation within 45 seconds and verify with `p3ForceStoppedPid`.
  Recovery must clear RUNNING tasks without model calls or automatic memory writes.
- `features.P3RetrievalPerformanceInstrumentedTest` requires `runP3Perf=true`.
  Its independent 1,001-row fixture declares p95 <=250ms and retained Java heap
  growth <=16MiB before 5 warmups and 30 measurements. It measures actual Room
  retrieval, including ranking and revalidation, on the tested device only.

All device classes are validation-only. Export synthetic private observations and
UI screenshots before uninstalling the validation and test packages. Record source
and APK hashes for each run. Preserve failed runs when rerunning fixes. Native
quality defects do not become passing results because engineering boundaries pass.
Local model extraction remains a Debug experiment and every candidate needs user
confirmation. The release workflow offers rules, manual selection and separately
authorized cloud extraction. Embedding remains conditional on measured need.
