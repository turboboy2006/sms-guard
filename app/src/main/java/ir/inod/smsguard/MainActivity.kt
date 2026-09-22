package ir.inod.smsguard

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.provider.Telephony
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import ir.inod.smsguard.databinding.ActivityMainBinding
import java.util.concurrent.Executors

class MainActivity : BaseActivity() {

    private companion object {
        const val MENU_MANAGE = 2001
        const val MENU_SEARCH = 2002
        const val MENU_RULES = 2003
        const val MENU_BLOCKED = 2004
        const val MENU_REFRESH = 2005
        const val MENU_TRASH = 2006
        const val MENU_MARK_READ = 2101
        const val MENU_BULK_SPAM = 2102
        const val MENU_BULK_TRASH = 2103
        const val MENU_BULK_RESTORE = 2104
        const val MENU_ARCHIVED = 2007
        const val MENU_SETTINGS = 2009
        const val MENU_MORE = 2010
        const val MENU_READ_ALL = 2011
        const val UNREAD_FILTER = "__unread__"

        /** Tab order, matching the chips built in [setUpFilterChips]. */
        const val TAB_ALL = 0
        const val TAB_SUSPICIOUS = 1
        const val TAB_SPAM = 2
        const val TAB_BANKING = 3
        const val TAB_SERVICE = 4
        const val TAB_TRASH = 5
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ThreadAdapter

    private val repo by lazy { SmsRepository(this) }
    private val theme by lazy { ThemePrefs(this) }
    private val blockedStore by lazy { BlockedStore(this) }
    private val senderStore by lazy { SenderStore(this) }
    private val messageCats by lazy { MessageCategoryStore(this) }

    /** Volatile: written on the main thread, read from the worker to size the skeleton. */
    @Volatile
    private var allThreads: List<ThreadSummary> = emptyList()
    private var selectedTab = TAB_ALL
    private var selectedCategoryId: String? = null
    private var query: String = ""
    private var rendered: List<ThreadSummary> = emptyList()
    private var drawnLayout: RowLayout? = null
    private var loadedFromCache = false
    private var syncRunning = false
    private var syncPending = false
    private var categorySignature = ""
    private var trashCleanupDone = false

    /**
     * The bottom navigation's Contacts destination.
     *
     * It is a *destination*, not a filter chip: it replaces the category strip
     * with a single question — which conversations are with people in my
     * address book — so it does not belong in the same row as "بانکی" and
     * "اسپم", which narrow a list of everything.
     */
    private var contactsOnly = false
    private var archiveMode = false

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var skeletonPulse: android.animation.ObjectAnimator? = null

    private val refresh: () -> Unit = { loadThreads() }

    private val roleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshBanner() }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        loadThreads()
        if (grants[Manifest.permission.READ_CONTACTS] == false) {
            Snackbar.make(binding.root, R.string.contacts_permission_explanation, Snackbar.LENGTH_LONG)
                .setAction(R.string.settings) {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = android.net.Uri.parse("package:$packageName")
                        }
                    )
                }
                .show()
        }
    }

    /**
     * Watches the SMS provider so a message that arrives while the app is open
     * appears without a manual refresh. Reloads are debounced: marking a thread
     * read rewrites many rows at once, and each rewrite fires this callback.
     */
    private val smsObserver = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            scheduleReload()
        }
    }

    private var reloadScheduled = false
    private val reloadRunnable = Runnable {
        reloadScheduled = false
        if (!isFinishing && !isDestroyed) loadThreads()
    }

    private fun scheduleReload() {
        if (reloadScheduled) return
        reloadScheduled = true
        main.postDelayed(reloadRunnable, 400)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        adapter = ThreadAdapter(
            onClick = { thread ->
                if (adapter.selectionCount > 0) adapter.toggleSelection(thread) else openThread(thread)
            },
            onLongClick = { thread -> showOptions(thread) },
            onCategoryClick = { thread -> showCategoryPicker(thread) },
            onSelectionChanged = { count -> updateSelectionUi(count) }
        )
        binding.recyclerThreads.layoutManager = LinearLayoutManager(this)
        binding.recyclerThreads.adapter = adapter
        // Updating read state or a draft should not fade the entire inbox.
        binding.recyclerThreads.itemAnimator = null
        attachSwipeActions()

        setUpFilterChips()
        applyAppearance()

        binding.buttonMakeDefault.setOnClickListener { requestDefaultRole() }
        binding.fabCompose.setOnClickListener { startCompose() }
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_saved -> {
                    startActivity(Intent(this, SavedMessagesActivity::class.java))
                    false
                }
                R.id.nav_contacts -> {
                    archiveMode = false
                    contactsOnly = true
                    applyFilter()
                    true
                }
                else -> {
                    archiveMode = false
                    contactsOnly = false
                    applyFilter()
                    true
                }
            }
        }

        // The stored inbox is on screen before the provider is even queried —
        // but it is read off the main thread, because at this point the cache
        // file can be at its largest.
        val preview = ThreadCache.readPreview(this)
        if (preview.isNotEmpty()) {
            val active = CategoryStore(this).active().mapTo(HashSet()) { it.id }
            allThreads = preview.map { cached -> cached.toSummary().let { row ->
                if (row.categoryId in active) row else row.copy(categoryId = Cat.OTHER)
            } }
            applyFilter()
        }
        loadCachedThreads()
        ensurePermissions()
    }

    /**
     * The filter strip.
     *
     * One row of pills, 46dp tall, 8dp apart, with 18dp of horizontal padding
     * inside each. Only the categories whose shape carries meaning get an icon —
     * a warning for suspicious, a bank for banking, a bell for service — because
     * an icon on every chip turns the strip into noise.
     */
    private fun setUpFilterChips() {
        binding.chipGroup.removeAllViews()
        val entries = listOf<Pair<String?, String>>(
            null to getString(R.string.tab_all),
            UNREAD_FILTER to getString(R.string.tab_unread)
        ) +
            CategoryStore(this).active().map { it.id to it.label(this) }
        val idToCategory = HashMap<Int, String?>()
        val density = resources.displayMetrics.density
        entries.forEachIndexed { index, entry ->
            val category = entry.first?.let { CategoryStore(this).byId(it) }
            val baseColor = runCatching { Color.parseColor(category?.colorHex ?: "#0F766E") }
                .getOrDefault(Color.parseColor("#0F766E"))
            val soft = Color.rgb(
                (Color.red(baseColor) * .18 + 255 * .82).toInt(),
                (Color.green(baseColor) * .18 + 255 * .82).toInt(),
                (Color.blue(baseColor) * .18 + 255 * .82).toInt()
            )
            val chip = com.google.android.material.chip.Chip(this).apply {
                text = entry.second
                isCheckable = true
                isClickable = true
                id = View.generateViewId()
                chipBackgroundColor = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(soft, ContextCompat.getColor(this@MainActivity, R.color.chip_inactive_bg))
                )
                val ink = baseColor
                setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(ink, ink)))
                val icon = if (entry.first == null) R.drawable.ic_tab_all else if (entry.first == UNREAD_FILTER) R.drawable.ic_unread else
                    (IconCatalog.byId(category?.iconId) ?: IconCatalog.forCategory(entry.first!!)).drawable
                if (icon != 0) {
                    chipIcon = ContextCompat.getDrawable(this@MainActivity, icon)?.mutate()?.apply {
                        isAutoMirrored = true
                    }
                    chipIconTint = ColorStateList.valueOf(ink)
                    chipIconSize = 21f * density
                }
                chipStrokeWidth = 0f
                chipStartPadding = 8f * density
                chipEndPadding = 8f * density
                chipCornerRadius = 20f * density
                chipMinHeight = 40f * density
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                // The Kotlin property is private; the public setter is not.
                setEnsureMinTouchTargetSize(false)
            }
            idToCategory[chip.id] = entry.first
            binding.chipGroup.addView(chip)
        }
        binding.chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val first = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            selectedCategoryId = idToCategory[first]
            applyFilter()
        }
        (binding.chipGroup.getChildAt(0) as? com.google.android.material.chip.Chip)
            ?.isChecked = true
        categorySignature = CategoryStore(this).all().joinToString("|") {
            "${it.id}:${it.label(this)}:${it.colorHex}:${it.iconId}:${it.order}:${it.enabled}"
        }
    }

    /** Hides the parts of the screen the user asked not to see. */
    private fun applyChipVisibility() {
        binding.chipScroll.visibility =
            if (theme.showChips && !contactsOnly && !archiveMode) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------- lifecycle

    override fun onStart() {
        super.onStart()
        // The AI stage can re-label a message after the fact; refresh when it does.
        MessageBus.register(refresh)
        contentResolver.registerContentObserver(
            Telephony.Sms.CONTENT_URI, true, smsObserver
        )
    }

    override fun onStop() {
        MessageBus.unregister(refresh)
        runCatching { contentResolver.unregisterContentObserver(smsObserver) }
        main.removeCallbacks(reloadRunnable)
        reloadScheduled = false
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) adapter.refreshDrafts()
        refreshBanner()
        applyChipVisibility()
        val nextCategorySignature = CategoryStore(this).all().joinToString("|") {
            "${it.id}:${it.label(this)}:${it.colorHex}:${it.iconId}:${it.order}:${it.enabled}"
        }
        if (nextCategorySignature != categorySignature) {
            selectedCategoryId = null
            setUpFilterChips()
        }

        // Appearance may have changed on the settings screen. Applying the new
        // layout in place means the inbox behind it is already correct when the
        // user comes back.
        val next = theme.snapshot()
        if (next != drawnLayout) {
            drawnLayout = next
            adapter.applyLayout(next)
            applyListPadding()
            applyPalette()
            applyFilter()
        }
        BackgroundRenderer.apply(binding.root, this, theme.backgroundStyle, theme.backgroundImageUri, theme.backgroundPreset)
        applyGlassSurfaces()

        loadThreads()
    }

    private fun applyAppearance() {
        drawnLayout = theme.snapshot()
        adapter.applyLayout(drawnLayout!!)
        applyChipVisibility()
        applyListPadding()
        applyPalette()
        BackgroundRenderer.apply(binding.root, this, theme.backgroundStyle, theme.backgroundImageUri, theme.backgroundPreset)
        applyGlassSurfaces()
    }

    private fun applyGlassSurfaces() {
        val alpha = if (theme.hasWallpaper()) (theme.surfaceOpacity * 255 / 100) else 255
        val surface = (alpha shl 24) or (ContextCompat.getColor(this, R.color.card_bg) and 0x00FFFFFF)
        binding.toolbar.setBackgroundColor(surface)
        binding.bottomNav.backgroundTintList = ColorStateList.valueOf(surface)
    }

    private fun applyPalette() {
        val accent = theme.accentColor()
        binding.fabCompose.backgroundTintList = ColorStateList.valueOf(accent)
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        binding.bottomNav.itemIconTintList = ColorStateList(
            states, intArrayOf(accent, ContextCompat.getColor(this, R.color.nav_icon_inactive))
        )
        binding.bottomNav.itemTextColor = ColorStateList(
            states, intArrayOf(accent, ContextCompat.getColor(this, R.color.nav_icon_inactive))
        )
        // Category chips keep their semantic pastel colours; the global accent
        // still controls navigation and the compose action.
    }

    private fun applyListPadding() {
        val layout = drawnLayout ?: return
        val pad = (layout.listPadding * resources.displayMetrics.density).toInt()
        binding.recyclerThreads.setPadding(0, pad, 0, pad + (72 * resources.displayMetrics.density).toInt())
    }

    // ------------------------------------------------------------ permissions

    private fun ensurePermissions() {
        val needed = mutableListOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) loadThreads() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun isDefaultSmsApp(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_SMS) == true
        } else {
            packageName == Telephony.Sms.getDefaultSmsPackage(this)
        }

    private fun requestDefaultRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_SMS) &&
                !rm.isRoleHeld(RoleManager.ROLE_SMS)
            ) {
                roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_SMS))
                return
            }
        }
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            } catch (e2: Exception) {
                Toast.makeText(this, R.string.open_settings_manually, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun refreshBanner() {
        binding.cardDefaultBanner.visibility = if (isDefaultSmsApp()) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------------------------ data

    /**
     * Paints whatever the last session stored, without touching the provider.
     * This is the step that takes the cold start from seconds to a frame.
     *
     * Reading the cache is a file read plus a parse, so it runs on the worker
     * and the rows are handed to the main thread in one go. The skeleton is
     * already on screen, so this only has to be quick, not instant.
     */
    private fun loadCachedThreads() {
        if (loadedFromCache) return
        worker.execute {
            val cached = try {
                repo.cachedThreads()
            } catch (t: Throwable) {
                emptyList()
            }
            if (cached.isEmpty()) return@execute
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (allThreads.size >= cached.size) return@runOnUiThread
                loadedFromCache = true
                allThreads = cached
                showSkeleton(false)
                applyFilter()
            }
        }
    }

    /**
     * Provider work: reading the mailbox and classifying senders is far too
     * heavy for the main thread — doing it there produced
     * "ANR in ir.inod.smsguard (MainActivity)" on a real device.
     *
     * The skeleton placeholder only appears when there is genuinely nothing to
     * show, so a warm start never flashes an empty screen.
     */
    private fun loadThreads() {
        if (syncRunning) { syncPending = true; return }
        syncRunning = true
        if (allThreads.isEmpty()) showSkeleton(true)
        worker.execute {
            val started = System.currentTimeMillis()
            // Build (or repair) the address book first, off the main thread, so
            // the Contacts filter and every name lookup afterwards are memory
            // reads. An empty index is rebuilt rather than trusted, which is how
            // the Contacts tab recovers after the permission is granted.
            try {
                ContactsIndex.ensure(this)
            } catch (t: Throwable) {
                // no contacts permission: an empty index is fine
            }
            val threads = try {
                repo.loadThreads(progressEvery = Int.MAX_VALUE) { partial ->
                    main.post {
                        if (isFinishing || isDestroyed || partial.isEmpty()) return@post
                        allThreads = partial
                        showSkeleton(false)
                        applyFilter()
                    }
                }
            } catch (t: Throwable) {
                emptyList()
            }
            val elapsed = System.currentTimeMillis() - started
            runOnUiThread {
                syncRunning = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (threads.isNotEmpty()) allThreads = threads
                cleanExpiredTrash(allThreads)
                showSkeleton(false)
                applyFilter()
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("SmsGuard", "inbox sync finished in ${elapsed}ms")
                }
                if (syncPending) { syncPending = false; loadThreads() }
            }
        }
    }

    private fun cleanExpiredTrash(rows: List<ThreadSummary>) {
        if (trashCleanupDone) return
        trashCleanupDone = true
        val days = SettingsStore(this).trashRetentionDays
        if (days <= 0) return
        val cutoff = System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L
        val victims = rows.filter { it.categoryId == Cat.TRASH && it.date < cutoff }
        if (victims.isEmpty()) return
        worker.execute {
            victims.forEach { repo.deleteThread(it.threadId) }
            ThreadCache.clear(this)
            main.post { loadThreads() }
        }
    }

    private fun showSkeleton(visible: Boolean) {
        binding.skeleton.visibility = if (visible) View.VISIBLE else View.GONE
        binding.recyclerThreads.visibility = if (visible) View.INVISIBLE else View.VISIBLE
        skeletonPulse?.cancel()
        skeletonPulse = if (visible) {
            android.animation.ObjectAnimator
                .ofFloat(binding.skeleton, "alpha", 1f, 0.45f)
                .apply {
                    duration = 700
                    repeatMode = android.animation.ValueAnimator.REVERSE
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    start()
                }
        } else {
            binding.skeleton.alpha = 1f
            null
        }
    }

    /**
     * One override covers every forward navigation, so the 220ms transition
     * cannot be forgotten at a call site.
     */
    override fun startActivity(intent: Intent) {
        super.startActivity(intent)
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.nav_enter, R.anim.nav_exit)
    }

    override fun onDestroy() {
        skeletonPulse?.cancel()
        worker.shutdownNow()
        super.onDestroy()
    }

    /** Search, plus the actions that used to live in a row of buttons. */
    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menu.add(0, MENU_SEARCH, 0, R.string.search)
            .setIcon(R.drawable.ic_search)
            .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, MENU_MORE, 1, R.string.more_actions)
            .setIcon(R.drawable.ic_more)
            .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, MENU_READ_ALL, 2, R.string.mark_all_read)
            .setIcon(R.drawable.ic_mark_read)
            .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, MENU_REFRESH, 1, R.string.refresh)
        menu.add(0, MENU_RULES, 2, R.string.rules)
        menu.add(0, MENU_BLOCKED, 3, R.string.blocked_log)
        menu.add(0, MENU_TRASH, 4, R.string.tab_trash)
        menu.add(0, MENU_MANAGE, 5, R.string.manage_brands)
        menu.add(0, MENU_ARCHIVED, 6, R.string.archived)
        menu.add(0, MENU_SETTINGS, 8, R.string.settings)
            .setIcon(R.drawable.ic_settings)
        menu.add(0, MENU_MARK_READ, 0, R.string.mark_read)
            .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, MENU_BULK_SPAM, 1, R.string.mark_spam)
            .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, MENU_BULK_TRASH, 2, R.string.move_to_trash)
            .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, MENU_BULK_RESTORE, 3, R.string.restore_from_trash)
            .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
        return true
    }

    override fun onPrepareOptionsMenu(menu: android.view.Menu): Boolean {
        val selecting = ::adapter.isInitialized && adapter.selectionCount > 0
        listOf(MENU_MARK_READ, MENU_BULK_SPAM, MENU_BULK_TRASH).forEach {
            menu.findItem(it)?.isVisible = selecting
        }
        menu.findItem(MENU_BULK_RESTORE)?.isVisible = selecting && selectedCategoryId == Cat.TRASH
        if (selectedCategoryId == Cat.TRASH) menu.findItem(MENU_BULK_TRASH)?.isVisible = false
        menu.findItem(MENU_SEARCH)?.isVisible = !selecting
        menu.findItem(MENU_MORE)?.isVisible = !selecting
        menu.findItem(MENU_READ_ALL)?.isVisible = !selecting && selectedCategoryId == UNREAD_FILTER
        listOf(MENU_REFRESH, MENU_RULES, MENU_BLOCKED, MENU_TRASH, MENU_MANAGE, MENU_ARCHIVED, MENU_SETTINGS).forEach {
            menu.findItem(it)?.isVisible = false
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        when (item.itemId) {
            MENU_MORE -> showMainMenu()
            MENU_READ_ALL -> markAllUnreadRead()
            MENU_SEARCH -> showSearch()
            MENU_REFRESH -> {
                ThreadCache.clear(this)
                toast(R.string.refreshing)
                loadThreads()
            }
            MENU_RULES -> startActivity(Intent(this, RulesActivity::class.java))
            MENU_BLOCKED -> showBlockedLog()
            MENU_TRASH -> {
                contactsOnly = false
                selectedCategoryId = Cat.TRASH
                binding.bottomNav.selectedItemId = R.id.nav_messages
                for (i in 0 until binding.chipGroup.childCount) {
                    val chip = binding.chipGroup.getChildAt(i) as? com.google.android.material.chip.Chip
                    if (chip != null && chip.text == CategoryStore(this).byId(Cat.TRASH)?.label(this)) chip.isChecked = true
                }
                applyFilter()
            }
            MENU_MANAGE -> startActivity(Intent(this, ManagerActivity::class.java))
            MENU_ARCHIVED -> showArchived()
            MENU_SETTINGS -> startActivity(Intent(this, SettingsActivity::class.java))
            MENU_MARK_READ -> bulkMarkRead()
            MENU_BULK_SPAM -> bulkCategory(Cat.SPAM)
            MENU_BULK_TRASH -> bulkCategory(Cat.TRASH)
            MENU_BULK_RESTORE -> bulkCategory(Cat.OTHER)
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun showMainMenu() {
        val ids = listOf(MENU_REFRESH, MENU_ARCHIVED, MENU_TRASH, MENU_RULES,
            MENU_BLOCKED, MENU_MANAGE, MENU_SETTINGS)
        val labels = listOf(R.string.refresh, R.string.archived, R.string.tab_trash,
            R.string.rules, R.string.blocked_log, R.string.manage_brands, R.string.settings)
        val icons = listOf(R.drawable.ic_archive, R.drawable.ic_archive, R.drawable.ic_tab_trash,
            R.drawable.ic_cat_security, R.drawable.ic_tab_spam, R.drawable.ic_cat_bank, R.drawable.ic_settings)
        ChoiceSheet.show(this, getString(R.string.more_actions), ids.indices.map { index ->
            ChoiceSheet.Option(getString(labels[index]), icons[index])
        }) { index ->
            binding.toolbar.menu.findItem(ids[index])?.let { onOptionsItemSelected(it) }
        }
    }

    private fun markAllUnreadRead() {
        val rows = allThreads.filter {
            it.unreadCount > 0 && it.categoryId != Cat.TRASH && !senderStore.isArchived(it.address)
        }
        if (rows.isEmpty()) return
        val ids = rows.mapTo(HashSet()) { it.threadId }
        worker.execute {
            rows.forEach { repo.markThreadRead(it.threadId) }
            main.post {
                if (isFinishing || isDestroyed) return@post
                allThreads = allThreads.map { row ->
                    if (row.threadId in ids) row.copy(unreadCount = 0) else row
                }
                applyFilter()
                loadThreads()
            }
        }
    }

    private fun updateSelectionUi(count: Int) {
        supportActionBar?.title = if (count > 0) Dates.count(this, count) else
            getString(if (contactsOnly) R.string.tab_contacts else R.string.tab_messages)
        invalidateOptionsMenu()
    }

    private fun attachSwipeActions() {
        val callback = object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ) = false

            override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int =
                if (adapter.selectionCount > 0 || !SettingsStore(this@MainActivity).swipeEnabled) 0
                else super.getSwipeDirs(recyclerView, viewHolder)

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                val row = adapter.itemAt(position) ?: return
                val action = if (direction == ItemTouchHelper.RIGHT) {
                    SettingsStore(this@MainActivity).swipeRightAction
                } else SettingsStore(this@MainActivity).swipeLeftAction
                Snackbar.make(binding.root, swipeActionLabel(action), 1000).show()
                performSwipeAction(row, action)
                if (action == SwipeAction.READ && position >= 0) {
                    main.postDelayed({
                        if (!isFinishing && !isDestroyed && position < adapter.itemCount &&
                            adapter.itemAt(position)?.threadId == row.threadId) adapter.notifyItemChanged(position)
                    }, 900L)
                }
            }

            override fun onChildDraw(
                c: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                    val item = viewHolder.itemView
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = ContextCompat.getColor(
                            this@MainActivity,
                            if (dX > 0) R.color.colorPrimary else R.color.danger
                        )
                    }
                    if (dX > 0) c.drawRect(
                        item.left.toFloat(), item.top.toFloat(),
                        item.left + dX, item.bottom.toFloat(), paint
                    ) else c.drawRect(
                        item.right + dX, item.top.toFloat(),
                        item.right.toFloat(), item.bottom.toFloat(), paint
                    )
                    paint.color = Color.WHITE
                    paint.textSize = 14f * resources.displayMetrics.scaledDensity
                    paint.textAlign = if (dX > 0) Paint.Align.LEFT else Paint.Align.RIGHT
                    val baseline = item.top + item.height / 2f - (paint.ascent() + paint.descent()) / 2f
                    val inset = 20f * resources.displayMetrics.density
                    c.drawText(
                        swipeActionLabel(if (dX > 0) SettingsStore(this@MainActivity).swipeRightAction else SettingsStore(this@MainActivity).swipeLeftAction),
                        if (dX > 0) item.left + inset else item.right - inset,
                        baseline,
                        paint
                    )
                }
                super.onChildDraw(
                    c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive
                )
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(binding.recyclerThreads)
    }

    private fun performSwipeAction(row: ThreadSummary, action: String) {
        when (action) {
            SwipeAction.READ -> worker.execute {
                repo.markThreadRead(row.threadId)
                main.post {
                    allThreads = allThreads.map { if (it.threadId == row.threadId) it.copy(unreadCount = 0) else it }
                    applyFilter()
                    loadThreads()
                }
            }
            SwipeAction.TRASH -> changeCategory(row, Cat.TRASH)
            SwipeAction.ARCHIVE -> { senderStore.setArchived(row.address, true); applyFilter() }
            else -> changeCategory(row, Cat.SPAM)
        }
    }

    private fun swipeActionLabel(action: String): String = getString(when (action) {
        SwipeAction.READ -> R.string.swipe_mark_read
        SwipeAction.TRASH -> R.string.move_to_trash
        SwipeAction.ARCHIVE -> R.string.archive
        else -> R.string.mark_spam
    })

    private fun bulkMarkRead() {
        val rows = adapter.selectedItems()
        adapter.clearSelection()
        worker.execute {
            rows.forEach { repo.markThreadRead(it.threadId) }
            main.post {
                loadThreads()
                showUndo {
                    worker.execute {
                        rows.forEach { repo.markThreadUnread(it.threadId) }
                        main.post { loadThreads() }
                    }
                }
            }
        }
    }

    private fun bulkCategory(categoryId: String) {
        val rows = adapter.selectedItems()
        val previous = rows.associate { row ->
            row.threadId to Pair(senderStore.categoryFor(row.address), messageCats.categoryFor(row.messageId))
        }
        adapter.clearSelection()
        rows.forEach { row ->
            senderStore.setCategory(row.address, categoryId)
            messageCats.set(row.messageId, categoryId)
            if (categoryId == Cat.SPAM) {
                SenderProfileStore(this).recordFeedback(row.address, true)
                LearnedWeights(this).record(row.snippet, true)
                CampaignStore(this).markSpam(row.messageId)
            }
        }
        Classifier.invalidateCaches()
        ThreadCache.clear(this)
        loadThreads()
        showUndo {
            rows.forEach { row ->
                val old = previous[row.threadId]
                senderStore.setCategory(row.address, old?.first ?: Cat.OTHER)
                messageCats.set(row.messageId, old?.second ?: old?.first ?: Cat.OTHER)
                if (categoryId == Cat.SPAM) {
                    SenderProfileStore(this).revertFeedback(row.address, true)
                    LearnedWeights(this).revert(row.snippet, true)
                }
            }
            Classifier.invalidateCaches()
            ThreadCache.clear(this)
            loadThreads()
        }
    }

    @Deprecated("Handled for selection mode")
    override fun onBackPressed() {
        if (::adapter.isInitialized && adapter.selectionCount > 0) adapter.clearSelection()
        else if (archiveMode) { archiveMode = false; applyFilter() }
        else super.onBackPressed()
    }

    /**
     * Search filters the already-loaded rows instead of re-reading the
     * provider, so results appear as the user types.
     */
    private fun showSearch() {
        startActivity(Intent(this, SearchActivity::class.java))
    }

    private fun applyFilter() {
        invalidateOptionsMenu()
        val inboxFlags = senderStore.inboxFlags()
        val spamIds = CategoryStore(this).active().filter { it.spamFolder }.map { it.id }.toSet()
        val byTab = when {
            archiveMode -> allThreads
            selectedCategoryId == UNREAD_FILTER -> allThreads.filter { it.unreadCount > 0 && it.categoryId != Cat.TRASH }
            contactsOnly -> allThreads.filter { ContactsIndex.isKnownContact(it.address) }
            selectedCategoryId != null -> allThreads.filter { it.categoryId == selectedCategoryId }
            // "All" hides the spam folder and the trash alike.
            else -> allThreads.filterNot {
                it.categoryId in spamIds || it.categoryId == Cat.TRASH
            }
        }
        val visibleRows = byTab.filter { (inboxFlags[it.address]?.second == true) == archiveMode }
            .sortedWith(compareByDescending<ThreadSummary> { inboxFlags[it.address]?.first == true }.thenByDescending { it.date })
        val filteredBase = if (query.isBlank()) {
            visibleRows
        } else {
            val needle = query.lowercase()
            visibleRows.filter {
                it.snippet.lowercase().contains(needle) ||
                    it.address.lowercase().contains(needle) ||
                    ContactNames.displayNameUi(it.address).lowercase().contains(needle)
            }
        }
        val filtered = filteredBase.map { row -> row.copy(pinned = inboxFlags[row.address]?.first == true) }

        if (rendered.isEmpty()) adapter.submit(filtered) else adapter.merge(filtered)
        rendered = filtered

        binding.textEmptyLabel.setText(
            if (contactsOnly) R.string.no_contacts_threads else R.string.no_threads
        )
        binding.textEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE

        // The category strip belongs to the message list; the Contacts
        // destination answers a different question.
        binding.chipScroll.visibility =
            if (theme.showChips && !contactsOnly && !archiveMode) View.VISIBLE else View.GONE
        supportActionBar?.title =
            getString(if (archiveMode) R.string.archived else if (contactsOnly) R.string.tab_contacts else R.string.tab_messages)
    }

    private fun openThread(thread: ThreadSummary) {
        startActivity(
            Intent(this, ConversationActivity::class.java).apply {
                putExtra(ConversationActivity.EXTRA_THREAD_ID, thread.threadId)
                putExtra(ConversationActivity.EXTRA_ADDRESS, thread.address)
            }
        )
    }

    /**
     * New message.
     *
     * The recipient is chosen here, before the conversation screen opens,
     * because a conversation with nobody in it has nothing to show. The screen
     * still handles a missing recipient on its own, for the case where it is
     * opened from elsewhere.
     */
    private fun startCompose() {
        RecipientPicker(this).show(this) { picked ->
            startActivity(
                Intent(this, ConversationActivity::class.java)
                    .putExtra(ConversationActivity.EXTRA_ADDRESS, picked)
            )
        }
    }

    // ------------------------------------------------- long-press: categorise

    private fun showOptions(thread: ThreadSummary) {
        if (archiveMode) {
            MaterialAlertDialogBuilder(this)
                .setTitle(ContactNames.displayNameUi(thread.address))
                .setPositiveButton(R.string.unarchive) { _, _ -> senderStore.setArchived(thread.address, false); applyFilter() }
                .setNeutralButton(R.string.open) { _, _ -> openThread(thread) }
                .setNegativeButton(R.string.cancel, null).show()
            return
        }
        val inTrash = thread.categoryId == Cat.TRASH
        val options = if (inTrash) {
            arrayOf(
                getString(R.string.delete_forever),
                getString(R.string.delete_all)
            )
        } else {
            arrayOf(
                getString(if (senderStore.isPinned(thread.address)) R.string.unpin else R.string.pin),
                getString(R.string.archive),
                getString(if (senderStore.notificationsMuted(thread.address)) R.string.enable_notifications else R.string.mute_notifications),
                getString(R.string.change_category),
                getString(R.string.pick_color),
                getString(R.string.mark_spam),
                getString(R.string.mark_not_spam),
                getString(R.string.move_to_trash),
                getString(R.string.block_sender)
            )
        }
        val icons = if (inTrash) listOf(R.drawable.ic_tab_trash, R.drawable.ic_tab_trash)
            else listOf(R.drawable.ic_pin, R.drawable.ic_archive, R.drawable.ic_tab_service,
                R.drawable.ic_tab_all, R.drawable.ic_cat_shop, R.drawable.ic_tab_spam,
                R.drawable.ic_person, R.drawable.ic_tab_trash, R.drawable.ic_cat_security)
        ChoiceSheet.show(this, ContactNames.displayNameUi(thread.address),
            options.mapIndexed { index, label -> ChoiceSheet.Option(label, icons[index],
                if (label == getString(R.string.block_sender) || label == getString(R.string.delete_forever))
                    ContextCompat.getColor(this, R.color.danger) else null) }) { which ->
                if (inTrash) {
                    when (which) {
                        0 -> confirm(
                            R.string.delete_forever,
                            getString(
                                R.string.confirm_delete_thread,
                                ContactNames.displayNameUi(thread.address)
                            )
                        ) { deleteThreadForever(thread) }
                        1 -> confirm(
                            R.string.delete_all,
                            getString(R.string.confirm_empty_trash)
                        ) { emptyTrash() }
                    }
                    return@show
                }
                when (which) {
                    0 -> { senderStore.setPinned(thread.address, !senderStore.isPinned(thread.address)); applyFilter() }
                    1 -> { senderStore.setArchived(thread.address, true); applyFilter() }
                    2 -> { senderStore.setNotificationsMuted(thread.address, !senderStore.notificationsMuted(thread.address)); toast(R.string.saved) }
                    3 -> pickCategory(thread)
                    4 -> pickColor(thread)
                    5 -> confirm(R.string.mark_spam, getString(R.string.confirm_spam_msg)) {
                        changeCategory(thread, Cat.SPAM)
                    }
                    6 -> confirm(R.string.mark_not_spam, getString(R.string.confirm_ham_msg)) {
                        // Negative feedback: stop flagging this sender and tell
                        // the AI stage to leave it alone from now on.
                        senderStore.setPolicy(thread.address, SenderPolicy.NEVER_ANALYZE)
                        changeCategory(thread, Cat.OTHER)
                    }
                    7 -> confirm(R.string.move_to_trash, getString(R.string.confirm_trash_msg)) {
                        changeCategory(thread, Cat.TRASH)
                    }
                    8 -> confirm(R.string.block_sender, getString(R.string.confirm_block_msg)) {
                        RuleStore(this).add(thread.address, RuleTarget.SENDER, false)
                        toast(R.string.sender_blocked)
                    }
                }
            }
    }

    private fun showArchived() {
        contactsOnly = false
        binding.bottomNav.selectedItemId = R.id.nav_messages
        archiveMode = true
        applyFilter()
    }

    /**
     * Deletion is a provider write plus a cache invalidation, so it runs on the
     * worker and reloads the list when it is done.
     */
    private fun deleteThreadForever(thread: ThreadSummary) {
        worker.execute {
            val deleted = try {
                repo.deleteThread(thread.threadId)
            } catch (t: Throwable) {
                false
            }
            Classifier.invalidateCaches()
            if (deleted) ThreadCache.clear(this)
            main.post {
                if (isFinishing || isDestroyed) return@post
                toast(if (deleted) R.string.cleared else R.string.send_failed)
                loadThreads()
            }
        }
    }

    private fun emptyTrash() {
        val victims = allThreads.filter { it.categoryId == Cat.TRASH }.map { it.threadId }
        worker.execute {
            try {
                victims.forEach { repo.deleteThread(it) }
            } catch (t: Throwable) {
                // Whatever was deleted stays deleted; the reload shows the rest.
            }
            Classifier.invalidateCaches()
            ThreadCache.clear(this)
            main.post {
                if (isFinishing || isDestroyed) return@post
                toast(R.string.cleared)
                loadThreads()
            }
        }
    }

    /** Every state-changing choice passes through here, so nothing is one-tap. */
    private fun confirm(titleRes: Int, message: String, onYes: () -> Unit) {
        ConfirmSheet.show(this, getString(titleRes), message, R.drawable.ic_warning, onYes)
    }

    private fun pickCategory(thread: ThreadSummary) {
        val cats = CategoryStore(this).all()
        val labels = cats.map { it.label(this) }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.change_category)
            .setItems(labels) { _, which ->
                val chosen = cats[which]
                confirm(R.string.change_category, getString(R.string.confirm_category_msg)) {
                    changeCategory(thread, chosen.id)
                }
            }
            .show()
    }

    /** Compact, coloured category chooser for the label directly on a row. */
    private fun showCategoryPicker(thread: ThreadSummary) {
        val categories = CategoryStore(this).active()
            .filter { it.id != Cat.TRASH }
        var chosen = categories.indexOfFirst { it.id == thread.categoryId }.coerceAtLeast(0)
        val group = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.VERTICAL
            val p = (8 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        categories.forEachIndexed { index, category ->
            val ink = runCatching { Color.parseColor(category.colorHex) }.getOrDefault(theme.accentColor())
            val row = android.widget.RadioButton(this).apply {
                id = View.generateViewId()
                text = category.label(this@MainActivity)
                textSize = 15f
                buttonTintList = ColorStateList.valueOf(ink)
                setTextColor(ink)
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 14f * resources.displayMetrics.density
                    setColor(Color.argb(22, Color.red(ink), Color.green(ink), Color.blue(ink)))
                }
                val margin = (3 * resources.displayMetrics.density).toInt()
                layoutParams = android.widget.RadioGroup.LayoutParams(-1, -2).apply { setMargins(margin, margin, margin, margin) }
                setPadding(12, 9, 12, 9)
                isChecked = index == chosen
                setOnClickListener { chosen = index }
            }
            group.addView(row)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.change_category)
            .setView(group)
            .setNegativeButton("✕", null)
            .setPositiveButton("✓") { _, _ ->
                categories.getOrNull(chosen)?.let { changeCategory(thread, it.id) }
            }
            .show()
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)?.contentDescription = getString(R.string.cancel)
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.contentDescription = getString(R.string.confirm)
    }

    /**
     * Colour picker as a grid of tappable circles, so the whole palette is
     * visible at once instead of scrolled past as wide rows.
     */
    private fun pickColor(thread: ThreadSummary) {
        val density = resources.displayMetrics.density
        val pad = (8 * density).toInt()
        val cell = (44 * density).toInt()
        val current = senderStore.colorFor(thread.address)

        val grid = android.widget.GridLayout(this).apply {
            columnCount = 4
            setPadding(pad * 2, pad * 2, pad * 2, pad * 2)
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pick_color)
            .setView(grid)
            .setNeutralButton(R.string.color_default) { _, _ ->
                confirm(R.string.pick_color, getString(R.string.confirm_color_msg)) {
                    applyColor(thread, null)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()

        Categories.PALETTE.forEach { hex ->
            val wrapper = android.widget.FrameLayout(this).apply {
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = cell + pad * 2
                    height = cell + pad * 2
                }
                setPadding(pad, pad, pad, pad)
            }
            val circle = View(this).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(cell, cell)
                background = swatch(hex, hex == current)
                contentDescription = hex
                setOnClickListener {
                    dialog.dismiss()
                    confirm(R.string.pick_color, getString(R.string.confirm_color_msg)) {
                        applyColor(thread, hex)
                    }
                }
            }
            wrapper.addView(circle)
            grid.addView(wrapper)
        }
        dialog.show()
    }

    /** Colour circle; the active one carries a white ring. */
    private fun swatch(hex: String, selected: Boolean): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(Color.parseColor(hex))
            if (selected) {
                setStroke((3 * resources.displayMetrics.density).toInt(), Color.WHITE)
            }
        }

    private fun applyColor(thread: ThreadSummary, hex: String?) {
        val previous = senderStore.colorFor(thread.address)
        senderStore.setColor(thread.address, hex)
        Classifier.invalidateCaches()
        ThreadCache.clear(this)
        loadThreads()
        showUndo {
            senderStore.setColor(thread.address, previous)
            Classifier.invalidateCaches()
            ThreadCache.clear(this)
            loadThreads()
        }
    }

    private fun changeCategory(thread: ThreadSummary, categoryId: String) {
        val previousCat = senderStore.categoryFor(thread.address)
        val previousOverride = messageCats.categoryFor(thread.messageId)

        senderStore.setCategory(thread.address, categoryId)
        messageCats.set(thread.messageId, categoryId)

        // Learning loop. Only labels carrying a clear verdict train the model;
        // ambiguous categories are left alone.
        val isSpam = categoryId == Cat.SPAM
        val isHam = categoryId == Cat.OTHER ||
            categoryId == Cat.NOTIFICATION ||
            categoryId == Cat.PERSONAL
        if (isSpam || isHam) {
            SenderProfileStore(this).recordFeedback(thread.address, isSpam)
            LearnedWeights(this).record(thread.snippet, isSpam)
        }

        // Guarded domain learning: only a sender already marked repeatedly can
        // teach the blocklist, so one mis-tap cannot blacklist a real domain.
        if (isSpam) {
            // Generalise the verdict: every number running the same template
            // becomes suspect, including ones never seen before.
            CampaignStore(this).markSpam(thread.messageId)
            val profile = SenderProfileStore(this).snapshot()[thread.address]
            if (profile?.hostile == true) {
                val blocks = BlockStore(this)
                UrlIntel.extract(thread.snippet).forEach { blocks.blockDomain(it.host) }
            }
        }

        Classifier.invalidateCaches()
        ThreadCache.clear(this)
        loadThreads()

        showUndo {
            // Put the labels back AND take the training sample out again,
            // otherwise an undone decision would still have taught the model.
            if (isSpam || isHam) {
                SenderProfileStore(this).revertFeedback(thread.address, isSpam)
                LearnedWeights(this).revert(thread.snippet, isSpam)
            }
            senderStore.setCategory(thread.address, previousCat ?: Cat.OTHER)
            messageCats.set(
                thread.messageId,
                previousOverride ?: previousCat ?: Cat.OTHER
            )
            Classifier.invalidateCaches()
            ThreadCache.clear(this)
            loadThreads()
        }
    }

    /** Five-second undo affordance shown after every change. */
    private fun showUndo(onUndo: () -> Unit) {
        Snackbar.make(binding.root, R.string.applied, 5000)
            .setAction(R.string.undo) { onUndo() }
            .show()
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------ blocked log

    private fun showBlockedLog() {
        val blocked = blockedStore.all()
        val fields = if (blocked.isEmpty()) listOf(InfoSheet.Field(
            getString(R.string.blocked_log), getString(R.string.blocked_empty), R.drawable.ic_tab_spam))
        else blocked.map { entry -> InfoSheet.Field(
            "${entry.address} · ${Dates.full(this, entry.date)}",
            "${entry.body}\n${entry.rulePattern}", R.drawable.ic_tab_spam)
        }
        InfoSheet.show(this, getString(R.string.blocked_log) + " (${blocked.size})", fields) {
                blockedStore.clear()
                Toast.makeText(this, R.string.cleared, Toast.LENGTH_SHORT).show()
            }
    }
}
