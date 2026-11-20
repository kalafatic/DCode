# DCode — Module Dependencies

```
targetplatform/eu.kalafatic.dcode.target
plugins/eu.kalafatic.dcode.core
plugins/eu.kalafatic.dcode.repositories  → core; org.eclipse.jgit
plugins/eu.kalafatic.dcode.config        → core; embedded jackson 2.20 (Bundle-ClassPath)
plugins/eu.kalafatic.dcode.client        → core; embeds + EXPORTS com.fasterxml.jackson.*
plugins/eu.kalafatic.dcode.harness       → core, config, repositories; embedded jackson
plugins/eu.kalafatic.dcode.java.tooling  → core, harness, repositories; org.eclipse.jdt.core(+manipulation), ltk.refactoring, core.resources/filebuffers/text
plugins/eu.kalafatic.dcode.persistence   → core; embedded sqlite-jdbc, slf4j-api, jackson
plugins/eu.kalafatic.dcode.server        → core, config, repositories, harness, persistence; embedded nanohttpd 2.3.1, jackson
plugins/eu.kalafatic.dcode.ui            → core, client; platform UI + jface.text
features/eu.kalafatic.dcode.feature      → all plugins
product/eu.kalafatic.dcode.product       → feature (2 products: dcode.product, dcode-headless.product)
tests/eu.kalafatic.dcode.tests           → all plugins; junit-jupiter [5.10.2,6.0.0), org.opentest4j, jgit, jdt.core
```

Notes: each jackson-using bundle embeds its own copy of jackson-annotations/core/databind 2.20; `client` additionally exports the jackson packages for `ui`/`tests`.
