package com.reelect.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.res.ColorStateList
import android.content.Intent
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private val bg = Color.parseColor("#0E0A1F")
    private val cardBg = Color.parseColor("#1A1433")
    private val line = Color.parseColor("#2A2150")
    private val white = Color.WHITE
    private val muted = Color.parseColor("#A79FC7")
    private val pink = Color.parseColor("#FF4D8D")
    private val purple = Color.parseColor("#7C4DFF")
    private val lavender = Color.parseColor("#A78BFA")
    private val green = Color.parseColor("#2EE59D")
    private val amber = Color.parseColor("#FFB020")
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var statusDot: View
    private lateinit var statusTitle: TextView
    private lateinit var statusSub: TextView
    private lateinit var enableBtn: TextView
    private lateinit var totalTv: TextView
    private lateinit var dateTv: TextView
    private lateinit var reelsTv: TextView
    private lateinit var shortsTv: TextView
    private lateinit var chart: BarChartView
    private lateinit var chartSummary: TextView
    private lateinit var chipAcc: TextView
    private lateinit var chipBat: TextView
    private lateinit var chipRes: TextView
    private lateinit var diagBody: TextView

    private val ui = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 1500) }
    }

    // ---------- helpers ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Int) = v * resources.displayMetrics.density

    private fun lp(w: Int = MATCH, h: Int = WRAP, top: Int = 0, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dp(top) }

    private fun text(s: String, size: Float, color: Int, face: String = "sans-serif", style: Int = Typeface.NORMAL) =
        TextView(this).apply {
            text = s; textSize = size; setTextColor(color); typeface = Typeface.create(face, style)
        }

    private fun rounded(color: Int, r: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dpf(r)
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun cardBox() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = rounded(cardBg, 20, line)
    }

    private fun pressFx(v: View) {
        v.setOnTouchListener { view, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> view.alpha = 0.65f
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.alpha = 1f
            }
            false
        }
    }

    private fun gradientButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 15f; setTextColor(white); gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(purple, pink)).apply { cornerRadius = dpf(14) }
        isClickable = true
        setOnClickListener { onClick() }
        pressFx(this)
    }

    private fun outlineButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 13.5f; setTextColor(lavender); gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = rounded(Color.TRANSPARENT, 12, purple)
        isClickable = true
        setOnClickListener { onClick() }
        pressFx(this)
    }

    private fun setChip(chip: TextView, label: String, color: Int) {
        chip.text = label
        chip.setTextColor(color)
        chip.background = rounded((color and 0x00FFFFFF) or 0x26000000, 20)
    }

    private fun newChip() = TextView(this).apply {
        textSize = 11.5f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setPadding(dp(10), dp(4), dp(10), dp(4))
    }

    // ---------- UI ----------

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        val scroll = ScrollView(this).apply { setBackgroundColor(bg); isVerticalScrollBarEnabled = false }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(26), dp(20), dp(32))
        }
        scroll.addView(col)
        buildHeader(col)
        buildStatus(col)
        buildToday(col)
        buildToggle(col)
        buildChart(col)
        buildSetup(col)
        buildDiagnostics(col)
        col.addView(
            text("🔒  Data stays on your phone and is deleted after 15 days.", 12f, muted).apply { gravity = Gravity.CENTER },
            lp(top = 22)
        )
        setContentView(scroll)
    }

    private fun buildHeader(col: LinearLayout) {
        val title = text("ScrollSense", 34f, white, "sans-serif-black")
        title.paint.shader = LinearGradient(
            0f, 0f, title.paint.measureText("ScrollSense"), 0f, intArrayOf(lavender, pink), null, Shader.TileMode.CLAMP
        )
        col.addView(title)
        col.addView(text("Know every scroll.", 15f, muted), lp(top = 2))
    }

    private fun buildStatus(col: LinearLayout) {
        val c = cardBox()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        statusDot = View(this)
        row.addView(statusDot, LinearLayout.LayoutParams(dp(12), dp(12)).apply { rightMargin = dp(12) })
        val tcol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        statusTitle = text("", 16f, white, "sans-serif-medium", Typeface.BOLD)
        statusSub = text("", 13f, muted)
        tcol.addView(statusTitle)
        tcol.addView(statusSub, lp(top = 2))
        row.addView(tcol, lp(w = 0, weight = 1f))
        c.addView(row)
        enableBtn = gradientButton("Enable tracking") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        c.addView(enableBtn, lp(top = 14))
        col.addView(c, lp(top = 22))
    }

    private fun buildToday(col: LinearLayout) {
        val t = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(purple, Color.parseColor("#B23DFF"), pink))
                .apply { cornerRadius = dpf(24) }
        }
        t.addView(text("TODAY", 12f, 0xCCFFFFFF.toInt(), "sans-serif-medium", Typeface.BOLD).apply { letterSpacing = 0.18f })
        totalTv = text("0", 60f, white, "sans-serif-black")
        t.addView(totalTv, lp(top = 2))
        t.addView(text("videos scrolled", 13f, 0xCCFFFFFF.toInt()))
        dateTv = text("", 12f, 0x99FFFFFF.toInt())
        t.addView(dateTv, lp(top = 4))

        val tiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun tile(emoji: String, label: String, sub: String): TextView {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = rounded(0x26FFFFFF, 16)
            }
            box.addView(text("$emoji  $label", 13f, 0xE6FFFFFF.toInt(), "sans-serif-medium", Typeface.BOLD))
            val n = text("0", 32f, white, "sans-serif-black")
            box.addView(n, lp(top = 2))
            box.addView(text(sub, 11.5f, 0xB3FFFFFF.toInt()))
            tiles.addView(box, lp(w = 0, weight = 1f).apply { if (tiles.childCount > 0) leftMargin = dp(10) })
            return n
        }
        reelsTv = tile("📸", "Reels", "Instagram")
        shortsTv = tile("▶", "Shorts", "YouTube")
        t.addView(tiles, lp(top = 16))
        col.addView(t, lp(top = 16))
    }

    private fun buildToggle(col: LinearLayout) {
        val c = cardBox()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val tcol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tcol.addView(text("Floating counter", 16f, white, "sans-serif-medium", Typeface.BOLD))
        tcol.addView(text("Live count over any app. Drag to move, tap to collapse.", 12.5f, muted), lp(top = 2))
        row.addView(tcol, lp(w = 0, weight = 1f))
        val sw = Switch(this).apply {
            isChecked = Store.overlayEnabled(this@MainActivity)
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(pink, Color.parseColor("#8F86B3")))
            trackTintList = ColorStateList(states, intArrayOf((pink and 0x00FFFFFF) or 0x66000000, 0x33FFFFFF))
            setOnCheckedChangeListener { _, on -> Store.setOverlay(this@MainActivity, on) }
        }
        row.addView(sw)
        c.addView(row)
        col.addView(c, lp(top = 14))
    }

    private fun legend(color: Int, label: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(View(context).apply { background = rounded(color, 4) }, LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(5) })
        addView(text(label, 11.5f, muted))
    }

    private fun buildChart(col: LinearLayout) {
        val c = cardBox()
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(text("Last 15 days", 16f, white, "sans-serif-medium", Typeface.BOLD), lp(w = 0, weight = 1f))
        head.addView(legend(pink, "Reels"))
        head.addView(legend(purple, "Shorts"), lp().apply { leftMargin = dp(12) })
        c.addView(head)
        chart = BarChartView(this)
        c.addView(chart, lp(top = 10))
        chartSummary = text("", 12.5f, muted)
        c.addView(chartSummary, lp(top = 6))
        col.addView(c, lp(top = 14))
    }

    private fun setupRow(parent: LinearLayout, title: String, sub: String, btn: String, onClick: () -> Unit): TextView {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(14), 0, dp(14)) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(text(title, 15f, white, "sans-serif-medium", Typeface.BOLD), lp(w = 0, weight = 1f))
        val chip = newChip()
        top.addView(chip)
        row.addView(top)
        row.addView(text(sub, 12.5f, muted), lp(top = 4))
        row.addView(outlineButton(btn, onClick), lp(top = 10))
        parent.addView(View(this).apply { setBackgroundColor(line) }, lp(h = 1))
        parent.addView(row)
        return chip
    }

    private fun buildSetup(col: LinearLayout) {
        val c = cardBox()
        c.addView(text("Setup & permissions", 16f, white, "sans-serif-medium", Typeface.BOLD))
        c.addView(text("Three quick things so counting keeps working in the background.", 12.5f, muted), lp(top = 2, h = WRAP))
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chipAcc = setupRow(
            inner, "1 · Accessibility service",
            "Lets ScrollSense notice swipes in Instagram & YouTube. In Settings open Installed apps → ScrollSense → turn on.",
            "Open accessibility settings"
        ) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        chipBat = setupRow(
            inner, "2 · Background activity",
            "Set battery usage to Unrestricted so Android never stops the counter.",
            "Allow unrestricted battery"
        ) { requestBattery() }
        chipRes = setupRow(
            inner, "3 · Restricted settings",
            "If the accessibility switch is greyed out: App info → ⋮ (top right) → Allow restricted settings.",
            "Open app info"
        ) { openAppInfo() }
        c.addView(inner, lp(top = 8))
        col.addView(c, lp(top = 14))
    }

    private fun buildDiagnostics(col: LinearLayout) {
        val c = cardBox()
        val head = text("Diagnostics  ▸", 14f, muted, "sans-serif-medium", Typeface.BOLD)
        diagBody = TextView(this).apply {
            textSize = 11f; setTextColor(muted); typeface = Typeface.MONOSPACE; visibility = View.GONE
            setTextIsSelectable(true)
        }
        head.setOnClickListener {
            val open = diagBody.visibility == View.VISIBLE
            diagBody.visibility = if (open) View.GONE else View.VISIBLE
            head.text = if (open) "Diagnostics  ▸" else "Diagnostics  ▾"
        }
        c.addView(head)
        c.addView(diagBody, lp(top = 10))
        col.addView(c, lp(top = 14))
    }

    // ---------- state ----------

    override fun onResume() {
        super.onResume()
        ticker.run()
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(ticker)
    }

    private fun refresh() {
        val on = serviceEnabled()
        statusDot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (on) green else amber) }
        statusTitle.text = if (on) "Tracking is active" else "Tracking is off"
        statusSub.text = if (on) "Counting Instagram Reels & YouTube Shorts." else "Turn ScrollSense on in Accessibility settings."
        enableBtn.visibility = if (on) View.GONE else View.VISIBLE

        val (ig, yt) = Store.todayCounts(this)
        totalTv.text = (ig + yt).toString()
        reelsTv.text = ig.toString()
        shortsTv.text = yt.toString()
        dateTv.text = SimpleDateFormat("EEEE, d MMM", Locale.getDefault()).format(Date()) + "  ·  resets at midnight"

        val days = Store.last15(this)
        chart.data = days
        chartSummary.text = "15-day total:  ${days.sumOf { it.ig }} reels  ·  ${days.sumOf { it.yt }} shorts"

        setChip(chipAcc, if (on) "ON" else "OFF", if (on) green else amber)
        val bat = batteryOk()
        setChip(chipBat, if (bat) "ALLOWED" else "RECOMMENDED", if (bat) green else amber)
        if (Build.VERSION.SDK_INT >= 33) setChip(chipRes, "ANDROID 13+", lavender) else setChip(chipRes, "NOT NEEDED", green)

        diagBody.text = DebugLog.dump().ifEmpty { "No events yet. Open Instagram Reels or YouTube Shorts." }
    }

    private fun serviceEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun batteryOk(): Boolean =
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    private fun requestBattery() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            catch (e2: Exception) { openAppInfo() }
        }
    }

    private fun openAppInfo() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }
}
