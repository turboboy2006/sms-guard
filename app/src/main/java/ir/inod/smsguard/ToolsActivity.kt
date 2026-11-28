package ir.inod.smsguard

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator

/**
 * ابزارها: به‌روزرسانی، پشتیبان کامل، گزارش خطا.
 * همهٔ کارهای شبکه‌ای در پس‌زمینه و فقط با اقدام کاربر انجام می‌شوند.
 */
class ToolsActivity : BaseActivity() {

    private lateinit var updateButton: MaterialButton
    private lateinit var updateStatus: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var backupStatus: TextView
    private lateinit var crashStatus: TextView
    private lateinit var crashButton: MaterialButton

    private val createBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@registerForActivityResult
        val sections = FullBackup.export(this, uri)
        toast(if (sections != null) getString(R.string.backup_done, sections) else getString(R.string.backup_failed))
        render()
    }

    private val openBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val info = FullBackup.describe(this, uri)
        if (info == null) { toast(getString(R.string.backup_invalid)); return@registerForActivityResult }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.backup_restore)
            .setMessage(getString(R.string.backup_restore_confirm, info))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.backup_restore) { _, _ ->
                val restored = FullBackup.import(this, uri)
                toast(if (restored != null) getString(R.string.backup_restored, restored) else getString(R.string.backup_invalid))
                render()
            }
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@ToolsActivity, R.color.screen_bg))
        }
        root.addView(MaterialToolbar(this).apply {
            title = getString(R.string.tools_title)
            setNavigationIcon(android.R.drawable.ic_media_previous)
            setNavigationOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-1, resources.getDimensionPixelSize(R.dimen.appbar_height)))

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(14)
            setPadding(p, p, p, p)
        }
        body.addView(section(getString(R.string.update_section)))
        updateStatus = TextView(this).apply {
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@ToolsActivity, R.color.text_muted))
            text = getString(R.string.update_current, UpdateChecker.currentVersion(this@ToolsActivity))
        }
        body.addView(updateStatus)
        progress = LinearProgressIndicator(this, null, com.google.android.material.R.attr.linearProgressIndicatorStyle).apply {
            isIndeterminate = false
            max = 100
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(8) }
        }
        body.addView(progress)
        updateButton = MaterialButton(this).apply { setOnClickListener { onUpdateClicked() } }
        body.addView(updateButton, LinearLayout.LayoutParams(-1, dp(46)).also { it.topMargin = dp(10) })

        body.addView(section(getString(R.string.backup_section)))
        backupStatus = TextView(this).apply {
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@ToolsActivity, R.color.text_muted))
            text = getString(R.string.backup_hint)
        }
        body.addView(backupStatus)
        body.addView(row(
            MaterialButton(this).apply {
                setText(R.string.backup_create)
                setOnClickListener {
                    createBackup.launch("payamban-backup-" + System.currentTimeMillis() + ".json")
                }
            },
            MaterialButton(this).apply {
                setText(R.string.backup_restore)
                setOnClickListener { openBackup.launch(arrayOf("application/json", "text/plain", "*/*")) }
            }
        ))

        body.addView(section(getString(R.string.reclassify_section)))
        body.addView(TextView(this).apply {
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@ToolsActivity, R.color.text_muted))
            text = getString(R.string.reclassify_hint)
        })
        val reclassifyButton = MaterialButton(this).apply {
            setText(R.string.reclassify_run)
            setOnClickListener {
                OfflineInboxClassifier.runManually(this@ToolsActivity)
                ThreadCache.clear(this@ToolsActivity)
                toast(getString(R.string.reclassify_started))
            }
        }
        body.addView(reclassifyButton, LinearLayout.LayoutParams(-1, dp(46)).also { it.topMargin = dp(10) })
        val reclassifyStatus = TextView(this).apply {
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@ToolsActivity, R.color.text_muted))
            OfflineInboxClassifier.lastStatus(this@ToolsActivity)?.let {
                text = it
                visibility = android.view.View.VISIBLE
            } ?: run { visibility = android.view.View.GONE }
        }
        body.addView(reclassifyStatus, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(6) })

        body.addView(section(getString(R.string.crash_section)))
        crashStatus = TextView(this).apply {
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@ToolsActivity, R.color.text_muted))
        }
        body.addView(crashStatus)
        crashButton = MaterialButton(this).apply { setOnClickListener { sendCrash() } }
        body.addView(crashButton, LinearLayout.LayoutParams(-1, dp(46)).also { it.topMargin = dp(10) })

        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        render()
        UpdateChecker.checkInBackground(this, force = true, onNewer = { render() }, onDone = { render() })
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun toast(text: String) {
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show()
    }

    private fun section(title: String): TextView = TextView(this).apply {
        text = title
        textSize = 15f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(ContextCompat.getColor(this@ToolsActivity, R.color.text_primary))
        setPadding(0, dp(16), 0, dp(6))
    }

    private fun row(vararg buttons: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(10) }
        buttons.forEachIndexed { index, view ->
            addView(view, LinearLayout.LayoutParams(0, dp(46), 1f).also {
                if (index > 0) it.marginStart = dp(8)
            })
        }
    }

    private fun card(inner: View): MaterialCardView = MaterialCardView(this).apply {
        radius = dp(16).toFloat()
        setCardBackgroundColor(ContextCompat.getColor(this@ToolsActivity, R.color.card_bg))
        layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(10) }
        addView(inner)
    }

    private fun render() {
        val latest = UpdateChecker.cachedLatest(this)
        if (latest != null && UpdateChecker.isNewer(latest.version, UpdateChecker.currentVersion(this))) {
            updateButton.setText(getString(R.string.update_now_instant, latest.version.removePrefix("v")))
            updateButton.setBackgroundColor(ContextCompat.getColor(this, R.color.success))
            updateButton.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            updateStatus.text = getString(R.string.update_available, latest.version.removePrefix("v"))
        } else {
            updateButton.setText(R.string.update_check)
            updateStatus.text = getString(R.string.update_current, UpdateChecker.currentVersion(this))
        }
        val crash = CrashReporter.lastCrash(this)
        if (crash.isNullOrBlank()) {
            crashStatus.text = getString(R.string.crash_none)
            crashButton.isEnabled = false
            crashButton.setText(R.string.crash_send)
        } else {
            crashStatus.text = getString(R.string.crash_ready)
            crashButton.isEnabled = true
            crashButton.setText(getString(R.string.crash_send_to, getString(R.string.support_email)))
        }
    }

    private fun onUpdateClicked() {
        val latest = UpdateChecker.cachedLatest(this)
        if (latest == null || !UpdateChecker.isNewer(latest.version, UpdateChecker.currentVersion(this))) {
            updateButton.isEnabled = false
            updateButton.setText(R.string.update_checking)
            UpdateChecker.checkInBackground(this, force = true, onNewer = {
                updateButton.isEnabled = true
                render()
                promptDownload(it)
            }, onDone = {
                updateButton.isEnabled = true
                render()
            })
            return
        }
        promptDownload(latest)
    }

    private fun promptDownload(info: UpdateChecker.Info) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.update_available, info.version.removePrefix("v")))
            .setMessage(R.string.update_download_question)
            .setNegativeButton(R.string.later) { _, _ -> UpdateChecker.postpone(this) }
            .setPositiveButton(R.string.update_download) { _, _ -> download(info) }
            .show()
    }

    private fun download(info: UpdateChecker.Info) {
        progress.visibility = View.VISIBLE
        progress.progress = 0
        updateButton.isEnabled = false
        UpdateChecker.downloadAndInstall(this, info,
            onProgress = { percent -> progress.progress = percent },
            onError = { message ->
                progress.visibility = View.GONE
                updateButton.isEnabled = true
                toast(getString(R.string.update_failed, message))
            })
    }

    private fun sendCrash() {
        val report = CrashReporter.lastCrash(this) ?: return
        val address = this@ToolsActivity.getString(R.string.support_email)
        val subject = this@ToolsActivity.getString(
            R.string.crash_mail_subject,
            UpdateChecker.currentVersion(this@ToolsActivity)
        )
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:" + address)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, report)
        }
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            toast(getString(R.string.crash_no_mail_app))
        }
    }

    companion object {
        /** وقتی اپ باز است، حداکثر یک بار در روز نسخهٔ جدید را چک می‌کند. */
        fun maybePromptUpdate(activity: android.app.Activity) {
            UpdateChecker.checkInBackground(activity, force = false, onNewer = { info ->
                if (activity.isFinishing || activity.isDestroyed) return@checkInBackground
                MaterialAlertDialogBuilder(activity)
                    .setTitle(activity.getString(R.string.update_available, info.version.removePrefix("v")))
                    .setMessage(R.string.update_download_question)
                    .setNegativeButton(R.string.later) { _, _ -> UpdateChecker.postpone(activity) }
                    .setPositiveButton(R.string.update_now) { _, _ ->
                        activity.applicationContext.startActivity(
                            Intent(activity, ToolsActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                    .show()
            })
        }
    }
}
