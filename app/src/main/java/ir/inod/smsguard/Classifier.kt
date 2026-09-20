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

    /**
     * App-wide caches.
     *
     * The conversation list calls [resolveRead] once per row. Every store read
     * parses its entire JSON document and a contact check used to hit
     * ContactsProvider, so doing that per row made the list O(rows x parse) and
     * caused an ANR. Everything is now loaded once and reused until a write
     * calls [invalidateCaches].
     */
    private var cachedCategories: Map<String, Category>? = null
    private var cachedOverrides: Map<Long, String>? = null
    private var cachedSenderCats: Map<String, String>? = null
    private var cachedSenderColors: Map<String, String>? = null
    private var cachedThreshold: Int = -1
    private var cachedContacts: Set<String>? = null
    private var cachedWeights: Map<String, Double>? = null
    private var cachedProfiles: Map<String, SenderProfile>? = null
    private var cachedDomains: Set<String>? = null
    private var cachedPrefixes: Set<String>? = null

    fun invalidateCaches() {
        cachedCategories = null
        cachedOverrides = null
        cachedSenderCats = null
        cachedSenderColors = null
        cachedThreshold = -1
        cachedContacts = null
        cachedWeights = null
        cachedProfiles = null
        cachedDomains = null
        cachedPrefixes = null
    }

    private fun blockedDomains(context: Context): Set<String> =
        cachedDomains ?: BlockStore(context).domains().also { cachedDomains = it }

    private fun blockedPrefixes(context: Context): Set<String> =
        cachedPrefixes ?: BlockStore(context).prefixes().also { cachedPrefixes = it }

    /** Mirrors BlockStore.matchesDomain but against the cached snapshot. */
    private fun domainBlocked(context: Context, host: String): Boolean {
        if (host.isBlank()) return false
        return blockedDomains(context).any { host == it || host.endsWith(".$it") }
    }

    /** A prefix entry ending in '*' matches the whole range. */
    private fun prefixBlocked(context: Context, address: String): Boolean {
        val a = address.replace(" ", "")
        return blockedPrefixes(context).any { p ->
            val clean = p.replace(" ", "")
            if (clean.endsWith("*")) a.startsWith(clean.dropLast(1)) else a == clean
        }
    }

    /** Learned token weights. Empty until the user has labelled some messages. */
    private fun weights(context: Context): Map<String, Double> =
        cachedWeights ?: LearnedWeights(context).weights().also { cachedWeights = it }

    private fun profiles(context: Context): Map<String, SenderProfile> =
        cachedProfiles ?: SenderProfileStore(context).snapshot().also { cachedProfiles = it }

    private fun categories(context: Context): Map<String, Category> =
        cachedCategories ?: CategoryStore(context).all().associateBy { it.id }
            .also { cachedCategories = it }

    private fun overrides(context: Context): Map<Long, String> =
        cachedOverrides ?: MessageCategoryStore(context).all().also { cachedOverrides = it }

    private fun senderCategories(context: Context): Map<String, String> =
        cachedSenderCats ?: SenderStore(context).allCategories().also { cachedSenderCats = it }

    private fun senderColors(context: Context): Map<String, String> =
        cachedSenderColors ?: SenderStore(context).allColors().also { cachedSenderColors = it }

    private fun threshold(context: Context): Int {
        if (cachedThreshold < 0) cachedThreshold = SettingsStore(context).threshold
        return cachedThreshold
    }

    // ------------------------------------------------------------- contacts

    /**
     * One query for the whole address book instead of one query per sender:
     * 204 ContactsProvider hits were showing up in logcat on a real device.
     */
    private fun contactNumbers(context: Context): Set<String> {
        cachedContacts?.let { return it }
        val set = HashSet<String>()
        var cursor: android.database.Cursor? = null
        try {
            cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null
            )
            if (cursor != null) {
                val idx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (idx >= 0) {
                    while (cursor.moveToNext()) {
                        cursor.getString(idx)?.let { set.add(matchKey(it)) }
                    }
                }
            }
        } catch (e: Exception) {
            // READ_CONTACTS missing or provider unavailable: treat as no contacts.
        } finally {
            cursor?.close()
        }
        cachedContacts = set
        return set
    }

    /** Last 10 digits, so +98…, 0098… and 0… all compare equal. */
    private fun matchKey(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return if (digits.length > 10) digits.takeLast(10) else digits
    }

    fun isKnownContact(context: Context, address: String): Boolean {
        if (address.isBlank()) return false
        val key = matchKey(address)
        if (key.length < 7) return false
        return contactNumbers(context).contains(key)
    }

    // --------------------------------------------------------------- scoring

    private data class Signal(val weight: Int, val tag: String)

    private fun hostOf(link: String): String =
        link.substringAfter("://", link).substringBefore('/').lowercase()

    fun localScore(context: Context, address: String, body: String): Int =
        signals(context, address, body).sumOf { it.weight }.coerceIn(0, 100)

    fun reasonsFor(context: Context, address: String, body: String): List<String> =
        signals(context, address, body).filter { it.weight > 0 }.map { it.tag }.distinct()

    private fun signals(context: Context, address: String, body: String): List<Signal> {
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
        // --- URL intelligence, all offline ---
        val urls = UrlIntel.extract(body)
        if (urls.any { it.isIp }) out.add(Signal(35, "ip-link"))
        if (urls.any { it.isShortener }) out.add(Signal(20, "shortener"))
        if (urls.any { it.riskyTld }) out.add(Signal(22, "risky-tld"))
        if (urls.any { it.isPunycode }) out.add(Signal(30, "punycode"))
        if (urls.any { it.subdomains >= 3 }) out.add(Signal(14, "deep-subdomain"))
        if (urls.any { it.length > 90 }) out.add(Signal(10, "url-length"))
        if (urls.any { it.entropy > 3.7 }) out.add(Signal(14, "host-entropy"))
        if (urls.any { it.digitRatio > 0.35 }) out.add(Signal(18, "digit-host"))
        if (urls.any { it.hasRedirectParam }) out.add(Signal(16, "redirect-param"))

        // --- blocklists the user has built up ---
        if (urls.any { domainBlocked(context, it.host) }) out.add(Signal(50, "domain-blocked"))
        if (prefixBlocked(context, address)) out.add(Signal(45, "prefix-blocked"))

        // Brand named in the text but the link points elsewhere.
        for ((brand, domains) in BRANDS) {
            if (Normalizer.containsAny(body, brand) && urls.isNotEmpty() &&
                urls.none { u -> domains.any { u.host == it || u.host.endsWith(".$it") } }
            ) {
                out.add(Signal(35, "brand-mismatch"))
                break
            }
        }

        // Typosquatted brand domains, e.g. digikalaa.xyz / bankmellii.ir
        if (urls.any { u -> BRANDS.values.any { UrlIntel.looksLikeImpersonation(u.host, it) } }) {
            out.add(Signal(40, "brand-impersonation"))
        }
        if (NUMBER_REGEX.containsMatchIn(Normalizer.normalize(body)) &&
            Normalizer.containsAny(body, listOf("میلیون", "میلیارد", "تومان", "جایزه", "وام"))
        ) out.add(Signal(18, "amount-lure"))

        // --- learned signals: the user's own corrections ---
        val learned = learnedScore(context, body)
        if (learned != 0) out.add(Signal(learned, "learned"))

        profiles(context)[address]?.let { p ->
            when {
                p.hostile -> out.add(Signal(30, "sender-hostile"))
                p.trusted -> out.add(Signal(-35, "sender-trusted"))
            }
            // Three or more messages inside ten minutes is a bulk signature.
            if (p.burst >= 3) out.add(Signal(14, "burst"))
        }

        // --- negative signals: legitimate service traffic ---
        if (looksLikeNotification(body)) out.add(Signal(-22, "notification"))
        if (body.length > 400) out.add(Signal(-8, "long-form"))

        return out
    }

    /**
     * Sum of learned token weights.
     *
     * Log-odds run roughly -4..+4 per token, so the sum is scaled down and
     * capped: a handful of corrected messages must never be able to run away
     * with the score.
     */
    private fun learnedScore(context: Context, body: String): Int {
        val w = weights(context)
        if (w.isEmpty()) return 0
        var sum = 0.0
        for (token in Learning.tokens(body)) {
            w[token]?.let { sum += it }
        }
        return (sum * 6.0).toInt().coerceIn(-30, 40)
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

        val boundary = threshold(context)
        val score = localScore(context, address, body)
        val suspicious = score >= boundary

        val category = when {
            suspicious -> Cat.SUSPICIOUS
            Normalizer.containsAny(body, PROMO) -> Cat.PROMOTION
            looksLikeNotification(body) -> Cat.NOTIFICATION
            else -> Cat.OTHER
        }

        // Heuristic spread, NOT a calibrated probability: it only reports how
        // far the score sits from the decision boundary.
        val confidence = (50 + kotlin.math.abs(score - boundary)).coerceIn(50, 99)

        return LocalVerdict(
            categoryId = category,
            score = score,
            reasons = reasonsFor(context, address, body),
            isSuspicious = suspicious,
            confidence = confidence
        )
    }

    /** Read-only resolution used while listing messages. Performs no writes. */
    fun resolveRead(context: Context, address: String, body: String, messageId: Long): String {
        overrides(context)[messageId]?.let { return it }
        senderCategories(context)[address]?.let { return it }
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
        val cat = categories(context)[verdict.categoryId]
        if (cat != null && cat.skipAi) {
            senders.setPolicy(address, SenderPolicy.NEVER_ANALYZE)
        }
        invalidateCaches()
        return verdict.categoryId
    }

    /** Colour for a conversation: explicit sender colour wins, else category. */
    fun colorFor(context: Context, address: String, categoryId: String): String =
        senderColors(context)[address]
            ?: categories(context)[categoryId]?.colorHex
            ?: "#616161"
}
