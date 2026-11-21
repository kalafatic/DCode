# DCode — Testing Guide

Reproducible instructions for starting DCode in each mode, connecting to ENR (native and
Ollama-compatible protocols), selecting models, running the AI Commander workflow,
terminal functionality, Java scenarios and regression tests.

All commands assume Linux with JDK 21 at `/usr/lib/jvm/java-21-openjdk-amd64` (the machine
default Java is 8 — DCode must be started with `-vm`), Maven wrapper `./mvnw` in the DCode
repo root, and ENR built in `/home/petr/git/evo-native-runtime-161126`.

## 1. Build

```bash
cd /home/petr/git/DCode-121126
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw -B verify                                   # full suite (hermetic)
./mvnw -B verify \
  -Ddcode.enr.endpoint=http://127.0.0.1:8111 \
  -Ddcode.live.model=smollm:135m                   # + live ENR/Ollama suites
```

Expected: BUILD SUCCESS, 197 tests, 0 failures, 0 skipped (live endpoints up).

Products:

- Headless: `product/eu.kalafatic.dcode.product/target/products/eu.kalafatic.dcode.server.headless/linux/gtk/x86_64/dcode-headless`
- RCP: `product/eu.kalafatic.dcode.product/target/products/eu.kalafatic.dcode.ui.rcp/linux/gtk/x86_64/dcode`

## 2. Start ENR

```bash
cd /home/petr/git/evo-native-runtime-161126
./enr.sh serve -c enr.yaml        # foreground; or nohup ./enr.sh serve ... &
```

ENR surfaces:

- `http://127.0.0.1:8111/` — Web Control Center
- `http://127.0.0.1:8111/enr/v1/*` — native family surface (DCode `EnrProvider` consumes this)
- `http://127.0.0.1:8111/api` — Ollama-compatible API
- `http://127.0.0.1:8111/v1` — OpenAI-compatible API

Models configured in `enr.yaml` (`runtime.model_directories`). ENR auto-loads the test
models `evo` and `tiny-evo` from `enr-model-loader/src/test/resources/models` — good for
protocol tests; real agent work uses Ollama models (below).

## 3. Start DCode — Headless mode (B1)

```bash
cd product/eu.kalafatic.dcode.product/target/products/eu.kalafatic.dcode.server.headless/linux/gtk/x86_64
./dcode-headless -vm /usr/lib/jvm/java-21-openjdk-amd64/bin/java -nosplash -consoleLog
# or daemonized:
nohup ./dcode-headless -vm /usr/lib/jvm/java-21-openjdk-amd64/bin/java -nosplash --launcher.suppressErrors &
```

- HTTP API: `http://127.0.0.1:48080/api/v1/*` (port from `dcode-config.json httpPort`,
  else PortAllocator from 48080).
- Interactive REPL: run without redirection on a terminal → `dcode>` prompt.
- Health: `curl http://127.0.0.1:48080/api/v1/health`
- Stop: `./dcode-headless ... stop` (SIGTERMs the process bound to the port) or SIGTERM
  to the java process. NOTE: killing the launcher wrapper alone does not stop the JVM
  child.

REPL commands: `status runtimes providers models sessions logs web terminal agents config
start stop restart server-status server-start server-stop server-restart exit help`.

## 4. Start DCode — RCP mode (B3)

```bash
cd product/eu.kalafatic.dcode.product/target/products/eu.kalafatic.dcode.ui.rcp/linux/gtk/x86_64
DISPLAY=:1 ./dcode -vm /usr/lib/jvm/java-21-openjdk-amd64/bin/java -consoleLog
```

The RCP embeds the same server bundle; when 48080 is taken it binds the next free port
(verified: 48081) and publishes `dcode.server.port`. The UI connects to the local backend
automatically (RuntimeConnection). Web interface lifecycle: `Tools ▸ Web Interface ▸
Start/Stop/Restart/Status/Open` (drives the shared `WebServerControl` service).

## 5. Web server lifecycle (B4)

Config keys in `<dataDir>/dcode-config.json` (default `~/.dcode/dcode-config.json`):

- `webServer.enabled` — false disables the web/API server entirely (state DISABLED).
- `webServer.autoStart` — false leaves the server STOPPED at startup; start it via the
  REPL `server-start`, the RCP menu, or `headless` CLI.

```bash
./dcode-headless ... server-status     # STOPPED / RUNNING / DISABLED / FAILED + URL
./dcode-headless ... server-start      # fails cleanly when enabled=false
./dcode-headless ... server-stop
./dcode-headless ... server-restart
```

Override the data dir with `-vmargs -Ddcode.home=<dir>` (must come after `-vmargs`, not
as a program argument).

## 6. Connecting to ENR and Ollama (Phases D/E)

Runtime registrations live in `~/.dcode/runtimes.json`; both UIs and the API manage them.

- Native ENR: `POST /api/v1/runtimes` `{"type":"enr","endpoint":"http://127.0.0.1:8111"}`
  (or leave the auto-seeded default `enr` registration).
- Ollama: `{"type":"ollama","endpoint":"http://127.0.0.1:11434"}` (default `ollama`).
- ENR through the standard Ollama protocol: `{"type":"ollama","endpoint":"http://127.0.0.1:8111"}`
  (verified as `enr-ollama`).

Protocol selection is deterministic — the runtime id you target decides the adapter;
there is no silent protocol fallback. Model selection:

```bash
curl http://127.0.0.1:48080/api/v1/runtimes/enr/models          # native ENR discovery
curl http://127.0.0.1:48080/api/v1/runtimes/ollama/models       # /api/tags
curl http://127.0.0.1:48080/api/v1/runtimes/enr-ollama/models   # /api/tags via ENR
```

Direct inference (non-streaming / streaming SSE):

```bash
curl -X POST http://127.0.0.1:48080/api/v1/inference/chat \
  -H 'Content-Type: application/json' \
  -d '{"runtimeId":"enr","model":"tiny-evo","messages":[{"role":"user","content":"hello"}]}'

curl -N -X POST http://127.0.0.1:48080/api/v1/inference/chat/stream \
  -H 'Content-Type: application/json' \
  -d '{"runtimeId":"ollama","model":"qwen3.8-32k:latest","messages":[{"role":"user","content":"hi"}]}'
```

Stream frames: `event: start` → `data: {"token": ...}` frames → `event: done` (content,
finishReason, token counts) or `event: error`. Cancel with
`POST /api/v1/inference/cancel {"requestId": ...}`.

## 7. AI Commander workflow (Phase C/F/G)

1. Create a workspace: `POST /api/v1/workspaces {"name": "...", "rootPath": "/tmp/<ws>"}`
   (plain directory; no Git required).
2. Create a session:
   `POST /api/v1/sessions {"title", "workspaceId", "providerId": "ollama",
   "modelId": "qwen3.8-32k:latest"}` (agent role MAIN by default; other agents via
   `/api/v1/agents`).
3. Start a task: `POST /api/v1/sessions/{id}/start {"task": "..."}` — runs async on the
   backend; watch `GET /api/v1/sessions/{id}` (`state`), `.../messages`, `.../toolcalls`,
   and SSE `GET /api/v1/events/stream?sessionId={id}`.
4. Terminal/console: tool results (incl. shell output) arrive in messages and toolcalls;
   the RCP Console view shows structured logs; web bottom panel shows the event console.

The agent protocol: the model must answer with exactly one JSON object per response —
`{"tool": "<name>", "arguments": {...}}` or `{"final": "<summary>"}`. The system prompt
lists the tool catalog with exact argument names.

## 8. Terminal and Java scenarios (Phase F)

Through the shell tool (`POST /api/v1/tools/shell/execute {"sessionId", "command"}`), all
execution happens inside the workspace root, as the DCode user:

```bash
curl -X POST http://127.0.0.1:48080/api/v1/tools/shell/execute \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"<sid>","command":"pwd && whoami && uname -a"}'
```

Known environment facts (agents must be told): machine `java` on PATH is 8;
use `/usr/lib/jvm/java-21-openjdk-amd64/bin/{javac,java}` for Java scenarios; `mvn` is
3.8.7 on Java 8 (use DCode's `./mvnw` with JAVA_HOME for Maven work).

Canonical Java scenario (F3, verified end-to-end):

> "Create a minimal Java application that prints DCODE_ENR_INTEGRATION_OK. Compile it
> using the available JDK, execute it, capture the output and verify the exact expected
> result. Use the shell tool. Note: the machine default java is 8; JDK 21 is at
> /usr/lib/jvm/java-21-openjdk-amd64 if needed. Do not use git."

Expected: write_file → shell (javac + java + string comparison) → final summary with the
verification result. Verified model: `qwen3.8-32k:latest` (any tools-capable
instruction-following model works; the loop parses plain JSON).

## 9. Regression tests

Full suite:

```bash
./mvnw -B verify                                  # hermetic
./mvnw -B verify -Ddcode.enr.endpoint=http://127.0.0.1:8111 \
                 -Ddcode.live.model=smollm:135m   # live ENR + Ollama included
```

New/changed regression coverage from this iteration:

- `core/ServiceAvailabilityGateTest` — readiness-supplier gating (DEFECT-2/6).
- `harness/ContextEnginePromptTest` — tool argument names in the system prompt.
- `osgi/HeadlessRuntimeWebTest.webServerControlServiceDrivesTheHttpServer` — B4 lifecycle.
- `server/ModelManagementApiTest` — must restore `dcode.enr.endpoint` (DEFECT-4).
- Live suites `EnrLiveIntegrationTest` / `LiveOllamaTest` now receive their endpoints via
  surefire `<systemProperties>` (DEFECT-5).

## 10. Troubleshooting

- `Unable to acquire application service` / launcher fails: wrong JDK — pass
  `-vm /usr/lib/jvm/java-21-openjdk-amd64/bin/java` (product requires JavaSE 17+).
- Port already bound: PortAllocator falls back within 48080–48580; check occupants with
  `ss -tlnp | grep 4808` and use `dcode-headless stop`.
- JDT tools missing in headless: expected (no Eclipse resources workspace) — see report
  §6. In RCP they register after the workbench opens the workspace.
- `Invalid session transition COMPLETED -> RUNNING`: completed sessions are terminal by
  design; create a new session for follow-up tasks.
- Live tests skipped: ensure ENR/Ollama are reachable and pass the two `-D` properties;
  the suite reports per-class results under
  `tests/eu.kalafatic.dcode.tests/target/surefire-reports/`.
