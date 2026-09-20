package ir.inod.smsguard

/**
 * Persian/Arabic text normalisation.
 *
 * Without this, keyword matching fails on the most common evasions used by
 * bulk senders in Iran:
 *   - Arabic letter forms:   ي vs ی ,  ك vs ک
 *   - ZWNJ / نیم‌فاصله:      تخفیف‌ویژه vs تخفیف ویژه
 *   - kashida padding:       تخـفیف
 *   - letter spacing:        ت خ ف ی ف
 *   - repeated letters:      تخفیففف
 *   - Persian/Arabic digits: ۱۲۳ / ١٢٣ vs 123
 *
 * [forms] returns several equivalent renderings of the same string; a keyword
 * counts as present when it appears in ANY of them.
 */
object Normalizer {

    private val DIACRITICS = Regex("[\u064B-\u0652\u0670\u0640]")
    private val ZERO_WIDTH = Regex("[\u200B-\u200F\u202A-\u202E\uFEFF]")
    private val REPEATS = Regex("(.)\\1{2,}")

    /** Canonical form used for matching. */
    fun normalize(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            val c = when (ch) {
                '\u064A', '\u0649' -> '\u06CC'   // ي , ى -> ی
                '\u0643' -> '\u06A9'             // ك -> ک
                '\u06C0', '\u0629' -> '\u0647'   // ۀ , ة -> ه
                '\u0623', '\u0625', '\u0622' -> '\u0627' // أ , إ , آ -> ا
                else -> ch
            }
            sb.append(c)
        }
        var s = sb.toString()
        s = DIACRITICS.replace(s, "")
        s = ZERO_WIDTH.replace(s, "")
        s = s.map { mapDigit(it) }.joinToString("")
        s = s.replace('\u200C', ' ').replace('\u00A0', ' ')
        s = s.replace(Regex("\\s+"), " ")
        return s.trim().lowercase()
    }

    private fun mapDigit(ch: Char): Char = when (ch) {
        in '\u06F0'..'\u06F9' -> ('0' + (ch - '\u06F0'))  // Persian
        in '\u0660'..'\u0669' -> ('0' + (ch - '\u0660'))  // Arabic-Indic
        else -> ch
    }

    /**
     * Equivalent renderings to match against. Cheaper than trying to guess the
     * single "correct" de-obfuscation, and it cannot produce false negatives.
     */
    fun forms(input: String): List<String> {
        val n = normalize(input)
        val compact = n.replace(" ", "")
        val deduped = REPEATS.replace(n, "$1")
        val compactDeduped = deduped.replace(" ", "")
        return listOf(n, compact, deduped, compactDeduped).distinct()
    }

    fun containsAny(input: String, keywords: List<String>): Boolean {
        val fs = forms(input)
        return keywords.any { kw ->
            val k = normalize(kw)
            fs.any { it.contains(k) }
        }
    }

    fun containsAny(input: String, keyword: String): Boolean =
        containsAny(input, listOf(keyword))

    /** True when the message shows signs of deliberate filter evasion. */
    fun looksObfuscated(raw: String): Boolean {
        if (ZERO_WIDTH.containsMatchIn(raw)) return true
        if (raw.contains('\u0640')) return true                       // kashida
        if (DIACRITICS.containsMatchIn(raw) && raw.length > 12) return true
        // "ت خ ف ی ف" — a long run of single letters separated by spaces
        if (Regex("(?:\\S\\s){4,}\\S").containsMatchIn(raw)) return true
        if (REPEATS.containsMatchIn(raw)) return true
        return false
    }
}
