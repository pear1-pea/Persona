# Model package acceptance (Qwen2.5-0.5B)

This is the end-to-end acceptance for the packager + Android installer + MNN runtime.
Run it on Windows with an arm64 device.

## 1. Generate

```powershell
python tools/model-packager/generate_manifest.py `
  C:\models\Qwen2.5-0.5B-Instruct-MNN `
  --id qwen2.5-0.5b-instruct-mnn `
  --name "Qwen2.5 0.5B Instruct" `
  --context-window 32768 `
  --min-ram-gb 4
```

Expected: `manifest.json` + `READY` are created; the manifest lists every regular file
with size and SHA-256.

## 2. Validate

```powershell
python tools/model-packager/validate_package.py `
  C:\models\Qwen2.5-0.5B-Instruct-MNN --require-ready
```

Expected: `OK`; no `ERROR`. A `signature is empty` warning is expected until package
signing is implemented.

## 3. Import

Copy the directory to the device, then **Profile → Settings → 本地模型管理 → 安装模型**
and pick it. Alternatively copy the entire directory (including `manifest.json` and
`READY`) to:

```text
/storage/emulated/0/Android/data/com.example.persona/files/models/qwen2.5-0.5b-instruct-mnn/
```

Expected: the model appears as `Ready` / `当前模型`, not `NotInstalled` or `Corrupted`.

## 4. Initialize + smoke

Select the model and open a chat. The engine performs `load` + a one-token smoke test.

Expected logs:

```text
MNN model loaded: id=qwen2.5-0.5b-instruct-mnn ...
```

Expected UI: `LOCAL READY`; a short message streams and completes.

## 5. Failure / recovery spot checks

- Rename one `.mnn` file and refresh: expect `Corrupted` (hash/size).
- Delete `manifest.json`: expect `NotInstalled`.
- Delete `READY`: expect `NotInstalled` until the SAF installer rewrites it.
- Simulate a temporary failure (kill the process during load): after restart, the
  model should back off briefly and then be retryable, not locked for 24 hours.
- Select the model again: this clears the failure history and retries immediately.

## 6. Delete lifecycle

- Start a long generation, then delete the current model.
- Expected: generation stops, engine is released, directory is removed, no crash.
- Delete a non-current model: the active model must keep working.

Record results in [`baseline.md`](baseline.md).
