package ir.inod.smsguard

import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** The same sender destination is used by the conversation header and inbox avatar. */
class ContactDetailsActivity : BaseActivity() {
    companion object { const val EXTRA_ADDRESS = "address" }
    private lateinit var address: String
    private val sender by lazy { SenderStore(this) }
    private lateinit var panel: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        address = intent.getStringExtra(EXTRA_ADDRESS).orEmpty()
        val scroll = ScrollView(this)
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(20)
            setPadding(pad, dp(12), pad, pad)
        }
        scroll.addView(panel)
        setContentView(scroll)
        draw()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(fa: String, en: String) = if (Dates.isPersian(this)) fa else en

    private fun draw() {
        panel.removeAllViews()
        action(label("بازگشت", "Back"), R.drawable.ic_arrow_up) { finish() }
        val photo = ContactsIndex.photo(this, address)
        panel.addView(ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(92), dp(92)).apply { gravity = Gravity.CENTER_HORIZONTAL }
            if (photo != null) setImageDrawable(BitmapDrawable(resources, photo))
            else setImageResource(R.drawable.ic_person)
            background = AvatarHelper.circle(ThemePrefs(this@ContactDetailsActivity).accentColor())
            setPadding(dp(16), dp(16), dp(16), dp(16))
        })
        panel.addView(TextView(this).apply {
            text = ContactNames.displayNameUi(address)
            textSize = 23f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@ContactDetailsActivity, R.color.text_primary))
            setPadding(0, dp(18), 0, dp(4))
        })
        panel.addView(TextView(this).apply {
            text = address
            textSize = 14f; gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@ContactDetailsActivity, R.color.text_secondary))
            setOnClickListener { openContacts() }
        })
        action(label("شماره در مخاطبین", "View number in Contacts"), R.drawable.ic_person) { openContacts() }
        action(getString(R.string.call_sender), R.drawable.ic_cat_mobile) {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(address)}")))
        }
        action(getString(R.string.search_conversation), R.drawable.ic_search) {
            startActivity(Intent(this, SearchActivity::class.java)
                .putExtra(SearchActivity.EXTRA_INITIAL_QUERY, address))
        }
        action(getString(R.string.sender_reply_sim), R.drawable.ic_send) { chooseSim() }
        action(getString(if (sender.notificationsMuted(address)) R.string.enable_notifications else R.string.mute_notifications),
            R.drawable.ic_tab_service) {
            sender.setNotificationsMuted(address, !sender.notificationsMuted(address)); draw()
        }
        action(label(if (sender.vibrateOnly(address)) "لغو حالت فقط لرزش" else "فقط لرزش",
            if (sender.vibrateOnly(address)) "Turn off vibrate only" else "Vibrate only"),
            R.drawable.ic_notification_vibrate) {
            sender.setVibrateOnly(address, !sender.vibrateOnly(address)); draw()
        }
        action(getString(R.string.change_category), R.drawable.ic_tab_all) {
            val cats = CategoryStore(this).active()
            ChoiceSheet.show(this, getString(R.string.change_category), cats.map {
                ChoiceSheet.Option(it.label(this), (IconCatalog.byId(it.iconId)
                    ?: IconCatalog.forCategory(it.id)).drawable)
            }) { index -> sender.setCategory(address, cats[index].id); Classifier.invalidateCaches();
                ThreadCache.clear(this); draw() }
        }
        action(getString(if (sender.isArchived(address)) R.string.unarchive else R.string.archive), R.drawable.ic_archive) {
            sender.setArchived(address, !sender.isArchived(address)); draw()
        }
        action(getString(R.string.block_sender), R.drawable.ic_cat_security) {
            MaterialAlertDialogBuilder(this).setTitle(R.string.block_sender)
                .setMessage(R.string.confirm_block_msg)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.block_sender) { _, _ ->
                    RuleStore(this).add(address, RuleTarget.SENDER, false)
                    Toast.makeText(this, R.string.sender_blocked, Toast.LENGTH_SHORT).show()
                }.show()
        }
    }

    private fun action(title: String, icon: Int, run: () -> Unit) {
        panel.addView(MaterialButton(this).apply {
            text = title
            setIconResource(icon)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            iconPadding = dp(12)
            setOnClickListener { run() }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
    }

    private fun chooseSim() {
        val labels = mutableListOf(getString(R.string.sim_system_default))
        val ids = mutableListOf(-1)
        try {
            getSystemService(android.telephony.SubscriptionManager::class.java)
                ?.activeSubscriptionInfoList.orEmpty().forEach {
                    ids += it.subscriptionId
                    labels += getString(R.string.sim_label, it.simSlotIndex + 1, it.carrierName?.toString().orEmpty())
                }
        } catch (_: SecurityException) { }
        ChoiceSheet.show(this, getString(R.string.sender_reply_sim), labels.map {
            ChoiceSheet.Option(it, R.drawable.ic_cat_mobile)
        }) { sender.setSim(address, ids[it]); draw() }
    }

    private fun openContacts() {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
        val contactId = runCatching {
            contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)?.use {
                if (it.moveToFirst()) it.getLong(0) else null
            }
        }.getOrNull()
        val destination = contactId?.let {
            android.content.ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, it)
        } ?: ContactsContract.Contacts.CONTENT_URI
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, destination)) }
    }
}

object ContactPreview {
    fun show(activity: android.app.Activity, address: String) {
        val density = activity.resources.displayMetrics.density
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val pad = (18 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val photo = ContactsIndex.photo(activity, address)
        body.addView(ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams((72*density).toInt(), (72*density).toInt())
            if (photo != null) setImageDrawable(BitmapDrawable(activity.resources, photo))
            else setImageResource(R.drawable.ic_person)
        })
        body.addView(TextView(activity).apply {
            text = ContactNames.displayNameUi(address)
            textSize = 19f; gravity = Gravity.CENTER
            setPadding(0, (12*density).toInt(), 0, 0)
        })
        body.addView(TextView(activity).apply { text = address; gravity = Gravity.CENTER })
        MaterialAlertDialogBuilder(activity).setView(body)
            .setPositiveButton(if (Dates.isPersian(activity)) "جزئیات" else "Details") { _, _ ->
                activity.startActivity(Intent(activity, ContactDetailsActivity::class.java)
                    .putExtra(ContactDetailsActivity.EXTRA_ADDRESS, address))
            }
            .setNeutralButton(R.string.call_sender) { _, _ ->
                activity.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(address)}")))
            }
            .setNegativeButton(R.string.close, null).show()
    }
}
