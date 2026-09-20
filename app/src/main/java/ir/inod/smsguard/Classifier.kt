package ir.inod.smsguard

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract

/**
 * The offline brain: fast, network-free, and the product rather than a
 * fallback, because the AI connector is optional.
 *
 * Two families of signal are scored separately in spirit but summed into one
 * score: *promotional* intent and *fraudulent* intent. Fraud signals are
 * weighted far higher because the cost of missing one is much greater.
 *
 * All keyword matching goes through [Normalizer], so Arabic letter forms,
 * kashida, letter spacing and repeated letters do not defeat it.
 */
object Classifier {

    // ------------------------------------------------------------- lexicons

    private val PROMO = listOf(
        "تخفیف", "جشنواره", "جایزه", "برنده", "قرعه", "رایگان", "هدیه", "فرصت",
        "حراج", "فروش ویژه", "کد تخفیف", "بونوس", "لاتاری",
        "فقط امروز", "آخرین فرصت", "کش بک", "cashback", "bonus",
        "discount", "offer", "winner", "prize", "free gift", "lottery", "casino",
        "ثبت نام", "مشاوره رایگان", "خدمات ویژه", "بهترین قیمت", "سفارش", "فروشگاه"
    )
    private val URGENT = listOf(
        "فوری", "همین حالا", "امشب", "مهلت", "فقط تا", "آخرین روز", "از دست ندهید",
        "act now", "limited time", "urgent"
    )
    private val MONEY_LURE = listOf(
        "میلیون تومان", "میلیارد", "جایزه نقدی", "سود تضمینی", "بدون ضامن",
        "وام فوری", "کد بورسی", "ارز دیجیتال", "سرمایه گذاری", "درآمد تضمینی"
    )
    /** Fraud / phishing specific. Weighted heavily. */
    private val FRAUD = listOf(
        "رمز کارت", "رمز دوم", "cvv2", "شماره کارت", "شماره حساب", "شبا",
        "واریز کنید", "کارت به کارت", "اطلاعات بانکی", "رمز عبور",
        "حساب شما مسدود", "برداشت غیرمجاز", "احراز هویت مجدد", "تایید هویت",
        "برنده خوش شانس", "برای دریافت جایزه کلیک"
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
        "سررسید", "یادآوری", "نوبت", "قرار", "فاکتور", "قبض", "سفارش شما",
        "ارسال شد", "تحویل", "مرسوله", "رزرو", "تمدید", "انقضا", "پست"
    )
    private val SHORTENERS = listOf(
        "bit.ly", "t.co", "tinyurl", "goo.gl", "is.gd", "rb.gy", "cutt.ly",
        "ow.ly", "shorturl", "linktr.ee", "t.me", "wa.me", "whatsapp.com"
    )
    private val RISKY_TLDS = listOf(
        ".xyz", ".top", ".click", ".shop", ".live", ".icu", ".buzz", ".rest",
        ".monster", ".cyou", ".sbs", ".cfd", ".loan", ".work"
    )

    /**
     * Brand name -> domains that brand legitimately uses. A message that names
     * a brand but links somewhere else is the classic phishing shape.
     */
    private val BRANDS = mapOf(
        "همراه اول" to listOf("mci.ir", "hamrah"),
        "ایرانسل" to listOf("irancell.ir", "irancell"),
        "رایتل" to listOf("rightel.ir", "rightel"),
        "دیجی کالا" to listOf("digikala.com", "digikala"),
        "بانک ملی" to listOf("bmi.ir"),
        "بانک ملت" to listOf("bankmellat.ir"),
        "بانک صادرات" to listOf("bsi.ir"),
        "بانک تجارت" to listOf("tejaratbank.ir"),
        "بانک پاسارگاد" to listOf("bpi.ir"),
        "بانک سامان" to listOf("sb24.ir", "samanbank"),
        "پست" to listOf("post.ir"),
        "اسنپ" to listOf("snapp.ir", "snapp.site")
    )

    private val LINK_REGEX = Regex("(https?://|www\\.)[^\\s]+", RegexOption.IGNORE_CASE)
    private val OPT_OUT_REGEX = Regex("(لغو\\s*1?1)|(off\\s*-?\\s*\\d{3,})", RegexOption.IGNORE_CASE)
    private val BULK_SENDER_REGEX = Regex("^\\+?98?\\d{4,}$|^\\d{5,}$")
    private val NUMBER_REGEX = Regex("\\d{4,}")
    private val CARD_REGEX = Regex("\\d{16}")
    private val SHEBA_REGEX = Regex("ir\\d{24}", RegexOption.IGNORE_CASE)

    // ------------------------------------------------------------ predicates

    fun looksLikeOtp(body: String): Boolean = Normalizer.containsAny(body, OTP_WORDS)

    fun senderLooksBank(address: String): Boolean =
        BANK_SENDERS.any { address.lowercase().contains(it) }

    fun looksLikeBank(address: String, body: String): Boolean =
        senderLooksBank(address) && Normalizer.containsAny(body, BANK_WORDS)

    fun looksLikeNotification(body: String): Boolean =
        Normalizer.containsAny(body, NOTIFY_WORDS)

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

    // --------------------------------------------------------------- scoring

    private data class Signal(val weight: Int, val tag: String)

    private fun hostOf(link: String): String =
        link.substringAfter("://", link).substringBefore('/').lowercase()

    fun localScore(address: String, body: String): Int =
        signals(address, body).sumOf { it.weight }.coerceIn(0, 100)

    fun reasonsFor(address: String, body: String): List<String> =
        signals(address, body).filter { it.weight > 0 }.map { it.tag }.distinct()

    private fun signals(address: String, body: String): List<Signal> {
        val out = mutableListOf<Signal>()
        val links = LINK_REGEX.findAll(body).map { it.value }.toList()
        val compact = Normalizer.normalize(body).replace(" ", "").replace("-", "")

        // --- promotional intent ---
        if (links.isNotEmpty()) out.add(Signal(24, "link"))
        if (Normalizer.containsAny(body, PROMO)) out.add(Signal(26, "promo"))
        if (Normalizer.containsAny(body, URGENT)) out.add(Signal(16, "urgency"))
        if (Normalizer.containsAny(body, MONEY_LURE)) out.add(Signal(24, "money"))
        if (OPT_OUT_REGEX.containsMatchIn(body)) out.add(Signal(14, "bulk-optout"))
        if (BULK_SENDER_REGEX.matches(address.replace(" ", ""))) out.add(Signal(8, "bulk-sender"))

        // --- fraud / phishing intent ---
        if (Normalizer.containsAny(body, FRAUD)) out.add(Signal(40, "fraud-words"))
        if (CARD_REGEX.containsMatchIn(compact)) out.add(Signal(35, "card-number"))
        if (SHEBA_REGEX.containsMatchIn(compact)) out.add(Signal(30, "sheba"))
        if (Normalizer.looksObfuscated(body)) out.add(Signal(30, "obfuscated"))
        if (links.any { Regex("https?://\\d{1,3}(\\.\\d{1,3}){3}").containsMatchIn(it) }) {
            out.add(Signal(35, "ip-link"))
        }
        if (links.any { l -> SHORTENERS.any { hostOf(l).contains(it) } }) {
            out.add(Signal(20, "shortener"))
        }
        if (links.any { l -> RISKY_TLDS.any { hostOf(l).contains(it) } }) {
            out.add(Signal(22, "risky-tld"))
        }
        // Brand named in the text but the link points elsewhere.
        for ((brand, domains) in BRANDS) {
            if (Normalizer.containsAny(body, brand) && links.isNotEmpty() &&
                links.none { l -> domains.any { hostOf(l).contains(it) } }
            ) {
                out.add(Signal(35, "brand-mismatch"))
                break
            }
        }
        if (links.any { hostOf(it).count { c -> c == '.' } >= 4 }) {
            out.add(Signal(12, "deep-subdomain"))
        }
        if (NUMBER_REGEX.containsMatchIn(Normalizer.normalize(body)) &&
            Normalizer.containsAny(body, listOf("میلیون", "میلیارد", "تومان", "جایزه", "وام"))
        ) out.add(Signal(18, "amount-lure"))

        // --- negative signals: legitimate service traffic ---
        if (looksLikeNotification(body)) out.add(Signal(-22, "notification"))
        if (body.length > 400) out.add(Signal(-8, "long-form"))

        return out
    }

    // ------------------------------------------------------------ decisioning

    /** Baseline decision that never touches the network. */
    fun classifyLocal(context: Context, address: String, body: String): LocalVerdict {
        // Hard exemptions first, so protected traffic is never scored.
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
            Normalizer.containsAny(body, PROMO) -> Cat.PROMOTION
            looksLikeNotification(body) -> Cat.NOTIFICATION
            else -> Cat.OTHER
        }
        return LocalVerdict(category, score, reasonsFor(address, body), suspicious)
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
