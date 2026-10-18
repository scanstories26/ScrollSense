package com.reelect.app

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/** Stores daily counts as JSON in SharedPreferences. Data older than 15 days is pruned automatically. */
object Store {
    const val IG = "ig"
    const val YT = "yt"
    const val KEY_OVERLAY = "overlay_visible"
    private const val PREFS = "reelect"
    private const val KEY_DATA = "data"
    private const val KEEP_DAYS = 15

    fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun fmt() = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    fun today(): String = fmt().format(Date())

    private fun cutoff(): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -(KEEP_DAYS - 1)) // today + 14 previous days = 15 days
        return fmt().format(cal.time)
    }

    @Synchronized
    private fun load(c: Context): JSONObject =
        try { JSONObject(prefs(c).getString(KEY_DATA, "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    @Synchronized
    fun increment(c: Context, app: String) {
        val all = load(c)
        val t = today()
        val day = all.optJSONObject(t) ?: JSONObject()
        day.put(app, day.optInt(app, 0) + 1)
        all.put(t, day)
        // prune old days
        val limit = cutoff()
        val it = all.keys()
        val remove = mutableListOf<String>()
        while (it.hasNext()) { val k = it.next(); if (k < limit) remove.add(k) }
        remove.forEach { all.remove(it) }
        prefs(c).edit().putString(KEY_DATA, all.toString()).apply()
    }

    fun todayCounts(c: Context): Pair<Int, Int> {
        val d = load(c).optJSONObject(today())
        return Pair(d?.optInt(IG, 0) ?: 0, d?.optInt(YT, 0) ?: 0)
    }

    /** Newest first: (date, reels, shorts) */
    fun history(c: Context): List<Triple<String, Int, Int>> {
        val all = load(c)
        val limit = cutoff()
        return all.keys().asSequence().filter { it >= limit }.sortedDescending().map {
            val d = all.getJSONObject(it)
            Triple(it, d.optInt(IG, 0), d.optInt(YT, 0))
        }.toList()
    }

    fun overlayEnabled(c: Context) = prefs(c).getBoolean(KEY_OVERLAY, true)
    fun setOverlay(c: Context, v: Boolean) = prefs(c).edit().putBoolean(KEY_OVERLAY, v).apply()
}
