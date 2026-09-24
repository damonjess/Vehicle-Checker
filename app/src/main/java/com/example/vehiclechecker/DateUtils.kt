package com.example.vehiclechecker

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

object DateUtils {

    private val fullFormat = SimpleDateFormat("d MMMM yyyy", Locale.UK)

    /** "1 June 2027" -> epoch ms. Returns null when unparseable. */
    fun parseFullDate(text: String): Long? = try {
        fullFormat.parse(text.trim())?.time
    } catch (_: ParseException) {
        null
    }

    /**
     * Handles both "13 February 2027" and month-only dates like "June 2027"
     * (treated as the 1st of that month).
     */
    fun parseFlexible(text: String): Long? {
        parseFullDate(text)?.let { return it }
        val parts = text.trim().split(" ")
        if (parts.size == 2) {
            val months = mapOf(
                "january" to 0, "february" to 1, "march" to 2, "april" to 3, "may" to 4,
                "june" to 5, "july" to 6, "august" to 7, "september" to 8,
                "october" to 9, "november" to 10, "december" to 11
            )
            val monthIndex = months[parts[0].lowercase(Locale.UK)] ?: return null
            val year = parts[1].toIntOrNull() ?: return null
            val cal = Calendar.getInstance()
            cal.set(year, monthIndex, 1, 0, 0, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }
        return null
    }

    /** Whole days from now until the given epoch (negative if in the past). */
    fun daysUntil(epochMs: Long): Long =
        TimeUnit.MILLISECONDS.toDays(epochMs - System.currentTimeMillis())
}
