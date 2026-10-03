package com.pragon.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder

/**
 * Battery reminders. Three kinds, all working while the screen is off:
 *  - LOW alerts: "battery is at 20 / 10 / 5 percent" while not charging (on/off, levels editable in Settings)
 *  - UNPLUG reminder: "battery reached 80 percent, unplug the charger" while charging
 *  - one-shot level reminders: "remind me when the battery is 50 percent"
 * Voice: "battery reminder on", "remind me to unplug at 80 percent", "remind me when battery is 30 percent",
 * "cancel battery reminders", "battery reminders" (what's active).
 *
 * Android only delivers battery changes to a running component, so a small foreground service (with a quiet
 * notification) watches the level while any reminder is active, and stops itself when none is.
 */
object BatteryReminder {

    const val DEFAULT_LEVELS = "20,10,5"

    private fun sp(c: Context) = c.getSharedPreferences("pragon_battery", Context.MODE_PRIVATE)

    fun lowOn(c: Context): Boolean = sp(c).getBoolean("low_on", false)
    fun lowLevelsText(c: Context): String = sp(c).getString("low_levels", DEFAULT_LEVELS) ?: DEFAULT_LEVELS
    fun lowLevels(c: Context): List<Int> = parseLevels(lowLevelsText(c))
    fun chargeTarget(c: Context): Int = sp(c).getInt("charge_target", 0)

    /** (level, direction): +1 = tell me when it rises to level (charging), -1 = when it falls to level. */
    fun levelTargets(c: Context): List<Pair<Int, Int>> =
        (sp(c).getString("targets", "") ?: "").split(",").mapNotNull {
            val p = it.split(":")
            val l = p.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            val d = p.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            l to d
        }

    private fun saveTargets(c: Context, t: List<Pair<Int, Int>>) =
        sp(c).edit().putString("targets", t.joinToString(",") { "${it.first}:${it.second}" }).apply()

    fun parseLevels(s: String?): List<Int> =
        (s ?: "").split(",", " ", ";", "/").mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..99 }.distinct().sortedDescending()

    fun anyActive(c: Context): Boolean = lowOn(c) || chargeTarget(c) > 0 || levelTargets(c).isNotEmpty()

    // ── reading the battery ──────────────────────────────────────────────

    private fun sticky(c: Context): Intent? = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    fun percentOf(i: Intent?): Int {
        if (i == null) return -1
        val l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val s = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return if (l < 0 || s <= 0) -1 else l * 100 / s
    }
    fun pluggedOf(i: Intent?): Boolean = (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0

    fun level(c: Context): Int = percentOf(sticky(c))
    fun charging(c: Context): Boolean = pluggedOf(sticky(c))

    // ── changing the reminders ───────────────────────────────────────────

    fun setLow(c: Context, on: Boolean, levels: String? = null) {
        val e = sp(c).edit().putBoolean("low_on", on)
        if (levels != null) {
            val parsed = parseLevels(levels)
            e.putString("low_levels", if (parsed.isEmpty()) DEFAULT_LEVELS else parsed.joinToString(","))
        }
        e.putString("fired_low", "").apply()
        sync(c)
    }

    fun setChargeTarget(c: Context, pct: Int) {
        sp(c).edit().putInt("charge_target", pct.coerceIn(0, 100)).putBoolean("charge_fired", false).apply()
        sync(c)
    }

    fun clearAll(c: Context) {
        sp(c).edit().putBoolean("low_on", false).putInt("charge_target", 0).putString("targets", "")
            .putString("fired_low", "").putBoolean("charge_fired", false).apply()
        sync(c)
    }

    /** Starts the watcher if any reminder is active, stops it otherwise. Safe to call any time. */
    fun sync(ctx: Context) {
        val c = ctx.applicationContext
        try {
            if (anyActive(c)) c.startForegroundService(Intent(c, BatteryReminderService::class.java))
            else c.stopService(Intent(c, BatteryReminderService::class.java))
        } catch (e: Exception) {
            // Android refused a background start; it starts the next time Pragon is opened.
        }
    }

    fun describe(c: Context): String {
        val parts = ArrayList<String>()
        if (lowOn(c)) parts.add("low battery alerts at " + lowLevels(c).joinToString(", ") + " percent")
        if (chargeTarget(c) > 0) parts.add(if (chargeTarget(c) >= 100) "a reminder when it is fully charged" else "an unplug reminder at ${chargeTarget(c)} percent")
        for ((l, d) in levelTargets(c)) parts.add("a reminder when it " + (if (d > 0) "reaches" else "drops to") + " $l percent")
        return if (parts.isEmpty()) "You have no battery reminders. Say \"battery reminder on\" or \"remind me to unplug at 80 percent\"."
        else "Battery reminders: " + parts.joinToString("; ") + "."
    }

    /** Voice / tool entry point. kind = battery_alert | battery_remind | battery_unplug. */
    fun act(ctx: Context, kind: String, value: String): CommandExecutor.Res {
        val c = ctx.applicationContext
        return when (kind) {
            "battery_alert" -> when (value) {
                "on" -> { setLow(c, true); CommandExecutor.Res(true, "Low battery alerts are on. I'll warn you at ${lowLevels(c).joinToString(", ")} percent.", label = "Battery") }
                "off" -> { setLow(c, false); CommandExecutor.Res(true, "Low battery alerts are off.", label = "Battery") }
                "clear" -> { clearAll(c); CommandExecutor.Res(true, "All battery reminders cancelled.", label = "Battery") }
                else -> CommandExecutor.Res(true, describe(c), label = "Battery")
            }
            "battery_remind" -> {
                val pct = value.trim().toIntOrNull()
                if (pct == null || pct !in 1..99) return CommandExecutor.Res(false, "Give me a battery level between 1 and 99 percent.")
                val now = level(c)
                if (now == pct) return CommandExecutor.Res(true, "The battery is already at $pct percent.", label = "Battery")
                val dir = if (now in 0 until pct) 1 else -1
                saveTargets(c, levelTargets(c).filter { it.first != pct } + (pct to dir))
                sync(c)
                CommandExecutor.Res(
                    true,
                    if (dir > 0) "Okay, I'll tell you when the battery reaches $pct percent. It's at $now now, so keep it charging."
                    else "Okay, I'll tell you when the battery drops to $pct percent. It's at $now now.",
                    label = "Battery",
                )
            }
            "battery_unplug" -> {
                val pct = value.trim().toIntOrNull() ?: 100
                if (pct !in 1..100) return CommandExecutor.Res(false, "Give me a charge level between 1 and 100 percent.")
                setChargeTarget(c, pct)
                CommandExecutor.Res(
                    true,
                    if (pct >= 100) "Okay, I'll tell you when the battery is fully charged." else "Okay, I'll remind you to unplug at $pct percent.",
                    label = "Battery",
                )
            }
            else -> CommandExecutor.Res(false, "Unknown battery command.")
        }
    }

    // ── the alert itself ─────────────────────────────────────────────────

    private const val ALERT_CH = "pragon_battery_alerts"
    private var alertId = 50

    /** A heads-up notification, spoken aloud too unless the phone is on silent/vibrate or Pragon is muted. */
    fun alert(ctx: Context, title: String, text: String) {
        val c = ctx.applicationContext
        try {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(ALERT_CH, "Battery reminders", NotificationManager.IMPORTANCE_HIGH))
            val open = PendingIntent.getActivity(
                c, 5, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE
            )
            val id = alertId
            alertId = if (alertId >= 60) 50 else alertId + 1
            nm.notify(
                id,
                Notification.Builder(c, ALERT_CH).setSmallIcon(android.R.drawable.ic_lock_idle_low_battery)
                    .setContentTitle(title).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
                    .setContentIntent(open).setAutoCancel(true).build()
            )
        } catch (e: Exception) { }
        try {
            val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (am.ringerMode == AudioManager.RINGER_MODE_NORMAL && !Prefs.muted(c)) Speaker.get(c).speak(text)
        } catch (e: Exception) { }
    }

    // ── the watcher's decisions (also used for the first check right after you set a reminder) ──

    fun evaluate(ctx: Context, i: Intent?) {
        val c = ctx.applicationContext
        val pct = percentOf(i)
        if (pct < 0) return
        val plugged = pluggedOf(i)
        val call = Prefs.callMe(c)
        val p = sp(c)

        // 1. low battery
        if (lowOn(c)) {
            if (plugged) {
                if ((p.getString("fired_low", "") ?: "").isNotEmpty()) p.edit().putString("fired_low", "").apply()
            } else {
                val fired = (p.getString("fired_low", "") ?: "").split(",").mapNotNull { it.toIntOrNull() }.toMutableSet()
                val crossed = lowLevels(c).filter { pct <= it && it !in fired }
                if (crossed.isNotEmpty()) {
                    fired.addAll(crossed)
                    p.edit().putString("fired_low", fired.joinToString(",")).apply()
                    val critical = pct <= 5
                    alert(
                        c,
                        if (critical) "Battery critical: $pct%" else "Battery low: $pct%",
                        if (critical) "Battery is at $pct percent, $call. Please plug in the charger now."
                        else "Battery is at $pct percent, $call. You may want to charge your phone.",
                    )
                }
            }
        }

        // 2. unplug reminder
        val target = chargeTarget(c)
        if (target > 0) {
            val fired = p.getBoolean("charge_fired", false)
            if (plugged) {
                if (pct >= target && !fired) {
                    p.edit().putBoolean("charge_fired", true).apply()
                    alert(
                        c,
                        if (target >= 100) "Fully charged" else "Battery at $pct%",
                        if (target >= 100) "The battery is fully charged, $call. You can unplug the charger."
                        else "The battery has reached $pct percent, $call. You can unplug the charger.",
                    )
                }
            } else if (fired) p.edit().putBoolean("charge_fired", false).apply()
        }

        // 3. one-shot levels
        val targets = levelTargets(c)
        if (targets.isNotEmpty()) {
            val keep = ArrayList<Pair<Int, Int>>()
            for (t in targets) {
                val hit = (t.second > 0 && pct >= t.first) || (t.second < 0 && pct <= t.first)
                if (hit) alert(c, "Battery at $pct%", "Your battery has ${if (t.second > 0) "reached" else "dropped to"} ${t.first} percent, $call.")
                else keep.add(t)
            }
            if (keep.size != targets.size) { saveTargets(c, keep); sync(c) }
        }
    }
}

/** Watches the battery while any reminder is active. Shows one quiet notification, as Android requires. */
class BatteryReminderService : Service() {

    private var receiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try { startInForeground() } catch (e: Exception) { }
        if (!BatteryReminder.anyActive(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (receiver == null) {
            receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, i: Intent) {
                    BatteryReminder.evaluate(context, i)
                    if (!BatteryReminder.anyActive(context)) stopSelf()
                }
            }
            registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }
        // The first reading comes from the sticky broadcast: check right away, e.g. "remind me at 20" when it already is.
        BatteryReminder.evaluate(this, registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
        if (!BatteryReminder.anyActive(this)) { stopSelf(); return START_NOT_STICKY }
        refreshNotification()
        return START_STICKY
    }

    override fun onDestroy() {
        receiver?.let { try { unregisterReceiver(it) } catch (e: Exception) { } }
        receiver = null
        super.onDestroy()
    }

    private fun build(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("pragon_battery", "Battery watcher", NotificationManager.IMPORTANCE_MIN))
        return Notification.Builder(this, "pragon_battery")
            .setSmallIcon(android.R.drawable.ic_lock_idle_low_battery)
            .setContentTitle("Pragon is watching the battery")
            .setContentText(BatteryReminder.describe(this).removePrefix("Battery reminders: "))
            .setOngoing(true)
            .build()
    }

    private fun startInForeground() {
        val n = build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(7, n)
    }

    private fun refreshNotification() {
        try { getSystemService(NotificationManager::class.java).notify(7, build()) } catch (e: Exception) { }
    }
}

/** Puts the battery watcher back after a restart or an app update. */
class BatteryBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            try { BatteryReminder.sync(context) } catch (e: Exception) { }
            try { Firewall.restore(context) } catch (e: Exception) { }
            try { if (SidebarService.autoStart(context)) SidebarService.start(context) } catch (e: Exception) { }
        }
    }
}
