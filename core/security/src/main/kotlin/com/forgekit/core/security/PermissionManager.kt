package com.forgekit.core.security

import com.forgekit.core.model.ForgeError
import java.util.concurrent.ConcurrentHashMap

/**
 * The runtime permission decision engine (ARCHITECTURE §23/§24: manifest
 * permissions are REQUESTS; this engine owns the grant state).
 *
 * Grant lifecycle: a permission exists for a plugin only after an explicit
 * approval (§25 approval gate at import, or a later user action), and can
 * be revoked at any time. Every transition is audited (§80: permission
 * granted / revoked / signature failure …) — never silently.
 *
 * Enforcement points (filesystem policy, execution policy, protocol gates)
 * all ask [isGranted]; they NEVER read the manifest as an authority.
 */
public class PermissionManager(
    private val audit: SecurityAuditLog = SecurityAuditLog(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** One granted permission for one plugin. */
    public data class Grant(
        val pluginId: String,
        val permission: String,
        val risk: PermissionCatalog.Risk,
        val grantedAtMillis: Long,
        /** Long.MAX_VALUE = until revoked. */
        val expiresAtMillis: Long,
    ) {
        public val expired: Boolean get() = System.currentTimeMillis() > expiresAtMillis
    }

    /** Records of the approval decision trail. */
    private val grants = ConcurrentHashMap<String, MutableMap<String, Grant>>()

    // ---- approval + grant ------------------------------------------------------

    /**
     * Records the user's approval of [permissions] for [pluginId].
     * Unknown (non-catalog) permissions are REFUSED — the catalog is the
     * only source of what exists, so unknown ids can never become grants.
     */
    public fun approve(pluginId: String, permissions: List<String>): List<Grant> {
        val granted = mutableListOf<Grant>()
        for (id in permissions) {
            val info = PermissionCatalog.find(id)
                ?: throw SecurityViolation("unknown permission '$id' can never be granted", pluginId)
            val grant = Grant(
                pluginId = pluginId,
                permission = id,
                risk = info.risk,
                grantedAtMillis = clock(),
                expiresAtMillis = Long.MAX_VALUE,
            )
            grantsFor(pluginId)[id] = grant
            granted += grant
            audit.record("permission", pluginId, "GRANTED", "$id (${info.risk})")
        }
        return granted
    }

    /**
     * Grants a NORMAL-risk permission implicitly (sandbox defaults that need
     * no approval step). DANGEROUS/SYSTEM permissions are never implicit.
     */
    public fun grantDefaults(pluginId: String): List<Grant> {
        val defaults = listOf(
            PermissionCatalog.FILES_READ, PermissionCatalog.FILES_WRITE,
            PermissionCatalog.ARTIFACT_WRITE, PermissionCatalog.UI_PROGRESS,
        )
        return approve(pluginId, defaults.map { it.id })
    }

    /** Revokes one permission; revoking a non-grant is a no-op that still audits. */
    public fun revoke(pluginId: String, permission: String) {
        val removed = grantsFor(pluginId).remove(permission)
        audit.record(
            "permission", pluginId,
            if (removed != null) "REVOKED" else "REVOKED_NOOP",
            permission,
        )
    }

    /** Removes every grant for the plugin (uninstall). */
    public fun revokeAll(pluginId: String) {
        val removed = grants.remove(pluginId)
        audit.record(
            "permission", pluginId, "REVOKED_ALL",
            "${removed?.size ?: 0} grant(s)",
        )
    }

    // ---- enforcement queries ------------------------------------------------------

    /** The enforcement question: may [pluginId] exercise [permission] right now? */
    public fun isGranted(pluginId: String, permission: String): Boolean {
        val grant = grantsFor(pluginId)[permission] ?: return false
        if (grant.expiresAtMillis != Long.MAX_VALUE && clock() > grant.expiresAtMillis) {
            return false // expired grants answer NO (lazily; cleanup on read)
        }
        return true
    }

    /**
     * Guard for execution paths: throws [SecurityViolation] listing every
     * missing permission when the plugin tries to exercise [permissions].
     */
    public fun require(pluginId: String, vararg permissions: String) {
        val missing = permissions.filter { !isGranted(pluginId, it) }
        if (missing.isNotEmpty()) {
            throw SecurityViolation(
                "plugin '$pluginId' lacks required permissions: ${missing.joinToString(", ")}",
                "grant state: ${grantsFor(pluginId).keys.sorted().joinToString(", ").ifBlank { "none" }}",
            )
        }
    }

    /** All live grants for the plugin (UI: plugin detail permissions card). */
    public fun grantsOf(pluginId: String): List<Grant> =
        grantsFor(pluginId).values.filter { !it.expired }.sortedBy { it.permission }

    // ---- persistence ------------------------------------------------------------------

    /** Serializable grant state (§61 backup; the app layer stores it in Room). */
    public fun snapshot(): List<Grant> {
        val all = mutableListOf<Grant>()
        for (perPlugin in grants.values) {
            all.addAll(perPlugin.values)
        }
        return all.sortedWith(compareBy<Grant> { it.pluginId }.thenBy { it.permission })
    }

    /** Restores a snapshot (device migration / restart). Idempotent. */
    public fun restore(snapshot: List<Grant>) {
        for (grant in snapshot) {
            grantsFor(grant.pluginId)[grant.permission] = grant
        }
    }

    /** Renders the import-review runtime permissions list deterministically. */
    public fun describeGrantState(pluginId: String, requested: List<String>): String =
        requested.joinToString("\n") { id ->
            val info = PermissionCatalog.find(id)
            val state = when {
                info == null -> "UNKNOWN"
                isGranted(pluginId, id) -> "GRANTED"
                info.risk == PermissionCatalog.Risk.NORMAL -> "DEFAULT"
                else -> "NOT GRANTED"
            }
            "$id $state"
        }

    private fun grantsFor(pluginId: String): MutableMap<String, Grant> =
        grants.getOrPut(pluginId) { ConcurrentHashMap() }
}

/** Typed policy violation (enforcement paths throw this — §24: never a silent pass). */
public class SecurityViolation(message: String, detail: String? = null) : ForgeError(message, detail)
