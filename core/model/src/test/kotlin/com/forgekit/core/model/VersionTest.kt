package com.forgekit.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class VersionTest {
    @Test
    fun `parses valid versions`() {
        assertEquals(1, Version("1.2.3").major)
        assertEquals(2, Version("1.2.3").minor)
        assertEquals(3, Version("1.2.3").patch)
        assertEquals(null, Version("1.2.3").preRelease)
        assertEquals("rc.1", Version("1.0.0-rc.1").preRelease)
    }

    @Test
    fun `rejects invalid versions`() {
        assertFailsWith<IllegalArgumentException> { Version("1.2") }
        assertFailsWith<IllegalArgumentException> { Version("v1.2.3") }
        assertFailsWith<IllegalArgumentException> { Version("") }
        assertFailsWith<IllegalArgumentException> { Version("1.2.3.4") }
    }

    @Test
    fun `orders by core numbers`() {
        assertTrue(Version("1.9.9") < Version("1.10.0"))
        assertTrue(Version("2.0.0") > Version("1.99.99"))
        assertEquals(0, Version("1.2.3").compareTo(Version("1.2.3")))
    }

    @Test
    fun `pre-release ranks below release`() {
        assertTrue(Version("1.0.0-rc.1") < Version("1.0.0"))
        assertTrue(Version("1.0.0-alpha") < Version("1.0.0-beta"))
        assertTrue(Version("1.0.0-2") < Version("1.0.0-10"))
        assertTrue(Version("1.0.0-1") < Version("1.0.0-alpha"))
    }

    @Test
    fun `satisfies requirements`() {
        assertTrue(Version("3.12.1").satisfies(">=3.11"))
        assertTrue(Version("3.10.0").satisfies(">=3.6"))
        assertFalse(Version("3.9.0").satisfies(">=3.11"))
        assertTrue(Version("18.0.0").satisfies(">=18"))
        assertTrue(Version("1.0.0").satisfies("1.0.0"))
        assertTrue(Version("1.0.1").satisfies("=1.0.1"))
        assertTrue(Version("1.0.0").satisfies("<1.1"))
        assertFalse(Version("1.2.0").satisfies("<1.1"))
    }
}

class ExitStatusTest {
    @Test
    fun `fromRaw maps posix codes`() {
        assertTrue(ExitStatus.fromRaw(0) is ExitStatus.Exited && (ExitStatus.fromRaw(0) as ExitStatus.Exited).successful)
        assertTrue(ExitStatus.fromRaw(1).let { it is ExitStatus.Exited && !it.successful })
    }

    @Test
    fun `fromWaitStatus decodes exit and signal`() {
        val exited = ExitStatus.fromWaitStatus(0 shl 8)
        assertTrue(exited is ExitStatus.Exited && exited.successful)
        val failed = ExitStatus.fromWaitStatus((2 shl 8))
        assertTrue(failed is ExitStatus.Exited && (failed as ExitStatus.Exited).code == 2)
        val killed = ExitStatus.fromWaitStatus(9)
        assertTrue(killed is ExitStatus.Signaled && (killed as ExitStatus.Signaled).signal == 9)
    }
}

class ForgeTimestampTest {
    @Test
    fun `compares and formats durations`() {
        assertTrue(ForgeTimestamp.of(10) < ForgeTimestamp.of(20))
        assertEquals("1500ms", ForgeDuration.ofMillis(1500).toString())
        assertEquals(2000L, ForgeDuration.ofSeconds(2).millis)
    }
}
