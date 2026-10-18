package com.reelect.app

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Daily counts keyed by calendar date (yyyy-MM-dd). "Today" is simply today's key, so the
 * counter starts again from 0 automatically at midnight. Days older than 15 are deleted.
 */
object Store {
    const val IG = "ig"
    const val YT = "yt"
    const val KEY_OVERLAY = "overlay_visible"
    const val KEY_X = "pill_x"
    const val KEY_Y = "pill_y"
    const val KEY_COMPACT = "pill_compact"
    private const val PREFS = "scrollsense"
    private const val KEY_DATA = "data"
    private const val KEEP_DAYS = 15

    data class Day(val date: String, val ig: Int, val yt: Int) {
        val total: Int get() = ig + yt
    }

    fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun fmt() = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    fun today(): String = fmt().format(Date())

    private fun cutoff(): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -(KEEP_DAYS - 1)) // today + 14 previous days
        return fmt().format(cal.time)
    }

    @Synchronized
    private fun load(c: Context): JSONObject =
        try { JSONObject(prefs(c).getString(KEY_DATA, "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    private fun pruned(all: JSONObject): JSONObject {
        val limit = cutoff()
        val drop = mutableListOf<String>()
        val it = all.keys()
        while (it.hasNext()) { val k = it.next(); if (k < limit) drop.add(k) }
        drop.forEach { all.remove(it) }
        return all
    }

    @Synchronized
    fun prune(c: Context) {
        prefs(c).edit().putString(KEY_DATA, pruned(load(c)).toString()).apply()
    }

    @Synchronized
    fun increment(c: Context, app: String) {
        val all = load(c)
        val t = today()
        val day = all.optJSONObject(t) ?: JSONObject()
        day.put(app, day.optInt(app, 0) + 1)
        all.put(t, day)
        prefs(c).edit().putString(KEY_DATA, pruned(all).toString()).apply()
    }

    fun todayCounts(c: Context): Pair<Int, Int> {
        val d = load(c).optJSONObject(today())
        return Pair(d?.optInt(IG, 0) ?: 0, d?.optInt(YT, 0) ?: 0)
    }

    /** Exactly 15 entries, oldest first, ending with today (missing days are zeros). */
    fun last15(c: Context): List<Day> {
        val all = load(c)
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -(KEEP_DAYS - 1))
        val out = mutableListOf<Day>()
        repeat(KEEP_DAYS) {
            val k = fmt().format(cal.time)
            val d = all.optJSONObject(k)
            out.add(Day(k, d?.optInt(IG, 0) ?: 0, d?.optInt(YT, 0) ?: 0))
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return out
    }

    fun overlayEnabled(c: Context) = prefs(c).getBoolean(KEY_OVERLAY, true)
    fun setOverlay(c: Context, v: Boolean) = prefs(c).edit().putBoolean(KEY_OVERLAY, v).apply()
}
