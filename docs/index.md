# Persona Documentation

Index of all project documentation. The agent entry point is
[AGENTS.md](../AGENTS.md).

## Overview

- [README](../README.md) — product overview, features, and quick start
- [Backend README](../backend/README.md) — API, database, limits, deployment
- [Long-term optimization plan](long-term-optimization-plan.md) — phased reliability, Persona, memory, and evaluation roadmap

## Development (agent-focused)

- [Architecture](agent/architecture.md) — app layers, AI pipeline, data sources, backend
- [Conventions](agent/conventions.md) — Kotlin/Android style, backend rules, security
- [Build](agent/build.md) — toolchain, `local.properties`, MNN, model packages
- [Testing](agent/testing.md) — unit/instrumented tests and backend smoke

## Quick reference

- Android app: `app/`
- Backend service: `backend/`
- Native bridge: `app/src/main/cpp/`
- Gradle version catalog: `gradle/libs.versions.toml`
- Verification baseline: [qa/baseline.md](qa/baseline.md)
- Latest P0 verification: [qa/p0-status-2026-10-27.md](qa/p0-status-2026-10-27.md)
- M1 runtime acceptance: [qa/m1-status-2026-10-28.md](qa/m1-status-2026-10-28.md)
- P1 reliability acceptance: [qa/p1-status-2026-10-28.md](qa/p1-status-2026-10-28.md)
- P2 first product-flow acceptance: [qa/p2-product-flow-status-2026-10-29.md](qa/p2-product-flow-status-2026-10-29.md)
- P2 explicit-memory acceptance candidate (Windows/device checks; quality pending): [qa/p2-memory-status-2026-10-29.md](qa/p2-memory-status-2026-10-29.md)
- P2 component adapter Mac preparation record: [qa/p2-adapter-status-2026-10-30.md](qa/p2-adapter-status-2026-10-30.md)
- P2 component adapter Windows/device acceptance (14 pass; 12 unsupported): [qa/p2-adapter-windows-status-2026-10-30.md](qa/p2-adapter-windows-status-2026-10-30.md)
- P2 request lifecycle acceptance (20 pass; 6 unsupported; controlled transports): [qa/p2-lifecycle-status-2026-10-30.md](qa/p2-lifecycle-status-2026-10-30.md)
- P2 real-model/UI/backend device evidence from 2026-10-30: [qa/p2-closure-status-2026-10-30.md](qa/p2-closure-status-2026-10-30.md)
- P2 human results and Windows/Mac integration record: [qa/p2-review-status-2026-11-04.md](qa/p2-review-status-2026-11-04.md)
- Chinese personality/memory evaluation: [../tools/persona-eval/README.md](../tools/persona-eval/README.md)
- Native/device plan: [qa/native-test-plan.md](qa/native-test-plan.md)
- Model package acceptance: [qa/model-package-acceptance.md](qa/model-package-acceptance.md)
- Model packager: [../tools/model-packager/README.md](../tools/model-packager/README.md)
