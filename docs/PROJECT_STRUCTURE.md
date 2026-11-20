# DCode — Project Structure

Authoritative parent/aggregator: **`pom.xml` (repository root)** — `eu.kalafatic.dcode.parent` 2.6.11-SNAPSHOT

| Layer | Location | Notes |
|---|---|---|
| Repository root | `/home/petr/git/DCode-121126` | Git root; parent POM at root |
| Build system | Maven + Tycho 4.0.8, `./mvnw` | Java 21 |
| Target platform | Eclipse 2024-03 SimRel | `targetplatform/eu.kalafatic.dcode.target/eu.kalafatic.dcode.target.target` |
| Feature | `features/eu.kalafatic.dcode.feature/feature.xml` | |
| Products | `product/eu.kalafatic.dcode.product/{dcode.product, dcode-headless.product}` | GUI RCP + headless server |
| Launch configs | `.launches/` (4 files) | DCode_RCP_Application, DCode_Headless_Server, DCode_Tests, DCode_Maven_Build |

## Reactor modules (13)

| Module | Role |
|---|---|
| targetplatform/eu.kalafatic.dcode.target | Target definition |
| plugins/eu.kalafatic.dcode.core | Core API |
| plugins/eu.kalafatic.dcode.repositories | JGit repository service |
| plugins/eu.kalafatic.dcode.config | Configuration (embedded jackson 2.20) |
| plugins/eu.kalafatic.dcode.client | Client (embeds + exports jackson packages) |
| plugins/eu.kalafatic.dcode.harness | Coding harness/execution |
| plugins/eu.kalafatic.dcode.java.tooling | JDT-based Java tooling (rename/search/refactoring) |
| plugins/eu.kalafatic.dcode.persistence | SQLite persistence (embedded sqlite-jdbc, slf4j) |
| plugins/eu.kalafatic.dcode.server | Headless HTTP server (embedded nanohttpd, jackson) |
| plugins/eu.kalafatic.dcode.ui | RCP UI |
| features/eu.kalafatic.dcode.feature | Feature |
| product/eu.kalafatic.dcode.product | Products (ui.rcp + server.headless) |
| tests/eu.kalafatic.dcode.tests | Test bundle (JUnit 5) |

Scripts: `scripts/build.sh`, `scripts/run-headless.sh`.
