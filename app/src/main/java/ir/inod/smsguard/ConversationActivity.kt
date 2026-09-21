package ir.inod.smsguard

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import ir.inod.smsguard.databinding.ActivityConversationBinding

class ConversationActivity : BaseActivity() {

    companion object {
        const val EXTRA_THREAD_ID = "extra_thread_id"
        const val EXTRA_ADDRESS = "extra_address"
        private const val MENU_BLOCK_SENDER = 1001
    }

    private lateinit var binding: ActivityConversationBinding
    private lateinit var adapter: MessageAdapter
    private val theme by lazy { ThemePrefs(this) }

    private val repo by lazy { SmsRepository(this) }
    private var threadId: Long = -1L
    private var address: String = ""

    /** What the current list was drawn with, so a change can be detected. */
    private var drawnLayout: MessageLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConversationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val extraAddress = intent.getStringExtra(EXTRA_ADDRESS).orEmpty()
        threadId = intent.getLongExtra(EXTRA_THREAD_ID, -1L)

        // Launched from an sms:/smsto: link, e.g. from a browser or another app.
        val linkAddress = intent.data?.schemeSpecificPart?.substringBefore('?').orEmpty()

        address = when {
            extraAddress.isNotBlank() -> extraAddress
            linkAddress.isNotBlank() -> linkAddress
            // Both of these are provider round trips, so they are resolved on
            // the worker below rather than here.
            else -> ""
        }

        supportActionBar?.title = ContactNames.displayNameUi(address)

        adapter = MessageAdapter { message -> confirmDeleteMessage(message) }
        binding.recyclerMessages.layoutManager =
            LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.recyclerMessages.adapter = adapter

        binding.buttonSend.setOnClickListener { sendCurrent() }
    }

    override fun onResume() {
        super.onResume()
        applyAppearance()
        load()
    }

    /**
     * Pushes the bubble appearance into the list. Called on every resume, so a
     * change made on the settings screen lands the moment the user comes back
     * instead of on the next visit.
     */
    private fun applyAppearance() {
        val next = theme.messageLayout()
        if (next != drawnLayout) {
            drawnLayout = next
            adapter.applyLayout(next)
        }
    }

    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()

    /**
     * Reading and classifying a thread is real work (provider query plus one
     * classification per sender). Running it on the main thread produced
     * "ANR in ir.inod.smsguard" on a real device, so it is dispatched.
     *
     * Resolving the thread id from an address, and marking the thread read, are
     * provider writes; they happen here too, one step before the read, so the
     * list the user sees already reflects the read state.
     */
    private fun load() {
        worker.execute {
            if (address.isBlank() && threadId >= 0) {
                address = try {
                    repo.addressForThread(threadId)
                } catch (t: Throwable) {
                    ""
                }
            }
            if (threadId < 0 && address.isNotBlank()) {
                threadId = try {
                    repo.threadIdFor(address)
                } catch (t: Throwable) {
                    -1L
                }
            }
            if (threadId >= 0) {
                repo.markThreadRead(threadId)
                Notifier(this).cancel(threadId)
            }

            val messages = try {
                if (threadId >= 0) repo.loadMessages(threadId) else emptyList()
            } catch (t: Throwable) {
                emptyList()
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                supportActionBar?.title = ContactNames.displayNameUi(address)
                adapter.submit(messages)
                // The adapter also emits day dividers, so scroll to its own
                // last row rather than to messages.size.
                if (adapter.itemCount > 0) {
                    binding.recyclerMessages.scrollToPosition(adapter.itemCount - 1)
                }
            }
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    /**
     * Dispatching is asynchronous, so the field is cleared and the reply
     * arrives through [load] like any other message.
     */
    private fun sendCurrent() {
        val text = binding.editMessage.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        if (address.isBlank()) {
            Toast.makeText(this, R.string.no_recipient, Toast.LENGTH_SHORT).show()
            return
        }
        binding.editMessage.setText("")
        // Sending writes to the provider (and to the stored inbox) before
        // returning, so it belongs on the same worker as everything else.
        worker.execute {
            val sent = try {
                repo.send(address, text)
            } catch (t: Throwable) {
                false
            }
            if (!sent) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        Toast.makeText(this, R.string.send_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            load()
        }
    }

    /**
     * Single-message deletion. Removing one message from a conversation is
     * allowed here rather than in a trash flow, because the rest of the thread
     * still exists as context.
     */
    private fun confirmDeleteMessage(message: SmsMessage) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.delete_message)
            .setMessage(R.string.confirm_delete_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ ->
                if (repo.deleteMessage(message.id)) {
                    Toast.makeText(this, R.string.cleared, Toast.LENGTH_SHORT).show()
                    load()
                } else {
                    Toast.makeText(this, R.string.send_failed, Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_BLOCK_SENDER, 0, R.string.block_sender)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                return true
            }
            MENU_BLOCK_SENDER -> {
                if (address.isNotBlank()) {
                    RuleStore(this).add(address, RuleTarget.SENDER, false)
                    Toast.makeText(this, R.string.sender_blocked, Toast.LENGTH_SHORT).show()
                }
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }
}
