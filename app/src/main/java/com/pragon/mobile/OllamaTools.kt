package com.pragon.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manage the models on your Ollama server from the phone: list, pull (download), delete, choose.
 * The server is the one in Settings > AI engine > Ollama (your PC, or Termux on this phone).
 * Used by the Settings screen (progress bar) and by chat commands:
 *   "ollama pull llama3.2:3b" / "pull model qwen2.5" / "ollama list" / "use model hermes3"
 */
object OllamaTools {

    class Model(val name: String, val size: Long)

    private val JSON = "application/json; charset=utf-8".toMediaType()

    fun base(ctx: Context, hostOverride: String? = null): String =
        LocalLlmClient.ollamaBase(if (hostOverride.isNullOrBlank()) Prefs.ollamaHost(ctx) else hostOverride)

    fun fmtSize(b: Long): String = when {
        b <= 0 -> ""
        b >= 1_000_000_000L -> String.format(Locale.US, "%.1f GB", b / 1e9)
        else -> String.format(Locale.US, "%d MB", b / 1_000_000L)
    }

    /** Installed models. Throws with a readable message if the server can't be reached. */
    fun list(base: String): List<Model> {
        val req = Request.Builder().url("$base/api/tags").build()
        LocalLlmClient.http.newCall(req).execute().use { r ->
            val raw = r.body?.string() ?: ""
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
            val arr = JSONObject(raw).optJSONArray("models") ?: return emptyList()
            return (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Model(o.optString("name"), o.optLong("size", 0))
            }
        }
    }

    /**
     * Downloads [name] on the server. Blocking. [onProgress] gets (status text, percent or -1, bytes done, bytes total).
     * Returns null on success, otherwise a readable error.
     */
    fun pull(base: String, name: String, onProgress: (String, Int, Long, Long) -> Unit): String? {
        return try {
            // "model" is what current Ollama wants, "name" is what older versions want: send both.
            val body = JSONObject().put("model", name).put("name", name).put("stream", true).toString().toRequestBody(JSON)
            val req = Request.Builder().url("$base/api/pull").post(body).build()
            LocalLlmClient.http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) {
                    val msg = try { JSONObject(r.body?.string() ?: "").optString("error") } catch (e: Exception) { "" }
                    return if (msg.isNotBlank()) msg else "The server answered HTTP ${r.code}."
                }
                val src = r.body?.source() ?: return "The server sent nothing back."
                var ok = false
                while (!src.exhausted()) {
                    val line = src.readUtf8Line() ?: break
                    if (line.isBlank()) continue
                    val o = try { JSONObject(line) } catch (e: Exception) { continue }
                    if (o.has("error")) return o.optString("error")
                    val st = o.optString("status")
                    val total = o.optLong("total", 0)
                    val done = o.optLong("completed", 0)
                    val pct = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else -1
                    onProgress(st, pct, done, total)
                    if (st == "success") ok = true
                }
                if (ok) null else "The download stopped before it finished. Check the server and try again."
            }
        } catch (e: Exception) {
            "Can't reach the Ollama server at $base (${e.message ?: "no connection"}). Is it running, on the same Wi-Fi, with OLLAMA_HOST=0.0.0.0?"
        }
    }

    /** Removes a model from the server. Returns null on success, else an error. */
    fun delete(base: String, name: String): String? = try {
        val body = JSONObject().put("model", name).put("name", name).toString().toRequestBody(JSON)
        val req = Request.Builder().url("$base/api/delete").delete(body).build()
        LocalLlmClient.http.newCall(req).execute().use { r ->
            if (r.isSuccessful) null else "The server answered HTTP ${r.code}."
        }
    } catch (e: Exception) {
        "Can't reach the Ollama server (${e.message ?: "no connection"})."
    }

    /** Makes [name] the model Pragon talks to, and switches the engine to Ollama. */
    fun use(ctx: Context, name: String) {
        Prefs.saveEngine(ctx, "ollama", Prefs.ollamaHost(ctx), name, Prefs.openaiBase(ctx), null, Prefs.openaiModel(ctx))
        Brain.warmup(ctx)
    }

    // ── pulling from a chat command: runs in the background with a progress notification ──

    private val pulling = AtomicBoolean(false)
    private const val CH = "pragon_pull"

    /** Starts a background pull. Returns what Pragon should say right now. */
    fun pullInBackground(ctx: Context, name: String): String {
        val app = ctx.applicationContext
        val call = Prefs.callMe(app)
        if (!pulling.compareAndSet(false, true)) return "Another model is still downloading, $call. Wait for it to finish."
        val server = base(app)
        Thread {
            val nm = app.getSystemService(NotificationManager::class.java)
            try { nm.createNotificationChannel(NotificationChannel(CH, "Model downloads", NotificationManager.IMPORTANCE_LOW)) } catch (e: Exception) { }
            fun show(title: String, text: String, pct: Int, ongoing: Boolean) {
                try {
                    val b = Notification.Builder(app, CH)
                        .setSmallIcon(android.R.drawable.stat_sys_download)
                        .setContentTitle(title).setContentText(text)
                        .setOngoing(ongoing).setOnlyAlertOnce(true).setAutoCancel(!ongoing)
                    if (ongoing) b.setProgress(100, pct.coerceAtLeast(0), pct < 0)
                    nm.notify(61, b.build())
                } catch (e: Exception) { }
            }
            show("Downloading $name", "Starting...", -1, true)
            var lastShown = 0L
            val err = pull(server, name) { st, pct, done, total ->
                val now = System.currentTimeMillis()
                if (now - lastShown > 700) {
                    lastShown = now
                    val size = if (total > 0) " (${fmtSize(done)} of ${fmtSize(total)})" else ""
                    show("Downloading $name", st + size, pct, true)
                }
            }
            if (err == null) {
                show("$name is ready", "Open Settings > AI engine to choose it, or say \"use model $name\".", 100, false)
                say(app, "The model $name has finished downloading, $call.")
            } else {
                show("Download failed", err, 0, false)
                say(app, "The download of $name failed, $call. $err")
            }
            pulling.set(false)
        }.start()
        return "Pulling $name from your Ollama server in the background, $call. I'll tell you when it's done."
    }

    private fun say(ctx: Context, text: String) {
        try { if (!Prefs.muted(ctx)) Speaker.get(ctx).speak(text) } catch (e: Exception) { }
    }

    /** "ollama list": the installed models as one sentence. Blocking. */
    fun listSentence(ctx: Context): String {
        val call = Prefs.callMe(ctx)
        return try {
            val ms = list(base(ctx))
            if (ms.isEmpty()) "Your Ollama server has no models yet, $call. Say \"ollama pull llama3.2\" to get one."
            else "Your Ollama server has ${ms.size} model" + (if (ms.size > 1) "s" else "") + ": " +
                ms.joinToString(", ") { it.name + (fmtSize(it.size).let { s -> if (s.isEmpty()) "" else " ($s)" }) } + "."
        } catch (e: Exception) {
            "I can't reach the Ollama server at ${base(ctx)}, $call (${e.message ?: "no connection"})."
        }
    }
}
