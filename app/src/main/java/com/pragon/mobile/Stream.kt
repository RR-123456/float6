package com.pragon.mobile

import org.json.JSONArray
import org.json.JSONObject

/** One tool call asked for by a model. */
class ToolCall(val id: String, val name: String, val args: JSONObject)

/**
 * Splits streaming text into sentences so speech can start with the first sentence while the model is still
 * writing the rest. Same idea as the PC's _SENT_END regex in pragoncore/model.py, extended for Indian and
 * East-Asian full stops. Decimals like 3.5 are not split (no space after the dot).
 */
class SentenceSplitter(private val emit: (String) -> Unit) {
    private val buf = StringBuilder()

    companion object {
        private val END = Regex("(?<=[.!?\u0964\u3002\uFF01\uFF1F])\\s+|(?<=\\n)\\s*\\n")
        private val ABBREV = Regex("(?i)(?:^|\\s)(?:mr|mrs|ms|dr|prof|sr|jr|vs|etc|e\\.g|i\\.e|st|no)\\.$")
    }

    fun feed(delta: String) {
        if (delta.isEmpty()) return
        buf.append(delta)
        while (true) {
            val m = END.find(buf) ?: break
            val sentence = buf.substring(0, m.range.first).trim()
            // "Dr. Smith": an abbreviation is not the end of a sentence, wait for more text
            if (ABBREV.containsMatchIn(sentence)) {
                val next = END.find(buf, m.range.last + 1) ?: break
                val merged = buf.substring(0, next.range.first).trim()
                buf.delete(0, next.range.last + 1)
                if (merged.isNotEmpty()) emit(merged)
                continue
            }
            buf.delete(0, m.range.last + 1)
            if (sentence.isNotEmpty()) emit(sentence)
        }
    }

    /** Call when the stream ends: emits whatever is left. */
    fun flush() {
        val rest = buf.toString().trim()
        buf.setLength(0)
        if (rest.isNotEmpty()) emit(rest)
    }
}

/** Drops <think>...</think> blocks that reasoning models print, even when the tags arrive split across chunks. */
class ThinkFilter {
    private var inThink = false
    private val pending = StringBuilder()

    /** Returns the part of [delta] that should be shown/spoken. */
    fun filter(delta: String): String {
        pending.append(delta)
        val out = StringBuilder()
        while (true) {
            if (inThink) {
                val e = pending.indexOf("</think>")
                if (e < 0) {
                    // keep a possible partial closing tag; discard the rest of the thought
                    val keep = partialSuffix(pending, "</think>")
                    pending.delete(0, pending.length - keep)
                    return out.toString()
                }
                pending.delete(0, e + "</think>".length)
                inThink = false
            } else {
                val s = pending.indexOf("<think>")
                if (s < 0) {
                    val keep = partialSuffix(pending, "<think>")
                    out.append(pending, 0, pending.length - keep)
                    pending.delete(0, pending.length - keep)
                    return out.toString()
                }
                out.append(pending, 0, s)
                pending.delete(0, s + "<think>".length)
                inThink = true
            }
        }
    }

    /** At the end of the stream: release anything that turned out not to be a tag. */
    fun finish(): String {
        val r = if (inThink) "" else pending.toString()
        pending.setLength(0)
        return r
    }

    /** Length of the longest suffix of [sb] that is a proper prefix of [tag]. */
    private fun partialSuffix(sb: StringBuilder, tag: String): Int {
        val max = minOf(sb.length, tag.length - 1)
        for (n in max downTo 1) if (sb.endsWith(tag.substring(0, n))) return n
        return 0
    }

    private fun StringBuilder.endsWith(s: String): Boolean =
        length >= s.length && substring(length - s.length) == s
}

/** Cleans text so a voice does not read out markdown, links or emoji. */
object SpeechText {
    private val URL = Regex("https?://\\S+|www\\.\\S+")
    private val MD_LINK = Regex("\\[([^\\]]+)]\\([^)]*\\)")
    private val CODE = Regex("(?s)```.*?```")
    private val MARKS = Regex("[*_`#>~|^]+")
    private val BULLET = Regex("(?m)^\\s*(?:[-\u2022\u25CF]|\\d+[.)])\\s+")
    private val EMOJI = Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{200D}]")
    private val SPACES = Regex("\\s+")

    fun clean(raw: String): String {
        var s = raw
        s = CODE.replace(s, " ")
        s = MD_LINK.replace(s, "$1")
        s = URL.replace(s, " link ")
        s = BULLET.replace(s, "")
        s = MARKS.replace(s, " ")
        s = EMOJI.replace(s, " ")
        return SPACES.replace(s, " ").trim()
    }
}

/** What one model request produced. [assistant] is the message to put back into the conversation history. */
class Turn(val text: String, val calls: List<ToolCall>, val assistant: JSONObject, val note: String = "")

/**
 * Parsers for the three wire formats. They take the response line by line and report text as it arrives.
 * Pure functions: no network in here, so they can be tested with recorded server output.
 */
object StreamParsers {

    // ---- Ollama /api/chat (newline-delimited JSON) ----
    fun ollama(lines: Iterator<String>, onText: (String) -> Unit): Turn {
        val think = ThinkFilter()
        val visible = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        val rawCalls = JSONArray()
        var err = ""
        while (lines.hasNext()) {
            val line = lines.next().trim()
            if (line.isEmpty()) continue
            val o = try { JSONObject(line) } catch (e: Exception) { continue }
            if (o.has("error")) { err = o.optString("error"); break }
            val msg = o.optJSONObject("message")
            if (msg != null) {
                val d = think.filter(msg.optString("content", ""))
                if (d.isNotEmpty()) { visible.append(d); onText(d) }
                val tc = msg.optJSONArray("tool_calls")
                if (tc != null) for (i in 0 until tc.length()) {
                    val fn = tc.optJSONObject(i)?.optJSONObject("function") ?: continue
                    val a = fn.opt("arguments")
                    val args = when (a) {
                        is JSONObject -> a
                        is String -> try { JSONObject(a) } catch (e: Exception) { JSONObject() }
                        else -> JSONObject()
                    }
                    calls.add(ToolCall("call_${calls.size}", fn.optString("name"), args))
                    rawCalls.put(JSONObject().put("function", JSONObject().put("name", fn.optString("name")).put("arguments", args)))
                }
            }
            if (o.optBoolean("done", false)) break
        }
        val tail = think.finish()
        if (tail.isNotEmpty()) { visible.append(tail); onText(tail) }
        if (err.isNotEmpty()) throw RuntimeException(err)
        val assistant = JSONObject().put("role", "assistant").put("content", visible.toString().trim())
        if (rawCalls.length() > 0) assistant.put("tool_calls", rawCalls)
        return Turn(visible.toString().trim(), calls, assistant)
    }

    // ---- OpenAI-compatible /chat/completions (server-sent events) ----
    fun openai(lines: Iterator<String>, onText: (String) -> Unit): Turn {
        val think = ThinkFilter()
        val visible = StringBuilder()
        class Frag { var id = ""; var name = StringBuilder(); var args = StringBuilder() }
        val frags = sortedMapOf<Int, Frag>()
        while (lines.hasNext()) {
            val line = lines.next().trim()
            if (!line.startsWith("data:")) continue
            val data = line.substring(5).trim()
            if (data == "[DONE]") break
            val o = try { JSONObject(data) } catch (e: Exception) { continue }
            if (o.has("error")) {
                val e = o.opt("error")
                throw RuntimeException(if (e is JSONObject) e.optString("message", "error") else (e?.toString() ?: "error"))
            }
            val choice = o.optJSONArray("choices")?.optJSONObject(0) ?: continue
            val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message") ?: JSONObject()
            val text = if (delta.isNull("content")) "" else delta.optString("content", "")
            val d = think.filter(text)
            if (d.isNotEmpty()) { visible.append(d); onText(d) }
            val tc = delta.optJSONArray("tool_calls")
            if (tc != null) for (i in 0 until tc.length()) {
                val t = tc.optJSONObject(i) ?: continue
                val f = frags.getOrPut(t.optInt("index", i)) { Frag() }
                if (t.optString("id").isNotEmpty()) f.id = t.optString("id")
                val fn = t.optJSONObject("function")
                if (fn != null) {
                    f.name.append(fn.optString("name", ""))
                    f.args.append(fn.optString("arguments", ""))
                }
            }
            val fr = choice.optString("finish_reason", "")
            if (fr == "stop" || fr == "tool_calls" || fr == "length") break
        }
        val tail = think.finish()
        if (tail.isNotEmpty()) { visible.append(tail); onText(tail) }
        val calls = mutableListOf<ToolCall>()
        val rawCalls = JSONArray()
        for ((idx, f) in frags) {
            val id = f.id.ifEmpty { "call_$idx" }
            val argStr = f.args.toString().ifBlank { "{}" }
            val args = try { JSONObject(argStr) } catch (e: Exception) { JSONObject() }
            calls.add(ToolCall(id, f.name.toString(), args))
            rawCalls.put(
                JSONObject().put("id", id).put("type", "function")
                    .put("function", JSONObject().put("name", f.name.toString()).put("arguments", argStr))
            )
        }
        val assistant = JSONObject().put("role", "assistant").put("content", visible.toString().trim())
        if (rawCalls.length() > 0) assistant.put("tool_calls", rawCalls)
        return Turn(visible.toString().trim(), calls, assistant)
    }

    // ---- Gemini :streamGenerateContent?alt=sse ----
    fun gemini(lines: Iterator<String>, onText: (String) -> Unit): Turn {
        val parts = JSONArray()                // what goes back into the history, in order
        val calls = mutableListOf<ToolCall>()
        val cur = StringBuilder()              // text being collected between function calls
        var curSig: String? = null
        val visible = StringBuilder()
        var note = ""
        fun flushText() {
            if (cur.isEmpty()) return
            val p = JSONObject().put("text", cur.toString())
            if (curSig != null) p.put("thoughtSignature", curSig)
            parts.put(p)
            cur.setLength(0); curSig = null
        }
        while (lines.hasNext()) {
            val line = lines.next().trim()
            if (!line.startsWith("data:")) continue
            val data = line.substring(5).trim()
            if (data.isEmpty() || data == "[DONE]") continue
            val o = try { JSONObject(data) } catch (e: Exception) { continue }
            if (o.has("error")) throw RuntimeException(o.optJSONObject("error")?.optString("message") ?: "Gemini error")
            val block = o.optJSONObject("promptFeedback")?.optString("blockReason", "") ?: ""
            if (block.isNotEmpty()) note = "blocked:$block"
            val cand = o.optJSONArray("candidates")?.optJSONObject(0) ?: continue
            val fin = cand.optString("finishReason", "")
            if (fin.isNotEmpty() && fin != "STOP") note = "finish:$fin"
            val ps = cand.optJSONObject("content")?.optJSONArray("parts") ?: continue
            for (i in 0 until ps.length()) {
                val p = ps.optJSONObject(i) ?: continue
                when {
                    p.has("functionCall") -> {
                        flushText()
                        val fc = p.getJSONObject("functionCall")
                        calls.add(ToolCall("call_${calls.size}", fc.optString("name"), fc.optJSONObject("args") ?: JSONObject()))
                        parts.put(p)           // verbatim: Gemini 3 needs its thoughtSignature sent back
                    }
                    p.has("text") && !p.optBoolean("thought", false) -> {
                        val t = p.optString("text", "")
                        if (t.isNotEmpty()) { cur.append(t); visible.append(t); onText(t) }
                        if (p.has("thoughtSignature")) curSig = p.optString("thoughtSignature")
                    }
                }
            }
        }
        flushText()
        val assistant = JSONObject().put("role", "model").put("parts", parts)
        return Turn(visible.toString().trim(), calls, assistant, note)
    }
}
