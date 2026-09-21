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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
        private const val MENU_TRASH_THREAD = 1010
        private const val MENU_DELETE_THREAD = 1011
        private const val MENU_SEARCH_THREAD = 1012
        private const val MENU_ADD_RECIPIENT = 1013
        private const val MENU_SEND_WITH_SIM = 1014
        private const val MENU_SAVED = 1015
    }

    private lateinit var binding: ActivityConversationBinding
    private lateinit var adapter: MessageAdapter
    private val theme by lazy { ThemePrefs(this) }

    private val repo by lazy { SmsRepository(this) }
    private var threadId: Long = -1L
    private var address: String = ""
    private var riskyMessageId: Long = -1L
    private var messageLimit = 500
    private var loadingMessages = false
    private var lastMessages: List<SmsMessage> = emptyList()
    private val additionalRecipients = linkedSetOf<String>()
    private val drafts by lazy { getSharedPreferences("conversation_drafts", MODE_PRIVATE) }
    private val deliveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { load() }
    }
    private val ringtonePicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val uri: android.net.Uri? = if (android.os.Build.VERSION.SDK_INT >= 33) {
            result.data?.getParcelableExtra(
                android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI,
                android.net.Uri::class.java
            )
        } else {
            @Suppress("DEPRECATION")
            result.data?.getParcelableExtra(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        }
        SenderStore(this).setNotificationSound(address, uri?.toString())
        Notifier(this).resetSenderChannel(address)
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
    }
    private val chatPhotoPicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        SenderStore(this).setBackgroundImage(address, uri.toString())
        SenderStore(this).setBackgroundPreset(address, null)
        applyAppearance()
    }

    /** What the current list was drawn with, so a change can be detected. */
    private var drawnLayout: MessageLayout? = null
    private var pinchBaseScale = 1f
    private var pinching = false
    private var pinchScale = 1f

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
            context = this,
            onLongClick = { message -> showMessageOptions(message) },
            onClick = { message ->
                if (adapter.selectionCount > 0) adapter.toggleSelection(message)
            },
            onRetry = { message -> retry(message) },
            onSelectionChanged = { count -> updateSelectionUi(count) }
        )
        binding.recyclerMessages.layoutManager =
            LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.recyclerMessages.adapter = adapter
        val scaleDetector = android.view.ScaleGestureDetector(this,
            object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: android.view.ScaleGestureDetector): Boolean {
                    pinchBaseScale = adapter.currentLayout().fontScale
                    pinchScale = pinchBaseScale
                    pinching = true
                    return true
                }
                override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                    pinchScale = (pinchScale * detector.scaleFactor).coerceIn(0.85f, 1.5f)
                    if (kotlin.math.abs(adapter.currentLayout().fontScale - pinchScale) >= 0.02f)
                        adapter.setFontZoom(pinchScale)
                    return true
                }
                override fun onScaleEnd(detector: android.view.ScaleGestureDetector) {
                    pinching = false
                    val scale = adapter.currentLayout().fontScale
                    theme.messageFontScale = scale
                    theme.listFontScale = scale
                    theme.touch()
                    Toast.makeText(this@ConversationActivity, getString(R.string.text_size_saved), Toast.LENGTH_SHORT).show()
                }
            })
        binding.recyclerMessages.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            pinching
        }
        binding.recyclerMessages.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                // A short conversation keeps its risk hint at the top. On a
                // scrollable conversation it leaves the viewport with older SMS.
                if (recyclerView.scrollState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_DRAGGING &&
                    !SenderStore(this@ConversationActivity).bannerDismissed(address) && riskyMessageId >= 0) {
                    val scrollable = recyclerView.canScrollVertically(-1) || recyclerView.canScrollVertically(1)
                    binding.cardRisk.visibility = if (!scrollable || lm.findFirstVisibleItemPosition() <= 1)
                        View.VISIBLE else View.GONE
                }
                if (lm.findFirstVisibleItemPosition() <= 2 && !loadingMessages && lastMessages.size >= messageLimit) {
                    val oldCount = adapter.itemCount
                    messageLimit += 500
                    load(scrollToEnd = false, preserveFromEnd = oldCount)
                }
            }
        })

        binding.buttonSend.setOnClickListener { sendCurrent() }
        binding.buttonSend.setOnLongClickListener { scheduleCurrent(); true }
        binding.buttonPickRecipient.setOnClickListener { pickRecipient() }
        binding.buttonNotSpam.setOnClickListener { markConversationSafe() }
        binding.buttonRiskBlock.setOnClickListener { showSenderMenu() }
        binding.buttonRiskCall.setOnClickListener {
            startActivity(Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:${android.net.Uri.encode(address)}")))
        }
        binding.textRisk.setOnClickListener { showRiskDetails() }
        binding.toolbar.setOnClickListener { if (address.isNotBlank()) showSenderMenu() }
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
        val sender = SenderStore(this)
        val senderStyle = sender.backgroundFor(address)
        val senderImage = sender.backgroundImageFor(address)
        val senderPreset = sender.backgroundPresetFor(address)
        BackgroundRenderer.apply(binding.root, this, senderStyle ?: theme.backgroundStyle,
            senderImage ?: if (senderStyle == null && senderPreset == null) theme.backgroundImageUri else null,
            senderPreset ?: if (senderStyle == null && senderImage == null) theme.backgroundPreset else null)
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
    private fun load(scrollToEnd: Boolean = true, preserveFromEnd: Int = 0) {
        if (loadingMessages) return
        loadingMessages = true
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
                if (threadId >= 0) repo.loadMessages(threadId, messageLimit) else emptyList()
            } catch (t: Throwable) {
                emptyList()
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                loadingMessages = false
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
                lastMessages = messages
                bindRiskBanner(messages)
                // The adapter also emits day dividers, so scroll to its own
                // last row rather than to messages.size.
                if (scrollToEnd && adapter.itemCount > 0) {
                    binding.recyclerMessages.scrollToPosition(adapter.itemCount - 1)
                } else if (preserveFromEnd > 0) {
                    val added = (adapter.itemCount - preserveFromEnd).coerceAtLeast(0)
                    (binding.recyclerMessages.layoutManager as? LinearLayoutManager)
                        ?.scrollToPositionWithOffset(added + 2, 0)
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
            val targets = listOf(address) + additionalRecipients
            val sentCount = try {
                targets.count { repo.send(it, text) }
            } catch (t: Throwable) {
                0
            }
            if (sentCount != targets.size) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        Toast.makeText(this, R.string.send_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            } else if (targets.size > 1) runOnUiThread {
                Toast.makeText(this, getString(R.string.group_sent_report, sentCount, targets.size), Toast.LENGTH_SHORT).show()
            }
            if (sentCount > 0) SenderProfileStore(this).recordFeedback(address, false)
            load()
        }
    }

    private fun scheduleCurrent() {
        val text = binding.editMessage.text?.toString()?.trim().orEmpty()
        if (text.isBlank() || address.isBlank()) return
        val calendar = java.util.Calendar.getInstance().apply { add(java.util.Calendar.HOUR_OF_DAY, 1) }
        android.app.DatePickerDialog(this, { _, year, month, day ->
            android.app.TimePickerDialog(this, { _, hour, minute ->
                calendar.set(year, month, day, hour, minute, 0)
                val targets = listOf(address) + additionalRecipients
                val ok = targets.all { ScheduledSmsStore(this).schedule(it, text, calendar.timeInMillis) }
                if (ok) { binding.editMessage.setText(""); drafts.edit().remove(address).apply() }
                Toast.makeText(this, if (ok) R.string.message_scheduled else R.string.send_failed, Toast.LENGTH_SHORT).show()
            }, calendar.get(java.util.Calendar.HOUR_OF_DAY), calendar.get(java.util.Calendar.MINUTE), true).show()
        }, calendar.get(java.util.Calendar.YEAR), calendar.get(java.util.Calendar.MONTH), calendar.get(java.util.Calendar.DAY_OF_MONTH)).show()
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
            .setIcon(R.drawable.ic_tab_spam)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, MENU_DELETE_SELECTED, 1, R.string.delete)
            .setIcon(R.drawable.ic_tab_trash)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, MENU_COPY_SELECTED, 2, R.string.copy)
        menu.add(0, MENU_SHARE_SELECTED, 3, R.string.share)
        menu.add(0, MENU_SELECT_ALL, 4, R.string.select_all)
        menu.add(0, MENU_FORWARD_SELECTED, 5, R.string.forward)
        menu.add(0, MENU_DETAILS, 6, R.string.message_details)
        menu.add(0, MENU_TRASH_THREAD, 7, R.string.move_to_trash)
            .setIcon(R.drawable.ic_tab_trash)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, MENU_DELETE_THREAD, 8, R.string.delete_forever)
        menu.add(0, MENU_SEARCH_THREAD, 9, R.string.search_conversation)
        menu.add(0, MENU_ADD_RECIPIENT, 10, R.string.add_recipient)
        menu.add(0, MENU_SEND_WITH_SIM, 11, R.string.send_with_sim)
        menu.add(0, MENU_SAVED, 12, R.string.saved_messages)
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
        menu.findItem(MENU_TRASH_THREAD)?.isVisible = !selecting && threadId >= 0
        menu.findItem(MENU_DELETE_THREAD)?.isVisible = !selecting && threadId >= 0
        menu.findItem(MENU_SEARCH_THREAD)?.isVisible = !selecting && threadId >= 0
        menu.findItem(MENU_ADD_RECIPIENT)?.isVisible = !selecting && address.isNotBlank()
        menu.findItem(MENU_SEND_WITH_SIM)?.isVisible = !selecting && address.isNotBlank()
        menu.findItem(MENU_SAVED)?.isVisible = !selecting
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
            MENU_TRASH_THREAD -> { moveConversationToTrash(); return true }
            MENU_DELETE_THREAD -> { confirmDeleteConversation(); return true }
            MENU_SEARCH_THREAD -> { searchConversation(); return true }
            MENU_ADD_RECIPIENT -> { addGroupRecipient(); return true }
            MENU_SEND_WITH_SIM -> { chooseSimForCurrentSend(); return true }
            MENU_SAVED -> { startActivity(Intent(this, SavedMessagesActivity::class.java)); return true }
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
        val hidden = SenderStore(this).bannerDismissed(address)
        binding.cardRisk.visibility = if (risky == null || hidden) View.GONE else View.VISIBLE
        if (risky != null) {
            binding.textRisk.text = Classifier.riskLabel(this, address, risky.body)
                ?: getString(R.string.risk_banner_default)
        }
    }

    private fun showRiskDetails() {
        val message = lastMessages.lastOrNull { it.id == riskyMessageId } ?: return
        val verdict = Classifier.classifyLocal(this, address, message.body)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.risk_score, verdict.score, verdict.confidence))
            .setMessage(verdict.reasons.joinToString("\n") { "• ${riskReasonLabel(it)}" })
            .setPositiveButton(android.R.string.ok, null).show()
    }

    private fun riskReasonLabel(reason: String): String = when (reason) {
        "link", "ip-link", "shortener", "risky-tld", "punycode" -> getString(R.string.risk_link)
        "callback-number" -> getString(R.string.risk_callback)
        "campaign" -> getString(R.string.risk_campaign)
        "money", "card-number", "sheba" -> getString(R.string.risk_bank_details)
        "fraud-words" -> getString(R.string.risk_fraud)
        "brand-impersonation", "brand-mismatch" -> getString(R.string.risk_brand)
        "urgency", "late-night" -> getString(R.string.risk_urgency)
        else -> reason
    }

    private fun markConversationSafe() {
        SenderStore(this).setPolicy(address, SenderPolicy.NEVER_ANALYZE)
        SenderStore(this).setCategory(address, Cat.OTHER)
        adapter.allMessages().filter { it.isIncoming }.forEach {
            MessageCategoryStore(this).set(it.id, Cat.OTHER)
        }
        SenderStore(this).setBannerDismissed(address)
        SenderProfileStore(this).recordFeedback(address, false)
        Classifier.invalidateCaches()
        ThreadCache.clear(this)
        binding.cardRisk.visibility = View.GONE
        load()
    }

    private fun blockRiskySender() {
        if (address.isBlank()) return
        RuleStore(this).add(address, RuleTarget.SENDER, false)
        SenderStore(this).setBannerDismissed(address)
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
                    selected.filter { it.isIncoming && (it.categoryId == Cat.SUSPICIOUS || it.categoryId == Cat.SPAM || it.categoryId == Cat.PROMOTION) }
                        .forEach { LearnedWeights(this).record(it.body, true) }
                    if (selected.any { it.isIncoming && (it.categoryId == Cat.SUSPICIOUS || it.categoryId == Cat.SPAM) }) {
                        SenderProfileStore(this).recordFeedback(address, true)
                    }
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
            if (message.subscriptionId >= 0) {
                append("\n")
                append(getString(R.string.message_sim_line, simLabel(message.subscriptionId)))
            }
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.message_details)
            .setMessage(details)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun simLabel(subscriptionId: Int): String {
        return try {
            val info = getSystemService(android.telephony.SubscriptionManager::class.java)
                ?.activeSubscriptionInfoList.orEmpty().firstOrNull { it.subscriptionId == subscriptionId }
            if (info == null) getString(R.string.sim_unknown)
            else getString(R.string.sim_label, info.simSlotIndex + 1, info.carrierName?.toString().orEmpty())
        } catch (_: SecurityException) { getString(R.string.sim_unknown) }
    }

    private fun showMessageOptions(message: SmsMessage) {
        val options = mutableListOf(
            getString(R.string.select_message),
            getString(R.string.copy),
            getString(R.string.forward),
            getString(R.string.save_message),
            getString(R.string.message_details),
            getString(R.string.delete_message)
        )
        if (!message.isIncoming && message.delivery == DeliveryState.FAILED) {
            options.add(0, getString(R.string.retry_started))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(Dates.full(this, message.date))
            .setItems(options.toTypedArray()) { _, index ->
                val selected = options[index]
                when (selected) {
                    getString(R.string.retry_started) -> retry(message)
                    getString(R.string.select_message) -> adapter.toggleSelection(message)
                    getString(R.string.copy) -> {
                        getSystemService(android.content.ClipboardManager::class.java)
                            .setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.app_name), message.body))
                        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
                    }
                    getString(R.string.forward) -> {
                        RecipientPicker(this).show(this) { recipient ->
                            worker.execute { repo.send(recipient, message.body) }
                        }
                    }
                    getString(R.string.save_message) -> saveMessage(message)
                    getString(R.string.message_details) -> {
                        adapter.clearSelection()
                        adapter.toggleSelection(message)
                        showSelectedDetails()
                    }
                    getString(R.string.delete_message) -> confirmDeleteMessage(message)
                }
            }.show()
    }

    private fun saveMessage(message: SmsMessage) {
        val note = android.widget.EditText(this).apply { hint = getString(R.string.note_optional) }
        MaterialAlertDialogBuilder(this).setTitle(R.string.save_message).setView(note)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                SavedMessageStore(this).save(message, note.text?.toString().orEmpty().trim())
                Toast.makeText(this, R.string.message_saved, Toast.LENGTH_SHORT).show()
            }.show()
    }

    /** Sender controls live behind the tappable conversation title. */
    private fun showSenderMenu() {
        worker.execute {
            val count = if (threadId >= 0) repo.messageCount(threadId) else 0
            runOnUiThread { if (!isFinishing && !isDestroyed) showSenderMenuNow(count) }
        }
    }

    private fun showSenderMenuNow(messageCount: Int) {
        val store = SenderStore(this)
        val muted = store.notificationsMuted(address)
        val options = arrayOf(
            getString(R.string.call_sender),
            getString(if (muted) R.string.enable_notifications else R.string.mute_notifications),
            getString(R.string.notification_sound),
            getString(R.string.chat_background),
            getString(R.string.sender_reply_sim),
            getString(R.string.change_category),
            getString(R.string.block_sender)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.sender_with_count, ContactNames.displayNameUi(address), messageCount))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        SenderProfileStore(this).recordFeedback(address, false)
                        startActivity(Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:${android.net.Uri.encode(address)}")))
                    }
                    1 -> {
                        store.setNotificationsMuted(address, !muted)
                        Toast.makeText(this, if (muted) R.string.notifications_enabled else R.string.notifications_muted, Toast.LENGTH_SHORT).show()
                    }
                    2 -> pickNotificationSound()
                    3 -> pickChatBackground()
                    4 -> pickSenderSim()
                    5 -> pickSenderCategory()
                    6 -> blockRiskySender()
                }
            }.show()
    }

    private fun pickChatBackground() {
        val gradients = listOf(
            R.string.background_clean, R.string.background_mist, R.string.background_aurora,
            R.string.background_dusk, R.string.background_bloom
        ).map { getString(it) }
        val pictures = listOf(
            R.string.wallpaper_mountains, R.string.wallpaper_eucalyptus, R.string.wallpaper_lake,
            R.string.wallpaper_desert, R.string.wallpaper_lavender, R.string.wallpaper_rain,
            R.string.wallpaper_ocean, R.string.wallpaper_pastel, R.string.wallpaper_neon,
            R.string.wallpaper_coral
        ).map { getString(it) }
        val labels = (gradients + pictures + listOf(
            getString(R.string.background_photo), getString(R.string.background_follow_global),
            getString(R.string.background_remove_photo)
        )).toTypedArray()
        MaterialAlertDialogBuilder(this).setTitle(R.string.chat_background)
            .setItems(labels) { _, which ->
                when (which) {
                    in BackgroundStyle.IDS.indices -> {
                        SenderStore(this).setBackground(address, BackgroundStyle.IDS[which])
                        SenderStore(this).setBackgroundImage(address, null)
                        SenderStore(this).setBackgroundPreset(address, null)
                        applyAppearance()
                    }
                    in BackgroundStyle.IDS.size until BackgroundStyle.IDS.size + BuiltInWallpaper.IDS.size -> {
                        SenderStore(this).setBackground(address, null)
                        SenderStore(this).setBackgroundImage(address, null)
                        SenderStore(this).setBackgroundPreset(address, BuiltInWallpaper.IDS[which - BackgroundStyle.IDS.size])
                        applyAppearance()
                    }
                    BackgroundStyle.IDS.size + BuiltInWallpaper.IDS.size -> chatPhotoPicker.launch(arrayOf("image/*"))
                    BackgroundStyle.IDS.size + BuiltInWallpaper.IDS.size + 1 -> {
                        SenderStore(this).setBackground(address, null); SenderStore(this).setBackgroundImage(address, null)
                        SenderStore(this).setBackgroundPreset(address, null); applyAppearance()
                    }
                    else -> { SenderStore(this).setBackgroundImage(address, null); SenderStore(this).setBackgroundPreset(address, null); applyAppearance() }
                }
            }.show()
    }

    private fun pickNotificationSound() {
        ringtonePicker.launch(Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_NOTIFICATION)
            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
        })
    }

    private fun pickSenderSim() {
        val labels = mutableListOf(getString(R.string.sim_system_default))
        val ids = mutableListOf(-1)
        try {
            getSystemService(android.telephony.SubscriptionManager::class.java)
                ?.activeSubscriptionInfoList.orEmpty().forEach { info ->
                    ids += info.subscriptionId
                    labels += getString(R.string.sim_label, info.simSlotIndex + 1, info.carrierName?.toString().orEmpty())
                }
        } catch (_: SecurityException) { }
        MaterialAlertDialogBuilder(this).setTitle(R.string.sender_reply_sim)
            .setItems(labels.toTypedArray()) { _, which ->
                SenderStore(this).setSim(address, ids[which])
                Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun pickSenderCategory() {
        val categories = CategoryStore(this).all()
        MaterialAlertDialogBuilder(this).setTitle(R.string.change_category)
            .setItems(categories.map { it.label(this) }.toTypedArray()) { _, which ->
                SenderStore(this).setCategory(address, categories[which].id)
                SenderStore(this).setBannerDismissed(address)
                Classifier.invalidateCaches()
                ThreadCache.clear(this)
                load()
            }.show()
    }

    private fun moveConversationToTrash() {
        SenderStore(this).setCategory(address, Cat.TRASH)
        Classifier.invalidateCaches()
        ThreadCache.clear(this)
        Toast.makeText(this, R.string.moved_to_trash, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun confirmDeleteConversation() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.delete_forever)
            .setMessage(R.string.confirm_delete_conversation)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ ->
                worker.execute {
                    val deleted = repo.deleteThread(threadId)
                    if (deleted) ThreadCache.clear(this)
                    runOnUiThread { if (deleted) finish() else Toast.makeText(this, R.string.send_failed, Toast.LENGTH_SHORT).show() }
                }
            }.show()
    }

    private fun searchConversation() {
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.search_hint)
            setSingleLine(true)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.search_conversation)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.search) { _, _ ->
                val needle = input.text?.toString()?.trim().orEmpty()
                val match = lastMessages.lastOrNull { it.body.contains(needle, ignoreCase = true) }
                val position = match?.let { adapter.positionOf(it.id) } ?: -1
                if (position >= 0) binding.recyclerMessages.smoothScrollToPosition(position)
                else Toast.makeText(this, R.string.search_no_results, Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun addGroupRecipient() {
        RecipientPicker(this).show(this) { recipient ->
            if (recipient != address) additionalRecipients += recipient
            supportActionBar?.subtitle = if (additionalRecipients.isEmpty()) null
            else getString(R.string.recipient_count, additionalRecipients.size + 1)
        }
    }

    private fun chooseSimForCurrentSend() {
        val text = binding.editMessage.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) { Toast.makeText(this, R.string.message_hint, Toast.LENGTH_SHORT).show(); return }
        val labels = mutableListOf<String>(); val ids = mutableListOf<Int>()
        try {
            getSystemService(android.telephony.SubscriptionManager::class.java)
                ?.activeSubscriptionInfoList.orEmpty().forEach { info ->
                    ids += info.subscriptionId
                    labels += getString(R.string.sim_label, info.simSlotIndex + 1, info.carrierName?.toString().orEmpty())
                }
        } catch (_: SecurityException) { }
        if (ids.isEmpty()) { sendCurrent(); return }
        MaterialAlertDialogBuilder(this).setTitle(R.string.send_with_sim)
            .setItems(labels.toTypedArray()) { _, which ->
                binding.editMessage.setText(""); drafts.edit().remove(address).apply()
                worker.execute {
                    val targets = listOf(address) + additionalRecipients
                    val sent = targets.count { repo.send(it, text, ids[which]) }
                    runOnUiThread { Toast.makeText(this, getString(R.string.group_sent_report, sent, targets.size), Toast.LENGTH_SHORT).show() }
                    load()
                }
            }.show()
    }

    @Deprecated("Handled for selection mode")
    override fun onBackPressed() {
        if (::adapter.isInitialized && adapter.selectionCount > 0) adapter.clearSelection()
        else super.onBackPressed()
    }
}
