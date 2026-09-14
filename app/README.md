# app

**Gradle module:** `:app`
**Kind:** Android APK
**Package root:** `com.forgekit.app`
**Responsibility:** Android application shell and composition root (constructor DI). Hosts cross-domain use cases such as RunPluginUseCase (plugin run creates a Job).
**Depends on:** every module (leaf; no module may depend on :app)
**Activation stage:** Stage 1+

See `/STRUCTURE.md` for placement, package, naming, and dependency rules.
