package ir.inod.smsguard

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import ir.inod.smsguard.databinding.ActivityCategoriesBinding
import java.util.Collections

/** Visual category editor with direct enable, colour/icon preview and drag ordering. */
class CategoriesActivity : BaseActivity() {
    companion object {
        const val EXTRA_EDIT_CATEGORY = "edit_category"
        private const val MENU_AUTO_ORDER = 4101
        private const val MENU_AUTO_COLOR = 4102
    }
    private lateinit var binding: ActivityCategoriesBinding
    private lateinit var adapter: CategoryAdapter
    private val store by lazy { CategoryStore(this) }
    private var pendingSoundResult: ((String?) -> Unit)? = null
    private val ringtonePicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val uri: android.net.Uri? = if (android.os.Build.VERSION.SDK_INT >= 33) {
            result.data?.getParcelableExtra(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI, android.net.Uri::class.java)
        } else {
            @Suppress("DEPRECATION") result.data?.getParcelableExtra(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        }
        pendingSoundResult?.invoke(uri?.toString())
        pendingSoundResult = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCategoriesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        adapter = CategoryAdapter(store.all().toMutableList(), ::editCategory) { category, enabled ->
            if (category.id != Cat.OTHER) {
                if (enabled) setEnabled(category, true) else chooseReassignment(category)
            }
        }
        binding.recyclerCategories.layoutManager = LinearLayoutManager(this)
        binding.recyclerCategories.adapter = adapter
        binding.recyclerCategories.itemAnimator = null
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
                adapter.move(from.bindingAdapterPosition, to.bindingAdapterPosition)
                return true
            }
            override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) = Unit
            override fun clearView(rv: RecyclerView, holder: RecyclerView.ViewHolder) {
                super.clearView(rv, holder)
                store.reorder(adapter.ids())
                Classifier.invalidateCaches()
                ThreadCache.clear(this@CategoriesActivity)
            }
        }).attachToRecyclerView(binding.recyclerCategories)
        binding.buttonAddCategory.setOnClickListener { editCategory(null) }
        intent.getStringExtra(EXTRA_EDIT_CATEGORY)?.let { id ->
            store.byId(id)?.let { category ->
                binding.recyclerCategories.post { editCategory(category) }
            }
        }
    }

    private fun chooseReassignment(category: Category) {
        val destinations = store.active().filter { it.id != category.id }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (Dates.isPersian(this)) "انتقال پیام‌های این دسته" else "Move messages in this category")
            .setSingleChoiceItems(destinations.map { it.label(this) }.toTypedArray(),
                destinations.indexOfFirst { it.id == Cat.OTHER }.coerceAtLeast(0)) { dialog, selected ->
                val target = destinations[selected].id
                store.setDisabledDestination(category.id, target)
                SenderStore(this).reassignCategory(category.id, target)
                val overrides = MessageCategoryStore(this)
                val migrated = overrides.all().mapValues { (_, value) -> if (value == category.id) target else value }
                overrides.replaceAll(migrated)
                setEnabled(category, false)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel) { _, _ -> reload() }
            .setOnCancelListener { reload() }
            .show()
    }

    private fun setEnabled(category: Category, enabled: Boolean) {
        if (enabled) store.setDisabledDestination(category.id, null)
        store.updateAny(category.copy(enabled = enabled))
        Classifier.invalidateCaches()
        ThreadCache.clear(this)
        reload()
    }

    private fun reload() = adapter.replace(store.all())

    private fun editCategory(original: Category?) {
        val density = resources.displayMetrics.density
        var selectedColor = original?.colorHex ?: Categories.PALETTE.first()
        var selectedIcon = original?.iconId ?: "unknown"
        val notificationStore = CategoryNotificationStore(this)
        var alertSettings = original?.let { notificationStore.get(it.id) }
            ?: CategoryNotificationSettings()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (18 * density).toInt(); setPadding(p, p / 2, p, p)
        }
        val name = EditText(this).apply {
            hint = getString(R.string.category_name_hint)
            setText(original?.label(this@CategoriesActivity).orEmpty())
            setSingleLine(true)
        }
        body.addView(name, LinearLayout.LayoutParams(-1, -2))
        body.addView(sectionLabel(getString(R.string.pick_color)))
        val colorGrid = GridLayout(this).apply { columnCount = 6 }
        val colorViews = mutableListOf<View>()
        Categories.PALETTE.forEach { hex ->
            val swatch = View(this).apply {
                val size = (42 * density).toInt()
                layoutParams = GridLayout.LayoutParams().apply {
                    width = size; height = size; setMargins(5, 5, 5, 5)
                }
                contentDescription = hex
                setOnClickListener { selectedColor = hex; updateSwatches(colorViews, selectedColor) }
            }
            colorViews += swatch; colorGrid.addView(swatch)
        }
        body.addView(colorGrid)
        updateSwatches(colorViews, selectedColor)

        body.addView(sectionLabel(getString(R.string.choose_icon)))
        val iconGrid = GridLayout(this).apply { columnCount = 6 }
        val iconViews = mutableListOf<ImageView>()
        IconCatalog.ALL.distinctBy { it.id }.forEach { spec ->
            val icon = ImageView(this).apply {
                val size = (46 * density).toInt()
                layoutParams = GridLayout.LayoutParams().apply {
                    width = size; height = size; setMargins(4, 4, 4, 4)
                }
                setPadding(11, 11, 11, 11)
                setImageResource(spec.drawable)
                tag = spec.id
                contentDescription = spec.id
                setOnClickListener { selectedIcon = spec.id; updateIcons(iconViews, selectedIcon, selectedColor) }
            }
            iconViews += icon; iconGrid.addView(icon)
        }
        body.addView(iconGrid)
        updateIcons(iconViews, selectedIcon, selectedColor)

        body.addView(sectionLabel(getString(R.string.category_notifications)))
        val alertGroup = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.VERTICAL
        }
        val alertModes = listOf(
            CategoryAlertMode.DEFAULT to R.string.alert_default,
            CategoryAlertMode.SILENT to R.string.alert_silent,
            CategoryAlertMode.VIBRATE_ONLY to R.string.alert_vibrate_only,
            CategoryAlertMode.CUSTOM to R.string.alert_custom,
            CategoryAlertMode.OFF to R.string.alert_off
        )
        val modeIds = HashMap<Int, CategoryAlertMode>()
        val soundButton = com.google.android.material.button.MaterialButton(this).apply {
            text = getString(R.string.notification_sound)
            setIconResource(R.drawable.ic_tab_service)
            visibility = if (alertSettings.mode == CategoryAlertMode.CUSTOM) View.VISIBLE else View.GONE
            setOnClickListener {
                pendingSoundResult = { sound ->
                    alertSettings = alertSettings.copy(soundUri = sound, mode = CategoryAlertMode.CUSTOM)
                    text = getString(R.string.alert_custom)
                }
                ringtonePicker.launch(android.content.Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                    putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_NOTIFICATION)
                    putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                    putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                    alertSettings.soundUri?.let { putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, android.net.Uri.parse(it)) }
                })
            }
        }
        alertModes.forEach { (mode, label) ->
            val radio = android.widget.RadioButton(this).apply {
                id = View.generateViewId()
                text = getString(label)
                minHeight = (48 * density).toInt()
                isChecked = alertSettings.mode == mode
            }
            modeIds[radio.id] = mode
            alertGroup.addView(radio)
        }
        alertGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = modeIds[checkedId] ?: return@setOnCheckedChangeListener
            alertSettings = alertSettings.copy(mode = mode)
            soundButton.visibility = if (mode == CategoryAlertMode.CUSTOM) View.VISIBLE else View.GONE
        }
        body.addView(alertGroup)
        body.addView(soundButton)
        fun settingSwitch(label: Int, checked: Boolean, update: (Boolean) -> Unit) =
            SwitchMaterial(this).apply {
                text = getString(label)
                isChecked = checked
                minHeight = (48 * density).toInt()
                setOnCheckedChangeListener { _, value -> update(value) }
            }
        body.addView(settingSwitch(R.string.notification_vibrate, alertSettings.vibrate) {
            alertSettings = alertSettings.copy(vibrate = it)
        })
        body.addView(settingSwitch(R.string.notification_lockscreen, alertSettings.showOnLockScreen) {
            alertSettings = alertSettings.copy(showOnLockScreen = it)
        })
        body.addView(settingSwitch(R.string.notification_wake, alertSettings.wakeScreen) {
            alertSettings = alertSettings.copy(wakeScreen = it)
        })

        val scroll = ScrollView(this).apply { addView(body) }
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(if (original == null) R.string.new_category else R.string.edit_category)
            .setView(scroll)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
        if (original != null && !original.isSystem) {
            builder.setNeutralButton(R.string.delete, null)
        }
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = name.text?.toString()?.trim().orEmpty()
                if (title.isBlank()) { name.error = getString(R.string.category_name_hint); return@setOnClickListener }
                if (original == null) {
                    val created = store.add(title, selectedColor)
                    store.update(created.copy(iconId = selectedIcon))
                    notificationStore.set(created.id, alertSettings)
                    Notifier(this).resetCategoryChannels(created.id)
                } else {
                    store.updateAny(original.copy(nameRes = 0, customName = title, colorHex = selectedColor, iconId = selectedIcon))
                    notificationStore.set(original.id, alertSettings)
                    Notifier(this).resetCategoryChannels(original.id)
                }
                Classifier.invalidateCaches(); ThreadCache.clear(this); reload(); dialog.dismiss()
            }
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                original?.let { store.delete(it.id) }
                Classifier.invalidateCaches(); ThreadCache.clear(this); reload(); dialog.dismiss()
                Toast.makeText(this, R.string.cleared, Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }

    private fun sectionLabel(value: String) = TextView(this).apply {
        text = value; textSize = 13f; setTextColor(ContextCompat.getColor(this@CategoriesActivity, R.color.text_secondary))
        setPadding(0, (16 * resources.displayMetrics.density).toInt(), 0, 5)
    }

    private fun updateSwatches(views: List<View>, selected: String) = views.forEach { view ->
        val hex = view.contentDescription.toString()
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(Color.parseColor(hex))
            if (hex == selected) setStroke((3 * resources.displayMetrics.density).toInt(), Color.WHITE)
        }
        view.scaleX = if (hex == selected) 1.12f else 1f
        view.scaleY = view.scaleX
    }

    private fun updateIcons(views: List<ImageView>, selected: String, color: String) = views.forEach { view ->
        val active = view.tag == selected
        view.imageTintList = ColorStateList.valueOf(if (active) Color.WHITE else Color.parseColor(color))
        view.background = GradientDrawable().apply {
            cornerRadius = 13f * resources.displayMetrics.density
            setColor(if (active) Color.parseColor(color) else Color.argb(22, Color.red(Color.parseColor(color)), Color.green(Color.parseColor(color)), Color.blue(Color.parseColor(color))))
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_AUTO_ORDER) {
            val ordered = store.all().sortedWith(compareBy<Category> { !it.isSystem }.thenBy { if (it.isSystem) Categories.system().indexOfFirst { base -> base.id == it.id }.coerceAtLeast(99) else it.order })
            store.reorder(ordered.map { it.id }); reload(); return true
        }
        if (item.itemId == MENU_AUTO_COLOR) {
            store.all().forEachIndexed { index, category ->
                store.updateAny(category.copy(colorHex = Categories.PALETTE[index % Categories.PALETTE.size]))
            }
            Classifier.invalidateCaches(); ThreadCache.clear(this); reload(); return true
        }
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menu.add(0, MENU_AUTO_ORDER, 0, R.string.auto_arrange).setIcon(R.drawable.ic_tab_all)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, MENU_AUTO_COLOR, 1, R.string.auto_colors).setIcon(R.drawable.ic_cat_shop)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        return true
    }

    private inner class CategoryAdapter(
        private val items: MutableList<Category>,
        private val edit: (Category) -> Unit,
        private val toggle: (Category, Boolean) -> Unit
    ) : RecyclerView.Adapter<CategoryAdapter.Holder>() {
        inner class Holder(val card: MaterialCardView, val iconBox: FrameLayout, val icon: ImageView,
            val title: TextView, val subtitle: TextView, val switch: SwitchMaterial) : RecyclerView.ViewHolder(card)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val d = parent.resources.displayMetrics.density
            val card = SecondaryUi.listCard(parent.context)
            val gap = SecondaryUi.px(parent.context, R.dimen.space_4)
            (card.layoutParams as RecyclerView.LayoutParams).setMargins(0, gap, 0, gap)
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding((14*d).toInt(), (12*d).toInt(), (10*d).toInt(), (12*d).toInt())
            }
            val iconBox = FrameLayout(parent.context).apply { layoutParams = LinearLayout.LayoutParams((48*d).toInt(), (48*d).toInt()) }
            val icon = ImageView(parent.context).apply { setPadding((12*d).toInt(), (12*d).toInt(), (12*d).toInt(), (12*d).toInt()) }
            iconBox.addView(icon, FrameLayout.LayoutParams(-1, -1))
            val texts = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = (12*d).toInt() }
            }
            val title = TextView(parent.context).apply { setTextAppearance(R.style.TextAppearance_SmsGuard_Title) }
            val subtitle = TextView(parent.context).apply { setTextAppearance(R.style.TextAppearance_SmsGuard_Label) }
            texts.addView(title); texts.addView(subtitle)
            val toggle = SwitchMaterial(parent.context).apply { showText = false }
            val drag = TextView(parent.context).apply { text = "≡"; textSize = 25f; gravity = Gravity.CENTER; setTextColor(ContextCompat.getColor(context, R.color.text_muted)); contentDescription = getString(R.string.categories_drag_hint) }
            row.addView(iconBox); row.addView(texts); row.addView(toggle, LinearLayout.LayoutParams((52*d).toInt(), -2)); row.addView(drag, LinearLayout.LayoutParams((36*d).toInt(), (48*d).toInt()))
            card.addView(row)
            return Holder(card, iconBox, icon, title, subtitle, toggle)
        }

        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            val color = runCatching { Color.parseColor(item.colorHex) }.getOrDefault(Color.GRAY)
            holder.title.text = item.label(this@CategoriesActivity)
            val alert = CategoryNotificationStore(this@CategoriesActivity).get(item.id)
            val alertLabel = getString(when (alert.mode) {
                CategoryAlertMode.DEFAULT -> R.string.notification_summary_default
                CategoryAlertMode.SILENT -> R.string.notification_summary_silent
                CategoryAlertMode.VIBRATE_ONLY -> R.string.alert_vibrate_only
                CategoryAlertMode.CUSTOM -> R.string.notification_summary_custom
                CategoryAlertMode.OFF -> R.string.notification_summary_off
            })
            holder.subtitle.text = getString(if (item.enabled) R.string.category_enabled else R.string.category_disabled) + " · " +
                getString(if (item.isSystem) R.string.category_builtin else R.string.category_custom) + " · " + alertLabel
            holder.icon.setImageResource((IconCatalog.byId(item.iconId) ?: IconCatalog.forCategory(item.id)).drawable)
            holder.icon.imageTintList = ColorStateList.valueOf(color)
            holder.iconBox.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(28, Color.red(color), Color.green(color), Color.blue(color))) }
            holder.card.strokeColor = Color.argb(if (item.enabled) 85 else 30, Color.red(color), Color.green(color), Color.blue(color))
            holder.card.setCardBackgroundColor(if (item.enabled) Color.argb(12, Color.red(color), Color.green(color), Color.blue(color)) else ContextCompat.getColor(this@CategoriesActivity, R.color.card_bg))
            holder.switch.setOnCheckedChangeListener(null)
            holder.switch.isChecked = item.enabled
            holder.switch.isEnabled = item.id != Cat.OTHER
            holder.switch.setOnCheckedChangeListener { _, checked -> toggle(item, checked) }
            holder.card.alpha = if (item.enabled) 1f else .62f
            holder.card.setOnClickListener { edit(item) }
        }

        fun move(from: Int, to: Int) {
            if (from !in items.indices || to !in items.indices) return
            Collections.swap(items, from, to); notifyItemMoved(from, to)
        }
        fun ids() = items.map { it.id }
        fun replace(next: List<Category>) { items.clear(); items.addAll(next); notifyDataSetChanged() }
    }
}
