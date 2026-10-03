package com.pragon.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import java.util.Locale

/**
 * Pragon's own stopwatch. Android has no "start a stopwatch" intent for other apps, so this one lives in Pragon:
 * "start stopwatch" / "stop stopwatch" / "lap" / "reset stopwatch" / "what's the stopwatch". While it runs, a
 * notification shows the live time. State is saved, so it survives Pragon being closed (not a phone restart).
 */
object Stopwatch {

    private const val CH = "pragon_stopwatch"
    private const val NID = 41

    private fun sp(c: Context) = c.getSharedPreferences("pragon_stopwatch", Context.MODE_PRIVATE)

    private class St(var running: Boolean, var runStart: Long, var base: Long, var laps: MutableList<Long>)

    private fun load(c: Context): St {
        val p = sp(c)
        val st = St(
            p.getBoolean("running", false), p.getLong("run_start", 0), p.getLong("base", 0),
            (p.getString("laps", "") ?: "").split(",").mapNotNull { it.toLongOrNull() }.toMutableList(),
        )
        // elapsedRealtime restarts at 0 after a phone restart: a "start" from the future means the phone rebooted.
        if (st.running && st.runStart > SystemClock.elapsedRealtime()) { st.running = false; st.base = 0; st.laps.clear(); save(c, st) }
        return st
    }

    private fun save(c: Context, s: St) {
        sp(c).edit().putBoolean("running", s.running).putLong("run_start", s.runStart).putLong("base", s.base)
            .putString("laps", s.laps.joinToString(",")).apply()
    }

    private fun elapsed(s: St): Long = s.base + (if (s.running) SystemClock.elapsedRealtime() - s.runStart else 0L)

    fun isRunning(c: Context): Boolean = load(c).running
    fun elapsedMs(c: Context): Long = elapsed(load(c))

    /** "12.4 seconds", "1 minute 5 seconds", "2 hours 3 minutes and 9 seconds". */
    fun human(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        if (h == 0L && m == 0L) return String.format(Locale.US, "%d.%d seconds", s, (ms / 100) % 10)
        val parts = ArrayList<String>()
        if (h > 0) parts.add("$h hour" + (if (h > 1) "s" else ""))
        if (m > 0) parts.add("$m minute" + (if (m > 1) "s" else ""))
        if (s > 0 || parts.isEmpty()) parts.add("$s second" + (if (s != 1L) "s" else ""))
        return if (parts.size == 1) parts[0] else parts.dropLast(1).joinToString(" ") + " and " + parts.last()
    }

    fun act(ctx: Context, what: String): CommandExecutor.Res {
        val c = ctx.applicationContext
        val st = load(c)
        val now = SystemClock.elapsedRealtime()
        return when (what) {
            "start", "resume" -> {
                if (st.running) return CommandExecutor.Res(true, "The stopwatch is already running at ${human(elapsed(st))}.", label = "Stopwatch")
                st.running = true; st.runStart = now
                save(c, st); notify(c, st)
                CommandExecutor.Res(true, if (st.base > 0) "Stopwatch resumed from ${human(st.base)}." else "Stopwatch started.", label = "Stopwatch")
            }
            "stop", "pause" -> {
                if (!st.running) return CommandExecutor.Res(true, if (st.base > 0) "The stopwatch is already stopped at ${human(st.base)}." else "The stopwatch isn't running.", label = "Stopwatch")
                st.base = elapsed(st); st.running = false
                save(c, st); notify(c, st)
                CommandExecutor.Res(true, "Stopped at ${human(st.base)}.", label = "Stopwatch")
            }
            "reset", "clear" -> {
                st.running = false; st.base = 0; st.laps.clear(); save(c, st); cancel(c)
                CommandExecutor.Res(true, "Stopwatch reset.", label = "Stopwatch")
            }
            "restart" -> {
                st.running = true; st.runStart = now; st.base = 0; st.laps.clear(); save(c, st); notify(c, st)
                CommandExecutor.Res(true, "Stopwatch restarted from zero.", label = "Stopwatch")
            }
            "lap" -> {
                if (!st.running) return CommandExecutor.Res(false, "The stopwatch isn't running, so there's nothing to lap.", label = "Stopwatch")
                val t = elapsed(st)
                val prev = st.laps.lastOrNull() ?: 0L
                st.laps.add(t); save(c, st)
                CommandExecutor.Res(true, "Lap ${st.laps.size}: ${human(t - prev)}. Total ${human(t)}.", label = "Stopwatch")
            }
            "status" -> status(st)
            // bare "stopwatch": start it if idle, otherwise just say what it reads
            "open" -> if (!st.running && st.base == 0L) act(c, "start") else status(st)
            else -> CommandExecutor.Res(false, "I don't know the stopwatch command '$what'.", label = "Stopwatch")
        }
    }

    private fun status(st: St): CommandExecutor.Res {
        val t = elapsed(st)
        return when {
            st.running -> CommandExecutor.Res(true, "The stopwatch is running: ${human(t)}.", label = "Stopwatch")
            t > 0 -> CommandExecutor.Res(true, "The stopwatch is stopped at ${human(t)}.", label = "Stopwatch")
            else -> CommandExecutor.Res(true, "The stopwatch is at zero. Say start stopwatch.", label = "Stopwatch")
        }
    }

    // ── notification with a live clock ───────────────────────────────────

    private fun notify(c: Context, st: St) {
        try {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH, "Stopwatch", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(
                c, 41, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE
            )
            val t = elapsed(st)
            val b = Notification.Builder(c, CH)
                .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
            if (st.running) {
                b.setContentTitle("Stopwatch running")
                    .setContentText("Say \"stop stopwatch\" or \"lap\"")
                    .setShowWhen(true).setUsesChronometer(true)
                    .setWhen(System.currentTimeMillis() - t)
            } else {
                b.setContentTitle("Stopwatch stopped").setContentText(human(t)).setShowWhen(false)
            }
            nm.notify(NID, b.build())
        } catch (e: Exception) {
            // notifications blocked: the voice answers still work
        }
    }

    private fun cancel(c: Context) {
        try { c.getSystemService(NotificationManager::class.java).cancel(NID) } catch (e: Exception) { }
    }
}
