package com.pragon.mobile

import android.content.Context
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Everything the user has to switch ON before Pragon may do it, plus the privacy rules.
 * All settings live in one private file (pragon_security) that no other app can read.
 *
 *  - Privacy Shield: Pragon only talks to YOUR devices (your PC / Ollama server on your network). Any other host is
 *    blocked inside Pragon's network code, and every connection attempt is logged where you can see it.
 *  - Allowed apps: when on, Pragon opens only the apps you ticked.
 *  - Screen reading, accessibility features (TalkBack / Live Caption), macros: each has its own switch, all OFF by default.
 */
object Guard {

    /** Set once by PragonApp so the network code (which has no Context of its own) can read the switches. */
    @Volatile var app: Context? = null

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("pragon_security", Context.MODE_PRIVATE)

    // ── switches ─────────────────────────────────────────────────────────

    /** Default ON: a fresh install never sends anything to a third party. */
    fun privacyOn(c: Context) = sp(c).getBoolean("privacy", true)
    fun setPrivacy(c: Context, on: Boolean) = sp(c).edit().putBoolean("privacy", on).apply()

    fun allowlistOn(c: Context) = sp(c).getBoolean("allow_only", false)
    fun setAllowlist(c: Context, on: Boolean) = sp(c).edit().putBoolean("allow_only", on).apply()

    fun screenReadingOn(c: Context) = sp(c).getBoolean("screen_ai", false)
    fun setScreenReading(c: Context, on: Boolean) = sp(c).edit().putBoolean("screen_ai", on).apply()

    /** Lets Pragon switch TalkBack / Live Caption by voice (also needs the one-time adb grant for WRITE_SECURE_SETTINGS). */
    fun a11yFeaturesOn(c: Context) = sp(c).getBoolean("a11y_features", false)
    fun setA11yFeatures(c: Context, on: Boolean) = sp(c).edit().putBoolean("a11y_features", on).apply()

    fun macrosOn(c: Context) = sp(c).getBoolean("macros", true)
    fun setMacros(c: Context, on: Boolean) = sp(c).edit().putBoolean("macros", on).apply()

    // ── string sets stored as JSON arrays ────────────────────────────────

    private fun getSet(c: Context, key: String): LinkedHashSet<String> {
        val out = LinkedHashSet<String>()
        try {
            val a = JSONArray(sp(c).getString(key, "[]"))
            for (i in 0 until a.length()) out.add(a.getString(i))
        } catch (e: Exception) { }
        return out
    }

    private fun putSet(c: Context, key: String, v: Collection<String>) =
        sp(c).edit().putString(key, JSONArray(v.toList()).toString()).apply()

    fun allowedApps(c: Context): Set<String> = getSet(c, "allowed_apps")
    fun setAllowedApps(c: Context, pkgs: Collection<String>) = putSet(c, "allowed_apps", pkgs)

    fun sidebarApps(c: Context): List<String> = getSet(c, "sidebar_apps").toList()
    fun setSidebarApps(c: Context, pkgs: Collection<String>) = putSet(c, "sidebar_apps", pkgs)

    /** Hosts the user explicitly trusts (e.g. a Tailscale / dynamic-DNS name of their own PC). */
    fun trustedHosts(c: Context): Set<String> = getSet(c, "trusted_hosts")
    fun setTrustedHosts(c: Context, hosts: Collection<String>) = putSet(c, "trusted_hosts", hosts.map { it.trim().lowercase() }.filter { it.isNotEmpty() })

    /** Blocked-by-firewall apps and mode (used by Firewall.kt). */
    fun firewallApps(c: Context): Set<String> = getSet(c, "fw_apps")
    fun setFirewallApps(c: Context, pkgs: Collection<String>) = putSet(c, "fw_apps", pkgs)
    fun firewallMode(c: Context): String = sp(c).getString("fw_mode", "block") ?: "block"          // block | allow
    fun setFirewallMode(c: Context, m: String) = sp(c).edit().putString("fw_mode", if (m == "allow") "allow" else "block").apply()
    fun firewallOn(c: Context) = sp(c).getBoolean("fw_on", false)
    fun setFirewallOn(c: Context, on: Boolean) = sp(c).edit().putBoolean("fw_on", on).apply()

    // ── allowed apps ─────────────────────────────────────────────────────

    /** True when Pragon may open [pkg]. Pragon itself and the Android system shell are always fine. */
    fun canOpen(c: Context, pkg: String?): Boolean {
        if (!allowlistOn(c)) return true
        if (pkg == null) return false
        if (pkg == c.packageName || pkg == "android" || pkg == "com.android.systemui") return true
        return pkg in allowedApps(c)
    }

    // ── privacy shield ───────────────────────────────────────────────────

    class LogLine(val host: String, val allowed: Boolean, val count: Int, val last: Long)

    private val log = LinkedHashMap<String, IntArray>()       // "A host" / "B host" -> [count]
    private val lastSeen = HashMap<String, Long>()

    private fun record(host: String, allowed: Boolean) = synchronized(log) {
        val k = (if (allowed) "A " else "B ") + host
        val a = log.getOrPut(k) { IntArray(1) }
        a[0]++
        lastSeen[k] = System.currentTimeMillis()
        if (log.size > 60) log.remove(log.keys.first())
    }

    fun connectionLog(): List<LogLine> = synchronized(log) {
        log.entries.map { LogLine(it.key.substring(2), it.key[0] == 'A', it.value[0], lastSeen[it.key] ?: 0L) }
            .sortedByDescending { it.last }
    }
    fun clearLog() = synchronized(log) { log.clear(); lastSeen.clear() }

    /**
     * Private / local addresses: loopback, 10.x, 172.16-31.x, 192.168.x, 169.254.x, Tailscale's 100.64-127.x, IPv6 local.
     * Plain names without dots ("mypc") and .local / .lan / .home / .internal names are also treated as your own network.
     */
    fun isLocalHost(host0: String): Boolean {
        val host = host0.trim().lowercase().trim('[', ']')
        if (host.isEmpty()) return false
        if (host == "localhost") return true
        if (Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(host)) {
            val p = host.split(".").map { it.toInt() }
            if (p.any { it > 255 }) return false
            return p[0] == 10 || p[0] == 127 ||
                (p[0] == 172 && p[1] in 16..31) ||
                (p[0] == 192 && p[1] == 168) ||
                (p[0] == 169 && p[1] == 254) ||
                (p[0] == 100 && p[1] in 64..127)
        }
        if (host.contains(":")) {                                   // IPv6 literal
            return host == "::1" || host.startsWith("fe80") || host.startsWith("fc") || host.startsWith("fd")
        }
        if (!host.contains(".")) return true                       // "mypc"
        return listOf(".local", ".lan", ".home", ".internal", ".localdomain", ".home.arpa").any { host.endsWith(it) }
    }

    /** null = fine to connect, else the reason it is blocked. */
    fun checkHost(c: Context, host: String): String? {
        if (!privacyOn(c)) return null
        if (isLocalHost(host)) return null
        val h = host.lowercase()
        if (trustedHosts(c).any { h == it || h.endsWith(".$it") }) return null
        return "Privacy Shield blocked a connection to $host. Pragon only talks to your own devices. " +
            "If $host is yours, add it under Settings > Security > Trusted hosts."
    }

    /** Add to every OkHttpClient Pragon builds. Blocked requests never leave the phone. */
    class ShieldInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val host = chain.request().url.host
            val ctx = app
            // No context yet (should never happen): fail closed for anything that is not on your own network.
            val why = if (ctx == null) (if (isLocalHost(host)) null else "Privacy Shield blocked a connection to $host.") else checkHost(ctx, host)
            if (why != null) {
                record(host, false)
                throw IOException(why)
            }
            record(host, true)
            return chain.proceed(chain.request())
        }
    }

    /** Pragon's own engine check: Gemini is Google's server, so Privacy Shield refuses it. */
    fun engineProblem(c: Context): String? {
        if (privacyOn(c) && Prefs.engine(c) == "gemini")
            return "Privacy Shield is on, and Gemini is Google's cloud, so I won't send anything to it. " +
                "Switch to Ollama (your own server) in Settings > AI engine, or turn Privacy Shield off."
        return null
    }

    /** Speech recognition should stay on the phone while Privacy Shield is on. */
    fun preferOfflineSpeech(c: Context) = privacyOn(c)

    // ── a summary for Settings / voice ───────────────────────────────────

    fun summary(c: Context): JSONObject = JSONObject()
        .put("privacy", privacyOn(c))
        .put("allowOnly", allowlistOn(c))
        .put("screenAI", screenReadingOn(c))
        .put("a11y", a11yFeaturesOn(c))
        .put("macros", macrosOn(c))
        .put("trusted", trustedHosts(c).joinToString(", "))
        .put("fwOn", firewallOn(c))
        .put("fwMode", firewallMode(c))
}
