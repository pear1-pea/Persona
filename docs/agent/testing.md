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

- Android `assembleDebug` + `testDebugUnitTest`.
- Backend `npm run typecheck` + `npm test`.
- Backend Postgres service + `npm run test:integration` + `npm run smoke`.
- Model packager `python -m unittest discover`.
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

- When changing app behavior, add or update a unit test in the matching package.
- When changing auth, ownership, or chat proxying, also run the backend smoke test.
- Do not land changes that fail `./gradlew testDebugUnitTest`.
