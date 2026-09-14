package com.forgekit.core.model

/** Point in time, milliseconds since Unix epoch, UTC. */
@JvmInline
public value class ForgeTimestamp(public val epochMillis: Long) : Comparable<ForgeTimestamp> {
    override fun compareTo(other: ForgeTimestamp): Int = epochMillis.compareTo(other.epochMillis)

    override fun toString(): String = epochMillis.toString()

    public companion object {
        public fun of(epochMillis: Long): ForgeTimestamp = ForgeTimestamp(epochMillis)
    }
}

/** Duration in milliseconds (job runtime, grace periods, …). */
@JvmInline
public value class ForgeDuration(public val millis: Long) : Comparable<ForgeDuration> {
    public val seconds: Double get() = millis / 1000.0

    override fun compareTo(other: ForgeDuration): Int = millis.compareTo(other.millis)

    public operator fun times(factor: Long): ForgeDuration = ForgeDuration(millis * factor)

    override fun toString(): String = "${millis}ms"

    public companion object {
        public fun ofSeconds(seconds: Long): ForgeDuration = ForgeDuration(seconds * 1000)
        public fun ofMillis(millis: Long): ForgeDuration = ForgeDuration(millis)
    }
}
