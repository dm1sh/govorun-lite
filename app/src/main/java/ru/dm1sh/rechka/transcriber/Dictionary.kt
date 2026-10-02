package ru.dm1sh.rechka.transcriber

import android.content.Context
import android.util.Log
import ru.dm1sh.rechka.util.Prefs

/**
 * User-defined post-recognition text replacements.
 *
 * Format (one rule per line):
 *  - "ключ=значение" — replace 'ключ' with 'значение'
 *  - "ключ" (no =)    — delete 'ключ' entirely
 *  - "# комментарий"  — comment, ignored
 *  - blank line       — ignored
 *
 * Mirrors the proven implementation in the Pro app's RechkaServiceAi.kt
 * (applyCorrections), which has been live for many users for months. The
 * Pro pattern is intentionally simple — explicit char class with Russian +
 * Latin letters and digits, IGNORE_CASE Kotlin regex, in-file order applies.
 *
 * Order matters: rules are applied top-to-bottom, so longer/more specific
 * patterns should be placed BEFORE shorter ones in the dictionary file.
 *
 * Empty replacement values delete the matched word; we then collapse runs
 * of multiple spaces to one and trim — so "X word Y" with rule "word="
 * becomes "X Y", not "X  Y".
 */
object Dictionary {

    private const val TAG = "Dictionary"
    // Explicit character class — Russian + Latin letters + digits.
    // Safer than \p{L} which can behave inconsistently across Android
    // regex engines, and avoids UNICODE_CHARACTER_CLASS (unsupported on
    // Android — caused a silent crash that swallowed VAD segments).
    private const val WORD_CHARS = "а-яёА-ЯЁa-zA-Z0-9"

    fun applyReplacements(context: Context, text: String): String {
        if (text.isBlank()) return text
        // Master switch — default OFF. New users see the dictionary screen
        // with example rules but their dictation isn't silently transformed
        // until they explicitly enable the dictionary inside that screen.
        if (!Prefs.isDictionaryEnabled(context)) return text
        val raw = Prefs.getDictionary(context)
        if (raw.isBlank()) return text

        return try {
            val result = raw.lines().fold(text) { acc, line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) return@fold acc
                val (from, to) = if ('=' in trimmed) {
                    val parts = trimmed.split("=", limit = 2)
                    parts[0].trim() to parts[1].trim()
                } else {
                    trimmed to ""
                }
                if (from.isEmpty()) return@fold acc
                val pattern = Regex(
                    "(?<![${WORD_CHARS}])${Regex.escape(from)}(?![${WORD_CHARS}])",
                    RegexOption.IGNORE_CASE
                )
                pattern.replace(acc, to)
            }
            // Collapse runs of spaces left behind by deletion rules and
            // trim leading/trailing whitespace introduced by them.
            result.replace(Regex(" {2,}"), " ").trim()
        } catch (e: Exception) {
            // Defensive: never let a malformed user rule break the whole
            // transcription pipeline. Recognised text reaching the field
            // matters more than any one replacement.
            Log.w(TAG, "applyReplacements failed: ${e.message}")
            text
        }
    }
}
