package ir.inod.smsguard

import android.os.Bundle
import android.view.MenuItem
import android.widget.AdapterView
import android.widget.ArrayAdapter
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
        MODERN(RowStyle.CLASSIC, 12, 0, 0, 8, MessageStyle.FILLED, 2, 14, true, true),
        COMPACT(RowStyle.COMPACT, 6, 0, 0, 4, MessageStyle.CLEAN, 2, 12, false, true),
        CARDS(RowStyle.CARD, 10, 8, 4, 12, MessageStyle.SOFT, 8, 18, false, true)
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
        binding.buttonReset.setOnClickListener { reset() }
        refresh()
    }

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
            binding.presetGroup.check(
                when (preset) {
                    Preset.MODERN -> R.id.buttonPresetModern
                    Preset.COMPACT -> R.id.buttonPresetCompact
                    Preset.CARDS -> R.id.buttonPresetCards
                }
            )
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
        theme.rowPadding = 12
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
