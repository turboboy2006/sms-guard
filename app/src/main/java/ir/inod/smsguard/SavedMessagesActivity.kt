package ir.inod.smsguard

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.widget.EditText
import android.view.View
import androidx.core.content.ContextCompat

class SavedMessagesActivity : BaseActivity() {
    private lateinit var list: LinearLayout
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p=(16*resources.displayMetrics.density).toInt(); setPadding(p,p,p,p) }
        setContentView(android.widget.ScrollView(this).apply { addView(list) }); render()
    }
    private fun render() {
        list.removeAllViews(); list.addView(TextView(this).apply { text=getString(R.string.saved_messages); textSize=22f })
        val items = SavedMessageStore(this).all()
        if (items.isEmpty()) list.addView(TextView(this).apply { text=getString(R.string.no_saved_messages); textSize=15f })
        items.forEach { item ->
            val gap = (8 * resources.displayMetrics.density).toInt()
            val card = MaterialCardView(this).apply {
                radius = 16f * resources.displayMetrics.density
                strokeWidth = 1
                strokeColor = ContextCompat.getColor(this@SavedMessagesActivity, R.color.border_soft)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap }
            }
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(gap * 2, gap * 2, gap * 2, gap)
            }
            content.addView(TextView(this).apply {
                text = "${ContactNames.displayNameUi(item.address)}  ·  ${Dates.full(this@SavedMessagesActivity, item.date)}"
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@SavedMessagesActivity, R.color.text_secondary))
            })
            content.addView(TextView(this).apply {
                text = item.body
                textSize = 16f
                setPadding(0, gap, 0, gap)
                textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            })
            if (item.note.isNotBlank()) content.addView(TextView(this).apply {
                text = item.note
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@SavedMessagesActivity, R.color.text_secondary))
                setPadding(gap, gap, gap, gap)
                background = ContextCompat.getDrawable(this@SavedMessagesActivity, R.drawable.day_pill)
            })
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(MaterialButton(this).apply {
                text = getString(R.string.note_optional)
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
            })
            actions.addView(MaterialButton(this).apply {
                text = getString(R.string.delete)
                setOnClickListener { SavedMessageStore(this@SavedMessagesActivity).remove(item.id); render() }
            })
            content.addView(actions)
            card.addView(content)
            list.addView(card)
        }
    }
}
