package com.reelect.app

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView

/**
 * Watches Instagram + YouTube. When the Reels / Shorts pager is on screen and the user
 * swipes to a new video, the counter goes up. A draggable pill shows the totals over any app.
 */
class CounterService : AccessibilityService() {

    companion object {
        const val IG_PKG = "com.instagram.android"
        const val YT_PKG = "com.google.android.youtube"

        // View-ids of the vertical Reels / Shorts pagers. These can change when the apps update;
        // add new ids here if counting stops working (find them with Android Studio Layout Inspector).
        private val IG_IDS = listOf("clips_viewer_view_pager", "clips_viewer_fragment_container")
        private val YT_IDS = listOf("reel_recycler", "reel_player_page_container", "reel_watch_fragment_root")

        private const val SETTLE_MS = 800L   // swipe must settle this long to count as "watched"
        private const val CHECK_GAP_MS = 600L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val pending = HashMap<String, Runnable>()
    private val inReel = HashMap<String, Boolean>()
    private var lastCheck = 0L

    private var wm: WindowManager? = null
    private var pill: TextView? = null
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, // no extra "draw over apps" permission needed
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START; x = 24; y = 220 }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Store.KEY_OVERLAY) syncOverlay()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        Store.prefs(this).registerOnSharedPreferenceChangeListener(prefListener)
        syncOverlay()
    }

    override fun onDestroy() {
        Store.prefs(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        removePill()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    // ---------- event handling ----------

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        if (pkg != IG_PKG && pkg != YT_PKG) return

        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) inReel[pkg] = false

        // 1) user swiped the Reels/Shorts pager -> count once it settles
        if (e.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED && isPager(e, pkg)) {
            pending[pkg]?.let { handler.removeCallbacks(it) }
            val r = Runnable { count(pkg) }
            pending[pkg] = r
            handler.postDelayed(r, SETTLE_MS)
            inReel[pkg] = true
            return
        }

        // 2) first reel/short shown when entering the player (throttled check)
        val now = System.currentTimeMillis()
        if (now - lastCheck < CHECK_GAP_MS) return
        lastCheck = now
        val on = onReelScreen(pkg)
        if (on && inReel[pkg] != true) count(pkg)
        inReel[pkg] = on
    }

    private fun idsFor(pkg: String) = if (pkg == IG_PKG) IG_IDS else YT_IDS

    private fun isPager(e: AccessibilityEvent, pkg: String): Boolean {
        val name = e.source?.viewIdResourceName ?: return false
        return idsFor(pkg).any { name == "$pkg:id/$it" }
    }

    private fun onReelScreen(pkg: String): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != pkg) return false
        return idsFor(pkg).any { !root.findAccessibilityNodeInfosByViewId("$pkg:id/$it").isNullOrEmpty() }
    }

    private fun count(pkg: String) {
        Store.increment(this, if (pkg == IG_PKG) Store.IG else Store.YT)
        updatePill()
    }

    // ---------- overlay ----------

    private fun syncOverlay() { if (Store.overlayEnabled(this)) showPill() else removePill() }

    private fun showPill() {
        if (pill != null) { updatePill(); return }
        val tv = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(32, 18, 32, 18)
            background = GradientDrawable().apply {
                cornerRadius = 60f
                setColor(Color.parseColor("#CC1B1B2F"))
                setStroke(3, Color.parseColor("#FF4D8D"))
            }
        }
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
        tv.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { sx = params.x; sy = params.y; tx = ev.rawX; ty = ev.rawY }
                MotionEvent.ACTION_MOVE -> {
                    params.x = sx + (ev.rawX - tx).toInt()
                    params.y = sy + (ev.rawY - ty).toInt()
                    wm?.updateViewLayout(tv, params)
                }
            }
            true
        }
        try { wm?.addView(tv, params); pill = tv; updatePill() } catch (_: Exception) {}
    }

    private fun updatePill() {
        val (ig, yt) = Store.todayCounts(this)
        pill?.text = "📸 $ig   ▶ $yt"
    }

    private fun removePill() {
        pill?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        pill = null
    }
}
