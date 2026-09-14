package com.forgekit.core.common

/** Milliseconds since Unix epoch (UTC). Always explicit — never `System.currentTimeMillis()` inline in domain code. */
public fun interface Clock {
    public fun nowMillis(): Long
}

/** Production clock. */
public object SystemClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

/** Cross-cutting identifier contract for entities that carry a stable string id. */
public interface HasId {
    public val id: String
}
