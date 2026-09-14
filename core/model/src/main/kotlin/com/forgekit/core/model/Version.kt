package com.forgekit.core.model

/**
 * Semantic version following semver 2.0.0 (MAJOR.MINOR.PATCH, optional pre-release).
 * Comparisons honor pre-release ordering: `1.0.0-rc1 < 1.0.0`.
 */
@JvmInline
public value class Version(public val raw: String) : Comparable<Version> {
    init {
        require(Regex("""^\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$""").matches(raw)) {
            "invalid semantic version: '$raw'"
        }
    }

    public val major: Int get() = raw.substringBefore('.').toInt()
    public val minor: Int get() = raw.substringAfter('.').substringBefore('.').toInt()
    public val patch: Int get() = raw.substringBefore('-').substringAfterLast('.', "0").toInt()

    public val preRelease: String? get() = if ('-' in raw) raw.substringAfter('-') else null

    /** True when this version satisfies the requirement string, e.g. `>=3.11`, `1.0.0`. */
    public fun satisfies(requirement: String): Boolean {
        val trimmed = requirement.trim()
        val match = Regex("""^(>=|<=|>|<|=)?\s*(\d+(?:\.\d+){0,2}(?:-[0-9A-Za-z.-]+)?)$""").find(trimmed)
            ?: return false
        val (op, targetRaw) = match.destructured
        val padded = padToFull(targetRaw)
        val target = Version(padded)
        val cmp = this.compareTo(target)
        return when (op) {
            "" -> cmp == 0
            "=" -> cmp == 0
            ">=" -> cmp >= 0
            "<=" -> cmp <= 0
            ">" -> cmp > 0
            "<" -> cmp < 0
            else -> false
        }
    }

    override fun toString(): String = raw

    override fun compareTo(other: Version): Int {
        val (a, b) = listOf(this, other).map {
            listOf(it.major, it.minor, it.patch)
        }
        for (i in 0..2) {
            if (a[i] != b[i]) return a[i].compareTo(b[i])
        }
        val pa = this.preRelease
        val pb = other.preRelease
        return when {
            pa == null && pb == null -> 0
            // no pre-release outranks pre-release (semver §11)
            pa == null -> 1
            pb == null -> -1
            else -> comparePreRelease(pa, pb)
        }
    }

    private fun comparePreRelease(pa: String, pb: String): Int {
        val idsA = pa.split('.')
        val idsB = pb.split('.')
        val n = minOf(idsA.size, idsB.size)
        for (i in 0 until n) {
            val x = idsA[i]
            val y = idsB[i]
            val nx = x.toIntOrNull()
            val ny = y.toIntOrNull()
            val cmp = when {
                nx != null && ny != null -> nx.compareTo(ny)
                nx != null -> -1 // numeric identifiers always lower than alphanumeric
                ny != null -> 1
                else -> x.compareTo(y)
            }
            if (cmp != 0) return cmp
        }
        return idsA.size.compareTo(idsB.size)
    }

    public companion object {
        internal fun padToFull(v: String): String {
            val core = v.substringBefore('-')
            val pre = if (v.contains('-')) "-" + v.substringAfter('-') else ""
            val parts = core.split('.').toMutableList()
            while (parts.size < 3) parts.add("0")
            return parts.joinToString(".") + pre
        }
    }
}
