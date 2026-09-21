package ir.inod.smsguard

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.provider.Telephony
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import ir.inod.smsguard.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private companion object {
        const val MENU_MANAGE = 2001
        const val MENU_SEARCH = 2002
        const val MENU_RULES = 2003
        const val MENU_BLOCKED = 2004
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ThreadAdapter

    private val repo by lazy { SmsRepository(this) }
    private val blockedStore by lazy { BlockedStore(this) }
    private val senderStore by lazy { SenderStore(this) }
    private val messageCats by lazy { MessageCategoryStore(this) }

    private var allThreads: List<ThreadSummary> = emptyList()
    private var selectedTab = 0

    private val refresh: () -> Unit = { loadThreads() }

    private val roleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshBanner() }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { loadThreads() }

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

        binding.buttonMakeDefault.setOnClickListener { requestDefaultRole() }
        binding.fabCompose.setOnClickListener {
            startActivity(Intent(this, ConversationActivity::class.java))
        }
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    false
                }
                else -> true
            }
        }

        ensurePermissions()
    }

    /**
     * Filter chips rather than a tab strip: they scroll horizontally, read as
     * pills, and the selected one fills with the primary colour.
     */
    private fun setUpFilterChips() {
        val labels = listOf(
            R.string.tab_all,
            R.string.tab_suspicious,
            R.string.tab_spam,
            R.string.tab_banking,
            R.string.tab_notifications,
            R.string.tab_trash
        )
        val idToIndex = HashMap<Int, Int>()
        labels.forEachIndexed { index, res ->
            val chip = com.google.android.material.chip.Chip(this).apply {
                text = getString(res)
                isCheckable = true
                isClickable = true
                id = View.generateViewId()
            }
            idToIndex[chip.id] = index
            binding.chipGroup.addView(chip)
        }
        binding.chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val first = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            selectedTab = idToIndex[first] ?: 0
            applyFilter()
        }
        (binding.chipGroup.getChildAt(0) as? com.google.android.material.chip.Chip)
            ?.isChecked = true
    }

    // ------------------------------------------------------------- lifecycle

    override fun onStart() {
        super.onStart()
        // The AI stage can re-label a message after the fact; refresh when it does.
        MessageBus.register(refresh)
    }

    override fun onStop() {
        MessageBus.unregister(refresh)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshBanner()
        loadThreads()
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

    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var skeletonPulse: android.animation.ObjectAnimator? = null

    /**
     * Scanning the SMS provider and classifying every row is far too heavy for
     * the main thread: doing it there produced
     * "ANR in ir.inod.smsguard (MainActivity)" on a real device.
     *
     * A skeleton placeholder covers the wait, so the screen never looks frozen
     * or empty while the work runs.
     */
    private fun loadThreads() {
        if (allThreads.isEmpty()) showSkeleton(true)
        worker.execute {
            val threads = try {
                repo.loadThreads()
            } catch (t: Throwable) {
                emptyList()
            }
            runOnUiThread {
                allThreads = threads
                showSkeleton(false)
                applyFilter()
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
            .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, MENU_RULES, 1, R.string.rules)
        menu.add(0, MENU_BLOCKED, 2, R.string.blocked_log)
        menu.add(0, MENU_MANAGE, 3, R.string.manage_brands)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        when (item.itemId) {
            MENU_SEARCH -> toast(R.string.search)
            MENU_RULES -> startActivity(Intent(this, RulesActivity::class.java))
            MENU_BLOCKED -> showBlockedLog()
            MENU_MANAGE -> startActivity(Intent(this, ManagerActivity::class.java))
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun applyFilter() {
        val spamIds = CategoryStore(this).all().filter { it.spamFolder }.map { it.id }.toSet()
        val filtered = when (selectedTab) {
            1 -> allThreads.filter { it.categoryId == Cat.SUSPICIOUS }
            2 -> allThreads.filter { it.categoryId in spamIds }
            3 -> allThreads.filter { it.categoryId == Cat.BANKING || it.categoryId == Cat.OTP }
            4 -> allThreads.filter { it.categoryId == Cat.NOTIFICATION }
            5 -> allThreads.filter { it.categoryId == Cat.TRASH }
            // "All" hides the spam folder and the trash alike.
            else -> allThreads.filterNot {
                it.categoryId in spamIds || it.categoryId == Cat.TRASH
            }
        }
        adapter.submit(filtered)
        binding.textEmpty.setText(
            if (selectedTab == 5) R.string.trash_empty else R.string.no_threads
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

    // ------------------------------------------------- long-press: categorise

    private fun showOptions(thread: ThreadSummary) {
        if (selectedTab == 5) {
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
            .setTitle(ContactNames.displayName(this, thread.address))
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
        val label = ContactNames.displayName(this, thread.address)
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

    private fun deleteThreadForever(thread: ThreadSummary) {
        if (repo.deleteThread(thread.threadId)) {
            Classifier.invalidateCaches()
            loadThreads()
            toast(R.string.cleared)
        } else {
            toast(R.string.send_failed)
        }
    }

    private fun emptyTrash() {
        allThreads.filter { it.categoryId == Cat.TRASH }
            .forEach { repo.deleteThread(it.threadId) }
        Classifier.invalidateCaches()
        loadThreads()
        toast(R.string.cleared)
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
        loadThreads()
        showUndo {
            senderStore.setColor(thread.address, previous)
            Classifier.invalidateCaches()
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
