package ir.inod.smsguard

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton

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
            list.addView(TextView(this).apply { text="${ContactNames.displayNameUi(item.address)}\n${item.body}\n${item.note}"; textSize=15f; setPadding(0,20,0,2) })
            list.addView(MaterialButton(this).apply { text=getString(R.string.delete); setOnClickListener { SavedMessageStore(this@SavedMessagesActivity).remove(item.id); render() } })
        }
    }
}
