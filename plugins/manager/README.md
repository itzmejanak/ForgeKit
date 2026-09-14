# manager

**Gradle module:** `:plugin:manager`
**Kind:** JVM library
**Package root:** `com.forgekit.plugin.manager`
**Responsibility:** Plugin lifecycle state machine (IMPORT to REMOVE) and use cases (install/update/remove/disable/initialize). Running a plugin (job creation) lives in the app composition, not here.
**Depends on:** plugins/api, plugins/manifest, plugins/installer, plugins/validator, plugins/resolver, plugins/protocol, core/security
**Activation stage:** Stage 3-7

See `/STRUCTURE.md` for placement, package, naming, and dependency rules.
