package com.forgekit.core.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ForgeContractsTest {
    @Test
    fun `contract strings are stable`() {
        assertEquals("forgekit.plugin/v1", ForgeContracts.MANIFEST_SCHEMA)
        assertEquals("forgekit.ui/v1", ForgeContracts.UI_SCHEMA)
        assertEquals("forgekit/1", ForgeContracts.PROTOCOL_WIRE)
        assertEquals("forgekit.protocol/v1", ForgeContracts.PROTOCOL_SPEC_ID)
        assertEquals("forgekit.runtime/v1", ForgeContracts.RUNTIME_CAPABILITIES)
        assertEquals("forgekit/rules/v1", ForgeContracts.RULES_CONTRACT)
        assertEquals("forgekit/v1", ForgeContracts.RULES_PACKAGE_V1)
    }
}

class ForgeResultTest {
    @Test
    fun `ok carries value`() {
        val r: ForgeResult<Int> = ForgeResult.Ok(42)
        assertTrue(r is ForgeResult.Ok && r.value == 42)
    }

    @Test
    fun `err renders code and message`() {
        val r: ForgeResult<Int> = ForgeResult.Err(ForgeFailure("E_TEST", "boom", "x=1"))
        assertTrue(r is ForgeResult.Err)
        assertEquals("E_TEST: boom (x=1)", r.error.toString())
    }
}
