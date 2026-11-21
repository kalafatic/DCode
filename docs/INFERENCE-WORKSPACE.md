# DCode Inference Workspace (OpenCode-like AI coding chat)

## Purpose

A complete, interactive AI coding chat in the RCP IDE: conversation with streaming
output, markdown/code rendering, prompt composer, configuration toolbar, session
navigation, workspace search and execution status — no separate dialogs required.

## Architecture

New presentation bundle **`eu.kalafatic.dcode.ui.inference`** (backend-agnostic, SWT-only):

- `ChatWorkspaceComposite` — assembles the workspace: toolbar + conversation + search
  row + composer + session navigator (sash layout).
- `ChatWorkspaceBackend` / `ChatWorkspaceUi` — SPI between presentation and product.
- `MarkdownRenderer` + `CodeHighlighter` — self-contained markdown rendering with
  fenced-code syntax highlighting (no external markdown dependency).
- `ConversationPanel` — user/assistant/tool/execution/system/error entries with
  per-entry Copy and Copy-code actions; incremental streaming entries.
- `InputComposer` — multiline input, Enter=send / Shift+Enter=newline, Attach /
  Clear / Send / Stop / Run task.
- `ToolbarBuilder`, `SessionListPanel`, `SearchPanel`.

Product side: `eu.kalafatic.dcode.ui.views.InferenceWorkspaceView` (view id
`eu.kalafatic.dcode.ui.views.inference`) implements the backend over the DCode HTTP
API. It is the default right-side view of the DCode perspective.

## Execution flow

1. Selectors: Project (workspaces), Provider (runtimes), Model (runtime models),
   Agent (agent registry), Profile (conversation profile registry), Search
   (Disabled / Workspace `search_code` tool). All selectors use real backend
   registries; changing provider reloads models; agent/profile are bound to the
   execution on submission.
2. Send → creates a session when needed → `POST /api/v1/sessions/{id}/start` with
   provider/model/agent/profile → the native agent loop runs; assistant output
   streams token-by-token through new **`MESSAGE_PARTIAL`** SSE events (batched
   ≥256 chars / ≥100 ms), finalized by `MESSAGE_APPENDED`.
3. Stop → `POST /api/v1/sessions/{id}/cancel` (cooperative cancel, partial output
   retained).
4. Sessions are multi-turn and fully persisted (SQLite); navigation supports
   New / Open / Rename / Delete / search filter. Continue = another submission to
   the same session.

## Backend additions

- `SessionService.renameSession/deleteSession` (+ SQLite/in-memory impls,
  `POST /sessions/{id}/rename`, `DELETE /sessions/{id}`).
- `DCodeEventType.MESSAGE_PARTIAL`; `ToolCallingAgentLoop` streams completions
  through `RuntimeRegistry.stream` when available (fallback: blocking complete).
  `EventLog` excludes MESSAGE_PARTIAL from durable storage.
- Conversation profile registry: `config.profile.*` (`<dataDir>/profiles.json`,
  built-in Default/Concise/Explaining), bound as
  `{"source":"DCODE_CONVERSATION_PROFILE"}` SYSTEM sections rendered into the
  agent system prompt; endpoints under `/api/v1/profiles`.
- `POST /sessions/{id}/start` accepts `agentId`/`profileId` and re-binds them
  (only when changed) so toolbar selections affect every submission.

## Constraints

- DCode UI knows nothing about Git; change tracking stays filesystem/tool-based.
- No new dependencies; markdown rendering is SWT-native.
