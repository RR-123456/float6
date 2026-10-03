package com.pragon.mobile

import android.content.Context
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Offline / self-hosted engines for Standalone mode, ported from the PC's pragoncore/model.py:
 *  - "ollama": Ollama's native /api/chat, streamed, model kept loaded (keep_alive -1) and primed by warmup()
 *  - "openai": any OpenAI-compatible server (LM Studio, llama.cpp server, vLLM, OpenRouter ...), streamed
 * Both can call the phone_control and remember tools. Blocking: call from a background thread.
 */
object LocalLlmClient {

    val http: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(Guard.ShieldInterceptor())
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)   // a local model can take a while to load the first time
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val noTools = HashSet<String>()      // models that said "does not support tools"
    private val warmed = HashSet<String>()

    // ---------- address helpers ----------
    fun ollamaBase(raw: String): String {
        var h = raw.trim().trimEnd('/')
        if (h.isEmpty()) h = "127.0.0.1"
        if (!h.startsWith("http://") && !h.startsWith("https://")) h = "http://$h"
        val afterScheme = h.substringAfter("://")
        if (!afterScheme.contains(":") && h.startsWith("http://")) h += ":11434"
        return h
    }

    fun openaiBase(raw: String): String {
        var b = raw.trim().trimEnd('/')
        if (!b.startsWith("http://") && !b.startsWith("https://")) b = "http://$b"
        return b
    }

    private class ToolsUnsupported : Exception()

    /**
     * Loads the model into memory and primes Ollama's prompt cache with Pragon's static system prompt, so the
     * first real question answers fast (PC build: warmup_model()). Safe to call often; runs once per model.
     */
    fun warmup(ctx: Context) {
        val engine = Prefs.engine(ctx)
        if (engine == "gemini") return
        val isOllama = engine == "ollama"
        val model = if (isOllama) Prefs.ollamaModel(ctx) else Prefs.openaiModel(ctx)
        if (model.isBlank()) return
        val key = engine + "|" + model + "|" + (if (isOllama) Prefs.ollamaHost(ctx) else Prefs.openaiBase(ctx))
        synchronized(warmed) { if (!warmed.add(key)) return }
        try {
            val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", Persona.STATIC))
                .put(JSONObject().put("role", "user").put("content", "hi"))
            val req = if (isOllama) {
                val body = JSONObject().put("model", model).put("messages", msgs).put("stream", false).put("keep_alive", -1)
                    .put("options", JSONObject().put("num_predict", 1).put("num_gpu", 99))
                Request.Builder().url(ollamaBase(Prefs.ollamaHost(ctx)) + "/api/chat").post(body.toString().toRequestBody(JSON)).build()
            } else {
                val body = JSONObject().put("model", model).put("messages", msgs).put("stream", false).put("max_tokens", 1)
                val rb = Request.Builder().url(openaiBase(Prefs.openaiBase(ctx)) + "/chat/completions").post(body.toString().toRequestBody(JSON))
                val k = Prefs.openaiKey(ctx)
                if (k.isNotBlank()) rb.header("Authorization", "Bearer $k")
                rb.build()
            }
            http.newCall(req).execute().use { }
        } catch (e: Exception) {
            synchronized(warmed) { warmed.remove(key) }   // try again next time
        }
    }

    fun chat(
        ctx: Context, userText: String, files: List<Attachment>, mode: PMode, maxTokens: Int, sink: ChatSink,
    ): ChatResult {
        val engine = Prefs.engine(ctx)
        val isOllama = engine == "ollama"
        val model = if (isOllama) Prefs.ollamaModel(ctx) else Prefs.openaiModel(ctx)
        if (model.isBlank()) {
            return ChatResult("Pick a model first. Open Settings, choose the Custom engine and type the model name your server uses.", emptyList(), true)
        }
        val history = Conversation.local
        Conversation.trim()
        val startSize = history.size

        // ---- build the user message (images + text files supported) ----
        var text = userText
        val images = mutableListOf<Attachment>()
        val skipped = mutableListOf<String>()
        for (f in files) {
            when {
                f.mime.startsWith("image/") -> images.add(f)
                f.mime.startsWith("text/") -> {
                    val body = try { String(Base64.decode(f.b64, Base64.DEFAULT), Charsets.UTF_8).take(20000) } catch (e: Exception) { "" }
                    text += "\n\n[File ${f.name}]\n$body"
                }
                else -> skipped.add(f.name)
            }
        }
        val user = JSONObject().put("role", "user")
        if (isOllama) {
            user.put("content", text)
            if (images.isNotEmpty()) user.put("images", JSONArray().also { arr -> images.forEach { arr.put(it.b64) } })
        } else if (images.isNotEmpty()) {
            val parts = JSONArray().put(JSONObject().put("type", "text").put("text", text))
            for (a in images) {
                parts.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:${a.mime};base64,${a.b64}")))
            }
            user.put("content", parts)
        } else {
            user.put("content", text)
        }
        history.add(user)

        val system = Persona.system(ctx, mode)
        val seen = HashSet<String>()
        val actions = mutableListOf<ToolOut>()
        var lastText = ""
        try {
            for (step in 0 until 4) {
                val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", system))
                for (m in history) msgs.put(m)
                val splitter = SentenceSplitter { sink.onSentence(it) }
                val onText = { d: String -> sink.onText(d); splitter.feed(d) }
                var useTools = model !in noTools
                val turn: Turn = try {
                    stream(ctx, isOllama, model, msgs, useTools, mode, maxTokens, onText)
                } catch (e: ToolsUnsupported) {
                    noTools.add(model); useTools = false
                    stream(ctx, isOllama, model, msgs, false, mode, maxTokens, onText)
                }
                splitter.flush()
                history.add(turn.assistant)
                if (turn.text.isNotBlank()) lastText = turn.text
                if (turn.calls.isEmpty()) break
                for (c in turn.calls) {
                    val out = Tools.run(ctx, c, mode, seen)
                    if (out.acted || c.name == "phone_control") actions.add(out)
                    val tm = JSONObject().put("role", "tool")
                        .put("content", JSONObject().put("ok", out.ok).put("message", out.msg).toString())
                    if (isOllama) tm.put("tool_name", c.name) else tm.put("tool_call_id", c.id)
                    history.add(tm)
                }
            }
        } catch (e: Exception) {
            trim(history, startSize)
            val where = if (isOllama) Prefs.ollamaHost(ctx) else Prefs.openaiBase(ctx)
            return ChatResult("I couldn't reach the model at $where (${e.message}). Is the server running and on the same network?", actions, true)
        } finally {
            // keep big image data out of the running conversation
            if (history.size > startSize && history[startSize].has("images")) {
                history[startSize] = JSONObject().put("role", "user").put("content", text)
            }
        }
        val note = if (skipped.isNotEmpty()) " (I can't read ${skipped.joinToString(", ")} with this engine.)" else ""
        return ChatResult(lastText + note, actions)
    }

    private fun stream(
        ctx: Context, isOllama: Boolean, model: String, msgs: JSONArray, useTools: Boolean, mode: PMode,
        maxTokens: Int, onText: (String) -> Unit,
    ): Turn {
        val req: Request
        if (isOllama) {
            val body = JSONObject().put("model", model).put("messages", msgs).put("stream", true)
                .put("keep_alive", -1)
                .put("options", JSONObject().put("num_predict", maxTokens).put("num_gpu", 99).put("num_ctx", 4096))
            if (useTools) body.put("tools", Tools.openai(mode))
            req = Request.Builder().url(ollamaBase(Prefs.ollamaHost(ctx)) + "/api/chat").post(body.toString().toRequestBody(JSON)).build()
        } else {
            val body = JSONObject().put("model", model).put("messages", msgs).put("stream", true).put("max_tokens", maxTokens)
            if (useTools) body.put("tools", Tools.openai(mode))
            val rb = Request.Builder().url(openaiBase(Prefs.openaiBase(ctx)) + "/chat/completions").post(body.toString().toRequestBody(JSON))
            val k = Prefs.openaiKey(ctx)
            if (k.isNotBlank()) rb.header("Authorization", "Bearer $k")
            req = rb.build()
        }
        val resp = http.newCall(req).execute()
        if (!resp.isSuccessful) {
            val code = resp.code
            val raw = resp.use { it.body?.string() ?: "" }
            val err = try {
                val o = JSONObject(raw)
                if (isOllama) o.optString("error") else (o.optJSONObject("error")?.optString("message") ?: raw.take(200))
            } catch (e: Exception) { raw.take(200) }
            if (useTools && code == 400 && err.contains("tool", true)) throw ToolsUnsupported()
            if (code == 404 && isOllama) throw RuntimeException("model \"$model\" isn't installed - pull it in Settings")
            throw RuntimeException(err.ifBlank { "HTTP $code" })
        }
        return resp.use { r ->
            val src = r.body!!.source()
            val lines = iterator<String> { while (!src.exhausted()) yield(src.readUtf8Line() ?: break) }
            if (isOllama) StreamParsers.ollama(lines, onText) else StreamParsers.openai(lines, onText)
        }
    }

    private fun trim(history: MutableList<JSONObject>, size: Int) {
        while (history.size > size) history.removeAt(history.size - 1)
    }
}
