package com.forgekit.runtime.termux

import com.forgekit.runtime.api.InstallPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure classifier tests: REAL apt/pkg/pip marker lines must map to the phase
 * the UI surfaces. No fakes — these are the actual words the package managers
 * print.
 */
class InstallOutputParserTest {

    // ---- pip markers -------------------------------------------------------

    @Test
    fun `pip download phase`() {
        assertEquals(InstallPhase.FETCHING, InstallOutputParser.classify("Collecting r2pipe", InstallPhase.INSPECTING))
        assertEquals(InstallPhase.FETCHING, InstallOutputParser.classify("Downloading r2pipe-1.9.8-py3-none-any.whl", InstallPhase.FETCHING))
    }

    @Test
    fun `pip build and unpack phase`() {
        assertEquals(InstallPhase.UNPACKING, InstallOutputParser.classify("Preparing metadata (pyproject.toml)", InstallPhase.FETCHING))
        assertEquals(InstallPhase.UNPACKING, InstallOutputParser.classify("Building wheel for r2pipe", InstallPhase.FETCHING))
        assertEquals(InstallPhase.UNPACKING, InstallOutputParser.classify("Running setup.py install", InstallPhase.FETCHING))
    }

    @Test
    fun `pip configure phase`() {
        assertEquals(InstallPhase.CONFIGURING, InstallOutputParser.classify("Installing collected packages: r2pipe", InstallPhase.UNPACKING))
        assertEquals(InstallPhase.CONFIGURING, InstallOutputParser.classify("Successfully installed r2pipe-1.9.8", InstallPhase.UNPACKING))
    }

    @Test
    fun `unrelated pip line stays in the current phase`() {
        assertEquals(InstallPhase.FETCHING, InstallOutputParser.classify("Using cached r2pipe-1.9.8", InstallPhase.FETCHING))
        assertEquals(InstallPhase.INSPECTING, InstallOutputParser.classify("", InstallPhase.INSPECTING))
    }

    // ---- apt / pkg markers --------------------------------------------------

    @Test
    fun `apt fetch phase`() {
        assertEquals(InstallPhase.FETCHING, InstallOutputParser.classify("Get:1 https://pkg.termux.dev ...", InstallPhase.INSPECTING))
        assertEquals(InstallPhase.FETCHING, InstallOutputParser.classify("Fetched 12.3 MB in 5s (2.4 MB/s)", InstallPhase.UNPACKING))
    }

    @Test
    fun `apt unpack phase`() {
        assertEquals(InstallPhase.UNPACKING, InstallOutputParser.classify("Selecting previously unselected package radare2.", InstallPhase.FETCHING))
        assertEquals(InstallPhase.UNPACKING, InstallOutputParser.classify("(Reading database ... 5 files and directories currently installed.)", InstallPhase.FETCHING))
        assertEquals(InstallPhase.UNPACKING, InstallOutputParser.classify("Preparing to unpack .../radare2_5.9.0_aarch64.deb ...", InstallPhase.FETCHING))
        assertEquals(InstallPhase.UNPACKING, InstallOutputParser.classify("Unpacking radare2 (5.9.0) ...", InstallPhase.FETCHING))
    }

    @Test
    fun `apt configure phase`() {
        assertEquals(InstallPhase.CONFIGURING, InstallOutputParser.classify("Setting up radare2 (5.9.0) ...", InstallPhase.UNPACKING))
        assertEquals(InstallPhase.CONFIGURING, InstallOutputParser.classify("Processing triggers for man-db ...", InstallPhase.UNPACKING))
    }

    // ---- status-line gating --------------------------------------------------

    @Test
    fun `status lines exclude blank long and http fetches`() {
        assertFalse(InstallOutputParser.isStatusLine(""))
        assertFalse(InstallOutputParser.isStatusLine("a".repeat(200)))
        assertFalse(InstallOutputParser.isStatusLine("Get:1 https://pkg.termux.dev/hermes"))
    }

    @Test
    fun `meaningful install lines surface as status`() {
        assertTrue(InstallOutputParser.isStatusLine("Setting up radare2 (5.9.0) ..."))
        assertTrue(InstallOutputParser.isStatusLine("Successfully installed r2pipe-1.9.8"))
        assertTrue(InstallOutputParser.isStatusLine("W: apt cache update required"))
    }
}