package ir.inod.smsguard

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Color
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

        /** Tab order, matching the chips built in [setUpFilterChips]. */
        const val TAB_ALL = 0
        const val TAB_CONTACTS = 1
        const val TAB_SUSPICIOUS = 2
        const val TAB_SPAM = 3
        const val TAB_BANKING = 4
        const val TAB_SERVICE = 5
        const val TAB_TRASH = 6
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
    private var query: String = ""
    private var rendered: List<ThreadSummary> = emptyList()
    private var drawnLayout: RowLayout? = null
    private var loadedFromCache = false

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var skeletonPulse: android.animation.ObjectAnimator? = null

    private val refresh: () -> Unit = { loadThreads() }

    private val roleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshBanner() }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { loadThreads() }

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
            onClick = { thread -> openThread(thread) },
            onLongClick = { thread -> showOptions(thread) }
        )
        binding.recyclerThreads.layoutManager = LinearLayoutManager(this)
        binding.recyclerThreads.adapter = adapter

        setUpFilterChips()
        applyAppearance()

        binding.buttonMakeDefault.setOnClickListener { requestDefaultRole() }
        binding.fabCompose.setOnClickListener { startCompose() }
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    false
                }
                else -> true
            }
        }

        // The stored inbox is on screen before the provider is even queried —
        // but it is read off the main thread, because at this point the cache
        // file can be at its largest.
        ensurePermissions()
        loadCachedThreads()
    }

    /**
     * Filter chips rather than a tab strip: they scroll horizontally, read as
     * pills, and the selected one fills with the primary colour.
     */
    private fun setUpFilterChips() {
        val labels = listOf(
            R.string.tab_all,
            R.string.tab_contacts,
            R.string.tab_suspicious,
            R.string.tab_spam,
            R.string.tab_banking,
            R.string.tab_notifications,
            R.string.tab_trash
        )
        // One icon per category, the way the reference strip reads: the shape
        // carries as much meaning as the word and survives a narrow screen
        // where the label has to be cut.
        val icons = listOf(
            R.drawable.ic_tab_all,
            R.drawable.ic_person,
            R.drawable.ic_tab_suspicious,
            R.drawable.ic_tab_spam,
            R.drawable.ic_tab_banking,
            R.drawable.ic_tab_service,
            R.drawable.ic_tab_trash
        )
        val idToIndex = HashMap<Int, Int>()
        val density = resources.displayMetrics.density
        labels.forEachIndexed { index, res ->
            val chip = com.google.android.material.chip.Chip(this).apply {
                text = getString(res)
                isCheckable = true
                isClickable = true
                id = View.generateViewId()
                // Filled rectangle when selected, pale grey otherwise: the
                // reference's filter strip, not the outlined default chip.
                chipBackgroundColor =
                    ContextCompat.getColorStateList(this@MainActivity, R.color.chip_bg)
                setTextColor(
                    ContextCompat.getColorStateList(this@MainActivity, R.color.chip_text)
                )
                chipIcon = ContextCompat.getDrawable(this@MainActivity, icons[index])
                chipIconTint =
                    ContextCompat.getColorStateList(this@MainActivity, R.color.chip_text)
                chipIconSize = 16f * density
                chipStrokeWidth = 0f
                chipStartPadding = 10f * density
                chipEndPadding = 12f * density
                chipCornerRadius = 16f * density
                chipMinHeight = 44f * density
                // The Kotlin property is private; the public setter is not.
                setEnsureMinTouchTargetSize(true)
            }
            idToIndex[chip.id] = index
            binding.chipGroup.addView(chip)
        }
        binding.chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val first = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            selectedTab = idToIndex[first] ?: TAB_ALL
            applyFilter()
        }
        (binding.chipGroup.getChildAt(0) as? com.google.android.material.chip.Chip)
            ?.isChecked = true
    }

    /** Hides the parts of the screen the user asked not to see. */
    private fun applyChipVisibility() {
        binding.chipScroll.visibility = if (theme.showChips) View.VISIBLE else View.GONE
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
        refreshBanner()
        applyChipVisibility()

        // Appearance may have changed on the settings screen. Applying the new
        // layout in place means the inbox behind it is already correct when the
        // user comes back.
        val next = theme.snapshot()
        if (next != drawnLayout) {
            drawnLayout = next
            adapter.applyLayout(next)
            applyListPadding()
            applyFilter()
        }

        loadThreads()
    }

    private fun applyAppearance() {
        drawnLayout = theme.snapshot()
        adapter.applyLayout(drawnLayout!!)
        applyChipVisibility()
        applyListPadding()
        if (!theme.showChips) binding.chipScroll.visibility = View.GONE
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
            Manifest.permission.READ_CONTACTS
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
        if (allThreads.isNotEmpty() || loadedFromCache) return
        worker.execute {
            val cached = try {
                repo.cachedThreads()
            } catch (t: Throwable) {
                emptyList()
            }
            if (cached.isEmpty()) return@execute
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (allThreads.isNotEmpty()) return@runOnUiThread
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
                repo.loadThreads()
            } catch (t: Throwable) {
                emptyList()
            }
            val elapsed = System.currentTimeMillis() - started
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (threads.isNotEmpty()) allThreads = threads
                showSkeleton(false)
                applyFilter()
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("SmsGuard", "inbox sync finished in ${elapsed}ms")
                }
            }
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
        menu.add(0, MENU_REFRESH, 1, R.string.refresh)
        menu.add(0, MENU_RULES, 2, R.string.rules)
        menu.add(0, MENU_BLOCKED, 3, R.string.blocked_log)
        menu.add(0, MENU_MANAGE, 4, R.string.manage_brands)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        when (item.itemId) {
            MENU_SEARCH -> showSearch()
            MENU_REFRESH -> {
                ThreadCache.clear(this)
                toast(R.string.refreshing)
                loadThreads()
            }
            MENU_RULES -> startActivity(Intent(this, RulesActivity::class.java))
            MENU_BLOCKED -> showBlockedLog()
            MENU_MANAGE -> startActivity(Intent(this, ManagerActivity::class.java))
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    /**
     * Search filters the already-loaded rows instead of re-reading the
     * provider, so results appear as the user types.
     */
    private fun showSearch() {
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.search_hint)
            setText(query)
            setSelection(text.length)
            setPadding(48, 32, 48, 32)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.search)
            .setView(input)
            .setNegativeButton(R.string.clear) { _, _ ->
                query = ""
                applyFilter()
            }
            .setPositiveButton(R.string.search) { _, _ ->
                query = input.text?.toString()?.trim().orEmpty()
                applyFilter()
            }
            .show()
    }

    private fun applyFilter() {
        val spamIds = CategoryStore(this).all().filter { it.spamFolder }.map { it.id }.toSet()
        val byTab = when (selectedTab) {
            TAB_CONTACTS -> allThreads.filter { ContactsIndex.isKnownContact(it.address) }
            TAB_SUSPICIOUS -> allThreads.filter { it.categoryId == Cat.SUSPICIOUS }
            TAB_SPAM -> allThreads.filter { it.categoryId in spamIds }
            TAB_BANKING -> allThreads.filter {
                it.categoryId == Cat.BANKING || it.categoryId == Cat.OTP
            }
            TAB_SERVICE -> allThreads.filter { it.categoryId == Cat.NOTIFICATION }
            TAB_TRASH -> allThreads.filter { it.categoryId == Cat.TRASH }
            // "All" hides the spam folder and the trash alike.
            else -> allThreads.filterNot {
                it.categoryId in spamIds || it.categoryId == Cat.TRASH
            }
        }
        val filtered = if (query.isBlank()) {
            byTab
        } else {
            val needle = query.lowercase()
            byTab.filter {
                it.snippet.lowercase().contains(needle) ||
                    it.address.lowercase().contains(needle) ||
                    ContactNames.displayNameUi(it.address).lowercase().contains(needle)
            }
        }

        if (rendered.isEmpty()) adapter.submit(filtered) else adapter.merge(filtered)
        rendered = filtered

        binding.textEmptyLabel.setText(
            when (selectedTab) {
                TAB_TRASH -> R.string.trash_empty
                TAB_CONTACTS -> R.string.no_contacts_threads
                else -> R.string.no_threads
            }
        )
        binding.textEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
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
        if (selectedTab == TAB_TRASH) {
            showTrashOptions(thread)
            return
        }
        val options = arrayOf(
            getString(R.string.change_category),
            getString(R.string.pick_color),
            getString(R.string.mark_spam),
            getString(R.string.mark_not_spam),
            getString(R.string.move_to_trash),
            getString(R.string.block_sender)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(ContactNames.displayNameUi(thread.address))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> pickCategory(thread)
                    1 -> pickColor(thread)
                    2 -> confirm(R.string.mark_spam, getString(R.string.confirm_spam_msg)) {
                        changeCategory(thread, Cat.SPAM)
                    }
                    3 -> confirm(R.string.mark_not_spam, getString(R.string.confirm_ham_msg)) {
                        // Negative feedback: stop flagging this sender and tell
                        // the AI stage to leave it alone from now on.
                        senderStore.setPolicy(thread.address, SenderPolicy.NEVER_ANALYZE)
                        changeCategory(thread, Cat.OTHER)
                    }
                    4 -> confirm(R.string.move_to_trash, getString(R.string.confirm_trash_msg)) {
                        changeCategory(thread, Cat.TRASH)
                    }
                    5 -> confirm(R.string.block_sender, getString(R.string.confirm_block_msg)) {
                        RuleStore(this).add(thread.address, RuleTarget.SENDER, false)
                        toast(R.string.sender_blocked)
                    }
                }
            }
            .show()
    }

    /**
     * Trash actions. A permanent delete is the only irreversible thing in the
     * app and has no undo, so the sender is named back in the confirmation.
     */
    private fun showTrashOptions(thread: ThreadSummary) {
        val label = ContactNames.displayNameUi(thread.address)
        val options = arrayOf(
            getString(R.string.delete_forever),
            getString(R.string.delete_all)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(label)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> confirm(
                        R.string.delete_forever,
                        getString(R.string.confirm_delete_thread, label)
                    ) { deleteThreadForever(thread) }
                    1 -> confirm(
                        R.string.delete_all,
                        getString(R.string.confirm_empty_trash)
                    ) { emptyTrash() }
                }
            }
            .show()
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
        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setMessage(message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ -> onYes() }
            .show()
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
        val message = if (blocked.isEmpty()) {
            getString(R.string.blocked_empty)
        } else {
            blocked.joinToString("\n\n") {
                "${Dates.full(this, it.date)}\n${it.address}\n${it.body}\n[${it.rulePattern}]"
            }
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.blocked_log) + " (${blocked.size})")
            .setMessage(message)
            .setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.clear) { _, _ ->
                blockedStore.clear()
                Toast.makeText(this, R.string.cleared, Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}
