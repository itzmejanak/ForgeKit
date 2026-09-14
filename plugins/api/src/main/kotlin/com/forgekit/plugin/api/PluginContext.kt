package com.forgekit.plugin.api

import com.forgekit.core.common.ForgeContracts

/**
 * Runtime-facing context handed to a running plugin action (ARCHITECTURE §15/§33).
 *
 * The plugin process NEVER receives this object — it receives its effects: env
 * vars, granted paths, protocol stdin/stdout wiring. This type is the
 * application-side handle for the job runner.
 */
public data class PluginContext(
    /** Id of the plugin being run. */
    public val pluginId: PluginId,
    /** Id of the job this run belongs to (job system link). */
    public val jobId: String,
    /** Plugin-owned working directory (job sandbox). */
    public val workingDirectory: String,
    /** Directories the plugin may read (granted). */
    public val readableDirectories: List<String>,
    /** Directories the plugin may write (granted). */
    public val writableDirectories: List<String>,
    /** Environment layered over the Termux base env (§8). */
    public val environment: Map<String, String>,
    /** Protocol version this run speaks. */
    public val protocol: String = ForgeContracts.PROTOCOL_WIRE,
    /** Action invocation timeout (0 = no timeout). */
    public val timeoutMillis: Long = 0,
) {
    init {
        require(protocol == ForgeContracts.PROTOCOL_WIRE) {
            "unsupported protocol '$protocol' (expected ${ForgeContracts.PROTOCOL_WIRE})"
        }
    }

    /** Conventional env block a runtime script uses to discover its sandbox. */
    public fun forgeEnvironment(): Map<String, String> = mapOf(
        "FORGE_JOB_ID" to jobId,
        "FORGE_PLUGIN_ID" to pluginId.raw,
        "FORGE_WORKDIR" to workingDirectory,
        "FORGE_PROTOCOL" to protocol,
    ) + environment
}
