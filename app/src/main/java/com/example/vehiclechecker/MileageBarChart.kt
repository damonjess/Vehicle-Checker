package com.example.vehiclechecker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Annual-mileage bar chart: miles driven between consecutive MOT tests, bucketed per year,
 * drawn in the teal style of the Vehicle Smart mileage chart.
 */
class MileageBarChart @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Entry(val year: Int, val miles: Float)

    private var entries: List<Entry> = emptyList()

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3D9186.toInt() }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8FA5B5.toInt()
        textSize = 11f * resources.displayMetrics.density
        textAlign = Paint.Align.CENTER
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 10f * resources.displayMetrics.density
        textAlign = Paint.Align.CENTER
    }
    private val axisLinePaint = Paint().apply {
        color = 0x55FFFFFF
        strokeWidth = 1f * resources.displayMetrics.density
    }

    fun setEntries(list: List<Entry>) {
        entries = list.filter { it.miles >= 0f }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (entries.isEmpty()) return

        val density = resources.displayMetrics.density
        val labelHeight = 18f * density
        val valueHeight = 14f * density
        val chartTop = valueHeight
        val chartBottom = height - labelHeight
        val chartHeight = chartBottom - chartTop

        // Baseline
        canvas.drawLine(0f, chartBottom, width.toFloat(), chartBottom, axisLinePaint)

        val maxValue = max(entries.maxOf { it.miles }, 1f)
        val slot = width.toFloat() / entries.size
        val barWidth = (slot * 0.62f).coerceAtMost(48f * density)

        entries.forEachIndexed { index, entry ->
            val barHeight = (entry.miles / maxValue * chartHeight).coerceAtLeast(2f)
            val left = slot * index + (slot - barWidth) / 2f
            val top = chartBottom - barHeight

            canvas.drawRoundRect(left, top, left + barWidth, chartBottom, 6f * density, 6f * density, barPaint)

            // Value above the bar
            if (entry.miles > 0) {
                val label = "${(entry.miles.roundToInt() / 1000).coerceAtLeast(0)}k"
                val text = if (entry.miles < 1000) "${entry.miles.roundToInt()}" else "$label"
                canvas.drawText(text, left + barWidth / 2f, top - 4f * density, valuePaint)
            }

            // Year label below the bar
            canvas.drawText(
                entry.year.toString(),
                left + barWidth / 2f,
                height - 5f * density,
                axisPaint
            )
        }
    }
}
