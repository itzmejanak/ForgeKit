package com.forgekit.core.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * REAL policy tests over the real catalog and real audit log: every grant,
 * revocation and refusal is decided by the same engine the runtime uses.
 */
class PermissionManagerTest {

    private val plugin = "com.example.tool"

    @Test
    fun `nothing is granted by default`() {
        val manager = PermissionManager()
        assertFalse(manager.isGranted(plugin, PermissionCatalog.NETWORK.id))
        assertFalse(manager.isGranted(plugin, PermissionCatalog.FILES_READ.id))
        assertEquals(emptyList(), manager.grantsOf(plugin))
    }

    @Test
    fun `approval grants exactly the approved set`() {
        val manager = PermissionManager()
        manager.approve(plugin, listOf("network", "files.read"))

        assertTrue(manager.isGranted(plugin, "network"))
        assertTrue(manager.isGranted(plugin, "files.read"))
        assertFalse(manager.isGranted(plugin, "package.install"), "approval never over-grants")
        assertEquals(2, manager.grantsOf(plugin).size)
    }

    @Test
    fun `unknown permissions are refused loudly`() {
        val manager = PermissionManager()
        val violation = assertFailsWith<SecurityViolation> {
            manager.approve(plugin, listOf("filesystem.root"))
        }
        assertTrue("can never be granted" in violation.message)
        assertFalse(manager.isGranted(plugin, "filesystem.root"))
    }

    @Test
    fun `sandbox defaults are grantable without approval`() {
        val manager = PermissionManager()
        manager.grantDefaults(plugin)
        assertTrue(manager.isGranted(plugin, "files.read"))
        assertTrue(manager.isGranted(plugin, "files.write"))
        assertTrue(manager.isGranted(plugin, "artifact.write"))
        assertFalse(manager.isGranted(plugin, "network"), "dangerous stays opt-in")
        assertFalse(manager.isGranted(plugin, "runtime.exec"), "system stays opt-in")
    }

    @Test
    fun `revoke takes effect immediately and audits a noop honestly`() {
        val manager = PermissionManager()
        manager.approve(plugin, listOf("network"))
        manager.revoke(plugin, "network")
        assertFalse(manager.isGranted(plugin, "network"))

        manager.revoke(plugin, "network") // second revoke: noop, still audited
        val revokeEvents = auditEntries(manager).filter {
            it.event == "permission" && it.subject == plugin &&
                (it.decision == "REVOKED" || it.decision == "REVOKED_NOOP")
        }
        assertEquals(2, revokeEvents.size, revokeEvents.joinToString { it.decision })
        assertEquals("REVOKED", revokeEvents[0].decision)
        assertEquals("REVOKED_NOOP", revokeEvents[1].decision)
    }

    @Test
    fun `require throws with the precise missing set`() {
        val manager = PermissionManager()
        manager.approve(plugin, listOf("files.read"))
        val violation = assertFailsWith<SecurityViolation> {
            manager.require(plugin, "files.read", "network", "terminal.access")
        }
        assertTrue("network" in violation.message)
        assertTrue("terminal.access" in violation.message)
        assertTrue("files.read" !in violation.message)
        assertTrue("grant state: files.read" in (violation.detail ?: ""), violation.detail)
    }

    @Test
    fun `revokeAll wipes the plugin on uninstall`() {
        val manager = PermissionManager()
        manager.approve(plugin, listOf("network", "files.read"))
        manager.approve("other.plugin", listOf("files.read"))
        manager.revokeAll(plugin)
        assertEquals(emptyList(), manager.grantsOf(plugin))
        assertTrue(manager.isGranted("other.plugin", "files.read"), "other plugins unaffected")
    }

    @Test
    fun `expired grants answer no`() {
        val manager = PermissionManager()
        manager.approve(plugin, listOf("network"))
        val expired = manager.grantsOf(plugin).first().copy(
            expiresAtMillis = System.currentTimeMillis() - 1,
        )
        manager.revokeAll(plugin)
        manager.restore(listOf(expired))
        assertFalse(manager.isGranted(plugin, "network"))
    }

    @Test
    fun `snapshot and restore survive restarts deterministically`() {
        val first = PermissionManager()
        first.approve(plugin, listOf("network", "package.install"))
        first.approve("other.plugin", listOf("files.read"))
        val snapshot = first.snapshot()

        val second = PermissionManager()
        second.restore(snapshot)
        assertEquals(3, second.snapshot().size)
        assertTrue(second.isGranted(plugin, "network"))
        assertTrue(second.isGranted(plugin, "package.install"))
        assertTrue(second.isGranted("other.plugin", "files.read"))
        assertEquals(first.snapshot(), second.snapshot())
    }

    @Test
    fun `every security-sensitive transition lands in the audit log`() {
        val manager = PermissionManager()
        manager.approve(plugin, listOf("network"))
        manager.revoke(plugin, "network")
        manager.revokeAll(plugin)

        val entries = auditEntries(manager)
        assertTrue(entries.any { it.decision == "GRANTED" && it.detail?.contains("network (DANGEROUS)") == true }, entries.toString())
        assertTrue(entries.any { it.decision == "REVOKED" && it.detail == "network" }, entries.toString())
        assertTrue(entries.any { it.decision == "REVOKED_ALL" }, entries.toString())
    }

    @Test
    fun `grant state description feeds the import review card`() {
        val manager = PermissionManager()
        manager.grantDefaults(plugin)
        val description = manager.describeGrantState(
            plugin,
            listOf("files.read", "network", "made.up.permission"),
        )
        assertTrue("files.read GRANTED" in description, description)
        assertTrue("network NOT GRANTED" in description, description)
        assertTrue("made.up.permission UNKNOWN" in description, description)
    }

    // ---- helpers -------------------------------------------------------------

    private fun auditEntries(manager: PermissionManager): List<SecurityAuditLog.AuditEntry> {
        val field = PermissionManager::class.java.getDeclaredField("audit")
        field.isAccessible = true
        return (field.get(manager) as SecurityAuditLog).all()
    }
}
