package com.pragon.mobile

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The "hands" of the app: global buttons (Home/Back/Recents...), taps, swipes
 * and typing. Android only allows this through an Accessibility Service that the
 * user switches on once in Settings.
 */
class PragonAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: PragonAccessibilityService? = null
    }

    override fun onServiceConnected() {
        instance = this
        Bridge.set(Bridge.status) // refresh UI
    }

    private var homePkg: String? = null

    private fun isLauncherOrSystem(pkg: String): Boolean {
        if (pkg == "com.android.systemui" || pkg == "android") return true
        if (homePkg == null) {
            homePkg = try {
                packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName ?: ""
            } catch (e: Exception) { "" }
        }
        return pkg == homePkg
    }

    /** Only does work while a macro is being recorded; otherwise returns immediately. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !Macros.isRecording) return
        val pkg = event.packageName?.toString() ?: return
        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                    if (pkg != "com.android.systemui") Macros.onWindowChange(this, pkg, isLauncherOrSystem(pkg))
                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    val src = event.source
                    if (src != null && src.isPassword) return
                    val label = labelOf(src) ?: event.text?.joinToString(" ")?.trim().orEmpty().ifEmpty { event.contentDescription?.toString() ?: "" }
                    Macros.onUserClick(this, label, pkg)
                }
                AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                    val dy = if (Build.VERSION.SDK_INT >= 28) event.scrollDeltaY else 0
                    if (dy != 0) Macros.onScroll(this, dy)
                }
            }
        } catch (e: Exception) { }
    }

    /** What a tapped thing is called: its text, its description, a child's text, or its id. */
    private fun labelOf(n: AccessibilityNodeInfo?): String? {
        if (n == null) return null
        n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        for (i in 0 until minOf(n.childCount, 6)) {
            val c = n.getChild(i) ?: continue
            c.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            c.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return n.viewIdResourceName?.substringAfter(":id/")?.replace('_', ' ')?.takeIf { it.isNotBlank() }
    }

    /** True when a text box has the cursor (used by the sidebar to offer voice typing). */
    fun hasFocusedTextBox(): Boolean = try { rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.isEditable == true } catch (e: Exception) { false }
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** Performs a single-stroke gesture and waits for it to finish. */
    fun gesture(path: Path, durationMs: Long): Boolean {
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        val done = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            val started = dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    done.set(true); latch.countDown()
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    latch.countDown()
                }
            }, null)
            if (!started) latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
        return done.get()
    }

    /** Types into the currently focused text box (appends to what's there). */
    fun typeText(text: String): Boolean {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val existing = if (node.isShowingHintText) "" else (node.text?.toString() ?: "")
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, existing + text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    // ── voice typing (Typer mode) ────────────────────────────────────────

    /**
     * Rewrites the text of the focused text box. [change] receives what is in the box now and returns what it
     * should say. The cursor is put at the end. Returns false when no text box is focused.
     */
    fun editField(change: (String) -> String): Boolean {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val existing = if (node.isShowingHintText) "" else (node.text?.toString() ?: "")
        val next = change(existing)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, next)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok) {
            val sel = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, next.length)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, next.length)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        }
        return ok
    }

    /** Adds dictated words to the focused text box with correct spacing and capitals. */
    fun dictate(piece: String): Boolean {
        var added = ""
        val ok = editField { old ->
            val joined = Typer.join(old, piece)
            added = joined.substring(old.length)
            joined
        }
        if (ok) Typer.lastAdded = added
        return ok
    }

    /** "delete that": removes what the last dictation added (or the last word if that is unknown). */
    fun deleteLastDictation(): Boolean {
        val last = Typer.lastAdded
        val ok = editField { old ->
            if (last.isNotEmpty() && old.endsWith(last)) old.removeSuffix(last)
            else dropLastWord(old)
        }
        Typer.lastAdded = ""
        return ok
    }

    fun deleteLastWord(): Boolean = editField { dropLastWord(it) }.also { Typer.lastAdded = "" }
    fun backspace(): Boolean = editField { if (it.isEmpty()) it else it.dropLast(1) }.also { Typer.lastAdded = "" }
    fun clearField(): Boolean = editField { "" }.also { Typer.lastAdded = "" }

    private fun dropLastWord(t0: String): String {
        val t = t0.trimEnd()
        val i = maxOf(t.lastIndexOf(' '), t.lastIndexOf('\n'))
        return if (i < 0) "" else t.substring(0, i + 1).trimEnd(' ')
    }

    fun selectAll(): Boolean {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val len = node.text?.length ?: 0
        val sel = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, len)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
    }

    /** "send" / "enter": the keyboard's action key (Android 11+), else a Send/Search/Go/Done button on screen. */
    fun pressEnter(): Boolean {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (node != null && Build.VERSION.SDK_INT >= 30) {
            if (node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) return true
        }
        for (label in listOf("send", "search", "go", "done", "submit", "post")) if (clickText(label)) return true
        return false
    }

    // ── click by voice ───────────────────────────────────────────────────

    private fun squash(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun allNodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || out.size >= 3000 || depth > 60) return
            out.add(n)
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return out
    }

    /** The on-screen element that best matches [target]: its text, description, hint or id. */
    private fun findBest(target: String): AccessibilityNodeInfo? {
        val q = squash(target)
        if (q.isEmpty()) return null
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0
        for (n in allNodes(rootInActiveWindow)) {
            if (!n.isVisibleToUser) continue
            val labels = listOfNotNull(
                n.text?.toString(),
                n.contentDescription?.toString(),
                n.hintText?.toString(),
                n.viewIdResourceName?.substringAfter(":id/")?.replace('_', ' '),
            )
            var base = 0
            for (l in labels) {
                val nl = squash(l)
                if (nl.isEmpty()) continue
                val sc = when {
                    nl == q -> 3
                    nl.startsWith(q) && q.length >= 2 -> 2
                    q.length >= 3 && nl.contains(q) -> 1
                    else -> 0
                }
                if (sc > base) base = sc
            }
            if (base == 0) continue
            val score = base * 4 + (if (n.isClickable) 2 else 0) + (if (n.isEnabled) 1 else 0)
            if (score > bestScore) { bestScore = score; best = n }
        }
        return best
    }

    /** Taps the element named [target]. False if nothing on screen matches or the tap was refused. */
    fun clickText(target: String): Boolean {
        val n = findBest(target) ?: return false
        if (clickNode(n)) return true
        val r = Rect()
        n.getBoundsInScreen(r)
        if (r.isEmpty) return false
        return gesture(Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }, 60)
    }

    /** True when something on screen is called [target]. */
    fun hasText(target: String): Boolean = findBest(target) != null

    /** Taps the middle of the screen (what "click" with no target does when nothing is focused). */
    fun tapCenter(): Boolean {
        val m = resources.displayMetrics
        return gesture(Path().apply { moveTo(m.widthPixels / 2f, m.heightPixels / 2f) }, 60)
    }

    /** "click": presses the focused element if there is one, otherwise taps the middle of the screen. */
    fun clickFocusedOrCenter(): String {
        val root = rootInActiveWindow
        val f = root?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) ?: root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (f != null && clickNode(f)) return "focused"
        return if (tapCenter()) "center" else ""
    }

    // ── close-app support ────────────────────────────────────────────────

    enum class StopResult { DONE, NOT_RUNNING, NO_BUTTON, NO_CONFIRM }

    /** Package of the app currently on screen (the Pragon bubble is not focusable, so it never counts). */
    fun foregroundPackage(): String? = rootInActiveWindow?.packageName?.toString()

    private fun matches(n: AccessibilityNodeInfo, ids: List<String>, texts: List<String>): Boolean {
        val id = n.viewIdResourceName
        if (id != null && ids.contains(id)) return true
        val t = (n.text?.toString() ?: n.contentDescription?.toString() ?: "").trim().lowercase()
        return t.isNotEmpty() && texts.any { t == it }
    }

    private fun find(root: AccessibilityNodeInfo?, ids: List<String>, texts: List<String>): AccessibilityNodeInfo? {
        if (root == null) return null
        if (matches(root, ids, texts)) return root
        for (i in 0 until root.childCount) {
            val c = root.getChild(i) ?: continue
            find(c, ids, texts)?.let { return it }
        }
        return null
    }

    private fun clickNode(n: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = n
        var hops = 0
        while (cur != null && hops < 5) {
            if (cur.isClickable && cur.isEnabled) return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            cur = cur.parent
            hops++
        }
        return false
    }

    private fun waitFor(ms: Long, ids: List<String>, texts: List<String>): AccessibilityNodeInfo? {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            find(rootInActiveWindow, ids, texts)?.let { return it }
            try { Thread.sleep(150) } catch (e: InterruptedException) { return null }
        }
        return null
    }

    /**
     * Assumes the system "App info" screen for the target app is opening. Presses Force stop,
     * confirms the dialog, then goes Back. Blocking: call from a background thread.
     */
    private val FORCE_WORDS = listOf(
        "force stop", "force close", "force-stop", "forcer l'arrêt", "forcer l’arrêt", "forzar detención", "forzar parada", "forzar cierre",
        "beenden erzwingen", "stopp erzwingen", "forçar parada", "forçar paragem", "forza arresto", "принудительно остановить",
        "强行停止", "强制停止", "強制停止", "강제 종료", "강제 중지", "जबरदस्ती रोकें", "ज़बरदस्ती रोकें", "ज़बरन रोकें", "जबरन रोकें",
        "வலுக்கட்டாயமாக நிறுத்து", "கட்டாயமாக நிறுத்து", "force dừng", "zorla durdur", "paksa berhenti", "إيقاف إجباري", "فرض الإيقاف",
    )
    private val ID_FORCE = listOf(
        "com.android.settings:id/force_stop_button", "com.android.settings:id/right_button", "com.android.settings:id/button_force_stop",
        "com.android.settings:id/force_stop", "com.miui.securitycenter:id/force_stop", "com.samsung.android.settings:id/force_stop_button",
    )

    /** Any enabled-or-disabled button whose label is a "force stop" in any language we know. */
    private fun findForceStop(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        for (n in allNodes(root)) {
            val id = n.viewIdResourceName
            if (id != null && ID_FORCE.contains(id)) return n
            val t = (n.text?.toString() ?: n.contentDescription?.toString() ?: "").trim().lowercase()
            if (t.isNotEmpty() && FORCE_WORDS.any { t == it || (it.length > 6 && t.startsWith(it)) }) return n
        }
        return null
    }

    /**
     * Assumes the system "App info" screen for the target app is opening. Presses Force stop, confirms the dialog,
     * then goes HOME (not Back: Back would land in the app that was just killed). Blocking: use a background thread.
     */
    fun forceStopCurrentAppInfo(): StopResult {
        var btn: AccessibilityNodeInfo? = null
        val end = System.currentTimeMillis() + 6000
        while (System.currentTimeMillis() < end) {
            btn = findForceStop(rootInActiveWindow)
            if (btn != null) break
            try { Thread.sleep(200) } catch (e: InterruptedException) { break }
        }
        if (btn == null) { performGlobalAction(GLOBAL_ACTION_HOME); return StopResult.NO_BUTTON }
        // Greyed out = the app isn't running.
        if (!btn.isEnabled) { performGlobalAction(GLOBAL_ACTION_HOME); return StopResult.NOT_RUNNING }
        if (!clickNode(btn)) { performGlobalAction(GLOBAL_ACTION_HOME); return StopResult.NO_BUTTON }
        // The confirmation dialog's positive button is android:id/button1 in every language.
        var ok: AccessibilityNodeInfo? = null
        val end2 = System.currentTimeMillis() + 3500
        while (System.currentTimeMillis() < end2) {
            ok = allNodes(rootInActiveWindow).firstOrNull { it.viewIdResourceName == "android:id/button1" }
                ?: findForceStop(rootInActiveWindow)?.takeIf { it != btn && it.isEnabled && it.isClickable && it.viewIdResourceName?.startsWith("android:id") == true }
            if (ok != null) break
            try { Thread.sleep(150) } catch (e: InterruptedException) { break }
        }
        if (ok == null) { performGlobalAction(GLOBAL_ACTION_HOME); return StopResult.NO_CONFIRM }
        clickNode(ok)
        try { Thread.sleep(450) } catch (e: InterruptedException) { }
        performGlobalAction(GLOBAL_ACTION_HOME)
        return StopResult.DONE
    }
}
