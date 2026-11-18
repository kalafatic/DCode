# PHASE 5 — WP5.6 IMPLEMENTATION REPORT: Native Eclipse JDT/PDE Java Development Tooling

**Date:** 2026-11-16
**Status:** Implemented and verified. `mvn -B clean verify` green (run twice for
determinism): **94 tests, 0 failures, 0 errors** (1 skipped: opt-in live model test).
**Repository:** DCode only. EVO and TRYST were not modified. Nothing was committed.

---

## 1. Architecture

New bundle **`eu.kalafatic.dcode.java.tooling`** implements the DCode language-tooling
role entirely on the native Eclipse stack. It plugs into the existing harness
architecture without any parallel analysis engine:

```
DCode repositories (JGit) ──► JavaWorkspaceManager (headless Eclipse workspace)
                                  │  ResourcesPlugin + JDT Core + corext + LTK
                                  │  import → linked IProject + IJavaProject
                                  │  classpath: JRT + sources + Maven deps + reactor
                                  ▼
                          shared JDT semantic services
   ┌──────────────┬──────────────┼──────────────┬───────────────┐
   │ AST (bindings)│ Java model    │ Search index  │ Batch compiler │
   └──────────────┴──────────────┴──────────────┴───────────────┘
                                  │ 13 tools (ToolRegistry)
                                  ▼
                        native DCode agent loop (ToolEngine)
```

- **One headless Eclipse workspace per runtime** (per manager instance); Java projects
  discovered in DCode repositories are imported as linked Eclipse projects (location
  points at the real module directory — never copies).
- **Reactor fidelity:** sibling Maven modules participate as JDT **project entries**
  (`CPE_PROJECT`), so the shared source index resolves cross-module references the way
  a reactor build does.
- **Classpath resolution:** running-JDK image (`jrt-fs.jar`, no `jdt.launching`
  dependency) + module source dirs + Maven dependencies (`mvn dependency:build-classpath`,
  120 s bound, pom-based local-repo fallback) + reactor project entries.
- **No SWT, no Workbench, no display** anywhere on the tooling path — verified by
  headless execution through the ToolEngine in the surefire runtime.

### Key design points

| Concern | Solution |
|---|---|
| Headless JDT | `ResourcesPlugin` started explicitly; linked projects with JDT nature + builder |
| Structured results | every tool returns JSON (`ToolResult.output`); errors as `{error, success:false}` — never exceptions into the agent loop |
| READ_ONLY | `java_rename` enforces the repository access policy; all other tools are read-only |
| Audit | tools run through the existing `ToolEngine` (ToolCall records + events) |
| Service unavailability | `ToolingException` with clear message; tools fail structured, the harness never crashes |
| Workspace reuse | projects are adopted by location (across manager instances/restarts); stale locations are re-imported; unique instance suffix avoids JDT name-cache collisions |

## 2. Implemented tools (13)

| Tool | Scope | Mutating | What it does |
|---|---|---|---|
| `java_project_structure` | REPOSITORY | no | discovery (Maven modules / Eclipse .classpath projects / OSGi bundles) + sources + classpath |
| `java_get_diagnostics` | REPOSITORY | no | Eclipse batch-compiler errors/warnings with real dependency checking |
| `java_ast_summary` | REPOSITORY | no | AST with bindings: types, supertypes, methods, fields, imports, call sites |
| `java_resolve_type` | REPOSITORY | no | type kind, visibility, superclass, interfaces, source location, methods |
| `java_type_hierarchy` | REPOSITORY | no | all supertypes + all subtypes (ITypeHierarchy) |
| `java_call_hierarchy` | REPOSITORY | no | callers via workspace-scope JDT search |
| `java_find_references` | REPOSITORY | no | reference search (types + methods) via SearchEngine |
| `java_find_implementations` | REPOSITORY | no | implementors of a type or interface method |
| `java_analyze_imports` | REPOSITORY | no | import resolution + usage counts (unused/unresolved reported) |
| `java_analyze_dependencies` | REPOSITORY | no | pom dependencies + reactor modules + OSGi manifest imports/exports |
| `java_rename` | REPOSITORY | **yes** | LTK rename of type/method/field with reference updates + post-rename diagnostics |
| `pde_inspect_bundle` | REPOSITORY | no | MANIFEST headers, Require-Bundle, Import/Export-Package, extension points/extensions |
| `pde_analyze_dependencies` | WORKSPACE | no | cross-bundle package resolution across all workspace repos; unresolved imports |

## 3. Eclipse dependencies

Added to the target platform (Eclipse 2024-03, sequenceNumber 7):

- `org.eclipse.jdt.core` 3.37.0 (+ transitive `jdt.core.compiler.batch` in-bundle)
- `org.eclipse.jdt.core.manipulation` 1.21.0 (rename processors; call hierarchy not used — replaced by workspace-scoped search)
- `org.eclipse.jdt.launching` (JRE container support for `.classpath` projects)
- `org.eclipse.ltk.core.refactoring` 3.14.300 (headless refactoring execution)
- `org.eclipse.core.resources`, `org.eclipse.core.filebuffers`, `org.eclipse.text`
- `org.eclipse.core.runtime`, `org.eclipse.equinox.common`, `org.eclipse.core.jobs` (direct Require-Bundle)

JSON: bundled Jackson 2.20 (family convention).

## 4. OSGi integration

- `JavaToolingActivator` registers all 13 tools in the existing harness `ToolRegistry`
  via the retry-safe `ServiceAvailabilityGate` (requires `ToolRegistry` +
  `DCodeLifecycle`; bundle start order irrelevant).
- Lifecycle: `TOOLING` stage action registered; `startThrough(TOOLING)` advances the
  shared runtime lifecycle.
- No new registries, no new event mechanisms; tool execution flows through `ToolEngine`
  (audit + events), same as every built-in tool.

## 5. Test evidence (94 tests, 0 failures — twice)

Real Java test projects on disk (no mocks): a 3-module Maven repo (`core` interface +
implementation, `app` caller + broken file, `invalid` syntax-broken module), an Eclipse
`.classpath` project, and 3 OSGi bundles (api with extension point / impl requiring it /
broken with an unresolved import).

| Requirement | Test | Evidence |
|---|---|---|
| Java project discovery | `JavaProjectDiscoveryAndDiagnosticsTest.discoversMavenEclipseAndOsgiProjects`, `kindDetectionIsCorrect` | 3 Maven + 1 Eclipse + 3 OSGi modules found with correct kinds |
| Compilation diagnostics | `healthyModuleCompilesWithoutErrors`, `missingDependencyIsReported`, `invalidJavaSourceIsReportedStructured` | 0 errors healthy; real "cannot be resolved" errors for the broken module; syntax errors structured |
| AST parsing | `astSummaryResolvesBindings` | resolved bindings: `com.example.core.GreetingImpl`, superclass `java.lang.Object`, interface `com.example.core.Greeting`, resolved method signatures |
| Type resolution | `resolveTypeReportsStructure` | kind/visibility/superclass/interfaces/methods from the Java model |
| Type hierarchy | `typeHierarchyFindsSubtypes` | `GreetingImpl` found as subtype of `Greeting` |
| Call hierarchy | `callHierarchyFindsCallerOfInterfaceMethod` | `App.main` found as caller across modules |
| Reference search | `referenceSearchFindsUsage` | references in `GreetingImpl.java` and `App.java` |
| Implementation search | `implementationSearchFindsImplementors` | `GreetingImpl` implementor found |
| Import analysis | `astSummaryResolvesBindings` (imports section) | resolved/unused/unresolved imports |
| Dependency analysis | `java_analyze_dependencies` via fixtures + `osgiDependencyResolutionAcrossWorkspace` | pom deps + reactor modules; cross-bundle package resolution (impl→api resolved, broken bundle unresolved) |
| PDE inspection | `pdeInspectBundleReportsManifestAndPluginXml`, `pdeInspectBundleReportsExtensions` | symbolic name/version/exports/extension-points/extensions |
| Refactoring | `renameRefactoringUpdatesReferencesAndVerifies` | LTK type rename: file renamed, reference in `App.java` updated, post-rename diagnostics clean |
| Invalid Java source | `invalidJavaSourceIsReportedStructured` | structured ERROR, no crash |
| Missing dependencies | `missingDependencyIsReported` | real unresolved-type diagnostics |
| READ_ONLY | `readOnlyRepositoryRejectsRename` | structured rejection, file untouched |
| Headless (no SWT) | `headlessExecutionThroughToolEngine` + all of the above in the surefire runtime | full tool set executes without any UI bundle on the path |
| Workspace lifecycle | `WorkspaceManagerReuseTest` | repeated scans reuse handles; cross-manager location adoption works |

## 6. Build results

```
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
MVN=~/.m2/wrapper/dists/apache-maven-3.9.12/6068d197/bin/mvn
$MVN -B clean verify        → BUILD SUCCESS
```

Modules: core, repositories, config, harness, java.tooling, persistence, server, tests.
Tests: 94 run, 0 failures, 0 errors, 1 skipped (live-model opt-in). Verified twice.

## 7. Known limitations (recorded honestly)

1. **Method/field rename access:** `RenameMethodProcessor`/`RenameFieldProcessor`
   constructors are protected (internal corext API, stable across JDT versions); accessed
   via small subclasses. `checkFinalConditions` requires a real
   `CheckConditionsContext` with `ResourceChangeChecker` + `ValidateEditChecker`
   (headless LTK requirement, handled).
2. **Maven classpath via CLI:** `dependency:build-classpath` requires a working Maven
   installation; on failure the pom-based local-repo fallback applies (and reactor
   project entries still provide cross-module resolution). No m2e dependency.
3. **Call hierarchy is search-based** (workspace scope), not the corext tree —
   deliberately: corext `CallHierarchyCore` is project-scoped and missed cross-module
   callers.
4. **Refactoring catalogue:** `java_rename` is the only mutating tooling operation in
   WP5.6; extract-method/move/organize-imports are planned follow-ups over the same
   LTK infrastructure.
5. **Index freshness:** after external (non-tool) file edits a re-scan/rebuild is
   required; tools trigger it on demand via `scanRepository`.
6. **OSGi classpath:** OSGi projects import with `src/` + JDK; full PDE target-platform
   resolution is not part of WP5.6 (manifest-level dependency analysis is).
7. Two manager instances in one JVM importing the same module directory adopt the same
   Eclipse project (documented semantics; matches product behavior on runtime reuse).
