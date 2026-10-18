package com.reelect.app

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Calendar
import java.util.TreeSet
import kotlin.math.abs

/**
 * Watches Instagram Reels and YouTube Shorts.
 *
 * A "short" is counted when:
 *  - the player is opened (once per visit, with a 1.5 s grace period so flicker can't recount),
 *  - the user swipes to the next video and the swipe settles (0.8 s),
 *  - or, if the app never reports swipe events, the on-screen title/channel text of the video changes.
 */
class CounterService : AccessibilityService() {

    companion object {
        const val IG_PKG = "com.instagram.android"
        const val YT_PKG = "com.google.android.youtube"

        // View-ids of the Reels / Shorts player. They can change with app updates;
        // add new ones here if counting stops (see Diagnostics in the app).
        private val IG_IDS = listOf("clips_viewer_view_pager", "clips_viewer_fragment_container", "clips_viewer_clips_tab")
        private val YT_IDS = listOf("reel_recycler", "reel_player_page_container", "reel_watch_fragment_root")

        private const val EVAL_GAP_ACTIVE = 350L
        private const val EVAL_GAP_IDLE = 1000L
        private const val LEAVE_MS = 1500L
        private const val SETTLE_MS = 800L
        private const val MIN_GAP = 1200L
        private const val SUPPRESS_MS = 2500L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val trackers = HashMap<String, Tracker>()
    private var receiverRegistered = false

    // ---------- lifecycle ----------

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        trackers[IG_PKG] = Tracker(IG_PKG, Store.IG, "Reels", IG_IDS)
        trackers[YT_PKG] = Tracker(YT_PKG, Store.YT, "Shorts", YT_IDS)
        Store.prune(this)
        Store.prefs(this).registerOnSharedPreferenceChangeListener(prefListener)
        registerDateReceiver()
        scheduleMidnightRefresh()
        syncOverlay()
        DebugLog.add("Service connected")
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        trackers[pkg]?.onEvent(e)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        handler.removeCallbacksAndMessages(null)
        Store.prefs(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        if (receiverRegistered) {
            try { unregisterReceiver(dateReceiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
        removePill()
    }

    // ---------- per-app tracker ----------

    private inner class Tracker(val pkg: String, val key: String, val label: String, val ids: List<String>) {
        var active = false
        var offSince = 0L
        var lastSig = ""
        var pendingSig = ""
        var lastCountAt = 0L
        var suppressUntil = 0L
        var lastScrollAt = 0L
        var pagerSeen = false
        var scrollSeen = false
        var evalPosted = false
        private val loggedIds = HashSet<String>()

        private val evalRunnable = Runnable { evalPosted = false; evaluate() }
        private val timerRunnable = Runnable { evaluate() }
        private val settleRunnable = Runnable { onSettled() }

        fun onEvent(e: AccessibilityEvent) {
            val now = SystemClock.uptimeMillis()
            if (e.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
                scrollSeen = true
                lastScrollAt = now
                if (isPager(e)) {
                    if (!pagerSeen) { pagerSeen = true; DebugLog.add("$label: swipe events detected") }
                    handler.removeCallbacks(settleRunnable)
                    handler.postDelayed(settleRunnable, SETTLE_MS)
                }
            }
            if (!evalPosted) {
                evalPosted = true
                handler.postDelayed(evalRunnable, if (active) EVAL_GAP_ACTIVE else EVAL_GAP_IDLE)
            }
        }

        private fun isPager(e: AccessibilityEvent): Boolean {
            var cur: AccessibilityNodeInfo? = e.source
            var hit = false
            var depth = 0
            while (cur != null && depth < 5) {
                val name = cur.viewIdResourceName
                if (name != null) {
                    if (depth == 0 && loggedIds.size < 8 && loggedIds.add(name)) DebugLog.add("$label: scroll from $name")
                    if (ids.any { name == "$pkg:id/$it" }) hit = true
                }
                cur = cur.parent
                depth++
            }
            return hit
        }

        private fun onSettled() {
            if (!active) { evaluate(); return }
            val now = SystemClock.uptimeMillis()
            suppressUntil = now + SUPPRESS_MS
            pendingSig = ""
            if (now - lastCountAt >= MIN_GAP) count("swipe")
        }

        private fun startTimer(ms: Long) {
            handler.removeCallbacks(timerRunnable)
            handler.postDelayed(timerRunnable, ms)
        }

        fun evaluate() {
            val now = SystemClock.uptimeMillis()
            val root = rootInActiveWindow
            if (root == null) { if (active) startTimer(900); return }   // unknown: never flip state on a null tree
            if (root.packageName?.toString() != pkg) { markOff(now); return }

            var container: AccessibilityNodeInfo? = null
            for (id in ids) {
                val l = root.findAccessibilityNodeInfosByViewId("$pkg:id/$id")
                if (!l.isNullOrEmpty()) { container = l[0]; break }
            }
            if (container == null) { markOff(now); return }

            offSince = 0L
            if (!active) {
                active = true
                lastSig = ""
                pendingSig = ""
                suppressUntil = now + SUPPRESS_MS
                DebugLog.add("$label: player opened")
                count("open")
            }

            var sig = signature(container)
            var viaRoot = false
            if (sig.isEmpty()) { sig = signature(root); viaRoot = true }
            handleSignature(sig, viaRoot, now)
            startTimer(if (pendingSig.isNotEmpty()) 500L else 1800L)
        }

        private fun markOff(now: Long) {
            if (!active) return
            if (offSince == 0L) offSince = now
            if (now - offSince >= LEAVE_MS) {
                active = false
                offSince = 0L
                lastSig = ""
                pendingSig = ""
                DebugLog.add("$label: player closed")
            } else {
                startTimer(LEAVE_MS)
            }
        }

        private fun handleSignature(sig: String, viaRoot: Boolean, now: Long) {
            if (sig.isEmpty()) return
            if (lastSig.isEmpty() || now < suppressUntil) { lastSig = sig; pendingSig = ""; return }
            if (sig == lastSig) { pendingSig = ""; return }
            if (pagerSeen) { lastSig = sig; return }                       // swipe events are trusted instead
            if (viaRoot && scrollSeen && now - lastScrollAt > 3000L) { lastSig = sig; return }  // e.g. comments opened
            if (sig == pendingSig) {
                lastSig = sig
                pendingSig = ""
                suppressUntil = now + 1500L
                if (now - lastCountAt >= MIN_GAP) count("new video")
            } else {
                pendingSig = sig
            }
        }

        /** Text fingerprint of what is visible on screen (numbers removed so likes/time don't matter). */
        private fun signature(node: AccessibilityNodeInfo): String {
            val set = TreeSet<String>()
            var budget = 300
            fun walk(n: AccessibilityNodeInfo?, depth: Int) {
                if (n == null || budget <= 0 || depth > 25) return
                budget--
                if (!n.isVisibleToUser) return
                val cn = n.className?.toString() ?: ""
                if (cn.contains("ProgressBar") || cn.contains("SeekBar")) return
                n.text?.toString()?.let { clean(it)?.let(set::add) }
                n.contentDescription?.toString()?.let { clean(it)?.let(set::add) }
                for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
            }
            walk(node, 0)
            return set.joinToString("|")
        }

        private fun clean(s: String): String? {
            val r = s.trim().split(Regex("\\s+")).filter { tok -> tok.none { it.isDigit() } }.joinToString(" ")
            return if (r.length >= 4) r.take(80) else null
        }

        private fun count(reason: String) {
            lastCountAt = SystemClock.uptimeMillis()
            Store.increment(this@CounterService, key)
            updatePill()
            DebugLog.add("$label +1 ($reason)")
        }
    }

    // ---------- midnight reset (data is keyed by date; this just refreshes the pill) ----------

    private val dateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            Store.prune(this@CounterService)
            updatePill()
            DebugLog.add("New day - counters reset")
        }
    }

    private fun registerDateReceiver() {
        if (receiverRegistered) return
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(dateReceiver, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(dateReceiver, f)
        receiverRegistered = true
    }

    private val midnightRunnable = Runnable {
        Store.prune(this)
        updatePill()
        scheduleMidnightRefresh()
    }

    private fun scheduleMidnightRefresh() {
        val cal = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 1); set(Calendar.MILLISECOND, 0)
        }
        handler.removeCallbacks(midnightRunnable)
        handler.postDelayed(midnightRunnable, maxOf(1000L, cal.timeInMillis - System.currentTimeMillis()))
    }

    // ---------- floating counter ----------

    private var wm: WindowManager? = null
    private var pill: LinearLayout? = null
    private var igTv: TextView? = null
    private var ytTv: TextView? = null
    private var sumTv: TextView? = null
    private var divider: View? = null
    private var compact = false

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,   // no "draw over apps" permission needed
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Store.KEY_OVERLAY) syncOverlay()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun syncOverlay() { if (Store.overlayEnabled(this)) showPill() else removePill() }

    private fun showPill() {
        if (pill != null) { updatePill(); return }
        val prefs = Store.prefs(this)
        params.x = prefs.getInt(Store.KEY_X, dp(16))
        params.y = prefs.getInt(Store.KEY_Y, dp(180))
        compact = prefs.getBoolean(Store.KEY_COMPACT, false)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.parseColor("#EB120D26"))
                setStroke(dp(1), Color.parseColor("#887C4DFF"))
            }
        }
        fun lbl(color: String) = TextView(this).apply {
            setTextColor(Color.parseColor(color))
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        val ig = lbl("#FF7AA8")
        val yt = lbl("#B8A4FF")
        val sum = lbl("#FFFFFF")
        val div = View(this).apply { setBackgroundColor(Color.parseColor("#44FFFFFF")) }
        root.addView(ig)
        root.addView(div, LinearLayout.LayoutParams(dp(1), dp(14)).apply { setMargins(dp(10), 0, dp(10), 0) })
        root.addView(yt)
        root.addView(sum)

        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f; var moved = false
        root.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { sx = params.x; sy = params.y; tx = ev.rawX; ty = ev.rawY; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - tx
                    val dy = ev.rawY - ty
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    if (moved) {
                        params.x = maxOf(0, sx + dx.toInt())
                        params.y = maxOf(0, sy + dy.toInt())
                        try { wm?.updateViewLayout(root, params) } catch (_: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        Store.prefs(this).edit().putInt(Store.KEY_X, params.x).putInt(Store.KEY_Y, params.y).apply()
                    } else {
                        compact = !compact                       // tap = collapse / expand
                        Store.prefs(this).edit().putBoolean(Store.KEY_COMPACT, compact).apply()
                        applyCompact()
                    }
                }
            }
            true
        }

        igTv = ig; ytTv = yt; sumTv = sum; divider = div
        try {
            wm?.addView(root, params)
            pill = root
            applyCompact()
            updatePill()
        } catch (e: Exception) {
            DebugLog.add("Overlay failed: ${e.message}")
        }
    }

    private fun applyCompact() {
        val full = if (compact) View.GONE else View.VISIBLE
        igTv?.visibility = full
        divider?.visibility = full
        ytTv?.visibility = full
        sumTv?.visibility = if (compact) View.VISIBLE else View.GONE
    }

    private fun updatePill() {
        val (ig, yt) = Store.todayCounts(this)
        igTv?.text = "📸 $ig"
        ytTv?.text = "▶ $yt"
        sumTv?.text = "Σ ${ig + yt}"
    }

    private fun removePill() {
        pill?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        pill = null
    }
}
