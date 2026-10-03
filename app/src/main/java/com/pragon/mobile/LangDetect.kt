package com.pragon.mobile

import java.util.Locale

/**
 * Picks a voice language from the script of the text (ported from the PC's language_detect.py, which does
 * the same Unicode-block check for Tamil, Telugu and Hindi, and extended to more scripts).
 * Returns null for Latin text, so the caller keeps the user's chosen speaking language.
 */
object LangDetect {

    private class Range(val lo: Int, val hi: Int, val tag: String)

    private val RANGES = listOf(
        Range(0x0B80, 0x0BFF, "ta-IN"),   // Tamil
        Range(0x0C00, 0x0C7F, "te-IN"),   // Telugu
        Range(0x0C80, 0x0CFF, "kn-IN"),   // Kannada
        Range(0x0D00, 0x0D7F, "ml-IN"),   // Malayalam
        Range(0x0900, 0x097F, "hi-IN"),   // Devanagari (Hindi, Marathi)
        Range(0x0980, 0x09FF, "bn-IN"),   // Bengali
        Range(0x0A00, 0x0A7F, "pa-IN"),   // Gurmukhi (Punjabi)
        Range(0x0A80, 0x0AFF, "gu-IN"),   // Gujarati
        Range(0x0600, 0x06FF, "ar"),      // Arabic
        Range(0x0E00, 0x0E7F, "th-TH"),   // Thai
        Range(0x0400, 0x04FF, "ru-RU"),   // Cyrillic
        Range(0x0370, 0x03FF, "el-GR"),   // Greek
        Range(0x3040, 0x30FF, "ja-JP"),   // Hiragana + Katakana
        Range(0xAC00, 0xD7AF, "ko-KR"),   // Hangul
        Range(0x4E00, 0x9FFF, "zh-CN"),   // CJK ideographs (after kana, so Japanese wins when kana is present)
    )

    /** Locale tag such as "ta-IN" when the text is mostly in a non-Latin script, else null. */
    fun tagFor(text: String): String? {
        if (text.isBlank()) return null
        val counts = IntArray(RANGES.size)
        var letters = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (!Character.isLetter(cp)) continue
            letters++
            for (r in RANGES.indices) {
                val rg = RANGES[r]
                if (cp in rg.lo..rg.hi) { counts[r]++; break }
            }
        }
        if (letters == 0) return null
        var best = -1; var bestN = 0
        for (r in counts.indices) if (counts[r] > bestN) { best = r; bestN = counts[r] }
        // Japanese text mixes kana with ideographs: if there is any kana, it is Japanese.
        val kana = counts[RANGES.indexOfFirst { it.tag == "ja-JP" }]
        if (kana > 0) return "ja-JP"
        return if (best >= 0 && bestN * 4 >= letters) RANGES[best].tag else null
    }

    fun localeFor(tag: String): Locale = Locale.forLanguageTag(tag)
}
