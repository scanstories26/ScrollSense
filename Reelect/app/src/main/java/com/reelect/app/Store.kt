package com.reelect.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Everything is stored locally. One JSON record per calendar day (yyyy-MM-dd):
 * counts, seconds watched, a 24-slot hourly histogram, quick-flick count and late-night count.
 * "Today" is simply today's key, so counters restart from 0 at midnight. Days older than 15 are deleted.
 */
object Store {
    const val IG = "ig"
    const val YT = "yt"

    // overlay position / state
    const val KEY_OVERLAY = "overlay_visible"
    const val KEY_X = "pill_x"
    const val KEY_Y = "pill_y"
    const val KEY_COMPACT = "pill_compact"

    // goals + features
    const val KEY_LIMIT = "limit"
    const val KEY_FRICTION = "friction"
    const val KEY_INTENT = "intent_check"
    const val KEY_NOTIF = "notif"
    const val KEY_SUMMARY_HOUR = "summary_hour"
    const val KEY_BED_ON = "bed_on"
    const val KEY_BED_START = "bed_start"
    const val KEY_BED_END = "bed_end"
    const val KEY_BED_LIMIT = "bed_limit"
    const val KEY_RESET = "reset_tick"     // changes whenever all data is deleted, so the live counter refreshes

    private const val PREFS = "scrollsense"
    private const val KEY_DATA = "data"
    private const val KEY_FIRST = "first_day"
    private const val KEEP_DAYS = 15

    class Day(
        val date: String, val ig: Int, val yt: Int, val igSec: Int, val ytSec: Int,
        val hours: IntArray, val fast: Int, val late: Int
    ) {
        val total: Int get() = ig + yt
        val sec: Int get() = igSec + ytSec
    }

    fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun fmt() = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    fun today(): String = fmt().format(Date())

    // ---------- generic settings ----------
    fun bool(c: Context, k: String, def: Boolean) = prefs(c).getBoolean(k, def)
    fun setBool(c: Context, k: String, v: Boolean) = prefs(c).edit().putBoolean(k, v).apply()
    fun int(c: Context, k: String, def: Int) = prefs(c).getInt(k, def)
    fun setInt(c: Context, k: String, v: Int) = prefs(c).edit().putInt(k, v).apply()

    fun limit(c: Context) = int(c, KEY_LIMIT, 50)
    fun overlayEnabled(c: Context) = bool(c, KEY_OVERLAY, true)
    fun setOverlay(c: Context, v: Boolean) = setBool(c, KEY_OVERLAY, v)

    /** True when [hour] falls inside the user's bedtime window (which may cross midnight). */
    fun inBedtime(c: Context, hour: Int): Boolean {
        if (!bool(c, KEY_BED_ON, true)) return false
        val s = int(c, KEY_BED_START, 23)
        val e = int(c, KEY_BED_END, 6)
        return if (s > e) (hour >= s || hour < e) else (hour in s until e)
    }

    fun firstDay(c: Context): String = prefs(c).getString(KEY_FIRST, null) ?: today()
    fun ensureFirstDay(c: Context) {
        if (prefs(c).getString(KEY_FIRST, null) == null) prefs(c).edit().putString(KEY_FIRST, today()).apply()
    }

    /** One-per-day flags, used so each nudge shows only once a day. */
    fun flagToday(c: Context, key: String) = prefs(c).getString("flag_$key", "") == today()
    fun setFlagToday(c: Context, key: String) = prefs(c).edit().putString("flag_$key", today()).apply()

    // ---------- day records ----------
    private fun cutoff(): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -(KEEP_DAYS - 1))
        return fmt().format(cal.time)
    }

    @Synchronized
    private fun load(c: Context): JSONObject =
        try { JSONObject(prefs(c).getString(KEY_DATA, "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    private fun save(c: Context, all: JSONObject) {
        val limit = cutoff()
        val drop = mutableListOf<String>()
        val it = all.keys()
        while (it.hasNext()) { val k = it.next(); if (k < limit) drop.add(k) }
        drop.forEach { all.remove(it) }
        prefs(c).edit().putString(KEY_DATA, all.toString()).apply()
    }

    @Synchronized fun prune(c: Context) { ensureFirstDay(c); save(c, load(c)) }
    @Synchronized fun clearAll(c: Context) {
        prefs(c).edit()
            .remove(KEY_DATA)
            .remove("flag_n80").remove("flag_n100")
            .putString(KEY_FIRST, today())
            .putLong(KEY_RESET, System.currentTimeMillis())
            .apply()
    }

    private fun todayObj(all: JSONObject): JSONObject = all.optJSONObject(today()) ?: JSONObject()

    @Synchronized
    fun recordVideo(c: Context, app: String, hour: Int, fast: Boolean, late: Boolean) {
        val all = load(c)
        val d = todayObj(all)
        d.put(app, d.optInt(app, 0) + 1)
        val h = d.optJSONArray("h") ?: JSONArray().also { a -> for (i in 0 until 24) a.put(0) }
        h.put(hour, h.optInt(hour, 0) + 1)
        d.put("h", h)
        if (fast) d.put("f", d.optInt("f", 0) + 1)
        if (late) d.put("l", d.optInt("l", 0) + 1)
        all.put(today(), d)
        ensureFirstDay(c)
        save(c, all)
    }

    @Synchronized
    fun addTime(c: Context, app: String, sec: Int) {
        if (sec <= 0) return
        val all = load(c)
        val d = todayObj(all)
        val k = if (app == IG) "igS" else "ytS"
        d.put(k, d.optInt(k, 0) + sec)
        all.put(today(), d)
        save(c, all)
    }

    private fun parse(date: String, o: JSONObject?): Day {
        val h = IntArray(24)
        o?.optJSONArray("h")?.let { a -> for (i in 0 until minOf(24, a.length())) h[i] = a.optInt(i, 0) }
        return Day(
            date, o?.optInt("ig", 0) ?: 0, o?.optInt("yt", 0) ?: 0,
            o?.optInt("igS", 0) ?: 0, o?.optInt("ytS", 0) ?: 0, h,
            o?.optInt("f", 0) ?: 0, o?.optInt("l", 0) ?: 0
        )
    }

    fun todayDay(c: Context): Day = parse(today(), load(c).optJSONObject(today()))

    /** Exactly 15 entries, oldest first, ending with today (missing days are zeros). */
    fun last15(c: Context): List<Day> {
        val all = load(c)
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -(KEEP_DAYS - 1))
        val out = mutableListOf<Day>()
        repeat(KEEP_DAYS) {
            val k = fmt().format(cal.time)
            out.add(parse(k, all.optJSONObject(k)))
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return out
    }

    fun csv(c: Context): String {
        val sb = StringBuilder("date,reels,shorts,reels_seconds,shorts_seconds,quick_flicks,late_night\n")
        last15(c).forEach { sb.append("${it.date},${it.ig},${it.yt},${it.igSec},${it.ytSec},${it.fast},${it.late}\n") }
        return sb.toString()
    }
}
