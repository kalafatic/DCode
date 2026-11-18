# PHASE 5 — WP5.9 TEST REPORT

**Date:** 2026-11-16
**Command:** `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
~/.m2/wrapper/dists/apache-maven-3.9.12/6068d197/bin/mvn -B clean verify`

**Result: BUILD SUCCESS — 104 tests, 0 failures, 0 errors** (1 skipped: opt-in live
model completion). Verified twice for determinism.

---

## 1. Test inventory relevant to WP5.9

### 1.1 Backend-surface integration (new: `WebUiIntegrationTest`, 8 tests, all green)

Real HTTP against a fully wired in-process stack (NanoHTTPD + real services + real
JDT tooling + real git repositories).

| Test | Verifies |
|---|---|
| `servesStaticAssets` | index/css served with correct content types; SPA fallback for unknown paths; traversal attempts neutralized |
| `fileTreeAndReadWithContainment` | tree listing (dirs/files), bounded file read; `..`/absolute path rejection (400 VALIDATION_FAILED); missing file → 404 |
| `javaToolingOverHttp` | `java_project_structure` (3 modules found), `java_get_diagnostics` (0 errors on healthy module), `pde_inspect_bundle` (symbolicName) — structured unwrapped results |
| `renameOverHttpRequiresRunningSessionAndPausesAfter` | `java_rename` over HTTP: change performed, references updated, session CREATED→RUNNING→SUSPENDED (documented pause), live `changes` endpoint reports the renamed file |
| `mutatingToolOnTerminalSessionIsRejected` | terminal session + mutating tool → 400 with diagnostic |
| `sseStreamDeliversTypedEventsWithMonotonicIds` | real SSE frames: `event:`/`data:` lines delivered, strictly increasing `id:` |
| `authEnforcedWhenTokenConfigured` | 401 AUTHENTICATION_FAILED without token; 200 with token; dev mode open when unconfigured |
| `toolEndpointRequiresSessionAndKnownTool` | missing sessionId → 400; unknown tool → 404 NOT_FOUND |

### 1.2 Real runtime evidence (new: `HeadlessRuntimeWebTest`, 2 tests, all green)

The assembled product — all DCode bundles started in the Equinox surefire runtime:

- `webAssetsArePackagedInTheServerBundle` — all 10 web assets present in the server bundle.
- `headlessRuntimeServesHealthAndWebUi` — the OSGi-run NanoHTTPD server (allocated
  port published via `dcode.server.port`) answers `GET /api/v1/health`
  (`ready:true`), serves the packaged `index.html` and `js/api.js`. Runtime log
  evidence: `DCode HTTP API listening on 127.0.0.1:48082 (allocated)`.

### 1.3 Pre-existing suites (regression)

| Suite | Result |
|---|---|
| `OsgiWiringTest` (all services, durable impls preferred, lifecycle through SERVER) | green |
| `HeadlessApiEndToEndTest` (WP5.8 full session over HTTP) | green |
| All WP5.6 JDT/PDE suites (discovery, diagnostics, AST, hierarchy, search, PDE, rename, READ_ONLY, workspace reuse) | green |
| Core / repositories / config / harness / persistence suites (WP5.2–5.7) | green |

## 2. Coverage against WP5.9 test requirements

| Requirement | Status |
|---|---|
| API client behavior, parsing, errors | green (typed envelope errors asserted) |
| Session + task operations over HTTP | green (create/list/start/cancel/messages/reviews) |
| Tool invocation incl. Java tooling over HTTP | green (4 tools exercised incl. mutating rename) |
| SSE connection/parsing/correlation/ids | green |
| Reconnection / duplicate handling / browser refresh / server restart | **implemented, not browser-tested** — reconnection logic lives in `js/sse.js` (backoff + dedupe + watchdog) and state reconciliation re-fetches the API; verified at the protocol level (ids monotonic, streams deliver) but not in a real browser harness. Documented limitation, not claimed as browser-verified. |
| UI workflow tests (open → session → task → monitor → inspect → reopen) | backend-level flow covered end-to-end (`HeadlessApiEndToEndTest` + `WebUiIntegrationTest`); the visual layer itself is exercised only by the served-assets + manual smoke. |
| `java_rename` explicit confirmation | implemented in `js/tooling.js` (confirmation modal) + backend gate verified |
| Multiple sessions/browsers | backend supports N sessions; UI is stateless per session stream |
| No leaked listeners / unbounded memory | activity + event panes capped at 500 entries; SSE queue bounded (256) |

## 3. Known limitations

1. Browser-level verification (real DOM workflows, multi-tab) is not automated — no
   browser test harness exists in the stack; protocol-level behavior is covered.
2. SSE has no Last-Event-ID replay (audited); recovery relies on API state
   re-fetch + id dedupe.
3. Auth is a single server token (no user accounts in WP5.9).
