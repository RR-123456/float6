package com.pragon.mobile

import android.content.Context
import android.os.Handler
import android.os.Looper

/** Pragon's three personalities. MASTER is the default: it talks AND acts, and says what it did. */
enum class PMode(val key: String, val label: String) {
    ASSISTANT("assistant", "ASSISTANT"),   // only talks and holds a conversation
    MASTER("master", "MASTER"),            // assistant + agent: acts, and narrates what it did
    AGENT("agent", "AGENT");               // acts, with as little talk as possible

    companion object {
        fun from(s: String?): PMode = values().firstOrNull { it.key == s } ?: MASTER
    }
}

/** Saved PC address + the token the PC gave us when we paired, plus all the standalone settings. */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("pragon", Context.MODE_PRIVATE)
    fun host(c: Context): String = sp(c).getString("host", "") ?: ""
    fun port(c: Context): Int = sp(c).getInt("port", 8000)
    fun token(c: Context): String = sp(c).getString("token", "") ?: ""
    fun save(c: Context, host: String, port: Int, token: String) =
        sp(c).edit().putString("host", host).putInt("port", port).putString("token", token).apply()
    fun saveToken(c: Context, token: String) = sp(c).edit().putString("token", token).apply()

    // ---- Standalone AI: the user's own Gemini key wins; otherwise the key cached from the paired PC.
    fun aiKey(c: Context): String = sp(c).getString("ai_key", "") ?: ""
    fun pcAiKey(c: Context): String = sp(c).getString("pc_ai_key", "") ?: ""

    const val DEFAULT_GEMINI = "gemini-3.8-flash"

    /** Models that no longer exist / are being shut down. If one of these is saved we quietly use the default. */
    private val DEAD_MODELS = setOf(
        "gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.5-pro",
        "gemini-2.0-flash", "gemini-2.0-flash-001", "gemini-2.0-flash-lite", "gemini-2.0-flash-lite-001",
        "gemini-1.5-flash", "gemini-1.5-pro", "gemini-1.5-flash-8b", "gemini-pro",
    )

    fun aiModel(c: Context): String {
        val m = (sp(c).getString("ai_model", "") ?: "").trim()
        return if (m.isBlank() || m in DEAD_MODELS) DEFAULT_GEMINI else m
    }
    /** The model that last answered successfully (set when the saved one 404s and a fallback works). */
    fun aiModelWorking(c: Context): String = sp(c).getString("ai_model_ok", "") ?: ""
    fun saveAiModelWorking(c: Context, m: String) = sp(c).edit().putString("ai_model_ok", m).apply()

    fun effectiveAiKey(c: Context): String = aiKey(c).ifBlank { pcAiKey(c) }
    fun saveAi(c: Context, key: String, model: String) =
        sp(c).edit().putString("ai_key", key).putString("ai_model", model).remove("ai_model_ok").apply()
    fun savePcAiKey(c: Context, key: String) = sp(c).edit().putString("pc_ai_key", key).apply()

    // ---- AI engine for Standalone: "gemini" (online), "ollama" (offline / LAN) or "openai" (any OpenAI-compatible server)
    fun engine(c: Context): String {
        val e = sp(c).getString("engine", "gemini") ?: "gemini"
        return if (e == "ollama" || e == "openai") e else "gemini"
    }
    fun ollamaHost(c: Context): String {
        val h = sp(c).getString("ollama_host", "") ?: ""
        if (h.isNotBlank()) return h
        val pc = host(c)
        return if (pc.isNotBlank()) "http://$pc:11434" else "http://127.0.0.1:11434"
    }
    fun ollamaModel(c: Context): String {
        val m = sp(c).getString("ollama_model", "") ?: ""
        return if (m.isBlank()) "hermes3" else m
    }
    fun openaiBase(c: Context): String {
        val b = sp(c).getString("openai_base", "") ?: ""
        return if (b.isBlank()) "http://127.0.0.1:8080/v1" else b
    }
    fun openaiKey(c: Context): String = sp(c).getString("openai_key", "") ?: ""
    fun openaiModel(c: Context): String = sp(c).getString("openai_model", "") ?: ""
    fun saveEngine(c: Context, engine: String, oHost: String, oModel: String, base: String, key: String?, cModel: String) {
        val e = sp(c).edit()
            .putString("engine", engine)
            .putString("ollama_host", oHost)
            .putString("ollama_model", oModel)
            .putString("openai_base", base)
            .putString("openai_model", cModel)
        if (key != null) e.putString("openai_key", key)   // null = keep the saved key
        e.apply()
    }
    /** Short label for the chip in the Standalone header. */
    fun engineLabel(c: Context): String = when (engine(c)) {
        "ollama" -> "OLLAMA"
        "openai" -> "CUSTOM"
        else -> "GEMINI"
    }

    // ---- Mode: assistant (talk only) / master (talk + act + narrate, DEFAULT) / agent (act)
    fun mode(c: Context): PMode = PMode.from(sp(c).getString("mode", PMode.MASTER.key))
    fun saveMode(c: Context, m: PMode) = sp(c).edit().putString("mode", m.key).apply()

    // ---- Voice + personality (ported from the PC's Pragon protocol / voice settings)
    /** What to call the user. The PC protocol says "sir". */
    fun callMe(c: Context): String = (sp(c).getString("call_me", "sir") ?: "sir").trim().ifBlank { "sir" }
    /** BCP-47 tag for listening and (for Latin-script replies) speaking. "" = the phone's language. */
    fun speechLang(c: Context): String = sp(c).getString("speech_lang", "") ?: ""
    /** "" = reply in whatever language the user is using right now; otherwise lock to this language name, e.g. "Tamil". */
    fun replyLang(c: Context): String = sp(c).getString("reply_lang", "") ?: ""
    fun ttsRate(c: Context): Float = sp(c).getFloat("tts_rate", 1.0f)
    fun ttsPitch(c: Context): Float = sp(c).getFloat("tts_pitch", 0.95f)
    fun ttsVoice(c: Context): String = sp(c).getString("tts_voice", "") ?: ""
    /** Also speak replies to typed messages (voice messages are always spoken unless muted). */
    fun speakTyped(c: Context): Boolean = sp(c).getBoolean("speak_typed", false)
    /** Float mode: require the word "Pragon" before a free-form question (off = just talk). */
    fun floatNeedsWake(c: Context): Boolean = sp(c).getBoolean("float_needs_wake", false)
    fun setTtsVoice(c: Context, name: String) = sp(c).edit().putString("tts_voice", name).apply()
    fun setTtsPitch(c: Context, p: Float) = sp(c).edit().putFloat("tts_pitch", p.coerceIn(0.5f, 1.5f)).apply()
    fun muted(c: Context): Boolean = sp(c).getBoolean("muted", false)
    fun setMuted(c: Context, v: Boolean) = sp(c).edit().putBoolean("muted", v).apply()
    fun saveVoice(
        c: Context, callMe: String, speechLang: String, replyLang: String, rate: Float, pitch: Float,
        voice: String, speakTyped: Boolean, floatNeedsWake: Boolean,
    ) {
        sp(c).edit()
            .putString("call_me", callMe.trim().ifBlank { "sir" })
            .putString("speech_lang", speechLang.trim())
            .putString("reply_lang", replyLang.trim())
            .putFloat("tts_rate", rate.coerceIn(0.5f, 2.0f))
            .putFloat("tts_pitch", pitch.coerceIn(0.5f, 1.5f))
            .putString("tts_voice", voice.trim())
            .putBoolean("speak_typed", speakTyped)
            .putBoolean("float_needs_wake", floatNeedsWake)
            .apply()
    }

    // ---- Float mode bubble position
    fun floatX(c: Context, def: Int): Int = sp(c).getInt("float_x", def)
    fun floatY(c: Context, def: Int): Int = sp(c).getInt("float_y", def)
    fun saveFloatPos(c: Context, x: Int, y: Int) = sp(c).edit().putInt("float_x", x).putInt("float_y", y).apply()

    /** Forget the PC but keep everything else (AI settings, mode, voice, memory, bubble position). */
    fun clear(c: Context) {
        sp(c).edit().remove("host").remove("port").remove("token").remove("pc_ai_key").apply()
    }
}

/** Tiny bridge so the service can show its status in the activity. */
object Bridge {
    @Volatile var status: String = "Not connected"
    @Volatile var listener: ((String) -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    fun set(s: String) {
        status = s
        main.post { listener?.invoke(s) }
    }
}
