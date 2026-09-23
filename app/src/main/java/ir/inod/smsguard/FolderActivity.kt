package ir.inod.smsguard

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import java.util.concurrent.Executors

/** Dedicated, searchable destinations for Archive and Trash. */
class FolderActivity : BaseActivity() {
    companion object {
        const val EXTRA_FOLDER = "folder"
        const val ARCHIVE = "archive"
        const val TRASH = "trash"
        private const val MENU_RESTORE = 1
        private const val MENU_DELETE = 2
        private const val MENU_EMPTY = 3
    }

    private val folder by lazy { intent.getStringExtra(EXTRA_FOLDER).takeIf { it == TRASH } ?: ARCHIVE }
    private val worker = Executors.newSingleThreadExecutor()
    private val repo by lazy { SmsRepository(this) }
    private val senderStore by lazy { SenderStore(this) }
    private val messageCats by lazy { MessageCategoryStore(this) }
    private lateinit var adapter: ThreadAdapter
    private lateinit var empty: android.widget.TextView
    private lateinit var search: EditText
    private var all = emptyList<ThreadSummary>()

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) adapter.refreshNotificationState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = SecondaryUi.toolbar(this,
            getString(if (folder == TRASH) R.string.tab_trash else R.string.archived)) { finish() }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, SecondaryUi.px(this, R.dimen.appbar_height)))
        search = SecondaryUi.search(this)
        root.addView(search, LinearLayout.LayoutParams(-1, SecondaryUi.px(this, R.dimen.search_height)))
        val frame = FrameLayout(this)
        val recycler = androidx.recyclerview.widget.RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@FolderActivity)
            clipToPadding = false
            setPadding(0, dp(6), 0, dp(88))
        }
        adapter = ThreadAdapter(
            onClick = { row -> if (adapter.selectionCount > 0) adapter.toggleSelection(row) else open(row) },
            onLongClick = { adapter.toggleSelection(it) },
            onAvatarClick = { ContactPreview.show(this, it.address) },
            onSelectionChanged = { count ->
                toolbar.title = if (count > 0) Dates.count(this, count)
                else getString(if (folder == TRASH) R.string.tab_trash else R.string.archived)
                invalidateOptionsMenu()
            }
        ).also { it.applyLayout(ThemePrefs(this).snapshot()) }
        recycler.adapter = adapter
        empty = SecondaryUi.empty(this,
            if (folder == TRASH) R.drawable.ic_tab_trash else R.drawable.ic_archive)
        frame.addView(recycler, FrameLayout.LayoutParams(-1, -1))
        frame.addView(empty, FrameLayout.LayoutParams(-1, -2, android.view.Gravity.CENTER))
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        if (folder == TRASH) root.addView(ExtendedFloatingActionButton(this).apply {
            text = getString(R.string.delete_all); setIconResource(R.drawable.ic_tab_trash)
            setOnClickListener { confirmEmpty() }
        }, LinearLayout.LayoutParams(-2, dp(52)).apply { gravity = android.view.Gravity.END; marginEnd = dp(16); bottomMargin = dp(12) })
        setContentView(root)
        setSupportActionBar(toolbar)
        search.doAfterTextChanged { filter() }
        load()
    }

    private fun load() {
        worker.execute {
            val flags = senderStore.inboxFlags()
            all = repo.loadThreads().filter { row ->
                if (folder == TRASH) row.categoryId == Cat.TRASH else flags[row.address]?.second == true
            }
            runOnUiThread { filter() }
        }
    }

    private fun filter() {
        val needle = search.text?.toString().orEmpty().trim().lowercase()
        val visible = if (needle.isBlank()) all else all.filter {
            it.address.lowercase().contains(needle) || it.snippet.lowercase().contains(needle) ||
                ContactNames.displayNameUi(it.address).lowercase().contains(needle)
        }
        adapter.submit(visible)
        empty.text = getString(if (folder == TRASH) R.string.trash_empty else R.string.no_threads)
        empty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun open(row: ThreadSummary) = startActivity(Intent(this, ConversationActivity::class.java).apply {
        putExtra(ConversationActivity.EXTRA_THREAD_ID, row.threadId)
        putExtra(ConversationActivity.EXTRA_ADDRESS, row.address)
    })

    private fun restoreSelected() {
        val selected = adapter.selectedItems(); if (selected.isEmpty()) return
        selected.forEach { row ->
            if (folder == TRASH) {
                senderStore.setCategory(row.address, Cat.OTHER); messageCats.set(row.messageId, Cat.OTHER)
            } else senderStore.setArchived(row.address, false)
        }
        all = all.filterNot { row -> selected.any { it.threadId == row.threadId } }
        adapter.clearSelection()
        filter()
        Classifier.invalidateCaches(); ThreadCache.clear(this)
        load()
    }

    private fun deleteSelected() {
        val selected = adapter.selectedItems(); if (selected.isEmpty()) return
        ConfirmSheet.show(this, getString(R.string.delete_forever), getString(R.string.confirm_delete_selected, selected.size), R.drawable.ic_tab_trash) {
            worker.execute {
                selected.forEach { repo.deleteThread(it.threadId) }
                ThreadCache.clear(this); runOnUiThread { adapter.clearSelection(); load() }
            }
        }
    }

    private fun confirmEmpty() {
        ConfirmSheet.show(this, getString(R.string.delete_all), getString(R.string.confirm_empty_trash), R.drawable.ic_tab_trash) {
            worker.execute {
                all.forEach { repo.deleteThread(it.threadId) }
                ThreadCache.clear(this); runOnUiThread { load() }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_RESTORE, 0, R.string.restore_from_trash).setIcon(R.drawable.ic_archive)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, MENU_DELETE, 1, R.string.delete_forever).setIcon(R.drawable.ic_tab_trash)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val selected = adapter.selectionCount > 0
        menu.findItem(MENU_RESTORE)?.isVisible = selected
        menu.findItem(MENU_DELETE)?.isVisible = selected
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_RESTORE -> { restoreSelected(); true }
        MENU_DELETE -> { deleteSelected(); true }
        android.R.id.home -> { finish(); true }
        else -> super.onOptionsItemSelected(item)
    }

    @Deprecated("Selection mode")
    override fun onBackPressed() { if (adapter.selectionCount > 0) adapter.clearSelection() else super.onBackPressed() }

    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }
}
