# Build

## Toolchain

| Item | Value |
| --- | --- |
| Android Gradle Plugin | 8.13.2 |
| Kotlin | 2.0.21 |
| compileSdk / targetSdk | 36 |
| minSdk | 24 |
| NDK | `27.0.11718014` |
| CMake (native build) | 3.22.1 |
| JVM target | 11 |
| ABI | `arm64-v8a` |
| Node (backend) | >= 20 |

Versions for AndroidX libraries are declared in `gradle/libs.versions.toml`.

## Local configuration

Create a git-ignored `local.properties` at the repository root:

```properties
sdk.dir=/path/to/Android/sdk
PERSONA_BACKEND_BASE_URL=https://api.example.cn/
DEEPSEEK_MODEL_ID=deepseek-v4-flash
```

- `PERSONA_BACKEND_BASE_URL` is injected as `BuildConfig.PERSONA_BACKEND_BASE_URL`.
- `DEEPSEEK_MODEL_ID` has a default; the client never holds a DeepSeek API key.
- Values can also come from Gradle properties or environment variables of the same name.

## Android commands

macOS / Linux:

```bash
chmod +x gradlew          # committed as 100644, so it may not be executable
./gradlew assembleDebug
./gradlew installDebug
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
```

Windows (PowerShell or `cmd`):

```bat
gradlew.bat assembleDebug
gradlew.bat installDebug
gradlew.bat testDebugUnitTest
gradlew.bat connectedDebugAndroidTest
```

On Windows, `sdk.dir` in `local.properties` may use either escaped backslashes
(`C\:\\Users\\you\\AppData\\Local\\Android\\Sdk`) or forward slashes
(`C:/Users/you/AppData/Local/Android/Sdk`).

## MNN native library

- `third_party/MNN` is a git submodule and is **not initialized by default**.
- If `third_party/MNN` is missing, CMake downloads the official 3.6.1 source archive.
- The fallback uses GitHub's codeload endpoint and verifies the pinned SHA-256.
- To use local sources: `git submodule update --init --recursive`.
- CPU is the acceptance baseline; OpenCL, Vulkan and optional KleidiAI are off.
  `PERSONA_ENABLE_KLEIDIAI=ON` is an explicit CMake experiment, not a supported-device
  promise. Record the effective CMake cache when comparing performance.

On restricted networks, initialize the pinned submodule before building instead of
relying on CMake's downloader. A failed or partial download is not an accepted source.
For source archives transferred from macOS, disable AppleDouble/xattr packaging
(`COPYFILE_DISABLE=1`, `tar --no-xattrs --no-acls`) so `._*` files do not become Android
resources on Windows.

## Model packages

Model files are side-loaded, not built into the APK. MNN packages do **not** include
the `manifest.json` + `READY` files that the app validates. Generate them first:

```bash
python3 tools/model-packager/generate_manifest.py \
  /path/to/Qwen2.5-0.5B-Instruct-MNN \
  --id qwen2.5-0.5b-instruct-mnn \
  --name "Qwen2.5 0.5B Instruct" \
  --context-window 32768 \
  --min-ram-gb 4
```

Then either use **Profile > Settings > 本地模型管理 > 安装模型** (recommended), or copy
the whole directory (including `manifest.json` and `READY`) to:

```text
/storage/emulated/0/Android/data/com.example.persona/files/models/<model-name>/
```

Validate without the app:

```bash
python3 tools/model-packager/validate_package.py \
  /path/to/Qwen2.5-0.5B-Instruct-MNN --require-ready
```

See [`tools/model-packager/README.md`](../../tools/model-packager/README.md) for the
manifest schema, tokenizer auto-detection, signature caveat and troubleshooting.

## Backend

```bash
cd backend
cp .env.example .env      # Windows: copy .env.example .env
psql "$DATABASE_URL" -f schema.sql
npm ci
npm run dev
```

See [backend/README.md](../../backend/README.md) for all environment variables and
deployment notes.
