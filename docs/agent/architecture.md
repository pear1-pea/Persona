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

1. `HybridAiRepository` serializes selection, deactivation, model-list publication,
   runtime initialization, generation registration, and the complete deletion operation.
2. `deleteLocalModel()` stops the owning request, releases its native session, clears
   the selection if necessary, and deletes files while holding that operation lock.
3. `LocalModelManager.deleteModel()` still refuses to delete a current model directly.
4. A failed native release retains the loaded directory identity; a later deletion
   must retry release before removing those files.

Each `GenerationSession` carries its first requested stop reason. The repository and
MNN engine both consult it, including before native generation starts. A generation
keeps its model snapshot so late results cannot update another model's failure history.

Load failures are classified (`CORRUPT`, `INCOMPATIBLE`, `OUT_OF_MEMORY`, `THERMAL`,
`USER_CANCELLED`, `TRANSIENT`, `UNKNOWN`). Permanent failures require reinstall or an
explicit user retry; transient failures use bounded exponential backoff instead of a
24-hour lock. A completed generation or an explicit model selection clears the failure
record; a short smoke test alone does not. Permanent records survive the statistics
window, while transient records expire. After transient backoff, routing permits a
new local probe even when recent failure statistics remain nonzero.

Supporting pieces in `core/ai/`:

- `LocalModelManager`, `ModelPackageInstaller`, `LocalModelValidator` — side-loaded model
  packages under the app files directory (not bundled in the APK)
- `prompt/` — `PromptAdapterRegistry` (e.g. Qwen ChatML), `ContextPlanner`,
  `PromptHistoryTrimmer`
- `RoutingContextProvider`, `DeviceCapability` — inputs for local/cloud routing

Routing first computes permitted and available executors. A session binds that
decision once; execution and fallback recheck identity, network, resource admission
and stop state. `forceCloud` cannot override a local-only/privacy restriction.
An unavailable permitted executor produces an explicit non-executable decision.

Both executors receive validated `GenerationParams`. MNN applies its sampler config
before load and reloads on sampler changes; output-token limits do not force a reload.
Each native request resets KV state and receives its complete authorized context,
preventing reuse across accounts. Persistent prefix caching is not enabled.
Native token counts and phase timings are optional metric fields; the existing
generation rate includes prefill and must not be labeled pure decode throughput.

Context planning retains the complete system/current input or rejects the request;
history is removed in complete turns. Cloud planning additionally enforces per-message,
aggregate-character and message-count budgets. SSE parsing bounds frames and tracks
effective output progress. Terminal events use suspending delivery so backpressure
cannot silently convert a truncated stream into success.

Generated Markdown uses no automatic image loading or clickable link spans. Streaming
messages display plain text; completed messages receive Markdown rendering.

## Data sources

- Logged in: the backend is authoritative (`RemotePersonaRepository`).
- Offline: Room is an independent local source (`RoomPersonaRepository`).
- `SwitchingPersonaRepository` selects between them; offline-created personas are not
  auto-uploaded, and the two sources are not merged by ID.
- The backend derives `creatorId` from the session; clients cannot set ownership.

`AuthIdentity` fixes account/session ownership for each request. Identity changes
cancel old generation; late 401s invalidate only their original token. Room deletes
only the requested owner's messages and retains shared Persona parents while other
owners still reference them. Message insertion rejects conflicting primary keys.
Logout retains history; account deletion records durable pending local cleanup and
retries failures. `OFFLINE_USER` remains independent. The P2 memory candidate uses
schema v8, with an explicit 7-to-8 migration. Windows compilation and device migration,
DAO, UI and ownership checks passed; model recall quality remains a separate gate.

### Explicit memory (P2 candidate)

`RoomMemoryRepository` stores user-confirmed notes on this device, isolated by owner
and Persona. `personaId=null` requires an explicit account-wide share; cloud use is
a separate opt-in. Writes check source ownership and normal user-message status,
use revision CAS, and clear deleted content while retaining a tombstone. Source
message deletion invalidates linked notes. Account cleanup deletes that owner's notes.

Each backend prepares a fresh authorized context through `GenerationSession`;
fallback never reuses a local memory prompt or partial reply. The current selector uses query-dependent keyword ranking, with at most four
records and a 1200-character UTF-16 content budget. Blank queries preserve recent-record
selection. No embedding retrieval is active. Existing token/character planners still enforce executor budgets.

`MemoryPrompt` (`quoted-user-facts-v4`) adds only permitted content as quoted JSON
facts to the current user turn. Trusted system rules stay separate; provenance stays
in the application, and the augmented reference body is not saved as a user message.
`GenerationContext.userMessage` is rebuilt for each executor, including fallback.
This input path does not guarantee factual recall: the 0.5B device repeat failed.
See the P2 memory QA report before changing model or sampling defaults.

Memory changes invalidate affected history for prompt replay while preserving its
display. Memory-bearing turns are not automatically replayed; local-only history is
excluded from cloud context. Legacy v7 messages have no recorded replay consent and
are retained for display but excluded from new prompts after migration. Editing or
deleting notes cancels stale generation; database observations also catch source deletion.

## Backend (`backend/`)

Node 24 (root `.nvmrc`), TypeScript (ESM), PostgreSQL. All routes live in `src/server.ts`:

- Email/password auth with Argon2id; only a hash of the session token is stored
- Persona CRUD with server-side ownership checks
- Rate-limited, allowlisted DeepSeek streaming proxy
- `schema.sql` for the database; `scripts/` for admin, import, and smoke checks

`chat-stream.ts` owns bounded SSE relay and cancellable drain waits;
`chat-budget.ts` reserves/settles both usage buckets transactionally. Concurrency
admission remains per-process, so deployments require one backend instance until
shared coordination exists. Reservations are conservative on uncertain upstream cost;
there is no durable per-request billing recovery ledger.

See [backend/README.md](../../backend/README.md) for endpoints, limits, and deployment.


## Controlled curation and diagnostics (P3/P4)

Room v9 stores pending candidates, owner preferences and summary provenance.
`RoomMemoryCurationRepository` revalidates source, identity, consent revision and task
state before generation and persistence. Model output only creates pending candidates.
`MemoryExtractionRunner` permits one contract-failure cloud attempt after local output
fails strict parsing, with separate per-request opt-in and existing cloud consent.
A local-only source, stopped request or non-unmetered network prevents escalation.
Authorization and network are checked again before dispatch. Both attempts share a
60-second budget, and runtime errors or cancellation never trigger contract escalation.

`InferenceAttemptTracker` hashes request/model identifiers and records actual executor
attempts. `BufferedInferenceTelemetry` keeps disk IO out of generation/cancellation.
The bounded `FileInferenceJournal` is device-level and best effort, has no chat or
account fields, and supports explicit export/clear in Settings. Structural acceptance
is distinct from execution completion and does not establish semantic quality.
See the dated P4 Mac QA record for Android/device work still pending.
