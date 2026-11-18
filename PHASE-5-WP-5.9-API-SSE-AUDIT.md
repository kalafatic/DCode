# PHASE 5 — WP5.9.1 API AND SSE AUDIT

**Date:** 2026-11-16
**Method:** Read-only inspection of `eu.kalafatic.dcode.server` sources
(`DCodeHttpServer.java`, `DCodeApi.java`, `SseHub.java`) and the service contracts they
consume. No code was modified during this audit.

---

## 1. API endpoint inventory (`/api/v1`, NanoHTTPD)

All handlers delegate to application services; business logic lives in the bundles, not
in handlers. Error envelope: top-level `{code, message, retryable, timestamp}`
(family TEIP error shape).

| # | Method | Path | Request | Response | Auth | Persistence | Sync/Async |
|---|---|---|---|---|---|---|---|
| 1 | GET | `/health` (alias of 2) | — | same as 2 | **none** (outside `/api/v1` prefix) | — | sync |
| 2 | GET | `/api/v1/health` | — | `{application, version, ready, status, timestamp, stages{...}}` | bearer if configured | — | sync |
| 3 | GET | `/api/v1/status` | — | `{ready, stages{...}}` | bearer if configured | — | sync |
| 4 | GET | `/api/v1/workspaces` | — | array of `{id,name,mode,rootPath,createdAt,repositories[]}` | bearer | SQLite (durable impl) | sync |
| 5 | POST | `/api/v1/workspaces` | `{name, rootPath}` | workspace object | bearer | SQLite | sync |
| 6 | GET | `/api/v1/workspaces/{id}` | — | workspace object | bearer | SQLite | sync |
| 7 | POST | `/api/v1/workspaces/{id}/repositories` | `{name, localPath, remoteUrl?, branch?, accessPolicy?}` | updated workspace | bearer | SQLite | sync |
| 8 | GET | `/api/v1/workspaces/{id}/repositories` | — | updated workspace | bearer | SQLite | sync |
| 9 | DELETE | `/api/v1/workspaces/{id}/repositories/{repoId}` | — | updated workspace | bearer | SQLite | sync |
| 10 | GET | `/api/v1/sessions` (`?workspaceId=`) | — | array of `{id,title,workspaceId,state,createdAt,updatedAt,providerId,modelId,agentRole}` | bearer | SQLite | sync |
| 11 | POST | `/api/v1/sessions` | `{title?, workspaceId, providerId?, modelId?, agentRole?}` | session object | bearer | SQLite | sync |
| 12 | GET | `/api/v1/sessions/{id}` | — | session object | bearer | SQLite | sync |
| 13 | POST | `/api/v1/sessions/{id}/start` | `{task}` | `{status:"accepted",message}` | bearer | SQLite (state RUNNING) | **async** (executor thread) |
| 14 | POST | `/api/v1/sessions/{id}/cancel` | — | `{status:"accepted",message}` | bearer | SQLite (state CANCELLED on stop) | async-ish (flag) |
| 15 | GET | `/api/v1/sessions/{id}/messages` | — | array of `{id,role,content,sequence,timestamp}` | bearer | SQLite | sync |
| 16 | GET | `/api/v1/sessions/{id}/reviews` | — | array of `{id,sessionId,changeSetId,status,approverId,comment,createdAt,decidedAt?}` | bearer | SQLite (reviews) | sync |
| 17 | GET | `/api/v1/reviews/{id}` | — | review object | bearer | SQLite | sync |
| 18 | POST | `/api/v1/reviews/{id}/approve` | `{approverId?, comment?}` | review object (APPROVED) | bearer | SQLite | sync |
| 19 | POST | `/api/v1/reviews/{id}/reject` | `{approverId?, comment?}` | review object (REJECTED) | bearer | SQLite | sync |
| 20 | GET | `/api/v1/providers` | — | array of `{id,name,baseUrl,local}` | bearer | registry (in-memory) | sync |
| 21 | POST | `/api/v1/providers/{id}/test` | — | `{providerId,success,message}` | bearer | — | sync (network) |
| 22 | GET | `/api/v1/models` | — | array of `{id,name,providerId,contextWindowTokens,available}` | bearer | — | sync (network) |
| 23 | GET | `/api/v1/tools` | — | array of `{name,description,readOnly,scope,parameters}` | bearer | registry (in-memory) | sync |

**Authentication:** `Authorization: Bearer <token>` required on every `/api/v1` path
**only when** the secret `server.authToken` is configured; otherwise the server is in
documented development mode (loopback). No user accounts/RBAC in the backend yet — auth
is a single server token.

**Session identifiers:** sessions are the central entities (`session.id`); there is no
separate task entity — a "task" is one execution of a session (`POST .../start`).

## 2. SSE audit (`GET /api/v1/events/stream?sessionId=<id>`)

Implementation: `SseHub` (server bundle), fed by the core `EventBus`.

| Aspect | Actual behavior |
|---|---|
| Endpoint | `/api/v1/events/stream`, required query param `sessionId` (must be non-empty; a missing parameter → 400) |
| Format | `id: <System.nanoTime()>\nevent: <DCodeEventType name>\ndata: <event.dataJson()>\n\n` |
| Event types | `DCodeEventType`: SESSION_CREATED, SESSION_STATE_CHANGED, MESSAGE_APPENDED, AGENT_RUN_STARTED/COMPLETED, TOOL_CALL_STARTED/COMPLETED, EXECUTION_PROGRESS, WORKSPACE_CREATED, REPOSITORY_ADDED/REMOVED/CHANGED, BUILD_COMPLETED, SYSTEM_INFO, SYSTEM_ERROR |
| Session correlation | stream filters by `event.sessionId == sessionId`; workspace/system events (null session) are NOT delivered on session streams |
| Ordering | FIFO per connection (single dispatching thread → per-connection queue) |
| Reconnection / Last-Event-ID | **NOT supported** — streams are live-only; `id` is opaque (nanotime), no replay endpoint is consumed by the hub |
| Heartbeat | keep-alive comment frame (`: keep-alive`) after 15 s of silence (per connection) |
| Backpressure | per-connection queue, capacity 256; slow clients drop the OLDEST frame |
| Connection cap | max 64 concurrent streams; beyond that `open()` returns null → 400 |
| Errors/disconnect | stream ends when the client disconnects; server shutdown sends a `shutdown` event frame |

**Gaps found (see §3):** no monotonic event ids, no Last-Event-ID replay, no
`retry:` hint, no end-of-stream marker distinguishing server shutdown.

## 3. Gaps relative to the WP5.9 UI requirements

| # | Needed by UI | Backend status | Resolution (minimal, additive) |
|---|---|---|---|
| G1 | File/directory browsing | **Missing** | Add `GET /api/v1/workspaces/{id}/tree?path=&depth=` (bounded directory listing) and `GET /api/v1/workspaces/{id}/files?path=` (bounded file read). Reuses the existing `ToolEngine.resolveContained` path-containment logic; read-only, no new write capability. |
| G2 | Invoking the 13 WP5.6 Java tools (and other tools) from the UI | **Missing** (ToolEngine is in-process only) | Add `POST /api/v1/tools/{name}/execute` — executes the registered tool synchronously through the existing `ToolEngine` (audit + events preserved). Mutating tools require a RUNNING session; the endpoint transitions CREATED→RUNNING before and RUNNING→SUSPENDED after a mutating call. No second execution engine. |
| G3 | Changed files + diffs after execution | Partial (reviews exist; ChangeSet in-memory only, no diff endpoint) | Add `GET /api/v1/sessions/{id}/changes` — live per-repository `changedFiles` + unified diff via the existing `RepositoryOperations` (read-only, honest live git state). Reviews keep their approve/reject flow. |
| G4 | Serving the web UI itself | Missing (no static assets) | Serve static assets from the server bundle (`web/`), same origin as the API — no port coupling, no CORS needed for same-origin UI. |
| G5 | SSE reconnection robustness | Live-only, opaque ids | Improve `id` to a monotonic sequence (dedupe-friendly); document that replay is not supported — the client MUST reconcile by re-fetching authoritative state after reconnect (no faked replay). |
| G6 | Source editing | No direct write endpoint | Editing goes through the existing tools endpoint (`write_file`/`edit_file` via G2) — audited, path-contained; no new filesystem write endpoints. |

Anything not listed above is intentionally outside WP5.9 (user accounts, artifact
downloads, build-result browsing beyond what exists).
