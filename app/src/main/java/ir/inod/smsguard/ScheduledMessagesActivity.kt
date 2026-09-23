package ir.inod.smsguard

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.EditText
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Calendar

/** Scheduled messages rendered as a scalable list, with immediate send and undoable cancel. */
class ScheduledMessagesActivity : BaseActivity() {
    private lateinit var adapter: ScheduledAdapter
    private lateinit var empty: TextView
    private val store by lazy { ScheduledSmsStore(this) }
    private val repo by lazy { SmsRepository(this) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(ContextCompat.getColor(this@ScheduledMessagesActivity,R.color.screen_bg))}
        root.addView(SecondaryUi.toolbar(this, getString(R.string.scheduled_messages)) { finish() },
            LinearLayout.LayoutParams(-1, SecondaryUi.px(this, R.dimen.appbar_height)))
        val frame=FrameLayout(this); adapter=ScheduledAdapter(::sendNow,::cancel)
        frame.addView(RecyclerView(this).apply{layoutManager=LinearLayoutManager(this@ScheduledMessagesActivity);adapter=this@ScheduledMessagesActivity.adapter;clipToPadding=false;setPadding(dp(12),dp(8),dp(12),dp(20))},FrameLayout.LayoutParams(-1,-1))
        empty=SecondaryUi.empty(this,R.drawable.ic_calendar);frame.addView(empty,FrameLayout.LayoutParams(-1,-2,Gravity.CENTER));root.addView(frame,LinearLayout.LayoutParams(-1,0,1f))
        root.addView(MaterialButton(this).apply {
            text = if (Dates.isPersian(this@ScheduledMessagesActivity)) "پیام زمان‌دار جدید" else "New scheduled message"
            setIconResource(R.drawable.ic_calendar)
            setOnClickListener { createScheduled() }
        }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(4), dp(16), dp(16)) })
        setContentView(root);render()
    }
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun render(){val data=store.all();adapter.submit(data);empty.text=getString(R.string.no_scheduled);empty.visibility=if(data.isEmpty())View.VISIBLE else View.GONE}
    private fun sendNow(item:ScheduledSms){store.remove(item.id);Thread{val ok=repo.send(item.address,item.body);runOnUiThread{render();Snackbar.make(empty,if(ok)R.string.forwarded else R.string.send_failed,Snackbar.LENGTH_LONG).show()}}.start()}
    private fun cancel(item:ScheduledSms){store.remove(item.id);render();Snackbar.make(empty,R.string.applied,Snackbar.LENGTH_LONG).setAction(R.string.undo){store.schedule(item.address,item.body,item.at);render()}.show()}
    private fun createScheduled() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val recipient = EditText(this).apply {
            hint = if (Dates.isPersian(this@ScheduledMessagesActivity)) "شماره گیرنده" else "Recipient number"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }
        val message = EditText(this).apply {
            hint = if (Dates.isPersian(this@ScheduledMessagesActivity)) "متن پیام" else "Message"
            minLines = 3
        }
        val whenButton = MaterialButton(this).apply {
            setIconResource(R.drawable.ic_calendar)
        }
        val date = Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, 1) }
        fun updateLabel() { whenButton.text = Dates.full(this, date.timeInMillis) }
        updateLabel()
        whenButton.setOnClickListener {
            android.app.DatePickerDialog(this, { _, year, month, day ->
                date.set(year, month, day)
                android.app.TimePickerDialog(this, { _, hour, minute ->
                    date.set(Calendar.HOUR_OF_DAY, hour)
                    date.set(Calendar.MINUTE, minute)
                    date.set(Calendar.SECOND, 0)
                    updateLabel()
                }, date.get(Calendar.HOUR_OF_DAY), date.get(Calendar.MINUTE), true).show()
            }, date.get(Calendar.YEAR), date.get(Calendar.MONTH), date.get(Calendar.DAY_OF_MONTH)).show()
        }
        box.addView(recipient); box.addView(message); box.addView(whenButton)
        val dialog = MaterialAlertDialogBuilder(this).setTitle(
            if (Dates.isPersian(this)) "پیام زمان‌دار جدید" else "New scheduled message")
            .setView(box).setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.save, null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val number = recipient.text.toString().trim()
                val body = message.text.toString().trim()
                if (number.isBlank() || body.isBlank() || date.timeInMillis <= System.currentTimeMillis()) {
                    Toast.makeText(this, if (Dates.isPersian(this)) "شماره، متن و زمان آینده را وارد کنید" else "Enter a recipient, message and future time", Toast.LENGTH_SHORT).show()
                } else {
                    if (store.schedule(number, body, date.timeInMillis)) {
                        render(); dialog.dismiss()
                    } else Toast.makeText(this, R.string.send_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialog.show()
    }
    private class ScheduledAdapter(val onSend:(ScheduledSms)->Unit,val onCancel:(ScheduledSms)->Unit):RecyclerView.Adapter<ScheduledAdapter.H>(){
        private val items=mutableListOf<ScheduledSms>();class H(val c:MaterialCardView,val title:TextView,val whenText:TextView,val body:TextView,val send:MaterialButton,val cancel:MaterialButton):RecyclerView.ViewHolder(c)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): H {
            val context = parent.context
            val density = context.resources.displayMetrics.density
            fun dp(value: Int) = (value * density).toInt()
            val card = SecondaryUi.listCard(context)
            val box = SecondaryUi.cardContent(context)
            val title = TextView(context).apply { setTextAppearance(R.style.TextAppearance_SmsGuard_Title) }
            val whenText = TextView(context).apply { setTextAppearance(R.style.TextAppearance_SmsGuard_Label) }
            val body = TextView(context).apply { setTextAppearance(R.style.TextAppearance_SmsGuard_Body); setPadding(dp(8), dp(8), dp(8), dp(8)); textDirection = View.TEXT_DIRECTION_FIRST_STRONG }
            val actions = LinearLayout(context)
            val send = MaterialButton(context).apply { text = ""; setIconResource(R.drawable.ic_send); contentDescription = context.getString(R.string.send) }
            val cancel = MaterialButton(context).apply { text = ""; setIconResource(R.drawable.ic_tab_trash); contentDescription = context.getString(R.string.cancel_scheduled) }
            actions.addView(send); actions.addView(cancel)
            box.addView(title); box.addView(whenText); box.addView(body); box.addView(actions)
            card.addView(box)
            return H(card, title, whenText, body, send, cancel)
        }
        override fun getItemCount()=items.size;override fun onBindViewHolder(h:H,p:Int){val i=items[p];h.title.text=ContactNames.displayNameUi(i.address);h.whenText.text=Dates.full(h.c.context,i.at);h.body.text=i.body;h.send.setOnClickListener{onSend(i)};h.cancel.setOnClickListener{onCancel(i)}};fun submit(next:List<ScheduledSms>){items.clear();items.addAll(next);notifyDataSetChanged()}
    }
}
