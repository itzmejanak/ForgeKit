package com.forgekit.core.common

/**
 * Canonical ForgeKit contract identifiers (STRUCTURE.md §20, ARCHITECTURE §81).
 *
 * Every versioned contract string used on the wire or in schemas is defined here exactly once
 * so that JVM modules (plugins, tools) and Android modules (ui, app) single-source the values.
 * Recorded as ADR-0010 (reconciliation: constants centralized in `core/common`).
 */
public object ForgeContracts {
    /** Manifest schema id, owner: `plugins/manifest`. */
    public const val MANIFEST_SCHEMA: String = "forgekit.plugin/v1"

    /** Declarative UI schema id, owner: `ui/design`. */
    public const val UI_SCHEMA: String = "forgekit.ui/v1"

    /** Plugin protocol wire value (JSON `"protocol"` field). */
    public const val PROTOCOL_WIRE: String = "forgekit/1"

    /** Plugin protocol spec identifier (documents, validator reports). */
    public const val PROTOCOL_SPEC_ID: String = "forgekit.protocol/v1"

    /** Runtime capabilities contract id, owner: `runtime/api`. */
    public const val RUNTIME_CAPABILITIES: String = "forgekit.runtime/v1"

    /** Rules development contract id. */
    public const val RULES_CONTRACT: String = "forgekit/rules/v1"

    /** Rules contract value declared by a plugin package manifest (`"rules": "forgekit/v1"`). */
    public const val RULES_PACKAGE_V1: String = "forgekit/v1"
}

/** Result of a ForgeKit operation that can fail with a typed failure instead of throwing in hot paths. */
public sealed interface ForgeResult<out T> {
    public data class Ok<T>(val value: T) : ForgeResult<T>
    public data class Err(val error: ForgeFailure) : ForgeResult<Nothing>
}

/** Lightweight failure descriptor: typed code + message + optional detail. */
public data class ForgeFailure(
    val code: String,
    val message: String,
    val detail: String? = null,
) {
    override fun toString(): String =
        if (detail == null) "$code: $message" else "$code: $message ($detail)"
}
