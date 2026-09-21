package ir.inod.smsguard

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.view.Menu
import android.view.View
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import ir.inod.smsguard.databinding.ActivitySearchBinding
import java.util.concurrent.Executors

/** Full-mailbox search. Every query goes to the SMS provider off the UI thread. */
class SearchActivity : BaseActivity() {
    private companion object {
        const val MENU_SPAM_SELECTED = 4101
        const val MENU_TRASH_SELECTED = 4102
    }
    private lateinit var binding: ActivitySearchBinding
    private lateinit var adapter: ThreadAdapter
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val repo by lazy { SmsRepository(this) }
    private var generation = 0
    private val searchRunnable = Runnable { search(binding.editSearch.text?.toString().orEmpty()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        adapter = ThreadAdapter(
            onClick = { row ->
                if (adapter.selectionCount > 0) {
                    adapter.toggleSelection(row)
                } else {
                    startActivity(Intent(this, ConversationActivity::class.java).apply {
                        putExtra(ConversationActivity.EXTRA_THREAD_ID, row.threadId)
                        putExtra(ConversationActivity.EXTRA_ADDRESS, row.address)
                    })
                }
            },
            onLongClick = { row -> adapter.toggleSelection(row) },
            onSelectionChanged = { count ->
                supportActionBar?.title = if (count > 0) Dates.count(this, count)
                    else getString(R.string.search_all_messages)
                invalidateOptionsMenu()
            }
        )
        adapter.applyLayout(ThemePrefs(this).snapshot())
        binding.recyclerResults.layoutManager = LinearLayoutManager(this)
        binding.recyclerResults.adapter = adapter
        binding.editSearch.doAfterTextChanged {
            main.removeCallbacks(searchRunnable)
            main.postDelayed(searchRunnable, 250)
        }
        binding.editSearch.requestFocus()
    }

    private fun search(text: String) {
        val query = text.trim()
        val token = ++generation
        if (query.length < 2) {
            adapter.submit(emptyList())
            binding.progress.visibility = View.GONE
            binding.textEmpty.setText(R.string.search_start_hint)
            binding.textEmpty.visibility = View.VISIBLE
            return
        }
        binding.progress.visibility = View.VISIBLE
        worker.execute {
            val rows = repo.searchThreads(query)
            main.post {
                if (token != generation || isFinishing || isDestroyed) return@post
                binding.progress.visibility = View.GONE
                adapter.submit(rows)
                binding.textEmpty.setText(R.string.search_no_results)
                binding.textEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onDestroy() {
        main.removeCallbacks(searchRunnable)
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_SPAM_SELECTED || item.itemId == MENU_TRASH_SELECTED) {
            applyCategoryToSelection(
                if (item.itemId == MENU_SPAM_SELECTED) Cat.SPAM else Cat.TRASH
            )
            return true
        }
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_SPAM_SELECTED, 0, R.string.mark_spam)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, MENU_TRASH_SELECTED, 1, R.string.move_to_trash)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val visible = ::adapter.isInitialized && adapter.selectionCount > 0
        menu.findItem(MENU_SPAM_SELECTED)?.isVisible = visible
        menu.findItem(MENU_TRASH_SELECTED)?.isVisible = visible
        return super.onPrepareOptionsMenu(menu)
    }

    private fun applyCategoryToSelection(category: String) {
        val selected = adapter.selectedItems()
        selected.forEach {
            SenderStore(this).setCategory(it.address, category)
            MessageCategoryStore(this).set(it.messageId, category)
        }
        Classifier.invalidateCaches()
        ThreadCache.clear(this)
        adapter.clearSelection()
        search(binding.editSearch.text?.toString().orEmpty())
    }

    @Deprecated("Handled for selection mode")
    override fun onBackPressed() {
        if (::adapter.isInitialized && adapter.selectionCount > 0) adapter.clearSelection()
        else super.onBackPressed()
    }
}
