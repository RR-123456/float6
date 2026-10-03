package com.pragon.mobile

/**
 * Turns what the speech recognizer heard (or what was typed) into something Pragon can act on.
 * No Android classes in here on purpose, so it is easy to test on a plain JVM.
 *
 * Control phrases work alone or with "pragon" in front:
 *   "im home" / "daddy's home" (or "pragon im home")  -> Wake
 *   "mute" (or "hey pragon mute")                      -> Mute
 *   "goodbye" (or "hey pragon goodbye")                -> Goodbye
 * Without "pragon" the sentence has to be short. Everything else only counts while Float mode is active.
 */
object VoiceParser {

    sealed class Cmd {
        object None : Cmd()
        object Wake : Cmd()
        object Mute : Cmd()
        object Goodbye : Cmd()
        /** Hand this text to the brain (Brain decides: phone action, mode switch, or conversation). */
        data class Handle(val text: String) : Cmd()
    }

    /**
     * A phone action found in a sentence. [soft] = the verb was vague ("show me", "start", "go to"),
     * so if the app doesn't exist the sentence is probably just conversation and should go to the AI.
     */
    data class Action(val action: String, val value: String = "", val soft: Boolean = false)

    // Speech recognizers often mishear the name. These are the usual suspects.
    private const val STRONG =
        "(?:pragon|paragon|pragan|pragun|pragone|pragonn|praagon|prakon|pergon|pre gone|pro gone|" +
            "prague on|prague one|prug on|pra gone|pra gun)"
    // "dragon" / "bragon" are real words, so they only count as the name at the very start ("hey dragon ...").
    private const val WEAK = "(?:dragon|bragon)"
    private val WAKE = Regex("(?:^| )$STRONG(?= |$)|^(?:(?:hey|hi|hello|ok|okay|yo) )*$WEAK(?= |$)")
    private val FILLER_START = Regex("^(?:(?:hey|hi|hello|ok|okay|yo)\\s+)+")

    fun norm(s: String): String = s.lowercase()
        .replace("\u2019", "").replace("'", "")
        // maths symbols between numbers survive as words ("2.5*4" -> "2 point 5 times 4"); a leading + (phone numbers) is untouched
        .replace(Regex("(?<=\\d)\\.(?=\\d)"), " point ")
        .replace(Regex("(?<=\\d)\\s*%"), " percent ")
        .replace(Regex("(?<=\\d)\\s*\\+\\s*(?=\\d)"), " plus ")
        .replace(Regex("(?<=\\d)\\s*[*\u00d7]\\s*(?=\\d)"), " times ")
        .replace(Regex("(?<=\\d)\\s*[/\u00f7]\\s*(?=\\d)"), " divided by ")
        .replace(Regex("(?<=\\d)\\s+[-\u2212]\\s+(?=\\d)"), " minus ")
        .replace(Regex("(?<=\\d)\\s*\\^\\s*(?=\\d)"), " power ")
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    fun hasWake(n: String) = WAKE.containsMatchIn(n)

    /** Removes the wake word and polite filler so "hey pragon please open youtube" -> "open youtube". */
    fun clean(n: String): String {
        var c = n
        c = WAKE.replace(c, " ")
        c = c.replace(Regex("\\s+"), " ").trim()
        c = FILLER_START.replace(c, "").trim()
        c = c.replace(Regex("^(?:(?:please|can you|could you|would you|will you|now|just|i want you to|i need you to)\\s+)+"), "")
        c = c.replace(Regex("(?:\\s+(?:please|now|for me))+$"), "")
        return c.trim()
    }

    /** Normalised + cleaned: the form the action matchers work on. */
    fun cleanCommand(text: String): String = clean(norm(text))

    private val RAW_WAKE = Regex(
        "^(?:(?:hey|hi|hello|ok|okay|yo)[,.!\\s]+)*(?:pragon|paragon|pragan|pragun|pragone|prakon|pergon|dragon|bragon)\\b[,.!?:\\s]*",
        RegexOption.IGNORE_CASE
    )
    /** Strips a leading "Hey Pragon," from the recognizer's raw text but keeps its capitals and punctuation. */
    fun stripWakeRaw(raw: String): String = RAW_WAKE.replace(raw.trim(), "").trim()

    // "I'm home" / "daddy's home" and nothing else, so "I am going home" can never trigger it.
    private val WAKE_PHRASE = Regex(
        "^(?:(?:hey|hi|ok|okay)\\s+)?(?:$STRONG\\s+)?(?:im|i m|i am|iam|daddys|daddy is|daddy|dady|dad is|dads|dad)\\s+(?:back\\s+)?home(?:\\s+(?:now|again|$STRONG))?$"
    )
    private val MUTE_PHRASE = Regex("\\b(?:mute|stop listening|be quiet|go silent|go to sleep)\\b")
    private val GOODBYE_PHRASE = Regex("\\b(?:goodbye|good bye|bye bye|bye|shut down|close yourself|go away)\\b")
    private val SELF_CLOSE = Regex(
        "^(?:(?:hey|ok|okay)\\s+)?(?:close|quit|exit|end|stop|shut down|kill)\\s+(?:the\\s+)?" +
            "(?:pragon|paragon|pragan|pragun|dragon|yourself|float mode|float|voice mode)$"
    )
    /** "close pragon" / "quit yourself" / "close float mode". Must be checked on the RAW text, before the wake word is stripped. */
    fun isSelfClose(text: String): Boolean {
        val n = norm(text)
        if (SELF_CLOSE.matches(n)) return true
        // "hey pragon close yourself": once the name is removed, the target must still be Pragon itself
        return hasWake(n) && Regex("^(?:close|quit|exit|end|stop|shut down|kill) (?:yourself|float mode|float|voice mode)$").matches(clean(n))
    }

    private val NOISE = setOf("um", "uh", "hmm", "mm", "mmm", "ah", "oh", "huh", "er", "erm")

    private fun words(n: String) = n.split(" ").size

    /**
     * @param alts the recognizer's alternatives, best first
     * @param active true when Float mode is listening for commands
     * @param mode current Pragon mode
     * @param needsWake when true, free-form conversation needs the word "Pragon"
     */
    fun parse(alts: List<String>, active: Boolean, mode: PMode = PMode.MASTER, needsWake: Boolean = false): Cmd {
        val norms = alts.map { norm(it) }.filter { it.isNotEmpty() }
        if (norms.isEmpty()) return Cmd.None

        // 0a. Typer mode (voice typing): everything you say is typed, except "close pragon" and "pragon goodbye".
        if (active && Typer.active) {
            val first = norms.first()
            if (SELF_CLOSE.matches(first)) return Cmd.Goodbye
            if (hasWake(first) && GOODBYE_PHRASE.containsMatchIn(first)) return Cmd.Goodbye
            return Cmd.Handle(stripWakeRaw(alts[0]).ifBlank { alts[0] })
        }

        // 0. "close pragon / close yourself / close float mode" must never become "close the current app"
        if (norms.any { SELF_CLOSE.matches(it) }) return Cmd.Goodbye

        // 1. control phrases. Without "pragon" the sentence must be short, so a long sentence from a video can't trigger them.
        for (n in norms) if (GOODBYE_PHRASE.containsMatchIn(n) && (hasWake(n) || words(n) <= 3)) return Cmd.Goodbye
        for (n in norms) if (MUTE_PHRASE.containsMatchIn(n) && (hasWake(n) || words(n) <= 4)) return Cmd.Mute
        for (n in norms) if (WAKE_PHRASE.matches(n)) return Cmd.Wake
        if (!active) return Cmd.None

        // 2. a mode switch or a phone action: first alternative that makes sense
        for ((i, n) in norms.withIndex()) {
            val c = clean(n)
            if (c.isEmpty()) continue
            if (modeSwitch(c) != null || voiceChange(c) != null || rawAction(n) != null || ollamaCommand(alts.getOrNull(i) ?: "") != null || actionFor(c) != null) {
                val raw = stripWakeRaw(alts.getOrNull(i) ?: c).ifBlank { c }
                return Cmd.Handle(raw)
            }
        }

        // 3. nothing matched: is it conversation?
        val first = norms.first()
        val c = clean(first)
        if (c.isEmpty() || c in NOISE) return Cmd.None
        val raw = stripWakeRaw(alts[0]).ifBlank { c }
        return when (mode) {
            PMode.AGENT -> if (hasWake(first) && c.length >= 3) Cmd.Handle(raw) else Cmd.None
            else -> {
                val addressed = hasWake(first)
                val ok = addressed || (!needsWake && words(c) <= 60)
                if (ok && c.length >= 2) Cmd.Handle(raw) else Cmd.None
            }
        }
    }

    // ── voice changer ────────────────────────────────────────────────────

    private val VOICE_NEXT = Regex(
        "^(?:(?:change|switch|swap|alter|shift|update)(?: to)?(?: (?:your|the|my|a))?(?: (?:new|different|another|other|next))? ?voice" +
            "|(?:use|try|give me|get)(?: (?:a|another|the))?(?: (?:new|different|other|next))? voice" +
            "|(?:next|new|different|another) voice|voice changer|sound different)(?: please)?$"
    )
    private val VOICE_RESET = Regex(
        "^(?:(?:reset|restore|revert)(?: (?:your|the))?(?: (?:voice|original voice|default voice))" +
            "|(?:use|go back to|back to)(?: (?:your|the))? (?:original|default|normal) voice|(?:original|default|normal) voice)(?: please)?$"
    )

    /** "change your voice" -> "next", "reset your voice" -> "reset", else null. */
    fun voiceChange(c: String): String? = when {
        VOICE_RESET.matches(c) -> "reset"
        VOICE_NEXT.matches(c) -> "next"
        else -> null
    }

    // ── mode switching ───────────────────────────────────────────────────

    private const val MODE_WORDS = "(assistant|assistance|master|mastermode|masters|muster|agent|asistant)"
    private val MODE_WITH_VERB = Regex(
        "^(?:(?:switch|change|go|set|turn|put|move|enable|activate|enter|use|be)(?: me| yourself| it| over)?(?: back)?" +
            "(?: in| into| to| on)?(?: the)? )$MODE_WORDS(?: mode)?(?: on| please)?$"
    )
    private val MODE_BARE = Regex("^(?:the )?$MODE_WORDS mode(?: on| please)?$")

    /** "agent mode", "switch to assistant mode", "go master" -> the new mode. */
    fun modeSwitch(c: String): PMode? {
        val m = MODE_WITH_VERB.matchEntire(c) ?: MODE_BARE.matchEntire(c) ?: return null
        return when (m.groupValues[1]) {
            "assistant", "assistance", "asistant" -> PMode.ASSISTANT
            "agent" -> PMode.AGENT
            else -> PMode.MASTER
        }
    }

    // ── phone actions ────────────────────────────────────────────────────

    private val PRAGON_SETTINGS = Regex(
        "^(?:(?:hey|hi|ok|okay)\\s+)?(?:please\\s+)?(?:open|show|go to|take me to)?\\s*(?:the\\s+)?" +
            "(?:pragon|paragon|pragan|pragun)(?:s)?\\s+settings$"
    )

    private val OLLAMA_PULL = Regex(
        "^(?:(?:hey|ok|okay)\\s+)?(?:(?:pragon|paragon)[,\\s]+)?(?:please\\s+)?" +
            "(?:ollama\\s+pull|pull(?:\\s+(?:the|an|a))?\\s+(?:ollama\\s+model|ollama|model)|download(?:\\s+(?:the|an|a))?(?:\\s+ollama)?\\s+model|install(?:\\s+(?:the|an|a))?(?:\\s+ollama)?\\s+model)" +
            "\\s+([A-Za-z0-9][A-Za-z0-9._:/@\\- ]{0,80}?)\\s*(?:please|model)?\\s*$", RegexOption.IGNORE_CASE
    )
    private val OLLAMA_LIST = Regex("^(?:(?:hey|ok|okay)\\s+)?(?:(?:pragon|paragon)[,\\s]+)?(?:ollama\\s+list|list(?:\\s+my)?\\s+(?:ollama\\s+)?models|(?:which|what)\\s+(?:ollama\\s+)?models(?:\\s+(?:do i have|are installed|are there))?)\\s*\\??$", RegexOption.IGNORE_CASE)
    private val OLLAMA_USE = Regex(
        "^(?:(?:hey|ok|okay)\\s+)?(?:(?:pragon|paragon)[,\\s]+)?(?:use|switch to|change to|select|choose)(?:\\s+(?:the|ollama))*\\s+model\\s+([A-Za-z0-9][A-Za-z0-9._:/@\\- ]{0,80}?)\\s*$", RegexOption.IGNORE_CASE
    )

    /** Model names as spoken ("llama 3 point 2") or typed ("llama3.2:3b") -> what Ollama wants. */
    private fun modelName(s: String): String =
        s.trim().lowercase().replace(" point ", ".").replace(" colon ", ":").replace(" dash ", "-").replace(" ", "")

    /** "ollama pull llama3.2:3b" / "ollama list" / "use model hermes3" on the RAW text (names keep their dots and colons). */
    fun ollamaCommand(raw: String): Action? {
        val t = raw.trim()
        OLLAMA_LIST.matches(t).let { if (it) return Action("ollama_list") }
        OLLAMA_PULL.matchEntire(t)?.let { return Action("ollama_pull", modelName(it.groupValues[1])) }
        OLLAMA_USE.matchEntire(t)?.let { return Action("ollama_use", modelName(it.groupValues[1])) }
        return null
    }

    /** Things that only make sense BEFORE the wake word is stripped: "pragon settings" opens Pragon's own settings. */
    fun rawAction(n: String): Action? =
        if (PRAGON_SETTINGS.matches(n)) Action("settings", "pragon") else null

    /** Finds a phone action in an already normalised + cleaned sentence, or null if it is not a command. */
    fun actionFor(c: String): Action? {
        if (c.isEmpty()) return null

        // ---- typer mode (voice typing): must come before "type ..." and "start ..." ----
        if (Typer.isStop(c)) return Action("typer", "off")
        if (Typer.isStart(c)) return Action("typer", "on")


        // ---- macros: must come before "play ..." / "stop ..." / "run ..." ----
        Regex("^(?:start |begin |create |make )?(?:a |an |the )?(?:new )?(?:record|recording)(?: a| an| new| the)?(?: macro)?(?: (?:called|named))?(?: (.+))?$").matchEntire(c)?.let {
            val n = it.groupValues[1].trim()
            if (c.contains("macro") || c.startsWith("start recording") || c.startsWith("begin recording") || c == "recording")
                return Action("macro_record", n.removePrefix("macro ").removePrefix("called ").removePrefix("named ").trim())
        }
        Regex("^(?:start|begin|create|make|record)(?: a| an| new)* macro(?: (?:called|named))?(?: (.+))?$").matchEntire(c)?.let {
            return Action("macro_record", it.groupValues[1].trim())
        }
        if (Regex("^(?:stop|end|finish|save|done)(?: the| this| my)? (?:recording|macro recording|macro)(?: it)?$|^(?:stop|end|finish|save) recording$|^save (?:the |this )?macro$").matches(c))
            return Action("macro_stop")
        if (Regex("^(?:cancel|abort|halt)(?: the| running| this)? macro(?: now)?$").matches(c)) return Action("macro_cancel")
        if (Regex("^(?:delete|remove|erase)(?: the| my)? macro (.+)$").matches(c))
            return Action("macro_delete", c.replace(Regex("^(?:delete|remove|erase)(?: the| my)? macro "), "").trim())
        if (Regex("^(?:list|show|what are|read)(?: me)?(?: my| the| all)? macros$|^what macros do i have$|^my macros$").matches(c)) return Action("macro_list")
        Regex("^(?:run|play|start|execute|do|replay|launch|trigger)(?: the| my)? macro (.+)$|^macro (.+)$").matchEntire(c)?.let {
            val n = it.groupValues[1].ifEmpty { it.groupValues[2] }.trim()
            if (n.isNotEmpty() && !n.startsWith("recording")) return Action("macro_run", n)
        }
        Regex("^(?:run|play|execute|replay|trigger) (.+) macro$").matchEntire(c)?.let { return Action("macro_run", it.groupValues[1].trim()) }

        // ---- volume / brightness / flashlight strength ----
        val up = "(?:increase|raise|turn up|turn it up|boost|higher|louder|brighten|brighter|bring up|crank up|pump up|up)"
        val down = "(?:decrease|reduce|lower|turn down|turn it down|dim|dimmer|quieter|softer|bring down|drop|cut|down)"
        val nounRe = "(volume|sound|audio|brightness|flash ?light|torch)"
        fun noun(n: String) = when {
            n.startsWith("flash") || n == "torch" -> "flashlight_level"
            n == "brightness" -> "brightness"
            else -> "volume"
        }
        Regex("^(?:please )?($up|$down) (?:the |my |phone |screen |media |music |ringtone )*$nounRe(?: brightness| level| strength| intensity)?(?: (by|to) (\\d{1,3})(?: ?percent)?)?$").matchEntire(c)?.let {
            val verb = it.groupValues[1]; val nn = it.groupValues[2]; val how = it.groupValues[3]; val amt = it.groupValues[4]
            val isUp = Regex("^$up$").matches(verb)
            val value = when {
                how == "to" && amt.isNotEmpty() -> "set:$amt"
                how == "by" && amt.isNotEmpty() -> (if (isUp) "+" else "-") + amt
                else -> if (isUp) "+" else "-"
            }
            return Action(noun(nn), value)
        }
        Regex("^$nounRe(?: brightness| level)? (up|down|higher|lower|brighter|dimmer|louder|quieter|max|maximum|min|minimum|full|half)$").matchEntire(c)?.let {
            val value = when (it.groupValues[2]) {
                "up", "higher", "brighter", "louder" -> "+"; "down", "lower", "dimmer", "quieter" -> "-"; "max", "maximum", "full" -> "max"; "min", "minimum" -> "min"; else -> "set:50"
            }
            return Action(noun(it.groupValues[1]), value)
        }
        Regex("^half (volume|sound|brightness|flash ?light|torch)$").matchEntire(c)?.let { return Action(noun(it.groupValues[1]), "set:50") }
        Regex("^(?:set|change|put|make|adjust)(?: the| my)? $nounRe(?: brightness| level)?(?: to| at) (\\d{1,3})(?: ?percent)?$|^$nounRe (\\d{1,3})(?: ?percent)?$|^(\\d{1,3}) ?percent $nounRe$").matchEntire(c)?.let {
            val g = it.groupValues
            val (nn, amt) = when {
                g[1].isNotEmpty() -> g[1] to g[2]
                g[3].isNotEmpty() -> g[3] to g[4]
                else -> g[6] to g[5]
            }
            return Action(noun(nn), "set:" + amt.toInt().coerceIn(0, 100))
        }
        Regex("^(?:set |turn |put )?(?:the )?(max|maximum|full|highest|maximi[sz]e|min|minimum|lowest|minimi[sz]e) $nounRe$|^$nounRe (?:to )?(max|maximum|full|min|minimum|lowest)$").matchEntire(c)?.let {
            val g = it.groupValues
            val w = g[1].ifEmpty { g[4] }; val nn = if (g[2].isNotEmpty()) g[2] else g[3]
            return Action(noun(nn), if (w.startsWith("max") || w == "full" || w == "highest") "max" else "min")
        }
        Regex("^(?:make )?(?:it |the screen |the phone |the sound |the music |the display )?(louder|quieter|softer|brighter|dimmer|darker)$").matchEntire(c)?.let {
            return when (it.groupValues[1]) {
                "louder" -> Action("volume", "+"); "quieter", "softer" -> Action("volume", "-")
                "brighter" -> Action("brightness", "+"); else -> Action("brightness", "-")
            }
        }
        if (Regex("^(?:dim|darken)(?: the)?(?: screen| display)?$").matches(c)) return Action("brightness", "-")
        if (Regex("^brighten(?: the)?(?: screen| display)?$").matches(c)) return Action("brightness", "+")
        Regex("^(?:turn |switch )?(on|off)? ?(?:auto|automatic|adaptive) brightness(?: (on|off))?$").matchEntire(c)?.let {
            val w = it.groupValues[1].ifEmpty { it.groupValues[2] }.ifEmpty { "on" }
            return Action("brightness", "auto:$w")
        }

        // ---- closing apps ----
        if (Regex("^(?:close|clear|kill|end|stop|clean)(?: out)?(?: all| every)?(?: the| my| open| background| recent| running)* apps$|^(?:clean|free up|clear)(?: up)?(?: my| the)? (?:memory|ram)$").matches(c))
            return Action("close_all")
        Regex("^force (?:stop|close|quit|kill)(?: (.+))?$|^(?:kill|stop|end) (.+) (?:completely|forcefully|forcibly|for good)$").matchEntire(c)?.let {
            val t = it.groupValues[1].ifEmpty { it.groupValues[2] }.trim().removePrefix("the ").removePrefix("app ").removeSuffix(" app").trim()
            if (t != "pragon" && t != "yourself") return Action("force_stop", t)
        }

        // ---- screen reading and questions about the screen (each needs "Let Pragon read my screen" in Settings) ----
        if (Regex("^(?:read|speak)(?: out)?(?: the| my| this)? (?:screen|page|text|display)(?: out loud| aloud| to me)?$|^read (?:this|it|that) (?:out loud|aloud|to me)$|^read (?:out loud|aloud)$").matches(c))
            return Action("screen_read")
        if (Regex("^(?:what(?:s| is)?(?: is)?(?: on| in| showing on)|describe|explain|summari[sz]e|what does)(?: the| my| this)? (?:screen|page|display)(?: say)?$|^what am i looking at$").matches(c))
            return Action("screen_ask", "Briefly say what is on this screen.")
        if (Regex("^(?:answer|solve)(?: the| this)? (?:question|problem|quiz|puzzle|mcq|sum)(?: (?:on|in|from)(?: the| my| this)? (?:screen|page))?$|^what(?:s| is) the answer(?: on(?: the| this)? screen)?$").matches(c))
            return Action("screen_ask", "Answer the question shown on the screen.")
        Regex("^(?:on|in|from|according to)(?: the| my| this)? (?:screen|page)[, ]+(.+)$|^(?:ask|question)(?: about)?(?: the| my| this)? screen[,: ]+(.+)$").matchEntire(c)?.let {
            return Action("screen_ask", it.groupValues[1].ifEmpty { it.groupValues[2] }.trim())
        }
        Regex("^((?:what|who|when|where|why|how|which|is|are|does|do|can|tell me|find|list).{3,}?) (?:on|in|from) (?:the|my|this) (?:screen|page)$").matchEntire(c)?.let {
            return Action("screen_ask", it.groupValues[1].trim())
        }

        // ---- security: scanner, firewall, privacy shield, allowed apps, sidebar, accessibility helpers ----
        if (Regex("^(?:scan|check|audit)(?: my| the)?(?: phone| apps| installed apps| device| mobile)(?: for (?:viruses|malware|threats|spyware|virus))?$|^(?:antivirus|anti virus|antivirus scan|anti virus scan|virus scan|malware scan|security scan|security check)$|^(?:scan|check) for (?:viruses|virus|malware|spyware|threats)$|^(?:run|start|do)(?: an?)? (?:antivirus|anti virus|virus|malware|security) (?:scan|check)$").matches(c))
            return Action("scan")
        Regex("^(?:turn |switch )?(on|off)? ?(?:the )?firewall(?: (on|off|status))?$|^(?:start|enable|activate) (?:the )?firewall$|^(?:stop|disable|deactivate) (?:the )?firewall$|^is (?:the )?firewall (?:on|running)$").matchEntire(c)?.let {
            val w = when {
                c.startsWith("start") || c.startsWith("enable") || c.startsWith("activate") -> "on"
                c.startsWith("stop") || c.startsWith("disable") || c.startsWith("deactivate") -> "off"
                c.startsWith("is ") -> "status"
                else -> it.groupValues[1].ifEmpty { it.groupValues[2] }.ifEmpty { "status" }
            }
            return Action("firewall", w)
        }
        Regex("^(?:turn |switch )?(on|off)? ?(?:the )?(?:privacy shield|privacy mode|privacy protection)(?: (on|off|status))?$|^(?:enable|activate|disable|deactivate) (?:the )?privacy(?: shield| mode)?$|^privacy(?: shield)?(?: status)?$|^(?:show|read)(?: me)?(?: the)? privacy (?:log|report)$|^(?:what|who) (?:did|have) you (?:connect|connected|talk|talked|send|sent)(?: to| data)?.*$").matchEntire(c)?.let {
            val w = when {
                c.contains("log") || c.contains("report") || c.startsWith("what ") || c.startsWith("who ") -> "log"
                c.startsWith("enable") || c.startsWith("activate") -> "on"
                c.startsWith("disable") || c.startsWith("deactivate") -> "off"
                else -> it.groupValues[1].ifEmpty { it.groupValues[2] }.ifEmpty { "status" }
            }
            return Action("privacy", w)
        }
        Regex("^(?:turn |switch )?(on|off) (?:the )?(?:allowed apps|app whitelist|app allowlist|allowed apps limit)$|^(?:enable|activate|disable|deactivate) (?:the )?(?:allowed apps|app whitelist)(?: limit)?$|^(?:allowed apps|app whitelist|app allowlist)(?: (on|off|status))?$|^only (?:open|allow) (?:my )?allowed apps$|^which apps are allowed$").matchEntire(c)?.let {
            val w = when {
                c.startsWith("only") -> "on"
                c.startsWith("enable") || c.startsWith("activate") -> "on"
                c.startsWith("disable") || c.startsWith("deactivate") -> "off"
                c.startsWith("which") -> "status"
                else -> it.groupValues[1].ifEmpty { it.groupValues[2] }.ifEmpty { "status" }
            }
            return Action("allowlist", w)
        }
        Regex("^(?:open|show|slide out|pull out|bring up|expand)(?: the| my)? (?:smart )?side ?bar$").matchEntire(c)?.let { return Action("sidebar", "open") }
        if (Regex("^(?:close|hide|collapse)(?: the| my)? (?:smart )?side ?bar$").matches(c)) return Action("sidebar", "close")
        if (Regex("^(?:turn off|switch off|disable|stop|deactivate|remove)(?: the| my)? (?:smart )?side ?bar$|^(?:smart )?side ?bar off$").matches(c)) return Action("sidebar", "off")
        if (Regex("^(?:turn on|switch on|enable|start|activate)(?: the| my)? (?:smart )?side ?bar$|^(?:smart )?side ?bar on$|^(?:smart )?side ?bar$").matches(c)) return Action("sidebar", "on")
        Regex("^(?:turn|switch) (on|off) (?:the )?(talk ?back|screen reader|live captions?|captions?|select to speak|text to speech|tts)$|^(enable|disable|start|stop|activate|deactivate) (?:the )?(talk ?back|screen reader|live captions?|select to speak|text to speech)$|^(talk ?back|live captions?|select to speak) (on|off)$").matchEntire(c)?.let {
            val g = it.groupValues
            val name = (g[2].ifEmpty { g[4] }.ifEmpty { g[5] })
            val on = when {
                g[1].isNotEmpty() -> g[1] == "on"
                g[3].isNotEmpty() -> g[3] in setOf("enable", "start", "activate")
                else -> g[6] == "on"
            }
            val feat = when {
                name.startsWith("talk") || name == "screen reader" -> "talkback"
                name.contains("caption") -> "caption"
                else -> "tts"
            }
            return Action("a11y_feature", feat + ":" + (if (on) "on" else "off"))
        }

        // ---- calculator: "calculate 25 times 4", "what is 15 percent of 200", "25 times 4 on the calculator" ----
        Calc.fromSpeech(c)?.let { r -> return Action(if (r is Calc.Result && r.onScreen) "calc_screen" else "calc", c) }

        // ---- stopwatch ----
        if (Regex("^(?:start|begin|run|open|launch|turn on)(?: the| a| my)? stop ?watch(?: please)?$|^stop ?watch(?: start| on)$").matches(c))
            return Action("stopwatch", "start")
        if (Regex("^(?:stop|pause|halt|end|freeze)(?: the| my)? stop ?watch$|^stop ?watch (?:stop|pause|off)$").matches(c))
            return Action("stopwatch", "stop")
        if (Regex("^(?:resume|continue|unpause)(?: the| my)? stop ?watch$").matches(c)) return Action("stopwatch", "start")
        if (Regex("^(?:reset|clear|zero)(?: the| my)? stop ?watch$|^stop ?watch reset$").matches(c)) return Action("stopwatch", "reset")
        if (Regex("^restart(?: the| my)? stop ?watch$").matches(c)) return Action("stopwatch", "restart")
        if (Regex("^(?:lap|mark (?:a )?lap|take (?:a )?lap|stop ?watch lap|new lap|record (?:a )?lap)$").matches(c)) return Action("stopwatch", "lap")
        if (Regex("^(?:what(?:s| is)? )?(?:the )?stop ?watch (?:time|status|reading)$|^how long(?: has)?(?: the)? stop ?watch.*$|^check(?: the)? stop ?watch$|^what(?:s| is) on(?: the)? stop ?watch$")
                .matches(c)) return Action("stopwatch", "status")
        if (Regex("^(?:the |my )?stop ?watch$").matches(c)) return Action("stopwatch", "open")

        // ---- battery reminders ----
        Regex("^(?:(?:turn|switch) (on|off) |(enable|disable) )?(?:the |my )?(?:low )?battery (?:reminders?|alerts?|warnings?|notifications?)(?: (on|off))?$").matchEntire(c)?.let {
            val g = it.groupValues
            val v = when {
                g[1].isNotEmpty() -> g[1]
                g[2] == "enable" -> "on"
                g[2] == "disable" -> "off"
                g[3].isNotEmpty() -> g[3]
                else -> "status"
            }
            return Action("battery_alert", v)
        }
        if (Regex("^(?:cancel|clear|delete|remove|stop)(?: all| my| the)* battery (?:reminders?|alerts?)$").matches(c)) return Action("battery_alert", "clear")
        Regex("^(?:remind|notify|alert|tell|warn|let) me (?:when|if|once|as soon as|at)?\\s*(?:the |my )?(?:battery|charge)(?: level| percentage)?(?: is| reaches| gets to| hits| goes to| drops to| falls to| becomes| at| is at| to)?\\s*(\\d{1,3})(?: percent)?$").matchEntire(c)?.let {
            return Action("battery_remind", it.groupValues[1])
        }
        Regex("^(?:remind|notify|alert|tell|warn|let) me (?:when|if|once|at) (\\d{1,3})(?: percent)?(?: battery| charge)?$|^battery (?:reminder|alert) (?:at|for|when) (\\d{1,3})(?: percent)?$").matchEntire(c)?.let {
            return Action("battery_remind", it.groupValues[1].ifEmpty { it.groupValues[2] })
        }
        Regex("^(?:remind|notify|alert|tell|warn|let) me to (?:unplug|disconnect|remove)(?: the| my)?(?: charger| phone| cable)?(?: (?:at|when it reaches|when its at|when battery is|when the battery reaches|at battery) (\\d{1,3})(?: percent)?)?$").matchEntire(c)?.let {
            return Action("battery_unplug", it.groupValues[1].ifEmpty { "100" })
        }
        if (Regex("^(?:remind|notify|alert|tell|warn|let) me when (?:the |my )?(?:battery|phone|charge|charging)(?: is| gets| reaches)?(?: fully)? (?:full|charged|complete|done)$").matches(c))
            return Action("battery_unplug", "100")
        Regex("^(?:charge|charging|unplug) (?:reminder|alert)(?: (?:at|for))? (\\d{1,3})(?: percent)?$").matchEntire(c)?.let {
            return Action("battery_unplug", it.groupValues[1])
        }

        // ---- phone settings (opens the right screen; Android doesn't let apps flip Wi-Fi/Bluetooth themselves) ----
        if (Regex("^(?:open |show |go to |take me to )?(?:the )?(?:your|app|assistant) settings$").matches(c)) return Action("settings", "pragon")
        Regex("^(?:open |show |go to |take me to )?(?:the )?(?:phone |system |device )?(wi ?fi|bluetooth|display|brightness|sounds?|volume|battery|location|gps|apps|accessibility|notifications?|developer(?: options)?|airplane mode|airplane|flight mode|mobile data|data|network|storage|date(?: and time)?|time|languages?|nfc|security|privacy|hotspot|vpn|do not disturb|dnd|keyboard|wallpaper|about phone|about) (?:settings|options|menu|page|screen)$").matchEntire(c)?.let {
            return Action("settings", it.groupValues[1])
        }
        if (Regex("^(?:open |show )?(?:the )?(?:phone |system |device )settings$").matches(c)) return Action("settings", "main")
        Regex("^(?:turn |switch )(on|off) (?:the )?(wi ?fi|bluetooth|mobile data|hotspot|airplane mode|nfc|location|gps)$|^(wi ?fi|bluetooth|mobile data|hotspot|airplane mode|nfc|location|gps) (on|off)$").matchEntire(c)?.let {
            val what = it.groupValues[2].ifEmpty { it.groupValues[3] }
            return Action("settings", what)
        }

        // ---- click / tap (by voice: "click", "click subscribe", "tap on search", "double tap") ----
        if (Regex("^(?:double|2|two) ?(?:click|tap|clicks|taps)$").matches(c)) return Action("double_click")
        if (Regex("^(?:click|tap|touch)(?: here| it| now| there| the screen| on screen| on the screen)?$").matches(c)) return Action("click", "")
        Regex("^(?:click|tap|touch|press|select|hit)(?: on| the| button| icon)* (.+?)(?: button| icon| option| tab)?$").matchEntire(c)?.let {
            val target = it.groupValues[1].trim().removePrefix("the ").trim()
            if (target.isEmpty() || words(target) > 5) return@let
            return when (target) {
                "home", "home button" -> Action("key", "home")
                "back", "back button" -> Action("key", "back")
                "recents", "recent apps", "recent" -> Action("key", "recents")
                "volume up" -> Action("key", "volume_up")
                "volume down" -> Action("key", "volume_down")
                "play" -> Action("key", "play")
                "pause" -> Action("key", "pause")
                "screenshot" -> Action("key", "screenshot")
                else -> Action("click", target)
            }
        }

        // ---- phone buttons ----
        if (Regex("^(?:go )?(?:to )?(?:the )?home(?: screen)?$|^go to home$").matches(c)) return Action("key", "home")
        if (Regex("^(?:go )?back$").matches(c)) return Action("key", "back")
        if (Regex("^(?:show |open )?(?:the )?recents?(?: apps?)?$").matches(c)) return Action("key", "recents")
        if (Regex("^(?:show |open |pull down )?(?:the )?notifications?(?: panel| shade)?$").matches(c)) return Action("key", "notifications")
        if (Regex("^(?:open )?quick settings$").matches(c)) return Action("key", "quick_settings")
        if (Regex("^(?:lock|lock the|lock my)(?: phone| screen)?$|^turn off (?:the )?screen$").matches(c)) return Action("key", "lock")
        if (Regex("^(?:take (?:a )?|capture (?:the )?)?screen ?shot$|^capture (?:the )?screen$").matches(c)) return Action("key", "screenshot")
        if (Regex("^(?:phone )?(?:status|battery(?: level| status| percentage)?)$|^how much battery.*$").matches(c)) return Action("status")

        // ---- flashlight ----
        Regex("^(?:turn |switch )?(on|off)?\\s?(?:the |my )?(?:flash ?light|torch)(?: (on|off))?$").matchEntire(c)?.let {
            val v = it.groupValues[1].ifEmpty { it.groupValues[2] }.ifEmpty { "toggle" }
            return Action("flashlight", v)
        }

        // ---- volume / media ----
        if (Regex("^(?:volume up|louder|increase (?:the )?volume|raise (?:the )?volume|turn (?:it|the volume) up)$").matches(c))
            return Action("key", "volume_up")
        if (Regex("^(?:volume down|quieter|lower (?:the )?volume|decrease (?:the )?volume|turn (?:it|the volume) down)$").matches(c))
            return Action("key", "volume_down")
        // Play and pause are two different keys: "pause" never starts playback and "play" never pauses.
        if (Regex("^(?:pause|stop)(?: the| this| my)?(?: video| music| song| it| playback| audio| track| reel)?$").matches(c))
            return Action("key", "pause")
        if (Regex("^(?:play|resume|unpause)(?: the| this| my)?(?: video| music| song| it| playback| audio| track| reel)?$").matches(c))
            return Action("key", "play")
        if (Regex("^(?:play pause|play or pause|toggle (?:play|playback)|pause or play)$").matches(c))
            return Action("key", "play_pause")
        if (Regex("^next(?: video| song| track)?$|^skip(?: this)?(?: video| song| track)?$").matches(c)) return Action("key", "next")
        if (Regex("^(?:previous|prev|last)(?: video| song| track)?$|^go to previous$").matches(c)) return Action("key", "previous")

        // ---- scrolling ----
        Regex("^(scroll|swipe) (up|down|left|right)$").matchEntire(c)?.let {
            val verb = it.groupValues[1]
            val dir = it.groupValues[2]
            // "scroll down" = move the content up = swipe up
            val swipe = if (verb == "scroll") when (dir) { "down" -> "up"; "up" -> "down"; else -> dir } else dir
            return Action("swipe", swipe)
        }

        // ---- timer / alarm ----
        if (Regex("^(?:cancel|delete|dismiss|remove|stop|clear)(?: the| my| all)* timers?$").matches(c)) return Action("timer_cancel")
        if (Regex("^(?:show|open|list|view|see)(?: me)?(?: my| the| all)* timers?$").matches(c)) return Action("timer_show")
        Regex("^(?:set |start |create |add )?(?:a |an )?timer (?:for |of )?(.+)$").matchEntire(c)?.let {
            val s = durationSeconds(it.groupValues[1]) ?: return@let
            return Action("timer", s.toString())
        }
        // "5 minute timer", "set a 10 minute timer", "set an hour timer"
        Regex("^(?:set |start |create |add )?(?:a |an )?(.+?) timer$").matchEntire(c)?.let {
            val s = durationSeconds(it.groupValues[1]) ?: return@let
            return Action("timer", s.toString())
        }
        if (Regex("^(?:cancel|delete|dismiss|remove|turn off|stop|clear)(?: the| my| next| all)* alarms?$").matches(c)) return Action("alarm_cancel")
        if (Regex("^(?:show|open|list|view|see)(?: me)?(?: my| the| all)* alarms?$").matches(c)) return Action("alarm_show")
        // "alarm in 30 minutes", "wake me up in 2 hours"
        Regex("^(?:set |create |add )?(?:an? )?alarm (?:in|after) (.+)$|^wake me (?:up )?in (.+)$").matchEntire(c)?.let {
            val s = durationSeconds(it.groupValues[1].ifEmpty { it.groupValues[2] }) ?: return@let
            return Action("alarm", "in:$s")
        }
        // "set alarm for 7 30 am", "alarm at 6", "set an alarm 7 am", "wake me up at 5 30"
        Regex("^(?:set |create |add )?(?:an? )?alarm(?: for| at)? (.+)$|^wake me (?:up )?at (.+)$").matchEntire(c)?.let {
            val raw = it.groupValues[1].ifEmpty { it.groupValues[2] }.replace(Regex("\\s+(?:tomorrow|today|morning|tonight)$"), "").trim()
            val t = clockTime(raw) ?: return@let
            return Action("alarm", t)
        }

        // ---- YouTube / web search ----
        Regex("^(?:search|find|look up) (?:for )?(.+?) (?:on|in) youtube$").matchEntire(c)?.let { return Action("youtube_search", it.groupValues[1]) }
        Regex("^play (.+?) (?:on|in) youtube$").matchEntire(c)?.let { return Action("youtube_search", it.groupValues[1]) }
        Regex("^(?:search youtube|youtube search|search youtube for|youtube) (?:for )?(.+)$").matchEntire(c)?.let { return Action("youtube_search", it.groupValues[1]) }
        Regex("^(?:google|search google for|search for|search|look up) (.+)$").matchEntire(c)?.let { return Action("web_search", it.groupValues[1]) }
        // "play despacito" -> YouTube (the bare "play"/"play music" cases were handled above)
        Regex("^play (.{2,60})$").matchEntire(c)?.let { return Action("youtube_search", it.groupValues[1], soft = true) }

        // ---- call / type ----
        Regex("^(?:call|dial|phone) (?:number )?([0-9+*# ]{3,})$").matchEntire(c)?.let { return Action("call", it.groupValues[1].replace(" ", "")) }
        Regex("^type (.+)$").matchEntire(c)?.let { return Action("type_text", it.groupValues[1]) }

        // ---- close an app ("close youtube", "close this app", "close") ----
        Regex("^(?:close|quit|exit|kill|terminate)(?: (.*))?$").matchEntire(c)?.let {
            val target = it.groupValues[1].trim().removePrefix("the ").removePrefix("app ").removeSuffix(" app").trim()
            if (target == "yourself" || target == "float mode" || target == "float" || target == "pragon") return null
            return Action("close_app", target)
        }
        // "shut down X" / "end X" only with a target, never alone
        Regex("^(?:shut down|shut|end) (.+)$").matchEntire(c)?.let {
            val target = it.groupValues[1].trim().removePrefix("the ").removePrefix("app ").removeSuffix(" app").trim()
            if (target.isEmpty() || target in setOf("yourself", "float mode", "float", "pragon", "it", "this", "down")) return null
            return Action("close_app", target)
        }

        // ---- open an app or a site ("open instagram", "open youtube.com") ----
        Regex("^(open|launch|start|run|go to|switch to|take me to|show me)(?: the)?(?: app)? (.+?)(?: app)?$").matchEntire(c)?.let {
            val verb = it.groupValues[1]
            val target = it.groupValues[2].trim()
            if (target.isEmpty() || words(target) > 4) return null   // a whole sentence, not an app name
            val soft = verb != "open" && verb != "launch"
            if (Regex("^[a-z0-9-]+ (?:dot )?(?:com|org|net|in|io)$").matches(target)) {
                return Action("open_url", target.replace(" dot ", ".").replace(" ", "."))
            }
            return Action("open_app", target, soft)
        }
        return null
    }

    // ── helpers for timer / alarm ────────────────────────────────────────

    private val NUM_WORDS = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "fifteen" to 15, "twenty" to 20, "thirty" to 30, "forty five" to 45,
        "forty" to 40, "fifty" to 50, "sixty" to 60,
    )

    /** "5 minutes", "1 hour 30 minutes", "half an hour", "90 seconds", "10" (= minutes) -> seconds, or null. */
    fun durationSeconds(s0: String): Int? {
        val s = s0.trim()
        if (s.isEmpty()) return null
        if (Regex("^half an hour$|^half hour$").matches(s)) return 1800
        var total = 0
        var found = false
        val re = Regex("(\\d+(?: point \\d+)?|forty five|[a-z]+) ?(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)\\b")
        for (m in re.findAll(s)) {
            val g = m.groupValues[1]
            val n: Double = (if (g.contains(" point ")) g.replace(" point ", ".").toDoubleOrNull() else g.toDoubleOrNull())
                ?: NUM_WORDS[g]?.toDouble() ?: continue
            val u = m.groupValues[2]
            total += (when {
                u.startsWith("h") -> n * 3600
                u.startsWith("m") -> n * 60
                else -> n
            }).toInt()
            found = true
        }
        if (!found) {
            val bare = Regex("^(\\d+)$").matchEntire(s)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            total = bare * 60
            found = true
        }
        return if (found && total in 1..86399) total else null
    }

    /** "7 30 pm", "7am", "19 45", "7" -> "HH:MM" (24 h), or null. */
    fun clockTime(s0: String): String? {
        val s = s0.trim().replace(" point ", " ")
        val m = Regex("^(\\d{1,2})(?: ?(\\d{2}))?(?: ?(a m|p m|am|pm))?(?: oclock)?$").matchEntire(s) ?: return null
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        val ap = m.groupValues[3].replace(" ", "")
        if (min > 59 || h > 23) return null
        if (ap == "pm" && h < 12) h += 12
        if (ap == "am" && h == 12) h = 0
        return "%02d:%02d".format(h, min)
    }
}
