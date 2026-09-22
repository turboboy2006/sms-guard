package ir.inod.smsguard

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** A local-only vault; saving a message or note never sends an SMS. */
class SavedMessagesActivity : BaseActivity() {
    private lateinit var list: LinearLayout
    private lateinit var composer: EditText
    private var starredOnly = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@SavedMessagesActivity, R.color.screen_bg))
        }
        root.addView(MaterialToolbar(this).apply {
            title = getString(R.string.saved_messages)
            setNavigationIcon(android.R.drawable.ic_media_previous)
            setNavigationOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-1, resources.getDimensionPixelSize(R.dimen.appbar_height)))
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(16)
            setPadding(p, p, p, p)
        }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val composeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setBackgroundColor(ContextCompat.getColor(this@SavedMessagesActivity, R.color.card_bg))
        }
        composer = EditText(this).apply {
            hint = getString(R.string.saved_note_hint)
            minHeight = dp(48)
            maxLines = 4
            textSize = 15f
            background = null
        }
        composeRow.addView(composer, LinearLayout.LayoutParams(0, -2, 1f))
        composeRow.addView(MaterialButton(this).apply {
            text = ""
            contentDescription = getString(R.string.save)
            setIconResource(R.drawable.ic_send)
            setOnClickListener {
                val note = composer.text?.toString().orEmpty().trim()
                if (note.isNotBlank()) {
                    SavedMessageStore(this@SavedMessagesActivity).addNote(note)
                    composer.setText("")
                    starredOnly = false
                    render()
                }
            }
        }, LinearLayout.LayoutParams(dp(52), dp(48)))
        root.addView(composeRow)
        setContentView(root)
        render()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun render() {
        list.removeAllViews()
        list.addView(MaterialButton(this).apply {
            text = if (starredOnly) getString(R.string.saved_messages) else getString(R.string.starred)
            setIconResource(if (starredOnly) R.drawable.ic_archive else R.drawable.ic_star)
            setOnClickListener { starredOnly = !starredOnly; render() }
        })
        val items = SavedMessageStore(this).all().filter { !starredOnly || it.starred }
        if (items.isEmpty()) list.addView(TextView(this).apply {
            text = getString(R.string.no_saved_messages)
            textSize = 16f
            gravity = Gravity.CENTER
            setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_star, 0, 0)
            compoundDrawablePadding = dp(14)
            setPadding(dp(20), dp(64), dp(20), dp(64))
            setTextColor(ContextCompat.getColor(this@SavedMessagesActivity, R.color.text_secondary))
        })
        items.forEach { item ->
            val card = MaterialCardView(this).apply {
                radius = dp(18).toFloat()
                strokeWidth = dp(1)
                strokeColor = ContextCompat.getColor(this@SavedMessagesActivity, R.color.border_soft)
            }
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(10))
            }
            content.addView(TextView(this).apply {
                text = if (item.address == SavedMessageStore.SELF_ADDRESS) getString(R.string.self_note)
                    else ContactNames.displayNameUi(item.address)
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(this@SavedMessagesActivity, R.color.text_primary))
                setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_person, 0, 0, 0)
                compoundDrawablePadding = dp(8)
            })
            content.addView(TextView(this).apply {
                text = Dates.full(this@SavedMessagesActivity, item.date)
                textSize = 12f
                setPadding(0, dp(4), 0, dp(10))
                setTextColor(ContextCompat.getColor(this@SavedMessagesActivity, R.color.text_secondary))
            })
            content.addView(TextView(this).apply {
                text = item.body
                textSize = 16f
                textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = MessageStyler.background(this@SavedMessagesActivity, MessageStyle.FILLED, 16, false)
                setTextColor(ContextCompat.getColor(this@SavedMessagesActivity, R.color.text_primary))
            }, LinearLayout.LayoutParams(-1, -2))
            if (item.note.isNotBlank()) {
                val accent = ThemePrefs(this).accentColor()
                content.addView(TextView(this).apply {
                    text = item.note
                    textSize = 15f
                    textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                    setTextColor(ContextCompat.getColor(this@SavedMessagesActivity, R.color.text_primary))
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(16).toFloat()
                        setColor(ColorUtils.blendARGB(ContextCompat.getColor(this@SavedMessagesActivity, R.color.card_bg), accent, 0.16f))
                    }
                }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8); gravity = Gravity.END })
            }
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(MaterialButton(this).apply {
                text = ""
                contentDescription = getString(R.string.starred)
                setIconResource(R.drawable.ic_star)
                iconTint = android.content.res.ColorStateList.valueOf(
                    if (item.starred) ThemePrefs(this@SavedMessagesActivity).accentColor()
                    else ContextCompat.getColor(this@SavedMessagesActivity, R.color.text_secondary))
                setOnClickListener { SavedMessageStore(this@SavedMessagesActivity).setStarred(item.id, !item.starred); render() }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            actions.addView(MaterialButton(this).apply {
                text = ""
                contentDescription = getString(R.string.note_optional)
                setIconResource(R.drawable.ic_compose)
                setOnClickListener {
                    val input = EditText(this@SavedMessagesActivity).apply { setText(item.note) }
                    MaterialAlertDialogBuilder(this@SavedMessagesActivity)
                        .setTitle(R.string.note_optional).setView(input)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.save) { _, _ ->
                            SavedMessageStore(this@SavedMessagesActivity).updateNote(item.id, input.text.toString())
                            render()
                        }.show()
                }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            actions.addView(MaterialButton(this).apply {
                text = ""
                contentDescription = getString(R.string.delete)
                setIconResource(R.drawable.ic_tab_trash)
                setOnClickListener { SavedMessageStore(this@SavedMessagesActivity).remove(item.id); render() }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            content.addView(actions)
            card.addView(content)
            list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
    }
}
