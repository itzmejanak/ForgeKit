package com.forgekit.app.screens.component

import com.forgekit.job.api.JobEvent
import com.forgekit.job.api.JobId
import com.forgekit.job.api.JobRecord
import com.forgekit.job.api.JobState
import com.forgekit.ui.design.ForgeLogTone
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class JobEventVisualsTest {

    private val utc = TimeZone.getTimeZone("UTC")
    private val job = JobId("job-1")

    @Test
    fun `channels are separated by tag and tone`() {
        val err = JobEvent.LogLine(job, "boom", "stderr", 0).toLogLine(0, utc)
        assertEquals("ERR" to ForgeLogTone.ERROR, err.tag to err.tone)
        val plugin = JobEvent.LogLine(job, "hi", "plugin", 0).toLogLine(1, utc)
        assertEquals("PLUGIN" to ForgeLogTone.ACCENT, plugin.tag to plugin.tone)
        val provision = JobEvent.LogLine(job, "Get:1", "provision", 0).toLogLine(2, utc)
        assertEquals("PROV", provision.tag)
        val out = JobEvent.LogLine(job, "ok", "stdout", 0).toLogLine(3, utc)
        assertEquals("OUT" to ForgeLogTone.NORMAL, out.tag to out.tone)
    }

    @Test
    fun `color escape codes are stripped from console lines`() {
        val esc = ""
        val bel = ""
        val line = JobEvent.LogLine(job, "$esc[1;31mERROR$esc[0m: $esc[38;2;255;0;0mbad$esc[0m$esc]0;title$bel", "stdout", 0)
        assertEquals("ERROR: bad", line.toLogLine(0, utc).text)
        assertEquals("plain [31m text", stripAnsi("plain [31m text"), "text without ESC is untouched")
        assertEquals("Downloading 45%", stripAnsi("$esc[2K$esc[32mDownloading$esc[0m 45%"))
    }

    @Test
    fun `lifecycle events`() {
        val state = JobEvent.StateChanged(job, JobState.QUEUED, JobState.RUNNING, 0).toLogLine(4, utc)
        assertEquals("queued → running", state.text)
        assertEquals(4L, state.id)
        val progress = JobEvent.Progress(job, 0.42, "halfway", 0).toLogLine(5, utc)
        assertEquals("42%  halfway", progress.text)
        val failed = JobEvent.Failed(job, "exit 1", null, 0).toLogLine(6, utc)
        assertEquals("FAIL" to ForgeLogTone.ERROR, failed.tag to failed.tone)
        assertEquals("00:00:00", failed.time)
    }

    @Test
    fun `summary detail prefers the first error line, then the first output, else nothing`() {
        val base = JobRecord(id = job, pluginId = "com.example.tool", action = "run", state = JobState.COMPLETED, input = emptyMap(), createdAtMillis = 0)
        assertEquals(null, base.summaryDetail())
        assertEquals("provisioned: ok", base.copy(output = mapOf("provisioned" to "ok")).summaryDetail())
        assertEquals(
            "dependency provisioning failed",
            base.copy(state = JobState.FAILED, error = "\ndependency provisioning failed\nmore", output = mapOf("a" to "b")).summaryDetail(),
        )
    }

    @Test
    fun `compact time shortens by distance from now`() {
        val now = 1_789_000_000_000L // 2026-09-10 00:26:40 UTC
        assertEquals("00:10", compactTime(now - 16 * 60_000L - 40_000L, now, utc))
        assertEquals("Sep 9, 00:26", compactTime(now - 24 * 3_600_000L, now, utc))
        assertEquals("2025-09-10", compactTime(now - 365L * 24 * 3_600_000L, now, utc))
    }

    @Test
    fun `date time formatting`() {
        assertEquals("1970-01-01 01:02", dateTime(3_723_000, utc))
    }
}
