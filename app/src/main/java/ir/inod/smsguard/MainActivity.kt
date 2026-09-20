package ir.inod.smsguard

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.provider.Telephony
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import ir.inod.smsguard.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ThreadAdapter

    private val repo by lazy { SmsRepository(this) }
    private val ruleStore by lazy { RuleStore(this) }
    private val blockedStore by lazy { BlockedStore(this) }

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

        adapter = ThreadAdapter { thread ->
            startActivity(
                Intent(this, ConversationActivity::class.java).apply {
                    putExtra(ConversationActivity.EXTRA_THREAD_ID, thread.threadId)
                    putExtra(ConversationActivity.EXTRA_ADDRESS, thread.address)
                }
            )
        }
        binding.recyclerThreads.layoutManager = LinearLayoutManager(this)
        binding.recyclerThreads.adapter = adapter

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
        if (missing.isEmpty()) {
            loadThreads()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    // -------------------------------------------------------------- default app

    private fun isDefaultSmsApp(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_SMS) == true
        } else {
            packageName == Telephony.Sms.getDefaultSmsPackage(this)
        }

    private fun requestDefaultRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null &&
                rm.isRoleAvailable(RoleManager.ROLE_SMS) &&
                !rm.isRoleHeld(RoleManager.ROLE_SMS)
            ) {
                roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_SMS))
                return
            }
        }
        // Older releases, or the role is unavailable: send the user to Settings.
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
        binding.cardDefaultBanner.visibility =
            if (isDefaultSmsApp()) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------------------------- lists

    private fun loadThreads() {
        val threads = repo.loadThreads()
        adapter.submit(threads)
        binding.textEmpty.visibility = if (threads.isEmpty()) View.VISIBLE else View.GONE
    }

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
