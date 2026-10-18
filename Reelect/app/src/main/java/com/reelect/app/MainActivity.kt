package com.reelect.app

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Shell: top bar, five bottom tabs, and a small back-stack for the detail pages. */
class MainActivity : Activity() {

    val ui by lazy { Ui(this) }
    private lateinit var content: FrameLayout
    private lateinit var titleTv: TextView
    private lateinit var backBtn: TextView
    private val navBoxes = ArrayList<LinearLayout>()
    private val tabs = arrayOfNulls<Page>(5)
    private val stack = ArrayList<Page>()
    private var currentTab = 0
    private var current: Page? = null
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { current?.refresh(); handler.postDelayed(this, 2000) }
    }

    private val tabNames = listOf("Home" to "🏠", "Trends" to "📊", "Goals" to "🎯", "Focus" to "🧘", "Setup" to "⚙️")

    private fun makeTab(i: Int): Page = when (i) {
        0 -> HomePage(this); 1 -> TrendsPage(this); 2 -> GoalsPage(this); 3 -> FocusPage(this); else -> SetupPage(this)
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = Pal.bg
        window.navigationBarColor = Color.WHITE
        var flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        if (Build.VERSION.SDK_INT >= 26) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        window.decorView.systemUiVisibility = flags

        Store.ensureFirstDay(this)
        Notifier.schedule(this)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Pal.bg) }

        val top = ui.hbox().apply { setPadding(ui.dp(14), ui.dp(14), ui.dp(18), ui.dp(6)) }
        backBtn = TextView(this).apply {
            text = "←"; textSize = 24f; setTextColor(Pal.primary); visibility = View.GONE
            setPadding(ui.dp(6), 0, ui.dp(14), 0)
            setOnClickListener { goBack() }
        }
        titleTv = ui.text("ScrollSense", 20f, Pal.ink, true)
        top.addView(backBtn)
        top.addView(titleTv)
        root.addView(top)

        content = FrameLayout(this)
        root.addView(content, ui.lp(h = 0, weight = 1f))

        root.addView(ui.divider(), ui.lp(h = 1))
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.WHITE); setPadding(0, ui.dp(6), 0, ui.dp(6)) }
        tabNames.forEachIndexed { i, (label, emoji) ->
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(0, ui.dp(6), 0, ui.dp(6))
            }
            box.addView(ui.text(emoji, 20f).apply { gravity = Gravity.CENTER })
            box.addView(ui.text(label, 11f, Pal.muted, true).apply { gravity = Gravity.CENTER })
            box.setOnClickListener { switchTab(i) }
            navBoxes.add(box)
            nav.addView(box, ui.lp(w = 0, weight = 1f))
        }
        root.addView(nav)
        setContentView(root)
        switchTab(0)
    }

    // ---------- navigation ----------

    fun switchTab(i: Int) {
        stack.clear()
        currentTab = i
        if (tabs[i] == null) tabs[i] = makeTab(i)
        show(tabs[i]!!)
    }

    fun push(p: Page) { stack.add(p); show(p) }

    private fun goBack() {
        if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
        show(stack.lastOrNull() ?: tabs[currentTab]!!)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() { if (stack.isNotEmpty()) goBack() else super.onBackPressed() }

    private fun show(p: Page) {
        current = p
        content.removeAllViews()
        content.addView(p.view)
        titleTv.text = p.title
        backBtn.visibility = if (stack.isNotEmpty()) View.VISIBLE else View.GONE
        navBoxes.forEachIndexed { i, box ->
            box.background = if (i == currentTab) ui.rounded(Pal.primarySoft, 16) else null
            (box.getChildAt(1) as TextView).setTextColor(if (i == currentTab) Pal.primary else Pal.muted)
        }
        p.refresh()
    }

    override fun onResume() { super.onResume(); current?.rebuild(); handler.postDelayed(ticker, 2000) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(ticker) }

    // ---------- helpers used by pages ----------

    fun serviceEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    fun batteryOk(): Boolean = (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    fun notifGranted(): Boolean =
        Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun requestNotif() {
        if (Build.VERSION.SDK_INT >= 33 && !notifGranted()) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        current?.rebuild()
    }
}
