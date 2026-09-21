package ir.inod.smsguard

import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import ir.inod.smsguard.databinding.ActivityConversationBinding

class ConversationActivity : BaseActivity() {

    companion object {
        const val EXTRA_THREAD_ID = "extra_thread_id"
        const val EXTRA_ADDRESS = "extra_address"

        /**
         * Sent by the compose button. The screen opens with nobody to write to
         * and asks, instead of showing an empty thread the user cannot use.
         */
        const val EXTRA_PICK_RECIPIENT = "extra_pick_recipient"

        private const val MENU_BLOCK_SENDER = 1001
        private const val MENU_NEW_MESSAGE = 1002
        private const val MENU_DELETE_SELECTED = 1003
        private const val MENU_SPAM_SELECTED = 1004
        private const val MENU_COPY_SELECTED = 1005
        private const val MENU_SHARE_SELECTED = 1006
        private const val MENU_SELECT_ALL = 1007
        private const val MENU_FORWARD_SELECTED = 1008
        private const val MENU_DETAILS = 1009
    }

    private lateinit var binding: ActivityConversationBinding
    private lateinit var adapter: MessageAdapter
    private val theme by lazy { ThemePrefs(this) }

    private val repo by lazy { SmsRepository(this) }
    private var threadId: Long = -1L
    private var address: String = ""
    private var riskyMessageId: Long = -1L
    private val drafts by lazy { getSharedPreferences("conversation_drafts", MODE_PRIVATE) }
    private val deliveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { load() }
    }

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

        adapter = MessageAdapter(
            onLongClick = { message -> adapter.toggleSelection(message) },
            onClick = { message ->
                if (adapter.selectionCount > 0) adapter.toggleSelection(message)
            },
            onRetry = { message -> retry(message) },
            onSelectionChanged = { count -> updateSelectionUi(count) }
        )
        binding.recyclerMessages.layoutManager =
            LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.recyclerMessages.adapter = adapter

        binding.buttonSend.setOnClickListener { sendCurrent() }
        binding.buttonPickRecipient.setOnClickListener { pickRecipient() }
        binding.buttonNotSpam.setOnClickListener { markConversationSafe() }
        binding.buttonRiskBlock.setOnClickListener { blockRiskySender() }
        binding.editMessage.doAfterTextChanged { editable ->
            if (address.isNotBlank()) drafts.edit().putString(address, editable?.toString().orEmpty()).apply()
            val count = editable?.length ?: 0
            if (count == 0) {
                binding.textCharacterCount.text = ""
            } else {
                val parts = android.telephony.SmsMessage.calculateLength(editable, false)[0]
                binding.textCharacterCount.text = getString(R.string.sms_counter, count, parts)
            }
        }
        updateEmptyState()
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this, deliveryReceiver, IntentFilter(DeliveryStatusReceiver.ACTION_UPDATED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        try { unregisterReceiver(deliveryReceiver) } catch (_: Exception) { }
        super.onStop()
    }

    /**
     * Asks who the message is for.
     *
     * Called when this screen was opened with nobody to write to — through the
     * compose button, or after a purge of the current conversation. The picker
     * is the same one the inbox uses, so "new message" behaves identically from
     * both places.
     */
    private fun pickRecipient() {
        RecipientPicker(this).show(this) { picked ->
            address = picked
            threadId = -1L
            applyAppearance()
            load()
        }
    }

    /**
     * Shows the "no recipient" panel instead of an empty message list. An empty
     * thread and a thread with nobody in it look the same otherwise, and only
     * one of them is a dead end.
     */
    private fun updateEmptyState() {
        val waiting = address.isBlank()
        binding.textNoRecipient.visibility = if (waiting) View.VISIBLE else View.GONE
        binding.recyclerMessages.visibility = if (waiting) View.GONE else View.VISIBLE
        binding.editMessage.isEnabled = !waiting
        binding.buttonSend.isEnabled = !waiting
        if (waiting) supportActionBar?.title = getString(R.string.compose)
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
        binding.buttonSend.background?.mutate()?.let {
            DrawableCompat.setTint(it, theme.accentColor())
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
                supportActionBar?.title = if (address.isBlank()) {
                    getString(R.string.compose)
                } else {
                    ContactNames.displayNameUi(address)
                }
                updateEmptyState()
                if (binding.editMessage.text.isNullOrEmpty() && address.isNotBlank()) {
                    binding.editMessage.setText(drafts.getString(address, "").orEmpty())
                    binding.editMessage.setSelection(binding.editMessage.length())
                }
                adapter.submit(messages)
                bindRiskBanner(messages)
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
        drafts.edit().remove(address).apply()
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
                // A provider delete is I/O; it runs on the worker like every
                // other provider call in this screen.
                worker.execute {
                    val deleted = try {
                        repo.deleteMessage(message.id)
                    } catch (t: Throwable) {
                        false
                    }
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        Toast.makeText(
                            this,
                            if (deleted) R.string.cleared else R.string.send_failed,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    if (deleted) load()
                }
            }
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_NEW_MESSAGE, 0, R.string.compose)
        menu.add(0, MENU_BLOCK_SENDER, 1, R.string.block_sender)
        menu.add(0, MENU_SPAM_SELECTED, 0, R.string.mark_spam)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, MENU_DELETE_SELECTED, 1, R.string.delete)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, MENU_COPY_SELECTED, 2, R.string.copy)
        menu.add(0, MENU_SHARE_SELECTED, 3, R.string.share)
        menu.add(0, MENU_SELECT_ALL, 4, R.string.select_all)
        menu.add(0, MENU_FORWARD_SELECTED, 5, R.string.forward)
        menu.add(0, MENU_DETAILS, 6, R.string.message_details)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val selecting = ::adapter.isInitialized && adapter.selectionCount > 0
        menu.findItem(MENU_NEW_MESSAGE)?.isVisible = !selecting
        menu.findItem(MENU_BLOCK_SENDER)?.isVisible = !selecting
        menu.findItem(MENU_SPAM_SELECTED)?.isVisible = selecting
        menu.findItem(MENU_DELETE_SELECTED)?.isVisible = selecting
        menu.findItem(MENU_COPY_SELECTED)?.isVisible = selecting
        menu.findItem(MENU_SHARE_SELECTED)?.isVisible = selecting
        menu.findItem(MENU_SELECT_ALL)?.isVisible = selecting
        menu.findItem(MENU_FORWARD_SELECTED)?.isVisible = selecting
        menu.findItem(MENU_DETAILS)?.isVisible = selecting && adapter.selectionCount == 1
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                return true
            }
            MENU_NEW_MESSAGE -> {
                if (address.isBlank()) {
                    // Already a blank compose screen: just ask again.
                    pickRecipient()
                } else {
                    startActivity(
                        Intent(this, ConversationActivity::class.java)
                            .putExtra(ConversationActivity.EXTRA_PICK_RECIPIENT, true)
                    )
                }
                return true
            }
            MENU_BLOCK_SENDER -> {
                if (address.isNotBlank()) {
                    RuleStore(this).add(address, RuleTarget.SENDER, false)
                    Toast.makeText(this, R.string.sender_blocked, Toast.LENGTH_SHORT).show()
                }
                return true
            }
            MENU_SPAM_SELECTED -> {
                markSelectedSpam()
                return true
            }
            MENU_DELETE_SELECTED -> {
                confirmDeleteSelected()
                return true
            }
            MENU_COPY_SELECTED -> { copySelected(); return true }
            MENU_SHARE_SELECTED -> { shareSelected(); return true }
            MENU_SELECT_ALL -> { adapter.selectAll(); return true }
            MENU_FORWARD_SELECTED -> { forwardSelected(); return true }
            MENU_DETAILS -> { showSelectedDetails(); return true }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun updateSelectionUi(count: Int) {
        supportActionBar?.title = if (count > 0) Dates.count(this, count)
        else ContactNames.displayNameUi(address)
        invalidateOptionsMenu()
    }

    private fun bindRiskBanner(messages: List<SmsMessage>) {
        val risky = messages.lastOrNull {
            it.isIncoming && (it.categoryId == Cat.SUSPICIOUS || it.categoryId == Cat.SPAM)
        }
        riskyMessageId = risky?.id ?: -1L
        binding.cardRisk.visibility = if (risky == null) View.GONE else View.VISIBLE
        if (risky != null) {
            binding.textRisk.text = Classifier.riskLabel(this, address, risky.body)
                ?: getString(R.string.risk_banner_default)
        }
    }

    private fun markConversationSafe() {
        SenderStore(this).setPolicy(address, SenderPolicy.NEVER_ANALYZE)
        SenderStore(this).setCategory(address, Cat.OTHER)
        if (riskyMessageId >= 0) MessageCategoryStore(this).set(riskyMessageId, Cat.OTHER)
        SenderProfileStore(this).recordFeedback(address, false)
        Classifier.invalidateCaches()
        ThreadCache.clear(this)
        binding.cardRisk.visibility = View.GONE
        load()
    }

    private fun blockRiskySender() {
        if (address.isBlank()) return
        RuleStore(this).add(address, RuleTarget.SENDER, false)
        binding.cardRisk.visibility = View.GONE
        Toast.makeText(this, R.string.sender_blocked, Toast.LENGTH_SHORT).show()
    }

    private fun markSelectedSpam() {
        val selected = adapter.selectedMessages()
        val categories = MessageCategoryStore(this)
        selected.forEach {
            categories.set(it.id, Cat.SPAM)
            LearnedWeights(this).record(it.body, true)
            CampaignStore(this).markSpam(it.id)
        }
        SenderProfileStore(this).recordFeedback(address, true)
        Classifier.invalidateCaches()
        ThreadCache.clear(this)
        adapter.clearSelection()
        load()
    }

    private fun confirmDeleteSelected() {
        val selected = adapter.selectedMessages()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.delete_message)
            .setMessage(getString(R.string.confirm_delete_selected, selected.size))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ ->
                adapter.clearSelection()
                worker.execute {
                    selected.forEach { repo.deleteMessage(it.id) }
                    ThreadCache.clear(this)
                    load()
                }
            }
            .show()
    }

    private fun retry(message: SmsMessage) {
        worker.execute {
            val ok = repo.send(address.ifBlank { message.address }, message.body)
            runOnUiThread {
                Toast.makeText(this, if (ok) R.string.retry_started else R.string.send_failed, Toast.LENGTH_SHORT).show()
            }
            load()
        }
    }

    private fun selectedText(): String = adapter.selectedMessages()
        .sortedBy { it.date }
        .joinToString("\n\n") { it.body }

    private fun copySelected() {
        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.app_name), selectedText()))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        adapter.clearSelection()
    }

    private fun shareSelected() {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, selectedText())
        }, getString(R.string.share)))
    }

    private fun forwardSelected() {
        val text = selectedText()
        RecipientPicker(this).show(this) { recipient ->
            worker.execute {
                val ok = repo.send(recipient, text)
                runOnUiThread {
                    Toast.makeText(this, if (ok) R.string.forwarded else R.string.send_failed, Toast.LENGTH_SHORT).show()
                    adapter.clearSelection()
                }
            }
        }
    }

    private fun showSelectedDetails() {
        val message = adapter.selectedMessages().singleOrNull() ?: return
        val state = when (message.delivery) {
            DeliveryState.RECEIVED -> getString(R.string.delivery_received)
            DeliveryState.SENDING -> getString(R.string.delivery_sending)
            DeliveryState.SENT -> getString(R.string.delivery_sent)
            DeliveryState.DELIVERED -> getString(R.string.delivery_delivered)
            DeliveryState.FAILED -> getString(R.string.delivery_failed)
        }
        val details = buildString {
            append(getString(R.string.message_status_line, state))
            append("\n")
            append(getString(R.string.message_date_line, Dates.full(this@ConversationActivity, message.date)))
            if (message.errorCode != 0) {
                append("\n")
                append(getString(R.string.message_error_line, message.errorCode))
            }
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.message_details)
            .setMessage(details)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    @Deprecated("Handled for selection mode")
    override fun onBackPressed() {
        if (::adapter.isInitialized && adapter.selectionCount > 0) adapter.clearSelection()
        else super.onBackPressed()
    }
}
