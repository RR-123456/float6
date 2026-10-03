package com.pragon.mobile

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Switching the phone's own accessibility helpers on and off by voice: TalkBack (screen reader), Select to Speak
 * (text to speech), Live Caption. They only work after YOU allow them: Settings > Security > "Let Pragon switch
 * accessibility features", plus a one-time permission Android only lets you give over a cable:
 *     adb shell pm grant com.pragon.mobile android.permission.WRITE_SECURE_SETTINGS
 * Without that grant Pragon opens the right Android screen instead and tells you what to tap.
 */
object AccessFeatures {

    private class Svc(val pkg: String, val cls: String)

    private val TALKBACK = listOf(
        Svc("com.google.android.marvin.talkback", "com.google.android.marvin.talkback.TalkBackService"),
        Svc("com.samsung.android.accessibility.talkback", "com.samsung.android.marvin.talkback.TalkBackService"),
    )
    private val SELECT_TO_SPEAK = listOf(
        Svc("com.google.android.accessibility.selecttospeak", "com.google.android.accessibility.selecttospeak.SelectToSpeakService"),
    )

    const val GRANT_CMD = "adb shell pm grant com.pragon.mobile android.permission.WRITE_SECURE_SETTINGS"

    fun canWriteSecure(c: Context) =
        c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    private fun installed(c: Context, s: Svc): Boolean = try { c.packageManager.getPackageInfo(s.pkg, 0); true } catch (e: Exception) { false }

    private fun enabledList(c: Context): MutableList<String> =
        (Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
            .split(":").filter { it.isNotBlank() }.toMutableList()

    private fun setService(c: Context, s: Svc, on: Boolean): Boolean {
        val comp = ComponentName(s.pkg, s.cls).flattenToString()
        val short = ComponentName(s.pkg, s.cls).flattenToShortString()
        val list = enabledList(c).filter { it != comp && it != short }.toMutableList()
        if (on) list.add(short)
        return try {
            Settings.Secure.putString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list.joinToString(":"))
            Settings.Secure.putInt(c.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, if (list.isEmpty()) 0 else 1)
            true
        } catch (e: SecurityException) { false }
    }

    private fun open(c: Context, i: Intent): Boolean = try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (e: Exception) { false }

    private fun needAllow(c: Context): CommandExecutor.Res? =
        if (Guard.a11yFeaturesOn(c)) null
        else CommandExecutor.Res(
            false,
            "I'm not allowed to switch accessibility features. Turn on \"Let Pragon switch accessibility features\" in Settings > Security first.",
            label = "Accessibility",
        )

    /** feature = talkback | tts | caption ; on = true/false */
    fun set(c: Context, feature: String, on: Boolean): CommandExecutor.Res {
        needAllow(c)?.let { return it }
        val call = Prefs.callMe(c)
        val word = if (on) "on" else "off"
        return when (feature) {
            "talkback" -> {
                val s = TALKBACK.firstOrNull { installed(c, it) }
                    ?: return CommandExecutor.Res(false, "I can't find TalkBack on this phone. Install Android Accessibility Suite from your app store.", label = "TalkBack")
                if (canWriteSecure(c) && setService(c, s, on)) CommandExecutor.Res(true, "TalkBack $word, $call.", label = "TalkBack")
                else { open(c, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); CommandExecutor.Res(true, manual("TalkBack", on), label = "TalkBack") }
            }
            "tts" -> {
                val s = SELECT_TO_SPEAK.firstOrNull { installed(c, it) }
                if (s != null && canWriteSecure(c) && setService(c, s, on)) CommandExecutor.Res(true, "Select to Speak $word, $call. Tap its icon, then a part of the screen, to hear it read.", label = "Text to speech")
                else {
                    open(c, Intent("com.android.settings.TTS_SETTINGS"))
                    CommandExecutor.Res(true, "I opened the text to speech settings, $call. For reading the screen aloud you can also just say read the screen.", label = "Text to speech")
                }
            }
            "caption" -> {
                if (canWriteSecure(c)) {
                    try {
                        Settings.Secure.putInt(c.contentResolver, "odi_captions_enabled", if (on) 1 else 0)
                        if (Settings.Secure.getInt(c.contentResolver, "odi_captions_enabled", -1) == (if (on) 1 else 0))
                            return CommandExecutor.Res(true, "Live Caption $word, $call.", label = "Live Caption")
                    } catch (e: Exception) { }
                }
                open(c, Intent(Settings.ACTION_SOUND_SETTINGS))
                CommandExecutor.Res(true, "I opened Sound settings, $call. Tap Live Caption to turn it $word. (This phone doesn't let me flip it directly.)", label = "Live Caption")
            }
            else -> CommandExecutor.Res(false, "I don't know the accessibility feature '$feature'.")
        }
    }

    private fun manual(name: String, on: Boolean) =
        "I opened Accessibility settings. Tap $name and switch it ${if (on) "on" else "off"}. " +
            "(To let me do this by voice, run once on your PC: $GRANT_CMD)"

    // ── screen reading (the screen's text, for \"read the screen\" and questions about it) ────────────────

    object Screen {
        /**
         * All the visible text on the screen in reading order, without anything from password boxes. Null if the
         * accessibility service is off. Pragon's own windows are left out.
         */
        fun dump(svc: PragonAccessibilityService, ownPkg: String, maxChars: Int = 6000): String? {
            val roots = ArrayList<AccessibilityNodeInfo>()
            try {
                svc.windows?.filter { it.isActive || it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
                    ?.forEach { w -> w.root?.let { roots.add(it) } }
            } catch (e: Exception) { }
            if (roots.isEmpty()) svc.rootInActiveWindow?.let { roots.add(it) }
            if (roots.isEmpty()) return null

            class Item(val top: Int, val left: Int, val text: String)
            val items = ArrayList<Item>()
            fun walk(n: AccessibilityNodeInfo?, depth: Int) {
                if (n == null || depth > 60 || items.size > 1500) return
                if (n.packageName?.toString() == ownPkg) return
                if (n.isVisibleToUser && !n.isPassword) {
                    val t = (n.text?.toString() ?: "").trim().ifEmpty { n.contentDescription?.toString()?.trim() ?: "" }
                    if (t.isNotEmpty()) {
                        val r = Rect(); n.getBoundsInScreen(r)
                        items.add(Item(r.top, r.left, t))
                    }
                }
                for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
            }
            roots.forEach { walk(it, 0) }
            val seen = HashSet<String>()
            val sb = StringBuilder()
            for (it in items.sortedWith(compareBy({ x -> x.top / 24 }, { x -> x.left }))) {
                if (!seen.add(it.text)) continue
                if (sb.length + it.text.length > maxChars) break
                sb.append(it.text).append('\n')
            }
            return sb.toString().trim()
        }
    }

    @Suppress("unused") private fun uriOf(p: String) = Uri.parse("package:$p")
}
