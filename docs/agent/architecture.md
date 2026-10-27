# Architecture

Persona is a single-module Android app (`:app`) plus a Node/TypeScript backend
(`backend/`).

## App layers

| Layer | Package | Responsibility |
| --- | --- | --- |
| UI / features | `features/` | Activities, Fragments, Adapters, ViewModels per screen |
| Domain | `domain/` | Models (`Persona`, `Message`) and repository interfaces |
| Data | `data/` | Room (`local/`), Retrofit (`remote/`), repository implementations |
| Core | `core/` | AI engine, auth, base ViewModel, utilities |
| DI | `di/` | Hilt modules (`DatabaseModule`, `NetworkModule`, `RepositoryModule`) |
| Entry | `MainActivity.kt`, `PersonaApp.kt` | Navigation host and `@HiltAndroidApp` |

Feature packages: `auth/`, `chat/`, `creation/`, `discovery/`, `feed/`, `model/`,
`profile/`.

## AI pipeline

`features/chat/ChatViewModel` → `HybridAiRepository` → `HybridRouter` chooses:

- **Local**: `LocalAiEngine` → `MnnLocalAiEngine` → `NativeMnnSession` → JNI
  (`app/src/main/cpp/mnn_jni.cpp`) → MNN
- **Cloud**: `CloudChatRepository` → backend `POST /api/chat/completions` → DeepSeek

### Model package lifecycle

Deleting a model is a runtime operation, not just a file operation:

1. `HybridAiRepository.prepareModelForDeletion()` stops generation and releases the
   native session.
2. While deactivation is in progress, `ensureLocalReady()` refuses to reacquire the
   model.
3. `LocalModelManager.deleteModel()` refuses to delete a model that is still current.
4. The coordinator removes the directory, then calls `finishModelDeletion()`.

Load failures are classified (`CORRUPT`, `INCOMPATIBLE`, `OUT_OF_MEMORY`, `THERMAL`,
`USER_CANCELLED`, `TRANSIENT`, `UNKNOWN`). Permanent failures require reinstall or an
explicit user retry; transient failures use bounded exponential backoff instead of a
24-hour lock. A successful load + smoke test or a completed generation clears the
failure record.

Supporting pieces in `core/ai/`:

- `LocalModelManager`, `ModelPackageInstaller`, `LocalModelValidator` — side-loaded model
  packages under the app files directory (not bundled in the APK)
- `prompt/` — `PromptAdapterRegistry` (e.g. Qwen ChatML), `ContextPlanner`,
  `PromptHistoryTrimmer`
- `RoutingContextProvider`, `DeviceCapability` — inputs for local/cloud routing

## Data sources

- Logged in: the backend is authoritative (`RemotePersonaRepository`).
- Offline: Room is an independent local source (`RoomPersonaRepository`).
- `SwitchingPersonaRepository` selects between them; offline-created personas are not
  auto-uploaded, and the two sources are not merged by ID.
- The backend derives `creatorId` from the session; clients cannot set ownership.

## Backend (`backend/`)

Node >= 20, TypeScript (ESM), PostgreSQL. All routes live in `src/server.ts`:

- Email/password auth with Argon2id; only a hash of the session token is stored
- Persona CRUD with server-side ownership checks
- Rate-limited, allowlisted DeepSeek streaming proxy
- `schema.sql` for the database; `scripts/` for admin, import, and smoke checks

See [backend/README.md](../../backend/README.md) for endpoints, limits, and deployment.
