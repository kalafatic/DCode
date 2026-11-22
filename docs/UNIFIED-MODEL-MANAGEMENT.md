# DCode — Unified Model Management Integration (shared ENR infrastructure)

Date: 2026-11-22 (updated after the initial functional-testing iteration)
Status: Implemented, tested and live-verified (DCode 2.6.11).

## 1. Objective

DCode consumes the shared family model management service owned by ENR —
it holds **no** model management state, no download jobs and no provider
registry of its own. Pulls, progress, cancellation and removal all execute
in ENR; DCode surfaces them through its existing server API and both UIs.

## 2. Components

| Component | Location | Role |
|---|---|---|
| `ModelManagementClient` | `eu.kalafatic.dcode.config.provider` | Wire client to ENR `/api/v1/models/*`; raw JSON strings (no Jackson types cross the OSGi bundle boundary). Endpoint resolution: `dcode.enr.endpoint` system property, then the registered ENR runtime endpoint (kept in sync with live detection by `RuntimeManager`), then a live probe of the well-known family endpoints (standalone 8111, EVO-embedded 28780), then the default `http://127.0.0.1:28780` |
| `DCodeApi.handleModels` | `eu.kalafatic.dcode.server` | Passthrough surface `/api/v1/models/*` (providers, installed, pull, jobs list/status/cancel, remove). Resolves the client endpoint from the ENR runtime registration. Upstream failures → `UPSTREAM_UNAVAILABLE` (503) |
| Web Model Manager | `server/web/js/models.js` + nav view `modelmanager` | Provider select, model input, Pull, installed list, live downloads with progress bars (1 s polling), Cancel/Remove |
| RCP `ModelManagerView` | `eu.kalafatic.dcode.ui.views` (id `eu.kalafatic.dcode.ui.views.modelmanager`) | Same operations; `Display.timerExec` polling, disposed cleanly |
| `DCodeApiClient` model methods | `eu.kalafatic.dcode.client` | `modelProviders/modelInstalled/modelPull/modelJobStatus/modelJobCancel/modelRemove` for JVM clients |

## 3. Compatibility note

The legacy runtime-adapter pull (`RuntimeAdapter.pullModel` /
`POST /api/v1/runtimes/{id}/models/{modelId}/pull`) remains available but no
longer implements a private Ollama pull: `OllamaProvider.pullModel` now
executes through the shared ENR service via `ModelManagementClient
.pullAndWait` (blocking adapter contract). DCode must be able to reach the
family ENR endpoint for pulls; endpoint resolution above covers standalone
and EVO-embedded ENR deployments without manual repair.

## 4. Verification

- `eu.kalafatic.dcode.tests.server.ModelManagementApiTest` — 9 hermetic cases
  against a mock ENR (providers, pull, jobs, progress, cancel, remove,
  validation, upstream error envelope). Full suite: 208 tests, 0 failures.
- Live (this host, DCode headless 58080 → ENR 8111 → real Ollama 11434):
  `/api/v1/models/providers` returns the reachable Ollama provider,
  `/api/v1/models/ollama/installed` lists the real installed models,
  `/api/v1/models/jobs` reflects the shared ENR job list. Previously the
  passthrough resolved to the unreachable historical default (28780) even
  when the ENR runtime registration had been migrated to the live endpoint
  (regression fixed in this iteration).
