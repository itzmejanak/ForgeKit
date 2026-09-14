# ForgeKit — Project Structure Specification

| Field | Value |
|---|---|
| Document | `STRUCTURE.md` |
| Status | Current repository map — 2026-09-14 |
| Authoritative architecture | `Docs/ARCHITECTURE.md` (read first) |
| Release process | `Docs/RELEASING.md` |
| Audience | Every developer and AI agent writing code in this repository |
| Scope | Physical repository layout: directories, Gradle modules, packages, file placement, naming, dependency edges, build order |

**Read order for any coding task:** `Docs/ARCHITECTURE.md` → `STRUCTURE.md` → `Docs/RULES.md` → the relevant spec in `Docs/` and `Docs/developer/`.

---

## Table of Contents

- §0 — How to use this document
- §1 — Purpose
- §2 — Architecture → repository mapping
- §3 — Canonical repository tree
- §4 — Gradle module registry
- §5 — Module kinds and build targets
- §6 — Dependency rules
- §7 — Package and naming conventions
- §8 — Contract inventory (where things live)
- §9 — Module-internal layout and file conventions
- §10 — Testing conventions
- §11 — Runtime filesystem contract
- §12 — Documentation and ADRs
- §13 — Termux upstream and third-party licensing
- §14 — Plugin SDK and plugin examples
- §15 — Command-line tools
- §16 — Implementation stages (module activation order)
- §17 — Acceptance tests map
- §18 — Agent working rules
- §19 — Change control and reconciliation notes
- §20 — Glossary and version constants

---

## §0 — How to use this document

1. This file is the **single source of truth** for where files live in the repository. `Docs/ARCHITECTURE.md` answers *what* and *why*; this file answers *where* and *how*.
2. **Every file you create must be placed according to §4–§9.** If a location is not defined, do not invent one — follow the change control rules in §19.
3. **Dependency boundaries are law (§6).** Never create: UI → `ProcessBuilder`, plugin → Kotlin internals, plugin → database implementation, UI → Termux private classes.
4. **Implementation follows the stage order in §16.** Never build later-stage UI around earlier-stage mocked behavior. Real functionality first.
5. Anything that violates `Docs/ARCHITECTURE.md` is automatically invalid, even if this document is technically silent on the point.

## §1 — Purpose

ForgeKit is an Android application platform (Kotlin + Jetpack Compose) with an **embedded Termux-derived runtime**. ForgeKit owns orchestration, UX, policy, packages, permissions and lifecycle; the embedded runtime owns Linux/POSIX execution, packages and language runtimes.

This document freezes the **physical project structure** so that multiple agents (human and AI) produce consistent code:

- same module for the same concern
- same package for the same boundary
- same name for the same concept
- same file in the same place every time

The architecture deliberately evolves. The **structure may evolve too**, but only through the process in §19 — never silently per-file.

## §2 — Architecture → repository mapping

| Architecture layer (from `ARCHITECTURE.md`) | Physical location |
|---|---|
| Jetpack Compose UI | `ui/*` — design system in `ui/design`, screens in `ui/<feature>` |
| Application layer (Plugin Manager / Jobs / Permissions / Files) | `plugins/manager`, `jobs/*`, `core/security`, `platform/permissions`, `core/filesystem`; composition and cross-domain use cases in `app` |
| Plugin Platform | `plugins/*` |
| Runtime Bridge | `runtime/api`, `runtime/bridge` |
| Embedded Termux Runtime | `termux/embedded`, `runtime/termux`, `runtime/bootstrap`, `termux/bootstrap` |
| Android infrastructure | `platform/*`, `app` |
| ForgeKit metadata database (Room) | `core/database`, `jobs/persistence` |
| Termux upstream integration | `termux/*` |
| Plugin tooling / developer guide / examples | `tools/*`, `Docs/developer/*`, `plugin-examples/*` |
| ForgeKit-owned filesystem | See §11 (logical layout) |

## §3 — Canonical repository tree

```
ForgeKit2/
│
├── STRUCTURE.md                  ← this document
├── README.md                     ← project entry point
│
├── app/                          → :app            (Android APK; composition root)
│
├── core/                         → :core:*         (pure Kotlin libraries)
│   ├── common/
│   ├── model/
│   ├── database/
│   ├── logging/
│   ├── security/
│   └── filesystem/
│
├── platform/                     → :platform:*     (Android-facing infrastructure)
│   ├── android/
│   ├── notifications/
│   ├── storage/
│   ├── permissions/
│   └── lifecycle/
│
├── runtime/                      → :runtime:*      (runtime abstraction + Termux impl)
│   ├── api/
│   ├── bridge/
│   ├── bootstrap/
│   └── termux/
│
├── plugins/                      → :plugin:*       (plugin platform)
│   ├── api/
│   ├── manifest/
│   ├── installer/
│   ├── validator/
│   ├── resolver/
│   ├── protocol/
│   ├── manager/
│   └── registry/
│
├── jobs/                         → :job:*          (job system)
│   ├── api/
│   ├── scheduler/                (target — see §4)
│   ├── execution/                (target — see §4)
│   ├── manager/
│   └── persistence/
│
├── ui/                           → :ui:*           (Jetpack Compose)
│   ├── design/
│   ├── navigation/
│   ├── home/
│   ├── plugins/
│   ├── jobs/
│   ├── terminal/
│   └── settings/
│
├── termux/                       → :termux:embedded + non-Gradle areas
│   ├── upstream/                 (vendored upstream source; never edited directly)
│   ├── patches/                  (diff patches on upstream)
│   ├── bootstrap/                (architecture-specific bootstrap archives + build scripts)
│   └── embedded/                 → :termux:embedded (integration harness)
│
├── plugin-examples/              (real .forge package sources)
│   ├── hello/
│   ├── interactive/
│   └── ssl-patcher/
│
├── tools/                        (CLI tools; JVM)
│   ├── forge-validator/
│   ├── forge-builder/
│   ├── forge-test/                (planned; README only, no implementation)
│   └── release/                   (release-signing helper)
│
├── tests/                        (test aggregation)
│   └── integration/              → :tests:integration
│
├── docs/                         (target — see §12; current docs live in Docs/)
│   └── adr/
│
├── third-party/                  (licensing and provenance registry)
│   ├── THIRD_PARTY_NOTICES.md
│   ├── licenses/
│   └── termux-bootstrap/
│
└── Docs/                         (current: ARCHITECTURE.md, specs, RELEASING.md; migration → docs/ per §12)
```

Rules that apply to the tree:

1. **Directory name is the physical location; Gradle module name is the logical identity.** They are related by the table in §4.
2. `STRUCTURE.md` sits at the repository root — the first document an implementing agent reads after `Docs/ARCHITECTURE.md`.
3. Non-Gradle areas (`termux/upstream`, `termux/patches`, `termux/bootstrap`, `Docs/developer`, `plugin-examples`, `tools/release`, `third-party`) are part of the repository and **still follow §6–§10** wherever they apply.
4. Do not scatter plugin data, runtime files, or build outputs outside the locations defined in §11 and §4.
5. Build outputs, `.forge` artifacts, and editor/IDE files must stay out of version control (see §19).

## §4 — Gradle module registry

The architecture prescribes module boundaries (ARCHITECTURE §5, §64). This table is the **binding mapping** between physical directories and Gradle modules, and records build target kind (§5), allowed dependencies (§6), and activation stage (§16).

**Kind legend:** `JVM` = pure Kotlin library (no Android imports; unit-testable on desktop) · `ANDROID` = Android/Compose library compiled into the APK · `APP` = the runnable Android application · `AREA` = not a Gradle module (tooling/vendored content) · `(target)` = declared boundary, create the directory only when a real dependency boundary exists (ARCHITECTURE §64).

### `app`

| Path | Module | Kind | Package root | Responsibility | Depends on | Stage |
|---|---|---|---|---|---|---|
| `app/` | `:app` | APP | `com.forgekit.app` | Android application shell, composition root (constructor DI). Hosts cross-domain use cases such as `RunPluginUseCase` (plugin run → creates a Job). | every module (leaf — no module may depend on `:app`) | 1+ |

### `core/*`

| Path | Module | Kind | Package root | Responsibility | Depends on | Stage |
|---|---|---|---|---|---|---|
| `core/common/` | `:core:common` | JVM | `com.forgekit.core.common` | Dependency-free shared utilities and cross-module contracts. | — | 1 |
| `core/model/` | `:core:model` | JVM | `com.forgekit.core.model` | Cross-cutting value objects used by >1 domain: `ForgeId`, `Version`, `ExitStatus`, timestamps, base error types. | `core/common` | 1 |
| `core/database/` | `:core:database` | JVM (Room) | `com.forgekit.core.database` | Room bootstrap/session for the ForgeKit metadata DB (plugins, jobs, runtime_state, settings, …). Not the Termux package DB. | `core/common`, `core/model` | 7 |
| `core/logging/` | `:core:logging` | JVM | `com.forgekit.core.logging` | Structured logging: categories (app/runtime/plugin/job/security), redaction of secrets. | `core/common` | 1 |
| `core/security/` | `:core:security` | JVM | `com.forgekit.core.security` | Package signing/verification (`PackageVerifier`, `PackageSigner`, `PublisherIdentity`, `SignatureMetadata`), trust store, permission policy engine, security audit log. | `core/common`, `core/model`, `core/logging` | 8 (interfaces from 3) |
| `core/filesystem/` | `:core:filesystem` | JVM | `com.forgekit.core.filesystem` | `ForgePaths`, plugin layout resolution (§11), storage boundary contracts, file access grants. | `core/common`, `core/model` | 1 |

### `platform/*`

| Path | Module | Kind | Package root | Responsibility | Depends on | Stage |
|---|---|---|---|---|---|---|
| `platform/android/` | `:platform:android` | ANDROID | `com.forgekit.platform.android` | Android environment provider hooks: app context, storage locations, device capabilities. | `core/common` | 1 |
| `platform/notifications/` | `:platform:notifications` | ANDROID | `com.forgekit.platform.notifications` | Android notifications for plugin/job events. | `core/common`, `core/model`, `platform/android` | 10 |
| `platform/storage/` | `:platform:storage` | ANDROID | `com.forgekit.platform.storage` | Android Storage Access Framework integration for user-selected files (safe grants). | `core/common`, `core/model`, `core/filesystem`, `platform/android` | 10 |
| `platform/permissions/` | `:platform:permissions` | ANDROID | `com.forgekit.platform.permissions` | Bridges ForgeKit permission decisions to Android runtime permissions (e.g. `INTERNET`). | `core/security`, `platform/android` | 8 |
| `platform/lifecycle/` | `:platform:lifecycle` | ANDROID | `com.forgekit.platform.lifecycle` | Android foreground/background execution mechanics, app lifecycle events, runtime recovery triggers. | `core/common`, `core/model`, `platform/android` | 7 |

### `runtime/*` and `termux/*`

| Path | Module | Kind | Package root | Responsibility | Depends on | Stage |
|---|---|---|---|---|---|---|
| `runtime/api/` | `:runtime:api` | JVM | `com.forgekit.runtime.api` | `ForgeRuntime` contract + types: `ExecutionRequest`, `ExecutionHandle`, `RuntimeState`, `RuntimeCapabilities`, `RuntimeHealth`, … | `core/common`, `core/model` | 1 |
| `runtime/bridge/` | `:runtime:bridge` | JVM | `com.forgekit.runtime.bridge` | `ExecutionChannel`, `TerminalSession`, output streaming, signal handling, IPC between app layer and runtime. | `runtime/api`, `core/common` | 1 |
| `runtime/bootstrap/` | `:runtime:bootstrap` | JVM | `com.forgekit.runtime.bootstrap` | Bootstrap orchestration: verify → extract → initialize → health → repair. Descriptors reference `termux/bootstrap` artifacts. | `runtime/api`, `core/common`, `core/logging` | 1 |
| `runtime/termux/` | `:runtime:termux` | JVM | `com.forgekit.runtime.termux` | `EmbeddedTermuxRuntime : ForgeRuntime`. The **only** Termux-aware orchestration layer. | `runtime/api`, `runtime/bridge`, `runtime/bootstrap`, `termux/embedded`, `core/filesystem`, `core/logging` | 1 |
| `termux/embedded/` | `:termux:embedded` | ANDROID | `com.forgekit.termux.embedded` | Vendored/integrated Termux harness: session boot, prefix init, native exec. Lowest-level Termux boundary. | `core/common` only | 1 |
| `termux/upstream/` | — | AREA | — | Vendored upstream Termux source (commits/tags recorded). Never edited directly. | — | 1 |
| `termux/patches/` | — | AREA | — | Diff patches applying ForgeKit changes on upstream; one file per change + upstream commit ref. | — | 1 |
| `termux/bootstrap/` | — | AREA | — | Architecture-specific bootstrap archives + build scripts (min `arm64-v8a`). | — | 1 |

### `plugins/*`

| Path | Module | Kind | Package root | Responsibility | Depends on | Stage |
|---|---|---|---|---|---|---|
| `plugins/api/` | `:plugin:api` | JVM | `com.forgekit.plugin.api` | Plugin domain types: `PluginId`, `PluginVersion`, `PluginDescriptor`, `PluginStatus`, `PluginTrustLevel`, `PluginCapability`, `PluginContext`. | `core/common`, `core/model` | 3 |
| `plugins/manifest/` | `:plugin:manifest` | JVM | `com.forgekit.plugin.manifest` | Manifest schema `forgekit.plugin/v1`, `PluginManifest` + nested types, `ManifestParser`. | `core/common`, `core/model`, `plugins/api` | 3 |
| `plugins/installer/` | `:plugin:installer` | JVM | `com.forgekit.plugin.installer` | `.forge` archive handling: layout, extraction, install/update/remove, initialization, dependency reference counting. | `plugins/api`, `plugins/manifest`, `core/filesystem`, `core/security` | 3 |
| `plugins/validator/` | `:plugin:validator` | JVM | `com.forgekit.plugin.validator` | Full validation: archive structure, manifest schema, UI schema, permissions, dependencies, runtime/protocol compatibility, signature, prohibited files, size, entrypoint. | `plugins/api`, `plugins/manifest`, `core/security` | 3 |
| `plugins/resolver/` | `:plugin:resolver` | JVM | `com.forgekit.plugin.resolver` | Dependency graph, resolution, runtime inspection, orchestration through Termux `pkg`/`apt` and language managers (pip, npm, …). | `plugins/api`, `plugins/manifest`, `runtime/api` | 4 |
| `plugins/protocol/` | `:plugin:protocol` | JVM | `com.forgekit.plugin.protocol` | Structured protocol `forgekit.protocol/v1` (invoke/progress/log/result/error): types, parser, serializer, errors. | `plugins/api`, `core/common` | 5 |
| `plugins/manager/` | `:plugin:manager` | JVM | `com.forgekit.plugin.manager` | Plugin lifecycle state machine (IMPORT → REMOVE) and use cases (install/update/remove/disable/initialize). **Job creation is not here** — it lives in `:app` (`RunPluginUseCase`). | `plugins/api`, `plugins/manifest`, `plugins/installer`, `plugins/validator`, `plugins/resolver`, `plugins/protocol`, `core/security` | 3–7 |
| `plugins/registry/` | `:plugin:registry` | JVM | `com.forgekit.plugin.registry` | `PluginSource` contract + implementations (`OfficialRegistrySource`, `GitSource`, `LocalFileSource`, `UrlSource`, `PrivateRegistrySource`). | `plugins/api`, `core/common` | 10 |

### `jobs/*`

| Path | Module | Kind | Package root | Responsibility | Depends on | Stage |
|---|---|---|---|---|---|---|
| `jobs/api/` | `:job:api` | JVM | `com.forgekit.job.api` | Job domain: `Job`, `JobId`, `JobState` (QUEUED … CANCELLED), `JobEvent`, `JobQuery`, job streams. | `core/common`, `core/model` | 7 |
| `jobs/scheduler/` | `:job:scheduler` | JVM `(target)` | `com.forgekit.job.scheduler` | Job scheduling: queuing, prioritization, timing. May merge into `:job:manager` until a real boundary exists. | `job/api` | 7 |
| `jobs/execution/` | `:job:execution` | JVM `(target)` | `com.forgekit.job.execution` | Job execution orchestration: prepare environment, start process, stream events, cancellation. May merge into `:job:manager` until a real boundary exists. | `job/api`, `runtime/api` | 7 |
| `jobs/manager/` | `:job:manager` | JVM | `com.forgekit.job.manager` | `JobManager`: state machine, cancellation policy (SIGTERM → grace → SIGKILL), resource observation, use cases. | `job/api`, `runtime/api`, `core/logging` | 7 |
| `jobs/persistence/` | `:job:persistence` | JVM (Room) | `com.forgekit.job.persistence` | `JobStore` contract + `RoomJobStore` (jobs / job_events DAOs), history queries. | `job/api`, `core/database` | 7 |

### `ui/*`

| Path | Module | Kind | Package root | Responsibility | Depends on | Stage |
|---|---|---|---|---|---|---|
| `ui/design/` | `:ui:design` | ANDROID | `com.forgekit.ui.design` | Design tokens, theme, reusable Compose components, declarative control registry + rendering primitives. | `core/common` | 6 |
| `ui/navigation/` | `:ui:navigation` | ANDROID | `com.forgekit.ui.navigation` | App shell/navigation: Home, Plugins, Jobs, Terminal, Settings, Files. | `ui/design` | 10 |
| `ui/home/` | `:ui:home` | ANDROID | `com.forgekit.ui.home` | Home: runtime status, recent jobs, quick actions. | `ui/design`, `ui/navigation`, `runtime/api`, `job/api` | 10 |
| `ui/plugins/` | `:ui:plugins` | ANDROID | `com.forgekit.ui.plugins` | Plugin browse/install/detail/run screens + host for the declarative UI renderer. | `ui/design`, `plugins/api`, `plugins/manager`, `plugins/validator` | 6 |
| `ui/jobs/` | `:ui:jobs` | ANDROID | `com.forgekit.ui.jobs` | Jobs list/detail: progress, logs, cancel, history. | `ui/design`, `job/api`, `job/manager` | 7 |
| `ui/terminal/` | `:ui:terminal` | ANDROID | `com.forgekit.ui.terminal` | Terminal surface connected to the embedded runtime via `:runtime:bridge` `TerminalSession`. | `ui/design`, `runtime/api`, `runtime/bridge` | 9 |
| `ui/settings/` | `:ui:settings` | ANDROID | `com.forgekit.ui.settings` | Settings, developer mode, advanced runtime screen (state, prefix, environment, packages). | `ui/design`, `core/filesystem`, `runtime/api` | 10 |

### `tests/*` and `tools/*`

| Path | Module | Kind | Package root | Responsibility | Depends on | Stage |
|---|---|---|---|---|---|---|
| `tests/integration/` | `:tests:integration` | JVM test | `com.forgekit.tests.integration` | Cross-module integration + device tests (`RuntimeExecutionTest`, `PluginExecutionTest`, `DependencyInstallationTest`, `ProcessCancellationTest`). | app test fixtures | 2+ |
| `tools/forge-validator/` | `:tools:forge-validator` | JVM CLI | `com.forgekit.tools.forgevalidator` | `forgekit validate <package.forge>` — full package validation. | `plugins/validator`, `plugins/manifest`, `core/security` | 8 |
| `tools/forge-builder/` | `:tools:forge-builder` | JVM CLI | `com.forgekit.tools.forgebuilder` | `forgekit build <src-dir>` → `.forge`; future `forgekit sign`. | `plugins/installer`, `plugins/manifest` | 8 |
| `tools/forge-test/` | `:tools:forge-test` | JVM CLI | `com.forgekit.tools.forgetest` | `forgekit test <package.forge>` — standard compatibility suite against a real/host runtime. | `plugins/protocol`, `runtime/api` | 8 |

Additional rules for the registry:

1. **No module may depend on `:app`.** `:app` is the composition leaf.
2. `(target)` modules exist in the architecture (§5/§64) but their directories are created only when a real dependency boundary justifies them. Do not create them "just in case".
3. When a module is activated (built for the first time), add its Gradle definition in `settings.gradle.kts`; its module `README.md` must already be accurate.
4. This registry table is authoritative over the tree in §3.

## §5 — Module kinds and build targets

| Kind | Meaning | Modules |
|---|---|---|
| JVM | Pure Kotlin library. No Android imports, no Compose, no Room classes at API level. Unit tests run on the desktop JVM. | all `core/*`, `runtime/*`, `plugins/*`, `jobs/*` |
| JVM (Room) | Pure Kotlin, but depends on the Room runtime for persistence. Tests use an in-memory/headless database. | `core/database`, `jobs/persistence` |
| ANDROID | Compiled into the APK. May use Android SDK and Jetpack Compose. Still a library — not a runnable app. | all `platform/*`, all `ui/*`, `termux/embedded` |
| APP | The runnable Android application. Owns composition and app lifecycle. | `app` |
| JVM CLI | Command-line entry point (JVM). Shares domain libraries; never duplicates logic. | all `tools/*` |
| AREA | Not a Gradle module. Vendored source, schemas, examples, docs, licenses. | `termux/{upstream,patches,bootstrap}`, `Docs/developer`, `plugin-examples`, `tests`, `third-party` |

Build-target consequences:

1. Android-only knowledge (Activities, notifications, storage locations, runtime permissions) stays in `app`, `platform/*`, `ui/*`, `termux/embedded`.
2. Domain logic (`core/*`, `runtime/*`, `plugins/*`, `jobs/*`) is fully testable without an Android device.
3. A single APK includes: ForgeKit code + bootstrap (ARCHITECTURE §33, §68). Packaging and size are a release concern, not a module-layout concern.

## §6 — Dependency rules

The dependency direction mandated by the architecture (UI → Application → Domain → Interfaces → Infrastructure) is **enforced per module** by the `Depends on` column in §4. Rules below are the normative summary.

### 6.1 Allowed layering

```text
UI (app, ui/*)  →  Application use cases  →  Domain interfaces  →  Implementations  →  Infrastructure
```

Canonical example (ARCHITECTURE §6):

```text
PluginScreen → PluginViewModel → RunPluginUseCase → PluginExecutor → ForgeRuntime → EmbeddedTermuxRuntime
```

### 6.2 Forbidden edges (absolute)

1. `ui/*` and `app` must **never** import `runtime/termux`, `termux/embedded`, `core/database`, `jobs/persistence`, or any `.internal` package.
2. `ui/*` and `app` must **never** construct `ProcessBuilder` or parse shell output as application data.
3. No `android.*` / `kotlinx.compose` imports outside `app`, `platform/*`, `ui/*`, `termux/embedded`.
4. No Room imports outside `core/database`, `jobs/persistence`, `app`.
5. `runtime/*` must never import `plugins/*`, `jobs/*`, or `ui/*`.
6. `plugins/*` must never import `jobs/*` or `ui/*`. (Plugin run orchestration that creates Jobs lives in `:app`.)
7. `jobs/*` must never import `plugins/*` or `ui/*`.
8. `core/model` may depend only on `core/common`.
9. `termux/embedded` may depend only on `core/common`. It must not know `runtime/api` or anything above.
10. No module may depend on `:app`.
11. No plugin package or plugin code may ever import ForgeKit Kotlin classes (ARCHITECTURE §4-R4, §8) — this is a repository rule but also a packaging boundary rule.

### 6.3 One runtime principle (ARCHITECTURE §25)

Plugin execution, Terminal, background jobs and dependency installation **converge on the same `ForgeRuntime`**. Never create a second, parallel execution stack for a feature. The only sanctioned UI→runtime path is through `:runtime:api`/`:runtime:bridge` (`ui/terminal`).

### 6.4 Enforcement

1. Gradle module boundaries make cross-module imports a compile error.
2. A `.internal` package marks implementation details; other modules never import from it.
3. Code review and agent completion checklist (§18) verify these edges.
4. Mature phases add an architecture test (e.g. ArchUnit-style) in `tests/integration` that fails on illegal imports.

## §7 — Package and naming conventions

### 7.1 Package mapping

Every module uses a package root under `com.forgekit.*` (ARCHITECTURE §65). The package root for each module is listed in §4. Rules:

1. Package root == Gradle module path: `core/model` → `com.forgekit.core.model`; `plugins/validator` → `com.forgekit.plugin.validator`.
2. `core/common` is the only module allowed near the top level (`com.forgekit.core.common`).
3. Do not create `com.forgekit.app.scenes`, `com.forgekit.helpers`, or similar catch-all packages. A new package root requires §19 review.
4. Termux/vendored code keeps its upstream naming and license headers; ForgeKit code never merges into it.
5. Test packages mirror the main package exactly (`src/test/kotlin/com/forgekit/...`).

### 7.2 Type naming

| Concept | Convention | Example |
|---|---|---|
| Interface | No `I` prefix; domain-named | `ForgeRuntime`, `PluginSource`, `JobStore`, `PackageVerifier` |
| Abstract base | `Abstract` prefix where a base class is needed | `AbstractJobRunner` |
| Implementation | Descriptive; often `<What><Where>` | `EmbeddedTermuxRuntime`, `RoomJobStore`, `JsonUiSchemaLoader` |
| Use case | `<Verb><Thing>UseCase`, lives in the feature module (or `:app` for cross-domain) | `InstallPluginUseCase`, `RunPluginUseCase` |
| View model | `<Feature>ViewModel` | `PluginsViewModel`, `JobDetailViewModel` |
| Screen / Compose | `<Feature>Screen` (file + component function) | `HomeScreen`, `PluginsScreen` |
| Manager | `<Domain>Manager` | `PluginManager`, `JobManager`, `RuntimeManager` |
| Persistence | `<Thing>Store` + `<Thing>Dao` | `JobStore`, `JobsDao` |
| Event | `ForgeEvent` sealed hierarchy, `data class` variants | `JobStarted`, `JobProgress`, `RuntimeStateChanged` |
| Error | `<Domain>Error`, typed, defined in the owning module (§74 of ARCHITECTURE) | `PluginManifestError`, `ProtocolError`, `RuntimeUnavailableError` |
| Value object | Plain immutable `data class` in `core/model` when cross-cutting; module-local otherwise | `PluginId`, `JobId`, `Version`, `ExitStatus` |
| JSON schema IDs | `forgekit.<area>/v<N>` as string constants (§20) | `"forgekit/1"` (protocol wire value) |

### 7.3 Kotlin style rules

1. Immutability by default: prefer `data class` / `value class` / immutable collections; no mutable public fields.
2. Async = Kotlin coroutines: `suspend fun` on interfaces; UI state via `kotlinx.coroutines.flow.StateFlow`/`Flow`.
3. Dependency injection by constructor everywhere; composition happens once in `:app`. No service locators.
4. Enums use SCREAMING_SNAKE values and must match the architecture's canonical states exactly (e.g. `JobState.QUEUED`, `JobState.INSTALLING_DEPENDENCIES` — ARCHITECTURE §30; runtime states §70; trust levels §25).
5. Sealed hierarchies for domain variants (`sealed interface ForgeEvent`, `sealed interface ExecutionStatus`).
6. No `null`-as-in-band: prefer sealed result types and typed errors; where Kotlin interop requires nullable, keep it local and documented.
7. Logging only through `core/logging`; never `println` in production code.
8. Redact secrets at the logging boundary (ARCHITECTURE §42, §41).
9. Never embed platform paths; use `ForgePaths` (`core/filesystem`).

### 7.4 File conventions

1. One primary type per file; file name = type name (`PluginManifest.kt`, `ForgeRuntime.kt`). Small cohesive companion types may share a file when natural.
2. Files live at the package root or under `.internal` only.
3. Kotlin extensions for Compose screens follow Compose idiom (`fun ComposeScope.HomeScreen(...)`) and stay inside `ui/<feature>`.
4. No `XxxUtils.kt` grab-bags without review; prefer purpose-named files.

## §8 — Contract inventory (where things live)

This is the **named-type placement index**. When an agent needs a type, find it here first; when an agent creates a type, add it here so the next agent finds it. Naming follows §7.

### 8.1 Core

| Module | Canonical types |
|---|---|
| `core/common` | shared utilities and cross-module contracts with no domain owner |
| `core/model` | `ForgeId`, `Version`, `ExitStatus`, `ForgeTimestamp`, `ForgeError` base hierarchy |
| `core/database` | `ForgeDatabase` (Room bootstrapper), schema definitions for: plugins, plugin_versions, plugin_permissions, plugin_sources, jobs, job_events, runtime_state, settings, trusted_publishers (ARCHITECTURE §39) |
| `core/logging` | `ForgeLogger`, `LogCategory` (APP, RUNTIME, PLUGIN, JOB, SECURITY), `SecretRedactor` |
| `core/security` | `PackageVerifier`, `PackageSigner`, `PublisherIdentity`, `SignatureMetadata`, `TrustStore`, `PermissionPolicy`, `SecurityAuditLog` |
| `core/filesystem` | `ForgePaths`, `PluginLayout`, `FileAccessGrant`, storage boundary contracts |

### 8.2 Platform

| Module | Canonical types |
|---|---|
| `platform/android` | `AndroidEnvironment` (context/storage locations/capabilities provider) |
| `platform/notifications` | `NotificationService` (Android impl) |
| `platform/storage` | `StorageAccessService` (SAF user file grants), `UserFileHandle` |
| `platform/permissions` | `AndroidPermissionBroker` (ForgeKit permission → Android runtime permission) |
| `platform/lifecycle` | `BackgroundExecutionService`, `AppLifecycleListener`, `RuntimeRecoveryClient` |

### 8.3 Runtime

| Module | Canonical types |
|---|---|
| `runtime/api` | **`ForgeRuntime`** (the frozen contract: `initialize`, `execute`, `terminate`, `install`, `inspect`, `healthCheck`, `repair`), `ExecutionRequest`, `ExecutionHandle`, `RuntimeState` (NOT_INSTALLED…FAILED), `RuntimeCapabilities`, `RuntimeHealth`, `RuntimeRepairResult`, `ProcessId`, `RuntimeUnavailableError` |
| `runtime/bridge` | `ExecutionChannel`, `TerminalSession`, `OutputStream`, `SignalRequest` (SIGTERM/SIGINT/SIGKILL order§53), IPC message types |
| `runtime/bootstrap` | `BootstrapDescriptor`, `BootstrapVerifier`, `BootstrapOrchestrator` (verify → extract → initialize → health), `RuntimeRepairer` |
| `runtime/termux` | **`EmbeddedTermuxRuntime : ForgeRuntime`**, `TermuxEnvironment` | 
| `termux/embedded` | `TermuxSession` (session boot), `PrefixInitializer`, native exec harness |

### 8.4 Plugins

| Module | Canonical types |
|---|---|
| `plugins/api` | `PluginId`, `PluginVersion`, `PluginDescriptor`, `PluginStatus`, `PluginTrustLevel` (OFFICIAL, VERIFIED, COMMUNITY, UNKNOWN, BLOCKED), `PluginCapability`, `PluginContext` |
| `plugins/manifest` | `PluginManifest`, `ManifestSchema`, `ManifestParser`, `RuntimeRequirement`, `UiManifest`, `DependencyRequirement`, `PermissionDeclaration`, `ManifestError` |
| `plugins/installer` | `PluginPackage`, `PackageLayout`, `PluginInstaller`, `InstallResult`, `UninstallResult`, `PackageIntegrityError` |
| `plugins/validator` | `PluginValidator`, `ValidationReport`, `ValidationIssue`, `ValidationSeverity` |
| `plugins/resolver` | `DependencyResolver`, `DependencyGraph`, `ResolutionResult`, `DependencyState`, `DependencyError` |
| `plugins/protocol` | `PluginRequest` (invoke), `PluginEvent` (progress/log), `PluginResult`, `PluginError`, `ProtocolParser`, `ProtocolError` |
| `plugins/manager` | `PluginManager`, `PluginLifecycle`, use cases (`ImportPluginUseCase`, `InstallPluginUseCase`, `UpdatePluginUseCase`, `DisablePluginUseCase`, `RemovePluginUseCase`, `InitializePluginUseCase`) |
| `plugins/registry` | `PluginSource` (`search`, `fetch`), `PluginArtifact`, `OfficialRegistrySource`, `GitSource`, `LocalFileSource`, `UrlSource`, `PrivateRegistrySource` |

### 8.5 Jobs

| Module | Canonical types |
|---|---|
| `jobs/api` | `Job`, `JobId`, `JobState` (QUEUED, PREPARING, INSTALLING_DEPENDENCIES, STARTING, RUNNING, PAUSED, CANCELLING, COMPLETED, FAILED, CANCELLED), `JobEvent`, `JobQuery`, `JobStream` |
| `jobs/manager` | `JobManager`, `JobScheduler` (target), `JobStateMachine`, `CancellationPolicy` (SIGTERM → grace → SIGKILL), use cases (CancelJobUseCase, …) |
| `jobs/persistence` | `JobStore` (contract), `RoomJobStore`, `JobsDao`, `JobEventsDao` |

### 8.6 UI and app

| Module | Canonical types |
|---|---|
| `ui/design` | `Theme`, design tokens, reusable components (ForgeButton, ForgeCard, StatusBadge, ProgressBar…), `ControlRegistry` (maps UI schema controls → renderers) |
| `ui/navigation` | `AppShell`, `AppRoute` (HOME, PLUGINS, JOBS, TERMINAL, SETTINGS, FILES) |
| `ui/home` | `HomeScreen`, `HomeViewModel` |
| `ui/plugins` | `PluginsScreen`, `PluginDetailScreen`, `PluginRunScreen`, `PluginsViewModel`, declarative renderer host (`UiSchemaRenderer`) |
| `ui/jobs` | `JobsScreen`, `JobDetailScreen`, `JobsViewModel`, `JobLogView` |
| `ui/terminal` | `TerminalScreen`, `TerminalViewModel` (feeds `TerminalSession`) |
| `ui/settings` | `SettingsScreen`, `DeveloperModeScreen`, `RuntimeStatusScreen`, `SettingsViewModel` |
| `app` | `ForgeKitApp` (entry), `AppServices` (composition root), cross-domain use cases (`RunPluginUseCase`), `ForgeEventBus` wiring |

### 8.7 Cross-cutting conventions

1. **Versioned contract constants** (ARCHITECTURE §81): `plugin/v1` schemas, `protocol/v1` wire strings, `ui/v1` schema, `runtime/v1` capabilities object. Defined once in the owning module (§20).
2. **Events**: UI observes `ForgeEvent` streams; it never reads processes or the database directly (ARCHITECTURE §40).
3. **Errors**: typed, never raw stack traces in the UI (ARCHITECTURE §74).
4. **Secrets**: never in env vars by default; `SecretStore` (target, Stage 10) resolves via `secret("KEY")` with policy (ARCHITECTURE §42).

## §9 — Module-internal layout and file conventions

### 9.1 Standard module layout (JVM / ANDROID libraries)

```text
core/model/
├── build.gradle.kts
├── README.md                          ← module spec (matches §4)
└── src/
    ├── main/kotlin/com/forgekit/core/model/      ← public API at package root
    │   ├── ForgeId.kt
    │   ├── Version.kt
    │   └── internal/                  ← implementation details; private to module
    │       └── ...
    ├── main/resources/                ← non-code resources when needed
    │   └── ...
    └── test/kotlin/com/forgekit/core/model/      ← mirrors main exactly
        └── ForgeIdTest.kt
```

1. `src/main/kotlin` (not `src/main/java`).
2. Public API at the package root; `internal/` subpackage for anything not part of the module contract.
3. Tests live in `src/test/kotlin` under the identical package, one test file per primary type (`PluginManifestTest`, `ManifestParserTest`).
4. `resources` appears only when the module actually ships resources (e.g. `ui/design` theme JSON, `runtime/bootstrap` descriptors).

### 9.2 UI module layout

```text
ui/plugins/src/main/kotlin/com/forgekit/ui/plugins/
├── PluginsScreen.kt                   ← Compose component function
├── PluginsViewModel.kt                ← StateFlow owner
└── component/                         ← feature-local components (not in ui/design)
    └── PluginCard.kt
```

Screens are thin. All behavior lives in view models and use cases; screens only render state and forward user intent (ARCHITECTURE R2).

### 9.3 App composition layout

```text
app/src/main/kotlin/com/forgekit/app/
├── ForgeKitApp.kt                     ← entry point / Activity
├── AppServices.kt                     ← composition root (constructor DI wiring)
├── usecase/                           ← cross-domain use cases
│   └── RunPluginUseCase.kt
└── resource/...                       ← app icon, assets
```

### 9.4 Conventions for all modules

1. No file outside its own module's `src/` tree references another module's `.internal`.
2. Deprecated types are removed or migrated, never left as dual implementations.
3. Every module's public surface is reviewed against §8 when the module is activated.

## §10 — Testing conventions

### 10.1 Layers (ARCHITECTURE §50)

| Layer | Where | Examples |
|---|---|---|
| Unit | `src/test/kotlin` inside each module | `ManifestParserTest`, `PluginValidatorTest`, `DependencyResolverTest`, `ProtocolParserTest`, `JobStateMachineTest`, `PermissionPolicyTest`, `RuntimeHealthTest` |
| Integration | `tests/integration` (`:tests:integration`) | `RuntimeExecutionTest`, `PluginExecutionTest`, `DependencyInstallationTest`, `ProcessCancellationTest` — these exercise real layers through the boundaries, not mocks of everything |
| Runtime | `tests/integration` + device | Python, Node, Bash, native binaries, network, files, background execution |
| Device | real Android device / emulator | real embedded runtime, real process, real plugin |

### 10.2 Rules

1. **No test may assert fake data.** Progress, output, versions and states must come from the real component.
2. Unit tests never touch Android APIs or the termux runtime unless they are runtime tests.
3. Integration tests run against `TestRuntime`-style fakes **only for contract verification**; real-execution tests must go through the real embedded runtime where practical.
4. Every new public type ships with tests in the mirror package; use case tests live beside the use case.
5. Test tooling: JUnit 5 + `kotlin.test` assertions; coroutine tests with `kotlinx-coroutines-test` where needed; Compose tests with the official Compose testing library in `ui/*`.
6. `tests/integration` aggregates cross-module suites; device tests are tagged and excluded from CI default runs.

### 10.3 Acceptance tests (executable definitions of done)

The three golden packages in `plugin-examples/` (§17) are **run as automated suites**, not just documentation.

## §11 — Runtime filesystem contract

### 11.1 Logical layout (ARCHITECTURE §10)

This is an architectural contract, not a hardcoded path list. Actual Android absolute paths are resolved once by `ForgePaths` (`core/filesystem`) against app-controlled storage.

```text
ForgeKit/                       ← application-owned root
├── app/                        ← Android application data (Room DB, settings, logs)
├── forge/
│   ├── plugins/<plugin-id>/    ← per-plugin isolation (ARCHITECTURE §11)
│   │   ├── manifest.json
│   │   ├── ui/
│   │   ├── runtime/
│   │   ├── data/
│   │   ├── cache/
│   │   ├── environment/        ← .venv, node_modules, bin (ARCHITECTURE §21)
│   │   └── logs/
│   ├── jobs/                   ← job inputs/outputs/history artifacts
│   ├── registry/               ← plugin source metadata/cache
│   └── metadata/               ← reserved ForgeKit metadata
└── termux/                     ← embedded Termux runtime (Termux-owned)
    ├── home/                   ← $HOME
    ├── prefix/                 ← $PREFIX (app + shell + packages)
    ├── tmp/                    ← $TMPDIR
    └── packages/               ← dpkg/apt state
```

### 11.2 Rules

1. **Never place ForgeKit plugin data directly into Termux `$HOME`** without a defined boundary (ARCHITECTURE §11).
2. Plugin runtime access to files is governed by `ForgePaths` + `FileAccessGrant`; the policy layer decides what a plugin may reach (ARCHITECTURE §22, §23).
3. User-selected external files go through the picker → grant → usable path/copy chain (ARCHITECTURE §35); never let plugins roam the device FS freely.
4. Job outputs and logs are persisted under `forge/jobs` and referenced from `jobs/persistence`; they survive restarts.
5. Secrets never reside in plugin data, job files, or logs (ARCHITECTURE §42).
6. Termux owns everything under `termux/`; ForgeKit owns everything under `app/` and `forge/`.

## §12 — Documentation and ADRs

### 12.1 Target `docs/` tree (ARCHITECTURE §84)

```text
docs/
├── ARCHITECTURE.md        ← architecture baseline (authoritative)
├── STRUCTURE.md           ← this document (physical layout)
├── RULES.md               ← versioned development contract (forgekit/v1)
├── PLUGIN_SPEC.md         ← .forge package + manifest specification
├── UI_SPEC.md             ← declarative UI schema specification
├── PROTOCOL.md            ← plugin runtime protocol specification
├── SECURITY.md            ← security model, signing, trust
├── CONTRIBUTING.md        ← contribution workflow
└── adr/                   ← Architecture Decision Records (§85)
    ├── 0001-embedded-termux.md
    ├── 0002-plugin-format.md
    ├── 0003-plugin-protocol.md
    ├── 0004-runtime-boundary.md
    ├── 0005-filesystem-model.md
    ├── 0006-permission-model.md
    ├── 0007-package-signing.md
    ├── 0008-dependency-management.md
    └── 0009-termux-upstream-strategy.md
```

### 12.2 Current state and migration

- Existing docs live in `Docs/` (architecture, rules, specs, `UPSTREAM.md`, `RELEASING.md`, `developer/`).
- Migration to lowercase `docs/` happens in a **later phase** (rename + link fixes) — until then, new docs are added to `Docs/` and references use the current location.
- **No doc may contradict `ARCHITECTURE.md` or `STRUCTURE.md`.** A spec doc that conflicts with architecture is itself invalid until the architecture is changed via ADR.

### 12.3 ADR rules

1. Every major architectural change starts with an ADR in `Docs/adr/NNNN-title.md` (using `Docs/` until migration).
2. ADRs never get deleted; superseded ADRs are marked `Superseded by NNNN`.
3. Follow the numbering scheme above; the highest number is next.

## §13 — Termux upstream and third-party licensing

### 13.1 `termux/` areas

| Area | Purpose | Rules |
|---|---|---|
| `termux/upstream/` | Vendored upstream Termux source: app, bootstrap tooling, relevant package/scripts. | Record repository, tag/commit and license per component (ARCHITECTURE §67). Never edit in place; apply changes via `termux/patches`. |
| `termux/patches/` | One patch file per ForgeKit change, in apply order. | Each patch header: upstream repo, base commit, reason, license impact. |
| `termux/bootstrap/` | Generated bootstrap archives + the scripts that produce them. | Build per ABI (minimum `arm64-v8a`; optional armeabi-v7a, x86_64, x86 — ARCHITECTURE §34). Never commit large archives; produce them in CI/release builds. Current exception: the pinned arm64 bootstrap is committed as `app/src/main/assets/bootstrap/bootstrap-arm64-v8a.zip` (see §19 reconciliation). |
| `termux/embedded/` | The Gradle `:termux:embedded` harness wrapping the above into ForgeKit. | Only integration code; supports `:runtime:termux`. |

### 13.2 Upstream synchronization (ARCHITECTURE §48)

```text
upstream Termux → review changes → update fork/integration → build
→ ForgeKit integration tests → runtime compatibility tests → release
```

[`Docs/UPSTREAM.md`](Docs/UPSTREAM.md) and `third-party/termux-bootstrap/` record the exact upstream release, commit and checksum.

### 13.3 Third-party registry (ARCHITECTURE §66, §67)

`third-party/` records every embedded component:

```text
third-party/
├── THIRD_PARTY_NOTICES.md     ← human-readable notices index
├── licenses/                  ← retained license texts
└── termux-bootstrap/          ← exact provenance, checksum, packages and source links
```

For every component record: name, version, source URL, license, repository/commit/tag, modifications, license obligations.

**Licensing boundary (mandatory before first public distribution):** Termux components carry open-source licenses (Termux app = GPLv3). Never treat vendored Termux code as freely copyable into a proprietary app. Before release, inventory every Termux component, preserve notices, and review GPL obligations (ARCHITECTURE §66). Do not postpone.

## §14 — Developer guide and plugin examples

### 14.1 `Docs/developer/`

The developer- and AI-agent-facing guide for producing `.forge` packages (ARCHITECTURE §27) is
split into ordered chapters under `Docs/developer/`. `order.txt` is the canonical merge order; the
app verifies it at build time and exports the chapters as one `Docs.md` file.

AI agent package workflow (ARCHITECTURE §27):

```text
Read RULES.md → understand Plugin API → generate package
→ run validator → run tests → build .forge → sign/package
```

1. Runtime parsers and validators are authoritative; developer chapters document only behavior
   verified against those implementations and their tests.
2. Any schema or protocol change is a versioned contract change and updates the relevant chapter,
   golden example and test in the same commit.

### 14.2 `plugin-examples/`

Real package sources that double as acceptance tests. Each directory is a **source tree**; built `.forge` files are not committed.

| Example | Purpose | Requires stage |
|---|---|---|
| `hello/` | Declarative UI, input validation, progress/log/result and a real Python process. | 6–7 |
| `interactive/` | Password prompt and host-mediated `prompt_response` round trip. | 5–7 |
| `ssl-patcher/` | File input, select option, real ZIP inspection and file output. | 6–7 |

Example source layout (see `hello/`):

```text
hello/
├── manifest.json
├── ui/
│   └── main.json
└── runtime/
    └── main.py
```

## §15 — Command-line tools

CLI tools are JVM applications that **reuse** domain libraries; they never re-implement validation, manifest parsing or protocol logic.

| Tool | Command | Responsibility |
|---|---|---|
| `tools/forge-validator` | `forge-validator inspect <package.forge>` | Run the production parser and validator, print review facts, and exit 0 for valid, 1 for rejected, or 2 for invalid usage. |
| `tools/forge-builder` | `forge-builder build <src-dir> [--out <file>] [--key <hex-seed>] [--name <publisher>]` | Build deterministically; optional Ed25519 signing uses the production security implementation. |
| `tools/forge-test` | Not implemented | Reserved directory containing only a README; compatibility is currently exercised by Gradle integration tests. |

Naming/behavior notes:

1. Active tools print deterministic human-readable output suitable for CI logs.
2. Their application distributions include `LICENSE` and `third-party/` notices.
3. CLI syntax is tested in each tool module; do not document planned commands as available.

## §16 — Implementation stages (module activation order)

These stages preserve the implementation order recorded by the architecture. **A stage is only complete when its exit criteria pass with real behavior.** Never activate a later stage's UI around an earlier stage's placeholder.

| Stage | Focus | Modules activated | Exit criteria |
|---|---|---|---|
| 0 | Structure | (this phase) | Repo tree, `STRUCTURE.md`, module READMEs, root README. |
| 1 | Embedded runtime | `core/common`, `core/model`, `core/logging`, `core/filesystem`, `runtime/api`, `runtime/bridge`, `runtime/bootstrap`, `termux/embedded`, `runtime/termux`, shell scaffold in `app` | `RuntimeManager.initialize()` → READY (or explicit FAILED); real runtime commands; `runtime/api` unit tests. |
| 2 | Runtime health, process execution, shell | `runtime/termux`, `runtime/bridge` hardening | `healthCheck()`, `repair()`, `execute()` for arbitrary commands; `TerminalSession` on `:runtime:bridge`; `RuntimeHealthTest`, `RuntimeExecutionTest`. |
| 3 | Plugin parser/manifest/installer | `plugins/api`, `plugins/manifest`, `plugins/installer`, `plugins/validator` (core), `termux/bootstrap` descriptors | Parse, validate and install an independently created `.forge`; `ManifestParserTest`, `PluginValidatorTest`. |
| 4 | Dependency system | `plugins/resolver` | Detect → inspect runtime → install missing → verify install for Termux + language level dependencies; resolver tests pass. |
| 5 | Plugin protocol | `plugins/protocol` | invoke/progress/log/prompt/result/error end-to-end between app and plugin process; protocol tests and golden packages pass. |
| 6 | Declarative UI renderer | `ui/design`, `ui/plugins` | `ui/main.json` → Compose screen with declared controls; golden-package integration passes. |
| 7 | Job manager | `core/database`, `job/api`, `job/manager`, `job/persistence`, `platform/lifecycle`, `ui/jobs` | Persistent jobs and logs; start/progress/prompt/cancel/terminate/history survive restart; job tests pass. |
| 8 | Permissions & security | `core/security` (engine), `platform/permissions`, `tools/forge-validator` foundation, security audit log | Permission declaration → user approval → enforcement chain; signing interfaces + local verification; `PermissionPolicyTest`; `forgekit validate`. |
| 9 | Terminal | `ui/terminal` | Real interactive PTY terminal over the embedded runtime. |
| 10 | Polished product | `ui/navigation`, `ui/home`, `ui/settings`, `platform/notifications`, `plugin/registry`, services, backup/restore, secret store | Full user flows; marketplace optional; ARCHITECTURE §88 completion criteria reviewed; distribution/licensing requirements met. |

Cross-cutting notes:

1. Stages may overlap in time but **never in priority**: earlier stages are always blocked by placeholders, not aesthetics.
2. Every stage adds its tests in the module (`src/test/kotlin`) or `tests/integration` before it is considered done.
3. A stage's exit criteria are the *minimum*. If an architecture rule cannot be satisfied yet, record the blocker (§19/Change control) instead of faking it.

## §17 — Acceptance tests map

| # | Test | Real chain required | Gate |
|---|---|---|---|
| 1 | `plugin-examples/hello` | build → validate → install → real Python process → progress/log/result protocol | Stage 6–7 |
| 2 | `plugin-examples/interactive` | real process → password prompt → host response → result, with response value excluded from persistence | Stage 5–7 |
| 3 | `plugin-examples/ssl-patcher` | file input → real ZIP inspection → progress → result and output-file artifact | Stage 6–7 |
| 4 | Terminal | interactive shell and commands over the same embedded runtime used by plugins | Stage 9 |

These four are automated where the platform permits; on real devices they are the release gates.

## §18 — Agent working rules

These rules bind every implementing agent (human or AI). They are the completion checklist.

### 18.1 Before writing code

- [ ] Read `Docs/ARCHITECTURE.md` (the architecture) and `STRUCTURE.md` (this file) — in that order.
- [ ] Identify the module(s) owning the change in §4; confirm package root and allowed dependencies.
- [ ] Check whether a named type already exists in §8; reuse before creating.
- [ ] Check the stage of the target module in §16; do not cross stage boundaries without recording a decision.

### 18.2 While writing code

- [ ] Place every file in the module the §4 table says.
- [ ] Follow naming and style in §7; follow module layout in §9.
- [ ] Respect the dependency edges in §6. When you need something from another module, depend on its public API — never on its `.internal`, and never on `:app`.
- [ ] No fake behavior: no hardcoded outputs, simulated progress, or mock-like plumbing in production paths.
- [ ] Keep UI thin: screens render state, view models own state, use cases own behavior (ARCHITECTURE R2).
- [ ] Every public type, event variant, and typed error you add must be reflected in §8.

### 18.3 Before finishing

- [ ] Tests added in the mirror package (or `tests/integration`) and passing — with real data (§10).
- [ ] Module `README.md` updated if the module's responsibility/deps changed.
- [ ] `STRUCTURE.md` updated if file placement, module set, or contract inventory changed. Structural change without updating it is a blocking defect.
- [ ] No secrets/absolute paths leaked into code, logs, or docs (§7.3, §11.2).
- [ ] Termux upstream vendored files untouched (changes go through `termux/patches`) unless the task is explicitly the upstream integration.
- [ ] No new dependency added without recording the version/scope in the module's build file and `third-party/` when vendored.

### 18.4 When blocked

Report the exact blocker with a reproducible description. Do not silently replace difficult functionality with a fake implementation, and do not claim a feature done because a screen exists.

## §19 — Change control and reconciliation notes

### 19.1 How to change the structure

| Severity | Process |
|---|---|
| Typo/clarification | Edit `STRUCTURE.md` directly, update TOC if needed. |
| New module / moved boundary / new package root / renamed canonical type | 1. Open an ADR if the architecture is affected (§12.3). 2. Edit §3/§4/§8 accordingly. 3. Update affected module `README.md` files. 4. Note the change in the git commit. |
| Architectural decision override | ADR first, then `STRUCTURE.md`. `ARCHITECTURE.md` changes only through the architecture owner. |

### 19.2 Reconciliation notes (decisions that merge the two source documents)

The two source documents occasionally use different names for the same boundary. The canonical choices are:

| Topic | Document A | Document B | Canonical decision |
|---|---|---|---|
| Plugin modules directory | `forgekit/plugins/` (§5) | `:plugin:*` Gradle names (§64) | Directory = `plugins/`, Gradle names = `:plugin:*`. |
| Jobs modules | `jobs/manager, scheduler, persistence, execution` (§5) | `:job:api, manager, persistence` (§64) | All five declared; `scheduler`/`execution` are `(target)` modules merged into `:job:manager` until real boundaries appear. |
| Bootstrap location | `runtime/bootstrap` (§5) + `termux/bootstrap` (§84) | `:runtime:bootstrap` (§64) | Split: `runtime/bootstrap` = orchestration code; `termux/bootstrap` = artifacts/build scripts. |
| Termux integration module | `termux-upstream/` (§5) | `:termux:embedded` (§64); `termux/integration/` (§84) | Physical `termux/embedded/` = Gradle `:termux:embedded` (the integration harness). |
| Design system module | `ui/design-system` (§5) | `:ui:design` (§64) | `ui/design/`, `:ui:design`. |
| Protocol wire value | `"protocol": "forgekit/1"` (§15) | `forgekit.protocol/v1` (§81) | Wire string `"forgekit/1"`; spec identifier `forgekit.protocol/v1`; both constants live in `plugins/protocol`. |
| Docs folder | `docs/` (§84) | repo currently `Docs/` | `docs/` is the target; migration deferred (see §12.2). |
| Job creation owner | Plugin run flow creates Jobs (§73) | `plugins/manager` must stay level-clean | `RunPluginUseCase` lives in `:app`; job/plugin module layers stay separate. |

## §20 — Glossary and version constants

### 20.1 Glossary

| Term | Meaning |
|---|---|
| `.forge` | The official plugin package archive format (ARCHITECTURE §12). |
| Runtime | The embedded Termux-derived execution environment behind `ForgeRuntime`. |
| Plugin | A `.forge` package installed and run by ForgeKit. |
| Job | One execution of a plugin (or runtime task), persisted and observable. |
| Contract inventory | §8: the canonical type-name → module placement index. |
| `(target)` module | Declared in the architecture but not physically created until needed (§4). |

### 20.2 Version constants (canonical values — ARCHITECTURE §81)

| Contract | Constant | Owner |
|---|---|---|
| Manifest schema | `forgekit.plugin/v1` | `plugins/manifest` |
| UI schema | `forgekit.ui/v1` | `ui/design` |
| Wire protocol value | `forgekit/1` · spec id `forgekit.protocol/v1` | `plugins/protocol` |
| Runtime capabilities | `forgekit.runtime/v1` | `runtime/api` |
| Rules contract | `forgekit/rules/v1` (package `"rules": "forgekit/v1"`) | docs/RULES.md |

1. Constants are Kotlin string constants defined once in the owner module; developer documentation under `Docs/developer/` records the same values.
2. Breaking changes bump the major version and require an ADR; old versions stay parseable by a compatibility path or are explicitly rejected with a typed error.

---

**End of Project Structure Specification.**
