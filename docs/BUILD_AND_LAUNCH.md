# DCode Build and Launch Guide

## Verified toolchain

- JDK 21 (`/usr/lib/jvm/java-21-openjdk-amd64`)
- Maven 3.9.12 via the included `./mvnw`
- Linux GTK x86_64

DCode uses Tycho 4.0.8. The machine-wide Maven 3.8.7 cannot resolve
`tycho-extras:4.0.8`, so a Maven wrapper pinned to 3.9.12 is included.

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
```

## Build

```bash
./mvnw -B -DskipTests clean verify
```

Verified result: `BUILD SUCCESS` for all 14 modules.

`scripts/build.sh` performs the same build with JDK 21 and prefers the included wrapper.
Override the Maven executable with `MVN_CMD` if needed.

## Products

| Product | Directory | Launcher |
|---|---|---|
| DCode RCP | `product/eu.kalafatic.dcode.product/target/products/eu.kalafatic.dcode.ui.rcp/linux/gtk/x86_64` | `dcode` |
| DCode Headless | `product/eu.kalafatic.dcode.product/target/products/eu.kalafatic.dcode.server.headless/linux/gtk/x86_64` | `dcode-headless` |

Product identities:

- RCP product/application: `eu.kalafatic.dcode.ui.rcp` / `eu.kalafatic.dcode.ui.application`
- Headless product/application: `eu.kalafatic.dcode.server.headless` /
  `eu.kalafatic.dcode.server.application`

## Launch configurations

Launch files are in `.launches/` and are visible when the repository root project is
imported.
The launches explicitly select this repository's bundles, so they stay isolated when
other projects or repository variants are imported into the same workspace.

| Launch | Purpose |
|---|---|
| `DCode_RCP_Application.launch` | Full DCode RCP product |
| `DCode_Headless_Server.launch` | DCode headless product |
| `DCode_Tests.launch` | PDE JUnit 5 run of `eu.kalafatic.dcode.tests` |
| `DCode_Maven_Build.launch` | `clean verify -DskipTests` with JDK 21 |

## Headless runtime

```bash
scripts/build.sh
scripts/run-headless.sh
```

Or directly:

```bash
cd product/eu.kalafatic.dcode.product/target/products/eu.kalafatic.dcode.server.headless/linux/gtk/x86_64
./dcode-headless -nosplash -consoleLog
```

Verified: the headless runtime starts core services, the native coding harness, and the
DCode HTTP API on `127.0.0.1:58080` (allocated port; family 58xxx block — DStudio owns 48xxx). The process runs until stopped.

## Configuration fix applied

`dcode-headless.product` had no `<configurations>` section. The exported `config.ini`
listed bundles without starting `org.eclipse.core.runtime`, `org.eclipse.equinox.app` or the
SCR extender, so the launcher failed with
`Unable to acquire application service`. A configurations block was added for:

- `org.apache.felix.scr`
- `org.eclipse.core.runtime`
- `org.eclipse.equinox.common`
- `org.eclipse.equinox.event`
- `org.eclipse.equinox.simpleconfigurator`
- `eu.kalafatic.dcode.server`

The exported headless product now starts successfully.

## Test status

```bash
./mvnw -B verify
```

Verified result: 128 tests, 0 failures, 0 errors, 3 skipped.

The test suite includes `WebUiIntegrationTest`, `RcpWorkbenchSmokeTest` and
`CrossClientInteropTest`. Some Aider/OpenCode tests depend on locally installed external
tools.
