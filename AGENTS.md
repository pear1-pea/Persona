# AGENTS.md

Persona — an Android AI-native social app (Kotlin) with a Node/TypeScript backend.
This is the single entry point for coding agents (Claude Code / Codex / OpenCode /
Kilo Code). Keep this file short and put details in `docs/`; load them on demand.

`CLAUDE.md` imports this file (`@AGENTS.md`); keep the content here only.

## Project

- App: Android, Kotlin 2.0.21, AGP 8.13.2, compileSdk/targetSdk 36, minSdk 24
- Architecture: MVVM + repository layers, Hilt DI, Coroutines/Flow, ViewBinding
- Local AI: MNN-LLM (Qwen2.5) via JNI; Cloud AI: DeepSeek through the backend proxy
- Storage: Room + Paging 3
- Backend: Node >= 20 + TypeScript + PostgreSQL (`backend/`)

## Commands

```bash
# Android (macOS/Linux)
chmod +x gradlew                      # the file is committed as mode 100644
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest   # needs a device/emulator

# Backend
cd backend && npm ci && npm run dev
cd backend && npm run smoke           # needs PERSONA_BACKEND_BASE_URL
```

On Windows use `gradlew.bat <task>` and `copy .env.example .env` in `backend/`.

## Hard rules

- The DeepSeek API key never goes into the client; it lives only in backend env vars or
  a secret manager.
- Never log `Authorization`, full prompts, or full model responses.
- Clients must not send `creatorId`; the backend derives ownership from the session.
- Logged in: the backend is authoritative. Offline: Room is an independent source.
  Do not auto-merge the two by ID.
- Keep `local.properties`, `.env`, and tokens out of git (they are git-ignored).
- Add/update tests under `app/src/test/` (or `backend/`) with behavior changes.
- Keep `third_party/MNN` as a submodule; do not vendor MNN sources into the repo.

## Docs map — read only what you need

| Need | Read |
| --- | --- |
| Full documentation index | `docs/index.md` |
| Architecture and layers | `docs/agent/architecture.md` |
| Kotlin/Android conventions | `docs/agent/conventions.md` |
| Build, NDK, `local.properties` | `docs/agent/build.md` |
| Tests and smoke checks | `docs/agent/testing.md` |
| Product overview and setup | `README.md` |
| Backend API and deployment | `backend/README.md` |
