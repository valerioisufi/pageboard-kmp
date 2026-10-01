package io.github.valerioisufi.drawview.ui.test

import io.github.valerioisufi.drawview.ui.util.formatDateTime
import io.github.valerioisufi.drawview.ui.util.formatDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DateFormatTest {

    private val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    @Test
    fun formatsDayMonthYearAndTime() {
        // 1700000000000 ms is Nov 14, 2023 22:13 UTC: the local date is Nov 14 or 15
        val result = formatDateTime(1700000000000L, months)
        assertTrue(result.contains("Nov 2023"), result)
        assertTrue(Regex("""^1[45] Nov 2023, \d\d:\d\d$""").matches(result), result)
    }

    @Test
    fun formatsDecimalsWithoutPlatformFormatting() {
        assertEquals("0.5", formatDecimal(0.5f, 1))
        assertEquals("1.2", formatDecimal(1.2f, 1))
        assertEquals("10.0", formatDecimal(9.96f, 1))
        assertEquals("-2.5", formatDecimal(-2.5f, 1))
        assertEquals("50", formatDecimal(50f, 0))
        assertEquals("0.25", formatDecimal(0.25f, 2))
    }
}
