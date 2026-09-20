package ir.inod.smsguard

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract

/**
 * The offline brain. It runs in microseconds and never touches the network,
 * which is what keeps SMS delivery inside the latency budget.
 *
 * This stage must stand on its own: the AI connector is optional, so the
 * scoring below is the product, not a fallback.
 */
object Classifier {

    // ---------------------------------------------------------------- lexicons

    private val PROMO = listOf(
        "تخفیف", "جشنواره", "جایزه", "برنده", "قرعه", "رایگان", "هدیه", "فرصت",
        "حراج", "کد تخفیف", "وام", "بونوس", "لاتاری", "شرط بندی", "سرمایه",
        "سیگنال", "فقط امروز", "آخرین فرصت", "کش بک", "cashback", "bonus",
        "discount", "offer", "winner", "prize", "free gift", "lottery", "casino"
    )
    private val URGENT = listOf(
        "فوری", "همین حالا", "امشب", "مهلت", "فقط تا", "آخرین روز", "فقط ۱ روز",
        "فقط 1 روز", "act now", "limited time", "urgent"
    )
    private val MONEY_LURE = listOf(
        "میلیون تومان", "میلیارد", "جایزه نقدی", "سود تضمینی", "بدون ضامن",
        "وام فوری", "کد بورسی", "ارز دیجیتال", "استخراج", "سرمایه‌گذاری"
    )
    private val OTP_WORDS = listOf(
        "رمز پویا", "رمز دوم", "رمز یکبار", "رمز یک بار", "کد تایید", "کد تأیید",
        "کد ورود", "کد فعالسازی", "کد فعال سازی", "one time", "verification code", "otp"
    )
    private val BANK_WORDS = listOf(
        "موجودی", "مانده", "برداشت", "واریز", "تراکنش", "حساب", "کارت", "خرید",
        "انتقال", "کارت به کارت", "شبا", "ریال", "تومان"
    )
    private val BANK_SENDERS = listOf(
        "bank", "melli", "mellat", "saderat", "tejarat", "parsian", "pasargad",
        "saman", "sepah", "keshavarzi", "refah", "maskan", "ayandeh", "shahr",
        "dey", "sina", "karafarin", "blu", "resalat", "mehr", "gardeshgari",
        "shaparak", "bmi", "bki", "bsi", "asanpardakht", "zarinpal", "snapp pay"
    )
    private val NOTIFY_WORDS = listOf(
        "سررسید", "یادآوری", "نوبت", "قرار", "فاکتور", "قبض", "سفارش",
        "ارسال شد", "تحویل", "مرسوله", "رزرو", "تمدید", "انقضا", "پست"
    )
    private val SHORTENERS = listOf(
        "bit.ly", "t.co", "tinyurl", "goo.gl", "is.gd", "rb.gy", "cutt.ly",
        "ow.ly", "shorturl", "linktr.ee", "t.me", "wa.me", "whatsapp.com"
    )
    /** TLDs disproportionately used by throwaway campaign domains. */
    private val RISKY_TLDS = listOf(
        ".xyz", ".top", ".click", ".shop", ".live", ".icu", ".buzz", ".rest",
        ".monster", ".cyou", ".sbs", ".cfd", ".loan", ".work"
    )

    private val LINK_REGEX = Regex("(https?://|www\\.)[^\\s]+", RegexOption.IGNORE_CASE)
    private val OPT_OUT_REGEX = Regex("(لغو\\s*1?1)|(off\\s*-?\\s*\\d{3,})", RegexOption.IGNORE_CASE)
    private val BULK_SENDER_REGEX = Regex("^\\+?98?\\d{4,}$|^\\d{5,}$")
    private val NUMBER_REGEX = Regex("[0-9\u06F0-\u06F9]{4,}")
    /** Zero-width joiners used to slip words past naive keyword filters. */
    private val ZERO_WIDTH_REGEX = Regex("[\u200B-\u200F\u202A-\u202E\uFEFF]")

    private fun containsAny(haystack: String, needles: List<String>): Boolean {
        val h = haystack.lowercase()
        return needles.any { h.contains(it.lowercase()) }
    }

    // ------------------------------------------------------------- predicates

    fun looksLikeOtp(body: String): Boolean = containsAny(body, OTP_WORDS)

    fun senderLooksBank(address: String): Boolean = containsAny(address, BANK_SENDERS)

    fun looksLikeBank(address: String, body: String): Boolean =
        senderLooksBank(address) && containsAny(body, BANK_WORDS)

    fun looksLikeNotification(body: String): Boolean = containsAny(body, NOTIFY_WORDS)

    /** Contact lookups are cached: the read path resolves a category per row. */
    private val contactCache = HashMap<String, Boolean>()

    fun isKnownContact(context: Context, address: String): Boolean {
        if (address.isBlank()) return false
        contactCache[address]?.let { return it }
        val result = queryContact(context, address)
        contactCache[address] = result
        return result
    }

    private fun queryContact(context: Context, address: String): Boolean {
        var cursor: android.database.Cursor? = null
        return try {
            val uri: Uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(address)
            )
            cursor = context.contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null
            )
            cursor != null && cursor.count > 0
        } catch (e: Exception) {
            false
        } finally {
            cursor?.close()
        }
    }

    // ---------------------------------------------------------------- scoring

    private data class Signal(val weight: Int, val tag: String)

    /**
     * Weighted offline score, 0..100.
     *
     * Positive signals accumulate suspicion; a small number of negative
     * signals pull the score back down so ordinary service traffic (reminders,
     * delivery notices) is not swept up with advertising.
     */
    fun localScore(address: String, body: String): Int {
        val signals = mutableListOf<Signal>()
        val lower = body.lowercase()
        val links = LINK_REGEX.findAll(body).map { it.value }.toList()

        if (links.isNotEmpty()) signals.add(Signal(28, "link"))
        if (containsAny(lower, PROMO)) signals.add(Signal(30, "promo"))
        if (containsAny(lower, URGENT)) signals.add(Signal(18, "urgency"))
        if (containsAny(lower, MONEY_LURE)) signals.add(Signal(28, "money-lure"))
        if (OPT_OUT_REGEX.containsMatchIn(body)) signals.add(Signal(14, "bulk-optout"))

        // A link is far more suspicious when the domain is a throwaway.
        if (links.any { link ->
                SHORTENERS.any { link.contains(it, ignoreCase = true) } ||
                    RISKY_TLDS.any { link.contains(it, ignoreCase = true) }
            }
        ) signals.add(Signal(22, "risky-domain"))

        // Raw-IP links and deep subdomain chains are hallmarks of phishing.
        if (links.any { Regex("https?://\\d{1,3}(\\.\\d{1,3}){3}").containsMatchIn(it) }) {
            signals.add(Signal(25, "ip-link"))
        }
        if (links.any { link ->
                // Host only: counting dots in the whole URL counts the scheme too.
                val host = link.substringAfter("://", link).substringBefore('/')
                host.count { it == '.' } >= 4
            }
        ) {
            signals.add(Signal(12, "deep-subdomain"))
        }

        // Amounts combined with prize wording is the classic lure.
        if (NUMBER_REGEX.containsMatchIn(body) &&
            containsAny(lower, listOf("میلیون", "میلیارد", "تومان", "جایزه", "وام"))
        ) signals.add(Signal(20, "amount-lure"))

        if (BULK_SENDER_REGEX.matches(address.replace(" ", ""))) signals.add(Signal(10, "bulk-sender"))

        // Obfuscation attempts.
        if (ZERO_WIDTH_REGEX.containsMatchIn(body)) signals.add(Signal(20, "obfuscated"))
        val emojiCount = body.codePoints().filter { it > 0x1F000 }.count()
        if (emojiCount >= 4) signals.add(Signal(8, "emoji-heavy"))

        // Negative signals: legitimate service traffic.
        if (looksLikeNotification(body)) signals.add(Signal(-22, "notification"))
        if (body.length > 400) signals.add(Signal(-8, "long-form"))

        val total = signals.sumOf { it.weight }
        return total.coerceIn(0, 100)
    }

    fun reasonsFor(body: String): List<String> {
        val out = mutableListOf<String>()
        val lower = body.lowercase()
        if (LINK_REGEX.containsMatchIn(body)) out.add("link")
        if (containsAny(lower, PROMO)) out.add("promo")
        if (containsAny(lower, URGENT)) out.add("urgency")
        if (containsAny(lower, MONEY_LURE)) out.add("money")
        if (OPT_OUT_REGEX.containsMatchIn(body)) out.add("bulk")
        if (ZERO_WIDTH_REGEX.containsMatchIn(body)) out.add("obfuscated")
        return out
    }

    // ------------------------------------------------------------ decisioning

    /** Baseline decision that never touches the network. */
    fun classifyLocal(context: Context, address: String, body: String): LocalVerdict {
        // Hard exemptions come first, so protected traffic is never scored.
        if (isKnownContact(context, address)) {
            return LocalVerdict(Cat.PERSONAL, 0, listOf("contact"), false)
        }
        if (looksLikeOtp(body)) {
            return LocalVerdict(Cat.OTP, 0, listOf("otp"), false)
        }
        if (looksLikeBank(address, body)) {
            return LocalVerdict(Cat.BANKING, 0, listOf("bank"), false)
        }

        val score = localScore(address, body)
        val threshold = SettingsStore(context).threshold
        val suspicious = score >= threshold

        val category = when {
            suspicious -> Cat.SUSPICIOUS
            containsAny(body, PROMO) -> Cat.PROMOTION
            looksLikeNotification(body) -> Cat.NOTIFICATION
            else -> Cat.OTHER
        }
        return LocalVerdict(category, score, reasonsFor(body), suspicious)
    }

    /** Read-only resolution used while listing messages. Performs no writes. */
    fun resolveRead(context: Context, address: String, body: String, messageId: Long): String {
        MessageCategoryStore(context).categoryFor(messageId)?.let { return it }
        SenderStore(context).categoryFor(address)?.let { return it }
        return classifyLocal(context, address, body).categoryId
    }

    /**
     * Called once per incoming message. Remembers the sender, which is what
     * makes bank and OTP senders permanently exempt from re-analysis.
     */
    fun rememberSender(context: Context, address: String, body: String): String {
        val verdict = classifyLocal(context, address, body)
        val senders = SenderStore(context)
        if (senders.categoryFor(address) == null) {
            senders.setCategory(address, verdict.categoryId)
        }
        val cat = CategoryStore(context).byId(verdict.categoryId)
        if (cat != null && cat.skipAi) {
            senders.setPolicy(address, SenderPolicy.NEVER_ANALYZE)
        }
        return verdict.categoryId
    }

    /** Colour for a conversation: explicit sender colour wins, else category. */
    fun colorFor(context: Context, address: String, categoryId: String): String =
        SenderStore(context).colorFor(address)
            ?: CategoryStore(context).byId(categoryId)?.colorHex
            ?: "#616161"
}
