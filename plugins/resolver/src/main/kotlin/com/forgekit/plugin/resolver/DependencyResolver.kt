package com.forgekit.plugin.resolver

import com.forgekit.plugin.manifest.PluginManifest
import com.forgekit.runtime.api.DependencyInstallResult
import com.forgekit.runtime.api.ForgeRuntime
import com.forgekit.runtime.api.InstallEvent
import com.forgekit.runtime.api.InstallPhase
import com.forgekit.runtime.api.RuntimeCapabilities
import com.forgekit.runtime.api.RuntimeDependency
import com.forgekit.runtime.api.StreamingInstaller
import kotlinx.coroutines.flow.FlowCollector

/**
 * One dependency the resolver plans to act on (ARCHITECTURE §26 pipeline:
 * read manifest → inspect runtime → check dependencies → identify missing →
 * resolve/install → verify).
 */
public data class DependencyPlan(
    /** Manager that owns the install: TERMUX / PIP / NPM. */
    public val manager: String,
    public val name: String,
    public val versionRequirement: String?,
    /** Present in the runtime already (real inspection result). */
    public val alreadyPresent: Boolean,
    public val source: String,
)

/** Outcome of resolving a whole plugin's dependency set. */
public data class ResolutionReport(
    public val pluginId: String,
    public val plans: List<DependencyPlan>,
    public val installResults: List<DependencyInstallResult>,
    public val verified: Boolean,
) {
    /** All dependencies the runtime reported as installed (or already present). */
    public val satisfied: Boolean
        get() = installResults.all { it.installed }

    public val missingAfterResolution: List<DependencyInstallResult>
        get() = installResults.filter { !it.installed }
}

/**
 * Streaming resolution events for one [DependencyResolver.resolveStreaming]
 * call. [Planned] carries the whole plan up front so the UI can show
 * per-dependency progress against a real denominator; [Resolving]/[Line] track
 * the dependency currently being installed; [Completed] closes each one.
 */
public sealed interface ResolutionEvent {
    public data class Planned(val plans: List<DependencyPlan>) : ResolutionEvent

    public data class Resolving(
        public val dependency: RuntimeDependency,
        public val phase: InstallPhase,
        public val message: String?,
    ) : ResolutionEvent

    public data class Line(
        public val dependency: RuntimeDependency,
        public val text: String,
    ) : ResolutionEvent

    public data class Completed(
        public val dependency: RuntimeDependency,
        public val result: DependencyInstallResult,
    ) : ResolutionEvent
}

/**
 * REAL dependency resolution against the live embedded runtime: capability
 * inspection (never assumptions) → plan → install through [ForgeRuntime.install]
 * (pkg/apt for Termux packages, pip for python, npm for node) → verify.
 *
 * Presence uses true package-database checks whenever the runtime exposes
 * [StreamingInstaller] (dpkg-query / `pip show` / `npm ls -g`); otherwise the
 * tool-on-disk heuristic is the fallback for stub runtimes in tests.
 *
 * The language-runtime itself (e.g. python) is a TERMUX dependency of the plan
 * when [RuntimeCapabilities.tools] does not report it.
 */
public class DependencyResolver(
    private val runtime: ForgeRuntime,
) {

    /** Inspects the runtime and builds the install plan for a plugin (no installs). */
    public suspend fun plan(manifest: PluginManifest): List<DependencyPlan> {
        val capabilities = runtime.inspect()
        val tools = capabilities.tools.associateBy { it.name }
        val presence = runtime as? StreamingInstaller
        val plans = mutableListOf<DependencyPlan>()

        // Termux packages declared by the manifest — presence truth is the REAL
        // package database (dpkg-query) when available, else the tool-on-disk list.
        for (name in manifest.dependencies.termux) {
            val present = presence?.let {
                runCatching { it.isInstalled(termux(name)) }.getOrDefault(false)
            } ?: (name in tools)
            plans += DependencyPlan(
                manager = "TERMUX", name = name,
                versionRequirement = null,
                alreadyPresent = present,
                source = "termux-main",
            )
        }

        // The language runtime itself, when absent AND not already covered by
        // an explicit termux dependency (deduped by manager+name)
        val runtimeType = manifest.runtime.type
        if (runtimeType != "binary" &&
            runtimeType !in tools &&
            plans.none { it.manager == "TERMUX" && it.name == runtimeType }
        ) {
            plans += DependencyPlan(
                manager = "TERMUX",
                name = runtimeType,
                versionRequirement = manifest.runtime.version,
                alreadyPresent = false,
                source = "termux-main",
            )
        }

        for (dep in manifest.dependencies.python) {
            plans += DependencyPlan(
                manager = "PIP", name = dep.name,
                versionRequirement = dep.version,
                alreadyPresent = presence?.let { installer ->
                    runCatching {
                        installer.isInstalled(
                            RuntimeDependency(RuntimeDependency.Kind.PYTHON_PACKAGE, dep.name, dep.version),
                        )
                    }.getOrDefault(false)
                } ?: ("pip" in tools),
                source = dep.source ?: "pypi",
            )
        }
        for (dep in manifest.dependencies.node) {
            plans += DependencyPlan(
                manager = "NPM", name = dep.name,
                versionRequirement = dep.version,
                alreadyPresent = presence?.let { installer ->
                    runCatching {
                        installer.isInstalled(
                            RuntimeDependency(RuntimeDependency.Kind.NODE_PACKAGE, dep.name, dep.version),
                        )
                    }.getOrDefault(false)
                } ?: ("npm" in tools),
                source = dep.source ?: "npm",
            )
        }
        return plans
    }

    /**
     * Resolves everything with streaming progress: emits the full plan first,
     * then one [ResolutionEvent] stream per install, then the terminal report.
     * Already-present items are reported as such (never re-installed blindly).
     */
    public suspend fun resolveStreaming(
        manifest: PluginManifest,
        events: FlowCollector<ResolutionEvent>,
    ): ResolutionReport {
        val plans = plan(manifest)
        events.emit(ResolutionEvent.Planned(plans))
        val results = mutableListOf<DependencyInstallResult>()
        for (plan in plans) {
            val dependency = dependencyOf(plan)
            if (plan.alreadyPresent) {
                val present = DependencyInstallResult(
                    dependency = dependency,
                    installed = true,
                    alreadyPresent = true,
                    detail = "present in runtime (inspected)",
                )
                results += present
                events.emit(ResolutionEvent.Completed(dependency, present))
                continue
            }
            events.emit(ResolutionEvent.Resolving(dependency, InstallPhase.INSPECTING, null))
            val result = installWithProgress(dependency, events)
            results += result
            events.emit(ResolutionEvent.Completed(dependency, result))
        }
        return ResolutionReport(
            pluginId = manifest.id,
            plans = plans,
            installResults = results,
            verified = results.all { it.installed },
        )
    }

    /**
     * Non-streaming resolve (the pre-streaming contract): identical result,
     * progress discarded.
     */
    public suspend fun resolve(manifest: PluginManifest): ResolutionReport =
        resolveStreaming(manifest) {}

    private suspend fun installWithProgress(
        dependency: RuntimeDependency,
        events: FlowCollector<ResolutionEvent>,
    ): DependencyInstallResult {
        val installer = runtime as? StreamingInstaller
        return if (installer != null) {
            runCatching {
                installer.installStreaming(dependency) { installEvent ->
                    when (installEvent) {
                        is InstallEvent.Phase -> events.emit(
                            ResolutionEvent.Resolving(dependency, installEvent.phase, installEvent.message),
                        )
                        is InstallEvent.Line -> events.emit(ResolutionEvent.Line(dependency, installEvent.text))
                    }
                }
            }.getOrElse { failure ->
                DependencyInstallResult(
                    dependency = dependency,
                    installed = false,
                    alreadyPresent = false,
                    detail = "install failed: ${failure.message}",
                )
            }
        } else {
            runCatching { runtime.install(dependency) }.getOrElse { failure ->
                DependencyInstallResult(
                    dependency = dependency,
                    installed = false,
                    alreadyPresent = false,
                    detail = "install failed: ${failure.message}",
                )
            }
        }
    }

    private fun dependencyOf(plan: DependencyPlan): RuntimeDependency =
        RuntimeDependency(
            kind = when (plan.manager) {
                "TERMUX" -> RuntimeDependency.Kind.TERMUX_PACKAGE
                "PIP" -> RuntimeDependency.Kind.PYTHON_PACKAGE
                "NPM" -> RuntimeDependency.Kind.NODE_PACKAGE
                else -> RuntimeDependency.Kind.TERMUX_PACKAGE
            },
            name = plan.name,
            versionRequirement = plan.versionRequirement,
        )

    private fun termux(name: String): RuntimeDependency =
        RuntimeDependency(RuntimeDependency.Kind.TERMUX_PACKAGE, name)
}
