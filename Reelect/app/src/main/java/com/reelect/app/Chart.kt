package com.reelect.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import kotlin.math.max

/** 15-day stacked bar chart: pink = Reels, purple = Shorts. */
class BarChartView(ctx: Context) : View(ctx) {

    var data: List<Store.Day> = emptyList()
        set(value) { field = value; invalidate() }

    private val d = ctx.resources.displayMetrics.density
    private val pReels = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF4D8D") }
    private val pShorts = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#7C4DFF") }
    private val pEmpty = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2A2150") }
    private val pLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8F86B3"); textSize = 10f * d; textAlign = Paint.Align.CENTER
    }
    private val pToday = Paint(pLabel).apply { color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD }
    private val pVal = Paint(pLabel).apply { textSize = 9f * d; color = Color.parseColor("#CFC8EC") }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(resolveSize((300 * d).toInt(), w), (190 * d).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        if (data.isEmpty()) return
        val w = width.toFloat()
        val h = height.toFloat()
        val labelH = 24f * d
        val topPad = 18f * d
        val chartH = h - labelH - topPad
        val bottom = topPad + chartH
        val maxV = max(5, data.maxOf { it.total })
        val slot = w / data.size
        val bw = slot * 0.58f
        val r = 4f * d
        val minSeg = 4f * d

        data.forEachIndexed { i, day ->
            val cx = slot * i + slot / 2f
            val left = cx - bw / 2f
            val right = cx + bw / 2f
            if (day.total == 0) {
                canvas.drawRoundRect(RectF(left, bottom - 3f * d, right, bottom), r, r, pEmpty)
            } else {
                val ih = if (day.ig > 0) max(chartH * day.ig / maxV, minSeg) else 0f
                val yh = if (day.yt > 0) max(chartH * day.yt / maxV, minSeg) else 0f
                val gap = if (day.ig > 0 && day.yt > 0) 1.5f * d else 0f
                if (ih > 0f) canvas.drawRoundRect(RectF(left, bottom - ih, right, bottom), r, r, pReels)
                val yBottom = bottom - ih - gap
                if (yh > 0f) canvas.drawRoundRect(RectF(left, yBottom - yh, right, yBottom), r, r, pShorts)
                val top = bottom - ih - gap - yh
                canvas.drawText(day.total.toString(), cx, top - 4f * d, pVal)
            }
            val label = day.date.substring(8)
            canvas.drawText(label, cx, h - 6f * d, if (i == data.size - 1) pToday else pLabel)
        }
    }
}
