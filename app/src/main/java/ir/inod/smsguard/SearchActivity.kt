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
    companion object {
        const val EXTRA_INITIAL_QUERY = "initial_query"
        const val MENU_SPAM_SELECTED = 4101
        const val MENU_TRASH_SELECTED = 4102
    }
    private lateinit var binding: ActivitySearchBinding
    private lateinit var adapter: ThreadAdapter
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val repo by lazy { SmsRepository(this) }
    private var generation = 0
    private var categories: List<Category?> = listOf(null)
    private var simIds: List<Int> = listOf(-1)
    private val searchRunnable = Runnable { search(binding.editSearch.text?.toString().orEmpty()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = ""
        binding.buttonClearSearch.setOnClickListener { binding.editSearch.setText("") }
        binding.editSearch.setOnEditorActionListener { _, _, _ ->
            main.removeCallbacks(searchRunnable)
            search(binding.editSearch.text?.toString().orEmpty())
            true
        }

        adapter = ThreadAdapter(
            onClick = { row ->
                if (adapter.selectionCount > 0) {
                    adapter.toggleSelection(row)
                } else {
                    startActivity(Intent(this, ConversationActivity::class.java).apply {
                        putExtra(ConversationActivity.EXTRA_THREAD_ID, row.threadId)
                        putExtra(ConversationActivity.EXTRA_ADDRESS, row.address)
                        putExtra(ConversationActivity.EXTRA_TARGET_MESSAGE_ID, row.messageId)
                        putExtra(ConversationActivity.EXTRA_TARGET_DATE, row.date)
                    })
                }
            },
            onLongClick = { row -> adapter.toggleSelection(row) },
            onAvatarClick = { row -> ContactPreview.show(this, row.address) },
            onSelectionChanged = { count ->
                supportActionBar?.title = if (count > 0) Dates.count(this, count) else ""
                invalidateOptionsMenu()
            },
            identityByMessage = true
        )
        adapter.applyLayout(ThemePrefs(this).snapshot())
        binding.recyclerResults.layoutManager = LinearLayoutManager(this)
        binding.recyclerResults.adapter = adapter
        binding.editSearch.doAfterTextChanged {
            binding.buttonClearSearch.visibility = if (it.isNullOrEmpty()) View.INVISIBLE else View.VISIBLE
            main.removeCallbacks(searchRunnable)
            main.postDelayed(searchRunnable, 250)
        }
        setUpFilters()
        intent.getStringExtra(EXTRA_INITIAL_QUERY)?.let { binding.editSearch.setText(it) }
        binding.editSearch.requestFocus()
    }

    private fun search(text: String) {
        val query = text.trim()
        val token = ++generation
        val category = categories.getOrNull(binding.spinnerCategory.selectedItemPosition)?.id
        val dateIndex = binding.spinnerDate.selectedItemPosition
        val since = when (dateIndex) { 1 -> System.currentTimeMillis() - 24*60*60*1000L; 2 -> System.currentTimeMillis() - 7*24*60*60*1000L; 3 -> System.currentTimeMillis() - 30L*24*60*60*1000L; else -> 0L }
        val sim = simIds.getOrElse(binding.spinnerSim.selectedItemPosition) { -1 }
        if (query.length < 2 && category == null && since == 0L && sim < 0) {
            adapter.submit(emptyList())
            binding.textResultCount.visibility = View.GONE
            binding.progress.visibility = View.GONE
            binding.textEmpty.setText(R.string.search_start_hint)
            binding.textEmpty.visibility = View.VISIBLE
            return
        }
        binding.progress.visibility = View.VISIBLE
        adapter.setHighlightQuery(query)
        worker.execute {
            val rows = repo.searchThreads(query, categoryId = category, since = since,
                subscriptionId = sim, onProgress = { partial ->
                    main.post {
                        if (token != generation || isFinishing || isDestroyed) return@post
                        binding.progress.visibility = View.GONE
                        adapter.submit(partial)
                        showCount(partial.size, true)
                        binding.textEmpty.visibility = View.GONE
                    }
                })
            main.post {
                if (token != generation || isFinishing || isDestroyed) return@post
                binding.progress.visibility = View.GONE
                adapter.submit(rows)
                showCount(rows.size, false)
                binding.textEmpty.setText(R.string.search_no_results)
                binding.textEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun setUpFilters() {
        categories = listOf(null) + CategoryStore(this).active()
        binding.spinnerCategory.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf(getString(R.string.filter_all_categories)) + categories.drop(1).map { it!!.label(this) })
        binding.spinnerDate.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf(getString(R.string.filter_any_date), getString(R.string.today), getString(R.string.filter_7_days), getString(R.string.filter_30_days)))
        val simLabels = mutableListOf(getString(R.string.sim_system_default)); val ids = mutableListOf(-1)
        try { getSystemService(android.telephony.SubscriptionManager::class.java)?.activeSubscriptionInfoList.orEmpty().forEach {
            ids += it.subscriptionId; simLabels += getString(R.string.sim_label, it.simSlotIndex + 1, it.carrierName?.toString().orEmpty())
        } } catch (_: SecurityException) { }
        simIds = ids; binding.spinnerSim.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, simLabels)
        val listener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) { main.removeCallbacks(searchRunnable); main.postDelayed(searchRunnable, 100) }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) = Unit
        }
        binding.spinnerCategory.onItemSelectedListener = listener; binding.spinnerDate.onItemSelectedListener = listener; binding.spinnerSim.onItemSelectedListener = listener
    }

    private fun showCount(count: Int, searching: Boolean) {
        binding.textResultCount.visibility = View.VISIBLE
        binding.textResultCount.text = if (Dates.isPersian(this)) {
            if (searching) "${Dates.count(this, count)}+ نتیجه" else "${Dates.count(this, count)} نتیجه"
        } else {
            if (searching) "$count+ results" else "$count results"
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
