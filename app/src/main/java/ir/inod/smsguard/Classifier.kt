package ir.inod.smsguard

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract

/**
 * The offline brain. It runs in microseconds and decides two things:
 *   - the baseline category of a message
 *   - a suspicion score used to gate the (slow, networked) AI stage
 *
 * Nothing here performs network I/O, which is what keeps SMS delivery under
 * the latency budget.
 */
object Classifier {

    private val PROMO_WORDS = listOf(
        "تخفیف", "جشنواره", "جایزه", "برنده", "قرعه", "رایگان", "هدیه", "فرصت",
        "حراج", "کد تخفیف", "وام", "بونوس", "لاتاری", "شرط بندی", "سرمایه",
        "سیگنال", "فقط امروز", "آخرین فرصت", "کش بک", "cashback", "bonus",
        "discount", "offer", "winner", "prize", "free gift"
    )
    private val URGENT_WORDS = listOf(
        "فوری", "همین حالا", "امشب", "مهلت", "فقط تا", "آخرین روز", "فقط ۱ روز", "فقط 1 روز"
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

    private val LINK_REGEX = Regex("(https?://|www\\.)[^\\s]+", RegexOption.IGNORE_CASE)
    private val OPT_OUT_REGEX = Regex("(لغو\\s*1?1)|(off\\s*-?\\s*\\d{3,})", RegexOption.IGNORE_CASE)
    private val BULK_SENDER_REGEX = Regex("^\\+?98?\\d{4,}$|^\\d{5,}$")

    private fun containsAny(haystack: String, needles: List<String>): Boolean {
        val h = haystack.lowercase()
        return needles.any { h.contains(it.lowercase()) }
    }

    fun looksLikeOtp(body: String): Boolean = containsAny(body, OTP_WORDS)

    fun senderLooksBank(address: String): Boolean = containsAny(address, BANK_SENDERS)

    fun looksLikeBank(address: String, body: String): Boolean =
        senderLooksBank(address) && containsAny(body, BANK_WORDS)

    fun looksLikeNotification(body: String): Boolean = containsAny(body, NOTIFY_WORDS)

    /** True when the sender is one of the device's saved contacts. */
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

    /** Local suspicion score. Higher means more likely to be junk. */
    fun localScore(address: String, body: String): Int {
        var score = 0
        val lower = body.lowercase()

        if (LINK_REGEX.containsMatchIn(body)) score += 30
        if (containsAny(lower, PROMO_WORDS)) score += 30
        if (containsAny(lower, URGENT_WORDS)) score += 20
        if (OPT_OUT_REGEX.containsMatchIn(body)) score += 15
        if (containsAny(lower, SHORTENERS)) score += 20

        val links = LINK_REGEX.findAll(body).map { it.value }.toList()
        if (links.any { link -> SHORTENERS.any { link.contains(it, ignoreCase = true) } }) score += 15

        // Persian/Arabic-Indic digits mixed with money words is a classic lure.
        if (Regex("[0-9\u06F0-\u06F9]{4,}").containsMatchIn(body) &&
            containsAny(lower, listOf("میلیون", "میلیارد", "تومان", "جایزه", "وام"))
        ) score += 20

        if (BULK_SENDER_REGEX.matches(address.replace(" ", ""))) score += 10

        return score.coerceIn(0, 100)
    }

    /** Baseline decision that never touches the network. */
    fun classifyLocal(context: Context, address: String, body: String): LocalVerdict {
        val reasons = mutableListOf<String>()

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
        if (LINK_REGEX.containsMatchIn(body)) reasons.add("link")
        if (containsAny(body, PROMO_WORDS)) reasons.add("promo-words")
        if (containsAny(body, URGENT_WORDS)) reasons.add("urgency")
        if (OPT_OUT_REGEX.containsMatchIn(body)) reasons.add("bulk-optout")

        val threshold = SettingsStore(context).threshold
        val suspicious = score >= threshold

        val category = when {
            looksLikeNotification(body) && !suspicious -> Cat.NOTIFICATION
            suspicious -> Cat.SUSPICIOUS
            containsAny(body, PROMO_WORDS) -> Cat.PROMOTION
            else -> Cat.OTHER
        }
        return LocalVerdict(category, score, reasons, suspicious)
    }

    /**
     * Final category for a message, in priority order:
     * manual/AI override -> remembered sender category -> local classification.
     * Remembering the sender is what makes bank and OTP senders permanently
     * exempt from re-analysis.
     */
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
