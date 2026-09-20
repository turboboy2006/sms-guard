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
import com.google.android.material.tabs.TabLayout
import ir.inod.smsguard.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

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

        setUpTabs()

        binding.buttonMakeDefault.setOnClickListener { requestDefaultRole() }
        binding.buttonRules.setOnClickListener {
            startActivity(Intent(this, RulesActivity::class.java))
        }
        binding.buttonSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.buttonBlocked.setOnClickListener { showBlockedLog() }

        ensurePermissions()
    }

    private fun setUpTabs() {
        val labels = listOf(
            R.string.tab_all,
            R.string.tab_suspicious,
            R.string.tab_spam,
            R.string.tab_banking,
            R.string.tab_notifications
        )
        labels.forEach { binding.tabs.addTab(binding.tabs.newTab().setText(it)) }
        // A nested class cannot be referenced through its fully-qualified outer
        // name in Kotlin, so TabLayout is imported and used unqualified.
        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                selectedTab = tab.position
                applyFilter()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
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

    /**
     * Scanning the SMS provider and classifying every row is far too heavy for
     * the main thread: doing it there produced
     * "ANR in ir.inod.smsguard (MainActivity)" on a real device.
     */
    private fun loadThreads() {
        worker.execute {
            val threads = try {
                repo.loadThreads()
            } catch (t: Throwable) {
                emptyList()
            }
            runOnUiThread {
                allThreads = threads
                applyFilter()
            }
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun applyFilter() {
        val spamIds = CategoryStore(this).all().filter { it.spamFolder }.map { it.id }.toSet()
        val filtered = when (selectedTab) {
            1 -> allThreads.filter { it.categoryId == Cat.SUSPICIOUS }
            2 -> allThreads.filter { it.categoryId in spamIds }
            3 -> allThreads.filter { it.categoryId == Cat.BANKING || it.categoryId == Cat.OTP }
            4 -> allThreads.filter { it.categoryId == Cat.NOTIFICATION }
            else -> allThreads.filterNot { it.categoryId in spamIds }
        }
        adapter.submit(filtered)
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
        val options = arrayOf(
            getString(R.string.change_category),
            getString(R.string.pick_color),
            getString(R.string.mark_spam),
            getString(R.string.mark_not_spam),
            getString(R.string.block_sender)
        )
        AlertDialog.Builder(this)
            .setTitle(ContactNames.displayName(this, thread.address))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> pickCategory(thread)
                    1 -> pickColor(thread)
                    2 -> assign(thread, Cat.SPAM, null)
                    3 -> {
                        // Negative feedback: stop flagging this sender and tell
                        // the AI stage to leave it alone from now on.
                        senderStore.setPolicy(thread.address, SenderPolicy.NEVER_ANALYZE)
                        assign(thread, Cat.OTHER, null)
                    }
                    4 -> {
                        RuleStore(this).add(thread.address, RuleTarget.SENDER, false)
                        Toast.makeText(this, R.string.sender_blocked, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun pickCategory(thread: ThreadSummary) {
        val cats = CategoryStore(this).all()
        val labels = cats.map { it.label(this) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.change_category)
            .setItems(labels) { _, which -> assign(thread, cats[which].id, null) }
            .show()
    }

    /** Coloured rows rendered without needing another layout file. */
    private fun pickColor(thread: ThreadSummary) {
        val palette = Categories.PALETTE
        val listAdapter = object : BaseAdapter() {
            override fun getCount(): Int = palette.size
            override fun getItem(position: Int): Any = palette[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tv = (convertView as? TextView) ?: TextView(this@MainActivity).apply {
                    setPadding(56, 40, 56, 40)
                    textSize = 16f
                }
                tv.text = palette[position]
                tv.setBackgroundColor(Color.parseColor(palette[position]))
                tv.setTextColor(Color.WHITE)
                return tv
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.pick_color)
            .setAdapter(listAdapter) { _, which ->
                senderStore.setColor(thread.address, palette[which])
                reloadAfterAssign(thread)
            }
            .setNeutralButton(R.string.color_default) { _, _ ->
                senderStore.setColor(thread.address, null)
                reloadAfterAssign(thread)
            }
            .show()
    }

    private fun assign(thread: ThreadSummary, categoryId: String, colorHex: String?) {
        senderStore.setCategory(thread.address, categoryId)
        messageCats.set(thread.messageId, categoryId)
        if (colorHex != null) senderStore.setColor(thread.address, colorHex)
        reloadAfterAssign(thread)
    }

    private fun reloadAfterAssign(thread: ThreadSummary) {
        // Writes invalidate the app-wide classify caches.
        Classifier.invalidateCaches()
        Toast.makeText(this, R.string.applied, Toast.LENGTH_SHORT).show()
        loadThreads()
    }

    // ------------------------------------------------------------ blocked log

    private fun showBlockedLog() {
        val blocked = blockedStore.all()
        val message = if (blocked.isEmpty()) {
            getString(R.string.blocked_empty)
        } else {
            blocked.joinToString("\n\n") {
                "${Dates.full(it.date)}\n${it.address}\n${it.body}\n[${it.rulePattern}]"
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
