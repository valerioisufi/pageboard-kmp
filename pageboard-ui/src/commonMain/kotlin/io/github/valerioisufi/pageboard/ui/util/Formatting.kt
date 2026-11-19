package io.github.valerioisufi.pageboard.ui.util

import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Formats [value] with exactly [decimals] fractional digits ("%.1f"), without relying on the
 * platform number formatting (Float.toString differs on JS, where floats are doubles).
 */
public fun formatDecimal(value: Float, decimals: Int = 1): String {
    var factor = 1L
    repeat(decimals) { factor *= 10L }
    val scaled = (value.toDouble() * factor).roundToLong()
    val negative = scaled < 0
    val absolute = abs(scaled)
    val integerPart = absolute / factor
    val fractionPart = absolute % factor
    val sign = if (negative) "-" else ""
    if (decimals <= 0) return sign + integerPart
    return sign + integerPart + "." + fractionPart.toString().padStart(decimals, '0')
}

/**
 * Formats an epoch timestamp as "dd MMM yyyy, HH:mm" in the local time zone (the format used by
 * the original app), with the given localized abbreviated month names (January first).
 */
@OptIn(ExperimentalTime::class)
public fun formatDateTime(epochMs: Long, monthNames: List<String>): String {
    val dateTime = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())
    val month = monthNames.getOrNull(dateTime.month.ordinal) ?: (dateTime.month.ordinal + 1).toString()
    val day = dateTime.day.toString().padStart(2, '0')
    val hour = dateTime.hour.toString().padStart(2, '0')
    val minute = dateTime.minute.toString().padStart(2, '0')
    return "$day $month ${dateTime.year}, $hour:$minute"
}

/** Formats an epoch timestamp as "d MMM · HH:mm" in the local time zone (the cards of the library). */
@OptIn(ExperimentalTime::class)
public fun formatShortDateTime(epochMs: Long, monthNames: List<String>): String {
    val dateTime = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())
    val month = monthNames.getOrNull(dateTime.month.ordinal) ?: (dateTime.month.ordinal + 1).toString()
    val hour = dateTime.hour.toString().padStart(2, '0')
    val minute = dateTime.minute.toString().padStart(2, '0')
    return "${dateTime.day} $month · $hour:$minute"
}

/** Formats an epoch timestamp as "d MMM yyyy" in the local time zone. */
@OptIn(ExperimentalTime::class)
public fun formatDate(epochMs: Long, monthNames: List<String>): String {
    val dateTime = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())
    val month = monthNames.getOrNull(dateTime.month.ordinal) ?: (dateTime.month.ordinal + 1).toString()
    return "${dateTime.day} $month ${dateTime.year}"
}
