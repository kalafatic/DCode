# PHASE 5 — ARCHITECTURE BASELINE (DCode)

**Date:** 2026-11-16
**Status:** Architecture gate input (WP5.1). All findings below were verified by direct
repository inspection on 2026-11-16; evidence paths are given inline.
**Scope:** Verified current architecture, implementation inventory, previous-phase decisions,
target DCode architecture, ownership, dependencies, integration boundaries, conflicts, missing
contracts, and the implementation roadmap.

---

## 1. Executive Summary

- **DCode repo is empty of code.** `/home/petr/git/DCoder-121126` contains only `README.md`
  (no `.project`, no sources). DCode is a greenfield product and Phase 5 starts from the README
  contract (Java 21, Eclipse RCP/OSGi, Tycho, independent app + server, provider-independent
  LLM, extensible adapters).
- **DStudio repo is also skeleton-only** (`/home/petr/git/DStudio-121126/README.md`). **Phase 4
  (DStudio) has no implementation anywhere in the family.** DCode therefore cannot integrate
  with a DStudio that does not exist; family integration (WP5.12) must target EVO/TRYST via
  TEIP 1.1 + the de-facto FHC adapter contract, and DStudio participant roles stay deferred to
  TEIP 2.0 exactly as TEIP-1.1 §10 prescribes.
- **TEIP 1.1 is the single normative family protocol** (canonical copy:
  `evolution-121126/docs/protocol/TEIP-1.1.md`), implemented and conformance-tested on both
  EVO (server) and TRYST (client + MCP tool plane). Phase 3 is closed and accepted.
- **"FHC" has no written specification anywhere in the family.** The de-facto FHC is the
  Phase 3 `CodingHarnessAdapter` SPI + product-local `ConnectedApplicationRegistry`
  (requirements A-14/A-15), implemented identically in TRYST and EVO. Phase 5 formalizes FHC
  1.0 as a canonical document based on that verified contract (see §11, conflict C1).
- **EVO contains the full CODER/DEVELOP implementation** (Developer Engine, standalone Develop
  subsystem, OpenCode adapter, tool framework, JDT Java tools, VCS, LLM router, TEIP server).
  Its generic coding parts are the migration source for DCode; Darwin/Genome/self-dev/
  architecture-scanner parts remain EVO-specific (see §10).
- **OpenCode is only an external execution capability behind an adapter.** DCode Phase 5 must
  replace it as the *default* native harness while keeping the adapter boundary; nothing in
  EVO/TRYST requires OpenCode as a runtime.
- **Build toolchain verified:** Java 21 at `/usr/lib/jvm/java-21-openjdk-amd64` (machine default
  is Java 8 — builds MUST set `JAVA_HOME`), Maven 3.8.7, Tycho 4.0.x in local `~/.m2`,
  `download.eclipse.org` reachable (HTTP 200), X display `:1` available, `xvfb-run` NOT
  installed. Ollama + OpenCode CLI present for integration testing.

---

## 2. Verified Current Architecture

### 2.1 Product family

| Product | Repo | State (2026-11-16) |
|---|---|---|
| EVO (Evolution) | `/home/petr/git/evolution-121126` | Full RCP + headless platform; 33 Tycho modules; TEIP 1.1 server; CODER/DEVELOP; self-dev |
| TRYST | `/home/petr/git/tryst-121126` | Full RCP + headless server; TEIP 1.1 client; MCP tool plane; harness SPI; OpenCode adapter |
| DStudio | `/home/petr/git/DStudio-121126` | Skeleton (README only) — Phase 4 never implemented |
| DCode | `/home/petr/git/DCoder-121126` | Skeleton (README only) — Phase 5 target |

Family snapshots (`evolution-161126`, `tryst-161126`, older `evolution-*`, `tryst-*`) are
pre-Phase-3 safety copies; the `-121126` repos are authoritative.

### 2.2 EVO (verified inventory highlights)

Build: Tycho 4.0.5 aggregator `eu.kalafatic.evolution.aggregator` 2.6.5-SNAPSHOT; target
Eclipse 2025-12 + Orbit; Java 21; bundles under `eu.kalafatic.evolution.*`.

| Bundle | Role | SWT/Workbench | Headless-usable |
|---|---|---|---|
| `eu.kalafatic.evolution.controller` | Core orchestration, Darwin engines, DEVELOPER mode, harness, TEIP server, tools, VCS, sessions, LLM routing | Yes (hard) | Partially (has `HeadlessApplication`) |
| `eu.kalafatic.evolution.model` | EMF models: `Orchestrator`, `ChatSession`, `ChangeSet`, `FileChange`, `DiffHunk`, `AiMode`, `ReviewSession` | No | Yes |
| `eu.kalafatic.evolution.servers` | NanoHTTPD servers, MCP demo, auth/RBAC, SQLite helpers | No | Yes |
| `eu.kalafatic.evolution.utils` | Log/net/threads/db/semantic/parsers + UI helpers | Yes | Partially |
| `eu.kalafatic.evolution.supervisor` | Self-dev supervision (REST 28480 + TCP 28580) | Optional | Yes |
| `eu.kalafatic.evolution.selfdev.genome` | Genome hub, upgrade engine, milestones | No | Yes |
| `eu.kalafatic.evolution.forge.*` | Native LLM training/inference/export | No | Yes |
| `eu.kalafatic.evolution.view` | RCP UI (DevelopmentPage, AiCommander, PeerReview, Teip page) | Yes | No |
| `eu.kalafatic.evolution.repository` | Products: `evolution.product`, `headless.product` | — | headless product exists |

Key EVO implementation points (all under `eu.kalafatic.evolution.controller/src/...`):

- **Harness:** `harness/CodingHarnessAdapter.java` (SPI), `harness/OpenCodeCodingHarnessAdapter.java`
  (process-spawn, hermetic env, per-run XDG isolation, containment check), `harness/ConnectedApplicationRegistry.java`
  (JSON-persisted, product-local), `harness/ConnectedApplicationDescriptor.java`.
- **Tool framework:** `tools/ITool.java`, `tools/ToolFactory.java` (registers
  `coding_harness_execute`, File/Maven/Git/Shell/Eclipse/Cpp/Database tools), `tools/java/JavaDevelopmentTools.java`
  (JDT-based: inspect_class/method, find_references/callers/implementations, get_diagnostics,
  get_project_structure, get_dependencies, format).
- **DEVELOPER mode:** `orchestration/selfdev/DeveloperEngine.java` (extends `CodingEngine` →
  `ADarwinEngine`), `DeveloperContextProvider.java`; Darwin engine registry/factory
  (`DarwinEngineFactory`, `case DEVELOPER`).
- **Standalone Develop subsystem (Jules-like, generic):** `orchestration/develop/` —
  `DevelopSession`, `DevelopSessionManager` (`~/.evo/develop-workspaces/`), `DevelopTaskManager`
  (priority queue, 4 workers), `DevelopPermissions`, `develop/agent/DevelopAgent`,
  `develop/vcs/GitService(Impl)` (shell git), `develop/tools/{DevelopTool,RunBuildTool,RunTestsTool}.java`,
  `develop/executor/{ProcessExecutor,SandboxProcessExecutor}.java`,
  `develop/workspace/RepositoryWorkspaceManager.java` (mirror cache + worktrees), JSON state
  persistence. Served by `orchestration/EvolutionServer.java` (NanoHTTPD, primary 28080, routes
  `/develop*`, `/develop/tasks/*`). Reserved port `EVO_DEVELOP = 28380`.
- **Git:** `vcs/{VersionControlProvider,GitVersionControlProvider}.java` (shell git),
  `tools/GitTool.java`, EGit/JGit-based `EclipseGitEvoTool.java` (UI-bound), and the independent
  `develop/vcs` implementation.
- **LLM:** `orchestration/llm/ILlmProvider.java` (SPI), `LlmRouter.java`, `OllamaProvider`,
  `OpenAIProvider`, `GeminiProvider`, `NativeEvoProvider`, reasoning-protocol parsers,
  `providers/AiProviders.java` (static catalog incl. deepseek), `security/TokenSecurityService.java`
  (AES token encryption; static key — weak).
- **TEIP server:** `teip/` — `TeipApiController.java` (202/200 idempotent create, Bearer auth,
  dev-mode opt-out), `dto/*`, `service/TeipExecutionManager.java`, `store/TeipEventStore.java`
  (sequenced replay), `store/TeipPersistenceStore.java` (persisted idempotency in
  `data/evolution.db`), `mcp/TrystMcpClient.java`.
- **Diff/review:** EMF `ChangeSet/FileChange/DiffHunk`, `review/service/PeerReviewService.java`.
- **EVO-specific (stays in EVO):** Darwin orchestration (`IterationManager`, `ADarwinEngine`,
  trajectory/fitness/mutation), Architecture Scanner (`design/RepoArchitectureScanner.java`,
  `mediation/**`), Genome (`selfdev.genome` bundle + `SemanticGenome`), self-development
  (`selfdev/*`, `supervisor/*`), Forge, mediated/intent/supervision workflows.

### 2.3 TRYST (verified inventory highlights)

Build: Tycho 4.0.8, target Eclipse 2024-03, Java 21; modules under `plugins/`, `features/`,
`product/`, `tests/`, `targetplatform/`.

| Bundle | Role |
|---|---|
| `eu.kalafatic.tryst.core` | Logging (sanitized), typed EventBus, config service, ports, lifecycle, readiness, async exec |
| `eu.kalafatic.tryst.domain` | Domain models, task orchestration (19-state lifecycle), **harness SPI + registry**, review/approval, security (RBAC), sandbox SPI, build contracts |
| `eu.kalafatic.tryst.infrastructure` | **JDK `HttpServer`** (`TrystHttpServer.java`, ~7,900 lines, embedded SPA, SSE for notifications), SQLite (V1–V17 migrations), Git (`GitService`), build/test exec, Ollama clients, OpenCode adapter |
| `eu.kalafatic.tryst.evo.integration` | TEIP 1.1 client (`TeipClient`), DTOs, execution tracking, MCP tool plane (`/api/mcp`), `EvoCodingHarnessAdapter` |
| `eu.kalafatic.tryst.application` | `IApplication`s (desktop + headless `TrystServerApplication`), `ApplicationLifecycleCoordinator` (single wiring point), HTTP handlers |
| `eu.kalafatic.tryst.ui` | SWT workbench, editor pages, embedded browser |

Key TRYST facts:

- **HTTP stack is JDK `com.sun.net.httpserver` — NOT NanoHTTPD.** EVO uses NanoHTTPD. The
  family has two conventions; DCode adopts NanoHTTPD (Phase 5 mandate + EVO convention). See
  conflict C3.
- **Task lifecycle** (authoritative, `TaskStatus.java` + `TaskStateTransitionValidator`):
  `CREATED → QUEUED → CLAIMED → CLONING → ANALYZING → PLANNING → IMPLEMENTING → BUILDING →
  TESTING → WAITING_FOR_REVIEW → (REWORK_REQUESTED|REJECTED|APPROVED) → COMMITTING →
  COMPLETED`, plus `CANCELLED/FAILED/RECOVERY_REQUIRED/PAUSED/ARCHIVED`. Harness delegation
  happens **only at IMPLEMENTING** (`TaskWorker.java:687-699` via `HarnessAiDelegator`).
- **Review/commit gating:** `CodeReview` (fingerprinted), `GitService.commitAuthorizedReview`
  (SHA-256 diff fingerprint match, review approval gate), AI forbidden from approving/committing.
- **Harness SPI (`domain/harness/CodingHarnessAdapter.java`):** `getApplicationId/Name/Type`,
  `getCapabilities`, `isAvailable`, `testConnection`, `updateConnectionConfiguration` (masked),
  `executeTask(Task, Workspace, instructions, prompt, logConsumer, cancellationSupplier, timeout)`
  → `HarnessExecutionResult(handled, success, summary, errorMessage, timedOut, cancelled,
  modifiedFiles)`. Javadoc is normative: TRYST stays authoritative for task state, validation,
  review, approval, commit; adapter must never write outside the workspace boundary.
- **Registry** (`ConnectedApplicationRegistry` + `DefaultConnectedApplicationRegistry` +
  `ConnectedApplicationRepository` + SQLite impl, migration V17): **product-local** (decision
  D7), not a cross-product service.
- **TEIP 1.1 client:** `TeipClient.java` (202/200, idempotency, `nextSequence` cursor, error
  envelope, 28080 default), `EvoIntegrationService` (pollers, reconciliation), MCP tool plane
  (Bearer + `X-Execution-Id`, `initialize` 2024-11-05, scopes from `evo_executions.granted_scopes`).
- **Persistence:** SQLite/JDBC, WAL, forward-only code migrations V1–V17
  (`SchemaMigrationService.java`), 26 `Sqlite*Repository` classes; `~/.tryst/tryst.db`.
- **Ports:** HTTP 38080 (fallback range 38080–38580), API 38180, TCP 38280, management 38380,
  WS 38480, aux 38580, debug 38680.

### 2.4 DCode / DStudio

- `DCoder-121126`: `README.md` only. Contract in README: independent app+server, Java 21 /
  RCP / Equinox / OSGi / Tycho, provider-independent LLM, concurrent isolated tasks, persistent
  state, extensible language/build adapters, no Spring/Node/Python/brokers.
- `DStudio-121126`: `README.md` only (9 lines). No Phase 4 artifacts exist.

---

## 3. Previous-Phase Decisions (verified)

Phase 1/2 original documents are **lost** (existed only in a corrupted OpenCode session DB;
`tryst-121126/PHASE-3-RECOVERY-AUDIT.md` §2). The following are reconstructed from surviving
normative documents and are treated as authoritative:

| # | Decision | Evidence |
|---|---|---|
| D1 | TEIP 1.1 is the single normative family contract; governance = recorded change + wire-test update + product-owner sign-off; no private variants. DStudio/DCode participant roles deferred to TEIP 2.0. | `TEIP-1.1.md` (NORMATIVE, §10) |
| D2 | Non-negotiable boundary rules: no direct object sharing; task-lifecycle sovereignty (TRYST authoritative for task state/workspace/review/commit in its flow); human-in-the-loop commit gating; sandbox isolation. | `TEIP-1.1.md` §6.5 |
| D3 | TEIP 1.1 canonical wire: 202/200 create, `mode`, `grantedToolScopes`, `caller.system`, object `contextReferences`, `nextSequence`, top-level error envelope, mandatory Bearer auth (explicit dev opt-out), persisted idempotency, MCP `initialize` required. | `TEIP-1.1.md` §1 |
| D4 | Canonical EVO TEIP endpoint `http://localhost:28080` (39080 was a defect). | `TEIP-1.1.md` §9 |
| D5 | TRYST independent of EVO; TEIP confined to `eu.kalafatic.tryst.evo.integration`; zero `eu.kalafatic.evolution.*` references outside it. | TRYST `AGENTS.md` §1/§5 |
| D6 | Coding harnesses are execution capabilities behind an adapter boundary; the product's own engine remains the orchestrator; no hardcoding of one agent; provider specifics stay inside the adapter. | EVO `AGENTS.md` §6; TRYST `AGENTS.md` §13 |
| D7 | Connected-application registries are product-local (TRYST SQLite V17; EVO JSON), never a cross-product shared service. | Javadoc both repos |
| D8 | TRYST built-in CodeAgent remains the fallback; `ExternalAiDelegator` seam reused for harness routing. | `CodingHarnessAdapter.java` javadoc |
| D9 | EVO Developer Mode target resolution (`ChatSession.targetPath` → project root → error) defines the harness containment boundary. | EVO harness javadoc |
| D10 | OpenCode baseline: CLI v1.19.5, `opencode run --agent/--model/--print-logs/--dir/--auto`, local Ollama models, hermetic env. | `OPENCODE_AUDIT_REPORT.md` |
| D11 | Phase 3 scope = EVO + TRYST only; DStudio/DCode out of scope. Phase 3 closed, accepted: TEIP 1.1 verified live; 305/305 TRYST tests; 10/10 EVO harness; 37/37 EVO TEIP wire. | `PHASE-3-FINAL-CLOSURE.md` |

**Phase 4:** no implementation exists (DStudio skeleton). Treat Phase 4 as "not executed" —
documented as conflict C2, not silently filled in.

---

## 4. Target DCode Architecture

One shared domain/execution core, three first-class access modes (RCP, Headless, Web), layered
with strict dependency inversion. Presentation never contains business logic; Headless has no
SWT/Workbench; Web has no business logic in JS.

### 4.1 Layers

```
PRESENTATION      DCode RCP (SWT/JFace)   |   DCode Web (HTML/CSS/JS)   |   HTTP API clients
APPLICATION       Session AppSvc · Agent AppSvc · Repository AppSvc · Config Svc · Execution AppSvc · Review/Change Svc
DOMAIN/HARNESS    AgentEngine · OrchestrationEngine · ContextEngine · ToolEngine · CodingEngine ·
                  LanguageTooling SPI · GitEngine · WorkflowEngine · ExecutionEngine · EventEngine
INFRASTRUCTURE    LLM Providers · JDT/Eclipse integration · JGit · SQLite · Filesystem · NanoHTTPD ·
                  SSE · External tool adapters
```

### 4.2 Planned OSGi bundles (all new, groupId `eu.kalafatic.dcode`)

| Bundle | Responsibility | Depends on | Headless-safe |
|---|---|---|---|
| `eu.kalafatic.dcode.core` | Domain model, session/execution state machines, event model, lifecycle, service contracts, `LanguageTooling` SPI (interfaces only), port registry, logging | OSGi, `org.eclipse.core.runtime`, sqlite-jdbc (only in persistence impls — no, see below) | Yes (no SWT) |
| `eu.kalafatic.dcode.config` | Provider SPI (`LlmProvider`), provider registry, model registry, role-based model routing, credential vault (encrypted at rest), git accounts, central configuration | `dcode.core` | Yes |
| `eu.kalafatic.dcode.repositories` | Workspace model (multi-repo), repository registry, JGit-based GitEngine, access policies, cross-repo context | `dcode.core` | Yes |
| `eu.kalafatic.dcode.persistence` | SQLite schema + migrations, `Sqlite*Repository`s, filesystem artifact store, retention/backup | `dcode.core`, sqlite-jdbc | Yes |
| `eu.kalafatic.dcode.harness` | AgentEngine, OrchestrationEngine, ContextEngine, ToolEngine (registry + permissions + audit), CodingEngine, ExecutionEngine, change/diff management, review | `dcode.core`, `dcode.config`, `dcode.repositories`, `dcode.persistence` | Yes |
| `eu.kalafatic.dcode.java.tooling` | Java `LanguageToolProvider` using JDT Core (project model, AST, type resolution, references, hierarchies, diagnostics, refactorings, Maven/Tycho build, PDE/OSGi analysis) | `dcode.core` (SPI), JDT, m2e-free Maven invocation | Yes (JDT Core only; refactorings headless subset) |
| `eu.kalafatic.dcode.server` | NanoHTTPD runtime: REST `/api/v1/*`, SSE event streams, cancellation, monitoring; headless `IApplication`; TEIP 1.1 server endpoints for family delegation | `dcode.core/harness/persistence/config/repositories` | Yes (headless-only, no SWT) |
| `eu.kalafatic.dcode.web` | Static web UI assets (HTML/CSS/JS) served by server bundle | none (assets) | n/a |
| `eu.kalafatic.dcode.ui` | RCP workbench: workspace explorer, editor, chat, sessions, diff viewer, review, git, console, build/test views, configuration | `dcode.core/harness/...`, SWT/JFace, Workbench | No (RCP-only) |
| `eu.kalafatic.dcode.application` | Product `IApplication` for RCP mode (or stays in `dcode.ui`), local-runtime bootstrap, remote-connect client | `dcode.ui`/`dcode.server` | split per mode |
| `eu.kalafatic.dcode.teip` | TEIP 1.1 client DTOs/client for EVO delegation + FHC 1.0 server surface consumed by TRYST via a future TRYST-side adapter (WP5.12; adapter code lives in TRYST per D5) | `dcode.server` | Yes |
| `eu.kalafatic.dcode.tests` | Tycho surefire tests (domain, harness, repos, java tooling, persistence, server API, integration) | all | n/a |

Plus `targetplatform/eu.kalafatic.dcode.target` (Eclipse 2025-12 + Orbit, JUnit Jupiter, JDT,
JGit, NanoHTTPD, sqlite-jdbc, Jackson), `features/eu.kalafatic.dcode.feature`,
`product/eu.kalafatic.dcode.product` (RCP + headless products in an `eclipse-repository` module).

### 4.3 Technology decisions (verified against family)

- Java 21, Eclipse RCP, OSGi (DS for service registration where practical), Tycho 4.x,
  Eclipse 2025-12 target (matches EVO; newer than TRYST's 2024-03).
- **HTTP: NanoHTTPD** (Phase 5 mandate; matches EVO `EvolutionServer`). TRYST's JDK HttpServer
  remains TRYST-internal (C3).
- **Git: JGit** (Java-native; EGit/JGit already used in EVO). Shell-git patterns from EVO
  `develop/vcs` are the reference behavior.
- **SQLite via sqlite-jdbc** (bundled `lib/` like EVO/TRYST), WAL, forward-only migrations
  (TRYST `SchemaMigrationService` pattern).
- **JSON: Jackson** (bundled; used by EVO servers and TRYST infrastructure).
- **Streaming: SSE** over NanoHTTPD (TEIP 1.1 event replay pattern as fallback for durability).
- No Spring/Node/Python/brokers; no second plugin system; no frontend framework.

### 4.4 DCode session model (unified, cross-client)

`Session`: id, workspace ref, repository associations (with per-repo access policy
READ_ONLY/READ_WRITE), provider/model routing, instructions, message history, agent-run records,
tool-call records, sequenced events (replayable), artifacts, build/test results, state machine:

`CREATED → RUNNING → WAITING_FOR_APPROVAL → COMPLETED | FAILED | CANCELLED`, plus
`SUSPENDED` (explicit pause), `ARCHIVED` (retention). Transitions validated by a single
`SessionLifecycleManager`. Runtime execution state is separate from persisted state; on restart,
in-flight sessions transition to `FAILED` (with diagnostic) or `SUSPENDED` — never blindly
re-run. Concurrent modification is guarded by a single writer lock per session; multiple
clients may observe concurrently.

### 4.5 Port conventions

Family convention is tens-digit-per-product: EVO 28xxx, TRYST 38xxx. **Proposal for DCode:
48xxx** — HTTP 48080 (fallback range 48080–48580), management 48380, auxiliary 48680.
(Proposal only; no previous decision conflicts with it. DStudio would follow 58xxx in its own
phase.)

---

## 5. Component Ownership Matrix

| Capability | DCode | DStudio | EVO | TRYST | FHC/TEIP | Notes |
|---|---|---|---|---|---|---|
| Native coding engine, harness internals | **Owner** | — | consumes via contracts | consumes via adapter | DCode publishes | EVO's experimental "native EVO Coder" remains separate (EVO AGENTS.md §6) |
| DCode sessions & execution state | **Owner** | — | references only (executionId) | references only | delegated-session refs = FHC 1.1 | no cross-product shared session store |
| Multi-repository workspace engine | **Owner** | — | keeps EVO self-dev repo handling | keeps TRYST sandbox/mirror pool | — | no shared repo registry |
| Generic coding tools (file/git/build/shell) | **Owner** | — | EVO tools stay until migration verified | TRYST tools stay | — | WP5.11 migrates EVO duplicates after verification |
| Java tooling (JDT) | **Owner** (native provider) | — | existing JDT tools remain in EVO until migration verified | — | — | same rule as above |
| LLM provider/config (per product) | Owner of DCode config | (none) | Owner of EVO config | Owner of TRYST config | TEIP capabilities | registries are product-local (D7) |
| TEIP 1.1 protocol | participant | participant (2.0) | server (authoritative impl) | client + MCP plane | **Spec owner: family** | canonical spec in EVO repo |
| FHC 1.0 contract | **Spec caretaker (canonical doc) + primary implementor** | consumer (later) | consumer (existing SPI) | consumer (existing SPI) | spec is family-level | see C1 |
| Task lifecycle for TRYST tasks | delegate at IMPLEMENTING only | — | — | **Owner/sovereign** | TEIP §6.5 | DCode never commits/pushes in delegated mode |
| Review/approval/commit gating | Owner for DCode-standalone sessions | — | — | Owner for TRYST tasks | TEIP §6.5 | human gates in both |
| Architecture Scanner, Genome, self-dev | — | — | **Owner** | — | — | never migrates |
| Family-level intermediary/aggregation | — | **Owner (future)** | — | — | TEIP 2.0 roles | nothing to build against in Phase 5 |

---

## 6. Dependency Direction

```
                    ┌──────────────────────────────────────────┐
                    │            eu.kalafatic.dcode            │
                    │  ui ──► application ◄── server ◄── web   │
                    │   │          │             │             │
                    │   ▼          ▼             ▼             │
                    │  harness ──► config ──► repositories     │
                    │   │          │             │             │
                    │   ▼          ▼             ▼             │
                    │  persistence ──► core ◄── java.tooling   │
                    └──────────────────────────────────────────┘
                                  (no EVO/TRYST/DStudio deps)

   TRYST ──(FHC adapter in TRYST, WP5.12)──► DCode server API / TEIP 1.1 surface
   EVO   ──(TEIP 1.1 client, optional)─────► DCode TEIP surface
   DCode ──(TEIP 1.1 client, optional)─────► EVO 28080 /teip/v1   (EVO-specific cognitive work)
```

Rules: DCode bundles have zero references to `eu.kalafatic.evolution.*`,
`eu.kalafatic.tryst.*`, or DStudio. Integration is wire-only (JSON over HTTP), DTOs owned per
product (open-world receivers per TEIP). DStudio, when implemented, orchestrates via TEIP/FHC
only — it never links DCode internals.

---

## 7. Integration Boundaries

| Boundary | What it is | Who implements in Phase 5 |
|---|---|---|
| **TEIP 1.1** | Family protocol: execution delegation, status/events/result, capabilities, errors, auth, MCP tool plane. Server role currently EVO-only; client role TRYST. | DCode implements a conformant TEIP 1.1 server surface (additive; no protocol change, no new endpoints). Client use optional. |
| **FHC 1.0** | Harness-level contract: adapter SPI + product-local registry + delegation semantics (containment, cancellation, timeout, result). | DCode writes the canonical FHC 1.0 doc (from verified Phase 3 SPI); DCode server exposes an FHC execution surface; TRYST-side `DCodeCodingHarnessAdapter` lands in TRYST only in WP5.12 and only if TRYST work is authorized. |
| **DCode internal APIs** | `/api/v1/*` (sessions, workspaces, repos, providers, tools, changes, builds, config, monitoring) + SSE | DCode. Not part of TEIP/FHC; versioned separately; open-world JSON. |

**TEIP vs FHC vs DCode-internal:** TEIP = family-to-family task/execution exchange with its own
execution lifecycle; FHC = how a *product-local orchestrator* (TRYST/EVO) delegates coding
execution to a harness while keeping its own task lifecycle; DCode API = DCode's own platform
surface for its clients (RCP/Web/external). These are three distinct contracts; no bilateral
custom integrations will be invented.

---

## 8. Session and Execution Ownership

- DCode owns its sessions end-to-end (create/resume/suspend/cancel/complete/fail/archive),
  including in local RCP mode (RCP uses the same core via the embedded server or in-process
  services — one implementation).
- DCode delegated executions (via TEIP/FHC) are *executions of the delegating product*; DCode
  records them in its execution history but never marks the delegating product's task/state.
- TRYST retains full authority over TRYST task state, sandbox, review, approval, commit.
  DCode never commits/pushes in delegated executions.
- In standalone DCode sessions, commits/pushes are allowed only with explicit per-session
  user authorization (read-only repos are enforced by policy, not convention).

## 9. Persistence Ownership

- DCode owns `~/.dcode/` — `dcode.db` (SQLite, WAL, migrations) + `workspaces/`, `artifacts/`,
  `logs/`, `snapshots/`. No sharing of TRYST/EVO databases; no writes into other products'
  data dirs.
- DB stores structured data (providers, models, credentials references, git accounts, repo
  registry, workspaces, sessions, messages, agent/tool records, events, config, build/test
  results, artifact metadata); filesystem stores large artifacts (diffs, logs, reports, repo
  worktrees).
- Credentials: encrypted at rest with a local key (improving on EVO's static-key
  `TokenSecurityService`), never logged, never exported in config dumps; masks in API responses.

## 10. Migration Boundaries (EVO CODER/DEVELOP → DCode)

Classification per §17.1 of the Phase 5 mandate (A=move, B=stay, C=adapter, D=reuse, E=clarify):

| EVO component | Class | Notes |
|---|---|---|
| `harness/` SPI + registry + OpenCode adapter | A→DCode | DCode implements its own native harness; keeps an OpenCode *adapter* for compatibility; EVO copy stays until WP5.11 verification |
| `orchestration/develop/**` (sessions, tasks, agent, git, tools, executors) | A→DCode | the DCode-shaped asset; decoupled at EVO `SessionManager`/`ChatSession`/`LlmRouter` seams |
| `tools/` framework + generic tools + `java/JavaDevelopmentTools` | A→DCode | JDT tools reimplemented headless in `dcode.java.tooling`; EVO tools stay until migration verified |
| `vcs/`, `develop/vcs` shell-git providers, `ProcessExecutor` sandbox | A→DCode | behavior reference for JGit-based GitEngine |
| `llm/` provider SPI + reasoning parsers, `providers/` | A→DCode | same SPI shape, DCode-owned config/credentials |
| `review/PeerReviewService` + `ChangeSet/FileChange/DiffHunk` | A→DCode | DCode domain model (not EMF) |
| `teip/` server | B→EVO | EVO keeps its TEIP server; DCode writes its own conformant server surface from the spec |
| Darwin engines, trajectory, fitness, `IterationManager` | B | never migrates |
| Architecture Scanner, `mediation/**` | B | EVO-specific |
| Genome, self-dev, supervisor, Forge | B | EVO-specific |
| `view/` bundle, DevelopmentPage etc. | B (rewritten in DCode RCP) | EVO UI untouched |
| OpenCode/CLI adapter behavior | C | DCode keeps an external-harness adapter; TRYST keeps its own |
| NanoHTTPD server patterns, sqlite helpers | D | pattern reuse, not code copy |

**Migration safety rule:** EVO keeps its working CODER/DEVELOP implementation until the DCode
replacement is functionally verified end-to-end. WP5.11 only then deprecates EVO copies. No EVO
code is removed in earlier work packages.

---

## 11. Identified Architectural Conflicts

| # | Conflict | Smallest architecture-consistent resolution |
|---|---|---|
| C1 | **"FHC" has no specification anywhere.** Phase 5 mandate requires reusing "verified FHC architecture"; only the Phase 3 `CodingHarnessAdapter` SPI (A-14/A-15) exists, implemented identically in TRYST and EVO. | Formalize **FHC 1.0** as a canonical document (in DCode repo) that codifies the verified SPI + registry + delegation semantics (containment, cancellation, timeout, result, product-local registry, orchestrator sovereignty). Extensions (delegated-session identity, structured progress events, capability negotiation) are recorded as **FHC 1.1 candidates** and are NOT shipped as TRYST/EVO changes in Phase 5. DCode implements the FHC execution surface behind its HTTP API. |
| C2 | **Phase 4 (DStudio) was never implemented**, but the Phase 5 mandate assumes Phase 4 results exist. | Treat DStudio as out of scope (consistent with D11/TEIP §10). DCode is built fully independent; WP5.12 integrates with EVO/TRYST only; DStudio integration is deferred to its own phase. No DStudio-facing code is fabricated. |
| C3 | **Phase 5 mandate says NanoHTTPD "consistent with existing EVO/TRYST architecture", but TRYST uses JDK `HttpServer`** (EVO does use NanoHTTPD). | DCode uses NanoHTTPD (mandate + EVO convention). TRYST's server remains untouched. Documented here so the divergence is explicit, not accidental. |
| C4 | **EVO AGENTS.md calls DEVELOPER "a Darwin Engine capability/mode, not a separate competing orchestration engine"** while Phase 5 moves generic coding orchestration to DCode. | DCode does not modify EVO's Darwin routing. EVO's DEVELOPER mode keeps working; DCode is reached through TEIP/FHC delegation when EVO wants DCode execution. Removal/rerouting of EVO's DEVELOPER mode is a WP5.11 decision gated on verified equivalence. |
| C5 | **Phase 1/2 decision documents are lost** (only D1–D11 reconstructions survive). | Surviving normative docs (TEIP-1.1.md, both AGENTS.md, Phase 3 closure/recovery docs) are authoritative. DCode records any decision that depends on the lost text as "unresolved" rather than guessing. |
| C6 | **EVO/TRYST AGENTS.md forbid all git operations by coding agents in those repos** (user controls Git via EGit). | DCode's own repo follows the global rules; DCode's *runtime GitEngine* (product feature) is unaffected. WP5.11 EVO-side work never executes git in the EVO repo. |
| C7 | **Mandate §24 requires "one session can coordinate changes across multiple repositories" with commits per repo, while TEIP §6.5 forbids remote engines from committing.** | Distinction enforced: family-delegated executions (TEIP/FHC) never commit/push — TRYST stays sovereign. DCode-standalone sessions may commit only with explicit per-session, per-repo user authorization. Documented in the session/permissions model, not left implicit. |
| C8 | **No Xvfb on the build machine** (TRYST docs reference `xvfb-run`); a real X display `:1` is available. | RCP/UI tests run against `DISPLAY=:1`; headless tests never need a display. Documented in build docs. |

---

## 12. Missing Contracts and Unresolved Decisions

1. **FHC text** — resolved by C1 (formalized from verified implementation).
2. **DStudio roles** — deferred (C2, TEIP-1.1 §10).
3. **Lost Phase 1/2 catalog** (A-14/A-15 origin text) — treated as reconstructed; any future
   requirement depending on the lost text is raised with the product owner before
   implementation (C5).
4. **DCode port range 48xxx** — proposal in §4.5, needs product-owner confirmation (no
   conflicting prior decision).
5. **TEIP 2.0 candidates** (SSE push, DCode participant roles, capability handshake) — NOT
   started in Phase 5; DCode's own SSE/API covers its clients; family push streaming waits for
   2.0 governance.
6. **TRYST-side `DCodeCodingHarnessAdapter`** — requires TRYST repo authorization; WP5.12
   either implements it (if authorized) or ships the verified DCode FHC surface + integration
   test plan for later wiring.

---

## 13. Implementation Roadmap (WP5.2 – WP5.13)

| WP | Deliverable | Gate / first verifiable milestone |
|---|---|---|
| 5.1 | This baseline | Architecture gate (this document) |
| 5.2 | DCode Core + shared domain: bundles `core`, target platform, build; domain model, events, lifecycle, service contracts, OSGi DS wiring | `mvn verify` green; Core initializes standalone (no RCP) in a plain OSGi/Equinox runtime test |
| 5.3 | Multi-repo & workspace engine (`repositories`): registry, workspace, JGit ops, access policies | JGit-based test: add 2+ repos, cross-repo status/diff, read-only enforcement verified |
| 5.4 | Providers & config (`config`): provider SPI, registry, model discovery (Ollama `/api/tags`), role routing, credential vault | Live connectivity test against Ollama; credential round-trip + masking tests |
| 5.5 | Native harness (`harness`): AgentEngine, Orchestration, ContextEngine, ToolEngine + registry, ExecutionEngine, change/diff, review, cancellation, recovery | **First independent milestone:** a real coding task (file edit + build/test) completes end-to-end using the native harness — no OpenCode process involved |
| 5.6 | Java tooling (`java.tooling`): JDT project model, AST, references, hierarchies, diagnostics, refactorings, Maven/Tycho build+test, PDE/OSGi analysis; headless-classified | Real Java scenario: analyze a real project (e.g., a DCode bundle itself), produce correct references/hierarchy, apply a verified rename |
| 5.7 | Sessions & persistence (`persistence`): SQLite schema V1+, migrations, artifact store, session lifecycle, recovery | Restart test: session created → server restart → session resumable, in-flight transitions to FAILED/SUSPENDED with diagnostics |
| 5.8 | Headless runtime & API (`server`): NanoHTTPD, `/api/v1/*`, SSE, cancellation, monitoring, TEIP 1.1 surface | Full coding session executed over HTTP only (no SWT/Workbench on classpath) |
| 5.9 | Web UI (`web`): dashboard, workspaces, repos, sessions, chat, execution, changes, diffs, build/test, logs, providers/models, config, monitoring | Create → monitor → resume → inspect a session entirely in browser |
| 5.10 | RCP IDE (`ui`): workspace explorer, editor, chat, session manager, diff viewer, review, git, console, build/test, config | Full coding workflow in RCP against the same Core + persisted sessions |
| 5.11 | EVO CODER/DEVELOP migration (EVO repo, no git ops, no premature removal) | Each migrated capability verified in DCode first; EVO delegation via TEIP/FHC verified; only then deprecation in EVO |
| 5.12 | Family integration: DCode FHC surface + (if authorized) TRYST adapter; EVO↔DCode TEIP verification | End-to-end: TRYST delegates an IMPLEMENTING stage to DCode; TRYST remains sovereign through review/commit; no duplicate session managers |
| 5.13 | Final verification: regression, integration, recovery, operational tests; `PHASE-5-TEST-REPORT.md`, `PHASE-5-FINAL-CLOSURE.md` | All §24 checkboxes supported by executed evidence |

**First independently verifiable milestone:** WP5.2 complete (Core initializes headless with
tests) → immediately followed by WP5.3/WP5.4 → WP5.5's end-to-end native harness run. The
milestone is verifiable without UI: headless execution of a coding task with persisted session.

## 14. Critical Risks

1. **Scope size** — 12 work packages spanning four repositories; mitigated by strict
   incremental gates and the rule that EVO/TRYST are untouched until WP5.11/5.12.
2. **Tycho first-build cost** — new target platform (Eclipse 2025-12) must resolve; network
   verified. Mitigated by reusing TRYST's proven parent-POM pattern.
3. **JDT headless refactoring limits** — some refactoring APIs need workspace resource
   semantics; mitigated by classifying capabilities headless/RCP-only (§9.4 mandate) and
   gating claims on actual execution.
4. **Live-model verification** — harness end-to-end depends on local Ollama models; mitigated
   by provider abstraction + capability detection, and by tests that assert behavior with a
   scriptable fake provider as fallback.
5. **Family-repo authorization** — WP5.11/5.12 require changes in EVO/TRYST under their own
   AGENTS.md rules (no git, user-controlled); if authorization is withheld, DCode-side
   surfaces ship verified and integration stays documented-as-planned (per mandate §25).

---

*Baseline prepared 2026-11-16 by repository inspection. No files outside the DCode repository
were modified during this audit.*

## 15. WP5.2 Build Verification Record (2026-11-16)

Verified during WP5.2 implementation; supersedes the 2025-12 assumptions in §4.3:

| Fact | Result |
|---|---|
| Tycho 4.0.8 + Maven | Requires Maven ≥ 3.9; system Maven is 3.8.7. **Use `~/.m2/wrapper/dists/apache-maven-3.9.12/6068d197/bin/mvn`** (verified). |
| Target platform | **Eclipse 2024-03** (single location), not 2025-12. Reason: Tycho 4.0.8's `junit59` surefire provider embeds a DisplayNameGenerator compiled against JUnit ≤ 5.10 and breaks against JUnit 5.14/6.0.1 (the only versions in the 2025-12 release repo). JUnit pinned to 5.10.2 / platform 1.10.2 (TRYST-proven). |
| Declarative Services | `org.eclipse.equinox.ds` is **not present in the 2025-12 SimRel repo** (verified by content scan) and DS-based wiring was therefore not adopted. Services are registered manually via `CoreActivator` (family convention, TRYST pattern). |
| Java | `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64` mandatory (machine default Java is 8). |
| WP5.2 gate | `mvn -B clean verify` green: core bundle + 24/24 tests pass; `CoreRuntimeTest` proves core initializes headless (no SWT/RCP). |

## 16. Work Package Verification Log

All entries verified by executed builds on 2026-11-16 (Maven 3.9.12 wrapper, Java 21).

| WP | Bundles delivered | Gate evidence |
|---|---|---|
| 5.2 | `eu.kalafatic.dcode.core` | Core initializes headless; domain/state machine/event bus/lifecycle/ports/log-sanitizer tests green |
| 5.3 | `eu.kalafatic.dcode.repositories` | JGitEngine against real repos: init/commit/diff/log/clone/push/pull/branch/checkout; cross-repo change collection; READ_ONLY mutation rejection |
| 5.4 | `eu.kalafatic.dcode.config` | Provider SPI + Ollama + OpenAI-compatible over real HTTP (mock wire tests); LIVE Ollama discovery verified; capability detection; credential vault; git accounts; role-based model routing |
| 5.5 | `eu.kalafatic.dcode.harness` | **Native harness E2E:** coding task (list→write→git_status→final) executes through the DCode agent loop with real tools and real Git — no OpenCode process; failure path marks session FAILED; tool audit + events verified |
| 5.7 | `eu.kalafatic.dcode.persistence` | SQLite V1 schema + migrations; sessions/messages/workspaces/repositories/reviews survive simulated restart; per-session event log with cursor replay; AES-GCM encrypted secrets (verified no plaintext on disk); restart recovery marks RUNNING/WAITING_FOR_APPROVAL → FAILED with diagnostics |
| 5.8 | `eu.kalafatic.dcode.server` | NanoHTTPD REST `/api/v1/*` + SSE; full coding session executed over HTTP with zero SWT/Workbench on the path; OSGi runtime test starts all 7 bundles in Equinox — durable services preferred (ranking), HARNESS+SERVER lifecycle stages reached, HTTP server binds |
| 5.6 | `eu.kalafatic.dcode.java.tooling` | Native Eclipse JDT/PDE tooling: headless ResourcesPlugin workspace with linked projects, JRT + Maven + reactor (CPE_PROJECT) classpaths, 13 registered tools (discovery, batch-compiler diagnostics, AST bindings, type/call hierarchy, reference/implementor search, import & dependency analysis, LTK rename with reference updates, PDE bundle inspection + cross-bundle package resolution). Details: `PHASE-5-WP-5.6-JDT-IMPLEMENTATION-REPORT.md` |

| 5.9 | `eu.kalafatic.dcode.server` (web UI) | Professional web IDE served from the server bundle (same origin): explorer, sessions/tasks, live SSE console, tool activity, all 13 JDT/PDE tools via `POST /api/v1/tools/{name}/execute`, rename confirmation, diff review; 5 minimal additive endpoints (tree/files/tools/changes/static). Docs: `PHASE-5-WP-5.9-*`. |

| 5.10 | `eu.kalafatic.dcode.client`, `eu.kalafatic.dcode.ui`, feature, product | Native Eclipse RCP IDE (workbench, 9 views, source/diff editors, workspace explorer with git ops, sessions/chat/activity/changes/java-tools/providers/connection) over the same `/api/v1` + SSE contract as Web; local (embedded runtime) + remote modes via `DCodeApiClient`; product + headless product materialized. Docs: `PHASE-5-WP-5.10-*`. |

Cumulative test result at end of WP5.10: **117 tests, 0 failures, 0 errors** (1 live-model completion skipped — no
`-Ddcode.live.model` opt-in; local Ollama hosts only EVO experiment models that fail their own chat API).

### 16.1 Deviations discovered during implementation (recorded honestly)

| # | Deviation | Resolution |
|---|---|---|
| V1 | Equinox DS (`org.eclipse.equinox.ds`) is absent from the 2025-12 SimRel repo | Manual OSGi service registration (family convention); retry-safe `ServiceAvailabilityGate` in core makes bundle start order irrelevant |
| V2 | Bundle lazy-activation made activator timing order-dependent | All activators re-wired through `ServiceAvailabilityGate`; verified by an OSGi runtime test that starts bundles in arbitrary order |
| V3 | Tycho surefire classpath treats embedded `lib/*.jar` (jackson/nanohttpd) as restricted API for consumers | Tests bundle embeds the same jars; `@SuppressWarnings("restriction")` where import-level |
| V4 | sqlite-jdbc requires explicit driver registration inside OSGi | `Class.forName("org.sqlite.JDBC")` in `SqliteDatabaseManager`; slf4j-api bundled alongside |
| V5 | `ServiceTracker` classes are discouraged API in org.eclipse.osgi | Replaced with core `DynamicServiceLookup` (standard `org.osgi.framework` API only) |
