package com.forgekit.core.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ForgeLoggerTest {
    @Test
    fun `logs flow through sink with category`() {
        val entries = mutableListOf<LogEntry>()
        val logger = ForgeLogger(LogCategory.RUNTIME, sink = { entries += it })
        logger.info("Bootstrap", "extracting bootstrap", mapOf("abi" to "arm64-v8a"))
        assertEquals(1, entries.size)
        assertEquals(LogCategory.RUNTIME, entries[0].category)
        assertEquals(LogLevel.INFO, entries[0].level)
        assertEquals("arm64-v8a", entries[0].data["abi"])
    }

    @Test
    fun `min level filters debug`() {
        val entries = mutableListOf<LogEntry>()
        val logger = ForgeLogger(LogCategory.APP, sink = { entries += it }, minLevel = LogLevel.WARN)
        logger.debug("t", "hidden")
        logger.warn("t", "shown")
        assertEquals(1, entries.size)
        assertEquals("shown", entries[0].message)
    }
}

class SecretRedactorTest {
    @Test
    fun `registered secrets are masked`() {
        val redactor = SecretRedactor(listOf("hunter2supersecret"))
        val out = redactor.redact("password was hunter2supersecret indeed")
        assertFalse(out.contains("hunter2supersecret"))
        assertTrue(out.contains("***"))
    }

    @Test
    fun `key-value patterns are masked without registration`() {
        val redactor = SecretRedactor.default()
        val out = redactor.redact("""{"api_key": "abcd-1234-secret"}""")
        assertFalse(out.contains("abcd-1234-secret"))
    }

    @Test
    fun `short values are not masked to avoid clobbering`() {
        val redactor = SecretRedactor(listOf("abc"))
        assertEquals("abc def", redactor.redact("abc def"))
    }
}
