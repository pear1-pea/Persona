# Build & Test Baseline

This document is filled on **Windows** (or CI), not on the Mac used for source edits.
It is intentionally undated so the same checklist can be reused after environment or
toolchain changes.

Latest recorded run: [2026-10-28 P1 status](p1-status-2026-10-28.md).
The blank results below are a reusable checklist, not the status of that run.

Run the evidence collector from the repository root:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-windows.ps1 -RunLint
```

The script writes per-step logs and `summary.json` under the git-ignored
`build/verification/windows/<run-id>/` directory. Required missing tools and failed
commands fail verification. Lint is advisory; omitted optional checks remain Pending.
For an already-running isolated backend, set `PERSONA_BACKEND_BASE_URL` and add
`-RunBackendSmoke`. This script does not create a database or run native device tests.

## 1. Environment

| Item | Expected | Check |
| --- | --- | --- |
| JDK | 17 | `java -version` |
| Android SDK | platform 36, build-tools 36.0.0 | `sdkmanager --list_installed` |
| NDK | `27.0.11718014` | `sdkmanager --list_installed` |
| CMake | 3.22.1 | `sdkmanager --list_installed` |
| Node | 20 LTS (or newer) | `node -v` |
| PostgreSQL | 16 (local or Docker) | `psql --version` |

`local.properties` (repository root, **never commit**):

```properties
sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk
PERSONA_BACKEND_BASE_URL=https://api.example.cn/
DEEPSEEK_MODEL_ID=deepseek-v4-flash
```

Notes:

- Keep the checkout on a short path such as `C:\dev\Persona`; MNN/CMake on Windows is
  sensitive to long paths.
- `third_party/MNN` is an optional submodule. If it is empty, CMake downloads MNN 3.6.1.
- The app currently ships only `arm64-v8a`. A standard x86_64 Windows emulator cannot
  run the local model; use a physical arm64 device for model/JNI testing.
- P1 acceptance uses the currently verified device/model/configuration scope. A 4 GB
  phone is a demand-driven future expansion, not a P1 blocker or a verified target.
  The dated P1 report includes SAF UI import, a five-minute paced workload and sampled
  RSS/PSS, including the thermal limits. Resource admission and cloud/privacy
  restrictions still apply; do not infer continuous full-load support from that run.

## 2. Android

```powershell
.\gradlew.bat --version
.\gradlew.bat assembleDebug --stacktrace
.\gradlew.bat testDebugUnitTest --stacktrace
.\gradlew.bat lintDebug
```

Record `lintDebug` as a baseline only; do not make it blocking until the existing
issues are triaged.

## 3. Backend

```powershell
cd backend
npm ci
npm run typecheck
npm test
```

`npm test` covers validation schemas, session-token helpers and DTO mapping. It does
not need a database.

## 4. Backend integration smoke

Requires a running PostgreSQL database and the backend process:

```powershell
$env:DATABASE_URL = "postgres://postgres:postgres@127.0.0.1:5432/persona"
psql $env:DATABASE_URL -v ON_ERROR_STOP=1 -f backend/schema.sql

# terminal A
cd backend
npm run dev

# terminal B
$env:PERSONA_BACKEND_BASE_URL = "http://127.0.0.1:8080"
cd backend
npm run smoke
```

The smoke covers registration, public/private Persona isolation, cross-user delete
rejection, session listing and account deletion. It does **not** exercise the DeepSeek
chat proxy (that requires a real upstream key).

The isolated chat proxy/budget suite uses PostgreSQL and a controllable fake upstream:
set `TEST_DATABASE_URL` to a disposable database and run `npm run test:integration`.
It does not require a real key; see [backend test instructions](../../backend/README.md).

## 5. Results

| Command | Result | Time | Notes |
| --- | --- | --- | --- |
| `gradlew.bat assembleDebug` | Pending | | |
| `gradlew.bat testDebugUnitTest` | Pending | | |
| `gradlew.bat lintDebug` | Pending | | existing issues: |
| `npm ci` | Pending | | |
| `npm run typecheck` | Pending | | |
| `npm test` | Pending | | 13 tests expected |
| `python -m unittest discover -s tools/model-packager/tests` | Pending | | 11 tests expected |
| `npm run smoke` | Pending | | requires PostgreSQL |

## 6. Known failures

Fill this section instead of hiding failures behind retries:

- Android:
- Backend:
- CI:

## 7. What is verified where

This table assigns checks to environments. Actual completion is recorded in the
dated report; a connected device alone does not verify native inference.

| Layer | Mac (source editing) | Windows | CI |
| --- | --- | --- | --- |
| Kotlin compilation | Not verified (no SDK) | Yes | Ubuntu |
| JVM unit tests | Not verified (no SDK) | Yes | Ubuntu |
| MNN/JNI runtime | No | Requires arm64 device acceptance | Device pipeline only |
| Backend typecheck/tests | Yes | Yes | Ubuntu |
| Backend smoke | No (no DB) | Yes, local Postgres | Postgres service |
| Real-device thermal/OOM | No | Requires sustained device tests | Manual/device |
