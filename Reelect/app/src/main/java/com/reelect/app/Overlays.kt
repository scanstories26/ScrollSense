package com.reelect.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Everything ScrollSense draws on top of other apps (via accessibility overlays, no extra permission). */
class Overlays(private val svc: AccessibilityService) {

    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val h = Handler(Looper.getMainLooper())
    private val density = svc.resources.displayMetrics.density
    private var friction: View? = null
    private var banner: View? = null
    private var dialog: View? = null
    private val hideBanner = Runnable { removeBanner() }

    val dialogShowing: Boolean get() = dialog != null

    private fun dp(v: Int) = (v * density).toInt()

    private fun params(w: Int, ht: Int, flags: Int, gravity: Int = Gravity.TOP or Gravity.START) =
        WindowManager.LayoutParams(w, ht, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT)
            .apply { this.gravity = gravity }

    // ---------- friction veil ----------

    /** A soft grey veil over the screen that gets stronger the further you are past your limit. */
    fun setFriction(alpha: Float) {
        if (alpha <= 0f) {
            friction?.let { try { wm.removeView(it) } catch (_: Exception) {} }
            friction = null
            return
        }
        var v = friction
        if (v == null) {
            v = View(svc).apply { setBackgroundColor(Color.rgb(120, 126, 138)); this.alpha = 0f }
            val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            try { wm.addView(v, params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, flags)) }
            catch (e: Exception) { return }
            friction = v
        }
        v.animate().alpha(alpha).setDuration(2500).start()   // slow fade
    }

    // ---------- nudge banner ----------

    fun banner(emoji: String, message: String, accent: Int) {
        removeBanner()
        val card = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(18).toFloat(); setStroke(dp(2), accent) }
            elevation = dp(8).toFloat()
            alpha = 0f
        }
        card.addView(TextView(svc).apply { text = emoji; textSize = 26f })
        card.addView(TextView(svc).apply {
            text = message; textSize = 14f; setTextColor(Color.parseColor("#1E2A44"))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(12), 0, 0, 0)
        })
        card.setOnClickListener { removeBanner() }
        val width = svc.resources.displayMetrics.widthPixels - dp(32)
        val p = params(width, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        p.y = dp(72)
        try { wm.addView(card, p) } catch (e: Exception) { return }
        banner = card
        card.animate().alpha(1f).setDuration(250).start()
        h.postDelayed(hideBanner, 6000)
    }

    private fun removeBanner() {
        h.removeCallbacks(hideBanner)
        banner?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        banner = null
    }

    // ---------- dialogs ----------

    private fun showDialog(card: LinearLayout, onOutside: () -> Unit) {
        closeDialog()
        val dim = FrameLayout(svc).apply { setBackgroundColor(Color.parseColor("#80101828")); isClickable = true; setOnClickListener { onOutside() } }
        card.isClickable = true
        dim.addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            setMargins(dp(28), 0, dp(28), 0)
        })
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        try { wm.addView(dim, params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, flags)) }
        catch (e: Exception) { return }
        dialog = dim
        dim.alpha = 0f
        dim.animate().alpha(1f).setDuration(200).start()
    }

    fun closeDialog() {
        dialog?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        dialog = null
    }

    private fun dialogCard(emoji: String, title: String, sub: String): LinearLayout {
        val c = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(22), dp(22), dp(20))
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(26).toFloat() }
        }
        c.addView(TextView(svc).apply { text = emoji; textSize = 40f; gravity = Gravity.CENTER })
        c.addView(TextView(svc).apply {
            text = title; textSize = 19f; setTextColor(Color.parseColor("#1E2A44")); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); setPadding(0, dp(6), 0, dp(4))
        })
        c.addView(TextView(svc).apply { text = sub; textSize = 13.5f; setTextColor(Color.parseColor("#6B7A99")); gravity = Gravity.CENTER })
        return c
    }

    private fun btn(label: String, filled: Boolean, onClick: () -> Unit) = TextView(svc).apply {
        text = label; textSize = 15f; gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setTextColor(if (filled) Color.WHITE else Color.parseColor("#4F7CFF"))
        setPadding(dp(12), dp(13), dp(12), dp(13))
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(if (filled) Color.parseColor("#4F7CFF") else Color.parseColor("#E8EEFF"))
        }
        setOnClickListener { onClick() }
    }

    /** "How long do you want to scroll?" – [onPick] gets minutes (0 = just a quick look). */
    fun intentDialog(onPick: (Int) -> Unit) {
        val c = dialogCard("⏱️", "How long do you want to scroll?", "Pick a timer and I'll remind you when it's up.")
        val row = LinearLayout(svc).apply { orientation = LinearLayout.HORIZONTAL }
        for (m in listOf(5, 10, 20)) {
            row.addView(btn("$m min", true) { closeDialog(); onPick(m) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { if (m != 5) leftMargin = dp(8) })
        }
        c.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        c.addView(btn("Just a quick look", false) { closeDialog(); onPick(0) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        showDialog(c) { closeDialog(); onPick(0) }
    }

    fun timeUpDialog(onMore: () -> Unit, onDone: () -> Unit) {
        val c = dialogCard("⏰", "Time's up!", "You planned a short scroll. How about a break now?")
        c.addView(btn("I'm done · go home", true) { closeDialog(); onDone() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        c.addView(btn("+5 more minutes", false) { closeDialog(); onMore() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        showDialog(c) { closeDialog(); onMore() }
    }

    fun clearAll() {
        setFriction(0f); removeBanner(); closeDialog()
    }
}
