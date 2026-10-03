package com.pragon.mobile

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** What a chat engine hands back to the Brain. */
class ChatResult(val text: String, val actions: List<ToolOut>, val error: Boolean = false)

/** Receives the model's words as they arrive. */
interface ChatSink {
    fun onText(delta: String)
    fun onSentence(sentence: String)
}

/** Shared conversation memory so the chat screen and the float bubble talk to the same Pragon. */
object Conversation {
    val gemini = mutableListOf<JSONObject>()
    val local = mutableListOf<JSONObject>()
    private var engineKey = ""

    /** Local histories are only valid for the engine/model/server they were built with. */
    @Synchronized fun checkEngine(ctx: Context) {
        val k = Prefs.engine(ctx) + "|" + Prefs.ollamaModel(ctx) + "|" + Prefs.openaiModel(ctx) + "|" +
            Prefs.ollamaHost(ctx) + "|" + Prefs.openaiBase(ctx)
        if (k != engineKey) { local.clear(); engineKey = k }
    }

    @Synchronized fun clear() { gemini.clear(); local.clear() }

    /** Drops the oldest turns, but never leaves a history that starts in the middle of a tool exchange. */
    @Synchronized fun trim() {
        while (gemini.size > 24) gemini.removeAt(0)
        while (gemini.isNotEmpty() && !isPlainUser(gemini[0])) gemini.removeAt(0)
        while (local.size > 30) local.removeAt(0)
        while (local.isNotEmpty() && local[0].optString("role") != "user") local.removeAt(0)
    }

    private fun isPlainUser(m: JSONObject): Boolean {
        if (m.optString("role") != "user") return false
        val ps = m.optJSONArray("parts") ?: return false
        return ps.length() > 0 && ps.optJSONObject(0)?.has("text") == true
    }

    /** A turn that was handled without the AI (a phone action), so the AI still knows it happened. */
    @Synchronized fun note(user: String, reply: String) {
        gemini.add(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user))))
        gemini.add(JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("text", reply))))
        local.add(JSONObject().put("role", "user").put("content", user))
        local.add(JSONObject().put("role", "assistant").put("content", reply))
        trim()
    }
}

class Attachment(val name: String, val mime: String, val b64: String)

/**
 * Talks to Gemini directly from the phone (standalone mode), streaming the answer so speech can start with
 * the first sentence. Handles: the default model being retired (falls back through newer ones), thinking
 * models (kept on a short leash for voice latency), and the phone_control / remember tools.
 * Blocking: call it from a background thread.
 */
object GeminiClient {

    private val http = OkHttpClient.Builder()
        .addInterceptor(Guard.ShieldInterceptor())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** Tried in order when the chosen model is gone (404). */
    private val FALLBACKS = listOf(Prefs.DEFAULT_GEMINI, "gemini-3.5-flash", "gemini-3.1-flash-lite", "gemini-2.5-flash")

    private fun modelChain(ctx: Context): List<String> {
        val first = Prefs.aiModelWorking(ctx).ifBlank { Prefs.aiModel(ctx) }
        return (listOf(first, Prefs.aiModel(ctx)) + FALLBACKS).distinct()
    }

    private fun thinking(model: String): JSONObject? = when {
        model.startsWith("gemini-3") -> JSONObject().put("thinkingLevel", "low")     // 3.x: "minimal" is rejected
        model.startsWith("gemini-2.5-flash") -> JSONObject().put("thinkingBudget", 0) // 2.5 flash may switch thinking off
        else -> null
    }

    private fun errorMessage(raw: String): String =
        try { JSONObject(raw).getJSONObject("error").optString("message") } catch (e: Exception) { raw.take(160) }

    fun chat(
        ctx: Context, userText: String, files: List<Attachment>, mode: PMode, maxTokens: Int, sink: ChatSink,
    ): ChatResult {
        val key = Prefs.effectiveAiKey(ctx)
        if (key.isBlank()) {
            return ChatResult("I need a Gemini API key first. Add yours in Settings, or pair with your PC once and I'll use its key.", emptyList(), true)
        }
        val history = Conversation.gemini
        Conversation.trim()
        val startSize = history.size
        val userContent = JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", userText)))
        for (f in files) {
            userContent.getJSONArray("parts").put(
                JSONObject().put("inlineData", JSONObject().put("mimeType", f.mime).put("data", f.b64))
            )
        }
        history.add(userContent)
        val system = Persona.system(ctx, mode)
        val seen = HashSet<String>()
        val actions = mutableListOf<ToolOut>()
        var lastText = ""
        try {
            for (step in 0 until 4) {
                val splitter = SentenceSplitter { sink.onSentence(it) }
                var turn: Turn? = null
                var failure = ""
                search@ for (m in modelChain(ctx)) {
                    for (attempt in 0..1) {
                        val gen = JSONObject().put("temperature", 0.7).put("maxOutputTokens", maxTokens)
                        if (attempt == 0) thinking(m)?.let { gen.put("thinkingConfig", it) }
                        val body = JSONObject()
                            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                            .put("contents", JSONArray(history))
                            .put("tools", Tools.gemini(mode))
                            .put("generationConfig", gen)
                        val req = Request.Builder()
                            .url("https://generativelanguage.googleapis.com/v1beta/models/$m:streamGenerateContent?alt=sse")
                            .header("x-goog-api-key", key)
                            .post(body.toString().toRequestBody(JSON))
                            .build()
                        val resp = http.newCall(req).execute()
                        if (resp.isSuccessful) {
                            resp.use { r ->
                                val src = r.body!!.source()
                                val lines = iterator<String> { while (!src.exhausted()) yield(src.readUtf8Line() ?: break) }
                                turn = StreamParsers.gemini(lines) { d -> sink.onText(d); splitter.feed(d) }
                            }
                            if (m != Prefs.aiModelWorking(ctx)) Prefs.saveAiModelWorking(ctx, m)
                            break@search
                        }
                        val code = resp.code
                        val msg = resp.use { errorMessage(it.body?.string() ?: "") }
                        failure = msg.ifBlank { "HTTP $code" }
                        if (attempt == 0 && code == 400 && failure.contains("think", true)) continue   // retry without thinkingConfig
                        val gone = code == 404 || failure.contains("no longer available", true) ||
                            failure.contains("is not found", true) || failure.contains("not found for api", true)
                        if (gone) continue@search else break@search
                    }
                }
                val t = turn
                if (t == null) {
                    trim(history, startSize)
                    return ChatResult("Gemini said no: $failure", actions, true)
                }
                splitter.flush()
                history.add(t.assistant)
                if (t.text.isNotBlank()) lastText = t.text
                if (t.calls.isEmpty()) {
                    if (t.text.isBlank() && actions.isEmpty()) {
                        trim(history, startSize)
                        return ChatResult("I couldn't come up with an answer to that.", actions, true)
                    }
                    break
                }
                val responses = JSONArray()
                for (c in t.calls) {
                    val out = Tools.run(ctx, c, mode, seen)
                    if (out.acted || c.name == "phone_control") actions.add(out)
                    responses.put(
                        JSONObject().put(
                            "functionResponse",
                            JSONObject().put("name", c.name)
                                .put("response", JSONObject().put("ok", out.ok).put("message", out.msg))
                        )
                    )
                }
                history.add(JSONObject().put("role", "user").put("parts", responses))
            }
        } catch (e: Exception) {
            trim(history, startSize)
            return ChatResult("I couldn't reach Gemini (${e.message}). Check your internet connection.", actions, true)
        } finally {
            // don't keep big file data in the running conversation; leave a text note instead
            if (files.isNotEmpty() && history.size > startSize) {
                history[startSize] = JSONObject().put("role", "user").put("parts", JSONArray().put(
                    JSONObject().put("text", userText + " [attached: " + files.joinToString(", ") { it.name } + "]")))
            }
        }
        return ChatResult(lastText, actions)
    }

    private fun trim(history: MutableList<JSONObject>, size: Int) {
        while (history.size > size) history.removeAt(history.size - 1)
    }
}
