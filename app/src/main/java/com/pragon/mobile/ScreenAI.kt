package com.pragon.mobile

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * "What's on my screen?" / "answer the question on the screen" / "read the screen".
 * Works only when YOU turned on "Let Pragon read my screen" (Settings > Security). The screen's text is read through the
 * accessibility service (never a picture, never a password box) and goes ONLY to the AI engine you chose in Settings:
 * your own Ollama / OpenAI-compatible server. With Privacy Shield on, anything outside your own network is refused.
 * The question is asked on its own: it is not added to your chat history.
 */
object ScreenAI {

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private const val SYSTEM =
        "You are Pragon, a phone assistant. The user shows you the text currently visible on their phone screen " +
            "(in reading order, one item per line) and asks about it. Answer ONLY from that text, in plain spoken language, " +
            "in at most three short sentences, with no markdown or lists. If the answer isn't on the screen, say so. " +
            "If the screen shows a question with options, give the answer and the option's letter or text."

    private fun gate(ctx: Context): CommandExecutor.Res? {
        if (!Guard.screenReadingOn(ctx))
            return CommandExecutor.Res(false, "I'm not allowed to read your screen. Turn on \"Let Pragon read my screen\" in Settings > Security first.", label = "Screen")
        if (PragonAccessibilityService.instance == null)
            return CommandExecutor.Res(false, "Reading the screen needs the Pragon accessibility service turned on.", label = "Screen")
        return null
    }

    /** The visible screen text, or a failure message. */
    private fun grab(ctx: Context): Pair<String?, CommandExecutor.Res?> {
        gate(ctx)?.let { return null to it }
        val txt = AccessFeatures.Screen.dump(PragonAccessibilityService.instance!!, ctx.packageName)
            ?: return null to CommandExecutor.Res(false, "I couldn't read the screen right now.", label = "Screen")
        if (txt.isBlank()) return null to CommandExecutor.Res(false, "I can't see any text on this screen. It may be a picture, a video or a protected screen.", label = "Screen")
        return txt to null
    }

    /** "read the screen": the phone's own text to speech reads what's visible. No AI, nothing leaves the phone. */
    fun read(ctx: Context): CommandExecutor.Res {
        val (txt, fail) = grab(ctx)
        if (fail != null) return fail
        val t = txt!!.lines().filter { it.isNotBlank() }.joinToString(". ").take(2500)
        try { Speaker.get(ctx).speak(t) } catch (e: Exception) { return CommandExecutor.Res(false, "I couldn't start reading: ${e.message}") }
        return CommandExecutor.Res(true, "Reading the screen aloud.", label = "Screen")
    }

    /** Asks the AI about the screen. Blocking: call from a background thread. */
    fun ask(ctx: Context, question: String): CommandExecutor.Res {
        val (txt, fail) = grab(ctx)
        if (fail != null) return fail
        Guard.engineProblem(ctx)?.let { return CommandExecutor.Res(false, it, label = "Screen") }
        val engine = Prefs.engine(ctx)
        if (engine == "gemini") return CommandExecutor.Res(false, "Screen questions need Ollama or an OpenAI-compatible server. Gemini sends data to Google, so it isn't used for this. Change the engine in Settings.", label = "Screen")
        val user = "SCREEN TEXT:\n" + txt + "\n\nQUESTION: " + question.ifBlank { "Briefly, what is on this screen?" }
        return try {
            val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", SYSTEM)).put(JSONObject().put("role", "user").put("content", user))
            val req: Request
            if (engine == "ollama") {
                val body = JSONObject().put("model", Prefs.ollamaModel(ctx)).put("messages", msgs).put("stream", false)
                    .put("options", JSONObject().put("num_predict", 220).put("num_ctx", 4096).put("temperature", 0.2))
                req = Request.Builder().url(LocalLlmClient.ollamaBase(Prefs.ollamaHost(ctx)) + "/api/chat").post(body.toString().toRequestBody(JSON)).build()
            } else {
                val body = JSONObject().put("model", Prefs.openaiModel(ctx)).put("messages", msgs).put("stream", false).put("max_tokens", 220).put("temperature", 0.2)
                val rb = Request.Builder().url(LocalLlmClient.openaiBase(Prefs.openaiBase(ctx)) + "/chat/completions").post(body.toString().toRequestBody(JSON))
                val k = Prefs.openaiKey(ctx)
                if (k.isNotBlank()) rb.header("Authorization", "Bearer $k")
                req = rb.build()
            }
            LocalLlmClient.http.newCall(req).execute().use { r ->
                val raw = r.body?.string() ?: ""
                if (!r.isSuccessful) return CommandExecutor.Res(false, "The AI server answered HTTP ${r.code}: " + raw.take(120), label = "Screen")
                val o = JSONObject(raw)
                val ans = (if (engine == "ollama") o.optJSONObject("message")?.optString("content")
                else o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content"))
                    ?.replace(Regex("<think>[\\s\\S]*?</think>"), "")?.replace(Regex("[*_`#]+"), "")?.trim().orEmpty()
                if (ans.isBlank()) CommandExecutor.Res(false, "The AI gave an empty answer.", label = "Screen")
                else CommandExecutor.Res(true, ans, label = "Screen")
            }
        } catch (e: Exception) {
            CommandExecutor.Res(false, "I couldn't reach your AI server (${e.message ?: "no connection"}).", label = "Screen")
        }
    }
}
