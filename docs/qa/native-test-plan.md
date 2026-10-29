# Native / Device Test Plan (MNN + JNI)

JVM unit tests cover the engine state machine through `NativeSessionFactory`. They do
not load the MNN shared library and cannot prove that real inference works.

Run this plan on an **arm64 physical Android device** with a valid model package.
Current support and acceptance scope is limited to verified device/model/configuration
combinations and cases recorded in the dated reports. Use the opt-in P1 suites below
for session boundaries, SAF UI import, sustained testing and sampled memory.

## Prerequisites

- A model package that passes `LocalModelValidator`.
- `adb devices` shows the device.
- The model is selected in **Profile → Settings → 本地模型管理**.

## Cases

1. **Cold load**
   - Kill the app, open it, select the local model.
   - Expect: load completes, smoke test passes, chat reports `LOCAL READY`.
   - Record: load time, peak RSS, thermal status.

2. **Short generation**
   - Send a short message.
   - Expect: streamed tokens, a completed message, no duplicate rows.
   - Record: TTFT, decode tok/s, total time.

3. **Cancel in flight**
   - Send a long prompt, tap stop after the first tokens.
   - Expect: generation stops, partial content is persisted once, next send works.
   - Record: cancel latency, whether the native session remains usable.

4. **Repeat after cancel**
   - Send another message immediately.
   - Expect: no deadlock, no `MNN session has not been loaded`, no duplicate output.

5. **Model switch**
   - Switch to a second installed model (or reinstall the same model).
   - Expect: old session is closed, new model loads, no stale KV/cache output.

6. **Delete while idle**
   - Delete the current model from model management.
   - Expect: engine is released before files are removed, no crash, directory is gone.

7. **Delete during generation**
   - Start a long generation, then delete the current model.
   - Expect: generation is stopped, engine released, then files removed. No crash.

8. **Background / foreground**
   - Start a generation, press Home, return.
   - Expect: state is consistent; no duplicate messages; no leaked session.

9. **Thermal / sustained**
   - Run 5 minutes of repeated short generations.
   - Record: tok/s at start/end, thermal status, throttling, OOM.

10. **Resource admission**
    - Cover insufficient-resource decisions in controlled tests and verify the current
      device's admission/error UI when reproducible resource-pressure conditions exist.
    - Expect: unsafe loads are refused with a clear explanation; any cloud fallback
      obeys the user's cloud and privacy settings.

11. **Overlapping model operations**
    - Request delete and selection close together, including a non-current model.
    - Expect: operations serialize; a removed model cannot be loaded; failures leave
      an accurate model list. Repeat with two delete requests.

12. **Stop during startup and retry**
    - Stop while a request is initializing, then send another request.
    - Expect: the stopped request never starts later against a replacement model,
      does not start cloud fallback, and does not block the next request.
    - Reselect the current model after a temporary failure; expect a fresh retry.

## Acceptance

- No JNI crash, no `UnsatisfiedLinkError`, no deadlock.
- Cancel and release always leave the engine in a usable state.
- Deleting a model never runs while the native session is still active.
- Peak RSS and sustained tok/s are recorded for the same device/model/quantization.

## Opt-in isolated lifecycle acceptance

Build a separate debug package on Windows PowerShell:

```powershell
.\gradlew.bat '-Ppersona.isolatedValidation=true' testDebugUnitTest assembleDebug assembleDebugAndroidTest
```

The quoted property is required in PowerShell. Check `applicationId` in each APK's
`output-metadata.json` before installation: the target must be
`com.example.persona.validation`, and the test APK must target `.validation.test`.
The normal `com.example.persona` app and its data must remain untouched.

Provision two distinct, validated MNN packages under the validation app's private
`files/model-sources/primary` and `files/model-sources/secondary` directories. ADB
cannot write directly into private app storage: stage each file in `/data/local/tmp`,
copy it with `run-as com.example.persona.validation`, compare its SHA-256, and remove
the temporary file. Include the manifest, READY marker, and every manifest-listed
file; preserve relative paths. Never provision into the original app's directories.

```powershell
adb -s <serial> install -r app\build\outputs\apk\debug\app-debug.apk
adb -s <serial> install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb -s <serial> shell am instrument -w -r -e class com.example.persona.core.ai.M1RuntimeInstrumentedTest -e runM1Runtime true -e primarySource primary -e secondarySource secondary com.example.persona.validation.test/androidx.test.runner.AndroidJUnitRunner
```

This test deletes installed models **inside the validation app** before each run,
then exercises staging/validation/atomic installation, real JNI load and generation,
cancel/reuse, switching, explicit transient-failure retry, and idle/in-flight deletion.
It retains the private source packages until the validation app is uninstalled.
Cloud transport is disabled; no account credentials are supplied.

Require `OK (1 test)` and completion of all phases in the `M1RuntimeAcceptance` tag.
ADB exit code zero alone does not prove JUnit success. The test is skipped without
explicit opt-in and refuses to run under the original application ID. Each phase has
a wall-clock deadline; if native code remains blocked, force-stop only the validation
package before another run. Preserve failure logs before retrying.

This is not SAF picker/UI acceptance, Room persistence acceptance, a sustained thermal
test, or a performance benchmark. Activation includes validation and smoke; TTFT is
call-to-first-delta; reported token rate includes prefill; PSS samples are not peak RSS.
Use the manual cases above to close those remaining coverage gaps.

## Opt-in P1 acceptance

Use the same isolated build and source provisioning described above. Run one
instrumentation session at a time, retain the complete output, and require the
expected `OK (N tests)` with no skipped tests or crashed process.

| Class (under `com.example.persona`) | Opt-in arguments | Coverage |
| --- | --- | --- |
| `data.local.OwnerIsolationInstrumentedTest` | None | Real Room owner deletion and insert boundaries |
| `core.auth.SessionCleanupInstrumentedTest` | None | Durable owner cleanup markers |
| `core.ai.MarkdownPrivacyInstrumentedTest` | None | No remote image spans or actionable links |
| `core.ai.P1SafInstrumentedTest` | `runP1Saf=true`, `primarySource=primary`, `secondarySource=secondary` | Real ContentResolver reads, bad/cancelled/interrupted packages |
| `core.ai.mnn.P1SamplingInstrumentedTest` | `runP1Sampling=true`, `samplingSource=primary` | Effective native sampler and output limits |
| `core.ai.P1PerformanceInstrumentedTest` | `runP1Performance=true` | At least five minutes of real 0.5B generation and 500 ms RSS/PSS sampling |
| `core.ai.P1PickerInstrumentedTest` | `runP1Picker=true` | User-facing system directory grant, installation, selection and reselection |

Supply arguments as `-e <name> <value>` to `am instrument`. These suites use only the
validation app's installed models and synthetic prompts. Lifecycle/performance/picker
tests reset that app's installed models; its private source fixtures remain available.

The SAF fixture provider lives in `src/debug` and is enabled only by the isolated
validation property. Its control API requires the validation package and same UID;
normal debug disables it and release excludes it. These six provider tests exercise
real file APIs, not a cross-UID document grant. The picker test supplies that separate
system-UI path: stage a validated package in an owned nested Download folder and start
the test. It clicks the real **安装模型** button; after `P1_PICKER_READY`, select that
directory in DocumentsUI and grant access. The test validates the installed package
and exercises current-model retry.
Retrieve its private `files/p1-picker-ui.png` with binary-safe `adb exec-out run-as`.
Remove the public fixture directory after acceptance.

Performance records native input/output counts and prefill/decode/sampling microseconds.
`generationRate` includes prefill and uses the app's existing estimate; calculate pure
decode rate separately from native counters. Sampled RSS/PSS is a lower-bound peak at
500 ms resolution; `VmHWM` covers that instrumentation process's lifetime. Report
load+smoke separately from first request, since changing sampler settings reloads MNN.
System thermal status and throughput changes do not by themselves establish causality.
Start with a device below SEVERE; the benchmark stops at CRITICAL or above. Optional
`performanceMaxTokens` (default 64, range 1–256) and `performanceDelayMs` (default 0,
range 0–10000) define the workload. Report them with every result; a paced short-reply
run does not establish that uninterrupted 64-token generation is thermally sustainable.

## Deferred device expansion

Local inference compatibility and physical acceptance on 4 GB phones are future
expansion goals, triggered by actual user demand. They are not current P1 requirements
or blockers, and remain **unverified**. When activated, repeat load, generation,
resource admission, and recovery cases before expanding the supported-device scope.
Resource safety and cloud/privacy restrictions remain current requirements.

## Out of scope

- CI automation (needs a device farm).
- Cross-device performance matrices.
- GPU/NPU backends.
