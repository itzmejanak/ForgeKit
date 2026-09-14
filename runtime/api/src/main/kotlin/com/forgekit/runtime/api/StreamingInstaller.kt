package com.forgekit.runtime.api

import kotlinx.coroutines.flow.FlowCollector

/**
 * Optional provisioning capability layered on [ForgeRuntime] without touching
 * the frozen contract (ARCHITECTURE §3-R3): real-time dependency-install
 * progress and true package-level presence checks.
 *
 * [ForgeRuntime.install] still exists unchanged for callers that only need the
 * terminal result. Implementers of this interface stream the same install
 * through [installStreaming], and answer [isInstalled] from the package
 * database (dpkg-query / `pip show` / `npm ls -g`) — never from the tool-path
 * heuristic.
 */
public interface StreamingInstaller : ForgeRuntime {

    /**
     * Installs [dependency] exactly like [ForgeRuntime.install], additionally
     * streaming every [InstallEvent] (phase milestones + raw output lines) to
     * [events] as the install progresses. The returned result is identical in
     * meaning to [ForgeRuntime.install]'s.
     */
    public suspend fun installStreaming(
        dependency: RuntimeDependency,
        events: FlowCollector<InstallEvent>,
    ): DependencyInstallResult

    /**
     * True when [dependency] is confirmed installed by the package database.
     * Contract honesty: presence is verified against real package state, never
     * inferred from which manager binary happens to exist.
     */
    public suspend fun isInstalled(dependency: RuntimeDependency): Boolean
}