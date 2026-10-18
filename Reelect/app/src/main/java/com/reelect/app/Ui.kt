package com.reelect.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/** Light, soft palette chosen for readability. */
object Pal {
    val bg = Color.parseColor("#F5F8FF")
    val card = Color.WHITE
    val line = Color.parseColor("#E3E9F5")
    val ink = Color.parseColor("#1E2A44")
    val muted = Color.parseColor("#6B7A99")
    val primary = Color.parseColor("#4F7CFF")
    val primarySoft = Color.parseColor("#E8EEFF")
    val mint = Color.parseColor("#27C08E")
    val mintSoft = Color.parseColor("#DDF7EE")
    val amber = Color.parseColor("#F5A524")
    val amberSoft = Color.parseColor("#FFF1D6")
    val coral = Color.parseColor("#F4617F")
    val coralSoft = Color.parseColor("#FFE3E9")
    val reels = Color.parseColor("#FF6B9A")
    val reelsSoft = Color.parseColor("#FFE6EF")
    val shorts = Color.parseColor("#5B8DEF")
    val shortsSoft = Color.parseColor("#E3ECFF")
    val lav = Color.parseColor("#8B7CF6")
}

/** Small view-building toolkit so pages stay short. */
class Ui(val ctx: Context) {
    val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val density = ctx.resources.displayMetrics.density

    fun dp(v: Int) = (v * density).toInt()
    fun dpf(v: Int) = v * density

    fun lp(w: Int = MATCH, h: Int = WRAP, top: Int = 0, weight: Float = 0f, left: Int = 0) =
        LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dp(top); leftMargin = dp(left) }

    fun rounded(color: Int, r: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dpf(r)
        if (stroke != null) setStroke(dp(1), stroke)
    }

    fun text(s: String, size: Float = 14f, color: Int = Pal.ink, bold: Boolean = false) = TextView(ctx).apply {
        text = s; textSize = size; setTextColor(color)
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
    }

    fun vbox() = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    fun hbox() = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    fun card(pad: Int = 16, bg: Int = Pal.card, stroke: Int? = Pal.line) = vbox().apply {
        setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
        background = rounded(bg, 20, stroke)
    }

    fun pressFx(v: View) {
        v.setOnTouchListener { view, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> view.alpha = 0.7f
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.alpha = 1f
            }
            false
        }
    }

    fun tappable(v: View, onClick: () -> Unit): View { v.isClickable = true; v.setOnClickListener { onClick() }; pressFx(v); return v }

    fun primaryBtn(label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setPadding(dp(16), dp(13), dp(16), dp(13))
        background = rounded(Pal.primary, 14)
        tappable(this, onClick)
    }

    fun softBtn(label: String, fg: Int = Pal.primary, bg: Int = Pal.primarySoft, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; textSize = 13.5f; setTextColor(fg); gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = rounded(bg, 12)
        tappable(this, onClick)
    }

    fun chip(label: String, fg: Int, bg: Int) = TextView(ctx).apply {
        text = label; textSize = 11.5f; setTextColor(fg)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setPadding(dp(10), dp(4), dp(10), dp(4))
        background = rounded(bg, 20)
    }

    fun divider() = View(ctx).apply { setBackgroundColor(Pal.line) }

    fun sectionTitle(s: String) = text(s, 13f, Pal.muted, true).apply { letterSpacing = 0.06f; setPadding(dp(4), 0, 0, 0) }

    fun switchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val row = hbox()
        val t = vbox()
        t.addView(text(title, 15.5f, Pal.ink, true))
        t.addView(text(sub, 12.5f, Pal.muted), lp(top = 2))
        row.addView(t, lp(w = 0, weight = 1f))
        val sw = Switch(ctx).apply {
            isChecked = checked
            val st = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(st, intArrayOf(Pal.primary, Color.parseColor("#B8C2D9")))
            trackTintList = ColorStateList(st, intArrayOf(Color.parseColor("#B9CBFF"), Color.parseColor("#DDE3F0")))
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }
        row.addView(sw, lp(left = 10))
        return row
    }

    /** − value + control. Updates itself; [onChange] receives the new value. */
    fun stepper(start: Int, min: Int, max: Int, step: Int, fmt: (Int) -> String, onChange: (Int) -> Unit): View {
        var v = start
        val row = hbox()
        val tv = text(fmt(v), 20f, Pal.ink, true).apply { gravity = Gravity.CENTER; minWidth = dp(92) }
        fun round(sym: String, delta: Int) = TextView(ctx).apply {
            text = sym; textSize = 22f; setTextColor(Pal.primary); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = rounded(Pal.primarySoft, 14)
            tappable(this) {
                v = (v + delta * step).coerceIn(min, max)
                tv.text = fmt(v); onChange(v)
            }
        }
        row.addView(round("−", -1), LinearLayout.LayoutParams(dp(46), dp(46)))
        row.addView(tv)
        row.addView(round("+", 1), LinearLayout.LayoutParams(dp(46), dp(46)))
        return row
    }
}
