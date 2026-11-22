# PHASE 5 — WP5.9.2 FRONTEND ARCHITECTURE

**Date:** 2026-11-16
**Scope:** DCode web interface — presentation layer only. The backend remains the sole
authority for sessions, tasks, execution, tools and persistence.

---

## 1. Principles

1. **Same-origin deployment.** The web UI is served by the DCode NanoHTTPD server from
   the server bundle's `web/` folder. The frontend derives its API base from
   `window.location` — no hard-coded host, port or environment URL, no CORS, and it
   works on any configured bind address.
2. **No frontend framework.** Plain ES5-style JavaScript with namespaced modules
   (`window.DCodeApi`, `window.DCodeSse`, …), plain CSS with an IDE-inspired theme.
   No React/Vue/Angular, no build pipeline, no Node tooling.
3. **Backend-authoritative state.** The UI keeps only presentation state (open tabs,
   expanded tree nodes, the in-memory auth token). Every data read goes through
   `/api/v1`; every mutation is a backend call. On reconnect/refresh the UI re-fetches
   server state.
4. **No duplicated endpoint definitions.** `js/api.js` is the single API client; all
   views go through it.

## 2. Static layout

```
plugins/eu.kalafatic.dcode.server/
└── web/
    ├── index.html          application shell (top bar, left nav, work area, right
    │                       context panel, bottom console/events/problems)
    ├── css/app.css         IDE theme (dark, dense, monospace-first)
    └── js/
        ├── api.js          API client (fetch, error envelope, bearer token in memory)
        ├── sse.js          SSE client (EventSource, backoff reconnect, id dedupe,
        │                   watchdog, status callbacks)
        ├── app.js          shell: navigation, tabs, modals, console, SSE dispatch
        ├── explorer.js     workspace/repository/file tree (lazy per directory)
        ├── editor.js       source viewer + editing via the backend write_file tool
        ├── sessions.js     session list/detail, tasks, messages, reviews, changes
        ├── tooling.js      JDT/PDE tool forms + generic tool dialog + rename confirm
        └── diff.js         unified diff renderer
```

## 3. Application shell

| Region | Content |
|---|---|
| Top bar | product identity, current workspace/session, live connection status, auth |
| Left nav | Workspace · Sessions · Java Tools · Activity |
| Work area | tab strip + active pane (files, session detail, tool results, changes, editor) |
| Right panel | contextual details (selection-dependent) |
| Bottom panel | Console · Events (SSE) · Problems |

Persistent layout preferences are limited to in-memory tab/view state; nothing
authoritative is stored in the browser.

## 4. Client modules and contracts

- **api.js** — `get/post/del(path, body)`, always JSON; throws typed errors
  `{code, status, message}` from the backend error envelope; `setToken()` keeps the
  bearer token in memory only (never localStorage).
- **sse.js** — `connect(sessionId, onEvent, onStatus)`: native `EventSource` with
  exponential-backoff reconnection, monotonic backend event-id dedupe, a 45 s
  watchdog that force-reopens silent streams, and explicit status transitions
  (connected / reconnecting / disconnected) surfaced in the top bar. The backend
  streams are live-only (no Last-Event-ID replay — audited in
  `PHASE-5-WP-5.9-API-SSE-AUDIT.md`); after any reconnect the session view re-fetches
  the authoritative session state.
- **app.js** — tab management, modal dialog factory (auth, new workspace/session,
  submit task, java tool forms, generic tool, rename confirmation), console/event
  sinks, SSE event → UI refresh routing (SESSION_STATE_CHANGED → refresh detail;
  MESSAGE_APPENDED → refresh conversation; TOOL_CALL_COMPLETED → activity).
- **explorer.js** — renders `GET /workspaces` and lazily expands directories via
  `GET /workspaces/{id}/tree`; file clicks open the viewer via
  `GET /workspaces/{id}/files`; context menus route Java-tooling actions per file.
- **editor.js** — line-numbered viewer; editing is a full-content save through the
  backend `write_file` tool (audited, path-contained, policy-enforced); saving
  requires an active session.
- **sessions.js** — session list/create, detail view (state badge from the real
  `SessionState` values), task submission (async `/start`), live conversation via SSE,
  reviews (approve/reject), and the live changes/diff view
  (`GET /sessions/{id}/changes`).
- **tooling.js** — dedicated forms for all 13 WP5.6 tools plus a generic tool dialog
  built from `GET /api/v1/tools`; `java_rename` requires an explicit confirmation
  dialog (target, new name, mutation warning). All invocations are
  `POST /api/v1/tools/{name}/execute` with the active `sessionId`.
- **diff.js** — lightweight unified-diff renderer (add/del/hunk/meta coloring).

## 5. State and recovery model

- SSE events are per-session, ordered, deduplicated by id.
- Reconnect: `EventSource` auto-retry + watchdog; on every reconnect the session
  detail re-fetches messages/state from the API (server is authoritative; no
  "running because the browser says so").
- Browser refresh: shell re-initializes, sessions are re-listed from the backend,
  the previously open session can be reopened by click; no task state lives in the
  browser.
- Multiple sessions/tasks: the session list and per-session streams are independent;
  the UI never assumes a single task.

## 6. Security

- Bearer token entered via the auth dialog, held in memory, sent on every request;
  never persisted (per WP5.9 constraints).
- File browsing is server-contained (`resolveContained`); the UI cannot reach outside
  the workspace, and READ_ONLY repository policy is enforced by the backend tool
  engine.
- Tool results render server-returned structured JSON; raw output remains available
  for debugging.

## 7. Addendum 2026-11-22 — Settings/Help unification and port migration

- **settings.html now shares the main SPA's assets**: it links `css/app.css` (UPM
  design tokens, dark default, `[data-theme="light"]`) plus the new
  `css/settings.css` (card/kv/badge/switch/table components); its former dark-only
  inline palette is removed. A `theme` button toggles the same `dcode.theme`
  localStorage key as the main page (immediate, persisted, no restart).
- **One API client, one token**: settings.html uses `js/api.js` (`DCodeApi`)
  instead of its own fetch helper; the bearer token is stored under
  `dcode.web.token` in localStorage (read at load, written by `setToken`) so a
  single sign-in covers both pages of the same browser — mirroring the DStudio web
  client. `window.prompt` is replaced by a WebKit-safe token modal.
- **help.html** is theme-aware (`data-theme="dark"` default, UPM tokens with light
  overrides) and gains a theme toggle.
- **Port migration**: DCode now owns the 58xxx family block (HTTP 58080,
  management 58380, aux 58680); DStudio owns 48xxx. The migration touches
  `DCodePorts`, `RuntimeScanner.DEFAULT_ENDPOINTS`, `RuntimeConnection`
  fallback, `ConnectionDialog` and `ScanRuntimesDialog`; docs updated
  (BUILD_AND_LAUNCH.md, TESTING_GUIDE, AGENTS.md, PHASE-5 baseline §4.5).
  Live-verified: DCode headless binds 58080 with `/api/v1/ports` reporting the
  new expected values.

### 7.1 Addendum 2026-11-22 (b) — port lifecycle and CLI port resolution

- `ServerActivator` persists the actually bound HTTP port in
  `dcode-config.json` (`CoreConfiguration.withHttpPort`) whenever it was
  allocated or taken by deterministic fallback, so the next start reuses it.
  A configured-but-occupied port falls back inside the 58xxx block (58080 +
  500) with a warning; nothing is ever silently rebound to an unrelated port.
- `HeadlessApplication` CLI commands resolve the target port through the
  authoritative chain: `dcode.server.port` (published by the server bundle) →
  `-Ddcode.port` override → persisted `httpPort` → `DCodePorts.HTTP`.
  `sessions`/`status`/`stop` in a second process now observe the real running
  server even after fallback allocation (previously they hardcoded 58080 and
  failed with "failed: null").

### 7.2 Addendum 2026-11-22 (c) — Frontend round and RCP runtime fixes

- New `web/js/markdown.js` (window.DCodeMarkdown): XSS-safe markdown renderer with a
  dependency-free syntax highlighter and copy controls. The **direct chat** now
  renders assistant answers as markdown with highlighted code blocks, per-message and
  per-code-block copy, streaming updates included.
- Sessions view gains a **search filter** (title/workspace/id); Settings gains an
  **Appearance** card (theme select, shared `dcode.theme`).
- **Persistence bundle slf4j fix**: the bundle embedded its own `slf4j-api-1.7.36`
  (legacy API → "StaticLoggerBinder" warning); it now imports `org.slf4j` from the
  shared slf4j.api bundle. The legacy warning is gone (0 SLF4J lines in headless
  runs).
- **RCP runtime fixes** (from the user's launch log): `org.eclipse.ui.themes` added
  to the product (resolves `Theme 'org.eclipse.e4.ui.css.theme.e4_default' not
  found`); the product requests the `e4_dark` CSS theme; `org.slf4j.nop` fragment
  provides the SLF4J NOP provider; the m2e circular-reference entries come from the
  development IDE (no m2e bundles in the product) and need no product change.

### 7.3 Addendum 2026-11-22 (d) — Unified frontend delivery architecture

- Root cause of "UI unchanged": PDE workspace launches resolve the frontend from
  the bundle output folder, which never received the `web/` resources (only Tycho
  packaged them into product jars). `web/` is now a build-input source folder
  (`source.web/ = web/` + `.classpath` entry) so the standard builder copies it to
  the runtime classpath in every mode; the embedded server keeps serving from the
  bundle classpath only — no mode-specific code paths.
- New `FrontendDeliveryTest` asserts bundle-vs-source equality (stale packaging
  fails the build) and the `web-20261122-r4` build constant consistency between
  `js/api.js` and `DCodeApi.FRONTEND_VERSION` (`/api/v1/health` → `frontendVersion`).
- The build badge in the topbar and the bottom status bar expose the revision at
  runtime in the UI itself.

### 7.4 Addendum 2026-11-22 (e) — Eclipse Run/Debug finalization

- DCode's serving already used `Bundle.getEntry` (`StaticContent.BundleEntry`), the
  single OSGi mechanism now shared with DStudio: packaged jars and PDE
  directory-installed workspace bundles expose the same `web/…` paths from the
  same authoritative source tree. The temporary `source.web/ = web/` entry was
  reverted (JDT flattening hazard).
- `/api/v1/health` reports `frontendVersion`, `resourceSource: "osgi-bundle"`,
  `executionMode: "pde"|"product"` (diagnostic only).
- Shared launch configuration committed at
  `features/eu.kalafatic.dcode.feature/DCode_RCP_Application.launch` (product
  `eu.kalafatic.dcode.ui.rcp`, workspace bundles selected, JavaSE-21) — identical
  for Run As and Debug As after a workspace refresh.
- `FrontendDeliveryTest` extended with the directory-bundle proof (PDE layout,
  same logical paths, unmodified content).

### 7.5 Addendum 2026-11-22 (f) — Local no-auth policy and navigation fix

- DCode already had the required behavior (`DCodeApi.isAuthorized` returns true
  when no server token is configured), so PDE Run/Debug and packaged RCP access
  the API without credentials. Verified live in the RCP product: server RUNNING,
  WebKit browser created, `/api/v1/health` 200 without a token.
- No authentication mechanism was added. DStudio aligned to the same local
  no-auth policy (loopback trusted; remote token gate unchanged).
- Grouped navigation now uses a 2-column grid so the group labels no longer clip
  (`css/app.css`); verified in the served stylesheet of the running RCP.
- Screenshot: `/tmp/opencode/ui/dcode-noauth.png`.
