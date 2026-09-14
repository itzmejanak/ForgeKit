# termux

**Gradle module:** `:runtime:termux`
**Kind:** JVM library
**Package root:** `com.forgekit.runtime.termux`
**Responsibility:** EmbeddedTermuxRuntime implementing ForgeRuntime. The only Termux-aware orchestration layer; no other module knows Termux internals.
**Depends on:** runtime/api, runtime/bridge, runtime/bootstrap, termux/embedded, core/filesystem, core/logging
**Activation stage:** Stage 1

See `/STRUCTURE.md` for placement, package, naming, and dependency rules.
