package com.forgekit.core.security

import com.forgekit.core.logging.ForgeLogger
import com.forgekit.core.logging.LogCategory

/**
 * The ForgeKit permission catalog and policy engine (ARCHITECTURE §21-25).
 *
 * Manifest `permissions` are REQUESTS. This engine — not the manifest — decides
 * what a request means, how risky it is, and whether it can ever be granted.
 * Runtime permissions are enforced at the execution boundary (FileAccessGrant,
 * execution policy), never by the plugin.
 */
public object PermissionCatalog {

    /** Permission identity + risk classification (§25: user must approve risky packages). */
    public data class PermissionInfo(
        val id: String,
        val risk: Risk,
        val description: String,
        /** Android runtime permission bridged by platform/permissions, or null. */
        val androidPermission: String?,
    )

    public enum class Risk { NORMAL, DANGEROUS, SYSTEM }

    public val NETWORK: PermissionInfo =
        PermissionInfo("network", Risk.DANGEROUS, "Outbound network access", "android.permission.INTERNET")

    public val FILES_READ: PermissionInfo =
        PermissionInfo("files.read", Risk.NORMAL, "Read files inside the plugin's own sandbox", null)

    public val FILES_WRITE: PermissionInfo =
        PermissionInfo("files.write", Risk.NORMAL, "Write files inside the plugin's own sandbox", null)

    public val ARTIFACT_READ: PermissionInfo =
        PermissionInfo("artifact.read", Risk.NORMAL, "Read job artifacts", null)

    public val ARTIFACT_WRITE: PermissionInfo =
        PermissionInfo("artifact.write", Risk.NORMAL, "Write job artifacts", null)

    public val UI_PROGRESS: PermissionInfo =
        PermissionInfo("ui.progress", Risk.NORMAL, "Emit progress events to the running job's UI", null)

    public val NETWORK_INSTALL: PermissionInfo =
        PermissionInfo("network.install", Risk.DANGEROUS, "Install packages from the network (pkg/pip/npm)", null)

    public val PACKAGE_INSTALL: PermissionInfo =
        PermissionInfo("package.install", Risk.DANGEROUS, "Modify the runtime package database", null)

    public val TERMINAL_ACCESS: PermissionInfo =
        PermissionInfo("terminal.access", Risk.DANGEROUS, "Open an interactive terminal session", null)

    public val RUNTIME_EXEC: PermissionInfo =
        PermissionInfo("runtime.exec", Risk.SYSTEM, "Execute arbitrary binaries in the runtime", null)

    public val STORAGE_ALL: PermissionInfo =
        PermissionInfo("storage.all", Risk.SYSTEM, "Whole shared-storage access (bypass sandbox)", null)

    private val all: List<PermissionInfo> = listOf(
        NETWORK, FILES_READ, FILES_WRITE, ARTIFACT_READ, ARTIFACT_WRITE, UI_PROGRESS,
        NETWORK_INSTALL, PACKAGE_INSTALL, TERMINAL_ACCESS, RUNTIME_EXEC, STORAGE_ALL,
    )

    private val byId: Map<String, PermissionInfo> = all.associateBy { it.id }

    /** Catalog lookup; unknown ids are policy failures, not silent passes. */
    public fun find(id: String): PermissionInfo? = byId[id]

    public fun catalog(): List<PermissionInfo> = all

    /** Classifies a manifest permission list; unknown permissions are reported. */
    public fun classify(requested: List<String>): PermissionClassification {
        val known = mutableListOf<PermissionInfo>()
        val unknown = mutableListOf<String>()
        for (id in requested) {
            val info = byId[id]
            if (info == null) unknown += id else known += info
        }
        val maxRisk = known.maxOfOrNull { it.risk } ?: Risk.NORMAL
        val requiresApproval = unknown.isNotEmpty() || maxRisk >= Risk.DANGEROUS
        return PermissionClassification(known, unknown, maxRisk, requiresApproval)
    }
}

/** Outcome of classifying a plugin's requested permission set. */
public data class PermissionClassification(
    val known: List<PermissionCatalog.PermissionInfo>,
    val unknown: List<String>,
    val maxRisk: PermissionCatalog.Risk,
    /** §25: when true the import MUST show the approval UI before registration. */
    val requiresApproval: Boolean,
) {
    public val grantable: Boolean get() = unknown.isEmpty()

    /** Deterministic summary for the import review card (provision permissions list). */
    public fun summary(): String = buildString {
        append("max risk: ")
        append(
            when (maxRisk) {
                PermissionCatalog.Risk.NORMAL -> "normal"
                PermissionCatalog.Risk.DANGEROUS -> "dangerous"
                PermissionCatalog.Risk.SYSTEM -> "system"
            },
        )
        if (unknown.isNotEmpty()) append("; unknown: ${unknown.joinToString(", ")}")
    }
}

/**
 * Append-only security audit trail (§74-adjacent: every security-relevant
 * decision is recorded with its inputs). In-memory by design; persistence
 * lands with the Room store (core/database) as an additional sink.
 */
public class SecurityAuditLog(
    private val logger: ForgeLogger = ForgeLogger(LogCategory.SECURITY, {}),
    private val maxEntries: Int = 2048,
) {
    public data class AuditEntry(
        val timestampMillis: Long,
        val event: String,
        val subject: String,
        val decision: String,
        val detail: String? = null,
    )

    private val entries = ArrayDeque<AuditEntry>(maxEntries)

    public fun record(event: String, subject: String, decision: String, detail: String? = null) {
        val entry = AuditEntry(
            timestampMillis = System.currentTimeMillis(),
            event = event,
            subject = subject,
            decision = decision,
            detail = detail,
        )
        synchronized(entries) {
            entries.addLast(entry)
            if (entries.size > maxEntries) entries.removeFirst()
        }
        logger.info(event, "$subject → $decision${detail?.let { " ($it)" } ?: ""}")
    }

    public fun all(): List<AuditEntry> = synchronized(entries) { entries.toList() }

    public fun clear(): Unit = synchronized(entries) { entries.clear() }
}
