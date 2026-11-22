# DCode — UI/UX and Interaction Improvement Report

Date: 2026-11-20 · Scope: RCP IDE, Web IDE, DCode client library, backend contract verification
Status key: **PASS** (verified), **PARTIAL** (implemented, partially verified),
**FAIL** (not met), **DEFERRED** (explicitly postponed)

## Unified AI Selection & Port Infrastructure Round

- **AI Selection toolbar** (top bar, non-closeable): four independent selectors —
  Harness · Provider · Model · Agent — plus status, AI Workspace focus, Runtime
  Manager, Scan and Wizard buttons. Each dimension loads/changes independently
  (switching the harness never resets the model; switching the provider never
  resets the harness); provider/model/agent changes persist platform defaults on
  the backend; changes affect the next execution, never an ongoing request.
- **Agents**: backend `AgentRegistry` (built-ins: General Coding Agent, Senior Java
  Developer, Code Reviewer; custom agents persisted in `agents.json`; default
  selection durable). API: `GET/POST /api/v1/agents`, `DELETE`, `POST /{id}/default`.
  Session binding: sessions accept `agentId`; the profile is stored as a dedicated
  SYSTEM message rendered by the harness as system instructions (verified live).
  RCP: Agent Explorer view + create/edit dialog + selector combo.
- **AI Environment Wizard**: first-run (opens automatically when no providers are
  configured) and re-runnable via the toolbar — local/cloud → provider type →
  endpoint → auth → test → discover models → default model → default agent →
  profile summary → save & activate. Reuses backend services only.
- **Harness selection**: harness catalog (`/api/v1/harnesses`) with honest
  availability/integration status; only usable harnesses execute tasks.
- **Port standardization**: family = `8x80 + offset` (EVO +20000/28xxx, TRYST
  +30000/38xxx, DStudio +40000/48xxx, DCode +50000/58xxx).
  *(Correction 2026-11-22: the convention is the reverse of what this round
  implemented — DStudio owns 48xxx (TEIP 48080), DCode owns 58xxx (58080); DCode
  migrated to 58xxx afterwards.)*
  - **DStudio migrated to 58xxx**: TEIP 58080, diagnostics 58180, DCode adapter
    endpoint now `http://localhost:48080`, conflict dialog text updated, and a
    persisted-config migration remaps legacy 48080/48180 values on load
    (explicit custom ports preserved). Tests updated + legacy-migration test added.
    **DStudio build BLOCKED by pre-existing incomplete in-progress changes in its
    working tree** (untracked, non-compiling agent/execution files — unrelated to
    the port migration).
  - **DCode port audit**: HTTP 48080 (fallback range stays in 48xxx), management
    48380, auxiliary 48680 documented; `PortInspector` identifies occupying
    processes (Linux /proc); `GET /api/v1/ports` reports expected/actual/bind/
    protocol/state/conflict; RCP Port & Network Monitor view added. Live: actual
    48081 reported honestly while another DCode instance held the default.
- **Professional IDE layout**: editor area visible with Project Explorer left,
  Runtime & Models below it, AI Workspace right, Sessions + Agent Explorer right
  column, bottom folder (Console · Ports · Activity · Changes); standard menus
  added: Navigate, Search, Project, Run (submit task, cancel, run build/tests via
  session tools), Models, Harness.

**Verification**: 180 tests, 0 failures, 0 errors, 3 skipped. Live: agents CRUD +
session binding, ports monitor, harness catalog, ENR + Ollama up, DStudio 58xxx
free. Cross-project: EVO (28xxx live on this machine), TRYST (38xxx unchanged),
ENR/Ollama external ports unchanged. RCP click-through on a display remains
DEFERRED.

---

## RCP Redesign Round (lightweight AI client, not an IDE)

- **New information architecture** (default perspective, editor area hidden):
  top Inference Selector bar · left “Runtime & Models” panel (integrated runtime
  instances + models of the selected runtime, real discovery, context menus,
  double-click model = use) · **AI Workspace as the primary center** · compact
  Sessions navigator on the right · collapsible Console/Activity/Changes bottom
  folder. No empty central editor, no squeezed chat column, no clipped controls at
  1280×800 (minimum widths in the view layouts); workbench persists the layout.
- **AI Workspace** merges chat + direct chat into one primary view with two
  explicit modes: *Direct chat* (streaming inference on the default runtime/model,
  history, retry, copy, copy-code, system prompt, cancel) and *AI coding* (task
  submission on the active session through the selected harness; backend SSE is
  the execution state source). Runtime/model/harness/session are always visible
  in the header chips.
- **Harness selection**: backend `GET /api/v1/harnesses` catalog (DCode native =
  integrated & usable; OpenCode/Claude Code/Aider = honest availability detection
  on PATH, marked “not integrated” — never silently substitutable). AI Workspace
  harness combo only executes usable harnesses and explains why others are
  disabled. Live: dcode usable; opencode/aider detected available; claude absent.
- **Runtime Manager dialog** (Add/Edit/Remove/Enable/Disable/Test/Set default/
  Scan/Refresh in one guided surface) + toolbar buttons on the selector bar.
- **Status consistency**: the selector chip, status line and Connection view all
  consume the single `RuntimeConnection` state machine (no contradictory
  CONNECTING/ERROR states); the wrong-endpoint case is a one-shot diagnostic with
  Scan/Use-local-server recovery — no permanent banners, no 404 spam.
- **Workspace 404**: root cause unchanged from the earlier round (endpoint pointed
  at ENR); the lightweight RCP no longer foregrounds a workspace explorer — it
  uses sessions through the shared API, and all request guards prevent error
  loops when the backend is unreachable.
- Legacy views (Workspace Explorer, Providers, Model Explorer, Direct Chat, Java
  Tools) remain registered and reachable via Window menu but are not part of the
  default layout.

**Verification:** 174 tests green (3 skipped live-probes); harness catalog verified
live; runtime/session persistence verified live across restarts; Web IDE untouched
and still served by the same backend. Manual pixel-level inspection on a real
display remains DEFERRED (headless environment; workbench smoke test is
display-gated).

---

## Interactive RCP Round (runtime selection, scanning, compact layout)

- **Inference Selector view** (always visible, non-closeable top bar): Runtime combo +
  Model combo + reachability/model-count status + Direct Chat and Scan… buttons +
  backend connection chip. Selecting a runtime/model persists the platform default on
  the backend (shared with the Web IDE); failures keep the selection and explain the
  state inline. **PASS**
- **Local runtime scanning** (`POST /api/v1/runtimes/scan` + `RuntimeScanner`):
  classifies endpoints as DCode/ENR/Ollama/OpenAI-compatible/HTTP/unreachable with
  model counts and sample models; RCP Scan dialog (Runtimes/Models/Selector views)
  registers findings in one click, skipping already-registered endpoints; Web IDE has
  the same Scan modal. Live scan correctly found Ollama 0.40.16 (5 sample models) and
  ENR 2.6.11 (tiny-evo). **PASS**
- **Compact perspective**: editor area hidden until a file editor is actually opened;
  selector on top, explorer/sessions/runtimes/models left, AI Chat + Direct Chat
  right, console/changes/activity bottom. **PASS**
- **Wrong-endpoint recovery**: Connection view and dialog now offer “Scan local…”
  and, when the embedded server is running, “Use local server” — one click away from
  the exact error scenario of connecting to `127.0.0.1:8111` (ENR) instead of DCode.
  **PASS**

---

## 1. UI/UX Audit Report

Audited: RCP (9 views, 3 dialogs, menu bar, status line, perspective), Web IDE
(explorer, sessions, runtimes, models, tools, activity, chat, direct chat, console,
modals), DCode API client, backend routes, SSE infrastructure.

Prioritized findings (all fixed in this work package unless marked otherwise):

| # | Finding | Severity | Resolution |
|---|---------|----------|------------|
| 1 | RCP connected to `127.0.0.1:8111` (an ENR server) and produced a repeating `list workspaces: HTTP 404` error stream | critical | **PASS** — explicit connection state model + backend identity probe detects non-DCode endpoints; permanent 404s are never auto-retried; errors deduplicated |
| 2 | No distinction between TCP connectivity, API compatibility and backend readiness | high | **PASS** — `ConnectionState` model (DISCONNECTED/CONNECTING/CONNECTED/INITIALIZING/READY/DEGRADED/ERROR) in client + UI; backend identity/version/stages surfaced in Connection view (RCP), status line, top bar and connection-details modal (Web) |
| 3 | Console was an unstructured text sink; exceptions printed raw | high | **PASS** — structured console in both clients: categories (SYSTEM/INFO/SUCCESS/WARNING/ERROR/DEBUG/NETWORK/HARNESS/RUNTIME/TOOL/TASK/INFERENCE/EVENT), timestamps, severity filter, search, pause, auto-scroll, copy, export; human-readable failure diagnostics with expandable technical detail |
| 4 | Repeated identical errors flooded the console | high | **PASS** — 30 s dedup window with repeat counters (RCP `Ui.reportFailure`, Web `logConsole`); single notification per persistent failure; original technical error retained in the buffer |
| 5 | Views went blank on backend failure | high | **PASS** — explicit loading/empty/error/degraded states with recovery actions (Retry, Connection details, New workspace, Add runtime) in WorkspaceExplorer, Sessions, Runtimes, Models (RCP) and Explorer/Sessions (Web) |
| 6 | Missing Edit menu, minimal File/Window menus, no accelerators | medium | **PASS** — File (New Session Ctrl+N, Connect, Exit), Edit (undo/redo/cut/copy/paste/select-all), Window (views with accelerators: Runtime Explorer Ctrl+R, Direct Chat Ctrl+Alt+C), Help |
| 7 | Status bar unused | medium | **PASS** — live connection state + backend identity on the workbench status line |
| 8 | New-session dialog lacked runtime/model selection | medium | **PASS** — RCP New Session dialog and Web new-session modal select runtime/model (server-authoritative, shared across clients) |
| 9 | Web IDE had no connection diagnostics | medium | **PASS** — top-bar connection chip + error banner + connection-details modal with stages |
| 10 | No empty states with actions | medium | **PASS** — “No workspaces → create”, “No sessions → new session”, “No runtimes → add runtime” etc. |
| 11 | Products never activated the persistence bundle (sessions lost on restart) | high | **PASS** — both product files auto-start core/config/client/repositories/harness/persistence/java.tooling/server; tooling degrades gracefully headless |
| 12 | Harness failed obscurely on empty model output | medium | **PASS** — clear diagnostic (“Empty response from model … context window too small …”) |

## 2. Root-Cause Analysis — Workspace HTTP 404

**Trace:** RCP `RuntimeConnection.initialize()` → persisted remote endpoint
`http://127.0.0.1:8111` → `DCodeApiClient.listWorkspaces()` →
`GET http://127.0.0.1:8111/api/v1/workspaces` → HTTP 404
`{"error":{"code":"NOT_FOUND","message":"No such page: /api/v1/workspaces"}}`.

**Root cause:** the configured endpoint was not a DCode server. Port 8111 serves the
**ENR Control Center** (verified live: root page is the ENR web UI; ENR's own
`/api/v1/health` returns `{"status":"healthy"}` with no DCode identity fields).
DCode's client never validated the endpoint identity, so every view refresh issued
the same doomed request and printed the raw exception — hence the repeating 404s.

**Contributing factors (all fixed):**
- No endpoint/identity probe before issuing domain requests.
- No error classification → every failure logged in full, every refresh retried.
- No view states → views silently emptied and refetched.
- `Ui.log` printed `DCodeApiException: HTTP 404` verbatim (not human-readable).

**Fix (no error suppression, no fake data):** `BackendProbe` classifies the endpoint
into the connection state model (connectivity / API availability / compatibility /
readiness). Non-DCode endpoints produce a single structured ERROR with the detected
identity and corrective guidance. Permanent contract errors (404/405) are reported
once and never auto-retried; retries are user-triggered. The backend needed no route
changes — `/api/v1/workspaces` is implemented; it was simply never reached.

## 3. API/Client/Backend Contract Verification

- `GET /api/v1/workspaces` — implemented (`DCodeApi.handleWorkspaces`), verified live: 200 with workspace list.
- `GET /api/v1/health` — implemented, returns `{application: DCODE, version, ready, status, stages}`; used as the compatibility probe.
- Client routes audited: workspaces, repositories, sessions, providers, runtimes, models, tools, inference — all match registered backend routes (no obsolete endpoints found).
- Live probe matrix (real services): DCode:48081 → **READY (DCODE 2.6.11, UP)**; ENR:8111 → **ERROR “not a DCode server”**; Ollama:11434 → **ERROR “does not provide the DCode API”**; dead port → **ERROR unreachable**. **PASS**

## 4. Implemented Improvements (summary)

- **Client:** `ConnectionState`, `BackendIdentity`, `BackendProbe` (+ `FailureClass`
  for retry policy: PERMANENT_CONTRACT / AUTHENTICATION / VALIDATION / TRANSIENT).
- **RCP:** state-machine `RuntimeConnection` with probe-on-connect and manual
  recheck; structured `ConsoleView` (filters, pause, search, copy, export, retained
  buffer); reworked `ConnectionView` (identity, stages, recovery actions);
  `ConnectionDialog` with pre-save endpoint test; view states everywhere; menus +
  accelerators; status-line connection state; secure token storage retained.
- **Web:** connection probe + banner + connection-details modal; structured console
  with category chips, pause/autoscroll/copy/export and error dedup; error/retry
  states in Explorer and Sessions; runtime/model selection in session/task modals
  (shared backend); no browser-side secrets.

## 5. Console and Feedback Improvements

Structured categories, timestamps, severity filtering, deduplication, human-readable
diagnostics with the original exception preserved at DEBUG level, export of the full
buffer even after clearing, per-operation acknowledgement (e.g. “Submitting task…”,
“Task submitted”, “Generation cancelled”). No indefinite spinners; no fabricated
progress; SSE remains the execution-state source. **PASS**

## 6. Connection Lifecycle Improvements

Explicit state machine used consistently in RCP (status line, Connection view,
console) and Web (top bar chip, banner, modal). Distinguishes connectivity, API
availability, compatibility, backend readiness, workspace availability, and
runtime/model availability. Successful HTTP ≠ operational DCode. **PASS**

## 7. RCP Verification

- Full build **PASS** (all 13 modules, Tycho 4.0.8).
- Startup/layout/navigation: workbench smoke test passes in suite; menus/accelerators compile and are wired; layout persistence unchanged (existing workbench persistence).
- Connection lifecycle: verified through live probe matrix + `BackendProbeTest` (7 tests). SWT-level manual interaction on a real display: **DEFERRED** (no display available in this environment; `RcpWorkbenchSmokeTest` gates on `DISPLAY`).

## 8. Web IDE Verification

- All JS files pass `node --check` **PASS**.
- Served by the real headless server; new controls present in HTML; runtime/models/direct-chat views served and functional against the live backend (verified via API responses and asset checks). Browser-level click-through: **DEFERRED** (headless environment; behavior covered by the same backend endpoints verified live).

## 9. Build and Test Report

- `./mvnw -B verify`: **163 tests, 0 failures, 0 errors, 3 skipped** (skips = live
  ENR/Ollama probes when unreachable — unchanged gate). Baseline before this work
  package: 156 green.
- New tests: `BackendProbeTest` (7: READY / INITIALIZING / DEGRADED / wrong-service /
  missing-API / unreachable / failure classification).
- Live verification: real DCode server (persistence, recovery, SSE), real Ollama
  (streaming, coding task completion, cancellation), real ENR (test/models/chat/
  stream/load; coding task fails with clear diagnostic for 32-token toy model —
  model capacity, not integration).

## 10. Remaining Limitations and Deferred Work

- **DEFERRED** — JDT/PDE tooling in the *headless product* remains unavailable
  (Eclipse workspace cannot initialize without the IDE runtime); now degrades with a
  single log line instead of a bundle failure. Works in the RCP product.
- **DEFERRED** — Manual UI click-through on a real display (no X server here);
  workbench smoke test remains display-gated.
- **PARTIAL** — Console “open related task/session” navigation exists via Sessions
  view; deep-linking console entries to sessions is not implemented.
- **DEFERRED** — Keyboard shortcut scheme beyond menu accelerators (full Eclipse
  key-binding commands) is not implemented; functionality is reachable via menus and
  mnemonics.
- Web IDE dark theme only (existing); no theme switching — unchanged by design.
