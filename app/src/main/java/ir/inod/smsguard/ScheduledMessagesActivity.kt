package ir.inod.smsguard

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton

class ScheduledMessagesActivity : BaseActivity() {
    private lateinit var list: LinearLayout
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p=(16*resources.displayMetrics.density).toInt(); setPadding(p,p,p,p) }
        setContentView(android.widget.ScrollView(this).apply { addView(list) }); render()
    }
    private fun render() {
        list.removeAllViews()
        list.addView(TextView(this).apply { text=getString(R.string.scheduled_messages); textSize=22f })
        val items = ScheduledSmsStore(this).all()
        if (items.isEmpty()) list.addView(TextView(this).apply { text=getString(R.string.no_scheduled); textSize=15f })
        items.forEach { item ->
            list.addView(TextView(this).apply { text="${ContactNames.displayNameUi(item.address)}\n${item.body}\n${Dates.full(this@ScheduledMessagesActivity,item.at)}"; textSize=15f; setPadding(0,24,0,4) })
            list.addView(MaterialButton(this).apply { text=getString(R.string.cancel_scheduled); setOnClickListener { ScheduledSmsStore(this@ScheduledMessagesActivity).remove(item.id); render() } })
        }
    }
}
