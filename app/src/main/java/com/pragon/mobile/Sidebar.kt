package com.pragon.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/**
 * Smart sidebar: a thin handle on the screen edge. Tap it (or swipe inwards) and a panel slides out with
 *   - smart chips that change with what you're doing (media controls while music plays, Typer when a text box is
 *     focused, Read / Ask screen when you allowed screen reading, Stop macro while a macro is running)
 *   - quick controls: flashlight, volume, brightness, screenshot, home/back
 *   - your favourite apps (most used first), only ones Pragon is allowed to open
 *   - your macros, with one-tap play and record
 * Every button goes through the same command code as voice, so the same switches and limits apply.
 */
class SidebarService : Service() {

    companion object {
        const val ACTION_STOP = "com.pragon.mobile.SIDEBAR_STOP"
        @Volatile var running = false
        @Volatile var instance: SidebarService? = null

        fun start(ctx: Context): String? {
            if (!Settings.canDrawOverlays(ctx) && PragonAccessibilityService.instance == null)
                return "Allow 'Display over other apps' for Pragon first."
            try { ctx.startForegroundService(Intent(ctx, SidebarService::class.java)) } catch (e: Exception) { return "Android wouldn't start the sidebar: ${e.message}" }
            return null
        }
        fun stop(ctx: Context) { try { ctx.startService(Intent(ctx, SidebarService::class.java).setAction(ACTION_STOP)) } catch (e: Exception) { } }
        fun open() { instance?.let { s -> s.main.post { s.expand() } } }
        fun close() { instance?.let { s -> s.main.post { s.collapse() } } }

        private fun prefs(c: Context) = c.getSharedPreferences("pragon_sidebar", Context.MODE_PRIVATE)
        fun leftSide(c: Context) = prefs(c).getBoolean("left", false)
        fun setLeft(c: Context, left: Boolean) = prefs(c).edit().putBoolean("left", left).apply()
        fun autoStart(c: Context) = prefs(c).getBoolean("auto", false)
        fun setAutoStart(c: Context, on: Boolean) = prefs(c).edit().putBoolean("auto", on).apply()

        /** Opened-through-Pragon counters: the sidebar lists the apps you use most first. */
        fun countOpen(c: Context, pkg: String) {
            val p = prefs(c)
            val o = try { JSONObject(p.getString("uses", "{}")) } catch (e: Exception) { JSONObject() }
            o.put(pkg, o.optInt(pkg, 0) + 1)
            p.edit().putString("uses", o.toString()).apply()
        }
        fun uses(c: Context): Map<String, Int> {
            val o = try { JSONObject(prefs(c).getString("uses", "{}")) } catch (e: Exception) { return emptyMap() }
            return o.keys().asSequence().associateWith { o.optInt(it, 0) }
        }
    }

    val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var root: FrameLayout? = null
    private var handle: View? = null
    private var panel: LinearLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private var expanded = false
    private var toast: TextView? = null

    private val cyan = 0xFF00D4FF.toInt()
    private val bg = 0xF2101018.toInt()
    private val chip = 0xFF1B1B28.toInt()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { shutdown(); return START_NOT_STICKY }
        if (running) return START_STICKY
        try { startInForeground() } catch (e: Throwable) { stopSelf(); return START_NOT_STICKY }
        val a11y = PragonAccessibilityService.instance
        wm = (a11y ?: this).getSystemService(Context.WINDOW_SERVICE) as WindowManager
        running = true
        instance = this
        try { build(viaA11y = a11y != null) } catch (e: Throwable) { shutdown() }
        return START_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("pragon_sidebar", "Sidebar", NotificationManager.IMPORTANCE_MIN))
        val stop = PendingIntent.getService(this, 9, Intent(this, SidebarService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "pragon_sidebar").setSmallIcon(android.R.drawable.ic_menu_more)
            .setContentTitle("Pragon sidebar is on").setContentText("Tap to turn it off").setContentIntent(stop).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(10, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(10, n)
    }

    private fun shutdown() {
        running = false
        instance = null
        main.removeCallbacksAndMessages(null)
        root?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        root = null
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (e: Exception) { }
        stopSelf()
    }

    override fun onDestroy() {
        if (running) { running = false; instance = null; root?.let { try { wm.removeView(it) } catch (e: Exception) { } }; root = null }
        super.onDestroy()
    }

    // ── window ───────────────────────────────────────────────────────────

    private fun type(viaA11y: Boolean) =
        if (viaA11y) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun build(viaA11y: Boolean) {
        val left = leftSide(this)
        val r = FrameLayout(this)
        root = r
        // the thin handle
        val h = View(this).apply {
            background = GradientDrawable().apply {
                setColor(0xCC00D4FF.toInt())
                cornerRadii = if (left) floatArrayOf(0f, 0f, dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat(), 0f, 0f)
                else floatArrayOf(dp(8).toFloat(), dp(8).toFloat(), 0f, 0f, 0f, 0f, dp(8).toFloat(), dp(8).toFloat())
            }
        }
        handle = h
        r.addView(h, FrameLayout.LayoutParams(dp(8), dp(96), (if (left) Gravity.START else Gravity.END) or Gravity.CENTER_VERTICAL))
        h.setOnTouchListener(handleTouch(left))

        val p = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(bg); setStroke(dp(1), 0x5500D4FF)
                val rad = dp(18).toFloat()
                cornerRadii = if (left) floatArrayOf(0f, 0f, rad, rad, rad, rad, 0f, 0f) else floatArrayOf(rad, rad, 0f, 0f, 0f, 0f, rad, rad)
            }
            setPadding(dp(10), dp(10), dp(10), dp(10))
            visibility = View.GONE
            elevation = dp(10).toFloat()
        }
        panel = p
        r.addView(p, FrameLayout.LayoutParams(dp(268), ViewGroup.LayoutParams.MATCH_PARENT, if (left) Gravity.START else Gravity.END))

        val sh = resources.displayMetrics.heightPixels
        val params = WindowManager.LayoutParams(
            dp(14), dp(110), type(viaA11y),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = (if (left) Gravity.START else Gravity.END) or Gravity.TOP
            x = 0
            y = getSharedPreferences("pragon_sidebar", MODE_PRIVATE).getInt("y", (sh * 0.4).toInt())
        }
        lp = params
        try { wm.addView(r, params) } catch (e: Throwable) {
            if (viaA11y && Settings.canDrawOverlays(this)) {
                wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                wm.addView(r, params)
            } else throw e
        }
    }

    private fun handleTouch(left: Boolean) = object : View.OnTouchListener {
        var downX = 0f; var downY = 0f; var startY = 0; var moved = false
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val params = lp ?: return false
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startY = params.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    // sideways pull toward the screen centre opens it; a mostly vertical drag moves the handle
                    if (kotlin.math.abs(dy) > dp(14) && kotlin.math.abs(dy) > kotlin.math.abs(dx)) {
                        moved = true
                        params.y = (startY + dy).toInt().coerceIn(0, resources.displayMetrics.heightPixels - dp(110))
                        try { wm.updateViewLayout(root, params) } catch (x: Exception) { }
                    } else if ((if (left) dx else -dx) > dp(18)) { expand(); return true }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) getSharedPreferences("pragon_sidebar", MODE_PRIVATE).edit().putInt("y", params.y).apply()
                    else expand()
                }
            }
            return true
        }
    }

    // ── open / close ─────────────────────────────────────────────────────

    private var idleClose = Runnable { collapse() }

    fun expand() {
        val params = lp ?: return
        val p = panel ?: return
        if (expanded) { refreshPanel(); return }
        expanded = true
        fillPanel()
        params.width = dp(268) + dp(14)
        params.height = WindowManager.LayoutParams.MATCH_PARENT
        params.y = 0
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL.inv() or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        try { wm.updateViewLayout(root, params) } catch (e: Exception) { }
        handle?.visibility = View.GONE
        p.visibility = View.VISIBLE
        p.translationX = if (leftSide(this)) -dp(268).toFloat() else dp(268).toFloat()
        p.animate().translationX(0f).setDuration(180).start()
        root?.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_OUTSIDE) { collapse(); true } else false }
        resetIdle()
    }

    fun collapse() {
        val params = lp ?: return
        val p = panel ?: return
        if (!expanded) return
        expanded = false
        main.removeCallbacks(idleClose)
        params.width = dp(14)
        params.height = dp(110)
        params.y = getSharedPreferences("pragon_sidebar", MODE_PRIVATE).getInt("y", (resources.displayMetrics.heightPixels * 0.4).toInt())
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH.inv()
        p.visibility = View.GONE
        handle?.visibility = View.VISIBLE
        try { wm.updateViewLayout(root, params) } catch (e: Exception) { }
    }

    private fun resetIdle() { main.removeCallbacks(idleClose); main.postDelayed(idleClose, 12000) }

    private fun refreshPanel() { fillPanel(); resetIdle() }

    // ── content ──────────────────────────────────────────────────────────

    private fun label(t: String, size: Float = 11f, color: Int = 0xFF8A8AA0.toInt()) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); typeface = Typeface.DEFAULT_BOLD; setPadding(dp(4), dp(8), 0, dp(4))
    }

    private fun button(text: String, hot: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = 13f; gravity = Gravity.CENTER
        setTextColor(if (hot) 0xFF001018.toInt() else 0xFFEAF6FF.toInt()); typeface = Typeface.DEFAULT_BOLD
        background = GradientDrawable().apply { setColor(if (hot) cyan else chip); cornerRadius = dp(12).toFloat() }
        setPadding(dp(10), dp(10), dp(10), dp(10))
        setOnClickListener { resetIdle(); onClick() }
    }

    private fun row(vararg v: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (x in v) addView(x, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
    }

    /** Runs one Pragon command off the main thread and shows the result in the panel. */
    private fun cmd(action: String, value: String = "", collapseAfter: Boolean = false) {
        Thread {
            val r = try { CommandExecutor.run(applicationContext, JSONObject().put("action", action).put("value", value), user = true) }
            catch (e: Exception) { CommandExecutor.Res(false, e.message ?: "failed") }
            main.post { say(r.msg); if (collapseAfter && r.ok) collapse() }
        }.start()
    }

    private fun say(t: String) { toast?.text = t.take(140); toast?.visibility = if (t.isBlank()) View.GONE else View.VISIBLE }

    private fun fillPanel() {
        val p = panel ?: return
        p.removeAllViews()
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply { text = "PRAGON"; textSize = 15f; setTextColor(cyan); typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.15f },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(TextView(this).apply { text = "✕"; textSize = 18f; setTextColor(0xFFAAAACC.toInt()); setPadding(dp(10), 0, dp(4), 0); setOnClickListener { collapse() } })
        p.addView(head)
        toast = TextView(this).apply { textSize = 12f; setTextColor(0xFF33FFB0.toInt()); visibility = View.GONE; setPadding(dp(4), dp(2), dp(4), dp(2)) }
        p.addView(toast)

        val sv = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sv.addView(body)
        p.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- smart chips: depend on what is happening right now ----
        val smart = ArrayList<View>()
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Macros.isRecording) smart.add(button("■ Stop recording", true) { cmd("macro_stop") })
        if (Macros.playing != null) smart.add(button("■ Stop macro", true) { cmd("macro_cancel") })
        if (am.isMusicActive) {
            smart.add(button("⏸ Pause") { cmd("key", "pause") }); smart.add(button("⏭ Next") { cmd("key", "next") })
        }
        val svc = PragonAccessibilityService.instance
        if (svc != null && svc.hasFocusedTextBox()) smart.add(button(if (Typer.active) "⌨ Typing…" else "⌨ Voice type", Typer.active) { cmd("typer", if (Typer.active) "off" else "on", true) })
        if (Guard.screenReadingOn(this) && svc != null) {
            smart.add(button("🔊 Read screen") { cmd("screen_read", "", true) })
        }
        val bat = BatteryReminder.level(this)
        if (bat in 0..15 && !BatteryReminder.charging(this)) smart.add(button("🔋 $bat% low") { cmd("status") })
        if (smart.isNotEmpty()) {
            body.addView(label("SMART"))
            smart.chunked(2).forEach { pair -> body.addView(row(*pair.toTypedArray(), *(if (pair.size == 1) arrayOf(View(this)) else emptyArray()))) }
        }

        // ---- quick controls ----
        body.addView(label("QUICK"))
        body.addView(row(button("🔦 Torch") { cmd("flashlight", "toggle") }, button("🔦 +") { cmd("flashlight_level", "+") }, button("🔦 −") { cmd("flashlight_level", "-") }))
        body.addView(row(button("🔊 +") { cmd("volume", "+") }, button("🔉 −") { cmd("volume", "-") }, button("🔇") { cmd("key", "mute") }))
        body.addView(row(button("☀ +") { cmd("brightness", "+") }, button("☀ −") { cmd("brightness", "-") }, button("📷 Shot") { cmd("key", "screenshot", true) }))
        body.addView(row(button("⌂ Home") { cmd("key", "home", true) }, button("◁ Back") { cmd("key", "back") }, button("▭ Recents") { cmd("key", "recents", true) }))

        // ---- apps ----
        val pm = packageManager
        val uses = uses(this)
        var pkgs = Guard.sidebarApps(this)
        if (pkgs.isEmpty() && Guard.allowlistOn(this)) pkgs = Guard.allowedApps(this).toList()
        pkgs = pkgs.filter { pm.getLaunchIntentForPackage(it) != null && Guard.canOpen(this, it) }
            .sortedByDescending { uses[it] ?: 0 }.take(16)
        body.addView(label(if (pkgs.isEmpty()) "APPS (choose in Pragon Settings)" else "APPS"))
        pkgs.chunked(4).forEach { rowPk ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (pk in rowPk) {
                val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(2), dp(4), dp(2), dp(4)) }
                val ic = ImageView(this).apply { try { setImageDrawable(pm.getApplicationIcon(pk)) } catch (e: Exception) { } }
                cell.addView(ic, LinearLayout.LayoutParams(dp(40), dp(40)))
                val name = try { pm.getApplicationLabel(pm.getApplicationInfo(pk, 0)).toString() } catch (e: Exception) { pk }
                cell.addView(TextView(this).apply { text = name; textSize = 9f; maxLines = 1; gravity = Gravity.CENTER; setTextColor(0xFFCCCCDD.toInt()); ellipsize = android.text.TextUtils.TruncateAt.END })
                cell.setOnClickListener { resetIdle(); countOpen(this@SidebarService, pk); cmd("open_app", name, true) }
                r.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            repeat(4 - rowPk.size) { r.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
            body.addView(r)
        }

        // ---- macros ----
        if (Guard.macrosOn(this)) {
            body.addView(label("MACROS"))
            val names = Macros.names(this)
            names.take(8).forEach { n -> body.addView(button("▶  $n") { cmd("macro_run", n, true) }.also { (it.layoutParams as? ViewGroup.MarginLayoutParams) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }) }
            if (!Macros.isRecording) body.addView(button("● Record new macro") { cmd("macro_record", "macro " + (names.size + 1), true) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
        }
        @Suppress("UNUSED_VARIABLE") val unused = HorizontalScrollView(this)
    }
}
