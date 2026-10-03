package com.pragon.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileInputStream

/**
 * Pragon Firewall: a LOCAL, on-device firewall built on Android's VpnService. Nothing is routed to any server:
 * traffic from the apps you choose is sent into a tunnel that simply throws every packet away, so those apps have no
 * internet. No VPN company, no logs, nothing leaves the phone.
 *
 *  - "block" mode: the apps you tick lose internet access (Wi-Fi and mobile data, IPv4 and IPv6).
 *  - "allow" mode: ONLY the apps you tick keep internet (Pragon itself always keeps it so it can reach your PC / Ollama).
 *
 * Android shows its usual key icon while a VPN is active. Only one VPN can be active at a time on Android, so this
 * cannot run together with another VPN app.
 */
object Firewall {

    @Volatile var droppedPackets = 0L

    fun prepareIntent(ctx: Context): Intent? = VpnService.prepare(ctx)

    /** Starts (or restarts) the firewall. Returns an Intent the caller must launch if Android still needs the user's OK. */
    fun start(ctx: Context): Intent? {
        val need = VpnService.prepare(ctx)
        if (need != null) return need
        Guard.setFirewallOn(ctx, true)
        ctx.startService(Intent(ctx, FirewallService::class.java))
        return null
    }

    fun stop(ctx: Context) {
        Guard.setFirewallOn(ctx, false)
        try { ctx.startService(Intent(ctx, FirewallService::class.java).setAction(FirewallService.ACTION_STOP)) } catch (e: Exception) { }
    }

    /** After a restart / update: if it was on and Android still trusts us, start it again without asking. */
    fun restore(ctx: Context) {
        if (!Guard.firewallOn(ctx)) return
        if (VpnService.prepare(ctx) != null) return
        try { ctx.startService(Intent(ctx, FirewallService::class.java)) } catch (e: Exception) { }
    }

    fun running(): Boolean = FirewallService.running

    /** Voice entry point. */
    fun act(ctx: Context, what: String): CommandExecutor.Res {
        val call = Prefs.callMe(ctx)
        return when (what) {
            "on" -> {
                if (Guard.firewallApps(ctx).isEmpty())
                    return CommandExecutor.Res(false, "The firewall has no apps selected yet, $call. Choose them in Settings > Security > Firewall.")
                val need = start(ctx)
                if (need != null) {
                    try {
                        ctx.startActivity(Intent(ctx, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("ask_vpn", true))
                    } catch (e: Exception) { }
                    CommandExecutor.Res(true, "Android needs your OK to start the firewall, $call. Tap OK on the screen that just opened.", label = "Firewall")
                } else CommandExecutor.Res(true, "Firewall on. " + describe(ctx), label = "Firewall")
            }
            "off" -> { stop(ctx); CommandExecutor.Res(true, "Firewall off. All apps have internet again.", label = "Firewall") }
            else -> CommandExecutor.Res(true, (if (running()) "The firewall is on. " else "The firewall is off. ") + describe(ctx), label = "Firewall")
        }
    }

    fun describe(ctx: Context): String {
        val n = Guard.firewallApps(ctx).size
        return if (Guard.firewallMode(ctx) == "allow") "Only $n selected app" + (if (n != 1) "s" else "") + " can use the internet."
        else "$n app" + (if (n != 1) "s are" else " is") + " blocked from the internet."
    }
}

class FirewallService : VpnService() {

    companion object {
        const val ACTION_STOP = "com.pragon.mobile.FIREWALL_STOP"
        @Volatile var running = false
    }

    private var tun: ParcelFileDescriptor? = null
    @Volatile private var alive = false
    private var reader: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { teardown(); stopSelf(); return START_NOT_STICKY }
        establish()
        return START_STICKY
    }

    private fun establish() {
        teardown()
        val mode = Guard.firewallMode(this)
        val apps = Guard.firewallApps(this).filter { it != packageName }
        // An empty "block" list would send EVERY app into the tunnel. Never do that.
        if (mode == "block" && apps.isEmpty()) { stopSelf(); return }
        try {
            val b = Builder().setSession("Pragon Firewall").setMtu(1500)
                .addAddress("10.77.77.1", 32).addRoute("0.0.0.0", 0)
                .addAddress("fd77:77:77::1", 128).addRoute("::", 0)
                .setBlocking(true)
            if (mode == "allow") {
                // everything is tunnelled (= blocked) except the apps you allowed and Pragon itself
                try { b.addDisallowedApplication(packageName) } catch (e: Exception) { }
                for (p in apps) try { b.addDisallowedApplication(p) } catch (e: Exception) { }
            } else {
                for (p in apps) try { b.addAllowedApplication(p) } catch (e: Exception) { }
            }
            tun = b.establish()
        } catch (e: Exception) {
            Guard.setFirewallOn(this, false)
            stopSelf()
            return
        }
        val fd = tun ?: run { stopSelf(); return }
        alive = true
        running = true
        Firewall.droppedPackets = 0
        showNotification()
        reader = Thread {
            // Read and discard: a packet nobody answers is a packet that went nowhere.
            val input = FileInputStream(fd.fileDescriptor)
            val buf = ByteArray(32767)
            while (alive) {
                val n = try { input.read(buf) } catch (e: Exception) { -1 }
                if (n < 0) break
                if (n > 0) Firewall.droppedPackets++
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun showNotification() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("pragon_firewall", "Firewall", NotificationManager.IMPORTANCE_MIN))
            val open = PendingIntent.getActivity(this, 8, Intent(this, SettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(this, "pragon_firewall").setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("Pragon Firewall is on").setContentText(Firewall.describe(this)).setContentIntent(open).setOngoing(true).build()
            if (Build.VERSION.SDK_INT >= 34) startForeground(9, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(9, n)
        } catch (e: Exception) { }
    }

    private fun teardown() {
        alive = false
        running = false
        try { tun?.close() } catch (e: Exception) { }
        tun = null
        reader = null
    }

    override fun onRevoke() { Guard.setFirewallOn(this, false); teardown(); stopSelf() }   // user turned the VPN off in Android
    override fun onDestroy() { teardown(); super.onDestroy() }
}

/**
 * Pragon Scanner: looks at every installed app on this phone, entirely offline, and flags the ones that deserve a
 * second look. It is NOT a virus-signature engine (that needs a cloud database, which would mean sending app data to a
 * third party). It checks what each app is ALLOWED to do, where it came from and whether it controls the phone.
 */
object Scanner {

    class Finding(val pkg: String, val label: String, val risk: Int, val reasons: List<String>)

    private val TRUSTED_STORES = setOf(
        "com.android.vending", "com.google.android.packageinstaller", "com.sec.android.app.samsungapps", "com.huawei.appmarket",
        "com.xiaomi.mipicks", "com.xiaomi.market", "com.oppo.market", "com.heytap.market", "com.vivo.appstore", "com.amazon.venezia",
        "org.fdroid.fdroid", "com.aurora.store",
    )

    private fun perms(pi: PackageInfo): Set<String> = pi.requestedPermissions?.toSet() ?: emptySet()

    @Suppress("DEPRECATION")
    fun scan(ctx: Context): List<Finding> {
        val pm = ctx.packageManager
        val enabledA11y = (android.provider.Settings.Secure.getString(ctx.contentResolver, "enabled_accessibility_services") ?: "").lowercase()
        val out = ArrayList<Finding>()
        val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES
        val pkgs = try { pm.getInstalledPackages(flags) } catch (e: Exception) { emptyList() }
        for (pi in pkgs) {
            val ai = pi.applicationInfo ?: continue
            if (ai.flags and ApplicationInfo.FLAG_SYSTEM != 0 && ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0) continue
            if (pi.packageName == ctx.packageName) continue
            val p = perms(pi)
            var risk = 0
            val why = ArrayList<String>()
            fun has(x: String) = p.contains("android.permission.$x")

            val installer = try {
                if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(pi.packageName).installingPackageName else pm.getInstallerPackageName(pi.packageName)
            } catch (e: Exception) { null }
            if (installer == null || installer !in TRUSTED_STORES) { risk += 1; why.add("installed from outside a known app store") }
            if (ai.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) { risk += 2; why.add("is a debug build (can be inspected and tampered with)") }

            if (enabledA11y.contains(pi.packageName.lowercase())) { risk += 3; why.add("has an accessibility service switched ON (can read the screen and tap for you)") }
            val spy = has("READ_SMS") || has("RECEIVE_SMS") || has("SEND_SMS")
            val net = has("INTERNET")
            if (spy && net) { risk += 3; why.add("can read/send text messages and has internet") }
            if (has("READ_CALL_LOG") && net) { risk += 2; why.add("can read your call log and has internet") }
            if (has("RECORD_AUDIO") && net && installer !in TRUSTED_STORES) { risk += 2; why.add("can record audio and has internet") }
            if (has("SYSTEM_ALERT_WINDOW") && installer !in TRUSTED_STORES) { risk += 2; why.add("can draw over other apps (used by tapjacking / fake login screens)") }
            if (has("REQUEST_INSTALL_PACKAGES")) { risk += 1; why.add("can install other apps") }
            if (has("BIND_DEVICE_ADMIN")) { risk += 2; why.add("can become a device administrator") }
            if (has("READ_CONTACTS") && net && installer !in TRUSTED_STORES) { risk += 1; why.add("can read contacts and has internet") }
            if (has("ACCESS_BACKGROUND_LOCATION") && net && installer !in TRUSTED_STORES) { risk += 1; why.add("tracks location in the background") }
            if (has("QUERY_ALL_PACKAGES") && installer !in TRUSTED_STORES) { risk += 1; why.add("can list every app you have") }
            if (has("RECEIVE_BOOT_COMPLETED") && has("SYSTEM_ALERT_WINDOW") && installer !in TRUSTED_STORES) { risk += 1; why.add("starts itself at boot and can draw over apps") }

            if (risk > 0) {
                val label = try { pm.getApplicationLabel(ai).toString() } catch (e: Exception) { pi.packageName }
                out.add(Finding(pi.packageName, label, risk, why))
            }
        }
        return out.sortedByDescending { it.risk }
    }

    fun level(risk: Int) = when { risk >= 6 -> "HIGH"; risk >= 3 -> "MEDIUM"; else -> "LOW" }

    fun toJson(f: List<Finding>): JSONArray = JSONArray().also { a ->
        f.forEach { a.put(JSONObject().put("pkg", it.pkg).put("label", it.label).put("level", level(it.risk)).put("reasons", JSONArray(it.reasons))) }
    }

    /** "scan my phone": a spoken summary. */
    fun spoken(ctx: Context): String {
        val call = Prefs.callMe(ctx)
        val f = scan(ctx)
        if (f.isEmpty()) return "I checked your apps and found nothing suspicious, $call."
        val high = f.filter { level(it.risk) == "HIGH" }
        val med = f.filter { level(it.risk) == "MEDIUM" }
        val sb = StringBuilder("I checked your apps, $call. ")
        sb.append(if (high.isEmpty()) "Nothing is high risk. " else "${high.size} high risk: " + high.take(3).joinToString(", ") { it.label } + ". ")
        if (med.isNotEmpty()) sb.append("${med.size} medium risk" + (if (high.isEmpty()) ": " + med.take(3).joinToString(", ") { it.label } else "") + ". ")
        sb.append("Open Settings > Security to see why and block them.")
        return sb.toString()
    }
}
