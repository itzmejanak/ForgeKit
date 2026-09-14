package com.forgekit.app.screens.component

import java.util.Calendar
import java.util.TimeZone

/** Wall-clock formatting for logs and job audit rows (API 24 safe: no java.time). */

/** `HH:mm:ss` — console line prefix. */
internal fun clockTime(millis: Long, zone: TimeZone = TimeZone.getDefault()): String {
    val cal = calendarOf(millis, zone)
    return "${pad(cal.get(Calendar.HOUR_OF_DAY))}:${pad(cal.get(Calendar.MINUTE))}:${pad(cal.get(Calendar.SECOND))}"
}

/** `yyyy-MM-dd HH:mm` — job audit timestamps. */
internal fun dateTime(millis: Long, zone: TimeZone = TimeZone.getDefault()): String {
    val cal = calendarOf(millis, zone)
    return "${cal.get(Calendar.YEAR)}-${pad(cal.get(Calendar.MONTH) + 1)}-${pad(cal.get(Calendar.DAY_OF_MONTH))} " +
        "${pad(cal.get(Calendar.HOUR_OF_DAY))}:${pad(cal.get(Calendar.MINUTE))}"
}

/**
 * Short list-row time: `HH:mm` today, `MMM d, HH:mm` earlier this year, `yyyy-MM-dd` before
 * that — short enough that a long plugin id beside it stays readable.
 */
internal fun compactTime(
    millis: Long,
    nowMillis: Long = System.currentTimeMillis(),
    zone: TimeZone = TimeZone.getDefault(),
): String {
    val cal = calendarOf(millis, zone)
    val now = calendarOf(nowMillis, zone)
    val clock = "${pad(cal.get(Calendar.HOUR_OF_DAY))}:${pad(cal.get(Calendar.MINUTE))}"
    return when {
        cal.get(Calendar.YEAR) != now.get(Calendar.YEAR) ->
            "${cal.get(Calendar.YEAR)}-${pad(cal.get(Calendar.MONTH) + 1)}-${pad(cal.get(Calendar.DAY_OF_MONTH))}"
        cal.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR) -> clock
        else -> "${MONTHS[cal.get(Calendar.MONTH)]} ${cal.get(Calendar.DAY_OF_MONTH)}, $clock"
    }
}

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

private fun calendarOf(millis: Long, zone: TimeZone): Calendar =
    Calendar.getInstance(zone).apply { timeInMillis = millis }

private fun pad(value: Int): String = value.toString().padStart(2, '0')
