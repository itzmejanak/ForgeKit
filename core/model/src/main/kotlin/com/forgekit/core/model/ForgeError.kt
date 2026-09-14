package com.forgekit.core.model

/**
 * Base of the typed ForgeKit error hierarchy (ARCHITECTURE §74).
 * Domain modules define concrete errors extending this (see STRUCTURE.md §8:
 * `PluginManifestError` in plugins/manifest, `RuntimeUnavailableError` in runtime/api, …).
 * User-facing message comes from [message]; technical detail is optional and never
 * rendered as a raw stack trace.
 *
 * Extends [RuntimeException] so typed errors flow through Kotlin's native
 * throw/catch while keeping the structured `message`/`detail` contract.
 */
public abstract class ForgeError(
    public final override val message: String,
    public val detail: String? = null,
) : RuntimeException(message) {
    override fun toString(): String =
        "${this::class.simpleName}: $message" + (detail?.let { " ($it)" } ?: "")
}
