# PHASE 5 — WP5.10 CROSS-CLIENT TEST REPORT

**Date:** 2026-11-16
**Principle:** ONE PLATFORM — THREE ACCESS MODES. RCP and Web share one backend,
one session model, one persistence layer.

---

## 1. Test setup

One authoritative DCode backend (durable SQLite persistence via the WP5.7 services,
HTTP server) with **two independent API clients** — client A plays the Web client,
client B plays the RCP client (`eu.kalafatic.dcode.client`, the exact class the
workbench uses). Scenario E additionally performs a real server restart over the
same SQLite database with both clients reconnecting to the new endpoint.

## 2. Scenario matrix

| # | Scenario | Verified |
|---|---|---|
| A | Web creates session → RCP opens it (same id, title, state) | ✅ |
| B | RCP continues session (task submission) → Web observes | ⚠ backend-verified (task execution + SSE delivery tested in WP5.9/5.10); live-agent continuation requires a working local model — environment limitation |
| C | RCP creates session → Web opens it + lists it | ✅ |
| D | Web continues → RCP observes | ⚠ same as B |
| E | Server restarts → both clients reconnect to persisted sessions + repository associations | ✅ (real SQLite restart, new server instance, both clients) |
| F | Multiple repositories → consistent associations + READ_ONLY policy in both clients | ✅ |
| G | Java tooling → same operations through both clients | ✅ (identical structured results) |

## 3. Consistency invariants verified

- Session identity/state read from the same persistence in both clients.
- Workspace/repository registries identical (incl. access policy).
- Tool executions are audited on the backend; both clients observe the same
  ToolEngine records.
- SSE events carry monotonic ids; both client stacks dedupe identically.

## 4. Conclusion

Cross-client session interoperability is functionally verified for the persistent
backing store and both client stacks. Scenario B/D depend on a working local LLM
(environment limitation, not architecture) and are covered at every layer except
the final model inference.
