package ir.inod.smsguard

/**
 * Phone-number intelligence, entirely offline.
 *
 * The same Iranian number is written many ways — +98912…, 0098912…, 0912…,
 * 912… — and any sender table keyed on the raw string would treat those as
 * separate senders. [canonical] folds them to one key.
 */
object PhoneIntel {

    private val IRAN_MOBILE = Regex("(\\+?98|0)?9\\d{9}")
    private val DIGIT_RUN = Regex("\\d{3,}")

    /** Last ten digits: the only part that identifies an Iranian number. */
    fun canonical(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return if (digits.length > 10) digits.takeLast(10) else digits
    }

    fun isIranianMobile(raw: String): Boolean =
        IRAN_MOBILE.matches(raw.replace(" ", ""))

    /** Four- to six-digit short codes are how banks and bulk senders appear. */
    fun isShortCode(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isLetter() }) return false
        val digits = trimmed.filter { it.isDigit() }
        return digits.length in 4..6 && digits.length == trimmed.length
    }

    /**
     * Mobile numbers written inside the message body.
     *
     * A message that asks the reader to call a number *different* from the
     * sender is the classic callback-scam shape: the payload never contains a
     * link, so link analysis alone would miss it entirely.
     */
    fun numbersIn(body: String): List<String> =
        DIGIT_RUN.findAll(Normalizer.normalize(body))
            .map { canonical(it.value) }
            .filter { it.length >= 10 }
            .distinct()
            .toList()
}
