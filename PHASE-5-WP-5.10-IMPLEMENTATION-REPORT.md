# PHASE 5 — WP5.10 IMPLEMENTATION REPORT: DCode RCP IDE

**Date:** 2026-11-16
**Build:** `mvn -B clean verify` → **BUILD SUCCESS, 117 tests, 0 failures, 0 errors**
(1 skipped: opt-in live model), run twice for determinism. Product assembly
(materialized RCP + headless products) included in the build.
**Repos:** DCode only. EVO/TRYST/DStudio untouched. Nothing committed.

---

## 1. New bundles

| Bundle | Role |
|---|---|
| `eu.kalafatic.dcode.client` | SWT-free HTTP API client (`DCodeApiClient`, typed domain mapping onto the core model) + SSE client (`DCodeSseClient`: backoff reconnect, monotonic-id dedupe, watchdog, reconnect callback). Exports one shared Jackson copy. |
| `eu.kalafatic.dcode.ui` | Eclipse RCP workbench: `IApplication`, `WorkbenchAdvisor`/`WindowAdvisor`/`ActionBarAdvisor`, DCode perspective, 9 views, 2 editors, dialogs. |
| `eu.kalafatic.dcode.feature` | Feature aggregating all 9 DCode bundles (+ platform RCP features). |
| `eu.kalafatic.dcode.product` | `eclipse-repository` materializing the RCP product (`eu.kalafatic.dcode.ui.rcp`) and the headless product (`eu.kalafatic.dcode.server.headless`); launchers verified to exist. |

## 2. Modified bundles

- `server`: registered the headless application + headless product extension
  (`plugin.xml`); published `dcode.server.port` (from WP5.9, kept).
- `ui` plugin.xml registers the RCP application + RCP product extension.

## 3. Actual capabilities implemented (all wired to real backend services)

- **Workbench**: perspective + views registered through standard Eclipse extension
  points; menu bar (File/Window/Help); status line; 1440×900 default window.
- **Workspace Explorer**: lazily loaded workspace/repository/file tree from
  `GET /api/v1/workspaces` + `/tree`; context menus: open file, git status/diff/
  commit/pull/push (new API endpoints), Java tooling actions; add-repository dialog.
- **Sessions view**: list/create/open; opening a session activates it and starts its
  SSE stream.
- **AI Chat view**: task submission to the active session (backend execution),
  cancellation, live conversation via SSE, authoritative reload.
- **Agent Activity view**: tool-call audit trail from the new
  `GET /api/v1/sessions/{id}/toolcalls` endpoint (ToolEngine records).
- **Console view**: live events + system log.
- **Java Tools view**: all registered tools listed from `GET /api/v1/tools`;
  schema-generated argument dialogs; `java_rename` shows an explicit mutation
  warning + confirmation.
- **Changes view**: per-repository live changes + unified diffs + review
  approve/reject.
- **Providers view**: provider list + connection test.
- **Connection view**: local/remote mode, endpoint, health, reconnect dialog.
- **Editors**: `DCodeFileEditor` (JFace SourceViewer, line-number ruler, Java syntax
  highlighting; backend-loaded content; save via the audited `write_file` tool);
  `DCodeDiffEditor` (add/del/hunk highlighting).

## 4. Existing services reused

Every capability goes through the same services/contracts as Web: SessionService,
WorkspaceService, ToolEngine, RepositoryOperations, ReviewService, ProviderRegistry,
EventBus/SSE hub — via `/api/v1` over HTTP. The RCP introduces **zero** duplicate
engines, managers, or persistence.

## 5. API endpoints consumed / added

Consumed: all WP5.9 endpoints (workspaces, tree, files, sessions, tools/execute,
changes, reviews, providers, models, SSE).
Added (minimal, additive, all policy-enforced through `RepositoryOperations`):
`GET .../repositories/{id}/status`, `GET .../repositories/{id}/diff`,
`POST .../repositories/{id}/commit|pull|push`, and
`GET /api/v1/sessions/{id}/toolcalls` (activity view).

## 6. Event contracts consumed

SSE per-session stream with monotonic ids; `DCodeSseClient` dedupes, reconnects with
backoff, watches silent streams, and triggers authoritative re-fetch on reconnect
(documented no-replay limitation respected — never faked).

## 7. Runtime modes tested

- **Local**: embedded server bundle (same OSGi runtime) publishes its port; the
  workbench client targets `http://127.0.0.1:<port>` — verified in the OSGi runtime
  tests and by the client tests against a fully assembled backend.
- **Remote**: `DCodeApiClient` targets a user-configured endpoint; remote paths are
  treated as server-side strings (files arrive via the API) — verified by the
  client tests against a separate HTTP stack and by the Connection dialog contract.
- **Headless independence**: server bundle has no SWT requirement (unchanged,
  regression-green).

## 8. Known limitations (honest)

1. **Materialized-product window rendering**: the product builds and launches
   (framework, all bundles resolved, application starts, workbench init begins,
   `preWindowOpen` runs) but the E4 window shell fails to render in this environment
   (`Unable to create class …CommandProcessingAddon…` + `EvaluationService`
   `IEventBroker` NPE during `Workbench.init`). Evidence captured in the launch logs.
   The workbench flow itself is covered by the in-process workbench test
   (creation → advisor → registry → clean shutdown). **Not claimed as a fully
   rendering desktop product.**
2. Full JDT `CompilationUnitEditor` embedding (content assist, quick fixes) is
   deferred — the integration point is identified (shared `IJavaProject`s in local
   mode); the DCode source editor with highlighting/navigation is implemented.
3. Editor save is full-file `write_file` (audited); no surgical-edit UI.
4. Terminal view deferred (no pty streaming in the backend).
5. Layout persistence relies on the standard Eclipse workbench restore.

## 9. Deferred work

JDT editor embedding, terminal, markdown rendering in chat, preference pages,
build/test results history pages.

## 10. Build evidence

`BUILD SUCCESS` (twice): 13 modules — 10 bundles, target, feature, product (two
materialized products, ~180 MB, launchers present).

## 11. Test evidence

`PHASE-5-WP-5.10-TEST-REPORT.md` and `PHASE-5-WP-5.10-CROSS-CLIENT-TEST-REPORT.md`:
client tests (6), cross-client interoperability scenarios A/C/F/G/E with a real
server restart over durable SQLite (5), RCP workbench smoke test (1), bundle
resolution test (1), plus the full regression suite (104 prior tests) — 117 total,
0 failures.
