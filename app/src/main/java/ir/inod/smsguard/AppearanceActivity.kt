package ir.inod.smsguard

import android.os.Bundle
import android.content.Intent
import android.view.MenuItem
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.slider.Slider
import ir.inod.smsguard.databinding.ActivityAppearanceBinding

/**
 * Appearance settings.
 *
 * Everything here applies as it is changed: the preview at the top of the
 * screen and the inbox behind it are redrawn from the same [RowLayout], and the
 * values are only ever read back through [ThemePrefs], never mirrored in local
 * state. That is what keeps "what I set" and "what I see" the same thing.
 */
class AppearanceActivity : BaseActivity() {

    private lateinit var binding: ActivityAppearanceBinding
    private val theme by lazy { ThemePrefs(this) }

    /** Guard so programmatic spinner updates do not write back. */
    private var bindingUi = false
    private val backgroundPhotoPicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        theme.backgroundImageUri = uri.toString()
        theme.backgroundPreset = null
        theme.touch()
        BackgroundRenderer.apply(binding.preview, this, theme.backgroundStyle, theme.backgroundImageUri)
    }

    private val listFontScales = listOf(0.9f, 1f, 1.1f, 1.25f, 1.4f)
    private val messageFontScales = listOf(0.9f, 1f, 1.15f, 1.3f, 1.5f)

    /** A few intentional starting points, rather than asking everyone to
     * understand seven spacing sliders before they can make the inbox pleasant. */
    private enum class Preset(
        val rowStyle: String,
        val padding: Int,
        val spacing: Int,
        val inset: Int,
        val listPadding: Int,
        val messageStyle: String,
        val messageSpacing: Int,
        val radius: Int,
        val dividers: Boolean,
        val chips: Boolean
    ) {
        MODERN(RowStyle.CLASSIC, 8, 0, 0, 8, MessageStyle.FILLED, 2, 14, true, true),
        COMPACT(RowStyle.COMPACT, 6, 0, 0, 4, MessageStyle.CLEAN, 2, 12, false, true),
        CARDS(RowStyle.CARD, 10, 8, 4, 12, MessageStyle.SOFT, 8, 18, false, true),
        AIRY(RowStyle.SOFT, 14, 8, 2, 12, MessageStyle.SOFT, 9, 22, false, true),
        MINIMAL(RowStyle.FLAT, 8, 0, 0, 4, MessageStyle.CLEAN, 3, 8, true, true),
        VIBRANT(RowStyle.ACCENT, 12, 4, 2, 10, MessageStyle.FILLED, 6, 20, true, true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppearanceBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        setUpSpinners()
        setUpSliders()
        setUpSwitches()
        setUpPresets()
        setUpPalettes()
        setUpStickyPreview()
        setUpFontSliders()
        setUpVisibleBackgrounds()
        binding.buttonBackground.setOnClickListener { chooseGlobalBackground() }
        binding.buttonReset.setOnClickListener { reset() }
        refresh()
    }

    private fun setUpStickyPreview() {
        val card = binding.preview.parent.parent as View
        val original = card.parent as ViewGroup
        original.removeView(card)
        val root = binding.root as ViewGroup
        root.addView(card, 1, LinearLayout.LayoutParams(-1, -2))
        val content = binding.preview.parent as LinearLayout
        val tabs = MaterialButtonToggleGroup(this).apply { isSingleSelection = true }
        listOf(
            (if (Dates.isPersian(this)) "فهرست و دسته‌ها" else "Inbox & categories") to true,
            (if (Dates.isPersian(this)) "گفتگو" else "Conversation") to false
        ).forEach { (label, inbox) ->
            tabs.addView(MaterialButton(this).apply {
                id = View.generateViewId()
                text = label
                setOnClickListener { binding.preview.showInboxPreview(inbox) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        content.addView(tabs, 1)
        binding.preview.showInboxPreview(true)
    }

    private fun setUpFontSliders() {
        fun replace(spinner: android.widget.Spinner, current: Float, write: (Float) -> Unit) {
            val parent = spinner.parent as ViewGroup
            val index = parent.indexOfChild(spinner)
            spinner.visibility = View.GONE
            val slider = Slider(this).apply {
                valueFrom = 0.85f
                valueTo = 1.5f
                stepSize = 0.05f
                value = (kotlin.math.round(current.coerceIn(0.85f, 1.5f) * 20f) / 20f)
                addOnChangeListener { _, value, fromUser ->
                    if (fromUser) { write(value); theme.touch(); refresh() }
                }
            }
            parent.addView(slider, index + 1)
        }
        replace(binding.spinnerListFont, theme.listFontScale) { theme.listFontScale = it }
        replace(binding.spinnerMessageFont, theme.messageFontScale) { theme.messageFontScale = it }
    }

    private fun setUpVisibleBackgrounds() {
        val parent = binding.buttonBackground.parent as ViewGroup
        val index = parent.indexOfChild(binding.buttonBackground)
        val strip = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val items = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val labels = listOf(R.string.background_clean, R.string.background_mist,
            R.string.background_aurora, R.string.background_dusk, R.string.background_bloom) +
            listOf(R.string.wallpaper_mountains, R.string.wallpaper_eucalyptus,
                R.string.wallpaper_lake, R.string.wallpaper_desert, R.string.wallpaper_lavender,
                R.string.wallpaper_rain, R.string.wallpaper_ocean, R.string.wallpaper_pastel,
                R.string.wallpaper_neon, R.string.wallpaper_coral)
        labels.forEachIndexed { position, labelId ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(8, 8, 8, 8)
            }
            val sample = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(72.dp(), 105.dp())
            }
            if (position < BackgroundStyle.IDS.size)
                BackgroundRenderer.apply(sample, this, BackgroundStyle.IDS[position])
            else BackgroundRenderer.apply(sample, this, theme.backgroundStyle,
                preset = BuiltInWallpaper.IDS[position - BackgroundStyle.IDS.size])
            item.addView(sample)
            item.addView(android.widget.TextView(this).apply {
                text = getString(labelId)
                maxLines = 1
                textSize = 11f
                gravity = android.view.Gravity.CENTER
            }, LinearLayout.LayoutParams(72.dp(), -2))
            item.setOnClickListener {
                if (position < BackgroundStyle.IDS.size) {
                    theme.backgroundStyle = BackgroundStyle.IDS[position]
                    theme.backgroundPreset = null
                } else theme.backgroundPreset = BuiltInWallpaper.IDS[position - BackgroundStyle.IDS.size]
                theme.backgroundImageUri = null
                theme.touch(); refresh()
            }
            items.addView(item)
        }
        strip.addView(items)
        parent.addView(strip, index + 1)
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------- set-up

    private fun setUpSpinners() {
        binding.spinnerListFont.adapter = adapter(
            listOf(
                R.string.font_small, R.string.font_normal, R.string.font_large,
                R.string.font_xlarge, R.string.font_huge
            )
        )
        binding.spinnerListFont.onItemSelectedListener = select { position ->
            theme.listFontScale = listFontScales[position]
            theme.touch()
            refresh()
        }

        binding.spinnerMessageFont.adapter = adapter(
            listOf(
                R.string.font_small, R.string.font_normal, R.string.font_large,
                R.string.font_xlarge, R.string.font_huge
            )
        )
        binding.spinnerMessageFont.onItemSelectedListener = select { position ->
            theme.messageFontScale = messageFontScales[position]
            theme.touch()
            refresh()
        }

        binding.spinnerRowStyle.adapter = adapter(
            listOf(
                R.string.row_style_classic, R.string.row_style_card,
                R.string.row_style_flat, R.string.row_style_accent,
                R.string.row_style_bubble, R.string.row_style_compact,
                R.string.row_style_soft, R.string.row_style_outline,
                R.string.row_style_striped, R.string.row_style_pill
            )
        )
        binding.spinnerRowStyle.onItemSelectedListener = select { position ->
            theme.rowStyle = RowStyle.IDS[position]
            theme.touch()
            refresh()
        }

        binding.spinnerMessageStyle.adapter = adapter(
            listOf(
                R.string.msg_style_filled, R.string.msg_style_contrast,
                R.string.msg_style_outline, R.string.msg_style_soft,
                R.string.msg_style_clean
            )
        )
        binding.spinnerMessageStyle.onItemSelectedListener = select { position ->
            theme.messageStyle = MessageStyle.IDS[position]
            theme.touch()
            refresh()
        }
    }

    private fun setUpSliders() {
        binding.sliderSurfaceOpacity.value = theme.surfaceOpacity.toFloat()
        binding.sliderSurfaceOpacity.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                theme.surfaceOpacity = value.toInt(); theme.touch()
                refresh()
            }
        }
        binding.sliderRowPadding.value = theme.rowPadding.toFloat()
        binding.sliderRowSpacing.value = theme.rowSpacing.toFloat()
        binding.sliderRowInset.value = theme.rowInset.toFloat()
        binding.sliderListPadding.value = theme.listPadding.toFloat()
        binding.sliderMessageSpacing.value = theme.messageSpacing.toFloat()
        binding.sliderBubbleRadius.value = theme.bubbleRadius.toFloat()

        binding.sliderRowPadding.onChange { theme.rowPadding = it }
        binding.sliderRowSpacing.onChange { theme.rowSpacing = it }
        binding.sliderRowInset.onChange { theme.rowInset = it }
        binding.sliderListPadding.onChange { theme.listPadding = it }
        binding.sliderMessageSpacing.onChange { theme.messageSpacing = it }
        binding.sliderBubbleRadius.onChange { theme.bubbleRadius = it }
    }

    private fun setUpSwitches() {
        binding.switchDividers.isChecked = theme.showDividers
        binding.switchChips.isChecked = theme.showChips
        binding.switchDividers.setOnCheckedChangeListener { _, checked ->
            if (bindingUi) return@setOnCheckedChangeListener
            theme.showDividers = checked
            theme.touch()
            refresh()
        }
        binding.switchChips.setOnCheckedChangeListener { _, checked ->
            if (bindingUi) return@setOnCheckedChangeListener
            theme.showChips = checked
            theme.touch()
            refresh()
        }
    }

    private fun setUpPresets() {
        binding.presetGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (bindingUi || !isChecked) return@addOnButtonCheckedListener
            val preset = when (checkedId) {
                R.id.buttonPresetModern -> Preset.MODERN
                R.id.buttonPresetCompact -> Preset.COMPACT
                R.id.buttonPresetCards -> Preset.CARDS
                else -> return@addOnButtonCheckedListener
            }
            applyPreset(preset)
        }
        val extras = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            (if (Dates.isPersian(this)) "آرام" else "Airy") to Preset.AIRY,
            (if (Dates.isPersian(this)) "مینیمال" else "Minimal") to Preset.MINIMAL,
            (if (Dates.isPersian(this)) "رنگی" else "Vibrant") to Preset.VIBRANT
        ).forEach { (label, preset) ->
            extras.addView(MaterialButton(this).apply {
                text = label
                setOnClickListener { applyPreset(preset) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        val parent = binding.presetGroup.parent as ViewGroup
        parent.addView(extras, parent.indexOfChild(binding.presetGroup) + 1)
    }

    private fun setUpPalettes() {
        binding.paletteGroup.addOnButtonCheckedListener { _, checkedId, checked ->
            if (bindingUi || !checked) return@addOnButtonCheckedListener
            theme.colorScheme = when (checkedId) {
                R.id.buttonPaletteEmerald -> ThemePalette.EMERALD
                R.id.buttonPaletteViolet -> ThemePalette.VIOLET
                R.id.buttonPaletteRose -> ThemePalette.ROSE
                R.id.buttonPaletteAmber -> ThemePalette.AMBER
                R.id.buttonPaletteIndigo -> ThemePalette.INDIGO
                R.id.buttonPaletteCoral -> ThemePalette.CORAL
                R.id.buttonPaletteLime -> ThemePalette.LIME
                R.id.buttonPaletteSky -> ThemePalette.SKY
                R.id.buttonPaletteSunset -> ThemePalette.SUNSET
                else -> ThemePalette.OCEAN
            }
            theme.touch()
            refresh()
        }
    }

    private fun chooseGlobalBackground() {
        val gradientLabels = listOf(
            R.string.background_clean, R.string.background_mist, R.string.background_aurora,
            R.string.background_dusk, R.string.background_bloom
        ).map { getString(it) }
        val wallpaperLabels = listOf(
            R.string.wallpaper_mountains, R.string.wallpaper_eucalyptus, R.string.wallpaper_lake,
            R.string.wallpaper_desert, R.string.wallpaper_lavender, R.string.wallpaper_rain,
            R.string.wallpaper_ocean, R.string.wallpaper_pastel, R.string.wallpaper_neon,
            R.string.wallpaper_coral
        ).map { getString(it) }
        val density = resources.displayMetrics.density
        val list = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val p = (12 * density).toInt()
            setPadding(p, p, p, p)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(list) }
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.background_style)
            .setView(scroll)
            .setNeutralButton(R.string.background_photo) { _, _ -> backgroundPhotoPicker.launch(arrayOf("image/*")) }
            .setNegativeButton(R.string.background_remove_photo) { _, _ -> theme.backgroundImageUri = null; theme.backgroundPreset = null; theme.touch(); refresh() }
            .create()
        fun addCard(label: String, selected: Boolean, render: (android.view.View) -> Unit, select: () -> Unit) {
            val card = com.google.android.material.card.MaterialCardView(this).apply {
                radius = 16f * density
                strokeWidth = if (selected) (2 * density).toInt() else 0
                strokeColor = theme.accentColor()
                layoutParams = android.widget.LinearLayout.LayoutParams(-1, (120 * density).toInt()).apply { bottomMargin = (8 * density).toInt() }
            }
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((8 * density).toInt(), (7 * density).toInt(), (8 * density).toInt(), (7 * density).toInt())
            }
            val preview = android.view.View(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams((64 * density).toInt(), (104 * density).toInt())
            }
            render(preview)
            row.addView(preview)
            val labelView = android.widget.TextView(this).apply {
                text = label
                textSize = 17f
                setTextColor(androidx.core.content.ContextCompat.getColor(this@AppearanceActivity, R.color.text_primary))
                gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
                setPadding((18 * density).toInt(), 0, (18 * density).toInt(), 0)
            }
            row.addView(labelView, android.widget.LinearLayout.LayoutParams(0, -1, 1f))
            card.addView(row, android.view.ViewGroup.LayoutParams(-1, -1))
            card.setOnClickListener { select(); dialog.dismiss() }
            list.addView(card)
        }
        gradientLabels.forEachIndexed { index, label ->
            val style = BackgroundStyle.IDS[index]
            addCard(label, theme.backgroundPreset == null && theme.backgroundImageUri == null && theme.backgroundStyle == style,
                { BackgroundRenderer.apply(it, this, style) }) {
                theme.backgroundStyle = style
                theme.backgroundPreset = null
                theme.backgroundImageUri = null
                theme.touch()
                BackgroundRenderer.apply(binding.preview, this, style)
            }
        }
        wallpaperLabels.forEachIndexed { index, label ->
            val preset = BuiltInWallpaper.IDS[index]
            addCard(label, theme.backgroundPreset == preset,
                { BackgroundRenderer.apply(it, this, theme.backgroundStyle, preset = preset) }) {
                theme.backgroundPreset = preset
                theme.backgroundImageUri = null
                theme.touch()
                BackgroundRenderer.apply(binding.preview, this, theme.backgroundStyle, preset = preset)
            }
        }
        dialog.show()
    }

    // ------------------------------------------------------------- helpers

    /** Writes the current preferences back into every control and the preview. */
    private fun refresh() {
        bindingUi = true
        binding.spinnerListFont.setSelection(
            listFontScales.indices.minByOrNull {
                kotlin.math.abs(listFontScales[it] - theme.listFontScale)
            } ?: 1
        )
        binding.spinnerMessageFont.setSelection(
            messageFontScales.indices.minByOrNull {
                kotlin.math.abs(messageFontScales[it] - theme.messageFontScale)
            } ?: 1
        )
        binding.spinnerRowStyle.setSelection(
            RowStyle.IDS.indexOf(theme.rowStyle).coerceAtLeast(0)
        )
        binding.spinnerMessageStyle.setSelection(
            MessageStyle.IDS.indexOf(theme.messageStyle).coerceAtLeast(0)
        )
        binding.switchDividers.isChecked = theme.showDividers
        binding.switchChips.isChecked = theme.showChips
        binding.presetGroup.clearChecked()
        presetForCurrent()?.let { preset ->
            if (preset in listOf(Preset.MODERN, Preset.COMPACT, Preset.CARDS)) {
                binding.presetGroup.check(when (preset) {
                    Preset.MODERN -> R.id.buttonPresetModern
                    Preset.COMPACT -> R.id.buttonPresetCompact
                    Preset.CARDS -> R.id.buttonPresetCards
                    else -> R.id.buttonPresetModern
                })
            }
        }
        binding.paletteGroup.check(
            when (theme.colorScheme) {
                ThemePalette.EMERALD -> R.id.buttonPaletteEmerald
                ThemePalette.VIOLET -> R.id.buttonPaletteViolet
                ThemePalette.ROSE -> R.id.buttonPaletteRose
                ThemePalette.AMBER -> R.id.buttonPaletteAmber
                ThemePalette.INDIGO -> R.id.buttonPaletteIndigo
                ThemePalette.CORAL -> R.id.buttonPaletteCoral
                ThemePalette.LIME -> R.id.buttonPaletteLime
                ThemePalette.SKY -> R.id.buttonPaletteSky
                ThemePalette.SUNSET -> R.id.buttonPaletteSunset
                else -> R.id.buttonPaletteOcean
            }
        )
        binding.textRowStyleHint.setText(
            when (theme.rowStyle) {
                RowStyle.FLAT -> R.string.row_style_flat_hint
                RowStyle.COMPACT -> R.string.row_style_compact_hint
                RowStyle.ACCENT -> R.string.row_style_accent_hint
                else -> R.string.row_style_hint
            }
        )
        bindingUi = false
        binding.preview.bind(theme.snapshot())
        BackgroundRenderer.apply(binding.preview, this, theme.backgroundStyle, theme.backgroundImageUri, theme.backgroundPreset)
    }

    /** Applies a complete, internally consistent layout in a single revision. */
    private fun applyPreset(preset: Preset) {
        theme.listFontScale = 1f
        theme.messageFontScale = 1f
        theme.rowStyle = preset.rowStyle
        theme.rowPadding = preset.padding
        theme.rowSpacing = preset.spacing
        theme.rowInset = preset.inset
        theme.listPadding = preset.listPadding
        theme.messageStyle = preset.messageStyle
        theme.messageSpacing = preset.messageSpacing
        theme.bubbleRadius = preset.radius
        theme.showDividers = preset.dividers
        theme.showChips = preset.chips
        theme.touch()
        refresh()
    }

    /** Returns a preset only when every relevant value still matches it. */
    private fun presetForCurrent(): Preset? = Preset.entries.firstOrNull { preset ->
        theme.listFontScale == 1f &&
            theme.messageFontScale == 1f &&
            theme.rowStyle == preset.rowStyle &&
            theme.rowPadding == preset.padding &&
            theme.rowSpacing == preset.spacing &&
            theme.rowInset == preset.inset &&
            theme.listPadding == preset.listPadding &&
            theme.messageStyle == preset.messageStyle &&
            theme.messageSpacing == preset.messageSpacing &&
            theme.bubbleRadius == preset.radius &&
            theme.showDividers == preset.dividers &&
            theme.showChips == preset.chips
    }

    private fun reset() {
        theme.listFontScale = 1f
        theme.messageFontScale = 1f
        theme.rowPadding = 8
        theme.rowSpacing = 0
        theme.rowInset = 0
        theme.listPadding = 8
        theme.messageSpacing = 2
        theme.bubbleRadius = 14
        theme.rowStyle = RowStyle.CLASSIC
        theme.messageStyle = MessageStyle.FILLED
        theme.showDividers = true
        theme.showChips = true
        theme.colorScheme = ThemePalette.OCEAN
        theme.backgroundStyle = BackgroundStyle.CLEAN
        theme.backgroundImageUri = null
        theme.backgroundPreset = null
        theme.surfaceOpacity = 90
        binding.sliderRowPadding.value = theme.rowPadding.toFloat()
        binding.sliderRowSpacing.value = theme.rowSpacing.toFloat()
        binding.sliderRowInset.value = theme.rowInset.toFloat()
        binding.sliderListPadding.value = theme.listPadding.toFloat()
        binding.sliderMessageSpacing.value = theme.messageSpacing.toFloat()
        binding.sliderBubbleRadius.value = theme.bubbleRadius.toFloat()
        theme.touch()
        refresh()
    }

    private fun adapter(labels: List<Int>): ArrayAdapter<String> = ArrayAdapter(
        this,
        android.R.layout.simple_spinner_dropdown_item,
        labels.map { getString(it) }
    ).also {
        it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    }

    /**
     * Spinner callbacks fire during set-up as well as on a real choice. The
     * guard keeps the first write from being treated as a user change, which
     * would bump the revision and rebuild screens that have nothing to rebuild.
     */
    private fun select(onChosen: (Int) -> Unit) =
        object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long
            ) {
                if (bindingUi) return
                onChosen(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

    /**
     * A slider reports every intermediate value while the finger moves, and the
     * preview follows it live because that is the whole point of having one.
     *
     * The preference is written once, on release. Writing on every frame would
     * rebuild the inbox behind this screen dozens of times per drag; this way
     * MainActivity sees exactly one new layout when the user comes back.
     */
    private fun Slider.onChange(write: (Int) -> Unit) {
        addOnChangeListener { slider, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            binding.preview.bind(liveLayout(slider, value))
        }
        addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            override fun onStopTrackingTouch(slider: Slider) {
                write(slider.value.toInt())
                theme.touch()
                refresh()
            }
        })
    }

    /** The snapshot during a drag, where the dragged slider's value wins. */
    private fun liveLayout(slider: Slider, value: Float): RowLayout {
        val snapshot = theme.snapshot()
        val v = value.toInt()
        return when (slider.id) {
            R.id.sliderRowPadding -> snapshot.copy(padding = v)
            R.id.sliderRowSpacing -> snapshot.copy(spacing = v)
            R.id.sliderRowInset -> snapshot.copy(inset = v)
            R.id.sliderListPadding -> snapshot.copy(listPadding = v)
            R.id.sliderMessageSpacing -> snapshot.copy(spacing = v)
            R.id.sliderBubbleRadius -> snapshot.copy(bubbleRadius = v)
            else -> snapshot
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
