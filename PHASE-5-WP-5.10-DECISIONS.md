# PHASE 5 — WP5.10 DECISIONS

**Date:** 2026-11-16

## D-5.10-1 — RCP consumes the platform over HTTP, not in-process services

The workbench uses `DCodeApiClient` (HTTP) against the local embedded server or a
remote endpoint — the same contract as the web UI. This guarantees one authoritative
backend, makes local/remote mode identical, and structurally prevents duplicate
service instances in the UI.

## D-5.10-2 — Client bundle with typed core-model mapping

`eu.kalafatic.dcode.client` maps API JSON onto the core domain records (Session,
Workspace, RepositoryRef) so clients reuse the shared domain model rather than
maintaining client-side copies. Jackson is exported once from this bundle (single
classloader identity for all JVM clients).

## D-5.10-3 — Minimal additive API for RCP needs

Git operations (status/diff/commit/pull/push) and the tool-call audit trail
(`GET /sessions/{id}/toolcalls`) were added as minimal endpoints reusing
`RepositoryOperations` (policy-enforced) and the existing `ToolEngine` records.
No new services were introduced.

## D-5.10-4 — Source editor on JFace text infrastructure, JDT editor deferred

The DCode source editor uses `SourceViewer` + line-number ruler + a Java syntax
scanner; full `CompilationUnitEditor` embedding (content assist/quick fixes) is
deferred with the integration point documented (shared IJavaProjects in local
mode). Not claimed as implemented.

## D-5.10-5 — Rename confirmation

`java_rename` in the RCP is presented with an explicit mutation warning dialog
(target kind, file, new name) before execution; the backend READ_ONLY gate and
session state machine remain the enforcement points.

## D-5.10-6 — Product assembly

Feature + eclipse-repository product module materialize the RCP and headless
products (launchers verified). The RCP product's E4 window shell rendering issue
in this environment is recorded as a known limitation with exact evidence — not
claimed as working.

## D-5.10-7 — Workbench test strategy

The workbench smoke test asserts the deterministic artifacts (workbench creation,
window advisor execution via probe, perspective + all view registrations, clean
shutdown) instead of window-shell rendering, which the in-process harness does not
reliably observe.
