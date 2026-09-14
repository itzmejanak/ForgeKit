package com.forgekit.plugin.api

import com.forgekit.core.model.ForgeId
import com.forgekit.core.model.ForgeError
import com.forgekit.core.model.Version

/** Plugin-domain errors (ARCHITECTURE §74 taxonomy). */
public open class PluginError(message: String, detail: String? = null) : ForgeError(message, detail)

/**
 * Reverse-domain plugin identifier (e.g. `com.example.tool`).
 *
 * Ids are validated ONCE at the type boundary so every later layer (filesystem
 * layout, protocol routing, registry keys) can trust the format.
 */
@JvmInline
public value class PluginId(public val raw: String) : Comparable<PluginId> {

    init {
        val pattern = Regex("""^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*){1,}$""")
        require(pattern.matches(raw)) {
            "plugin id must be reverse-domain, lowercase (min two segments): '$raw'"
        }
    }

    override fun toString(): String = raw

    override fun compareTo(other: PluginId): Int = raw.compareTo(other.raw)

    public companion object {
        /** Parses or throws [PluginError] with the precise violation. */
        public fun parse(value: String): PluginId = try {
            PluginId(value.trim())
        } catch (e: IllegalArgumentException) {
            throw PluginError("invalid plugin id '$value'", e.message)
        }
    }
}

/** Plugin version — semver with the core [Version] type. */
@JvmInline
public value class PluginVersion(public val value: Version) : Comparable<PluginVersion> {

    public val raw: String get() = value.raw

    override fun toString(): String = value.raw

    override fun compareTo(other: PluginVersion): Int = value.compareTo(other.value)

    public companion object {
        public fun parse(raw: String): PluginVersion = try {
            PluginVersion(Version(raw.trim()))
        } catch (e: IllegalArgumentException) {
            throw PluginError("invalid plugin version '$raw'", e.message)
        }
    }
}

/**
 * Plugin lifecycle status (STRUCTURE.md plugins/manager: IMPORT → REMOVE).
 *
 * UNRESOLVED marks imported-but-not-yet-provisioned plugins (dependencies or
 * user approval pending) — the exact state the reference UI shows on the
 * plugin cards.
 */
public enum class PluginStatus {
    /** Received from a source, quarantined; nothing validated yet. */
    QUARANTINED,

    /** Validated + signed/quarantine policy decided, awaiting user approval or dependency provisioning. */
    UNRESOLVED,

    /** Fully validated and approved; runtime dependencies present. */
    READY,

    /** Explicitly disabled by the user; never auto-started. */
    DISABLED,

    /** Failed during initialization/run; carries diagnostics. */
    ERROR,

    /** Removed from the registry (terminal). */
    REMOVED,
}

/** How much the platform trusts an installed plugin (mirrors core/security levels). */
public enum class PluginTrustLevel {
    SIGNED_TRUSTED,
    SIGNED_UNKNOWN,
    UNSIGNED,
}

/**
 * A capability a plugin exposes to the platform: runnable actions, declarative
 * UI, terminal access, artifacts — discovered from the validated manifest,
 * never self-declared at runtime.
 */
public data class PluginCapability(
    /** Capability kind, e.g. `action`, `ui`, `terminal`. */
    public val kind: String,
    /** Stable name inside the plugin, e.g. action id. */
    public val name: String,
    /** Human title shown in UI. */
    public val title: String?,
    public val description: String?,
)

/**
 * The installed view of a plugin: validated manifest + lifecycle state + where
 * it lives on disk. This is what the registry persists and the UI renders.
 */
public data class PluginDescriptor(
    public val id: PluginId,
    public val version: PluginVersion,
    public val name: String,
    public val description: String?,
    public val status: PluginStatus,
    public val trust: PluginTrustLevel,
    public val publisherKeyFingerprint: String?,
    /** Package-level SHA-256 of the .forge archive as imported. */
    public val packageSha256: String?,
    /** Path of the installed plugin tree (PluginLayout root). */
    public val installedPath: String?,
    public val runtimeType: String,
    public val runtimeVersionRequirement: String?,
    public val capabilities: List<PluginCapability>,
    public val requestedPermissions: List<String>,
    public val tags: List<String> = emptyList(),
    public val installedAtMillis: Long,
    public val updatedAtMillis: Long,
)
