package com.forgekit.core.logging

/**
 * Redacts secret values before anything is written to logs (ARCHITECTURE §42).
 * Secrets must never appear in UI logs, stdout, job history or crash reports.
 */
public class SecretRedactor(
    private val secretValues: List<String>,
) {
    /** Generic sensitive key names whose *values* get masked even when the raw secret is not registered. */
    private val sensitiveKeyPattern = Regex(
        """(?i)("(?:api[_-]?key|token|secret|password|passwd|authorization|credential|private[_-]?key)"\s*[:=]\s*")([^"]*)(")"""
    )

    public fun redact(text: String): String {
        var out = text
        for (secret in secretValues) {
            if (secret.length >= MIN_REDACTABLE_LENGTH) {
                out = out.replace(secret, MASK)
            }
        }
        out = sensitiveKeyPattern.replace(out) { m ->
            m.groupValues[1] + MASK + m.groupValues[3]
        }
        return out
    }

    public companion object {
        private const val MASK = "***"
        private const val MIN_REDACTABLE_LENGTH = 4

        /** Redactor that only applies pattern-based masking. */
        public fun default(): SecretRedactor = SecretRedactor(emptyList())
    }
}

/** Registry of live secret values that must be scrubbed from logs of this session. */
public class SecretRegistry {
    private val secrets = mutableSetOf<String>()

    public fun register(secret: String) {
        if (secret.length >= 4) secrets += secret
    }

    public fun redactor(): SecretRedactor = SecretRedactor(secrets.toList())

    public fun clear(): Unit {
        secrets.clear()
    }
}
