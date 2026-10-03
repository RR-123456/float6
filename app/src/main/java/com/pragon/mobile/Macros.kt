package com.pragon.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Macros: record what you do, replay it later with one phrase ("run macro morning").
 *
 * While recording, Pragon remembers
 *   - every command it carries out ("open youtube", "volume up", "swipe down", "flashlight on" ...)
 *   - every button you tap yourself, by its NAME on screen (not by position, so it still works if the layout moves)
 *   - every app you open, and every scroll
 * It never records what you type, and never records anything on password screens.
 * Replay goes through the same command code as voice, so "Only allowed apps" and every other switch still apply.
 * Macros are stored only in Pragon's private storage.
 */
object Macros {

    class Step(val action: String, val value: String, val delayMs: Long)

    @Volatile var recordingName: String? = null
        private set
    private var steps = ArrayList<Step>()
    private var lastAt = 0L
    private val cancel = AtomicBoolean(false)
    @Volatile var playing: String? = null
        private set

    val isRecording: Boolean get() = recordingName != null

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("pragon_macros", Context.MODE_PRIVATE)

    // ── storage ──────────────────────────────────────────────────────────

    fun names(c: Context): List<String> = try {
        val o = JSONObject(sp(c).getString("all", "{}"))
        o.keys().asSequence().toList().sorted()
    } catch (e: Exception) { emptyList() }

    fun load(c: Context, name: String): List<Step> = try {
        val a = JSONObject(sp(c).getString("all", "{}")).optJSONArray(key(name)) ?: JSONArray()
        (0 until a.length()).map { val o = a.getJSONObject(it); Step(o.getString("a"), o.optString("v"), o.optLong("d", 600)) }
    } catch (e: Exception) { emptyList() }

    private fun key(name: String) = name.trim().lowercase()

    private fun save(c: Context, name: String, list: List<Step>) {
        val all = try { JSONObject(sp(c).getString("all", "{}")) } catch (e: Exception) { JSONObject() }
        all.put(key(name), JSONArray().also { a -> list.forEach { a.put(JSONObject().put("a", it.action).put("v", it.value).put("d", it.delayMs)) } })
        sp(c).edit().putString("all", all.toString()).apply()
    }

    fun delete(c: Context, name: String): Boolean {
        val all = try { JSONObject(sp(c).getString("all", "{}")) } catch (e: Exception) { return false }
        if (!all.has(key(name))) return false
        all.remove(key(name))
        sp(c).edit().putString("all", all.toString()).apply()
        return true
    }

    fun describe(c: Context, name: String): String = load(c, name).joinToString(" > ") {
        when (it.action) {
            "open_app" -> "open " + it.value
            "click" -> "tap " + it.value
            "swipe" -> "scroll " + it.value
            else -> it.action + (if (it.value.isNotEmpty()) " " + it.value else "")
        }
    }

    // ── recording ────────────────────────────────────────────────────────

    /** Actions that must never be recorded (they control the recorder itself, or are unsafe to replay). */
    private val SKIP = setOf(
        "macro_record", "macro_stop", "macro_run", "macro_delete", "macro_list", "macro_cancel", "typer", "typer_mode",
        "screen_ask", "screen_read", "firewall", "scan", "status", "ollama_pull", "ollama_list", "ollama_use", "battery_alert",
        "battery_remind", "battery_unplug", "stopwatch", "sidebar", "privacy", "allowlist",
    )

    fun start(c: Context, name: String): String {
        val call = Prefs.callMe(c)
        if (!Guard.macrosOn(c)) return "Macros are switched off in Settings > Security, $call."
        if (isRecording) return "I'm already recording '${recordingName}', $call. Say stop recording first."
        val n = name.trim().ifEmpty { "macro " + (names(c).size + 1) }
        recordingName = n
        steps = ArrayList()
        lastAt = System.currentTimeMillis()
        notifyRecording(c, 0)
        return "Recording '$n', $call. Do what you want repeated, then say stop recording. I record taps, scrolls, apps you open and my commands, never what you type."
    }

    fun stop(c: Context): String {
        val call = Prefs.callMe(c)
        val n = recordingName ?: return "I'm not recording anything, $call."
        recordingName = null
        clearNotification(c)
        val list = trim(steps)
        steps = ArrayList()
        if (list.isEmpty()) return "Nothing was recorded, $call, so I didn't save '$n'."
        save(c, n, list)
        return "Saved '$n' with ${list.size} step" + (if (list.size != 1) "s" else "") + ". Say run macro $n to play it."
    }

    /** Drops accidental repeats. */
    private fun trim(l: List<Step>): List<Step> {
        val out = ArrayList<Step>()
        for (s in l) {
            val p = out.lastOrNull()
            if (p != null && p.action == s.action && p.value.equals(s.value, ignoreCase = true) && s.action == "open_app") continue
            out.add(s)
        }
        return out.take(200)
    }

    private fun add(c: Context, action: String, value: String) {
        val now = System.currentTimeMillis()
        val d = (now - lastAt).coerceIn(500L, 8000L)
        lastAt = now
        steps.add(Step(action, value, d))
        if (steps.size % 3 == 0) notifyRecording(c, steps.size)
    }

    /** Called by CommandExecutor after it carries out a command. */
    @Synchronized fun onCommand(c: Context, action: String, value: String, ok: Boolean) {
        if (!isRecording || !ok || action in SKIP) return
        add(c, action, value)
    }

    /** Called by the accessibility service when the user taps something. */
    @Synchronized fun onUserClick(c: Context, label: String, pkg: String) {
        if (!isRecording || label.isBlank() || pkg == c.packageName) return
        // The tap that launched an app from the home screen is replaced by an "open app" step when the window changes.
        add(c, "click", label.trim().take(60))
        lastClickAt = System.currentTimeMillis()
        lastClickPkg = pkg
    }

    private var lastClickAt = 0L
    private var lastClickPkg = ""
    private var lastWindowPkg = ""

    /** Called by the accessibility service when a different app comes to the front. */
    @Synchronized fun onWindowChange(c: Context, pkg: String, isLauncherOrSystem: Boolean) {
        if (!isRecording) return
        if (pkg == lastWindowPkg) return
        lastWindowPkg = pkg
        if (isLauncherOrSystem || pkg == c.packageName) return
        // an app opened right after a tap on the launcher: that tap was "open <app>"
        if (System.currentTimeMillis() - lastClickAt < 2000 && steps.lastOrNull()?.action == "click") {
            steps.removeAt(steps.size - 1)
        }
        val label = try { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }
        add(c, "open_app", label)
    }

    private var scrollAcc = 0
    private var scrollAt = 0L

    /** Called for scroll events (dy > 0 = content moved up = swipe up). Bursts are merged into one step. */
    @Synchronized fun onScroll(c: Context, dy: Int) {
        if (!isRecording || dy == 0) return
        val now = System.currentTimeMillis()
        if (now - scrollAt > 700) scrollAcc = 0
        scrollAcc += dy
        scrollAt = now
        val dir = if (scrollAcc > 0) "up" else "down"
        val last = steps.lastOrNull()
        if (last != null && last.action == "swipe" && now - lastAt < 900) {
            steps[steps.size - 1] = Step("swipe", dir, last.delayMs)
        } else if (kotlin.math.abs(scrollAcc) > 30) {
            add(c, "swipe", dir)
        }
    }

    // ── playback ─────────────────────────────────────────────────────────

    fun cancelPlayback() = cancel.set(true)

    /** Plays the macro on a background thread. Returns what to say now. */
    fun run(c: Context, name: String, times: Int = 1): String {
        val call = Prefs.callMe(c)
        if (!Guard.macrosOn(c)) return "Macros are switched off in Settings > Security, $call."
        if (isRecording) return "Stop recording first, $call."
        if (playing != null) return "Another macro is still running, $call. Say stop macro to cancel it."
        val list = load(c, name)
        if (list.isEmpty()) {
            val all = names(c)
            return if (all.isEmpty()) "You haven't recorded any macros yet, $call. Say record macro, then a name."
            else "I don't have a macro called '$name', $call. You have: ${all.joinToString(", ")}."
        }
        if (PragonAccessibilityService.instance == null && list.any { it.action == "click" || it.action == "swipe" })
            return "Macros that tap or scroll need the accessibility service turned on, $call."
        val app = c.applicationContext
        val n = times.coerceIn(1, 20)
        cancel.set(false)
        playing = name
        Thread {
            try {
                var failed = ""
                loop@ for (round in 1..n) {
                    for ((i, s) in list.withIndex()) {
                        if (cancel.get()) break@loop
                        sleepCancellable(if (i == 0 && round == 1) 400 else s.delayMs.coerceIn(400, 8000))
                        if (cancel.get()) break@loop
                        val cmd = JSONObject().put("action", s.action).put("value", s.value)
                        if (s.action == "click") cmd.put("wait_ms", 4000)
                        val r = CommandExecutor.run(app, cmd, fromStandalone = false, user = true)
                        if (!r.ok) { failed = "Step ${i + 1} (${s.action} ${s.value}) failed: ${r.msg}"; break@loop }
                    }
                }
                val msg = when {
                    cancel.get() -> "Macro $name stopped."
                    failed.isNotEmpty() -> "Macro $name stopped. $failed"
                    else -> "Macro $name finished, $call."
                }
                try { if (!Prefs.muted(app)) Speaker.get(app).speak(msg) } catch (e: Exception) { }
            } finally {
                playing = null
            }
        }.start()
        return "Running macro $name" + (if (n > 1) " $n times" else "") + ", $call. Say stop macro to cancel."
    }

    private fun sleepCancellable(ms: Long) {
        var left = ms
        while (left > 0 && !cancel.get()) { val s = minOf(left, 100L); try { Thread.sleep(s) } catch (e: InterruptedException) { return }; left -= s }
    }

    // ── notification while recording ─────────────────────────────────────

    private fun notifyRecording(c: Context, n: Int) {
        try {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("pragon_macro", "Macro recording", NotificationManager.IMPORTANCE_LOW))
            nm.notify(
                71,
                Notification.Builder(c, "pragon_macro").setSmallIcon(android.R.drawable.presence_video_busy)
                    .setContentTitle("Recording macro '${recordingName}'").setContentText("$n steps. Say \"stop recording\" to save.")
                    .setOngoing(true).setOnlyAlertOnce(true).build()
            )
        } catch (e: Exception) { }
    }

    private fun clearNotification(c: Context) {
        try { c.getSystemService(NotificationManager::class.java).cancel(71) } catch (e: Exception) { }
    }

    /** Voice / tool entry point. */
    fun act(c: Context, kind: String, value: String): CommandExecutor.Res = when (kind) {
        "macro_record" -> { val m = start(c, value); CommandExecutor.Res(isRecording, m, label = "Macro") }
        "macro_stop" -> {
            if (playing != null && !isRecording) { cancelPlayback(); CommandExecutor.Res(true, "Stopping the macro.", label = "Macro") }
            else CommandExecutor.Res(true, stop(c), label = "Macro")
        }
        "macro_cancel" -> { cancelPlayback(); CommandExecutor.Res(true, "Stopping the macro.", label = "Macro") }
        "macro_run" -> {
            val m = Regex("^(.*?)(?:\\s+(\\d{1,2})\\s+times?)?$").find(value.trim())
            val name = m?.groupValues?.get(1)?.trim().orEmpty()
            val times = m?.groupValues?.get(2)?.toIntOrNull() ?: 1
            val say = run(c, name, times)
            CommandExecutor.Res(say.startsWith("Running"), say, label = "Macro")
        }
        "macro_delete" -> CommandExecutor.Res(true, if (delete(c, value)) "Deleted macro ${value.trim()}." else "I don't have a macro called '${value.trim()}'.", label = "Macro")
        else -> {
            val all = names(c)
            CommandExecutor.Res(true, if (all.isEmpty()) "You have no macros yet. Say record macro, then a name." else "Your macros: ${all.joinToString(", ")}.", label = "Macro")
        }
    }
}
