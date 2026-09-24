package com.example.vehiclechecker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Simple pie chart for the pass / pass-with-advisories / fail breakdown. */
class MotPieChart @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Slice(val color: Int, val value: Float, val label: String)

    private var slices: List<Slice> = emptyList()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14f * resources.displayMetrics.density
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    fun setSlices(slices: List<Slice>) {
        this.slices = slices
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val total = slices.sumOf { it.value.toDouble() }.toFloat()
        if (total <= 0f) return

        val cx = width / 2f
        val cy = height / 2f
        val radius = min(cx, cy) - 4f
        val rectLeft = cx - radius
        val rectTop = cy - radius
        val rectRight = cx + radius
        val rectBottom = cy + radius

        var startAngle = -90f
        for (slice in slices) {
            val sweep = slice.value / total * 360f
            paint.color = slice.color
            canvas.drawArc(rectLeft, rectTop, rectRight, rectBottom, startAngle, sweep, true, paint)

            if (slice.value > 0f) {
                val midAngle = Math.toRadians((startAngle + sweep / 2f).toDouble())
                val labelRadius = radius * 0.6f
                val x = cx + (labelRadius * cos(midAngle)).toFloat()
                val y = cy + (labelRadius * sin(midAngle)).toFloat()
                val percent = (slice.value / total * 100f).roundToInt()
                canvas.drawText("$percent%", x, y + textPaint.textSize / 3f, textPaint)
            }
            startAngle += sweep
        }
    }
}
