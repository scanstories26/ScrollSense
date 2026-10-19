package com.reelect.app

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** A screen. Read-only pages rebuild when their data changes; interactive pages call rebuild() themselves. */
abstract class Page(val a: MainActivity) {
    val ui: Ui get() = a.ui
    abstract val title: String
    private var lastSig: String? = null

    protected val col = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(a.ui.dp(18), a.ui.dp(6), a.ui.dp(18), a.ui.dp(28))
    }
    val view = ScrollView(a).apply { setBackgroundColor(Pal.bg); isVerticalScrollBarEnabled = false; addView(col) }

    abstract fun build()
    open fun sig(): String = "static"

    fun refresh() { val s = sig(); if (s != lastSig) { lastSig = s; rebuild() } }

    fun rebuild() {
        val y = view.scrollY
        col.removeAllViews()
        build()
        view.post { view.scrollTo(0, y) }
    }

    protected fun add(v: View, top: Int = 12) { col.addView(v, ui.lp(top = top)) }

    protected fun tile(label: String, value: String, accent: Int, bg: Int = Pal.card, stroke: Int? = Pal.line): LinearLayout =
        ui.vbox().apply {
            setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
            background = ui.rounded(bg, 18, stroke)
            addView(ui.text(value, 22f, accent, true))
            addView(ui.text(label, 12f, Pal.muted), ui.lp(top = 2))
        }

    protected fun row(vararg tiles: View): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        tiles.forEachIndexed { i, t -> addView(t, ui.lp(w = 0, weight = 1f, left = if (i == 0) 0 else 10)) }
    }

    protected fun levelColor(total: Int, limit: Int) = when {
        total > limit -> Pal.coral
        total * 5 >= limit * 4 -> Pal.amber
        else -> Pal.mint
    }

    protected fun legend(color: Int, label: String): LinearLayout = ui.hbox().apply {
        addView(View(a).apply { background = ui.rounded(color, 4) }, LinearLayout.LayoutParams(ui.dp(8), ui.dp(8)).apply { rightMargin = ui.dp(5) })
        addView(ui.text(label, 11.5f, Pal.muted))
    }

    protected fun goHome() = a.switchTab(0)
}

// ======================================================================= HOME

class HomePage(a: MainActivity) : Page(a) {
    override val title = "ScrollSense"

    override fun sig(): String {
        val d = Store.todayDay(a)
        return "${d.total}|${d.sec}|${d.fast}|${d.late}|${Store.limit(a)}|${a.serviceEnabled()}|${Store.today()}"
    }

    private fun greeting(): String {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when { h < 5 -> "Good night 🌙"; h < 12 -> "Good morning ☀️"; h < 17 -> "Good afternoon 🌤️"; h < 21 -> "Good evening 🌆"; else -> "Good night 🌙" }
    }

    override fun build() {
        val d = Store.todayDay(a)
        val limit = Store.limit(a)
        val sc = Stats.score(d, limit)
        val mood = Stats.mood(d, limit)
        val days = Store.last15(a)
        val (cur, best) = Stats.streaks(days, limit, Store.firstDay(a))
        val on = a.serviceEnabled()

        val head = ui.hbox()
        val tc = ui.vbox()
        tc.addView(ui.text(greeting(), 13f, Pal.muted))
        tc.addView(ui.text("Your scroll today", 23f, Pal.ink, true))
        head.addView(tc, ui.lp(w = 0, weight = 1f))
        head.addView(if (on) ui.chip("● Tracking on", Pal.mint, Pal.mintSoft) else ui.chip("● Tracking off", Pal.amber, Pal.amberSoft))
        add(head, 6)

        if (!on) {
            val w = ui.card(16, Pal.amberSoft, null)
            w.addView(ui.text("Turn on tracking to start counting", 15f, Pal.ink, true))
            w.addView(ui.text("ScrollSense needs its Accessibility switch on. It only reads Instagram and YouTube.", 12.5f, Pal.muted), ui.lp(top = 4))
            w.addView(ui.primaryBtn("Enable tracking") { a.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, ui.lp(top = 12))
            w.addView(ui.softBtn("Need help? Open setup guide", Pal.amber, Color.WHITE) { a.switchTab(4) }, ui.lp(top = 8))
            add(w)
        }

        // hero: mascot + score
        val hero = ui.hbox().apply {
            setPadding(ui.dp(14), ui.dp(14), ui.dp(16), ui.dp(14))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#DCE8FF"), Color.parseColor("#D6F5EA")))
                .apply { cornerRadius = ui.dpf(24) }
        }
        hero.addView(MascotView(a).apply { this.mood = mood }, LinearLayout.LayoutParams(ui.dp(112), ui.dp(112)))
        val hc = ui.vbox()
        hc.addView(ui.text("SCROLL SCORE", 11.5f, Pal.muted, true).apply { letterSpacing = 0.12f })
        val big = ui.hbox()
        big.addView(ui.text(sc.value.toString(), 42f, Pal.ink, true))
        big.addView(ui.text(" /100", 14f, Pal.muted), ui.lp(w = ui.WRAP, left = 2))
        hc.addView(big)
        val lab = if (sc.value >= 70) ui.chip(sc.label, Pal.mint, Color.WHITE) else if (sc.value >= 50) ui.chip(sc.label, Pal.amber, Color.WHITE) else ui.chip(sc.label, Pal.coral, Color.WHITE)
        hc.addView(lab)
        hc.addView(ui.text(Stats.moodMessage(mood, d.total), 12.5f, Pal.ink), ui.lp(top = 6))
        hero.addView(hc, ui.lp(w = 0, weight = 1f, left = 10))
        ui.tappable(hero) { a.push(ScorePage(a)) }
        add(hero, 14)

        // today tiles
        val left = limit - d.total
        add(row(
            tile("Videos today", d.total.toString(), Pal.primary),
            tile("Time spent", if (d.sec == 0) "0 min" else Stats.fmtDuration(d.sec), Pal.lav),
            tile(if (left >= 0) "Left today" else "Over limit", Math.abs(left).toString(), if (left >= 0) Pal.mint else Pal.coral)
        ))

        // limit progress
        val lc = ui.card()
        val lh = ui.hbox()
        lh.addView(ui.text("Daily limit", 15f, Pal.ink, true), ui.lp(w = 0, weight = 1f))
        lh.addView(ui.text("${d.total} / $limit", 14f, Pal.muted, true))
        lc.addView(lh)
        lc.addView(ProgressBarView(a).apply { progress = d.total.toFloat() / limit; color = levelColor(d.total, limit) }, ui.lp(top = 10))
        val note = when {
            d.total > limit -> "${d.total - limit} videos past your limit. A short break could help 🌿"
            d.total * 5 >= limit * 4 -> "Almost there: ${limit - d.total} videos left."
            else -> "${limit - d.total} videos left. You're doing great."
        }
        lc.addView(ui.text(note, 12.5f, Pal.muted), ui.lp(top = 8))
        ui.tappable(lc) { a.switchTab(2) }
        add(lc)

        // reels / shorts
        val rt = tile("📸 Reels · ${Stats.fmtDuration(d.igSec)}", d.ig.toString(), Pal.reels, Pal.reelsSoft, null)
        val st = tile("▶ Shorts · ${Stats.fmtDuration(d.ytSec)}", d.yt.toString(), Pal.shorts, Pal.shortsSoft, null)
        add(row(rt, st))

        // streak
        val sk = ui.card().apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        sk.addView(ui.text("🔥", 32f))
        val sc2 = ui.vbox()
        sc2.addView(ui.text(if (cur == 0) "No streak yet" else "$cur-day streak", 17f, Pal.ink, true))
        sc2.addView(ui.text("Days under your limit in a row · best $best", 12.5f, Pal.muted), ui.lp(top = 2))
        sk.addView(sc2, ui.lp(w = 0, weight = 1f, left = 12))
        sk.addView(ui.text("›", 24f, Pal.muted))
        ui.tappable(sk) { a.switchTab(2) }
        add(sk)

        // impact teaser
        val im = ui.card()
        im.addView(ui.text("What your scroll adds up to", 15f, Pal.ink, true))
        im.addView(ui.text("📏  ${Stats.fmtDistance(Stats.distance(d.total))} scrolled, ${Stats.distanceCompare(Stats.distance(d.total))}", 13f, Pal.muted), ui.lp(top = 8))
        val e = Stats.timeEquivalents(d.sec)
        im.addView(ui.text("⏳  ${Stats.fmtDuration(d.sec)} = ${e[0].value} ${e[0].label} or ${e[1].value} ${e[1].label}", 13f, Pal.muted), ui.lp(top = 6))
        im.addView(ui.text("See details ›", 13f, Pal.primary, true), ui.lp(top = 10))
        ui.tappable(im) { a.push(ImpactPage(a)) }
        add(im)
    }
}

// ======================================================================= TRENDS

class TrendsPage(a: MainActivity) : Page(a) {
    override val title = "Trends"
    override fun sig(): String { val d = Store.todayDay(a); return "${d.total}|${d.sec}|${Store.limit(a)}|${Store.today()}" }

    override fun build() {
        val days = Store.last15(a)
        val limit = Store.limit(a)
        val w = Stats.weekCompare(days)

        // week vs week
        val wc = ui.card()
        wc.addView(ui.text("This week vs last week", 16f, Pal.ink, true))
        val mx = maxOf(1, w.thisTotal, w.prevTotal).toFloat()
        fun bar(label: String, v: Int, sec: Int, color: Int) {
            val h = ui.hbox()
            h.addView(ui.text(label, 13f, Pal.muted), ui.lp(w = 0, weight = 1f))
            h.addView(ui.text("$v videos · ${Stats.fmtDuration(sec)}", 13f, Pal.ink, true))
            wc.addView(h, ui.lp(top = 12))
            wc.addView(ProgressBarView(a).apply { progress = v / mx; this.color = color }, ui.lp(top = 6))
        }
        bar("This week", w.thisTotal, w.thisSec, Pal.primary)
        bar("Last week", w.prevTotal, w.prevSec, Color.parseColor("#B8C2D9"))
        val pct = w.changePct
        val chip = when {
            w.prevTotal == 0 -> ui.chip("Not enough data yet", Pal.muted, Pal.primarySoft)
            pct < 0 -> ui.chip("↓ ${-pct}% less than last week", Pal.mint, Pal.mintSoft)
            pct > 0 -> ui.chip("↑ $pct% more than last week", Pal.coral, Pal.coralSoft)
            else -> ui.chip("Same as last week", Pal.primary, Pal.primarySoft)
        }
        wc.addView(chip, ui.lp(w = ui.WRAP, top = 14))
        add(wc, 8)

        // daily chart
        val cc = ui.card()
        val ch = ui.hbox()
        ch.addView(ui.text("Last 15 days", 16f, Pal.ink, true), ui.lp(w = 0, weight = 1f))
        ch.addView(legend(Pal.reels, "Reels"))
        ch.addView(legend(Pal.shorts, "Shorts"), ui.lp(w = ui.WRAP, left = 12))
        cc.addView(ch)
        cc.addView(BarChartView(a).apply { data = days; this.limit = limit }, ui.lp(top = 8))
        cc.addView(ui.text("Dashed line = your daily limit ($limit)", 11.5f, Pal.muted), ui.lp(top = 2))
        add(cc)

        // busiest time
        val totals = Stats.hourTotals(days)
        val bh = Stats.busiestHour(totals)
        val bc = ui.card()
        bc.addView(ui.text("Busiest time of day", 16f, Pal.ink, true))
        bc.addView(HourBarsView(a).apply { this.totals = totals }, ui.lp(top = 8))
        bc.addView(ui.text(
            if (bh == null) "Scroll a few videos and your busiest hour will show up here."
            else "You scroll most around ${Stats.hourLabel(bh)} – ${Stats.hourLabel((bh + 1) % 24)} (${totals[bh]} videos in 15 days).",
            13f, Pal.muted), ui.lp(top = 6))
        bc.addView(ui.softBtn("Open full heatmap ›") { a.push(HeatmapPage(a)) }, ui.lp(top = 12))
        add(bc)

        // split
        val ig = days.sumOf { it.ig }; val yt = days.sumOf { it.yt }
        val sp = ui.card()
        sp.addView(ui.text("Reels vs Shorts", 16f, Pal.ink, true))
        val barRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; background = ui.rounded(Color.parseColor("#E9EEF8"), 8) }
        val tot = maxOf(1, ig + yt)
        barRow.addView(View(a).apply { background = ui.rounded(Pal.reels, 8) }, LinearLayout.LayoutParams(0, ui.dp(14), ig.toFloat().coerceAtLeast(0.0001f)))
        barRow.addView(View(a).apply { background = ui.rounded(Pal.shorts, 8) }, LinearLayout.LayoutParams(0, ui.dp(14), yt.toFloat().coerceAtLeast(0.0001f)))
        sp.addView(barRow, ui.lp(top = 12))
        val s2 = ui.hbox()
        s2.addView(ui.text("📸 Reels ${ig * 100 / tot}% · ${days.sumOf { it.igSec }.let { Stats.fmtDuration(it) }}", 12.5f, Pal.reels, true), ui.lp(w = 0, weight = 1f))
        s2.addView(ui.text("▶ Shorts ${yt * 100 / tot}% · ${days.sumOf { it.ytSec }.let { Stats.fmtDuration(it) }}", 12.5f, Pal.shorts, true))
        sp.addView(s2, ui.lp(top = 8))
        add(sp)

        // quick numbers
        val active = days.filter { it.date >= Store.firstDay(a) }
        val avg = if (active.isEmpty()) 0 else active.sumOf { it.total } / active.size
        val peak = days.maxByOrNull { it.total }
        add(row(
            tile("Average per day", avg.toString(), Pal.primary),
            tile(if (peak != null && peak.total > 0) "Peak day (${peak.date.substring(5)})" else "Peak day", (peak?.total ?: 0).toString(), Pal.coral),
            tile("Total time", Stats.fmtDuration(days.sumOf { it.sec }), Pal.lav)
        ))
    }
}

class HeatmapPage(a: MainActivity) : Page(a) {
    override val title = "Heatmap"
    override fun sig(): String { val d = Store.todayDay(a); return "${d.total}|${Store.today()}" }

    override fun build() {
        val days = Store.last15(a)
        val c = ui.card()
        c.addView(ui.text("When do you scroll?", 16f, Pal.ink, true))
        c.addView(ui.text("Each row is a day, each column an hour. Darker squares mean more videos.", 12.5f, Pal.muted), ui.lp(top = 2))
        c.addView(HeatmapView(a).apply { this.days = days }, ui.lp(top = 12))
        val lg = ui.hbox()
        lg.addView(ui.text("less", 11.5f, Pal.muted))
        lg.addView(View(a).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.parseColor("#C9D8FF"), Color.parseColor("#3558E0")))
                .apply { cornerRadius = ui.dpf(6) }
        }, LinearLayout.LayoutParams(ui.dp(90), ui.dp(10)).apply { setMargins(ui.dp(8), 0, ui.dp(8), 0) })
        lg.addView(ui.text("more", 11.5f, Pal.muted))
        c.addView(lg, ui.lp(top = 10))
        add(c, 8)

        var bd: Store.Day? = null; var bh = 0; var bv = 0
        days.forEach { d -> for (h in 0 until 24) if (d.hours[h] > bv) { bv = d.hours[h]; bd = d; bh = h } }
        val t = ui.card(16, Pal.primarySoft, null)
        t.addView(ui.text(
            if (bd == null) "No data yet. Your pattern will appear after a few videos."
            else "Your single busiest hour: ${bd!!.date.substring(5)} around ${Stats.hourLabel(bh)} with $bv videos.",
            13.5f, Pal.ink))
        add(t)
    }
}

// ======================================================================= GOALS

class GoalsPage(a: MainActivity) : Page(a) {
    override val title = "Goals & streaks"

    override fun build() {
        val limit = Store.limit(a)
        val days = Store.last15(a)
        val first = Store.firstDay(a)
        val (cur, best) = Stats.streaks(days, limit, first)

        // limit
        val lc = ui.card()
        lc.addView(ui.text("Daily limit", 16f, Pal.ink, true))
        lc.addView(ui.text("Reels + Shorts you allow yourself per day.", 12.5f, Pal.muted), ui.lp(top = 2))
        val st = ui.stepper(limit, 5, 300, 5, { "$it videos" }) { v -> Store.setInt(a, Store.KEY_LIMIT, v); rebuild() }
        lc.addView(st, ui.lp(w = ui.WRAP, top = 12))
        val presets = ui.hbox()
        listOf(30, 50, 75, 100).forEachIndexed { i, p ->
            val sel = p == limit
            presets.addView(ui.softBtn("$p", if (sel) Color.WHITE else Pal.primary, if (sel) Pal.primary else Pal.primarySoft) { Store.setInt(a, Store.KEY_LIMIT, p); rebuild() },
                ui.lp(w = 0, weight = 1f, left = if (i == 0) 0 else 8))
        }
        lc.addView(presets, ui.lp(top = 12))
        lc.addView(ui.text("The floating counter turns amber at ${(limit * 4 + 4) / 5} videos and red above $limit.", 12.5f, Pal.muted), ui.lp(top = 10))
        add(lc, 8)

        // streak
        val sk = ui.card()
        val sh = ui.hbox()
        sh.addView(ui.text("🔥", 30f))
        val sv = ui.vbox()
        sv.addView(ui.text(if (cur == 0) "No streak yet" else "$cur-day streak", 20f, Pal.ink, true))
        sv.addView(ui.text("Best in the last 15 days: $best", 12.5f, Pal.muted), ui.lp(top = 2))
        sh.addView(sv, ui.lp(w = 0, weight = 1f, left = 10))
        sk.addView(sh)
        val dots = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        days.forEach { d ->
            val ok = d.date >= first
            val color = when { !ok -> Color.parseColor("#E3E8F2"); d.total <= limit -> Pal.mint; else -> Pal.coral }
            val cell = ui.vbox().apply { gravity = Gravity.CENTER_HORIZONTAL }
            cell.addView(View(a).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) } }, LinearLayout.LayoutParams(ui.dp(14), ui.dp(14)))
            cell.addView(ui.text(d.date.substring(8), 9f, Pal.muted), ui.lp(w = ui.WRAP, top = 3))
            dots.addView(cell, ui.lp(w = 0, weight = 1f))
        }
        sk.addView(dots, ui.lp(top = 14))
        val lg = ui.hbox()
        lg.addView(legend(Pal.mint, "Under limit"))
        lg.addView(legend(Pal.coral, "Over"), ui.lp(w = ui.WRAP, left = 12))
        lg.addView(legend(Color.parseColor("#C9D0E0"), "Before you started"), ui.lp(w = ui.WRAP, left = 12))
        sk.addView(lg, ui.lp(top = 10))
        add(sk)

        // weekly goal
        val week = days.takeLast(7).filter { it.date >= first }
        val good = week.count { it.total <= limit }
        val wg = ui.card()
        val wh = ui.hbox()
        wh.addView(ui.text("Weekly goal", 16f, Pal.ink, true), ui.lp(w = 0, weight = 1f))
        wh.addView(ui.text("$good / 5 days", 13.5f, Pal.muted, true))
        wg.addView(wh)
        wg.addView(ui.text("Stay under your limit on 5 of the last 7 days.", 12.5f, Pal.muted), ui.lp(top = 2))
        wg.addView(ProgressBarView(a).apply { progress = good / 5f; color = if (good >= 5) Pal.mint else Pal.primary }, ui.lp(top = 12))
        wg.addView(ui.text(if (good >= 5) "Goal reached! 🎉" else "${5 - good} more good days to go.", 12.5f, Pal.muted), ui.lp(top = 8))
        add(wg)

        // badges
        val calm = days.any { it.total > 0 && it.late == 0 && it.date >= first }
        val mindful = days.any { it.total > 0 && Stats.score(it, limit).value >= 85 }
        val badges = listOf(
            Triple("🌱", "First good day", best >= 1),
            Triple("🔥", "3-day streak", best >= 3),
            Triple("⭐", "5-day streak", best >= 5),
            Triple("🏆", "7-day streak", best >= 7),
            Triple("🌙", "Calm night", calm),
            Triple("🧘", "Mindful day", mindful)
        )
        val bc = ui.card()
        bc.addView(ui.text("Badges", 16f, Pal.ink, true))
        for (r in 0 until 2) {
            val br = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            for (k in 0 until 3) {
                val (emoji, name, got) = badges[r * 3 + k]
                val b = ui.vbox().apply { gravity = Gravity.CENTER_HORIZONTAL; alpha = if (got) 1f else 0.35f; setPadding(0, ui.dp(10), 0, ui.dp(4)) }
                b.addView(ui.text(emoji, 30f).apply { gravity = Gravity.CENTER })
                b.addView(ui.text(name, 11.5f, Pal.ink, true).apply { gravity = Gravity.CENTER }, ui.lp(top = 4))
                br.addView(b, ui.lp(w = 0, weight = 1f))
            }
            bc.addView(br, ui.lp(top = if (r == 0) 6 else 4))
        }
        add(bc)
    }
}

// ======================================================================= FOCUS

class FocusPage(a: MainActivity) : Page(a) {
    override val title = "Focus tools"

    override fun build() {
        add(ui.sectionTitle("ON-SCREEN HELPERS"), 6)
        val c1 = ui.card()
        c1.addView(ui.switchRow("Floating counter", "Live count over any app. Drag to move, tap to collapse.", Store.overlayEnabled(a)) { Store.setOverlay(a, it) })
        c1.addView(ui.divider(), ui.lp(h = 1, top = 14))
        c1.addView(ui.switchRow("Friction mode", "Past your limit, the counter grows and the screen slowly fades to grey. Scrolling gets less rewarding, never blocked.",
            Store.bool(a, Store.KEY_FRICTION, true)) { Store.setBool(a, Store.KEY_FRICTION, it) }, ui.lp(top = 14))
        c1.addView(ui.divider(), ui.lp(h = 1, top = 14))
        c1.addView(ui.switchRow("Intent check", "When you open Reels or Shorts, choose 5, 10 or 20 minutes and get a reminder when time is up.",
            Store.bool(a, Store.KEY_INTENT, true)) { Store.setBool(a, Store.KEY_INTENT, it) }, ui.lp(top = 14))
        add(c1)

        add(ui.sectionTitle("REMINDERS"), 18)
        val c2 = ui.card()
        c2.addView(ui.switchRow("Daily & weekly summary", "A notification each evening, plus a weekly recap on Sundays.", Store.bool(a, Store.KEY_NOTIF, false)) { on ->
            Store.setBool(a, Store.KEY_NOTIF, on)
            if (on) a.requestNotif()
        })
        c2.addView(ui.text("Send it at", 12.5f, Pal.muted), ui.lp(top = 14))
        c2.addView(ui.stepper(Store.int(a, Store.KEY_SUMMARY_HOUR, 21), 0, 23, 1, { Stats.hourLabel(it) }) { Store.setInt(a, Store.KEY_SUMMARY_HOUR, it) }, ui.lp(w = ui.WRAP, top = 6))
        c2.addView(ui.softBtn("Send a test summary now") {
            if (!a.notifGranted()) a.requestNotif() else Notifier.sendDaily(a)
        }, ui.lp(top = 14))
        add(c2)

        add(ui.sectionTitle("NIGHT TIME"), 18)
        val c3 = ui.card()
        val on = Store.bool(a, Store.KEY_BED_ON, true)
        c3.addView(ui.text("🌙  Bedtime guard", 16f, Pal.ink, true))
        c3.addView(ui.text(
            if (on) "On · ${Stats.hourLabel(Store.int(a, Store.KEY_BED_START, 23))} to ${Stats.hourLabel(Store.int(a, Store.KEY_BED_END, 6))} · limit ${Store.int(a, Store.KEY_BED_LIMIT, 5)} videos"
            else "Off", 13f, Pal.muted), ui.lp(top = 4))
        c3.addView(ui.softBtn("Bedtime settings ›") { a.push(BedtimePage(a)) }, ui.lp(top = 12))
        add(c3)
    }
}

class BedtimePage(a: MainActivity) : Page(a) {
    override val title = "Bedtime guard"

    override fun build() {
        val c = ui.card()
        c.addView(ui.switchRow("Bedtime guard", "A stricter limit at night, with a gentle \"still awake?\" nudge.", Store.bool(a, Store.KEY_BED_ON, true)) {
            Store.setBool(a, Store.KEY_BED_ON, it); rebuild()
        })
        add(c, 8)

        val s = ui.card()
        s.addView(ui.text("Bedtime starts", 14f, Pal.muted))
        s.addView(ui.stepper(Store.int(a, Store.KEY_BED_START, 23), 0, 23, 1, { Stats.hourLabel(it) }) { Store.setInt(a, Store.KEY_BED_START, it); rebuild() }, ui.lp(w = ui.WRAP, top = 6))
        s.addView(ui.text("Bedtime ends", 14f, Pal.muted), ui.lp(top = 16))
        s.addView(ui.stepper(Store.int(a, Store.KEY_BED_END, 6), 0, 23, 1, { Stats.hourLabel(it) }) { Store.setInt(a, Store.KEY_BED_END, it); rebuild() }, ui.lp(w = ui.WRAP, top = 6))
        s.addView(ui.text("Videos allowed during bedtime", 14f, Pal.muted), ui.lp(top = 16))
        s.addView(ui.stepper(Store.int(a, Store.KEY_BED_LIMIT, 5), 1, 30, 1, { "$it videos" }) { Store.setInt(a, Store.KEY_BED_LIMIT, it) }, ui.lp(w = ui.WRAP, top = 6))
        add(s)

        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val now = Store.inBedtime(a, hour)
        val d = Store.todayDay(a)
        val n = ui.card(16, if (now) Pal.amberSoft else Pal.primarySoft, null)
        n.addView(ui.text(if (now) "🌙 Bedtime is active right now" else "☀️ Bedtime is not active right now", 14.5f, Pal.ink, true))
        n.addView(ui.text(
            if (now) "Late-night videos so far today: ${d.late} of ${Store.int(a, Store.KEY_BED_LIMIT, 5)}. After midnight the nudges get firmer."
            else "Late-night videos counted today: ${d.late}.", 12.5f, Pal.muted), ui.lp(top = 4))
        add(n)
    }
}

// ======================================================================= IMPACT

class ImpactPage(a: MainActivity) : Page(a) {
    override val title = "Impact"
    override fun sig(): String { val d = Store.todayDay(a); return "${d.total}|${d.sec}|${Store.today()}" }

    override fun build() {
        val d = Store.todayDay(a)
        val days = Store.last15(a)
        val tm = Stats.distance(d.total)
        val allVideos = days.sumOf { it.total }
        val allSec = days.sumOf { it.sec }

        val hero = ui.vbox().apply {
            setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(20))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#DCE8FF"), Color.parseColor("#E8DFFF"))).apply { cornerRadius = ui.dpf(24) }
        }
        hero.addView(ui.text("📏  SCROLL DISTANCE TODAY", 11.5f, Pal.muted, true).apply { letterSpacing = 0.1f })
        hero.addView(ui.text(Stats.fmtDistance(tm), 40f, Pal.ink, true), ui.lp(top = 4))
        hero.addView(ui.text(Stats.distanceCompare(tm), 14f, Pal.ink), ui.lp(top = 2))
        hero.addView(ui.primaryBtn("Share my number") {
            val msg = "Today I scrolled ${d.total} Reels & Shorts (${Stats.fmtDistance(tm)}, ${Stats.distanceCompare(tm)}) in ${Stats.fmtDuration(d.sec)}. Tracked privately with ScrollSense 📱"
            a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, msg), "Share"))
        }, ui.lp(top = 16))
        add(hero, 8)

        val t = ui.card()
        t.addView(ui.text("⏳  Time-cost today", 16f, Pal.ink, true))
        t.addView(ui.text("${Stats.fmtDuration(d.sec)} of scrolling is about…", 12.5f, Pal.muted), ui.lp(top = 2))
        equivGrid(t, Stats.timeEquivalents(d.sec))
        add(t)

        val w = ui.card()
        w.addView(ui.text("📅  Last 15 days", 16f, Pal.ink, true))
        w.addView(ui.text("$allVideos videos · ${Stats.fmtDuration(allSec)} · ${Stats.fmtDistance(Stats.distance(allVideos))}", 13f, Pal.ink, true), ui.lp(top = 6))
        w.addView(ui.text(Stats.distanceCompare(Stats.distance(allVideos)), 12.5f, Pal.muted), ui.lp(top = 2))
        equivGrid(w, Stats.timeEquivalents(allSec))
        add(w)

        val n = ui.card(16, Pal.primarySoft, null)
        n.addView(ui.text("How these are worked out", 13.5f, Pal.ink, true))
        n.addView(ui.text("Distance assumes one video ≈ one phone screen (about 16 cm). Time uses 9 min per book chapter, 6 min per km of jogging, 10 min per meditation and 22 min per episode. They are fun estimates, not exact measures.", 12.5f, Pal.muted), ui.lp(top = 4))
        add(n)
    }

    private fun equivGrid(parent: LinearLayout, eq: List<Stats.Equiv>) {
        for (r in 0 until 2) {
            val rowL = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            for (k in 0 until 2) {
                val e = eq[r * 2 + k]
                val b = ui.vbox().apply { setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)); background = ui.rounded(Pal.primarySoft, 14) }
                b.addView(ui.text("${e.emoji}  ${e.value}", 18f, Pal.ink, true))
                b.addView(ui.text(e.label, 12f, Pal.muted), ui.lp(top = 2))
                rowL.addView(b, ui.lp(w = 0, weight = 1f, left = if (k == 0) 0 else 8))
            }
            parent.addView(rowL, ui.lp(top = if (r == 0) 12 else 8))
        }
    }
}

// ======================================================================= SCORE

class ScorePage(a: MainActivity, private val date: String? = null) : Page(a) {
    override val title: String = if (date == null || date == Store.today()) "Scroll score" else "Score · ${date.substring(5)}"
    private fun day(): Store.Day = (if (date == null) null else Store.last15(a).firstOrNull { it.date == date }) ?: Store.todayDay(a)
    override fun sig(): String { val d = day(); return "${d.total}|${d.fast}|${d.late}|${Store.limit(a)}|${Store.today()}" }

    override fun build() {
        val d = day()
        val limit = Store.limit(a)
        val sc = Stats.score(d, limit)
        val mood = Stats.mood(d, limit)

        val hero = ui.vbox().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(18)); background = ui.rounded(Pal.card, 24, Pal.line) }
        hero.addView(RingView(a).apply { score = sc.value }, LinearLayout.LayoutParams(ui.dp(170), ui.dp(170)))
        hero.addView(ui.text(if (d.total == 0) "Fresh start" else sc.label, 20f, Pal.ink, true), ui.lp(w = ui.WRAP, top = 4))
        hero.addView(ui.text(Stats.moodMessage(mood, d.total), 13f, Pal.muted).apply { gravity = Gravity.CENTER }, ui.lp(top = 4))
        add(hero, 8)

        val b = ui.card()
        b.addView(ui.text("What changed your score", 16f, Pal.ink, true))
        fun line(label: String, sub: String, pen: Int, max: Int, color: Int) {
            val h = ui.hbox()
            h.addView(ui.text(label, 14f, Pal.ink, true), ui.lp(w = 0, weight = 1f))
            h.addView(ui.text(if (pen == 0) (if (d.total == 0) "no videos yet" else "no penalty") else "−$pen", 13.5f, if (pen == 0) Pal.mint else color, true))
            b.addView(h, ui.lp(top = 16))
            b.addView(ProgressBarView(a).apply { progress = pen / max.toFloat(); this.color = color }, ui.lp(top = 6))
            b.addView(ui.text(sub, 12f, Pal.muted), ui.lp(top = 4))
        }
        line("Volume", "${d.total} videos against a limit of $limit", sc.volume, 50, Pal.primary)
        line("Late-night scrolling", "${d.late} videos during bedtime hours", sc.late, 25, Pal.lav)
        line("Quick flicks", "${d.fast} videos skipped in under 4 seconds", sc.fast, 25, Pal.amber)
        add(b)

        val last7 = Store.last15(a).takeLast(7)
        val w = ui.card()
        w.addView(ui.text("Last 7 days", 16f, Pal.ink, true))
        w.addView(ui.text("Tap a day to see why it scored that way.", 12f, Pal.muted), ui.lp(top = 2))
        val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        last7.forEach { day ->
            val s = Stats.score(day, limit).value
            val color = if (day.total == 0) Pal.muted else if (s >= 70) Pal.mint else if (s >= 50) Pal.amber else Pal.coral
            val cell = ui.vbox().apply { gravity = Gravity.CENTER_HORIZONTAL }
            cell.addView(ui.text(if (day.total == 0) "–" else s.toString(), 15f, color, true).apply { gravity = Gravity.CENTER })
            cell.addView(ui.text(SimpleDateFormat("EEE", Locale.getDefault()).format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(day.date)!!).take(2), 11f, Pal.muted).apply { gravity = Gravity.CENTER }, ui.lp(top = 2))
            ui.tappable(cell) { a.push(ScorePage(a, day.date)) }
            r.addView(cell, ui.lp(w = 0, weight = 1f))
        }
        w.addView(r, ui.lp(top = 12))
        add(w)

        val tip = ui.card(16, Pal.mintSoft, null)
        tip.addView(ui.text("💡 How to raise it", 14f, Pal.ink, true))
        tip.addView(ui.text("Stay under your limit, skip late-night scrolling, and give each video a few seconds instead of flicking past. A score of 85 or more is \"Mindful\".", 12.5f, Pal.muted), ui.lp(top = 4))
        add(tip)
    }
}

// ======================================================================= SETUP

class SetupPage(a: MainActivity) : Page(a) {
    override val title = "Setup & privacy"
    override fun sig() = "${a.serviceEnabled()}|${a.batteryOk()}|${a.notifGranted()}"

    private fun step(parent: LinearLayout, n: String, name: String, sub: String, chip: View, btn: String, first: Boolean, onClick: () -> Unit) {
        if (!first) parent.addView(ui.divider(), ui.lp(h = 1, top = 14))
        val h = ui.hbox()
        h.addView(ui.text("$n  $name", 15f, Pal.ink, true), ui.lp(w = 0, weight = 1f))
        h.addView(chip)
        parent.addView(h, ui.lp(top = if (first) 0 else 14))
        parent.addView(ui.text(sub, 12.5f, Pal.muted), ui.lp(top = 4))
        parent.addView(ui.softBtn(btn) { onClick() }, ui.lp(top = 10))
    }

    override fun build() {
        val acc = a.serviceEnabled(); val bat = a.batteryOk()
        val c = ui.card()
        c.addView(ui.text("Get ScrollSense ready", 17f, Pal.ink, true).apply { setOnLongClickListener { a.push(DiagnosticsPage(a)); true } })
        c.addView(ui.text("Three quick steps so counting keeps working in the background.", 12.5f, Pal.muted), ui.lp(top = 2, h = ui.WRAP))
        val inner = ui.vbox()
        step(inner, "1", "Accessibility service", "Lets ScrollSense notice swipes in Instagram and YouTube. Open Installed apps, tap ScrollSense, and turn it on.",
            if (acc) ui.chip("ON", Pal.mint, Pal.mintSoft) else ui.chip("OFF", Pal.amber, Pal.amberSoft), "Open accessibility settings", true) {
            a.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        step(inner, "2", "Background activity", "Set battery to Unrestricted so Android never stops the counter.",
            if (bat) ui.chip("ALLOWED", Pal.mint, Pal.mintSoft) else ui.chip("RECOMMENDED", Pal.amber, Pal.amberSoft), "Allow unrestricted battery", false) {
            try { a.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${a.packageName}"))) }
            catch (e: Exception) { try { a.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (e2: Exception) { openInfo() } }
        }
        step(inner, "3", "Restricted settings", "If the accessibility switch is greyed out: App info → ⋮ (top right) → Allow restricted settings.",
            if (Build.VERSION.SDK_INT >= 33) ui.chip("ANDROID 13+", Pal.lav, Pal.primarySoft) else ui.chip("NOT NEEDED", Pal.mint, Pal.mintSoft), "Open app info", false) { openInfo() }
        step(inner, "4", "Notifications (optional)", "Needed only for the daily and weekly summary.",
            if (a.notifGranted()) ui.chip("ALLOWED", Pal.mint, Pal.mintSoft) else ui.chip("OPTIONAL", Pal.muted, Pal.primarySoft), "Allow notifications", false) {
            if (Build.VERSION.SDK_INT >= 33) a.requestNotif() else openInfo()
        }
        c.addView(inner, ui.lp(top = 12))
        add(c, 8)

        val p = ui.card(16, Pal.mintSoft, null)
        p.addView(ui.text("🔒 Your privacy", 15f, Pal.ink, true))
        p.addView(ui.text("ScrollSense has no internet permission, so nothing can leave your phone. It only watches Instagram and YouTube, reads no messages, and deletes data older than 15 days.", 12.5f, Pal.muted), ui.lp(top = 4))
        add(p)

        val d = ui.card()
        d.addView(ui.text("Your data", 16f, Pal.ink, true))
        d.addView(ui.softBtn("Export last 15 days (CSV)") {
            a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, Store.csv(a)), "Export"))
        }, ui.lp(top = 10))
        d.addView(ui.softBtn("Delete all my data", Pal.coral, Pal.coralSoft) {
            AlertDialog.Builder(a).setTitle("Delete all data?").setMessage("This removes your saved counts and history. It can't be undone.")
                .setPositiveButton("Delete") { _, _ -> Store.clearAll(a); rebuild() }.setNegativeButton("Cancel", null).show()
        }, ui.lp(top = 8))
        add(d)

        val ver = try { a.packageManager.getPackageInfo(a.packageName, 0).versionName } catch (e: Exception) { "?" }
        add(ui.text("ScrollSense version $ver", 12f, Pal.muted).apply { gravity = Gravity.CENTER }, 18)
    }

    private fun openInfo() {
        a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.packageName}")))
    }
}

class DiagnosticsPage(a: MainActivity) : Page(a) {
    override val title = "Diagnostics"
    override fun sig() = DebugLog.dump().hashCode().toString()

    override fun build() {
        val c = ui.card()
        c.addView(ui.text("Recent events", 16f, Pal.ink, true))
        c.addView(ui.text("Shows what ScrollSense detected in Instagram and YouTube. Send a screenshot of this if counting looks wrong.", 12.5f, Pal.muted), ui.lp(top = 2))
        c.addView(TextView(a).apply {
            text = DebugLog.dump().ifEmpty { "No events yet. Open Instagram Reels or YouTube Shorts." }
            textSize = 11.5f; setTextColor(Pal.ink); typeface = android.graphics.Typeface.MONOSPACE; setTextIsSelectable(true)
        }, ui.lp(top = 12))
        c.addView(ui.softBtn("Clear log") { DebugLog.clear(); rebuild() }, ui.lp(top = 12))
        add(c, 8)
    }
}
