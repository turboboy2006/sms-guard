package ir.inod.smsguard

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import ir.inod.smsguard.databinding.ActivityManagerBinding

/**
 * Brand and blocklist management, ported from the reference's
 * `BrandManagerPage` and blocked-numbers page.
 *
 * Per sender the user can set a display name, category, colour and catalog icon;
 * the override beats the brand catalog, which beats the detected category.
 */
class ManagerActivity : BaseActivity() {

    private companion object { const val MENU_CLEAR_SELECTED = 5101 }

    private lateinit var binding: ActivityManagerBinding
    private val senders by lazy { SenderStore(this) }
    private val blocks by lazy { BlockStore(this) }
    private var tabIndex = 0
    private lateinit var addButton: MaterialButton

    private data class Row(
        val key: String,
        val title: String,
        val subtitle: String,
        val iconRes: Int?,
        val colorHex: String,
        val action: () -> Unit,
        val clear: () -> Unit
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.tabs.addTab(binding.tabs.newTab().setText(R.string.appearance))
        binding.tabs.addTab(binding.tabs.newTab().setText(R.string.blocked_numbers))
        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                tabIndex = tab.position
                load()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.editFilter.doAfterTextChanged { load() }
        addButton = MaterialButton(this).apply {
            setIconResource(R.drawable.ic_compose)
            setOnClickListener { addEntry() }
        }
        (binding.root as LinearLayout).addView(addButton, 3,
            LinearLayout.LayoutParams(-1, -2).apply {
                val margin = (16 * resources.displayMetrics.density).toInt()
                setMargins(margin, margin / 3, margin, margin / 3)
            })
        binding.recycler.adapter = RowAdapter { count ->
            supportActionBar?.title = if (count > 0) Dates.count(this, count)
                else getString(R.string.manage_brands)
            invalidateOptionsMenu()
        }
        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun rows(): List<Row> = if (tabIndex == 0) appearanceRows() else blockedRows()

    private fun appearanceRows(): List<Row> =
        senders.allOverrides().map { override ->
            val icon = IconCatalog.byId(override.iconId)
            val color = override.colorHex
                ?: icon?.colorHex
                ?: "#667085"
            val category = override.categoryId
                ?.let { CategoryStore(this).byId(it)?.label(this) }
                .orEmpty()
            Row(
                key = "sender:${override.address}",
                title = override.displayName ?: override.address,
                subtitle = if (category.isBlank()) override.address else category,
                iconRes = icon?.drawable,
                colorHex = color,
                action = { editOverride(override) },
                clear = { senders.clearOverride(override.address) }
            )
        }

    private fun blockedRows(): List<Row> {
        val list = mutableListOf<Row>()
        blocks.prefixes().sorted().forEach { prefix ->
            list.add(
                Row(
                    key = "prefix:$prefix",
                    title = prefix,
                    subtitle = getString(R.string.blocked_number_hint),
                    iconRes = IconCatalog.byId("warning")?.drawable,
                    colorHex = "#B91C1C",
                    action = {
                        confirm(
                            R.string.unblock,
                            getString(R.string.confirm_unblock, prefix)
                        ) {
                            blocks.unblockPrefix(prefix)
                            load()
                        }
                    },
                    clear = { blocks.unblockPrefix(prefix) }
                )
            )
        }
        blocks.domains().sorted().forEach { domain ->
            list.add(
                Row(
                    key = "domain:$domain",
                    title = domain,
                    subtitle = getString(R.string.blocked_domain_hint),
                    iconRes = IconCatalog.byId("security")?.drawable,
                    colorHex = "#B91C1C",
                    action = {
                        confirm(
                            R.string.unblock,
                            getString(R.string.confirm_unblock, domain)
                        ) {
                            blocks.unblockDomain(domain)
                            load()
                        }
                    },
                    clear = { blocks.unblockDomain(domain) }
                )
            )
        }
        senders.allOverrides()
            .filter { it.categoryId == Cat.SPAM }
            .forEach { spam ->
                list.add(
                    Row(
                        key = "spam:${spam.address}",
                        title = spam.displayName ?: spam.address,
                        subtitle = getString(R.string.blocked_sender_hint),
                        iconRes = IconCatalog.byId("warning")?.drawable,
                        colorHex = "#B91C1C",
                        action = {
                            confirm(
                                R.string.unblock,
                                getString(R.string.confirm_unblock, spam.address)
                            ) {
                                senders.setCategory(spam.address, Cat.OTHER)
                                Classifier.invalidateCaches()
                                load()
                            }
                        },
                        clear = { senders.setCategory(spam.address, Cat.OTHER) }
                    )
                )
            }
        return list
    }

    private fun load() {
        addButton.text = if (tabIndex == 0) getString(R.string.manage_brands)
            else getString(R.string.blocked_numbers)
        val data = try {
            rows()
        } catch (t: Throwable) {
            emptyList()
        }
        val needle = binding.editFilter.text?.toString().orEmpty().trim()
        val filtered = if (needle.isBlank()) data else data.filter {
            it.title.contains(needle, true) || it.subtitle.contains(needle, true)
        }
        (binding.recycler.adapter as RowAdapter).submit(filtered)
        binding.textEmptyLabel.setText(
            if (tabIndex == 0) R.string.no_overrides else R.string.blocked_none
        )
        binding.textEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun addEntry() {
        if (tabIndex == 0) {
            InputSheet.show(this, getString(R.string.manage_brands),
                getString(R.string.sender_address_hint), icon = R.drawable.ic_person) { address ->
                if (address.isNotBlank()) {
                    senders.setName(address.trim(), address.trim())
                    load()
                }
            }
        } else {
            ChoiceSheet.show(this, getString(R.string.blocked_numbers), listOf(
                ChoiceSheet.Option(getString(R.string.blocked_number_hint), R.drawable.ic_person),
                ChoiceSheet.Option(getString(R.string.blocked_domain_hint), R.drawable.ic_cat_security)
            )) { kind ->
                InputSheet.show(this, getString(R.string.blocked_numbers),
                    if (kind == 0) getString(R.string.blocked_number_hint)
                    else getString(R.string.blocked_domain_hint),
                    icon = if (kind == 0) R.drawable.ic_person else R.drawable.ic_cat_security) { value ->
                    if (value.isNotBlank()) {
                        if (kind == 0) blocks.blockPrefix(value.trim())
                        else blocks.blockDomain(value.trim())
                        load()
                    }
                }
            }
        }
    }

    private fun confirm(titleRes: Int, message: String, onYes: () -> Unit) {
        ConfirmSheet.show(this, getString(titleRes), message, R.drawable.ic_warning, onConfirm = onYes)
    }

    // ------------------------------------------------- per-sender appearance

    private fun editOverride(override: SenderOverride) {
        val options = arrayOf(
            getString(R.string.choose_icon),
            getString(R.string.choose_name),
            getString(R.string.change_category),
            getString(R.string.pick_color),
            getString(R.string.clear_override)
        )
        val icons = listOf(R.drawable.ic_person, R.drawable.ic_cat_receipt,
            R.drawable.ic_tab_all, R.drawable.ic_cat_shop, R.drawable.ic_tab_trash)
        ChoiceSheet.show(this, override.displayName ?: override.address,
            options.mapIndexed { index, label -> ChoiceSheet.Option(label, icons[index]) }) { which ->
                when (which) {
                    0 -> pickIcon(override)
                    1 -> pickName(override)
                    2 -> pickCategory(override)
                    3 -> pickColor(override)
                    4 -> confirm(
                        R.string.clear_override,
                        getString(R.string.confirm_clear_override)
                    ) {
                        senders.setIcon(override.address, null)
                        senders.setColor(override.address, null)
                        Classifier.invalidateCaches()
                        load()
                    }
                }
            }
    }

    /** Catalog icons laid out as a tap grid, grouped by their catalogue group. */
    private fun pickIcon(override: SenderOverride) {
        val density = resources.displayMetrics.density
        val cell = (48 * density).toInt()
        val pad = (6 * density).toInt()
        val grid = android.widget.GridLayout(this).apply {
            columnCount = 5
            setPadding(pad * 2, pad * 2, pad * 2, pad * 2)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_icon)
            .setView(grid)
            .setNeutralButton(R.string.clear_override) { _, _ ->
                senders.setIcon(override.address, null)
                Classifier.invalidateCaches()
                load()
            }
            .setNegativeButton(R.string.cancel, null)
            .create()

        IconCatalog.ALL.forEach { spec ->
            val wrap = FrameLayout(this).apply {
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = cell + pad * 2
                    height = cell + pad * 2
                }
                setPadding(pad, pad, pad, pad)
            }
            val circle = FrameLayout(this).apply {
                layoutParams = FrameLayout.LayoutParams(cell, cell)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor(spec.colorHex))
                }
            }
            val image = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
                setPadding((12 * density).toInt(), (12 * density).toInt(),
                    (12 * density).toInt(), (12 * density).toInt())
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setImageResource(spec.drawable)
            }
            circle.addView(image)
            wrap.addView(circle)
            wrap.setOnClickListener {
                senders.setIcon(override.address, spec.id)
                dialog.dismiss()
                Classifier.invalidateCaches()
                load()
            }
            grid.addView(wrap)
        }
        dialog.show()
    }

    private fun pickName(override: SenderOverride) {
        InputSheet.show(this, getString(R.string.choose_name), getString(R.string.choose_name),
            override.displayName.orEmpty(), R.drawable.ic_person) { value ->
                senders.setName(override.address, value)
                Classifier.invalidateCaches()
                load()
            }
    }

    private fun pickCategory(override: SenderOverride) {
        val cats = CategoryStore(this).all()
        val labels = cats.map { it.label(this) }.toTypedArray()
        ChoiceSheet.show(this, getString(R.string.change_category),
            cats.map { category -> ChoiceSheet.Option(category.label(this),
                (IconCatalog.byId(category.iconId) ?: IconCatalog.forCategory(category.id)).drawable,
                runCatching { Color.parseColor(category.colorHex) }.getOrNull()) }) { which ->
                senders.setCategory(override.address, cats[which].id)
                Classifier.invalidateCaches()
                load()
            }
    }

    private fun pickColor(override: SenderOverride) {
        val density = resources.displayMetrics.density
        val cell = (44 * density).toInt()
        val pad = (8 * density).toInt()
        val grid = android.widget.GridLayout(this).apply {
            columnCount = 4
            setPadding(pad * 2, pad * 2, pad * 2, pad * 2)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pick_color)
            .setView(grid)
            .setNegativeButton(R.string.cancel, null)
            .create()

        Categories.PALETTE.forEach { hex ->
            val wrap = FrameLayout(this).apply {
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = cell + pad * 2
                    height = cell + pad * 2
                }
                setPadding(pad, pad, pad, pad)
            }
            wrap.addView(
                View(this).apply {
                    layoutParams = FrameLayout.LayoutParams(cell, cell)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor(hex))
                        if (hex == override.colorHex) {
                            setStroke((3 * density).toInt(), Color.WHITE)
                        }
                    }
                }
            )
            wrap.setOnClickListener {
                senders.setColor(override.address, hex)
                dialog.dismiss()
                Classifier.invalidateCaches()
                load()
            }
            grid.addView(wrap)
        }
        dialog.show()
    }

    // ------------------------------------------------------- programmatic rows

    /**
     * Rows are built in code rather than inflated, so this screen needs no item
     * layout and stays consistent with the rest of the app's token usage.
     */
    private class RowAdapter(
        private val onSelectionChanged: (Int) -> Unit
    ) : RecyclerView.Adapter<RowAdapter.VH>() {

        private val items = mutableListOf<Row>()
        private val selected = linkedSetOf<String>()
        val selectionCount: Int get() = selected.size

        fun selectedRows(): List<Row> = items.filter { it.key in selected }

        fun clearSelection() {
            if (selected.isEmpty()) return
            selected.clear()
            notifyDataSetChanged()
            onSelectionChanged(0)
        }

        fun submit(list: List<Row>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        class VH(val root: LinearLayout, val circle: FrameLayout,
                 val image: ImageView, val letter: TextView,
                 val title: TextView, val subtitle: TextView) :
            RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val density = parent.resources.displayMetrics.density
            val root = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    (16 * density).toInt(), (12 * density).toInt(),
                    (16 * density).toInt(), (12 * density).toInt()
                )
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    val h = (12 * density).toInt()
                    setMargins(h, (4 * density).toInt(), h, (4 * density).toInt())
                }
                isClickable = true
                background = GradientDrawable().apply {
                    cornerRadius = 18 * density
                    setColor(ContextCompat.getColor(parent.context, R.color.card_bg))
                    setStroke((1 * density).toInt().coerceAtLeast(1),
                        ContextCompat.getColor(parent.context, R.color.divider))
                }
            }
            val circle = FrameLayout(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (44 * density).toInt(), (44 * density).toInt()
                )
            }
            val image = ImageView(parent.context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
                setPadding((11 * density).toInt(), (11 * density).toInt(),
                    (11 * density).toInt(), (11 * density).toInt())
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }
            val letter = TextView(parent.context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 17f
            }
            circle.addView(image)
            circle.addView(letter)

            val texts = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = (12 * density).toInt() }
            }
            val title = TextView(parent.context).apply {
                setTextColor(ContextCompat.getColor(parent.context, R.color.text_primary))
                textSize = 16f
            }
            val subtitle = TextView(parent.context).apply {
                setTextColor(ContextCompat.getColor(parent.context, R.color.text_secondary))
                textSize = 13f
            }
            texts.addView(title)
            texts.addView(subtitle)

            root.addView(circle)
            root.addView(texts)
            return VH(root, circle, image, letter, title, subtitle)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = items[position]
            holder.title.text = row.title
            TextDir.apply(holder.title, row.title)
            holder.subtitle.text = row.subtitle
            TextDir.apply(holder.subtitle, row.subtitle)

            // Same avatar language as the inbox: a known business keeps its own
            // colour with a white glyph, a person gets a soft tinted initial.
            val letter = AvatarHelper.monogram(row.title)
            val (container, ink) = if (row.iconRes != null) {
                parseHex(row.colorHex) to Color.WHITE
            } else {
                AvatarHelper.softPair(row.title)
            }
            holder.circle.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(container)
            }
            holder.letter.setTextColor(ink)

            when {
                row.iconRes != null -> {
                    holder.letter.text = null
                    holder.image.setImageResource(row.iconRes)
                }
                letter != null -> {
                    holder.image.setImageDrawable(null)
                    holder.letter.text = letter
                }
                else -> {
                    holder.image.setImageResource(R.drawable.ic_person)
                    holder.image.setColorFilter(
                        ContextCompat.getColor(holder.itemView.context, R.color.text_muted)
                    )
                    holder.letter.text = null
                }
            }

            holder.root.setOnClickListener {
                if (selected.isEmpty()) row.action() else toggle(row)
            }
            holder.root.setOnLongClickListener {
                toggle(row)
                true
            }
            holder.root.setBackgroundColor(
                ContextCompat.getColor(
                    holder.itemView.context,
                    if (row.key in selected) R.color.selection_bg else android.R.color.transparent
                )
            )
        }

        private fun toggle(row: Row) {
            if (!selected.add(row.key)) selected.remove(row.key)
            val position = items.indexOfFirst { it.key == row.key }
            if (position >= 0) notifyItemChanged(position)
            onSelectionChanged(selected.size)
        }

        private fun parseHex(hex: String): Int = try {
            Color.parseColor(hex)
        } catch (e: Exception) {
            Color.GRAY
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_CLEAR_SELECTED, 0, R.string.clear_override)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val adapter = binding.recycler.adapter as? RowAdapter
        menu.findItem(MENU_CLEAR_SELECTED)?.isVisible = (adapter?.selectionCount ?: 0) > 0
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_CLEAR_SELECTED) {
            val adapter = binding.recycler.adapter as RowAdapter
            adapter.selectedRows().forEach { it.clear() }
            adapter.clearSelection()
            Classifier.invalidateCaches()
            load()
            return true
        }
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    @Deprecated("Handled for selection mode")
    override fun onBackPressed() {
        val adapter = binding.recycler.adapter as? RowAdapter
        if ((adapter?.selectionCount ?: 0) > 0) adapter?.clearSelection()
        else super.onBackPressed()
    }
}
