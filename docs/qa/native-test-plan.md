# Native / Device Test Plan (MNN + JNI)

JVM unit tests cover the engine state machine through `NativeSessionFactory`. They do
not load the MNN shared library and cannot prove that real inference works.

Run this plan on an **arm64 physical Android device** with a valid model package.

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

10. **Low-memory device**
    - Repeat cold load and short generation on a 4 GB device.
    - Expect: admission warnings or a clean failure, not an ANR/crash.

## Acceptance

- No JNI crash, no `UnsatisfiedLinkError`, no deadlock.
- Cancel and release always leave the engine in a usable state.
- Deleting a model never runs while the native session is still active.
- Peak RSS and sustained tok/s are recorded for the same device/model/quantization.

## Out of scope

- CI automation (needs a device farm).
- Cross-device performance matrices.
- GPU/NPU backends.
