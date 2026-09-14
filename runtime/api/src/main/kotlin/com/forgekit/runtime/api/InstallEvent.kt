package com.forgekit.runtime.api

/**
 * Streaming events from a single [StreamingInstaller.installStreaming] call.
 * Each event is bounded to the dependency being installed by the call it
 * belongs to — no cross-correlation, no global broadcast.
 */
public sealed interface InstallEvent {

    /** A concrete milestone, derived from real package-manager output. */
    public data class Phase(
        public val dependency: RuntimeDependency,
        public val phase: InstallPhase,
        /** Short human summary, e.g. the apt line the phase was derived from. */
        public val message: String? = null,
    ) : InstallEvent

    /** A raw output line from the package manager (diagnostics/terminal). */
    public data class Line(
        public val dependency: RuntimeDependency,
        public val text: String,
    ) : InstallEvent
}