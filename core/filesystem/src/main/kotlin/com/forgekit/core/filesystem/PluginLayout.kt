package com.forgekit.core.filesystem

import java.nio.file.Files
import java.nio.file.Path

/**
 * Layout of one installed plugin (STRUCTURE.md §11.1): manifest, ui, runtime, data, cache, environment, logs.
 * Every plugin is isolated in its own tree; the policy layer grants access per file access grant.
 */
public data class PluginLayout(private val root: Path) {
    public val manifestFile: Path = root.resolve("manifest.json")
    public val uiDir: Path = root.resolve("ui")
    public val runtimeDir: Path = root.resolve("runtime")
    public val dataDir: Path = root.resolve("data")
    public val cacheDir: Path = root.resolve("cache")
    public val environmentDir: Path = root.resolve("environment")
    public val logsDir: Path = root.resolve("logs")
    public val binDir: Path = root.resolve("bin")

    /** Python venv for this plugin (created on demand by the resolver, ARCHITECTURE §21). */
    public val pythonVenv: Path = environmentDir.resolve(".venv")

    /** Node modules for this plugin. */
    public val nodeModules: Path = environmentDir.resolve("node_modules")

    /** Marker written after a successful initialize(). */
    public val readyMarker: Path = root.resolve(".forgekit-ready")

    public val rootPath: Path get() = root

    public fun ensureDirectories() {
        for (d in listOf(uiDir, runtimeDir, dataDir, cacheDir, environmentDir, logsDir, binDir)) {
            Files.createDirectories(d)
        }
    }

    public fun isReady(): Boolean = Files.isRegularFile(readyMarker)

    public fun markReady() {
        Files.writeString(readyMarker, "ready")
    }
}

/**
 * A controlled access grant the policy layer issues when a plugin needs to reach files
 * outside its own tree (ARCHITECTURE §22/§23/§35). The runtime exec layer receives the
 * mapped path; the plugin never roams the device filesystem freely.
 */
public data class FileAccessGrant(
    /** Logical id of the plugin the grant belongs to. */
    val pluginId: String,
    /** Absolute path the plugin may access. */
    val path: Path,
    /** Access mode granted. */
    val mode: Mode,
    /** Expiry in epoch millis; Long.MAX_VALUE = until revoked. */
    val expiresAtMillis: Long,
) {
    public enum class Mode { READ, WRITE, READ_WRITE }

    public fun isExpired(nowMillis: Long): Boolean = nowMillis > expiresAtMillis
}
