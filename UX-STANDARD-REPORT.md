# DCode — Unified RCP / Web UX Standard — Delivery Report

Date: 2026-11-21

## 1. Executive summary

DCode already implemented most of the Family UX Standard (Tools menu with Open
Terminal / Open Web Interface, Help menu with the six standard entries, F1 context
help via a web `help.html`, table-driven headless CLI with `/help [topic]` and
suggestions, and a keyboard-accessible web context menu). This work closes the
remaining gaps: the mandatory `/start` CLI command, session Rename/Delete context
actions in the RCP session views (mirroring the Web UI), a context menu on the
Session Navigator, model context actions in the Model Explorer, Copy Path / Open
Terminal in the Workspace Explorer, and a Help button in the web UI.

## 2. Existing infrastructure reused

- **Single backend as the shared action layer** — RCP, Web and Headless all invoke
  the same REST endpoints (`DCodeApi` / `js/api.js` / `HeadlessApplication`
  `getJson` helpers). No second action registry was introduced.
- `DesktopActions` (`plugins/eu.kalafatic.dcode.ui/.../DesktopActions.java`) —
  terminal/browser launching shared by menu and context menus.
- `Ui.run` / `Ui.async` — the established async/UI-thread pattern.
- `HeadlessApplication` command table — the single dispatch+help source.
- Web context menu (`app.js` `showContextMenu`, keyboard-accessible) — untouched.

## 3. Components modified

| File | Change |
|---|---|
| `plugins/eu.kalafatic.dcode.server/src/.../HeadlessApplication.java` | Added `/start` command (verifies server on the configured port; prints start instructions when nothing is listening). Help topic text and daemon banner now list `start`. |
| `plugins/eu.kalafatic.dcode.server/web/help.html` | CLI table: added `start` row; slash-forms line updated to `/help /status /start /stop /restart /web /terminal /models /sessions /providers /agents /config /logs /exit`. |
| `plugins/eu.kalafatic.dcode.server/web/index.html` | Help button (`#btn-help`, links `/help.html`, opens in new tab). |
| `plugins/eu.kalafatic.dcode.server/web/css/app.css` | `a.btn` styling so the Help link renders like the other topbar buttons. |
| `plugins/eu.kalafatic.dcode.ui/src/.../DesktopActions.java` | Extracted `openTerminalIn(String)` (single terminal implementation) reused by `openTerminal()`. |
| `plugins/eu.kalafatic.dcode.ui/src/.../views/SessionActions.java` | **New**: single implementation of session Open/Rename/Delete used by both session views (same backend ops as Web: `POST /rename`, `DELETE`). |
| `plugins/eu.kalafatic.dcode.ui/src/.../views/SessionsView.java` | Context menu: Open, Rename…, Delete, Cancel execution (RUNNING only). |
| `plugins/eu.kalafatic.dcode.ui/src/.../views/SessionNavigatorView.java` | Context menu added (Open, Rename…, Delete) via `SessionActions`. |
| `plugins/eu.kalafatic.dcode.ui/src/.../views/ModelsView.java` | Context menu: Load, Unload, Pull (same `modelAction` as the toolbar), Set as default, Refresh. |
| `plugins/eu.kalafatic.dcode.ui/src/.../views/WorkspaceExplorerView.java` | `FileNode` now carries a directory flag; context menus: Workspace → Copy Path / Open Terminal; File/Directory → Copy Path; Directory → Open Terminal. Clipboard helper added. |

## 4. New actions

- CLI `/start`.
- RCP context actions: session Rename…/Delete (Sessions + Session Navigator), model
  Set as default, workspace/file Copy Path, Open Terminal in a workspace/directory.
- Web Help button.

## 5. Context menu coverage (RCP)

| Surface | Actions |
|---|---|
| Workspace Explorer (workspace) | Refresh, Copy Path, Open Terminal |
| Workspace Explorer (repository) | Git status, Diff, Commit…, Pull, Push, Java project structure |
| Workspace Explorer (file) | Open, Copy Path, AST summary/Analyze imports (.java), Inspect bundle (MANIFEST.MF), Open Terminal (directories) |
| Sessions | Open, Rename…, Delete, Cancel execution (RUNNING) |
| Session Navigator | Open, Rename…, Delete |
| Chat | Copy, Copy Code |
| Console | Clear, Copy, Save Output… |
| Providers | Test connection, Refresh |
| Models | Load, Unload, Pull, Set as default, Refresh |
| Runtimes | Refresh |
| Runtime Explorer | Test connection, Refresh models, Enable/Disable, Set as default, Edit…, Remove |
| Agent Explorer | Select as default, Configure… |

Not exposed (no backend operation exists — per the standard, unsupported operations
are not invented): Folder Create/Rename/Delete, File Rename/Delete, Session Duplicate,
Provider Connect/Disconnect, Message Retry/Continue.

## 6. Help coverage

- Help menu: Help Contents, Getting Started, Context Help, Keyboard Shortcuts, CLI
  Commands, Diagnostics, About — all served by `web/help.html` anchors.
- F1 (no modifiers) opens the context help anchor for the active view
  (`HelpLauncher.anchorFor`); every main view has an anchor.
- Web: `help.html` reachable via the new Help button (previously only by URL).

## 7. CLI help coverage

- `/help` and `/help <topic>` generated from the single command table (no static
  list). Vocabulary now: `status, runtimes, providers, models, sessions, logs, web,
  terminal, agents, config, start, stop, restart, exit, help`.
- Unknown commands → nearest-match suggestions ("Did you mean").
- `dcode>` interactive REPL with the same table.

## 8. Tests executed

- Full Tycho build: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
  ~/.m2/wrapper/dists/apache-maven-3.9.12/6068d197/bin/mvn -B clean verify`
  → **BUILD SUCCESS** — `Tests run: 184, Failures: 0, Errors: 0, Skipped: 3`.
- Runtime verification (materialized headless product, Java 21 VM):
  - `dcode-headless help` → full command table printed, including `start`.
  - `dcode-headless start` → `[DCode] Nothing is listening on port …` guidance on a
    free port (honest start semantics; the server starts automatically when the
    application runs without a command).

## 9. GUI behavior verified

- RCP GUI (SWT views/menus): **not verified interactively** — this host has no X
  server (`useUIHarness=false` everywhere). Compilation and test coverage only.
- Web UI: context menus/tooltips already covered by existing tests
  (`WebUiIntegrationTest` in the suite above); the Help button is a plain link.

## 10. Remaining limitations

- No Eclipse command framework in DCode: menus/toolbars/context menus are JFace
  actions; the shared action layer is the backend API (all surfaces call the same
  endpoints). The Darwin Family Contextual Action Registry is not present in DCode.
- The headless CLI reads `-Ddcode.port` while the server may bind a fallback port
  when the configured one is occupied (pre-existing; `config` prints both values).
- No context menus in the editors (`DCodeFileEditor`, `DCodeDiffEditor`) and in a
  few auxiliary views (Connection, Activity, Changes, Java Tools, Port Monitor).
- `DCodePreferencePage` remains unregistered (dormant, pre-existing).
