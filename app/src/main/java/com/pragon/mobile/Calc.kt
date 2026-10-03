package com.pragon.mobile

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Spoken / typed arithmetic: "calculate 25 times 4", "what is 15 percent of 200", "square root of 144",
 * "two lakh plus 50 thousand". No Android classes in here so it can be tested on a plain JVM.
 * Input is the normalised command (lowercase, letters/digits/spaces; VoiceParser.norm turns "2.5" into "2 point 5").
 */
object Calc {

    class Result(
        /** "25 × 4 = 100" - for the chat bubble. */
        val display: String,
        /** "25 times 4 is 100" - for the voice. */
        val spoken: String,
        val value: Double,
        /** The expression as keys for a phone calculator ("25*4"), or null when it needs functions a basic calculator lacks. */
        val keys: String?,
        /** True when the user asked to do it ON the calculator app ("... on the calculator"). */
        val onScreen: Boolean,
    )

    /** A calculation that can't be done (divide by zero etc.). */
    class Failure(val msg: String)

    private val PREFIX = Regex(
        "^(?:(?:please )?(?:calculate|calculator|calc|compute|solve|evaluate|work out|find out|find|tell me|what is|what s|whats|how much is|how much|use (?:the )?calculator (?:to )?(?:calculate|solve|do|find)?|do))\\s+"
    )
    private val ON_SCREEN_SUFFIX = Regex("\\s+(?:on|in|using|with|via|into) (?:the |my )?(?:phone )?(?:calculator|calc)$")
    private val ON_SCREEN_PREFIX = Regex("^(?:(?:open|use|type|enter|put|punch)(?: it)?(?: in| into| on)? (?:the )?(?:phone )?(?:calculator|calc)(?: and| to)?)\\s+(?:calculate |solve |compute |do |type |enter )?")

    /**
     * Returns a Result when [c] is a calculation, a Failure when it is one but can't be done, or null when it is
     * not maths at all (so the sentence can go on to the other matchers / the AI).
     */
    fun fromSpeech(c0: String): Any? {
        var c = c0.trim()
        if (c.isEmpty()) return null
        var onScreen = false
        ON_SCREEN_PREFIX.find(c)?.let { onScreen = true; c = c.substring(it.range.last + 1).trim() }
        ON_SCREEN_SUFFIX.find(c)?.let { onScreen = true; c = c.substring(0, it.range.first).trim() }
        PREFIX.find(c)?.let { c = c.substring(it.range.last + 1).trim() }
        c = c.removePrefix("the ").removePrefix("answer to ").removePrefix("result of ").removePrefix("value of ").trim()
        c = c.replace(Regex("\\s+(?:equals?|is equal to|is)$"), "").trim()
        if (c.isEmpty()) return null

        val toks = tokenize(c) ?: return null
        // Needs at least one number, and at least one operator unless it is "square root of 9" style.
        val hasNum = toks.any { it is Tok.Num }
        val hasOp = toks.any { it !is Tok.Num }
        if (!hasNum || !hasOp) return null
        // A bare sentence must be ONLY maths. With "calculate/what is" in front the same rule applies,
        // because the tokenizer rejects unknown words, so "what is the capital of France" never gets here.
        val p = Parser(toks)
        val v = try { p.parseAll() } catch (e: ArithmeticException) { return Failure(e.message ?: "That can't be calculated.") } catch (e: IllegalStateException) { return null }
        if (v.isNaN() || v.isInfinite()) return Failure("That doesn't have a real answer.")
        val answer = format(v)
        val words = toks.joinToString(" ") { it.spoken() }
        val shown = toks.joinToString(" ") { it.shown() }
        return Result(
            display = "$shown = $answer",
            spoken = "$words is $answer",
            value = v,
            keys = keysFor(toks),
            onScreen = onScreen,
        )
    }

    /** True when the sentence is clearly a calculation (used by the parser to claim it before the AI sees it). */
    fun looksLikeMath(c: String): Boolean = fromSpeech(c) != null

    // ── formatting ───────────────────────────────────────────────────────

    fun format(v: Double): String {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return v.toLong().toString()
        var bd = BigDecimal(v).round(MathContext(10, RoundingMode.HALF_UP)).stripTrailingZeros()
        if (bd.scale() > 8) bd = bd.setScale(8, RoundingMode.HALF_UP).stripTrailingZeros()
        return bd.toPlainString()
    }

    // ── tokens ───────────────────────────────────────────────────────────

    private sealed class Tok {
        class Num(val v: Double, val text: String) : Tok()
        class Op(val sym: Char, val word: String) : Tok()          // + - * / ^ %
        object PercentOf : Tok()
        object Percent : Tok()
        object Sqrt : Tok()
        object Cbrt : Tok()
        object Sq : Tok()
        object Cube : Tok()
        object LParen : Tok()
        object RParen : Tok()

        fun spoken(): String = when (this) {
            is Num -> text
            is Op -> word
            PercentOf -> "percent of"
            Percent -> "percent"
            Sqrt -> "square root of"
            Cbrt -> "cube root of"
            Sq -> "squared"
            Cube -> "cubed"
            LParen -> "open bracket"
            RParen -> "close bracket"
        }
        fun shown(): String = when (this) {
            is Num -> text
            is Op -> when (sym) { '*' -> "×"; '/' -> "÷"; else -> sym.toString() }
            PercentOf -> "% of"
            Percent -> "%"
            Sqrt -> "√"
            Cbrt -> "∛"
            Sq -> "²"
            Cube -> "³"
            LParen -> "("
            RParen -> ")"
        }
    }

    private val UNITS = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
        "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
    )
    private val TENS = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
    )
    private val SCALES = mapOf(
        "thousand" to 1_000.0, "lakh" to 100_000.0, "lakhs" to 100_000.0, "lac" to 100_000.0, "million" to 1_000_000.0,
        "crore" to 10_000_000.0, "crores" to 10_000_000.0, "billion" to 1_000_000_000.0,
    )
    private val IGNORE = setOf("the", "a", "an", "of", "and", "is", "equals", "equal", "to", "please")

    private fun isNumWord(w: String) = w in UNITS || w in TENS || w == "hundred" || w in SCALES

    /** Turns the sentence into tokens, or null if it contains words that aren't maths (so it's not a calculation). */
    private fun tokenize(s: String): List<Tok>? {
        val w = s.split(" ").filter { it.isNotEmpty() }
        val out = ArrayList<Tok>()
        var i = 0
        fun rest(n: Int) = w.drop(i).take(n).joinToString(" ")
        while (i < w.size) {
            val t = w[i]
            // numbers: digits, "2 point 5", "twenty five", "5 lakh"
            if (t.all { it.isDigit() } || isNumWord(t)) {
                val (num, used) = readNumber(w, i) ?: return null
                out.add(num); i += used; continue
            }
            // multi-word operators first
            when {
                rest(4) == "raised to the power of" -> { out.add(Tok.Op('^', "to the power of")); i += 5; continue }
                rest(4) == "to the power of" -> { out.add(Tok.Op('^', "to the power of")); i += 4; continue }
                rest(3) == "to the power" -> { out.add(Tok.Op('^', "to the power of")); i += 3; continue }
                rest(2) == "raised to" -> { out.add(Tok.Op('^', "to the power of")); i += 2; continue }
                rest(3) == "cube root of" -> { out.add(Tok.Cbrt); i += 3; continue }
                rest(3) == "square root of" -> { out.add(Tok.Sqrt); i += 3; continue }
                rest(2) == "square root" -> { out.add(Tok.Sqrt); i += 2; continue }
                rest(2) == "root of" -> { out.add(Tok.Sqrt); i += 2; continue }
                rest(2) == "multiplied by" || rest(2) == "multiply by" -> { out.add(Tok.Op('*', "times")); i += 2; continue }
                rest(2) == "divided by" || rest(2) == "divide by" -> { out.add(Tok.Op('/', "divided by")); i += 2; continue }
                rest(2) == "percent of" || rest(2) == "percentage of" -> { out.add(Tok.PercentOf); i += 2; continue }
                rest(2) == "per cent" -> { out.add(Tok.Percent); i += 2; continue }
                rest(2) == "open bracket" || rest(2) == "left bracket" || rest(2) == "opening bracket" -> { out.add(Tok.LParen); i += 2; continue }
                rest(2) == "close bracket" || rest(2) == "right bracket" || rest(2) == "closing bracket" -> { out.add(Tok.RParen); i += 2; continue }
                rest(2) == "open parenthesis" || rest(2) == "left parenthesis" -> { out.add(Tok.LParen); i += 2; continue }
                rest(2) == "close parenthesis" || rest(2) == "right parenthesis" -> { out.add(Tok.RParen); i += 2; continue }
            }
            when (t) {
                "plus", "add", "added" -> { out.add(Tok.Op('+', "plus")); if (t == "added" && w.getOrNull(i + 1) == "to") i++ }
                "minus", "subtract", "less", "negative" -> out.add(Tok.Op('-', "minus"))
                "times", "into", "multiply", "multiplied", "x", "star" -> out.add(Tok.Op('*', "times"))
                "over", "upon", "divide", "divided" -> out.add(Tok.Op('/', "divided by"))
                "power", "pow", "caret" -> out.add(Tok.Op('^', "to the power of"))
                "mod", "modulo", "remainder" -> out.add(Tok.Op('%', "mod"))
                "percent", "percentage" -> out.add(Tok.Percent)
                "squared", "square" -> out.add(Tok.Sq)
                "cubed", "cube" -> out.add(Tok.Cube)
                "sqrt", "root" -> out.add(Tok.Sqrt)
                "by" -> { /* "divided by" split oddly, or "5 by 3": ignore the bare word */ }
                else -> if (t !in IGNORE) return null
            }
            i++
        }
        // drop leading/trailing filler operators like "to"
        return out
    }

    /** Reads one number starting at w[start]. Returns (token, words used). */
    private fun readNumber(w: List<String>, start: Int): Pair<Tok.Num, Int>? {
        // last: 0 none, 1 digits, 2 unit word, 3 tens word, 4 hundred, 5 scale word
        var i = start
        var total = 0.0
        var cur = 0.0
        var last = 0
        while (i < w.size) {
            val t = w[i]
            val u = UNITS[t]
            val tn = TENS[t]
            if (t.isNotEmpty() && t.all { it.isDigit() }) {
                if (last == 0 || last == 5) { cur = t.toDouble(); last = 1; i++ } else break
            } else if (u != null) {
                val ok = last == 0 || last == 4 || last == 5 || (last == 3 && u in 1..9)
                if (!ok) break
                cur += u; last = 2; i++
            } else if (tn != null) {
                if (last == 0 || last == 4 || last == 5) { cur += tn; last = 3; i++ } else break
            } else if (t == "hundred") {
                if (last == 0) { cur = 100.0; last = 4; i++ }
                else if (last == 1 || last == 2 || last == 3) { cur *= 100.0; last = 4; i++ }
                else break
            } else if (t == "and" && (last == 4 || last == 5) && i + 1 < w.size && (w[i + 1] in UNITS || w[i + 1] in TENS)) {
                i++   // "one hundred and five"
            } else if (t in SCALES) {
                if (last == 0) break
                total += (if (cur == 0.0) 1.0 else cur) * SCALES[t]!!
                cur = 0.0; last = 5; i++
            } else break
        }
        if (last == 0) return null
        var value = total + cur
        // decimals: "point" followed by digits / digit words ("2 point 5", "two point five", "3 point 14")
        if (i + 1 < w.size && w[i] == "point") {
            val frac = StringBuilder()
            var j = i + 1
            while (j < w.size) {
                val d = w[j]
                if (d.isNotEmpty() && d.all { it.isDigit() } && frac.isEmpty()) { frac.append(d); j++; break }
                val dv = UNITS[d]?.takeIf { it in 0..9 } ?: break
                frac.append(dv); j++
            }
            if (frac.isNotEmpty()) { value += ("0." + frac).toDouble(); i = j }
        }
        return Tok.Num(value, format(value)) to (i - start)
    }

    // ── keys for a phone calculator ──────────────────────────────────────

    private fun keysFor(toks: List<Tok>): String? {
        val sb = StringBuilder()
        for (t in toks) when (t) {
            is Tok.Num -> sb.append(format(t.v))
            is Tok.Op -> if (t.sym == '+' || t.sym == '-' || t.sym == '*' || t.sym == '/') sb.append(t.sym) else return null
            Tok.LParen -> sb.append('(')
            Tok.RParen -> sb.append(')')
            Tok.Percent -> sb.append('%')
            else -> return null
        }
        return sb.toString()
    }

    // ── parser (recursive descent) ───────────────────────────────────────

    private class Parser(val t: List<Tok>) {
        var p = 0
        var lastWasPercent = false

        fun parseAll(): Double {
            val v = expr()
            if (p != t.size) throw IllegalStateException("leftover")
            return v
        }

        private fun peek(): Tok? = t.getOrNull(p)

        private fun expr(): Double {
            var left = term()
            while (true) {
                val o = peek()
                if (o is Tok.Op && (o.sym == '+' || o.sym == '-')) {
                    p++
                    lastWasPercent = false
                    var right = term()
                    // "200 plus 10 percent" -> 220 (the percent is of the left side, like a pocket calculator)
                    if (lastWasPercent) right = left * right
                    left = if (o.sym == '+') left + right else left - right
                    lastWasPercent = false
                } else break
            }
            return left
        }

        private fun term(): Double {
            var left = power()
            while (true) {
                val o = peek()
                if (o is Tok.Op && (o.sym == '*' || o.sym == '/' || o.sym == '%')) {
                    p++
                    val r = power()
                    left = when (o.sym) {
                        '*' -> left * r
                        '/' -> { if (r == 0.0) throw ArithmeticException("I can't divide by zero."); left / r }
                        else -> { if (r == 0.0) throw ArithmeticException("I can't divide by zero."); left % r }
                    }
                    lastWasPercent = false
                } else if (o === Tok.PercentOf) {
                    p++
                    val r = power()
                    left = left / 100.0 * r
                    lastWasPercent = false
                } else if (o is Tok.Num || o === Tok.LParen || o === Tok.Sqrt || o === Tok.Cbrt) {
                    // "2 (3 + 4)" or "2 square root of 9": implicit multiply
                    if (o is Tok.Num) break
                    val r = power()
                    left *= r
                } else break
            }
            return left
        }

        private fun power(): Double {
            val base = unary()
            val o = peek()
            if (o is Tok.Op && o.sym == '^') {
                p++
                val e = power()
                return Math.pow(base, e)
            }
            return base
        }

        private fun unary(): Double {
            val o = peek()
            if (o is Tok.Op && o.sym == '-') { p++; return -unary() }
            if (o is Tok.Op && o.sym == '+') { p++; return unary() }
            if (o === Tok.Sqrt) {
                p++
                val v = unary()
                if (v < 0) throw ArithmeticException("There's no real square root of a negative number.")
                return Math.sqrt(v)
            }
            if (o === Tok.Cbrt) { p++; return Math.cbrt(unary()) }
            return postfix()
        }

        private fun postfix(): Double {
            var v = primary()
            while (true) {
                val o = peek()
                when {
                    o === Tok.Sq -> { p++; v *= v; lastWasPercent = false }
                    o === Tok.Cube -> { p++; v = v * v * v; lastWasPercent = false }
                    o === Tok.Percent -> { p++; v /= 100.0; lastWasPercent = true }
                    else -> return v
                }
            }
        }

        private fun primary(): Double {
            val o = peek() ?: throw IllegalStateException("end")
            return when (o) {
                is Tok.Num -> { p++; o.v }
                Tok.LParen -> {
                    p++
                    val v = expr()
                    if (peek() === Tok.RParen) p++
                    v
                }
                else -> throw IllegalStateException("unexpected")
            }
        }
    }
}
