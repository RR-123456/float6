package com.pragon.mobile

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import java.util.Calendar
import java.util.Locale
import android.view.KeyEvent
import org.json.JSONObject

/** Runs one command from the PC. Same action names as the PC's phone_control tool. */
object CommandExecutor {

    data class Res(val ok: Boolean, val msg: String, val unsupported: Boolean = false, val label: String = "")

    private const val YT = "com.google.android.youtube"

    private val ALIASES = mapOf(
        "youtube" to YT,
        "youtube music" to "com.google.android.apps.youtube.music",
        "whatsapp" to "com.whatsapp",
        "instagram" to "com.instagram.android",
        "snapchat" to "com.snapchat.android",
        "facebook" to "com.facebook.katana",
        "messenger" to "com.facebook.orca",
        "telegram" to "org.telegram.messenger",
        "twitter" to "com.twitter.android",
        "x" to "com.twitter.android",
        "spotify" to "com.spotify.music",
        "netflix" to "com.netflix.mediaclient",
        "chrome" to "com.android.chrome",
        "gmail" to "com.google.android.gm",
        "maps" to "com.google.android.apps.maps",
        "google maps" to "com.google.android.apps.maps",
        "photos" to "com.google.android.apps.photos",
        "play store" to "com.android.vending",
        "google" to "com.google.android.googlequicksearchbox",
        "discord" to "com.discord",
        "zoom" to "us.zoom.videomeetings",
    )

    private fun screenStart(ctx: Context): Res {
        if (ScreenShareService.running) return Res(true, "The phone is already sharing its screen.")
        val i = Intent(ctx, ScreenConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(i)
        } catch (e: Exception) {
            // Background launch can be blocked; fall through to the notification below.
        }
        // Fallback: a tap-to-share notification in case Android blocked the pop-up.
        try {
            val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
            nm.createNotificationChannel(
                android.app.NotificationChannel("pragon_screen_ask", "Screen share request",
                    android.app.NotificationManager.IMPORTANCE_HIGH)
            )
            val pi = android.app.PendingIntent.getActivity(ctx, 2, i, android.app.PendingIntent.FLAG_IMMUTABLE)
            nm.notify(3, android.app.Notification.Builder(ctx, "pragon_screen_ask")
                .setContentTitle("Pragon wants to show your screen on the PC")
                .setContentText("Tap to choose Start now")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build())
        } catch (e: Exception) {
        }
        return Res(true, "Tap 'Start now' on your phone to share its screen.")
    }

    private fun screenStop(ctx: Context): Res {
        if (!ScreenShareService.running) return Res(true, "The phone wasn't sharing its screen.")
        ctx.startService(Intent(ctx, ScreenShareService::class.java).setAction(ScreenShareService.ACTION_STOP))
        return Res(true, "Stopped sharing the phone screen.")
    }

    /** Actions that loosen a protection or read private data. Only Pragon's own user (voice / chat / sidebar) may run them, never the AI model or a PC. */
    private val USER_ONLY = setOf("privacy", "allowlist", "a11y_feature", "screen_read", "screen_ask", "macro_record", "firewall_off")

    /**
     * Runs one command. [user] = it came from the person using this phone (their voice, chat or sidebar), not from an
     * AI tool call or the PC link. Everything that was carried out is also remembered if a macro is being recorded.
     */
    fun run(ctx: Context, cmd: JSONObject, fromStandalone: Boolean = false, user: Boolean = false): Res {
        val r = runInner(ctx, cmd, fromStandalone, user)
        try {
            val a = cmd.optString("action").lowercase()
            val v = cmd.optString("app_name").ifEmpty { cmd.optString("direction").ifEmpty { cmd.optString("value") } }.trim()
            Macros.onCommand(ctx.applicationContext, a, v, r.ok)
        } catch (e: Exception) { }
        return r
    }

    private fun runInner(ctx: Context, cmd: JSONObject, fromStandalone: Boolean, user: Boolean): Res {
        val action = cmd.optString("action").lowercase()
        val value = cmd.optString("value").trim()
        val app = cmd.optString("app_name").trim()
        if (action in USER_ONLY && !user)
            return Res(false, "That can only be done by you directly on the phone, not by the AI or the PC link.", unsupported = true)
        return try {
            when (action) {
                "volume" -> volume(ctx, value)
                "brightness" -> brightness(ctx, value)
                "flashlight_level" -> flashlightLevel(ctx, value)
                "close_all" -> closeAll(ctx)
                "force_stop" -> forceStop(ctx, app.ifEmpty { value })
                "macro_record", "macro_stop", "macro_run", "macro_delete", "macro_list", "macro_cancel" -> Macros.act(ctx, action, value)
                "firewall" -> if (value == "off" && !user) Res(false, "Turn the firewall off in Settings > Security.") else Firewall.act(ctx, value)
                "scan" -> Res(true, Scanner.spoken(ctx), label = "Scanner")
                "privacy" -> privacyCmd(ctx, value)
                "allowlist" -> allowlistCmd(ctx, value)
                "sidebar" -> sidebarCmd(ctx, value)
                "a11y_feature" -> { val p = value.split(":"); AccessFeatures.set(ctx, p[0], p.getOrNull(1) != "off") }
                "screen_read" -> ScreenAI.read(ctx)
                "screen_ask" -> ScreenAI.ask(ctx, value)
                else -> runMain(ctx, cmd, action, value, app, fromStandalone)
            }
        } catch (e: Exception) {
            Res(false, "Phone error: ${e.message}")
        }
    }

    private fun runMain(ctx: Context, cmd: JSONObject, action: String, value: String, app: String, fromStandalone: Boolean): Res {
        return try {
            when (action) {
                "status" -> status(ctx)
                "screen_start" -> screenStart(ctx)
                "screen_stop" -> screenStop(ctx)
                "open_app" -> openApp(ctx, app.ifEmpty { value }, fromStandalone)
                "close_app" -> closeApp(ctx, app.ifEmpty { value })
                "open_url" -> openUrl(ctx, value)
                "youtube_search", "search_youtube" -> youtubeSearch(ctx, value)
                "web_search", "search" -> webSearch(ctx, value)
                "key" -> key(ctx, value.ifEmpty { app }, cmd.optInt("count", 1))
                "swipe" -> swipe(ctx, cmd.optString("direction").ifEmpty { value })
                "tap" -> tap(cmd.optInt("x", -1), cmd.optInt("y", -1))
                "type_text" -> typeText(value)
                "call" -> call(ctx, value)
                "flashlight", "torch" -> flashlight(ctx, value)
                "timer" -> timer(ctx, value)
                "alarm" -> alarm(ctx, value)
                "alarm_cancel" -> alarmCancel(ctx)
                "alarm_show" -> go(ctx, Intent(AlarmClock.ACTION_SHOW_ALARMS), "Here are your alarms.", label = "Alarms")
                "timer_cancel" -> timerCancel(ctx)
                "timer_show" -> go(ctx, Intent(AlarmClock.ACTION_SHOW_TIMERS), "Here are your timers.", label = "Timers")
                "stopwatch" -> Stopwatch.act(ctx, value.ifEmpty { "status" })
                "calc", "calculate", "calculator" -> calc(ctx, value, onScreen = false)
                "calc_screen" -> calc(ctx, value, onScreen = true)
                "click", "press" -> click(ctx, value, cmd.optInt("wait_ms", 1000))
                "double_click" -> doubleClick()
                "settings", "open_settings" -> settings(ctx, value)
                "typer", "typer_mode" -> typer(ctx, value)
                "battery_alert", "battery_remind", "battery_unplug" -> BatteryReminder.act(ctx, action, value)
                "ollama_pull" -> if (value.isBlank()) Res(false, "Which model should I pull? For example: ollama pull llama3.2.")
                    else Res(true, OllamaTools.pullInBackground(ctx, value), label = "Ollama")
                "ollama_list" -> Res(true, OllamaTools.listSentence(ctx), label = "Ollama")
                "ollama_use" -> if (value.isBlank()) Res(false, "Which model should I use?")
                    else { OllamaTools.use(ctx, value); Res(true, "Now using $value on Ollama.", label = "Ollama") }
                else -> Res(false, "The phone app can't do '$action'.", unsupported = true)
            }
        } catch (e: Exception) {
            Res(false, "Phone error: ${e.message}")
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun needAccessibility() = Res(
        false,
        "Turn on the PragonMobile accessibility service on the phone (Settings > Accessibility > PragonMobile)."
    )

    @Suppress("DEPRECATION")
    private fun wake(ctx: Context): String {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) {
            pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "pragon:wake"
            ).acquire(3000)
            try { Thread.sleep(400) } catch (e: InterruptedException) { }
        }
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return if (km.isKeyguardLocked) " (The phone is locked - unlock it to see the result.)" else ""
    }

    /**
     * Starts [intent]. When [verifyPkg] is given and the accessibility service is on, waits until that app
     * is really on screen, so "YouTube opened" is only ever said when it is true (Android silently drops
     * launches from the background on some phones instead of raising an error).
     */
    private fun targetPackage(ctx: Context, intent: Intent): String? =
        intent.component?.packageName ?: intent.`package`
            ?: try { ctx.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName } catch (e: Exception) { null }

    private fun go(ctx: Context, intent: Intent, okMsg: String, verifyPkg: String? = null, label: String = "", guard: Boolean = true): Res {
        if (guard && Guard.allowlistOn(ctx)) {
            val pkg = targetPackage(ctx, intent)
            if (!Guard.canOpen(ctx, pkg)) {
                val what = label.ifBlank { pkg ?: "that" }
                return Res(false, "$what isn't on your allowed apps list, so I won't open it. Add it in Settings > Allowed apps.", label = label)
            }
        }
        val note = wake(ctx)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val svc = PragonAccessibilityService.instance
        val before = if (verifyPkg != null) svc?.foregroundPackage() else null
        try {
            ctx.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            return Res(false, "No app on the phone can handle that.")
        } catch (e: Exception) {
            return Res(
                false,
                "Android blocked the launch (${e.message}). In PragonMobile, allow 'Display over other apps' and the accessibility service."
            )
        }
        if (verifyPkg != null && svc != null && before != verifyPkg) {
            val end = System.currentTimeMillis() + 3500
            while (System.currentTimeMillis() < end) {
                val now = svc.foregroundPackage()
                if (now == verifyPkg || (now != null && now != before)) return Res(true, okMsg + note, label = label)
                try { Thread.sleep(150) } catch (e: InterruptedException) { break }
            }
            return Res(true, "I asked Android to open ${label.ifBlank { "it" }}, but I couldn't confirm it appeared. " +
                "If nothing opened, allow 'Display over other apps' for Pragon.", label = label)
        }
        return Res(true, okMsg + note, label = label)
    }

    // ── actions ──────────────────────────────────────────────────────────

    private fun status(ctx: Context): Res {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val a11y = if (PragonAccessibilityService.instance != null) "" else
            " Accessibility service is OFF - taps and buttons won't work until you enable it."
        return Res(
            true,
            "Connected to ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}), battery $level%.$a11y"
        )
    }

    private fun special(name: String): Intent? = when (name) {
        "camera" -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        "settings" -> Intent(Settings.ACTION_SETTINGS)
        "wifi settings" -> Intent(Settings.ACTION_WIFI_SETTINGS)
        "bluetooth settings" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        "dialer", "phone" -> Intent(Intent.ACTION_DIAL)
        else -> null
    }

    /** Finds the installed app for a spoken/typed name. Returns (label, package) or null. */
    private fun resolveApp(ctx: Context, name: String): Pair<String, String>? {
        val pm = ctx.packageManager
        val key = name.lowercase().trim()
        ALIASES[key]?.let { pkg ->
            if (pm.getLaunchIntentForPackage(pkg) != null) {
                val real = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { name }
                return real to pkg
            }
        }
        val q = norm(name)
        if (q.isEmpty()) return null
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        return apps.firstOrNull { norm(it.first) == q }
            ?: (if (q.length >= 2) apps.filter { norm(it.first).startsWith(q) }.minByOrNull { it.first.length } else null)
            ?: (if (q.length >= 3) apps.filter { norm(it.first).contains(q) }.minByOrNull { it.first.length } else null)
    }

    private fun openApp(ctx: Context, name: String, fromStandalone: Boolean = false): Res {
        if (name.isBlank()) return Res(false, "Which app should I open?")
        val key = name.lowercase().trim()
        special(key)?.let { return go(ctx, it, "Opened $name on your phone.", label = name) }

        val hit = resolveApp(ctx, name)
        val intent = hit?.let { ctx.packageManager.getLaunchIntentForPackage(it.second) }
            ?: return Res(false, "I couldn't find an app called '$name' on the phone.", label = name)
        // Opened through Pragon standalone: start the floating voice bubble FIRST, while Pragon
        // is still on screen (Android only lets a microphone service start from the foreground).
        if (fromStandalone) FloatMode.autoStart(ctx)
        val shown = if (hit.first == name) name else hit.first
        val r = go(ctx, intent, "Opened $shown on your phone.", verifyPkg = hit.second, label = shown)
        if (r.ok) try { SidebarService.countOpen(ctx, hit.second) } catch (e: Exception) { }
        return r
    }

    private val ME_WORDS = setOf("this", "this app", "it", "app", "the app", "current app", "current")

    /** (package, label) for a spoken name, or "this app" = whatever is in front. Second value is an error message if it fails. */
    private fun target(ctx: Context, name0: String): Triple<String?, String, String?> {
        val name = name0.trim()
        if (name.isBlank() || name.lowercase() in ME_WORDS) {
            val svc = PragonAccessibilityService.instance
                ?: return Triple(null, "", "To close the app in front I need the Pragon accessibility service turned on. You can also say the app's name.")
            val pkg = svc.foregroundPackage()?.takeIf { it != ctx.packageName && !isSystemShell(ctx, it) }
                ?: return Triple(null, "", "There's no other app open to close.")
            val label = try { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }
            return Triple(pkg, label, null)
        }
        val hit = resolveApp(ctx, name) ?: return Triple(null, "", "I couldn't find an app called '$name' on the phone.")
        return Triple(hit.second, if (hit.first == name) name.replaceFirstChar { it.uppercase() } else hit.first, null)
    }

    private fun goHome(ctx: Context) {
        val svc = PragonAccessibilityService.instance
        if (svc != null) { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME); return }
        try { ctx.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
    }

    /**
     * Closes an app cleanly: if it is on screen, go Home first (so it is a background app), then tell Android to end the
     * app's process. No Settings screens pop up, it works in every language, and it takes about half a second.
     * Apps that keep a foreground service alive (music, navigation, downloads) survive this, so the reply says so and
     * "force stop <app>" ends those too.
     */
    private fun closeApp(ctx: Context, name0: String): Res {
        val (pkg, label, err) = target(ctx, name0)
        if (pkg == null) return Res(false, err ?: "Which app should I close?")
        if (pkg == ctx.packageName) return Res(false, "I won't close myself. Say goodbye to close float mode.")
        val svc = PragonAccessibilityService.instance
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val front = svc?.foregroundPackage()
        if (front == pkg) { goHome(ctx); sleepMs(550) }
        try { am.killBackgroundProcesses(pkg) } catch (e: SecurityException) {
            return Res(false, "Android refused to close $label (missing permission). Try 'force stop $label'.", label = label)
        }
        sleepMs(300)
        // a second pass catches apps that restart a helper process right away
        try { am.killBackgroundProcesses(pkg) } catch (e: Exception) { }
        val stillPlaying = audio.isMusicActive
        return Res(
            true,
            if (stillPlaying) "Closed $label. Sound is still playing, so an app may be running in the background. Say force stop $label to end it completely."
            else "Closed $label.",
            label = label,
        )
    }

    /** Last resort: App info > Force stop, driven through the accessibility service, ends on the home screen. */
    private fun forceStop(ctx: Context, name0: String): Res {
        val svc = PragonAccessibilityService.instance ?: return needAccessibility()
        val (pkg, label, err) = target(ctx, name0)
        if (pkg == null) return Res(false, err ?: "Which app should I force stop?")
        if (pkg == ctx.packageName) return Res(false, "I won't close myself. Say goodbye to close float mode.")
        val note = wake(ctx)
        val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i) } catch (e: Exception) {
            return Res(false, "Android blocked opening the app info screen (${e.message}). Allow 'Display over other apps' for Pragon.")
        }
        return when (svc.forceStopCurrentAppInfo()) {
            PragonAccessibilityService.StopResult.DONE -> Res(true, "Force stopped $label.$note", label = label)
            PragonAccessibilityService.StopResult.NOT_RUNNING -> Res(true, "$label wasn't running.", label = label)
            PragonAccessibilityService.StopResult.NO_BUTTON ->
                Res(false, "I couldn't find the Force stop button for $label on this phone's settings screen.")
            PragonAccessibilityService.StopResult.NO_CONFIRM ->
                Res(false, "I pressed Force stop for $label but the confirmation didn't appear.")
        }
    }

    /** "close all apps": ends every other app that is not needed (keeps Pragon, the launcher, the keyboard, the phone app). */
    private fun closeAll(ctx: Context): Res {
        val pm = ctx.packageManager
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val keep = HashSet<String>()
        keep.add(ctx.packageName); keep.add("com.android.systemui"); keep.add("android"); keep.add("com.android.phone")
        pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName?.let { keep.add(it) }
        try {
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.enabledInputMethodList.forEach { keep.add(it.packageName) }
        } catch (e: Exception) { }
        pm.resolveActivity(Intent(Intent.ACTION_DIAL), 0)?.activityInfo?.packageName?.let { keep.add(it) }
        goHome(ctx); sleepMs(500)
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }.distinct().filter { it !in keep }
        var n = 0
        for (p in apps) try { am.killBackgroundProcesses(p); n++ } catch (e: Exception) { }
        return Res(true, "Closed the background apps ($n checked). Pragon, your launcher and keyboard were left alone.", label = "Apps")
    }

    private fun sleepMs(ms: Long) { try { Thread.sleep(ms) } catch (e: InterruptedException) { } }

    private fun isSystemShell(ctx: Context, pkg: String): Boolean {
        if (pkg == "com.android.systemui") return true
        val home = ctx.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
        return home?.activityInfo?.packageName == pkg
    }

    private fun openUrl(ctx: Context, url0: String): Res {
        if (url0.isBlank()) return Res(false, "Which link should I open?")
        val url = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(url0)) url0 else "https://$url0"
        return go(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(url)), "Opened $url on your phone.")
    }

    private fun youtubeSearch(ctx: Context, q: String): Res {
        if (q.isBlank()) return Res(false, "What should I search for on YouTube?")
        val i = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))
        )
        if (ctx.packageManager.getLaunchIntentForPackage(YT) != null) i.setPackage(YT)
        return go(ctx, i, "Searching YouTube for '$q' on your phone.", verifyPkg = if (i.`package` == YT) YT else null, label = "YouTube")
    }

    private fun webSearch(ctx: Context, q: String): Res {
        if (q.isBlank()) return Res(false, "What should I search for?")
        val i = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))
        )
        return go(ctx, i, "Searching Google for '$q' on your phone.")
    }

    private fun media(audio: AudioManager, code: Int, label: String): Res {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return Res(true, "Sent $label.")
    }

    private fun key(ctx: Context, name0: String, count0: Int): Res {
        val name = name0.lowercase().trim().replace(' ', '_')
        val count = count0.coerceIn(1, 30)
        val svc = PragonAccessibilityService.instance

        fun global(action: Int, label: String): Res {
            val s = svc ?: return needAccessibility()
            return if (s.performGlobalAction(action)) Res(true, "Done ($label).")
            else Res(false, "The phone refused '$label'.")
        }

        val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return when (name) {
            "home" -> global(AccessibilityService.GLOBAL_ACTION_HOME, "home")
            "back" -> global(AccessibilityService.GLOBAL_ACTION_BACK, "back")
            "recents" -> global(AccessibilityService.GLOBAL_ACTION_RECENTS, "recent apps")
            "notifications" -> global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "notifications")
            "quick_settings" -> global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, "quick settings")
            "close_panel" -> global(AccessibilityService.GLOBAL_ACTION_BACK, "close panel")
            "power" -> global(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG, "power menu")
            "lock", "sleep" ->
                if (Build.VERSION.SDK_INT >= 28) global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, "lock")
                else Res(false, "Locking the screen needs Android 9 or newer.", unsupported = true)
            "wake" -> Res(true, "Screen on." + wake(ctx))
            "screenshot" ->
                if (Build.VERSION.SDK_INT >= 28) global(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT, "screenshot")
                else Res(false, "Screenshots by voice need Android 9 or newer.", unsupported = true)
            "volume_up", "volume_down" -> {
                val dir = if (name == "volume_up") AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                repeat(count) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, if (it == count - 1) AudioManager.FLAG_SHOW_UI else 0) }
                Res(true, "Volume ${if (name == "volume_up") "up" else "down"}" + (if (count > 1) " x$count" else "") + ".")
            }
            "mute" -> {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
                Res(true, "Toggled mute.")
            }
            // Separate keys on purpose: "pause" can only pause and "play" can only play. They are also checked against
            // what the phone is really doing, so they can never act like one toggle button.
            "pause" ->
                if (!audio.isMusicActive) Res(true, "Nothing is playing right now.")
                else media(audio, KeyEvent.KEYCODE_MEDIA_PAUSE, "pause")
            "play" ->
                if (audio.isMusicActive) Res(true, "It's already playing.")
                else media(audio, KeyEvent.KEYCODE_MEDIA_PLAY, "play")
            "play_pause" -> media(audio, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "play/pause")
            "next" -> media(audio, KeyEvent.KEYCODE_MEDIA_NEXT, "next track")
            "previous" -> media(audio, KeyEvent.KEYCODE_MEDIA_PREVIOUS, "previous track")
            else -> Res(false, "The phone app doesn't support key '$name'.", unsupported = true)
        }
    }

    private fun swipe(ctx: Context, direction: String): Res {
        val s = PragonAccessibilityService.instance ?: return needAccessibility()
        val m = ctx.resources.displayMetrics
        val w = m.widthPixels.toFloat()
        val h = m.heightPixels.toFloat()
        val (x1, y1, x2, y2) = when (direction.lowercase().trim()) {
            "up" -> listOf(w / 2, h * .75f, w / 2, h * .25f)
            "down" -> listOf(w / 2, h * .25f, w / 2, h * .75f)
            "left" -> listOf(w * .85f, h / 2, w * .15f, h / 2)
            "right" -> listOf(w * .15f, h / 2, w * .85f, h / 2)
            else -> return Res(false, "Swipe direction must be up, down, left or right.")
        }
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        return if (s.gesture(p, 300)) Res(true, "Swiped ${direction.lowercase()}.")
        else Res(false, "The phone didn't perform the swipe.")
    }

    private fun tap(x: Int, y: Int): Res {
        val s = PragonAccessibilityService.instance ?: return needAccessibility()
        if (x < 0 || y < 0) return Res(false, "Tap needs x and y coordinates.")
        val p = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return if (s.gesture(p, 60)) Res(true, "Tapped ($x, $y).") else Res(false, "The phone didn't perform the tap.")
    }

    private fun typeText(text: String): Res {
        val s = PragonAccessibilityService.instance ?: return needAccessibility()
        if (text.isEmpty()) return Res(false, "What should I type?")
        return if (s.typeText(text)) Res(true, "Typed it on your phone.")
        else Res(false, "Tap a text box on the phone first, then try again.")
    }

    private fun call(ctx: Context, number: String): Res {
        val digits = number.replace(Regex("[^0-9+*#]"), "")
        if (digits.isEmpty()) return Res(false, "Which number should I dial?")
        val i = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(digits)))
        val r = go(ctx, i, "Opened the dialer with $digits - tap the call button to place it.")
        return r
    }

    private var torchOn = false

    private fun flashlight(ctx: Context, v: String): Res {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            val ch = cm.getCameraCharacteristics(it)
            ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: return Res(false, "This phone has no flashlight I can use.")
        val want = when (v.lowercase().trim()) { "on" -> true; "off" -> false; else -> !torchOn }
        cm.setTorchMode(id, want)
        torchOn = want
        return Res(true, if (want) "Flashlight on." else "Flashlight off.", label = "Flashlight")
    }

    private fun timer(ctx: Context, v: String): Res {
        val secs = v.trim().toIntOrNull() ?: VoiceParser.durationSeconds(v.lowercase())
            ?: return Res(false, "How long should the timer run?")
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, secs).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        val human = if (secs % 3600 == 0) "${secs / 3600} hour" + (if (secs >= 7200) "s" else "")
            else if (secs % 60 == 0) "${secs / 60} minute" + (if (secs >= 120) "s" else "") else "$secs seconds"
        return go(ctx, i, "Timer set for $human.", label = "Timer", guard = false)
    }

    private fun clock12(h: Int, m: Int): String {
        val ap = if (h < 12) "AM" else "PM"
        val hh = if (h % 12 == 0) 12 else h % 12
        return String.format(Locale.US, "%d:%02d %s", hh, m, ap)
    }

    private fun alarm(ctx: Context, v: String): Res {
        val h: Int
        val m: Int
        val what: String
        if (v.startsWith("in:")) {
            val secs = v.removePrefix("in:").toIntOrNull() ?: return Res(false, "How long from now should the alarm ring?")
            val cal = Calendar.getInstance().apply { add(Calendar.SECOND, secs) }
            h = cal.get(Calendar.HOUR_OF_DAY); m = cal.get(Calendar.MINUTE)
            val mins = (secs + 59) / 60
            what = "Alarm set for ${clock12(h, m)}, in " + (if (mins >= 60) "${mins / 60} hour" + (if (mins >= 120) "s" else "") + (if (mins % 60 != 0) " ${mins % 60} minutes" else "") else "$mins minute" + (if (mins != 1) "s" else "")) + "."
        } else {
            val t = VoiceParser.clockTime(v.lowercase().replace(":", " ").trim()) ?: return Res(false, "What time should the alarm ring?")
            h = t.substring(0, 2).toInt(); m = t.substring(3, 5).toInt()
            what = "Alarm set for ${clock12(h, m)}."
        }
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return go(ctx, i, what, label = "Alarm", guard = false)
    }

    private fun alarmCancel(ctx: Context): Res {
        val i = Intent(AlarmClock.ACTION_DISMISS_ALARM)
            .putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_NEXT)
        val r = go(ctx, i, "Cancelled your next alarm.", label = "Alarm", guard = false)
        if (r.ok) return r
        // This clock app can't cancel by itself: open the list so you can switch it off.
        return go(ctx, Intent(AlarmClock.ACTION_SHOW_ALARMS), "Your clock app can't cancel alarms by voice, so here is the alarm list.", label = "Alarms")
    }

    private fun timerCancel(ctx: Context): Res {
        val r = go(ctx, Intent(AlarmClock.ACTION_DISMISS_TIMER), "Cancelled the timer.", label = "Timer", guard = false)
        if (r.ok) return r
        return go(ctx, Intent(AlarmClock.ACTION_SHOW_TIMERS), "Your clock app can't cancel timers by voice, so here is the timer list.", label = "Timers")
    }

    // ── click ────────────────────────────────────────────────────────────

    private fun click(ctx: Context, target: String, waitMs: Int = 1000): Res {
        val s = PragonAccessibilityService.instance ?: return needAccessibility()
        val t = target.trim()
        if (t.isEmpty()) {
            return when (s.clickFocusedOrCenter()) {
                "focused" -> Res(true, "Clicked.", label = "Click")
                "center" -> Res(true, "Tapped the middle of the screen.", label = "Click")
                else -> Res(false, "The phone didn't register the tap.")
            }
        }
        // The thing may still be loading: look for up to ~1 second before giving up.
        var tries = 0
        val maxTries = (waitMs / 250).coerceIn(1, 20)
        while (!s.hasText(t) && tries < maxTries) {
            try { Thread.sleep(250) } catch (e: InterruptedException) { break }
            tries++
        }
        if (!s.hasText(t)) return Res(false, "I couldn't find '$t' on the screen.")
        return if (s.clickText(t)) Res(true, "Clicked $t.", label = t) else Res(false, "I found '$t' but the phone wouldn't press it.")
    }

    private fun doubleClick(): Res {
        val s = PragonAccessibilityService.instance ?: return needAccessibility()
        val a = s.tapCenter()
        try { Thread.sleep(110) } catch (e: InterruptedException) { }
        val b = s.tapCenter()
        return if (a && b) Res(true, "Double tapped.", label = "Click") else Res(false, "The phone didn't register the double tap.")
    }

    // ── calculator ───────────────────────────────────────────────────────

    private fun calc(ctx: Context, value: String, onScreen: Boolean): Res {
        return when (val r = Calc.fromSpeech(VoiceParser.cleanCommand(value))) {
            is Calc.Result -> if (onScreen || r.onScreen) calcOnScreen(ctx, r) else Res(true, r.spoken + ".", label = "Calculator")
            is Calc.Failure -> Res(false, r.msg)
            else -> Res(false, "I couldn't work that out. Try \"calculate 25 times 4\" or \"15 percent of 200\".")
        }
    }

    private val CALC_PACKAGES = listOf(
        "com.google.android.calculator", "com.android.calculator2", "com.sec.android.app.popupcalculator", "com.miui.calculator",
        "com.oneplus.calculator", "com.coloros.calculator", "com.vivo.calculator", "com.realme.calculator", "com.motorola.calculator",
    )

    private fun keyNames(ch: Char): List<String> = when (ch) {
        '+' -> listOf("plus", "add", "+")
        '-' -> listOf("minus", "subtract", "-", "\u2212")
        '*' -> listOf("multiply", "multiplication", "times", "\u00d7", "x", "*")
        '/' -> listOf("divide", "division", "\u00f7", "/")
        '.' -> listOf("point", "decimal point", "decimal", "dot", ".")
        '%' -> listOf("percent", "percentage", "%")
        else -> listOf(ch.toString())
    }

    /** Opens the phone's calculator and presses the keys; the answer is spoken from our own calculation either way. */
    private fun calcOnScreen(ctx: Context, r: Calc.Result): Res {
        val said = r.spoken + "."
        val keys = r.keys ?: return Res(true, "$said I worked it out myself, because the calculator app has no button for that kind of maths.", label = "Calculator")
        val svc = PragonAccessibilityService.instance
            ?: return Res(true, "$said (Turn on the accessibility service and I can also type it into the calculator for you.)", label = "Calculator")
        val pm = ctx.packageManager
        val pkg = resolveApp(ctx, "calculator")?.second ?: CALC_PACKAGES.firstOrNull { pm.getLaunchIntentForPackage(it) != null }
            ?: return Res(true, "$said I couldn't find a calculator app on this phone to type it into.", label = "Calculator")
        val intent = pm.getLaunchIntentForPackage(pkg) ?: return Res(true, said, label = "Calculator")
        val opened = go(ctx, intent, "", verifyPkg = pkg, label = "Calculator")
        if (!opened.ok) return Res(true, "$said ${opened.msg}", label = "Calculator")
        try { Thread.sleep(700) } catch (e: InterruptedException) { }
        // start from a clean screen
        for (name in listOf("clear", "all clear", "ac")) if (svc.hasText(name)) { svc.clickText(name); break }
        for (ch in keys) {
            var pressed = false
            for (name in keyNames(ch)) {
                if (svc.hasText(name) && svc.clickText(name)) { pressed = true; break }
            }
            if (!pressed) return Res(true, "$said I opened the calculator but couldn't press '$ch' on it.", label = "Calculator")
            try { Thread.sleep(90) } catch (e: InterruptedException) { }
        }
        for (name in listOf("equals", "=", "equal")) if (svc.hasText(name)) { svc.clickText(name); break }
        return Res(true, "$said It's typed into the calculator too.", label = "Calculator")
    }

    // ── phone settings screens ───────────────────────────────────────────

    private fun settings(ctx: Context, value: String): Res {
        val parts = value.lowercase().trim().split(":")
        val key = parts[0].replace(Regex("\\s+"), "")
        val toggle = parts.getOrNull(1).orEmpty()            // "on" / "off" when the user asked to flip it
        data class S(val intent: Intent, val label: String, val canToggle: Boolean = false)
        val s: S = when (key) {
            "pragon" -> S(Intent(ctx, SettingsActivity::class.java), "Pragon's settings")
            "wifi", "wi-fi" -> S(Intent(Settings.ACTION_WIFI_SETTINGS), "Wi-Fi settings", true)
            "bluetooth" -> S(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), "Bluetooth settings", true)
            "display", "brightness", "wallpaper" ->
                if (key == "wallpaper") S(Intent(Intent.ACTION_SET_WALLPAPER), "wallpaper chooser") else S(Intent(Settings.ACTION_DISPLAY_SETTINGS), "display settings")
            "sound", "sounds", "volume" -> S(Intent(Settings.ACTION_SOUND_SETTINGS), "sound settings")
            "battery" -> S(Intent(Intent.ACTION_POWER_USAGE_SUMMARY), "battery settings")
            "location", "gps" -> S(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), "location settings", true)
            "apps" -> S(Intent(Settings.ACTION_APPLICATION_SETTINGS), "app settings")
            "accessibility" -> S(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), "accessibility settings")
            "notification", "notifications" -> S(Intent("android.settings.ALL_APPS_NOTIFICATION_SETTINGS"), "notification settings")
            "developer", "developeroptions" -> S(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), "developer options")
            "airplanemode", "airplane", "flightmode" -> S(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS), "airplane mode settings", true)
            "mobiledata", "data", "network", "hotspot" -> S(Intent(Settings.ACTION_WIRELESS_SETTINGS), "network settings", true)
            "storage" -> S(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS), "storage settings")
            "date", "dateandtime", "time" -> S(Intent(Settings.ACTION_DATE_SETTINGS), "date and time settings")
            "language", "languages" -> S(Intent(Settings.ACTION_LOCALE_SETTINGS), "language settings")
            "nfc" -> S(Intent(Settings.ACTION_NFC_SETTINGS), "NFC settings", true)
            "security" -> S(Intent(Settings.ACTION_SECURITY_SETTINGS), "security settings")
            "privacy" -> S(Intent(Settings.ACTION_PRIVACY_SETTINGS), "privacy settings")
            "vpn" -> S(Intent(Settings.ACTION_VPN_SETTINGS), "VPN settings")
            "donotdisturb", "dnd" -> S(Intent("android.settings.ZEN_MODE_SETTINGS"), "Do Not Disturb settings", true)
            "keyboard" -> S(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS), "keyboard settings")
            "aboutphone", "about" -> S(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS), "About phone")
            else -> S(Intent(Settings.ACTION_SETTINGS), "settings")
        }
        var r = go(ctx, s.intent, "Opened the ${s.label}.", label = s.label)
        if (!r.ok && key != "pragon" && key != "main") {
            r = go(ctx, Intent(Settings.ACTION_SETTINGS), "This phone has no separate ${s.label} screen, so I opened Settings.", label = "Settings")
        }
        if (r.ok && s.canToggle && toggle.isNotEmpty()) {
            return Res(true, r.msg + " Android doesn't let apps switch it $toggle by themselves, so tap the switch.", label = r.label)
        }
        return r
    }

    // ── typer mode (voice typing) ────────────────────────────────────────

    private fun typer(ctx: Context, v: String): Res {
        if (v == "off") {
            return if (Typer.active) { Typer.stop(); Res(true, "Typer mode off.", label = "Typer") }
            else Res(true, "Typer mode was already off.", label = "Typer")
        }
        if (PragonAccessibilityService.instance == null) return needAccessibility()
        // Typer listens through the float bubble (it keeps the microphone open while you're in another app).
        if (!FloatService.running) {
            FloatMode.problem(ctx)?.let { return Res(false, "Typer mode needs float mode: $it") }
            FloatMode.autoStart(ctx)
        }
        Typer.start()
        return Res(
            true,
            "Typer mode on. Tap a text box, then just talk. Say \"comma\", \"question mark\" or \"new line\" for punctuation, " +
                "\"delete that\" to undo, \"send\" to press enter, and \"stop typing\" when you're done.",
            label = "Typer",
        )
    }

    // ── volume / brightness / flashlight strength ────────────────────────

    private class Adj(val kind: Char, val n: Int)   // + up, - down, = set to n%, M max, m min; n = 0 means "one normal step"

    private fun adj(v: String): Adj {
        val t = v.trim().lowercase()
        return when {
            t == "max" -> Adj('M', 100)
            t == "min" -> Adj('m', 0)
            t.startsWith("set:") -> Adj('=', t.removePrefix("set:").toIntOrNull()?.coerceIn(0, 100) ?: 50)
            t.startsWith("+") -> Adj('+', t.drop(1).toIntOrNull()?.coerceIn(0, 100) ?: 0)
            t.startsWith("-") -> Adj('-', t.drop(1).toIntOrNull()?.coerceIn(0, 100) ?: 0)
            else -> Adj('=', t.toIntOrNull()?.coerceIn(0, 100) ?: 50)
        }
    }

    /** Works out the new level (0..max) from an adjustment. [step] is the default size of one step in percent. */
    private fun newLevel(a: Adj, cur: Int, max: Int, step: Int, floor: Int): Int {
        val d = Math.max(1, Math.round(max * (if (a.n > 0) a.n else step) / 100f))
        return when (a.kind) {
            'M' -> max
            'm' -> floor
            '+' -> cur + d
            '-' -> cur - d
            else -> Math.round(max * a.n / 100f)
        }.coerceIn(floor, max)
    }

    private fun pct(level: Int, max: Int) = if (max <= 0) 0 else Math.round(100f * level / max)

    private fun volume(ctx: Context, v: String): Res {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = if (am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
        val max = am.getStreamMaxVolume(stream)
        val cur = am.getStreamVolume(stream)
        val a = adj(v)
        val next = newLevel(a, cur, max, 10, if (a.kind == 'm') 1 else 0)
        if (next == cur) {
            return Res(true, when {
                a.kind == '+' || a.kind == 'M' -> "Volume is already at maximum."
                a.kind == '-' || a.kind == 'm' -> "Volume is already as low as it goes."
                else -> "Volume is already ${pct(cur, max)} percent."
            }, label = "Volume")
        }
        am.setStreamVolume(stream, next, AudioManager.FLAG_SHOW_UI)
        return Res(true, "Volume ${pct(next, max)} percent.", label = "Volume")
    }

    private fun brightness(ctx: Context, v: String): Res {
        val t = v.trim().lowercase()
        if (!Settings.System.canWrite(ctx)) {
            go(ctx, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + ctx.packageName)), "", label = "Settings", guard = false)
            return Res(false, "I need permission to change the brightness. I opened the screen: switch on \"Allow modifying system settings\" for Pragon, then ask again. " +
                "(Or run once on your PC: adb shell appops set com.pragon.mobile WRITE_SETTINGS allow)")
        }
        val cr = ctx.contentResolver
        if (t == "auto" || t == "auto:on" || t == "auto:off") {
            val on = t != "auto:off"
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                if (on) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            return Res(true, "Automatic brightness " + (if (on) "on." else "off."), label = "Brightness")
        }
        val cur = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128).coerceIn(0, 255)
        val a = adj(v)
        // never go fully black: 8 of 255 is the dimmest usable level
        val next = newLevel(a, cur, 255, 15, 8)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        if (next == cur) {
            return Res(true, if (a.kind == '+' || a.kind == 'M') "Brightness is already at maximum." else "Brightness is already at its lowest.", label = "Brightness")
        }
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, next)
        return Res(true, "Brightness ${pct(next, 255)} percent.", label = "Brightness")
    }

    private fun flashlightLevel(ctx: Context, v: String): Res {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            val ch = cm.getCameraCharacteristics(it)
            ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true && ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: return Res(false, "This phone has no flashlight I can use.")
        val max = if (Build.VERSION.SDK_INT >= 33) cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1 else 1
        if (Build.VERSION.SDK_INT < 33 || max <= 1) {
            cm.setTorchMode(id, true); torchOn = true
            return Res(true, "This phone's flashlight has only one brightness (Android 13 and a flash with several levels are needed). It's on.", label = "Flashlight")
        }
        val cur = if (torchOn) cm.getTorchStrengthLevel(id) else 0
        val a = adj(v)
        val next = newLevel(a, cur, max, 100 / max, 1)
        if (torchOn && next == cur) return Res(true, if (a.kind == '+' || a.kind == 'M') "The flashlight is already at full brightness." else "The flashlight is already at its dimmest.", label = "Flashlight")
        cm.turnOnTorchWithStrengthLevel(id, next)
        torchOn = true
        return Res(true, "Flashlight brightness $next of $max.", label = "Flashlight")
    }

    // ── security switches by voice (they can be turned ON by voice; loosening them is done in Settings) ──

    private fun privacyCmd(ctx: Context, v: String): Res {
        val call = Prefs.callMe(ctx)
        return when (v) {
            "on" -> { Guard.setPrivacy(ctx, true); Res(true, "Privacy Shield on, $call. Pragon will only talk to your own devices.", label = "Privacy") }
            "off" -> Res(false, "For your safety I won't turn Privacy Shield off by voice, $call. Do it in Settings > Security.", label = "Privacy")
            "log" -> {
                val l = Guard.connectionLog()
                Res(true, if (l.isEmpty()) "Pragon hasn't made any connections since it started, $call."
                else "Since Pragon started: " + l.take(5).joinToString("; ") { (if (it.allowed) "talked to " else "BLOCKED ") + it.host + " x" + it.count } + ".", label = "Privacy")
            }
            else -> Res(true, if (Guard.privacyOn(ctx)) "Privacy Shield is on. Pragon only talks to your own network." else "Privacy Shield is OFF, $call.", label = "Privacy")
        }
    }

    private fun allowlistCmd(ctx: Context, v: String): Res {
        val call = Prefs.callMe(ctx)
        return when (v) {
            "on" -> {
                if (Guard.allowedApps(ctx).isEmpty()) Res(false, "No apps are allowed yet, $call, so I'd open nothing. Choose them in Settings > Allowed apps first.", label = "Allowed apps")
                else { Guard.setAllowlist(ctx, true); Res(true, "Only your ${Guard.allowedApps(ctx).size} allowed apps can be opened now.", label = "Allowed apps") }
            }
            "off" -> Res(false, "For your safety I won't turn the allowed-apps limit off by voice, $call. Do it in Settings > Allowed apps.", label = "Allowed apps")
            else -> Res(true, if (Guard.allowlistOn(ctx)) "Only ${Guard.allowedApps(ctx).size} allowed apps can be opened." else "Any app can be opened (the allowed-apps limit is off).", label = "Allowed apps")
        }
    }

    private fun sidebarCmd(ctx: Context, v: String): Res {
        val call = Prefs.callMe(ctx)
        return when (v) {
            "off" -> { SidebarService.setAutoStart(ctx, false); SidebarService.stop(ctx); Res(true, "Sidebar off.", label = "Sidebar") }
            "close" -> { SidebarService.close(); Res(true, "Sidebar closed.", label = "Sidebar") }
            else -> {
                if (!SidebarService.running) SidebarService.start(ctx)?.let { return Res(false, it) }
                SidebarService.setAutoStart(ctx, true)
                if (v == "open") {
                    var tries = 0
                    while (SidebarService.instance == null && tries++ < 12) sleepMs(100)
                    SidebarService.open()
                    Res(true, "Sidebar open.", label = "Sidebar")
                } else Res(true, "Sidebar on, $call. Tap the thin bar on the screen edge, or say open sidebar.", label = "Sidebar")
            }
        }
    }
}
