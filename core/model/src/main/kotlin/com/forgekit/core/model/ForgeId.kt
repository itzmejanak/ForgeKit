package com.forgekit.core.model

import com.forgekit.core.common.HasId

/**
 * Base for all cross-cutting ForgeKit identifiers: non-empty, trimmed, opaque strings.
 *
 * Domain ids (PluginId, JobId, ProcessId, …) wrap this; the wrapper documents what the string means.
 */
@JvmInline
public value class ForgeId(override val id: String) : HasId {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
    }

    override fun toString(): String = id

    public companion object {
        public fun of(id: String): ForgeId = ForgeId(id.trim())
    }
}
