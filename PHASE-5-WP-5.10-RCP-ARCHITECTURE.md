# PHASE 5 — WP5.10.1 RCP ARCHITECTURE

**Date:** 2026-11-16
**Rule:** ONE PLATFORM — THREE ACCESS MODES. The RCP IDE is a presentation layer over
the existing DCode services. No second session manager, agent engine, tool engine, git
implementation, persistence layer, execution lifecycle or Java tooling.

---

## 1. Existing architecture (verified)

8 OSGi bundles, Tycho build, Eclipse 2024-03 target, 104 tests green at the end of
WP5.9: `core` (domain/events/lifecycle/ports), `config` (providers/models/credentials),
`repositories` (JGit engine, policy-enforced operations), `harness` (native agent loop,
ToolEngine, SessionExecutor), `java.tooling` (headless Eclipse workspace + 13 JDT/PDE
tools), `persistence` (SQLite, durable services), `server` (NanoHTTPD, `/api/v1`, SSE,
packaged web UI). Service contracts: `SessionService`, `WorkspaceService`,
`ReviewService`, `ProviderRegistry`, `ToolRegistry`, `ToolEngine`, `RepositoryOperations`,
`ConfigurationService`, `EventBus`, `DCodeLifecycle`.

## 2. Existing RCP implementation

None. `HeadlessApplication` (server bundle) exists; there is no product, feature,
workbench or UI bundle. Eclipse JDT/PDE bundles are already in the target platform
(2024-03: jdt.core, jdt.core.manipulation, jdt.launching, ltk.core.refactoring,
core.resources, core.filebuffers, jface.text, org.eclipse.ui via the RCP feature).

## 3. Required RCP bundles

| Bundle | Responsibility | SWT |
|---|---|---|
| `eu.kalafatic.dcode.client` (new) | HTTP API client (`DCodeApiClient`) + SSE client (`DCodeSseClient`) with typed domain mapping onto the core model; JDK HttpClient + Jackson. Shared by any JVM client. | no |
| `eu.kalafatic.dcode.ui` (new) | Eclipse RCP workbench: `IApplication`, `WorkbenchAdvisor`, perspective, views (workspace/sessions/chat/activity/console/tools/changes/providers/connection), source editor (JFace text infrastructure + Java syntax highlighting), diff editor, commands, menus, status line. | yes |
| `eu.kalafatic.dcode.feature` (new) | Feature aggregating all DCode bundles (+ client, ui) | — |
| `eu.kalafatic.dcode.product` (new) | `eclipse-repository` with the RCP product (and a headless product) | — |

## 4. Bundle dependency graph

```
ui ──► client ──► core
 │        │
 │        └──(HTTP)──► server bundle APIs (local loopback or remote host)
 └──► jface/text, workbench, commands, swt (target platform)
```

`ui` never imports `harness`, `java.tooling`, `persistence` or `repositories` classes —
it consumes the platform exclusively through `client` (HTTP/SSE), exactly like the web
UI consumes `js/api.js`.

## 5. Presentation and service boundaries

- All state is backend-authoritative: `ui` holds only presentation state (selection,
  expanded nodes, active editor).
- All mutations are API calls: task start/cancel, tool execution (incl. all 13 JDT/PDE
  tools and `java_rename` with confirmation), reviews, git operations (new API
  endpoints, see §12), file save via `write_file`.
- Long-running work never runs on the SWT UI thread: HTTP calls are async
  (`CompletableFuture` + `Display.asyncExec`); SSE events arrive on a background thread
  and are dispatched to the display.

## 6. Local runtime connection model

The RCP product includes the full DCode bundle set (including `server`). At startup the
bundle activators initialize the platform exactly as the headless runtime does;
`ServerActivator` binds the HTTP server on the configured address (default
127.0.0.1, port configured or allocated) and publishes `dcode.server.port`. The UI
reads that system property and points its `DCodeApiClient` at
`http://127.0.0.1:<port>`. No second initialization path, no duplicated services —
the workbench and the HTTP server share one OSGi runtime.

## 7. Remote runtime connection model

The same `DCodeApiClient` targets a user-configured base URL (Connection dialog /
preference). Remote repository paths are server-side strings — the UI treats them as
opaque and never assumes local filesystem access; file contents arrive via the files
endpoint. Status bar shows the runtime mode and health.

## 8. Session interoperability

Sessions live in the backend persistence. RCP lists/creates/opens the same sessions as
Web (same endpoints); conversation history, repository associations, reviews and
changes are read from the API; continuation (task submission) is the same `/start`
endpoint. RCP↔Web cross-client scenarios are covered by dedicated tests (WP5.10.10).

## 9. Event integration

`DCodeSseClient` implements the documented SSE contract (monotonic ids, per-session
filtering, keep-alive). Reconnection: exponential backoff, id dedupe, then
authoritative re-fetch (`GET /sessions/{id}`, messages, changes). Events are processed
off the UI thread; coalesced UI updates avoid refresh storms.

## 10. Editor integration

- **DCode source editor** (both modes): JFace `SourceViewer` + `LineNumberRulerColumn`
  + a `RuleBasedScanner` Java highlighter; content from the files endpoint; saving via
  the audited `write_file` tool; navigation to file:line from diagnostics/references.
- **Diff editor**: read-only diff presentation for the changes endpoint data.
- Full JDT `CompilationUnitEditor` embedding (content assist, quick fixes) is a
  documented deferred enhancement: in local mode the shared `JavaWorkspaceManager`
  already provides `IJavaProject`s in the same runtime, so the integration point is
  defined (`IJavaProject` access via an OSGi service); it is not implemented in
  WP5.10 and is not claimed.

## 11. Java tooling integration

All 13 WP5.6 tools are invoked through `POST /api/v1/tools/{name}/execute` with the
active session id — the same ToolEngine as the web UI. The Java Tools view builds
forms from `GET /api/v1/tools` schemas; `java_rename` shows an explicit confirmation
dialog. No UI-specific tool implementations.

## 12. Minimal additive API endpoints (gaps identified in WP5.9 audit + RCP needs)

Git operations for the workspace views (reuse `RepositoryOperations`, policy-enforced,
user-initiated):

- `GET /api/v1/workspaces/{id}/repositories/{repoId}/status`
- `GET /api/v1/workspaces/{id}/repositories/{repoId}/diff`
- `POST /api/v1/workspaces/{id}/repositories/{repoId}/commit` `{message, accountId?}`
- `POST /api/v1/workspaces/{id}/repositories/{repoId}/pull`
- `POST /api/v1/workspaces/{id}/repositories/{repoId}/push`

## 13. Testing strategy

1. **Client unit tests** (`eu.kalafatic.dcode.tests`): `DCodeApiClient` against a real
   embedded DCode HTTP stack (same pattern as WP5.9 `WebUiIntegrationTest`): sessions,
   repositories, tools, files, changes, git endpoints, SSE frames, auth.
2. **Cross-client tests**: one headless backend + two independent API clients
   (client A = "Web", client B = "RCP client") executing the mandatory scenarios
   (create/continue/observe/restart/multi-repo/tooling).
3. **RCP runtime test**: Tycho surefire UI execution (`useUIHarness=true`, real X
   display) that creates the workbench with `DCodeWorkbenchAdvisor`, asserts the
   perspective and views exist, and performs a full local-mode workflow
   (connect → workspace → session → task monitor via client) — proving the RCP
   product actually starts.
4. **Build verification**: full `mvn -B clean verify` including the product assembly
   (p2 director) run twice.

## 14. Identified implementation risks

1. **UI test harness stability** — mitigated by DISPLAY=:1 available on the build
   machine; if the harness proves unusable, evidence degrades to bundle-level tests
   + documented manual launch (not claimed as automated).
2. **Product assembly time/network** — p2 director downloads the RCP feature set
   (cached by Tycho); mitigated by reusing the verified target platform.
3. **SSE reconnect semantics in SWT** — background-thread lifecycle; bounded retries;
   never blocks UI.
4. **Scope of full JDT editor** — explicitly deferred (documented, not claimed).
