package com.pragon.mobile

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Pragon's voice. One shared instance for the chat screen and the float bubble.
 *  - speaks sentence by sentence as the model writes them (first words come out almost immediately)
 *  - strips markdown/links/emoji so nothing odd is read out
 *  - picks the best installed voice, follows the reply's script (Tamil, Hindi, ...) and the user's rate/pitch
 *  - ducks music while talking and tells float mode when it is done, so the mic never hears Pragon itself
 */
class Speaker private constructor(private val app: Context) {

    companion object {
        @Volatile private var inst: Speaker? = null
        fun get(ctx: Context): Speaker =
            inst ?: synchronized(this) { inst ?: Speaker(ctx.applicationContext).also { inst = it } }
    }

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private val backlog = mutableListOf<Pair<String, Boolean>>()   // spoken before the engine finished starting

    private var gen = 0
    private var counter = 0
    private var pending = 0
    private var currentTag = ""
    private var focus: AudioFocusRequest? = null

    /** Called on the main thread when everything queued has been spoken (or speech was stopped). */
    @Volatile var onIdle: (() -> Unit)? = null

    val speaking: Boolean get() = pending > 0

    init {
        main.post { start() }
    }

    private fun start() {
        tts = TextToSpeech(app) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setOnUtteranceProgressListener(progress)
                ready = true
                applyBase()
                val b = synchronized(backlog) { backlog.toList().also { backlog.clear() } }
                for ((t, add) in b) main.post { say(t, add) }
            }
        }
    }

    // ── configuration ─────────────────────────────────────────────────────

    private fun baseLocale(): Locale {
        val tag = Prefs.speechLang(app)
        return if (tag.isNotBlank()) Locale.forLanguageTag(tag) else Locale.getDefault()
    }

    private fun usable(l: Locale): Boolean {
        val r = tts?.isLanguageAvailable(l) ?: return false
        return r >= TextToSpeech.LANG_AVAILABLE
    }

    private fun bestVoice(l: Locale): Voice? {
        val all = try { tts?.voices } catch (e: Exception) { null } ?: return null
        return all.filter { it.locale.language == l.language && !it.features.contains("notInstalled") }
            .sortedWith(compareByDescending<Voice> { if (it.locale.country == l.country) 1 else 0 }
                .thenByDescending { it.quality }
                .thenBy { it.isNetworkConnectionRequired })
            .firstOrNull()
    }

    /** Sets language, voice, rate and pitch. [tag] = language of the text about to be spoken, "" = user's language. */
    private fun apply(tag: String) {
        val t = tts ?: return
        val loc = if (tag.isNotBlank()) Locale.forLanguageTag(tag) else baseLocale()
        val use = if (usable(loc)) loc else if (usable(Locale.getDefault())) Locale.getDefault() else Locale.US
        try {
            t.language = use
            val wanted = Prefs.ttsVoice(app)
            val chosen = t.voices?.firstOrNull { it.name == wanted && it.locale.language == use.language } ?: bestVoice(use)
            if (chosen != null) t.voice = chosen
            t.setSpeechRate(Prefs.ttsRate(app))
            t.setPitch(Prefs.ttsPitch(app))
        } catch (e: Exception) { }
        currentTag = tag
    }

    private fun applyBase() = apply("")

    /** Voices for the Settings screen: [{name, label, quality}] for the chosen speaking language. */
    fun voicesJson(): String {
        val arr = JSONArray()
        val l = baseLocale()
        try {
            tts?.voices?.filter { it.locale.language == l.language && !it.features.contains("notInstalled") }
                ?.sortedByDescending { it.quality }?.forEach {
                    arr.put(JSONObject().put("name", it.name)
                        .put("label", "${it.locale.displayName}${if (it.isNetworkConnectionRequired) " (online)" else ""} - ${it.name}")
                        .put("quality", it.quality))
                }
        } catch (e: Exception) { }
        return JSONObject().put("ready", ready).put("voices", arr).toString()
    }

    // ── speaking ──────────────────────────────────────────────────────────

    /** Speaks [text] (several sentences are fine). Replaces anything being said unless [add]. Returns false when muted. */
    fun speak(text: String, add: Boolean = false): Boolean {
        if (Prefs.muted(app)) return false
        main.post { say(text, add) }
        return true
    }

    /** One sentence from the model's stream: appended to the queue. */
    fun enqueue(sentence: String) { speak(sentence, add = true) }

    private fun say(text0: String, add: Boolean) {
        val t = tts
        if (!ready || t == null) { synchronized(backlog) { backlog.add(text0 to add) }; return }
        val clean = SpeechText.clean(text0)
        if (clean.isEmpty()) { if (pending == 0) idle(); return }
        if (!add) halt(silent = true)
        if (pending == 0) {
            // A new reply starts: pick the language from the script of its first sentence.
            val tag = LangDetect.tagFor(clean) ?: ""
            apply(tag)
            requestFocus()
        }
        // Long text goes out sentence by sentence so it can be interrupted quickly.
        val parts = mutableListOf<String>()
        SentenceSplitter { parts.add(it) }.also { it.feed(clean + " "); it.flush() }
        for (p in parts.ifEmpty { listOf(clean) }) {
            pending++
            val id = "p$gen:${counter++}"
            val r = t.speak(p.take(900), TextToSpeech.QUEUE_ADD, null, id)
            if (r != TextToSpeech.SUCCESS) pending--
        }
        if (pending <= 0) idle()
    }

    /** Stops speaking at once. */
    fun stop() { main.post { halt(silent = false) } }

    private fun halt(silent: Boolean) {
        gen++                       // callbacks from the old utterances are now ignored
        val was = pending > 0
        pending = 0
        try { tts?.stop() } catch (e: Exception) { }
        if (!silent) { abandonFocus(); if (was) onIdle?.invoke() }
    }

    private fun idle() {
        abandonFocus()
        onIdle?.invoke()
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(id: String?) {}
        override fun onDone(id: String?) { finished(id) }
        @Deprecated("Deprecated in Java")
        override fun onError(id: String?) { finished(id) }
        override fun onError(id: String?, errorCode: Int) { finished(id) }
        override fun onStop(id: String?, interrupted: Boolean) { finished(id) }
    }

    private fun finished(id: String?) {
        main.post {
            val g = id?.removePrefix("p")?.substringBefore(':')?.toIntOrNull() ?: return@post
            if (g != gen) return@post
            if (pending > 0) pending--
            if (pending == 0) idle()
        }
    }

    // ── audio focus: music ducks while Pragon talks ──────────────────────

    private fun requestFocus() {
        try {
            val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                ).build()
            focus = req
            am.requestAudioFocus(req)
        } catch (e: Exception) { }
    }

    private fun abandonFocus() {
        try {
            val f = focus ?: return
            (app.getSystemService(Context.AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(f)
            focus = null
        } catch (e: Exception) { }
    }

    /** After the user changed voice settings. */
    fun reload() { main.post { if (ready) apply(currentTag) } }

    private val PITCHES = floatArrayOf(0.95f, 0.75f, 1.15f, 1.35f)

    /**
     * The voice changer. Each call moves to the next installed voice for the speaking language. If the phone has
     * only one voice, it steps through pitch presets instead, so the change is always audible.
     * [reset] returns to the original voice. Returns the sentence Pragon should say in the new voice.
     */
    fun changeVoice(reset: Boolean): String {
        val call = Prefs.callMe(app)
        if (reset) {
            Prefs.setTtsVoice(app, ""); Prefs.setTtsPitch(app, PITCHES[0]); reload()
            return "Back to my original voice, $call."
        }
        val lang = baseLocale().language
        val list = try {
            tts?.voices?.filter { it.locale.language == lang && !it.features.contains("notInstalled") }
                ?.sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.name })
        } catch (e: Exception) { null } ?: emptyList()
        if (list.size >= 2) {
            val cur = Prefs.ttsVoice(app).ifBlank { tts?.voice?.name ?: "" }
            val next = list[(list.indexOfFirst { it.name == cur } + 1) % list.size]
            Prefs.setTtsVoice(app, next.name)
            reload()
            return "Voice changed, $call. This is voice ${list.indexOf(next) + 1} of ${list.size}."
        }
        val i = PITCHES.indexOfFirst { kotlin.math.abs(it - Prefs.ttsPitch(app)) < 0.03f }
        val n = (i + 1) % PITCHES.size
        Prefs.setTtsPitch(app, PITCHES[n])
        reload()
        return "Voice changed, $call. This is voice ${n + 1} of ${PITCHES.size}."
    }

    fun test() {
        speak("Hello, I am Pragon. This is how I sound, ${Prefs.callMe(app)}.")
    }
}
