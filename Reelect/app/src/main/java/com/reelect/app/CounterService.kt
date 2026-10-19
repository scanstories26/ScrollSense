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
import android.util.TypedValue
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
 * Watches Instagram Reels and YouTube Shorts and powers every live feature:
 * counting + time spent, the floating counter, limit nudges, friction mode, bedtime guard and intent check.
 */
class CounterService : AccessibilityService() {

    companion object {
        const val IG_PKG = "com.instagram.android"
        const val YT_PKG = "com.google.android.youtube"

        // View-ids of the Reels / Shorts player. They can change with app updates (see Diagnostics).
        private val IG_IDS = listOf("clips_viewer_view_pager", "clips_viewer_fragment_container", "clips_viewer_clips_tab")
        private val YT_IDS = listOf("reel_recycler", "reel_player_page_container", "reel_watch_fragment_root")

        private const val EVAL_GAP_ACTIVE = 350L
        private const val EVAL_GAP_IDLE = 1000L
        private const val LEAVE_MS = 1500L
        private const val SETTLE_MS = 800L
        private const val MIN_GAP = 1200L
        private const val SUPPRESS_MS = 2500L
        private const val FAST_MS = 3500L          // a video watched for less than this is a "quick flick"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val trackers = HashMap<String, Tracker>()
    private var receiverRegistered = false
    private lateinit var overlays: Overlays

    // intent check state
    private var intentEndAt = 0L
    private var intentCooldownUntil = 0L

    // ---------- lifecycle ----------

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        overlays = Overlays(this)
        trackers[IG_PKG] = Tracker(IG_PKG, Store.IG, "Reels", IG_IDS)
        trackers[YT_PKG] = Tracker(YT_PKG, Store.YT, "Shorts", YT_IDS)
        Store.prune(this)
        Store.prefs(this).registerOnSharedPreferenceChangeListener(prefListener)
        registerDateReceiver()
        scheduleMidnightRefresh()
        Notifier.schedule(this)
        syncOverlay()
        DebugLog.add("Service connected")
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        trackers[pkg]?.onEvent(e)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean { cleanup(); return super.onUnbind(intent) }
    override fun onDestroy() { cleanup(); super.onDestroy() }

    private fun cleanup() {
        trackers.values.forEach { it.flushTime() }
        handler.removeCallbacksAndMessages(null)
        Store.prefs(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        if (receiverRegistered) {
            try { unregisterReceiver(dateReceiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
        if (::overlays.isInitialized) overlays.clearAll()
        removePill()
    }

    // ---------- status (limit / bedtime) ----------

    private class Status(val level: Int, val over: Int, val bed: Boolean)

    /** level 0 = fine, 1 = near limit (amber), 2 = past limit (red). */
    private fun status(): Status {
        val d = Store.todayDay(this)
        val limit = Store.limit(this)
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val bed = Store.inBedtime(this, hour)
        val bl = Store.int(this, Store.KEY_BED_LIMIT, 5)
        val overDay = d.total - limit
        val overBed = if (bed) d.late - bl else 0
        val level = when {
            overDay > 0 || overBed > 0 -> 2
            d.total * 5 >= limit * 4 || (bed && d.late > 0 && d.late >= maxOf(1, bl - 2)) -> 1
            else -> 0
        }
        return Status(level, maxOf(0, overDay, overBed), bed)
    }

    // ---------- reactions to a counted video ----------

    private fun onCounted() {
        val d = Store.todayDay(this)
        val limit = Store.limit(this)
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val bed = Store.inBedtime(this, hour)
        val bl = Store.int(this, Store.KEY_BED_LIMIT, 5)

        // daily limit nudges
        val near = (limit * 4 + 4) / 5
        val over = d.total - limit
        if (d.total >= limit && !Store.flagToday(this, "n100")) {
            Store.setFlagToday(this, "n100")
            Store.setFlagToday(this, "n80")
            overlays.banner("🛑", "You've reached your daily limit of $limit. Time for a break? 🌿", Pal.coral)
        } else if (d.total >= near && d.total < limit && !Store.flagToday(this, "n80")) {
            Store.setFlagToday(this, "n80")
            overlays.banner("🟡", "Almost at your limit: ${d.total} of $limit videos.", Pal.amber)
        } else if (over > 0 && over % 10 == 0) {
            overlays.banner("🌿", "You're $over videos past your limit. Pause for a moment?", Pal.coral)
        }

        // bedtime guard
        if (bed && d.late > 0) {
            val after = hour < 5
            if (d.late == 1) overlays.banner("🌙", "Still awake? Your bedtime limit is $bl videos tonight.", Pal.lav)
            else if (d.late == bl) overlays.banner("😴", "That's your bedtime limit. Time to wind down.", Pal.lav)
            else if (d.late > bl && (d.late - bl) % 3 == 0)
                overlays.banner("🌙", if (after) "It's after midnight. Sleep helps more than one more video." else "Still scrolling? Rest is calling.", Pal.lav)
        }
        updatePill()
        syncFriction()
    }

    private fun anyActive() = trackers.values.any { it.active }

    /** Grey veil: only while inside Reels/Shorts, only when over the limit, and it deepens with every extra video. */
    private fun syncFriction() {
        if (!::overlays.isInitialized) return
        val st = status()
        val on = Store.bool(this, Store.KEY_FRICTION, true) && st.over > 0 && anyActive()
        overlays.setFriction(if (on) minOf(0.6f, 0.10f + 0.025f * st.over) else 0f)
    }

    // ---------- intent check ----------

    private fun onPlayerOpened() {
        syncFriction()
        if (!Store.bool(this, Store.KEY_INTENT, true)) return
        val now = SystemClock.uptimeMillis()
        if (intentEndAt > 0 || now < intentCooldownUntil || overlays.dialogShowing) return
        intentCooldownUntil = now + 20_000L
        overlays.intentDialog { minutes ->
            if (minutes > 0) {
                intentEndAt = SystemClock.uptimeMillis() + minutes * 60_000L
                handler.removeCallbacks(intentRunnable)
                handler.postDelayed(intentRunnable, 3000)
                overlays.banner("⏱️", "Timer set for $minutes minutes. Enjoy mindfully!", Pal.primary)
            }
        }
    }

    private fun onPlayerClosed() {
        if (anyActive()) return
        intentEndAt = 0L
        handler.removeCallbacks(intentRunnable)
        overlays.closeDialog()
        syncFriction()
    }

    private val intentRunnable = object : Runnable {
        override fun run() {
            if (intentEndAt <= 0L) return
            if (anyActive() && SystemClock.uptimeMillis() >= intentEndAt && !overlays.dialogShowing) {
                overlays.timeUpDialog(
                    onMore = { intentEndAt = SystemClock.uptimeMillis() + 5 * 60_000L },
                    onDone = { intentEndAt = 0L; performGlobalAction(GLOBAL_ACTION_HOME) }
                )
            }
            handler.postDelayed(this, 3000)
        }
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
        private var lastTick = 0L
        private var pendingMs = 0L
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

        // ----- time spent: add the gap between evaluations while the player is on screen -----
        private fun tick(now: Long) {
            if (lastTick > 0L) {
                val dlt = now - lastTick
                if (dlt in 1..4000) pendingMs += dlt
            }
            lastTick = now
            if (pendingMs >= 5000L) flushTime()
        }

        fun discardPending() { pendingMs = 0L }

        fun flushTime() {
            val s = (pendingMs / 1000L).toInt()
            if (s > 0) { Store.addTime(this@CounterService, key, s); pendingMs -= s * 1000L }
        }

        fun evaluate() {
            val now = SystemClock.uptimeMillis()
            val root = rootInActiveWindow
            if (root == null) { if (active) startTimer(900); return }   // never flip state on a null tree
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
                lastTick = 0L
                suppressUntil = now + SUPPRESS_MS
                DebugLog.add("$label: player opened")
                count("open")
                onPlayerOpened()
            }
            tick(now)

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
                lastTick = 0L
                flushTime()
                DebugLog.add("$label: player closed")
                onPlayerClosed()
            } else {
                startTimer(LEAVE_MS)
            }
        }

        private fun handleSignature(sig: String, viaRoot: Boolean, now: Long) {
            if (sig.isEmpty()) return
            if (lastSig.isEmpty() || now < suppressUntil) { lastSig = sig; pendingSig = ""; return }
            if (sig == lastSig) { pendingSig = ""; return }
            if (pagerSeen) { lastSig = sig; return }
            if (viaRoot && scrollSeen && now - lastScrollAt > 3000L) { lastSig = sig; return }
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
            val now = SystemClock.uptimeMillis()
            val fast = reason != "open" && lastCountAt > 0L && now - lastCountAt < FAST_MS
            lastCountAt = now
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            Store.recordVideo(this@CounterService, key, hour, fast, Store.inBedtime(this@CounterService, hour))
            DebugLog.add("$label +1 ($reason${if (fast) ", quick" else ""})")
            onCounted()
        }
    }

    // ---------- midnight reset (data is keyed by date; this just refreshes the pill) ----------

    private val dateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            Store.prune(this@CounterService)
            updatePill()
            syncFriction()
            Notifier.schedule(this@CounterService)
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
    private var moodTv: TextView? = null
    private var igTv: TextView? = null
    private var ytTv: TextView? = null
    private var sumTv: TextView? = null
    private var divider: View? = null
    private var compact = false

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            Store.KEY_OVERLAY -> syncOverlay()
            Store.KEY_LIMIT, Store.KEY_FRICTION, Store.KEY_BED_ON, Store.KEY_BED_LIMIT, Store.KEY_BED_START, Store.KEY_BED_END -> { updatePill(); syncFriction() }
            Store.KEY_NOTIF, Store.KEY_SUMMARY_HOUR -> Notifier.schedule(this)
            Store.KEY_RESET -> { trackers.values.forEach { it.discardPending() }; updatePill(); syncFriction() }
        }
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
        }
        fun lbl() = TextView(this).apply {
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        val mood = lbl(); val ig = lbl(); val yt = lbl(); val sum = lbl()
        val div = View(this).apply { setBackgroundColor(Color.parseColor("#66FFFFFF")) }
        root.addView(mood)
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
                        compact = !compact
                        Store.prefs(this).edit().putBoolean(Store.KEY_COMPACT, compact).apply()
                        applyCompact()
                    }
                }
            }
            true
        }

        moodTv = mood; igTv = ig; ytTv = yt; sumTv = sum; divider = div
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

    /** Colour = state (blue ok, amber near limit, red over). With friction mode on, the pill grows as you go over. */
    private fun updatePill() {
        val root = pill ?: return
        val d = Store.todayDay(this)
        val st = status()
        val limit = Store.limit(this)
        val grow = if (Store.bool(this, Store.KEY_FRICTION, true) && st.over > 0) 1f + minOf(0.8f, 0.05f * st.over) else 1f
        val fill = when (st.level) { 2 -> "#F4617F"; 1 -> "#F5A524"; else -> "#3F6BE8" }

        root.setPadding(dp((14 * grow).toInt()), dp((8 * grow).toInt()), dp((14 * grow).toInt()), dp((8 * grow).toInt()))
        root.background = GradientDrawable().apply {
            cornerRadius = dp(26).toFloat() * grow
            setColor(Color.parseColor(fill))
            setStroke(dp(2), Color.WHITE)
        }
        for (tv in listOf(moodTv, igTv, ytTv, sumTv)) tv?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * grow)
        moodTv?.text = Stats.moodEmoji(Stats.mood(d, limit)) + "  "
        igTv?.text = "📸 ${d.ig}"
        ytTv?.text = "▶ ${d.yt}"
        sumTv?.text = "Σ ${d.total}"
        try { wm?.updateViewLayout(root, params) } catch (_: Exception) {}
    }

    private fun removePill() {
        pill?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        pill = null
    }
}
