package ir.inod.smsguard

import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar

/** Local-only saved messages with tabs, search, multi-select and undo. */
class SavedMessagesActivity : BaseActivity() {
    private companion object { const val MENU_STAR = 1; const val MENU_DELETE = 2 }
    private val store by lazy { SavedMessageStore(this) }
    private lateinit var adapter: SavedAdapter
    private lateinit var empty: TextView
    private lateinit var search: EditText
    private lateinit var toolbar: MaterialToolbar
    private lateinit var allTab: MaterialButton
    private lateinit var starredTab: MaterialButton
    private var starredOnly = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(ContextCompat.getColor(this@SavedMessagesActivity, R.color.screen_bg)) }
        toolbar = MaterialToolbar(this).apply { title = getString(R.string.saved_messages); setNavigationIcon(R.drawable.ic_chevron); setNavigationOnClickListener { finish() } }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(64)))
        search = EditText(this).apply { hint = getString(R.string.search_hint); setSingleLine(true); setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0); compoundDrawablePadding = dp(10); setPadding(dp(18), dp(5), dp(18), dp(5)) }
        root.addView(search, LinearLayout.LayoutParams(-1, dp(52)))
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(4), dp(12), dp(8)) }
        allTab = tab(getString(R.string.saved_messages), R.drawable.ic_pin) { starredOnly = false; render() }
        starredTab = tab(getString(R.string.starred), R.drawable.ic_star) { starredOnly = true; render() }
        tabs.addView(allTab, LinearLayout.LayoutParams(0, dp(42), 1f)); tabs.addView(starredTab, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(8) }); root.addView(tabs)
        val frame = FrameLayout(this)
        adapter = SavedAdapter(
            onClick = { if (adapter.selectionCount > 0) adapter.toggle(it) }, onLongClick = { adapter.toggle(it) },
            onStar = { item -> store.setStarred(item.id, !item.starred); render() }, onNote = ::editNote,
            onDelete = ::deleteOne, onSelection = { count -> toolbar.title = if (count > 0) Dates.count(this, count) else getString(R.string.saved_messages); invalidateOptionsMenu() }
        )
        frame.addView(RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@SavedMessagesActivity); adapter = this@SavedMessagesActivity.adapter; clipToPadding = false; setPadding(dp(12), dp(4), dp(12), dp(88)) }, FrameLayout.LayoutParams(-1, -1))
        empty = TextView(this).apply { gravity = Gravity.CENTER; textSize = 16f; setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_star, 0, 0); compoundDrawablePadding = dp(14); setPadding(dp(24), dp(60), dp(24), dp(60)) }
        frame.addView(empty, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER)); root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        val compose = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(6), dp(10), dp(6)); setBackgroundColor(ContextCompat.getColor(this@SavedMessagesActivity, R.color.card_bg)) }
        val note = EditText(this).apply { hint = getString(R.string.saved_note_hint); maxLines = 4; background = null }; compose.addView(note, LinearLayout.LayoutParams(0, -2, 1f))
        compose.addView(MaterialButton(this).apply { text = ""; setIconResource(R.drawable.ic_send); contentDescription = getString(R.string.save); setOnClickListener { note.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { store.addNote(it); note.setText(""); starredOnly = false; render() } } }, LinearLayout.LayoutParams(dp(52), dp(48))); root.addView(compose)
        setContentView(root); setSupportActionBar(toolbar); search.doAfterTextChanged { render() }; render()
    }

    private fun tab(text: String, icon: Int, action: () -> Unit) = MaterialButton(this).apply { this.text = text; setIconResource(icon); setOnClickListener { action() } }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun render() {
        val needle = search.text?.toString().orEmpty().trim().lowercase()
        var items = store.all().filter { !starredOnly || it.starred }
        if (needle.isNotBlank()) items = items.filter { it.body.lowercase().contains(needle) || it.note.lowercase().contains(needle) || ContactNames.displayNameUi(it.address).lowercase().contains(needle) }
        adapter.submit(items); empty.text = getString(R.string.no_saved_messages); empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE; allTab.alpha = if (!starredOnly) 1f else .55f; starredTab.alpha = if (starredOnly) 1f else .55f
    }
    private fun editNote(item: SavedMessage) = InputSheet.show(this, getString(R.string.note_optional), getString(R.string.note_optional), item.note, R.drawable.ic_compose, true) { text -> store.updateNote(item.id, text); render() }
    private fun deleteOne(item: SavedMessage) { store.remove(item.id); render(); Snackbar.make(search, R.string.applied, Snackbar.LENGTH_LONG).setAction(R.string.undo) { store.restore(item); render() }.show() }
    private fun deleteSelected() { val removed = adapter.selected(); removed.forEach { store.remove(it.id) }; adapter.clear(); render(); Snackbar.make(search, R.string.applied, Snackbar.LENGTH_LONG).setAction(R.string.undo) { removed.forEach(store::restore); render() }.show() }
    override fun onCreateOptionsMenu(menu: Menu): Boolean { menu.add(0, MENU_STAR, 0, R.string.starred).setIcon(R.drawable.ic_star).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM); menu.add(0, MENU_DELETE, 1, R.string.delete).setIcon(R.drawable.ic_tab_trash).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM); return true }
    override fun onPrepareOptionsMenu(menu: Menu): Boolean { val selected = adapter.selectionCount > 0; menu.findItem(MENU_STAR)?.isVisible = selected; menu.findItem(MENU_DELETE)?.isVisible = selected; return true }
    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) { MENU_STAR -> { adapter.selected().forEach { store.setStarred(it.id, true) }; adapter.clear(); render(); true }; MENU_DELETE -> { deleteSelected(); true }; android.R.id.home -> { finish(); true }; else -> super.onOptionsItemSelected(item) }
    @Deprecated("Selection mode") override fun onBackPressed() { if (adapter.selectionCount > 0) adapter.clear() else super.onBackPressed() }

    private class SavedAdapter(private val onClick: (SavedMessage) -> Unit, private val onLongClick: (SavedMessage) -> Unit, private val onStar: (SavedMessage) -> Unit, private val onNote: (SavedMessage) -> Unit, private val onDelete: (SavedMessage) -> Unit, private val onSelection: (Int) -> Unit) : RecyclerView.Adapter<SavedAdapter.Holder>() {
        private val items = mutableListOf<SavedMessage>(); private val selected = linkedSetOf<Long>(); val selectionCount get() = selected.size
        class Holder(val card: MaterialCardView, val title: TextView, val meta: TextView, val body: TextView, val note: TextView, val star: MaterialButton, val edit: MaterialButton, val delete: MaterialButton) : RecyclerView.ViewHolder(card)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder { val c = parent.context; val d = c.resources.displayMetrics.density; fun dp(v: Int) = (v*d).toInt(); val card = MaterialCardView(c).apply { radius=dp(18).toFloat(); strokeWidth=dp(1); layoutParams=RecyclerView.LayoutParams(-1,-2).apply { bottomMargin=dp(8) } }; val box=LinearLayout(c).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(14),dp(12),dp(10),dp(8)) }; val title=TextView(c).apply { textSize=16f; setTypeface(null,1) }; val meta=TextView(c).apply { textSize=12f; setTextColor(ContextCompat.getColor(c,R.color.text_secondary)) }; val body=TextView(c).apply { textSize=16f; setPadding(dp(10),dp(8),dp(10),dp(8)); textDirection=View.TEXT_DIRECTION_FIRST_STRONG }; val note=TextView(c).apply { textSize=14f; setPadding(dp(10),dp(6),dp(10),dp(6)); textDirection=View.TEXT_DIRECTION_FIRST_STRONG }; val actions=LinearLayout(c); fun action(icon:Int, desc:Int)=MaterialButton(c).apply { text=""; setIconResource(icon); contentDescription=c.getString(desc) }; val star=action(R.drawable.ic_star,R.string.starred); val edit=action(R.drawable.ic_compose,R.string.note_optional); val delete=action(R.drawable.ic_tab_trash,R.string.delete); actions.addView(star);actions.addView(edit);actions.addView(delete);box.addView(title);box.addView(meta);box.addView(body);box.addView(note);box.addView(actions);card.addView(box);return Holder(card,title,meta,body,note,star,edit,delete) }
        override fun getItemCount()=items.size
        override fun onBindViewHolder(h:Holder,p:Int){ val i=items[p]; val c=h.card.context; h.title.text=if(i.address==SavedMessageStore.SELF_ADDRESS)c.getString(R.string.self_note) else ContactNames.displayNameUi(i.address); h.meta.text=Dates.full(c,i.date); h.body.text=i.body; h.note.text=i.note; h.note.visibility=if(i.note.isBlank())View.GONE else View.VISIBLE; h.star.alpha=if(i.starred)1f else .45f; h.card.alpha=if(i.id in selected).75f else 1f; h.card.setOnClickListener{onClick(i)}; h.card.setOnLongClickListener{onLongClick(i);true}; h.star.setOnClickListener{onStar(i)}; h.edit.setOnClickListener{onNote(i)}; h.delete.setOnClickListener{onDelete(i)} }
        fun submit(next:List<SavedMessage>){items.clear();items.addAll(next);selected.retainAll(items.map{it.id}.toSet());notifyDataSetChanged()}; fun toggle(i:SavedMessage){if(!selected.add(i.id))selected.remove(i.id);notifyItemChanged(items.indexOf(i));onSelection(selected.size)}; fun selected()=items.filter{it.id in selected}; fun clear(){if(selected.isEmpty())return;selected.clear();notifyDataSetChanged();onSelection(0)}
    }
}
