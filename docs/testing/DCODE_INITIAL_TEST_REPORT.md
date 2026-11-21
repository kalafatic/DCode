# DCode — Initial Functional Test Report (ENR + Ollama Integration Iteration)

Date: 2026-11-21
Status: Test → Fix → Verify iteration completed. All confirmed defects in scope fixed and
re-verified; remaining items documented as deferred decisions / known limitations.

Sections:

1. Environment and architecture findings
2. Initial baseline (test results before fixes)
3. Implemented fixes
4. Post-fix verification
5. Remaining defects
6. Known limitations
7. Modified files

---

## 1. Environment and Architecture Findings

| Item | Value |
|---|---|
| DCode repo | `/home/petr/git/DCode-121126` (Tycho 4.0.8, Java 21, Eclipse 2024-03 target) |
| Build command | `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw -B verify` |
| ENR repo | `/home/petr/git/evo-native-runtime-161126` (started with `./enr.sh serve -c enr.yaml`) |
| ENR endpoint | `http://127.0.0.1:8111` — native `/enr/v1/*`, Ollama-compatible `/api/*`, OpenAI `/v1/*`, model mgmt `/api/v1/*` |
| ENR models | `evo` (53952 params, ctx 4), `tiny-evo` (920 params, ctx 32) — protocol-test models |
| Ollama | `http://127.0.0.1:11434` (0.40.16), 23 models; tools-capable: `qwen3.8-32k:latest` (used for agent runs), `qwen3-coder:30b` |
| DCode data dir | `~/.dcode` (`dcode.db`, `dcode-config.json`, `runtimes.json`, `agents.json`, `profiles.json`, `secrets.enc`) |
| DCode ports | HTTP 48080 + fallback range (PortAllocator); configured values win |
| Machine | 64-core EPYC, 121 GB RAM, DISPLAY=:1 |

Architecture facts verified by inspection (no redesign performed):

- One shared core; RCP/Web/Headless are access modes over the same backend. The RCP UI talks
  to the embedded local HTTP server (same process, different port when headless occupies
  48080).
- "Native TEIP" between DCode and ENR is the **family ENR 1.0 wire contract** at `/enr/v1/*`
  (health, models, chat, chat/stream, load/unload, runtime) implemented by
  `EnrProvider` (`plugins/eu.kalafatic.dcode.config/.../provider/EnrProvider.java`).
  TEIP 1.1 proper (EVO 28080) is a family protocol DCode does not consume today; DCode↔ENR
  uses ENR's native family surface. Nothing was replaced with Ollama.
- Ollama compatibility is the `OllamaProvider` (`/api/tags`, `/api/chat`, streaming) — an
  additional protocol, selectable per runtime registration (`runtimes.json`). Protocol
  selection is deterministic: the runtime id/type selected on a session or request decides
  the adapter; no silent cross-routing was found.
- Filesystem-only project model: workspaces are plain directories (`rootPath`); the
  repository abstraction is optional metadata. All test scenarios ran without any Git
  involvement. No Git functionality was added.
- Terminal execution = `shell` tool (`harness/tools/ShellTool.java`): `/bin/bash -c`,
  workspace-root cwd confinement, env sanitization, 300 s default timeout (3600 s cap),
  100 k output cap, merged stdout/stderr, non-zero exit → `ToolResult.failure`. Tools run
  exclusively through `ToolEngine` (permission gate + audit + events).
- Agent loop (`orchestration/ToolCallingAgentLoop.java`): the model emits exactly one JSON
  object per response — `{"tool": "...", "arguments": {...}}` or `{"final": "..."}`; tool
  output is fed back as a user message; max 20 iterations.
- Sessions are central entities; state changes go through
  `SessionState.isValidTransition(...)` only. `COMPLETED`/`FAILED`/`CANCELLED` are terminal
  (documented design, regression-tested); continuing a conversation after COMPLETED
  requires a new session (see §6).

## 2. Initial Baseline (before fixes)

Result classes: PASS / FAIL / BLOCKED / NOT TESTED.

| # | Test | Result | Evidence |
|---|---|---|---|
| B1 | Headless mode (start, HTTP, runtimes, graceful stop) | PASS | health UP, stages STARTED, SIGTERM stops cleanly |
| B2 | Headless + Web (assets, API) | PASS | all 11 web assets 200; `/api/v1/*` endpoints respond |
| B3 | RCP mode | **FAIL** | workbench event loop crashed; app exited (see DEFECT-1, DEFECT-2) |
| B4 | Web lifecycle (config keys, start/stop/restart) | PASS | verified after DEFECT-2 fix; feature present at baseline |
| C | AI Commander workflow + config persistence | PASS (backend) | session create/start/messages/toolcalls via API; `runtimes.json` survives restart; UI verified by code inspection + window/rendering logs (visual verification not possible for the text-only test agent) |
| D | ENR native integration (health, models, chat, streaming, multi-turn, load/unload, errors) | PASS | live against ENR 8111; error attribution correct (`enr` runtime) |
| E | Ollama compatibility (direct 11434 and via ENR's `/api`) | PASS | tags/chat/stream/multi-turn on all three runtime paths; protocol independence verified |
| F1 | Basic terminal commands | PASS | `pwd` → workspace root; `whoami`, `uname`, `java`, `mvn` real output |
| F2 | Filesystem operations | PASS | mkdir/write/read/modify/list/delete in `/tmp/dcode-enr-test` |
| F3 | Java app creation + compile + run + verify | PASS | `Main.java` → `DCODE_ENR_INTEGRATION_OK` verified (2 tool calls, JDK 21 chosen correctly) |
| F4 | AI-assisted modification | PASS | read → edit → compile → run → `DCODE_ENR_MODIFICATION_OK`; model self-recovered from one wrong-argument retry |
| F5 | Error handling (invalid command) | PASS | non-zero exit + stderr captured, `success:false`, no false success |
| F6 | Full tool round trip | PASS | instruction → qwen3.8-32k → tool calls → real shell → results → final summary |
| G1 | Basic AI chat | PASS | ENR receives, model answers, DCode displays |
| G2 | Workspace inspection | PASS | list_files/read_file → accurate summary (no Git metadata required) |
| G3 | README creation | PASS | README.md created with accurate contents |
| G4 | Code generation | PASS | same as F3 |
| G5 | Code debugging | PASS | real compile error reproduced, cause identified, fixed, verified (`S5_DEBUG_FIXED`) |
| G6 | Conversation context | PASS | within-run tool loop; direct-chat multi-turn retains context ("What is my name?" → "I'm Alice") |
| G7 | Protocol comparison | PASS | TEIP(native ENR) + Ollama both functional; streaming/tooling/context/errors verified per protocol |
| — | JDT tooling in headless | FAIL (degraded, non-fatal) | `JDT/PDE tooling unavailable ... SearchRequestor` (headless has no Eclipse resources workspace; documented behavior) |
| — | JDT tooling in RCP | **FAIL** | JDT core crashed at bundle start before the workspace opened (DEFECT-2) |
| — | Live regression suites (JUnit) | FAIL (infra) | `EnrLiveIntegrationTest` skipped: `ModelManagementApiTest` cleared the shared `dcode.enr.endpoint` system property (DEFECT-4); CLI `-D` properties were also not forwarded to the Tycho test JVM (DEFECT-5) |

Baseline suite: 193 tests, 0 failures, 3 skipped (RcpWorkbenchSmokeTest headless-skip and
the two misbehaving live skips).

## 3. Implemented Fixes

### DEFECT-1 — RCP workbench crash: e4 services never registered (fatal)

Root cause: the RCP product started `org.apache.felix.scr` **without** auto-start
(`org.apache.felix.scr_2.2.10.jar@4`, no `:start`), so all OSGi Declarative Services of the
e4 workbench stack (IEventBroker etc.) were dead. The workbench died with
`Unable to create class ...CommandProcessingAddon ... no actual value was found for the
argument "IEventBroker"`, followed by an NPE cascade
(`Platform.getContentTypeManager() null`, `ExtensionTracker null`) and process exit.

Fix: `product/eu.kalafatic.dcode.product/dcode.product` — added
`<plugin id="org.apache.felix.scr" autoStart="true" startLevel="2"/>` to the product
configurations (mirrors the earlier verified headless-product fix).

Verification: RCP starts, window renders (`PartRenderingEngine` active, X window
"DCode — Darwin Code" 1440x863), **0** `!ENTRY` errors in the log.

### DEFECT-2 — JDT core activated before the Eclipse resources workspace existed

Root cause: `JavaToolingActivator.wire()` loaded JDT classes at bundle start level 4 —
before the workbench opens the Eclipse resources workspace. `JavaCore.start()` then threw
`Workspace is already closed or not ready yet`, killing JDT tooling (and producing startup
errors). In headless (no resources bundle) the same load failed with `SearchRequestor`
NoClassDefFoundError (gracefully degraded, but logged as ERROR).

Fix (two parts, both minimal):
1. `ServiceAvailabilityGate` — new constructor with an additional readiness
   `BooleanSupplier` re-evaluated on every service registration (core API extended,
   existing callers unchanged).
2. `JavaToolingActivator` — gates wiring on `org.eclipse.core.resources.IWorkspace` service
   availability when the resources bundle is present (RCP waits for the workspace; headless
   proceeds and degrades gracefully as before).
3. `DCodeWorkbenchAdvisor.initialize()` — opens the Eclipse resources workspace
   (`ResourcesPlugin.getWorkspace()`) so the IWorkspace service appears in RCP;
   `org.eclipse.core.resources` added to the ui bundle's Require-Bundle.

Verification: in RCP all 24 tools register, including the 13 JDT/PDE tools
(`java_project_structure`, `java_get_diagnostics`, ...). No startup exceptions.

### DEFECT-3 — SearchPanel NPE on view creation

Root cause: `SearchPanel` constructor did
`((GridData) parent.getLayoutData()).exclude = true` but the parent composite had no
layout data → NPE logged on every InferenceWorkspaceView creation.

Fix: `ui.inference/.../SearchPanel.java` — assign a GridData when absent, then set
`exclude`.

Verification: RCP log now has zero `!ENTRY` entries.

### DEFECT-4 — ModelManagementApiTest wiped the live ENR endpoint for later tests

Root cause: `tests/.../server/ModelManagementApiTest.java` set
`dcode.enr.endpoint` for its mocked ENR and `System.clearProperty(...)` in tearDown. The
Tycho suite runs all test classes in one JVM, so `EnrLiveIntegrationTest` (later in the
run) silently lost the user-provided endpoint and skipped.

Fix: save the previous value in setup and restore it in tearDown.

### DEFECT-5 — Live-test system properties never reached the Tycho test JVM

Root cause: `tycho-surefire-plugin` was not configured to forward
`dcode.enr.endpoint` / `dcode.live.model`; `-D...` on the Maven command line was not
propagated to the OSGi test runtime.

Fix: `tests/eu.kalafatic.dcode.tests/pom.xml` — `<properties>` defaults + surefire
`<systemProperties>` forwarding (CLI `-D` overrides work).

### DEFECT-6 — ServiceAvailabilityGate return value contradicted its contract

Root cause: `runIfAvailable()` returned true whenever preconditions held, even when the
work had already run (the Javadoc says "Returns true if it ran now"). All callers ignore
the value, so this was doc/impl inconsistency, not a live bug.

Fix: return true only when the callback actually ran (fire-once preserved).

### IMPROVEMENT — Agent system prompt now lists exact tool argument names

Root cause of live friction: the model twice called `edit_file` with `old_text`/`new_text`
(snake_case) and needed one retry to recover. The system prompt listed tool names and
descriptions but not parameter names.

Fix: `ContextEngine.buildSystemPrompt` appends `args: path, oldText, newText`-style
suffixes parsed from each tool's JSON schema.

Verification (post-fix live run): the model used `edit_file` with correct
`oldText`/`newText` on the first attempt.

## 4. Post-Fix Verification

- Full build: `./mvnw -B verify` → **BUILD SUCCESS**, **197 tests, 0 failures, 0 errors,
  0 skipped** (193 baseline + 4 new regression tests; all skips eliminated).
- Live suites inside the same run:
  - `EnrLiveIntegrationTest` — 2/2 against live ENR (`-Ddcode.enr.endpoint=http://127.0.0.1:8111`).
  - `LiveOllamaTest` — 2/2 including a live completion (`-Ddcode.live.model=smollm:135m`).
- RCP restart on the fixed build: running, 0 log errors, JDT tools registered,
  HTTP server auto-starts (allocated 48081).
- Headless restart on the fixed build: healthy, ENR + Ollama + ENR-Ollama runtimes all
  `reachable=true`.
- Web lifecycle (B4) re-verified: `server-status/start/stop/restart` CLI + REPL behave per
  spec; `webServer.enabled=false` → DISABLED with a clear failure message;
  `webServer.autoStart=false` → STOPPED until started (configured port 48123 honored).
- REPL `exit` terminates the JVM cleanly (no lingering process/port).
- F6 agent run on the final build: read_file → edit_file (correct args, first try) →
  shell compile+run → verified `EDIT2_AFTER`.

## 5. Remaining Defects

None confirmed open within the campaign scope. All six defects above are fixed and
re-verified.

## 6. Known Limitations / Deferred Decisions

1. **Terminal session states are final by design.** `COMPLETED/FAILED/CANCELLED` only
   transition to `ARCHIVED` (`SessionState.isValidTransition`), so a follow-up task in a
   completed session is rejected with `Invalid session transition COMPLETED -> RUNNING`
   and the RCP ChatView surfaces a raw error toast. Multi-turn context within one run and
   in direct chat works; cross-task continuation requires a new session. Changing the
   state machine is a normative architecture change → **deferred for product-owner
   approval** (candidate: allow COMPLETED → RUNNING, or auto-create a continuation
   session in ChatView).
2. **JDT tooling is unavailable in the headless product** (no Eclipse resources bundle/
   workspace). This is documented, graceful degradation; the Java development workflow
   (F3/F4/S5/F6) uses the `shell` tool + JDK and is fully functional. Enabling JDT
   headless would require bundling core.resources + opening a workspace headlessly —
   **deferred**.
3. **No terminal/PTY view in the web UI.** The bottom Console panel is a structured
   event log; the `shell` tool is reachable through the generic tool-invocation modal.
   A dedicated web terminal view is a feature request, not a defect. RCP opens a system
   terminal via `Tools ▸ Open Terminal`.
4. **Machine-default Java is 8.** The DCode launchers must be started with
   `-vm /usr/lib/jvm/java-21-openjdk-amd64/bin/java` (or via `scripts/run-headless.sh`),
   and the `shell` tool sees Java 8 on PATH; agents must use absolute JDK paths
   (documented in the testing guide).
5. **Killing the launcher wrapper does not stop the JVM child** (native launcher spawns
   java and waits). Graceful stop: `dcode-headless stop` (SIGTERMs the port occupant) or
   SIGTERM to the java process. Not a product defect; documented in the testing guide.
6. **`edit_file` requires exact match** (fails on 0 or >1 matches) — by design; the
   prompt now advertises exact argument names to reduce failed calls.

## 7. Modified Files

Source/product:

- `product/eu.kalafatic.dcode.product/dcode.product` — SCR auto-start (DEFECT-1)
- `plugins/eu.kalafatic.dcode.core/src/eu/kalafatic/dcode/core/service/ServiceAvailabilityGate.java` — readiness supplier + return-value fix (DEFECT-2, DEFECT-6)
- `plugins/eu.kalafatic.dcode.java.tooling/src/eu/kalafatic/dcode/java/tooling/internal/JavaToolingActivator.java` — IWorkspace gate (DEFECT-2)
- `plugins/eu.kalafatic.dcode.ui/src/eu/kalafatic/dcode/ui/DCodeWorkbenchAdvisor.java` — open Eclipse workspace (DEFECT-2)
- `plugins/eu.kalafatic.dcode.ui/META-INF/MANIFEST.MF` — core.resources dependency (DEFECT-2)
- `plugins/eu.kalafatic.dcode.ui.inference/src/eu/kalafatic/dcode/inference/ui/SearchPanel.java` — NPE fix (DEFECT-3)
- `plugins/eu.kalafatic.dcode.harness/src/eu/kalafatic/dcode/harness/context/ContextEngine.java` — tool arg names in system prompt (IMPROVEMENT)

Tests/build:

- `tests/eu.kalafatic.dcode.tests/pom.xml` — surefire systemProperties forwarding (DEFECT-5)
- `tests/eu.kalafatic.dcode.tests/src/eu/kalafatic/dcode/tests/server/ModelManagementApiTest.java` — save/restore endpoint (DEFECT-4)
- `tests/eu.kalafatic.dcode.tests/src/eu/kalafatic/dcode/tests/core/ServiceAvailabilityGateTest.java` — new regression test
- `tests/eu.kalafatic.dcode.tests/src/eu/kalafatic/dcode/tests/harness/ContextEnginePromptTest.java` — new regression test
- `tests/eu.kalafatic.dcode.tests/src/eu/kalafatic/dcode/tests/osgi/HeadlessRuntimeWebTest.java` — WebServerControl lifecycle regression test

Documentation:

- `docs/testing/DCODE_INITIAL_TEST_REPORT.md` (this file)
- `docs/testing/DCODE_TESTING_GUIDE.md`

Pre-existing uncommitted work (kept, verified during this campaign): web server lifecycle
control (`core/server/WebServerControl*`, `ServerActivator`, `HeadlessApplication`
commands, `CoreConfiguration.webServerEnabled/webServerAutoStart`,
`DurableConfigurationService`, RCP `WebServerControlAccess` + menu, web UI updates).

Environment state after the iteration: DCode headless running on 48080 (healthy), DCode
RCP running on DISPLAY=:1 (backend 48081), ENR running on 8111, Ollama on 11434. Test
artifacts are confined to `/tmp/dcode-enr-test` and `/tmp/opencode/`; `~/.dcode` was
backed up before testing and left functional.
