# FHC 1.0 — Family Harness Contract

**Status:** NORMATIVE (Phase 6)
**Spec caretaker:** DCode (`/home/petr/git/DCode-121126`)
**Related:** TEIP 1.1 (canonical copy in the EVO repository, `docs/protocol/TEIP-1.1.md`),
ENR 1.0 (EVO repository, `docs/protocol/ENR-1.0.md`)

## 1. Purpose

FHC (Family Harness Contract) is the family contract for delegating coding tasks to a
coding harness. It formalizes the de-facto contract established in Phase 3 as the
`CodingHarnessAdapter` SPI (A-14/A-15), implemented identically in EVO and TRYST, and its
DStudio counterpart (`HarnessAdapter` SPI).

FHC defines **harness delegation only** — agent execution, tool invocation, workspace
operations, permissions, containment, cancellation, timeouts, event streaming and result
collection. FHC says nothing about LLM inference: inference is governed by ENR 1.0 or any
alternative inference contract. A harness and an inference engine are distinct family
roles and must never be conflated.

FHC is transport-agnostic. The two conformant wire mappings are:

1. **TEIP 1.1** — family execution exchange (`/teip/v1/*`), used when a harness platform
   (DStudio) hosts the harness or when EVO/TRYST delegate across product boundaries.
2. **DCode REST surface** (`/api/v1/*`) — the native DCode harness API; consuming
   applications map it onto their product-local adapter SPI (see section 5).

## 2. Roles and ownership

| Role | Responsibility | Never |
|---|---|---|
| Calling application (EVO, TRYST, DStudio) | Task/execution lifecycle, target/workspace resolution, review, approval, commit | — |
| Harness (DCode, OpenCode, Aider, …) | Agent loop, tool selection/invocation, file operations, events | Commit/push in delegated mode |
| Inference engine (ENR, Ollama, remote) | Model discovery/lifecycle, generation, streaming | Session/task/repository ownership |

Every delegated operation must carry the originating application and session/task
reference so that execution metadata remains attributable across product boundaries.

## 3. Delegate semantics (normative)

A conformant harness delegation MUST satisfy:

1. **Submission** — the caller provides: a working directory (the containment target),
   instructions, an optional structured prompt, an optional model selector, a timeout and
   a cancellation supplier.
2. **Containment** — the harness MUST NOT write outside the resolved working directory.
   Implementations enforce containment server-side (path resolution, `..` rejection,
   symlink escape rejection).
3. **Events** — execution events (started, log, progress, tool invocation, completed,
   failed, cancelled) MUST be streamed or polled to the caller with monotonic ordering.
4. **Cancellation** — the caller's cancellation request MUST stop the harness execution
   and return a `cancelled` result; cancellation is idempotent.
5. **Timeout** — the caller's timeout MUST stop the harness execution and return a
   `timedOut` result.
6. **Result** — the terminal result carries: success/error/cancelled/timedOut, a summary,
   the list of modified files and (where available) the diff text.
7. **No commit** — in delegated mode the harness MUST NOT commit or push to the user's
   repository. Changes are collected and returned; approval and commit remain with the
   calling application. DCode sessions with changes terminate in `WAITING_FOR_APPROVAL`.
8. **Unavailability** — an unavailable harness MUST NOT fail the caller. Callers select
   another registered harness or fall back to their local capability according to
   configured policy (never silently, per configured policy).

## 4. Product-local adapter SPI (normative shape)

EVO and TRYST implement the identical SPI (`eu.kalafatic.evolution.controller.harness`
and `eu.kalafatic.tryst.domain.harness`):

```java
public interface CodingHarnessAdapter {
    record ConnectionTestResult(boolean success, String message) {}
    record HarnessExecutionResult(boolean handled, boolean success, String summary,
            String errorMessage, boolean timedOut, boolean cancelled,
            List<String> modifiedFiles) {}

    String getApplicationId();        // stable identity, e.g. "dcode"
    String getApplicationName();
    String getApplicationType();      // e.g. "DCODE", "OPENCODE", "DSTUDIO"
    List<String> getCapabilities();   // e.g. "CODE_IMPLEMENTATION"
    boolean isAvailable();
    ConnectionTestResult testConnection();
    default void updateConnectionConfiguration(Map<String,String> config) {}
    default Map<String,String> getConnectionConfiguration() { return Map.of(); }
    HarnessExecutionResult executeTask(/* task/workspace/target */ Object targetOrTask,
            /* workspace */ Object workspaceOrNull,
            String instructions, String prompt,
            Consumer<String> logConsumer,
            BooleanSupplier cancellationSupplier,
            Duration timeout);
}
```

DStudio implements the equivalent contract as `eu.kalafatic.dstudio.core.harness.HarnessAdapter`
(`execute(HarnessExecutionRequest, HarnessExecutionSink)`); the semantics of section 3
apply identically.

Adapters are registered in **product-local registries only** (EVO/TRYST
`ConnectedApplicationRegistry`, DStudio `HarnessRegistry`). There is no global family
registry and no shared harness runtime.

## 5. DCode REST mapping (conformant wire surface)

The native DCode harness exposes the following delegation flow under `/api/v1`
(default `http://localhost:58080`, loopback, bearer token optional in dev mode):

| Step | Call |
|---|---|
| Workspace | `POST /api/v1/workspaces` `{name, rootPath}` → workspace id |
| Repository | `POST /api/v1/workspaces/{id}/repositories` `{name, localPath, accessPolicy}` |
| Session | `POST /api/v1/sessions` `{title, workspaceId, providerId?, modelId?}` → session id |
| Execute | `POST /api/v1/sessions/{id}/start` `{task}` (asynchronous) |
| Observe | `GET /api/v1/sessions/{id}` (state), `GET .../messages`, `GET .../toolcalls` |
| Stream | `GET /api/v1/events/stream?sessionId=` (SSE) |
| Cancel | `POST /api/v1/sessions/{id}/cancel` |
| Result | `GET /api/v1/sessions/{id}/changes` → per-repository `changedFiles` + `diff` |
| Review | `GET /api/v1/sessions/{id}/reviews`, `POST /api/v1/reviews/{id}/approve|reject` |

Session states: `CREATED → RUNNING → COMPLETED | WAITING_FOR_APPROVAL | FAILED | CANCELLED`
(plus `SUSPENDED`, `ARCHIVED`). `WAITING_FOR_APPROVAL` means "completed with changes
pending review" — the calling application decides approval; DCode never commits in
delegated mode.

Reference consumers: EVO `DCodeCodingHarnessAdapter` (controller/harness), TRYST
`DCodeCodingHarnessAdapter` (infrastructure/dcode), DStudio `DCodeHarnessAdapter`
(harness/dcode).

## 6. Default family harness

DCode is the **default family harness** in EVO, TRYST and DStudio (registered at startup,
selected when no explicit selection exists). The default is configurable: users may
select OpenCode, DStudio TEIP delegation or future harnesses per application and per task
where the product supports it.

## 7. Compatibility

- Existing OpenCode CLI adapters remain supported and selectable.
- DStudio TEIP 1.1 delegation remains supported and selectable.
- A harness MUST NOT require any specific inference engine; DCode consumes its configured
  LLM provider (Ollama, OpenAI-compatible, ENR) independently of the caller.

## 8. Versioning

FHC 1.0. Breaking semantic changes require a 2.0 document; additive adapter fields and
endpoints are allowed within 1.x.
