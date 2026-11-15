package com.laurencebrwn.ntsradio.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * A minimal progress line for the current show's timeslot. Fills in as the show
 * plays, like a radio schedule line on the NTS website.
 */
class TimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x2EFFFFFF
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
    }

    private var startMs = 0L
    private var endMs = 0L

    fun setRange(startMs: Long, endMs: Long) {
        if (this.startMs == startMs && this.endMs == endMs) return
        this.startMs = startMs
        this.endMs = endMs
        invalidate()
    }

    private fun fraction(): Float {
        if (endMs <= startMs) return 0f
        val now = System.currentTimeMillis()
        return ((now - startMs).toFloat() / (endMs - startMs).toFloat()).coerceIn(0f, 1f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = 4f * density
        val centerY = height / 2f
        val lineStart = inset
        val lineEnd = width - inset
        if (lineEnd <= lineStart) return

        canvas.drawLine(lineStart, centerY, lineEnd, centerY, trackPaint)
        if (endMs <= startMs) return

        val x = lineStart + (lineEnd - lineStart) * fraction()
        canvas.drawLine(lineStart, centerY, x, centerY, fillPaint)
        canvas.drawCircle(x, centerY, min(4f * density, height / 2f), dotPaint)
    }
}
