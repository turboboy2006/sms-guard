package ir.inod.smsguard

import android.content.Context

/**
 * Icon catalog, ported from the reference `icon_catalog.dart`.
 *
 * Every glyph is a plain vector drawable, so no SVG/PNG asset or licence is
 * involved. Brands deliberately do NOT carry real logos — see the reference's
 * rule that a logo may only ship with a valid asset and a clear right to use it.
 */
data class IconSpec(
    val id: String,
    val drawable: Int,
    val colorHex: String,
    val group: String
)

object IconCatalog {

    val ALL: List<IconSpec> = listOf(
        IconSpec("bank", R.drawable.ic_cat_bank, "#1D4ED8", "financial"),
        IconSpec("card", R.drawable.ic_cat_card, "#1D4ED8", "financial"),
        IconSpec("receipt", R.drawable.ic_cat_receipt, "#0F766E", "financial"),
        IconSpec("insurance", R.drawable.ic_cat_insurance, "#0F766E", "financial"),

        IconSpec("shop", R.drawable.ic_cat_shop, "#EA580C", "retail"),
        IconSpec("bag", R.drawable.ic_cat_bag, "#EA580C", "retail"),
        IconSpec("restaurant", R.drawable.ic_cat_food, "#EA580C", "retail"),
        IconSpec("medical", R.drawable.ic_cat_insurance, "#15803D", "retail"),

        IconSpec("delivery", R.drawable.ic_cat_delivery, "#7C3AED", "transport"),
        IconSpec("travel", R.drawable.ic_cat_travel, "#7C3AED", "transport"),
        IconSpec("taxi", R.drawable.ic_cat_travel, "#7C3AED", "transport"),

        IconSpec("mobile", R.drawable.ic_cat_mobile, "#0891B2", "telecom"),
        IconSpec("internet", R.drawable.ic_cat_internet, "#0891B2", "telecom"),
        IconSpec("security", R.drawable.ic_cat_security, "#B91C1C", "telecom"),

        IconSpec("government", R.drawable.ic_cat_government, "#475467", "public"),
        IconSpec("education", R.drawable.ic_cat_education, "#7C3AED", "public"),
        IconSpec("notification", R.drawable.ic_tab_service, "#7C3AED", "public"),

        IconSpec("warning", R.drawable.ic_tab_suspicious, "#D97706", "alert"),
        IconSpec("unknown", R.drawable.ic_person, "#667085", "alert")
    )

    fun byId(id: String?): IconSpec? = ALL.firstOrNull { it.id == id }

    /** Fallback glyph for a category when no brand matched. */
    fun forCategory(categoryId: String): IconSpec = when (categoryId) {
        Cat.BANKING, Cat.OTP -> byId("bank")
        Cat.NOTIFICATION -> byId("notification")
        Cat.PROMOTION -> byId("shop")
        Cat.SUSPICIOUS, Cat.SPAM -> byId("warning")
        else -> null
    } ?: byId("unknown")!!
}

/** One entry of the built-in brand catalog. `logoAsset` stays null by design. */
data class BrandEntry(
    val displayName: String,
    val categoryId: String,
    val iconId: String,
    val colorHex: String,
    val aliases: List<String>
)

object BrandCatalog {

    val ALL: List<BrandEntry> = listOf(
        BrandEntry("بانک ملت", Cat.BANKING, "bank", "#1D4ED8", listOf("BANKMELLAT", "MELLAT", "بانک ملت")),
        BrandEntry("بانک ملی", Cat.BANKING, "bank", "#1D4ED8", listOf("BANKMELLI", "MELLI", "BMI", "بانک ملی")),
        BrandEntry("بانک صادرات", Cat.BANKING, "bank", "#1D4ED8", listOf("SADERAT", "BSI", "بانک صادرات")),
        BrandEntry("بانک تجارت", Cat.BANKING, "bank", "#1D4ED8", listOf("TEJARAT", "بانک تجارت")),
        BrandEntry("بانک پاسارگاد", Cat.BANKING, "bank", "#1D4ED8", listOf("PASARGAD", "BPI", "بانک پاسارگاد")),
        BrandEntry("بانک سامان", Cat.BANKING, "bank", "#1D4ED8", listOf("SAMAN", "SB24", "بانک سامان")),
        BrandEntry("بانک پارسیان", Cat.BANKING, "bank", "#1D4ED8", listOf("PARSIAN", "بانک پارسیان")),
        BrandEntry("بانک کشاورزی", Cat.BANKING, "bank", "#1D4ED8", listOf("KESHAVARZI", "بانک کشاورزی")),
        BrandEntry("بانک رفاه", Cat.BANKING, "bank", "#1D4ED8", listOf("REFAH", "بانک رفاه")),
        BrandEntry("بانک آینده", Cat.BANKING, "bank", "#1D4ED8", listOf("AYANDEH", "بانک آینده")),
        BrandEntry("بانک شهر", Cat.BANKING, "bank", "#1D4ED8", listOf("SHAHR", "بانک شهر")),
        BrandEntry("بانک دی", Cat.BANKING, "bank", "#1D4ED8", listOf("DAYBANK", "بانک دی")),
        BrandEntry("بانک سینا", Cat.BANKING, "bank", "#1D4ED8", listOf("SINABANK", "بانک سینا")),
        BrandEntry("بانک گردشگری", Cat.BANKING, "bank", "#1D4ED8", listOf("TOURISM", "TOURISMBANK", "بانک گردشگری")),
        BrandEntry("بلوبانک", Cat.BANKING, "card", "#1D4ED8", listOf("BLUBANK", "بلو بانک", "بلوبانک")),
        BrandEntry("توبانک", Cat.BANKING, "card", "#1D4ED8", listOf("TOBANK", "توبانک")),
        BrandEntry("شاپرک", Cat.BANKING, "card", "#1D4ED8", listOf("SHAPARAK", "شاپرک")),
        BrandEntry("زرین‌پال", Cat.BANKING, "card", "#1D4ED8", listOf("ZARINPAL", "زرین پال")),

        BrandEntry("همراه اول", Cat.NOTIFICATION, "mobile", "#0891B2", listOf("MCI", "HAMRAHAVVAL", "همراه اول")),
        BrandEntry("ایرانسل", Cat.NOTIFICATION, "mobile", "#0891B2", listOf("IRANCELL", "MTN", "ایرانسل")),
        BrandEntry("رایتل", Cat.NOTIFICATION, "mobile", "#0891B2", listOf("RIGHTEL", "رایتل")),

        BrandEntry("دیجی‌کالا", Cat.PROMOTION, "shop", "#EA580C", listOf("DIGIKALA", "دیجی کالا")),
        BrandEntry("دیوار", Cat.PROMOTION, "shop", "#EA580C", listOf("DIVAR", "دیوار")),
        BrandEntry("تپسی", Cat.NOTIFICATION, "taxi", "#7C3AED", listOf("TAPSI", "تپسی")),
        BrandEntry("اسنپ", Cat.NOTIFICATION, "taxi", "#7C3AED", listOf("SNAPP", "اسنپ")),
        BrandEntry("اسنپ‌فود", Cat.PROMOTION, "restaurant", "#EA580C", listOf("SNAPPFOOD", "اسنپ فود")),
        BrandEntry("فیدیبو", Cat.PROMOTION, "shop", "#EA580C", listOf("FIDIBO", "فیدیبو")),
        BrandEntry("فیلیمو", Cat.PROMOTION, "shop", "#EA580C", listOf("FILIMO", "فیلیمو")),
        BrandEntry("آپ", Cat.NOTIFICATION, "card", "#1D4ED8", listOf("ASANPARDAKHT", "آپ", "آسان پرداخت")),
        BrandEntry("تاپ", Cat.NOTIFICATION, "card", "#1D4ED8", listOf("TOP", "TOSAN", "تاپ")),
        BrandEntry("پست", Cat.NOTIFICATION, "delivery", "#7C3AED", listOf("POST", "پست جمهوری")),
        BrandEntry("بیمه", Cat.NOTIFICATION, "insurance", "#0F766E", listOf("INSURANCE", "بیمه")),
        BrandEntry("امور مالیاتی", Cat.NOTIFICATION, "government", "#475467", listOf("TAX", "مالیاتی", "INTAMEDIA"))
    )

    /** Match on the normalised address first, then on the display name. */
    fun find(address: String, displayName: String): BrandEntry? {
        val addr = address.uppercase().replace(" ", "")
        if (addr.isNotEmpty()) {
            ALL.firstOrNull { entry ->
                entry.aliases.any { alias ->
                    val a = alias.uppercase().replace(" ", "")
                    a.length >= 4 && addr.contains(a)
                }
            }?.let { return it }
        }
        val name = Normalizer.normalize(displayName)
        if (name.isBlank()) return null
        return ALL.firstOrNull { entry ->
            entry.aliases.any { Normalizer.normalize(it) == name }
        }
    }
}

/** What should be drawn in a conversation row's avatar slot. */
data class AvatarSpec(
    val iconRes: Int?,
    val colorHex: String,
    val displayName: String?
)

object BrandResolver {

    /**
     * Resolution order, exactly as the reference specifies:
     *   1. a user override
     *   2. the brand catalog
     *   3. the detected category
     *   4. a deterministic coloured monogram
     *   5. a grey person for an unnamed sender
     */
    fun resolve(
        context: Context,
        address: String,
        displayName: String,
        categoryId: String
    ): AvatarSpec {
        val senders = SenderStore(context)

        // 1. User override: colour wins over everything below it.
        val overrideColor = senders.colorFor(address)

        // 2. Brand catalog
        val brand = BrandCatalog.find(address, displayName)
        if (brand != null) {
            return AvatarSpec(
                iconRes = IconCatalog.byId(brand.iconId)?.drawable,
                colorHex = overrideColor ?: brand.colorHex,
                displayName = brand.displayName
            )
        }

        // 3. Category icon
        IconCatalog.forCategory(categoryId)?.let { spec ->
            return AvatarSpec(spec.drawable, overrideColor ?: spec.colorHex, null)
        }

        // 4/5. Monogram or grey person; the caller decides which.
        val unknown = AvatarHelper.monogram(displayName)
        return AvatarSpec(
            iconRes = if (unknown == null) IconCatalog.byId("unknown")!!.drawable else null,
            colorHex = overrideColor
                ?: if (unknown == null) AvatarHelper.placeholderColor().let(::hexOf)
                else hexOf(AvatarHelper.colorFor(displayName)),
            displayName = null
        )
    }

    private fun hexOf(color: Int): String =
        String.format("#%06X", 0xFFFFFF and color)
}
