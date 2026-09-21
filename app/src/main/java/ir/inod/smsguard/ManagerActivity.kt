package ir.inod.smsguard

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import ir.inod.smsguard.databinding.ActivityManagerBinding

/**
 * Brand and blocklist management, ported from the reference's
 * `BrandManagerPage` and blocked-numbers page.
 *
 * Per sender the user can set a display name, category, colour and catalog icon;
 * the override beats the brand catalog, which beats the detected category.
 */
class ManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityManagerBinding
    private val senders by lazy { SenderStore(this) }
    private val blocks by lazy { BlockStore(this) }
    private var tabIndex = 0

    private data class Row(
        val title: String,
        val subtitle: String,
        val iconRes: Int?,
        val colorHex: String,
        val action: () -> Unit
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
        binding.recycler.adapter = RowAdapter()
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
                title = override.displayName ?: override.address,
                subtitle = if (category.isBlank()) override.address else category,
                iconRes = icon?.drawable,
                colorHex = color,
                action = { editOverride(override) }
            )
        }

    private fun blockedRows(): List<Row> {
        val list = mutableListOf<Row>()
        blocks.prefixes().sorted().forEach { prefix ->
            list.add(
                Row(
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
                    }
                )
            )
        }
        blocks.domains().sorted().forEach { domain ->
            list.add(
                Row(
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
                    }
                )
            )
        }
        senders.allOverrides()
            .filter { it.categoryId == Cat.SPAM }
            .forEach { spam ->
                list.add(
                    Row(
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
                        }
                    )
                )
            }
        return list
    }

    private fun load() {
        val data = try {
            rows()
        } catch (t: Throwable) {
            emptyList()
        }
        (binding.recycler.adapter as RowAdapter).submit(data)
        binding.textEmpty.setText(
            if (tabIndex == 0) R.string.no_overrides else R.string.blocked_none
        )
        binding.textEmpty.visibility = if (data.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun confirm(titleRes: Int, message: String, onYes: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setMessage(message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ -> onYes() }
            .show()
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
        MaterialAlertDialogBuilder(this)
            .setTitle(override.displayName ?: override.address)
            .setItems(options) { _, which ->
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
            .show()
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
        val input = EditText(this).apply {
            hint = getString(R.string.choose_name)
            setText(override.displayName ?: "")
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_name)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ ->
                senders.setName(override.address, input.text?.toString())
                Classifier.invalidateCaches()
                load()
            }
            .show()
    }

    private fun pickCategory(override: SenderOverride) {
        val cats = CategoryStore(this).all()
        val labels = cats.map { it.label(this) }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.change_category)
            .setItems(labels) { _, which ->
                senders.setCategory(override.address, cats[which].id)
                Classifier.invalidateCaches()
                load()
            }
            .show()
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
    private class RowAdapter : RecyclerView.Adapter<RowAdapter.VH>() {

        private val items = mutableListOf<Row>()

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
                )
                isClickable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
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

            holder.circle.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(row.colorHex))
            }

            val letter = AvatarHelper.monogram(row.title)
            if (row.iconRes != null) {
                holder.letter.text = null
                holder.image.setImageResource(row.iconRes)
            } else if (letter != null) {
                holder.image.setImageDrawable(null)
                holder.letter.text = letter
            } else {
                holder.image.setImageResource(R.drawable.ic_person)
                holder.letter.text = null
            }

            holder.root.setOnClickListener { row.action() }
        }
    }
}
