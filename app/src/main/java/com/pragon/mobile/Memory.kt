package com.pragon.mobile

import android.content.Context
import org.json.JSONObject

/**
 * Long-term memory on the phone (the PC build keeps this in consciousness/memory_manager.py).
 * Stored as {category: {key: value}} in private SharedPreferences. Pragon fills it through the
 * remember tool and reads it back in every system prompt.
 */
object PragonMemory {
    private const val FILE = "pragon_memory"
    private const val KEY = "facts"
    private const val MAX_FACTS = 60

    private val CATEGORIES = listOf("identity", "preference", "project", "relationship", "note")

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @Synchronized
    private fun load(c: Context): JSONObject =
        try { JSONObject(sp(c).getString(KEY, "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    @Synchronized
    private fun save(c: Context, o: JSONObject) { sp(c).edit().putString(KEY, o.toString()).apply() }

    private fun cleanKey(k: String) = k.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(40)

    fun count(c: Context): Int {
        val o = load(c); var n = 0
        for (cat in o.keys()) n += o.optJSONObject(cat)?.length() ?: 0
        return n
    }

    @Synchronized
    fun remember(c: Context, category: String, key: String, value: String): Boolean {
        val k = cleanKey(key)
        val v = value.trim().take(200)
        if (k.isEmpty() || v.isEmpty()) return false
        val cat = category.trim().lowercase().let { if (it in CATEGORIES) it else "note" }
        val o = load(c)
        if (count(c) >= MAX_FACTS && o.optJSONObject(cat)?.has(k) != true) return false
        val bucket = o.optJSONObject(cat) ?: JSONObject().also { o.put(cat, it) }
        bucket.put(k, v)
        save(c, o)
        return true
    }

    @Synchronized
    fun forget(c: Context, key: String): Boolean {
        val k = cleanKey(key)
        if (k.isEmpty()) return false
        val o = load(c); var hit = false
        for (cat in o.keys().asSequence().toList()) {
            val b = o.optJSONObject(cat) ?: continue
            if (b.has(k)) { b.remove(k); hit = true }
        }
        if (hit) save(c, o)
        return hit
    }

    @Synchronized
    fun clear(c: Context) { sp(c).edit().remove(KEY).apply() }

    /** Plain lines, same spirit as the PC's format_memory_for_prompt(). */
    fun promptBlock(c: Context): String {
        val o = load(c)
        val lines = mutableListOf<String>()
        fun add(cat: String, title: String?) {
            val b = o.optJSONObject(cat) ?: return
            if (b.length() == 0) return
            if (title != null) { lines.add(""); lines.add(title) }
            for (k in b.keys()) {
                val label = k.replace('_', ' ').replaceFirstChar { it.uppercase() }
                lines.add(if (title == null) "$label: ${b.optString(k)}" else " - $label: ${b.optString(k)}")
            }
        }
        add("identity", null)
        add("preference", "Preferences:")
        add("project", "Active projects and goals:")
        add("relationship", "People:")
        add("note", "Notes:")
        return lines.joinToString("\n").trim()
    }

    /** For the Settings screen. */
    fun asJson(c: Context): String = load(c).toString()
}
