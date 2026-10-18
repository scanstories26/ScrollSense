package com.reelect.app

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var today: TextView
    private lateinit var history: TextView
    private lateinit var toggle: Switch
    private var building = true

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            setBackgroundColor(Color.parseColor("#F6F3FF"))
        }
        fun tv(size: Float, bold: Boolean = false, color: String = "#1B1B2F") = TextView(this).apply {
            textSize = size; setTextColor(Color.parseColor(color))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, pad / 3, 0, pad / 3)
        }

        root.addView(tv(30f, true, "#6C2BD9").apply { text = "Reelect" })
        root.addView(tv(14f, color = "#555555").apply { text = "Reflect on every reel & short you watch." })

        status = tv(15f, true)
        root.addView(status)
        root.addView(Button(this).apply {
            text = "Open Accessibility Settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })

        toggle = Switch(this).apply {
            text = "Show floating counter"
            textSize = 16f
            isChecked = Store.overlayEnabled(this@MainActivity)
            setPadding(0, pad, 0, pad)
            setOnCheckedChangeListener { _, on -> if (!building) Store.setOverlay(this@MainActivity, on) }
        }
        root.addView(toggle)

        root.addView(tv(16f, true).apply { text = "Today" })
        today = tv(22f, true, "#FF4D8D"); root.addView(today)

        root.addView(tv(16f, true).apply { text = "Last 15 days" })
        history = tv(14f)
        root.addView(ScrollView(this).apply { addView(history) })

        setContentView(root)
        building = false
    }

    override fun onResume() {
        super.onResume()
        val on = serviceEnabled()
        status.text = if (on) "● Tracking is ON" else "● Tracking is OFF — enable Reelect in Accessibility"
        status.setTextColor(Color.parseColor(if (on) "#1E8E3E" else "#D93025"))
        val (ig, yt) = Store.todayCounts(this)
        today.text = "📸 Reels: $ig     ▶ Shorts: $yt"
        val rows = Store.history(this)
        history.text = if (rows.isEmpty()) "No data yet." else
            rows.joinToString("\n") { "${it.first}    Reels ${it.second}    Shorts ${it.third}" }
    }

    private fun serviceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, CounterService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(me, true) }
    }
}
