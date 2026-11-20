# DCode — Build Guide

Authoritative parent: `/home/petr/git/DCode-121126` (root `pom.xml`).

Prerequisites: JDK 21, `./mvnw` (Maven 3.9.12), network for `https://download.eclipse.org/releases/2024-03`.

| Operation | Command (from repo root) | Output | Exit |
|---|---|---|---|
| Full Build | `./mvnw -B -DskipTests clean verify` | 13 modules; products in `product/eu.kalafatic.dcode.product/target/products/` | 0 |
| Clean Build | same | — | 0 |
| Build Selected Module | `./mvnw -B -DskipTests clean verify -pl <module> -am` | module + deps | 0 |
| Test | `./mvnw -B verify` | 128 tests | 0 |
| Validate | `./mvnw -B validate` | fast | 0 |
| Package | `./mvnw -B -DskipTests package` | bundles + product assemblies | 0 |

Environment: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`.

## Verified results (2026-11-19)
- Full reactor build: **BUILD SUCCESS**; tests 128/128 green (3 skipped).
- UPM: `validate` SUCCESS, full `build` SUCCESS, `test` SUCCESS (127 tests reported via UPM op).
