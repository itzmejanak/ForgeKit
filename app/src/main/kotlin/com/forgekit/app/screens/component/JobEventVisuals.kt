package com.forgekit.app.screens.component

import com.forgekit.job.api.JobEvent
import com.forgekit.job.api.JobRecord
import com.forgekit.ui.design.ForgeLogLineVisual
import com.forgekit.ui.design.ForgeLogTone
import java.util.TimeZone

/**
 * One persisted/live [JobEvent] → console line. Channels stay separated by tag and
 * tone (§16/§41): stderr, plugin, provision and stdout never share a color.
 * [index] is the event's position in the job's append-only event list (stable id).
 */
internal fun JobEvent.toLogLine(index: Int, zone: TimeZone = TimeZone.getDefault()): ForgeLogLineVisual {
    val (tag, text, tone) = when (this) {
        is JobEvent.StateChanged -> Triple("STATE", "${from.name.lowercase()} → ${to.name.lowercase()}", ForgeLogTone.MUTED)
        is JobEvent.Progress -> Triple(
            "PROG",
            "${(value * 100).toInt()}%${message?.takeIf(String::isNotBlank)?.let { "  $it" } ?: ""}",
            ForgeLogTone.INFO,
        )
        is JobEvent.PromptRequested -> Triple(
            "INPUT",
            "${prompt.kind.name.lowercase()} · ${prompt.title}",
            ForgeLogTone.WARNING,
        )
        is JobEvent.PromptResolved -> Triple("INPUT", "$promptId · ${status.name.lowercase()}", ForgeLogTone.MUTED)
        is JobEvent.LogLine -> when {
            channel.startsWith("stderr") -> Triple("ERR", line, ForgeLogTone.ERROR)
            channel.startsWith("plugin") -> Triple("PLUGIN", line, ForgeLogTone.ACCENT)
            channel.startsWith("provision") -> Triple("PROV", line, ForgeLogTone.NORMAL)
            else -> Triple("OUT", line, ForgeLogTone.NORMAL)
        }
        is JobEvent.Completed -> Triple("DONE", "completed", ForgeLogTone.SUCCESS)
        is JobEvent.Failed -> Triple("FAIL", reason, ForgeLogTone.ERROR)
        is JobEvent.Cancelled -> Triple("CANCEL", "cancelled", ForgeLogTone.WARNING)
    }
    return ForgeLogLineVisual(
        id = index.toLong(),
        text = stripAnsi(text),
        tone = tone,
        time = clockTime(timestampMillis, zone),
        tag = tag,
    )
}

/**
 * The short outcome shown after a job's state word: the first error line, else the first
 * output entry (`key: value`), else null so no dangling separator is drawn.
 */
internal fun JobRecord.summaryDetail(maxLength: Int = 80): String? {
    error?.lineSequence()?.firstOrNull { it.isNotBlank() }?.let { return it.trim().take(maxLength) }
    return output.entries.firstOrNull()?.let { (key, value) -> "$key: $value".take(maxLength) }
}
