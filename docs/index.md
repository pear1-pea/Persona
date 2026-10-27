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
- Native/device plan: [qa/native-test-plan.md](qa/native-test-plan.md)
- Model package acceptance: [qa/model-package-acceptance.md](qa/model-package-acceptance.md)
- Model packager: [../tools/model-packager/README.md](../tools/model-packager/README.md)
