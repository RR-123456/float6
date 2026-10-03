package com.pragon.mobile

import android.content.Context
import org.json.JSONObject

/**
 * Pragon's brain for Standalone mode: one entry point used by both the chat screen and the float bubble.
 *
 *  ASSISTANT  only talks. Phone commands get a polite "I'm in Assistant mode".
 *  MASTER     (default) talks AND acts, and says what it did: "open youtube" opens it, then "YouTube opened, sir."
 *  AGENT      acts with as little talk as possible: silent on success, speaks only failures and answers.
 *
 * Plain phone commands are recognised locally and run instantly (no AI round trip, works offline, no API key).
 * Everything else goes to the chosen AI engine with Pragon's persona and streams back sentence by sentence.
 */
object Brain {

    /** What the UI should do with the answer. */
    class Reply(
        val text: String,
        /** Speak it? (false = show only, e.g. Agent mode after a successful action) */
        val speak: Boolean,
        /** True when its sentences were already delivered through [Sink.onSentence], so don't speak [text] again. */
        val streamed: Boolean,
        val acted: Boolean = false,
    )

    interface Sink {
        /** Text so far of the reply (for the chat bubble). */
        fun onPartial(textSoFar: String) {}
        /** A finished sentence, ready to be spoken. Only called in Assistant and Master mode. */
        fun onSentence(sentence: String) {}
    }

    private val lock = Any()
    /** Actions that only answer a question (they don't control the phone), so Assistant mode may run them. */
    private val INFO_ONLY = setOf("calc", "screen_ask", "screen_read", "privacy", "allowlist", "macro_list")
    private val ANSWERS = setOf("calc", "calc_screen", "stopwatch", "ollama_list", "battery_alert", "status", "typer", "screen_ask", "screen_read", "scan", "privacy", "allowlist", "firewall", "macro_list", "macro_record", "macro_stop", "macro_run")

    fun respond(
        ctx: Context, input: String, files: List<Attachment> = emptyList(), viaVoice: Boolean = false, sink: Sink? = null,
    ): Reply = synchronized(lock) {
        val raw = input.trim()
        val call = Prefs.callMe(ctx)
        val mode = Prefs.mode(ctx)

        // ---- typer mode: everything you say is typed into the focused text box ----
        if (viaVoice && Typer.active && files.isEmpty()) return typerStep(ctx, raw, mode)

        // ---- local commands (work with no AI at all) ----
        if (VoiceParser.isSelfClose(raw)) {
            return Reply("I can't close myself from here, $call. In float mode, say goodbye.", true, false)
        }
        val c = VoiceParser.cleanCommand(raw)
        VoiceParser.modeSwitch(c)?.let { m ->
            Prefs.saveMode(ctx, m)
            val t = Narrator.modeChanged(ctx, m)
            Conversation.note(raw, t)
            return Reply(t, true, false)
        }
        VoiceParser.voiceChange(c)?.let { v ->
            // Works in every mode. reload() is queued before the speech below, so the reply is in the NEW voice.
            val t = Speaker.get(ctx).changeVoice(reset = v == "reset")
            Conversation.note(raw, t)
            return Reply(t, true, false)
        }
        val act = if (files.isEmpty()) (VoiceParser.ollamaCommand(raw) ?: VoiceParser.rawAction(VoiceParser.norm(raw)) ?: VoiceParser.actionFor(c)) else null
        if (act != null) {
            if (mode == PMode.ASSISTANT && act.action !in INFO_ONLY) {
                val t = Narrator.assistantRefusal(ctx)
                Conversation.note(raw, t)
                return Reply(t, true, false)
            }
            val res = CommandExecutor.run(
                ctx, JSONObject().put("action", act.action).put("value", act.value), fromStandalone = true, user = true
            )
            // "show me the weather" is conversation, not an app called "weather": let the AI answer it
            val notAnApp = act.soft && act.action == "open_app" && !res.ok && res.msg.startsWith("I couldn't find an app")
            if (!notAnApp) {
                val t = Narrator.confirm(ctx, act, res)
                Conversation.note(raw, t)
                // Answers (a sum, a stopwatch reading, the model list) are always spoken, even in Agent mode.
                val isAnswer = act.action in ANSWERS
                return Reply(t, speak = mode == PMode.MASTER || !res.ok || isAnswer, streamed = false, acted = true)
            }
        }

        // ---- the AI ----
        Guard.engineProblem(ctx)?.let { return Reply(it, true, false) }
        Conversation.checkEngine(ctx)
        val streaming = mode != PMode.AGENT          // Agent mode never narrates while it works
        var spoken = 0
        val acc = StringBuilder()
        val chatSink = object : ChatSink {
            override fun onText(delta: String) { acc.append(delta); sink?.onPartial(acc.toString()) }
            override fun onSentence(sentence: String) {
                if (streaming && sink != null) { spoken++; sink.onSentence(sentence) }
            }
        }
        val maxGemini = if (viaVoice) 1024 else 2048
        val maxLocal = if (viaVoice) 400 else 900
        val r = try {
            if (Prefs.engine(ctx) == "gemini") GeminiClient.chat(ctx, raw.ifEmpty { "Please look at the attached file." }, files, mode, maxGemini, chatSink)
            else LocalLlmClient.chat(ctx, raw.ifEmpty { "Please look at the attached file." }, files, mode, maxLocal, chatSink)
        } catch (e: Exception) {
            ChatResult("Something went wrong: ${e.message}", emptyList(), true)
        }

        val acted = r.actions.any { it.acted }
        val allOk = r.actions.all { it.ok }
        var text = r.text
        var streamed = spoken > 0 && text.isNotBlank()
        if (text.isBlank() && r.actions.isNotEmpty()) {
            // The model did the action but said nothing: report the real result ourselves.
            val last = r.actions.last()
            text = if (last.ok) "Done, $call." else last.msg
            streamed = false
        }
        if (text.isBlank()) text = "Done, $call."
        if (r.error) streamed = false
        val speak = when (mode) {
            PMode.AGENT -> r.error || !acted || !allOk
            else -> true
        }
        Reply(text, speak, streamed && speak, acted)
    }

    /** One heard sentence while typer mode is on. Silent: it only speaks to confirm start/stop or to report a problem. */
    private fun typerStep(ctx: Context, raw: String, mode: PMode): Reply {
        val call = Prefs.callMe(ctx)
        val svc = PragonAccessibilityService.instance
        if (svc == null) {
            Typer.stop()
            return Reply("The accessibility service turned off, $call, so typer mode stopped.", true, false)
        }
        Typer.touch()
        fun quiet(msg: String) = Reply(msg, speak = false, streamed = false, acted = true)
        return when (val st = Typer.interpret(raw)) {
            Typer.Step.Stop -> { Typer.stop(); Reply("Typer mode off, $call.", true, false, true) }
            Typer.Step.Nothing -> quiet("")
            is Typer.Step.Text -> if (svc.dictate(st.piece)) quiet("Typed: " + st.piece)
                else Reply("I can't find a text box, $call. Tap one first and keep talking.", true, false)
            Typer.Step.DeleteLast -> if (svc.deleteLastDictation()) quiet("Deleted that.") else Reply("There's no text box to delete from.", true, false)
            Typer.Step.DeleteWord -> if (svc.deleteLastWord()) quiet("Deleted the last word.") else Reply("There's no text box to delete from.", true, false)
            Typer.Step.Backspace -> if (svc.backspace()) quiet("Backspace.") else Reply("There's no text box to delete from.", true, false)
            Typer.Step.ClearAll -> if (svc.clearField()) quiet("Cleared the box.") else Reply("There's no text box to clear.", true, false)
            Typer.Step.SelectAll -> if (svc.selectAll()) quiet("Selected all.") else Reply("There's no text box to select in.", true, false)
            Typer.Step.Enter -> if (svc.pressEnter()) quiet("Sent.") else Reply("I couldn't find a send button, $call.", true, false)
        }
    }

    /** Loads the model and primes its cache in the background (Ollama / custom server). */
    fun warmup(ctx: Context) {
        val app = ctx.applicationContext
        Thread { try { LocalLlmClient.warmup(app) } catch (e: Exception) { } }.start()
    }
}
