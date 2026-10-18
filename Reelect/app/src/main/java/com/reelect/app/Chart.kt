package com.reelect.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

private fun paint(c: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c }

/** 15-day stacked bars (pink = Reels, blue = Shorts) with a dashed daily-limit line. */
class BarChartView(ctx: Context) : View(ctx) {
    var data: List<Store.Day> = emptyList()
        set(v) { field = v; invalidate() }
    var limit: Int = 0
        set(v) { field = v; invalidate() }

    private val d = ctx.resources.displayMetrics.density
    private val pReels = paint(Pal.reels)
    private val pShorts = paint(Pal.shorts)
    private val pEmpty = paint(Color.parseColor("#E3E8F2"))
    private val pLabel = paint(Pal.muted).apply { textSize = 10f * d; textAlign = Paint.Align.CENTER }
    private val pToday = Paint(pLabel).apply { color = Pal.ink; typeface = Typeface.DEFAULT_BOLD }
    private val pVal = Paint(pLabel).apply { textSize = 9f * d }
    private val pLimit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Pal.coral; style = Paint.Style.STROKE; strokeWidth = 1.5f * d
        pathEffect = DashPathEffect(floatArrayOf(8f * d, 6f * d), 0f)
    }

    override fun onMeasure(w: Int, h: Int) { setMeasuredDimension(resolveSize((300 * d).toInt(), w), (190 * d).toInt()) }

    override fun onDraw(canvas: Canvas) {
        if (data.isEmpty()) return
        val w = width.toFloat(); val h = height.toFloat()
        val chartH = h - 24f * d - 18f * d
        val bottom = 18f * d + chartH
        val maxV = max(5, max(limit, data.maxOf { it.total }))
        val slot = w / data.size
        val bw = slot * 0.58f
        val r = 4f * d
        val minSeg = 4f * d
        if (limit > 0) {
            val y = bottom - chartH * limit / maxV
            canvas.drawLine(0f, y, w, y, pLimit)
        }
        data.forEachIndexed { i, day ->
            val cx = slot * i + slot / 2f
            val left = cx - bw / 2f; val right = cx + bw / 2f
            if (day.total == 0) {
                canvas.drawRoundRect(RectF(left, bottom - 3f * d, right, bottom), r, r, pEmpty)
            } else {
                val ih = if (day.ig > 0) max(chartH * day.ig / maxV, minSeg) else 0f
                val yh = if (day.yt > 0) max(chartH * day.yt / maxV, minSeg) else 0f
                val gap = if (day.ig > 0 && day.yt > 0) 1.5f * d else 0f
                if (ih > 0f) canvas.drawRoundRect(RectF(left, bottom - ih, right, bottom), r, r, pReels)
                val yb = bottom - ih - gap
                if (yh > 0f) canvas.drawRoundRect(RectF(left, yb - yh, right, yb), r, r, pShorts)
                canvas.drawText(day.total.toString(), cx, bottom - ih - gap - yh - 4f * d, pVal)
            }
            canvas.drawText(day.date.substring(8), cx, h - 6f * d, if (i == data.size - 1) pToday else pLabel)
        }
    }
}

/** 24 hourly bars; the busiest hour is highlighted. */
class HourBarsView(ctx: Context) : View(ctx) {
    var totals: IntArray = IntArray(24)
        set(v) { field = v; invalidate() }
    private val d = ctx.resources.displayMetrics.density
    private val pBar = paint(Color.parseColor("#BFD0FF"))
    private val pTop = paint(Pal.primary)
    private val pEmpty = paint(Color.parseColor("#E9EEF8"))
    private val pLabel = paint(Pal.muted).apply { textSize = 10f * d; textAlign = Paint.Align.CENTER }

    override fun onMeasure(w: Int, h: Int) { setMeasuredDimension(resolveSize((300 * d).toInt(), w), (130 * d).toInt()) }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val chartH = h - 20f * d
        val mx = max(1, totals.maxOrNull() ?: 1)
        val slot = w / 24f
        val bw = slot * 0.62f
        val r = 3f * d
        for (i in 0 until 24) {
            val cx = slot * i + slot / 2f
            val v = totals[i]
            val bh = if (v == 0) 3f * d else max(chartH * v / mx, 5f * d)
            val p = if (v == 0) pEmpty else if (v == mx) pTop else pBar
            canvas.drawRoundRect(RectF(cx - bw / 2, chartH - bh, cx + bw / 2, chartH), r, r, p)
        }
        listOf(0 to "12a", 6 to "6a", 12 to "12p", 18 to "6p", 23 to "11p").forEach { (hr, s) ->
            canvas.drawText(s, slot * hr + slot / 2f, h - 4f * d, pLabel)
        }
    }
}

/** 15 days × 24 hours grid. Darker = more videos in that hour. */
class HeatmapView(ctx: Context) : View(ctx) {
    var days: List<Store.Day> = emptyList()
        set(v) { field = v; requestLayout(); invalidate() }
    private val d = ctx.resources.displayMetrics.density
    private val pCell = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pLabel = paint(Pal.muted).apply { textSize = 10f * d; textAlign = Paint.Align.CENTER }
    private val pRow = Paint(pLabel).apply { textAlign = Paint.Align.RIGHT }
    private val leftW get() = 34f * d
    private val topH get() = 20f * d

    private fun cell(w: Int) = (w - leftW) / 24f

    override fun onMeasure(w: Int, h: Int) {
        val width = resolveSize((320 * d).toInt(), w)
        val rows = max(1, days.size)
        setMeasuredDimension(width, (topH + rows * cell(width)).toInt() + (4 * d).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        if (days.isEmpty()) return
        val cs = cell(width)
        val mx = max(1, days.maxOf { day -> day.hours.maxOrNull() ?: 0 })
        for (hr in 0 until 24 step 3) canvas.drawText(Stats.hourLabel(hr).replace(" ", "").lowercase(), leftW + cs * hr + cs / 2f, 12f * d, pLabel)
        days.forEachIndexed { r, day ->
            val y = topH + r * cs
            canvas.drawText(day.date.substring(8), leftW - 6f * d, y + cs * 0.68f, pRow)
            for (hr in 0 until 24) {
                val v = day.hours[hr]
                pCell.color = if (v == 0) Color.parseColor("#EEF2FA") else blend((v.toFloat() / mx).pow(0.6f))
                val x = leftW + hr * cs
                canvas.drawRoundRect(RectF(x + 1f, y + 1f, x + cs - 1f, y + cs - 1f), 3f * d, 3f * d, pCell)
            }
        }
    }

    private fun blend(t: Float): Int {
        val a = Color.parseColor("#C9D8FF"); val b = Color.parseColor("#3558E0")
        fun m(x: Int, y: Int) = (x + (y - x) * t).toInt()
        return Color.rgb(m(Color.red(a), Color.red(b)), m(Color.green(a), Color.green(b)), m(Color.blue(a), Color.blue(b)))
    }
}

/** Rounded progress bar. */
class ProgressBarView(ctx: Context) : View(ctx) {
    var progress = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var color = Pal.primary
        set(v) { field = v; invalidate() }
    private val d = ctx.resources.displayMetrics.density
    private val pTrack = paint(Color.parseColor("#E9EEF8"))
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(w: Int, h: Int) { setMeasuredDimension(resolveSize((200 * d).toInt(), w), (12 * d).toInt()) }

    override fun onDraw(canvas: Canvas) {
        val r = height / 2f
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), r, r, pTrack)
        if (progress > 0f) {
            pFill.color = color
            canvas.drawRoundRect(RectF(0f, 0f, max(height.toFloat(), width * progress), height.toFloat()), r, r, pFill)
        }
    }
}

/** Circular 0-100 gauge. */
class RingView(ctx: Context) : View(ctx) {
    var score = 100
        set(v) { field = v; invalidate() }
    private val d = ctx.resources.displayMetrics.density
    private val pTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.parseColor("#E9EEF8") }
    private val pArc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val pNum = paint(Pal.ink).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD) }
    private val pSub = paint(Pal.muted).apply { textAlign = Paint.Align.CENTER }

    override fun onMeasure(w: Int, h: Int) { val s = (170 * d).toInt(); setMeasuredDimension(resolveSize(s, w), s) }

    override fun onDraw(canvas: Canvas) {
        val s = min(width, height).toFloat()
        val sw = 14f * d
        pTrack.strokeWidth = sw; pArc.strokeWidth = sw
        pArc.color = when { score >= 70 -> Pal.mint; score >= 50 -> Pal.amber; else -> Pal.coral }
        val rect = RectF(sw, sw, s - sw, s - sw)
        canvas.drawArc(rect, 135f, 270f, false, pTrack)
        canvas.drawArc(rect, 135f, 270f * score / 100f, false, pArc)
        pNum.textSize = 44f * d; pSub.textSize = 12f * d
        canvas.drawText(score.toString(), s / 2f, s / 2f + 10f * d, pNum)
        canvas.drawText("out of 100", s / 2f, s / 2f + 32f * d, pSub)
    }
}

/** Scrolly the mascot. Mood changes face, colour and little extras; it gently bobs. */
class MascotView(ctx: Context) : View(ctx) {
    var mood: Stats.Mood = Stats.Mood.HAPPY
        set(v) { field = v; invalidate() }
    private val d = ctx.resources.displayMetrics.density
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val soft = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dark = paint(Color.parseColor("#26324F"))
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#26324F"); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val cheek = paint(Color.parseColor("#FF9DB8"))
    private val extra = paint(Color.parseColor("#26324F")).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private var phase = 0f
    private var anim: ValueAnimator? = null

    override fun onMeasure(w: Int, h: Int) { val s = (120 * d).toInt(); setMeasuredDimension(resolveSize(s, w), resolveSize(s, h)) }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        anim = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
            duration = 2600; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() { anim?.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val s = min(width, height).toFloat()
        val cx = width / 2f
        val bob = sin(phase) * s * 0.012f
        val cy = height / 2f + s * 0.04f + bob
        val col = when (mood) {
            Stats.Mood.HAPPY -> "#8BE3C1"; Stats.Mood.OKAY -> "#9CC0FF"
            Stats.Mood.TIRED -> "#C4B5F7"; Stats.Mood.DIZZY -> "#FFCF9A"
        }
        body.color = Color.parseColor(col)
        soft.color = Color.argb(110, 255, 255, 255)
        // ears
        for (sx in listOf(-1f, 1f)) {
            canvas.drawCircle(cx + sx * s * 0.27f, cy - s * 0.32f, s * 0.12f, body)
            canvas.drawCircle(cx + sx * s * 0.27f, cy - s * 0.32f, s * 0.06f, soft)
        }
        // body + shine
        canvas.drawCircle(cx, cy, s * 0.40f, body)
        canvas.drawOval(RectF(cx - s * 0.26f, cy - s * 0.34f, cx - s * 0.04f, cy - s * 0.20f), soft)

        stroke.strokeWidth = s * 0.03f
        val eyeY = cy - s * 0.04f
        val ex = s * 0.14f
        when (mood) {
            Stats.Mood.HAPPY -> for (sx in listOf(-1f, 1f)) {
                canvas.drawArc(RectF(cx + sx * ex - s * 0.06f, eyeY - s * 0.05f, cx + sx * ex + s * 0.06f, eyeY + s * 0.07f), 200f, 140f, false, stroke)
            }
            Stats.Mood.OKAY -> for (sx in listOf(-1f, 1f)) canvas.drawCircle(cx + sx * ex, eyeY, s * 0.035f, dark)
            Stats.Mood.TIRED -> for (sx in listOf(-1f, 1f)) {
                canvas.drawLine(cx + sx * ex - s * 0.06f, eyeY, cx + sx * ex + s * 0.06f, eyeY + s * 0.01f, stroke)
                canvas.drawArc(RectF(cx + sx * ex - s * 0.05f, eyeY + s * 0.03f, cx + sx * ex + s * 0.05f, eyeY + s * 0.09f), 20f, 140f, false, stroke)
            }
            Stats.Mood.DIZZY -> for (sx in listOf(-1f, 1f)) {
                canvas.drawCircle(cx + sx * ex, eyeY, s * 0.055f, stroke)
                canvas.drawCircle(cx + sx * ex, eyeY, s * 0.025f, stroke)
            }
        }
        if (mood == Stats.Mood.HAPPY || mood == Stats.Mood.OKAY) {
            cheek.alpha = if (mood == Stats.Mood.HAPPY) 150 else 90
            for (sx in listOf(-1f, 1f)) canvas.drawCircle(cx + sx * s * 0.25f, cy + s * 0.08f, s * 0.05f, cheek)
        }
        val my = cy + s * 0.12f
        when (mood) {
            Stats.Mood.HAPPY -> canvas.drawArc(RectF(cx - s * 0.10f, my - s * 0.08f, cx + s * 0.10f, my + s * 0.08f), 15f, 150f, false, stroke)
            Stats.Mood.OKAY -> canvas.drawLine(cx - s * 0.06f, my, cx + s * 0.06f, my, stroke)
            Stats.Mood.TIRED -> canvas.drawOval(RectF(cx - s * 0.04f, my - s * 0.02f, cx + s * 0.04f, my + s * 0.07f), dark)
            Stats.Mood.DIZZY -> {
                val p = android.graphics.Path()
                p.moveTo(cx - s * 0.10f, my)
                p.quadTo(cx - s * 0.05f, my - s * 0.05f, cx, my)
                p.quadTo(cx + s * 0.05f, my + s * 0.05f, cx + s * 0.10f, my)
                canvas.drawPath(p, stroke)
            }
        }
        extra.textSize = s * 0.14f
        when (mood) {
            Stats.Mood.HAPPY -> { extra.color = Color.parseColor("#F5A524"); canvas.drawText("✦", cx + s * 0.38f, cy - s * 0.30f, extra); canvas.drawText("✦", cx - s * 0.40f, cy - s * 0.05f, Paint(extra).apply { textSize = s * 0.09f }) }
            Stats.Mood.TIRED -> { extra.color = Color.parseColor("#6B7A99"); canvas.drawText("z", cx + s * 0.36f, cy - s * 0.30f, extra); canvas.drawText("Z", cx + s * 0.44f, cy - s * 0.42f, Paint(extra).apply { textSize = s * 0.18f }) }
            Stats.Mood.DIZZY -> { extra.color = Color.parseColor("#F4617F"); canvas.drawText("✧", cx + s * 0.38f, cy - s * 0.30f, extra); canvas.drawText("✧", cx - s * 0.40f, cy - s * 0.26f, extra) }
            else -> {}
        }
    }
}
