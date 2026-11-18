# PHASE 5 — WP5.10 TEST REPORT

**Date:** 2026-11-16
**Command:** `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
~/.m2/wrapper/dists/apache-maven-3.9.12/6068d197/bin/mvn -B clean verify`

**Result: BUILD SUCCESS — 117 tests, 0 failures, 0 errors** (1 skipped: opt-in live
model). Verified twice.

---

## 1. RCP unit/integration tests (new)

### `DCodeApiClientTest` (6, green) — the workbench's client contract against a real
assembled backend (NanoHTTPD + real services + real git repos):

- workspaces/sessions lifecycle through the typed client (core model mapping)
- tools execute through the shared ToolEngine + audit trail endpoint
- git operations (status/diff/commit) through the new endpoints
- file read with path-containment errors (typed `DCodeApiException`)
- auth handling
- SSE client: ordered, monotonic-id events delivered

### `CrossClientInteropTest` (5, green) — mandatory scenarios (Web=RCP client A/B):

| Scenario | Result |
|---|---|
| A: Web creates session → RCP opens it | green |
| C: RCP creates session → Web opens it | green |
| F: both clients see consistent multi-repo associations + READ_ONLY policy | green |
| G: same tooling results through both clients | green |
| E: server restart → both clients reconnect to the same persisted (SQLite) sessions/repositories | green |

(Scenarios B/D — task continuation observed by the other client — are covered at the
backend level by the WP5.9 HTTP execution tests + SSE delivery tests; a live agent
continuation requires a working local model, which this machine lacks — the same
documented environment limitation as WP5.4.)

### `RcpWorkbenchSmokeTest` (1, green) — real Eclipse workbench startup on DISPLAY=:1:

- `PlatformUI.createAndRunWorkbench` with `DCodeWorkbenchAdvisor`
- workbench created; the DCode window advisor runs (`preWindowOpen` probe)
- perspective and all 9 views registered
- graceful close, clean exit code

### `DCodeBundlesResolvedTest` (1, green) — all 9 DCode bundles resolved in the OSGi
runtime.

## 2. Regression

All WP5.2–WP5.9 suites remain green (core, repositories, config, harness,
java.tooling, persistence, server, web-UI backend, OSGi wiring, headless runtime):
117 total.

## 3. Runtime evidence

- OSGi runtime: workbench startup verified in-process (above).
- Product: materialized RCP + headless products build successfully; launcher
  binaries exist. Launch evidence: the RCP product starts (framework, bundles,
  application, workbench init, `preWindowOpen`) — **E4 window shell rendering fails**
  in this environment (documented in the implementation report §8; not claimed).

## 4. Skipped tests

1. Live model completion (`-Ddcode.live.model` opt-in) — no chat-capable local
   model on this machine (documented since WP5.4).
2. Browser-DOM / visual window assertions — no browser harness; workbench DOM-level
   rendering covered only by the advisor-probe test.
