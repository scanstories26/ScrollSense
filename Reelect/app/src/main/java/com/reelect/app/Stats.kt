package com.reelect.app

import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Pure calculations: score, mood, distance, time-cost translations, streaks, trends. */
object Stats {

    // ---------- score ----------
    class Score(val value: Int, val volume: Int, val late: Int, val fast: Int) {
        val label: String get() = when {
            value >= 85 -> "Mindful"
            value >= 70 -> "Balanced"
            value >= 50 -> "Drifting"
            else -> "Doom-scrolling"
        }
    }

    /** 100 minus penalties for volume, late-night videos and quick flicks (under ~3.5 s each). */
    fun score(d: Store.Day, limit: Int): Score {
        if (d.total == 0) return Score(100, 0, 0, 0)
        val v = d.total.toFloat() / max(1, limit)
        val vol = min(50f, 30f * v).roundToInt()
        val late = min(25f, d.late * 2.5f).roundToInt()
        val fastRatio = d.fast.toFloat() / d.total
        val fast = min(25f, fastRatio * 40f).roundToInt()
        return Score((100 - vol - late - fast).coerceIn(0, 100), vol, late, fast)
    }

    // ---------- mascot ----------
    enum class Mood { HAPPY, OKAY, TIRED, DIZZY }

    fun mood(d: Store.Day, limit: Int): Mood {
        if (d.total == 0) return Mood.HAPPY
        val s = score(d, limit).value
        val fastRatio = if (d.total >= 8) d.fast.toFloat() / d.total else 0f
        return when {
            fastRatio >= 0.5f -> Mood.DIZZY
            d.late >= 3 || s < 50 -> Mood.TIRED
            s >= 75 -> Mood.HAPPY
            else -> Mood.OKAY
        }
    }

    fun moodEmoji(m: Mood) = when (m) { Mood.HAPPY -> "😊"; Mood.OKAY -> "😐"; Mood.TIRED -> "😴"; Mood.DIZZY -> "😵" }

    fun moodMessage(m: Mood, total: Int) = when {
        total == 0 -> "Fresh start! Nothing scrolled yet today."
        m == Mood.HAPPY -> "Nice and mindful today. Keep it up!"
        m == Mood.OKAY -> "Getting close to your limit. A little pause helps."
        m == Mood.TIRED -> "I'm sleepy… heavy or late scrolling wears me out."
        else -> "Whoa, that's fast! Slow down and enjoy each video."
    }

    // ---------- distance ----------
    private const val METERS_PER_VIDEO = 0.16   // about one phone screen (~16 cm) of content per video

    fun distance(videos: Int): Double = videos * METERS_PER_VIDEO

    fun fmtDistance(m: Double): String = when {
        m >= 1000 -> String.format(Locale.US, "%.2f km", m / 1000)
        m >= 10 -> String.format(Locale.US, "%.0f m", m)
        else -> String.format(Locale.US, "%.1f m", m)
    }

    private val landmarks = listOf(
        "a bicycle" to 1.8, "a car" to 4.5, "a bus" to 12.0, "a cricket pitch" to 20.1,
        "a blue whale" to 25.0, "the Qutub Minar" to 72.5, "a football field" to 105.0,
        "the Eiffel Tower" to 330.0, "the Burj Khalifa" to 828.0, "Mount Everest" to 8849.0
    )

    fun distanceCompare(m: Double): String {
        if (m <= 0.0) return "Nothing yet. A perfectly flat day."
        val fit = landmarks.lastOrNull { it.second <= m }
        if (fit == null) return "about ${((m / landmarks[0].second) * 100).roundToInt()}% of a bicycle"
        val times = m / fit.second
        return if (times < 1.5) "about as long as ${fit.first}"
        else if (times >= 10) "about ${times.roundToInt()} × ${fit.first}"
        else "about ${String.format(Locale.US, "%.1f", times)} × ${fit.first}"
    }

    // ---------- time ----------
    fun fmtDuration(sec: Int): String = when {
        sec < 60 -> "${sec}s"
        sec < 3600 -> "${sec / 60} min"
        else -> "${sec / 3600} h ${(sec % 3600) / 60} min"
    }

    class Equiv(val emoji: String, val value: String, val label: String)

    fun timeEquivalents(sec: Int): List<Equiv> {
        val m = sec / 60.0
        fun f(x: Double) = String.format(Locale.US, "%.1f", x)
        return listOf(
            Equiv("📖", f(m / 9.0), "book chapters"),
            Equiv("🏃", f(m / 6.0) + " km", "of jogging"),
            Equiv("🧘", f(m / 10.0), "10-min meditations"),
            Equiv("📺", f(m / 22.0), "sitcom episodes")
        )
    }

    fun hourLabel(h: Int): String {
        val hh = h % 12
        return (if (hh == 0) 12 else hh).toString() + (if (h < 12) " AM" else " PM")
    }

    // ---------- streaks ----------
    /** (current, best). Only days since the app was first used count; today counts while it is under the limit. */
    fun streaks(days: List<Store.Day>, limit: Int, first: String): Pair<Int, Int> {
        var run = 0
        var best = 0
        for (d in days) {
            if (d.date < first) { run = 0; continue }
            if (d.total <= limit) { run++; best = max(best, run) } else run = 0
        }
        return Pair(run, best)
    }

    // ---------- trends ----------
    class Week(val thisTotal: Int, val prevTotal: Int, val thisSec: Int, val prevSec: Int) {
        val changePct: Int get() = if (prevTotal == 0) (if (thisTotal == 0) 0 else 100)
        else (((thisTotal - prevTotal) * 100f) / prevTotal).roundToInt()
    }

    fun weekCompare(days: List<Store.Day>): Week {
        val t = days.takeLast(7)
        val p = days.dropLast(7).takeLast(7)
        return Week(t.sumOf { it.total }, p.sumOf { it.total }, t.sumOf { it.sec }, p.sumOf { it.sec })
    }

    fun hourTotals(days: List<Store.Day>): IntArray {
        val out = IntArray(24)
        days.forEach { d -> for (i in 0 until 24) out[i] += d.hours[i] }
        return out
    }

    fun busiestHour(totals: IntArray): Int? {
        val mx = totals.maxOrNull() ?: 0
        return if (mx == 0) null else totals.indexOf(mx)
    }
}
