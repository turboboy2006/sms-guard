package ir.inod.smsguard

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar

/** Scheduled messages rendered as a scalable list, with immediate send and undoable cancel. */
class ScheduledMessagesActivity : BaseActivity() {
    private lateinit var adapter: ScheduledAdapter
    private lateinit var empty: TextView
    private val store by lazy { ScheduledSmsStore(this) }
    private val repo by lazy { SmsRepository(this) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(ContextCompat.getColor(this@ScheduledMessagesActivity,R.color.screen_bg))}
        root.addView(MaterialToolbar(this).apply{title=getString(R.string.scheduled_messages);setNavigationIcon(R.drawable.ic_chevron);setNavigationOnClickListener{finish()}},LinearLayout.LayoutParams(-1,dp(64)))
        val frame=FrameLayout(this); adapter=ScheduledAdapter(::sendNow,::cancel)
        frame.addView(RecyclerView(this).apply{layoutManager=LinearLayoutManager(this@ScheduledMessagesActivity);adapter=this@ScheduledMessagesActivity.adapter;clipToPadding=false;setPadding(dp(12),dp(8),dp(12),dp(20))},FrameLayout.LayoutParams(-1,-1))
        empty=TextView(this).apply{gravity=Gravity.CENTER;textSize=16f;setCompoundDrawablesWithIntrinsicBounds(0,R.drawable.ic_calendar,0,0);compoundDrawablePadding=dp(14);setPadding(dp(24),dp(60),dp(24),dp(60))};frame.addView(empty,FrameLayout.LayoutParams(-1,-2,Gravity.CENTER));root.addView(frame,LinearLayout.LayoutParams(-1,0,1f));setContentView(root);render()
    }
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun render(){val data=store.all();adapter.submit(data);empty.text=getString(R.string.no_scheduled);empty.visibility=if(data.isEmpty())View.VISIBLE else View.GONE}
    private fun sendNow(item:ScheduledSms){store.remove(item.id);Thread{val ok=repo.send(item.address,item.body);runOnUiThread{render();Snackbar.make(empty,if(ok)R.string.forwarded else R.string.send_failed,Snackbar.LENGTH_LONG).show()}}.start()}
    private fun cancel(item:ScheduledSms){store.remove(item.id);render();Snackbar.make(empty,R.string.applied,Snackbar.LENGTH_LONG).setAction(R.string.undo){store.schedule(item.address,item.body,item.at);render()}.show()}
    private class ScheduledAdapter(val onSend:(ScheduledSms)->Unit,val onCancel:(ScheduledSms)->Unit):RecyclerView.Adapter<ScheduledAdapter.H>(){
        private val items=mutableListOf<ScheduledSms>();class H(val c:MaterialCardView,val title:TextView,val whenText:TextView,val body:TextView,val send:MaterialButton,val cancel:MaterialButton):RecyclerView.ViewHolder(c)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): H {
            val context = parent.context
            val density = context.resources.displayMetrics.density
            fun dp(value: Int) = (value * density).toInt()
            val card = MaterialCardView(context).apply {
                radius = dp(18).toFloat(); strokeWidth = dp(1)
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
            }
            val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(10), dp(8)) }
            val title = TextView(context).apply { textSize = 16f; setTypeface(null, 1) }
            val whenText = TextView(context).apply { textSize = 13f; setTextColor(ContextCompat.getColor(context, R.color.text_secondary)) }
            val body = TextView(context).apply { textSize = 15f; setPadding(dp(10), dp(8), dp(10), dp(8)); textDirection = View.TEXT_DIRECTION_FIRST_STRONG }
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
