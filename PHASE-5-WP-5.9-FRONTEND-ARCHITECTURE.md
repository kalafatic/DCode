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
