package com.forgekit.app.navigation

import com.forgekit.job.api.JobId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Navigation model: the overlay back stack that fixed the "tab tap does nothing on a
 * detail/run screen" bug. Verifies tab selection clears overlays, push/pop ordering, and
 * that Back (pop) is only offered when an overlay is present.
 */
class ForgeNavStateTest {

    private fun state() = ForgeNavState(initialTab = Tab.HOME, overlays = emptyList())

    @Test
    fun `starts on the initial tab with no overlay`() {
        val nav = state()
        assertEquals(Tab.HOME, nav.tab)
        assertNull(nav.current)
        assertFalse(nav.canPop)
    }

    @Test
    fun `push and pop maintain overlay order`() {
        val nav = state()
        val a = Overlay.JobDetail(JobId("a"))
        val b = Overlay.JobDetail(JobId("b"))
        nav.push(a)
        nav.push(b)
        assertEquals(b, nav.current)
        assertTrue(nav.canPop)
        assertTrue(nav.pop())
        assertEquals(a, nav.current)
        assertTrue(nav.pop())
        assertNull(nav.current)
        assertFalse(nav.pop()) // nothing left to pop → let the system handle Back
    }

    @Test
    fun `selecting a tab clears the overlay stack`() {
        val nav = state()
        nav.push(Overlay.JobDetail(JobId("x")))
        nav.push(Overlay.JobDetail(JobId("y")))
        nav.selectTab(Tab.TERMINAL)
        assertEquals(Tab.TERMINAL, nav.tab)
        assertNull(nav.current) // the exact bug: tab tap now shows the tab, not the overlay
        assertFalse(nav.canPop)
    }

    @Test
    fun `selecting the same tab still clears overlays`() {
        val nav = state()
        nav.push(Overlay.JobDetail(JobId("x")))
        nav.selectTab(Tab.HOME)
        assertEquals(Tab.HOME, nav.tab)
        assertNull(nav.current)
    }
}
