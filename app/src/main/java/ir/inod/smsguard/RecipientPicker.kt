package ir.inod.smsguard

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * Who should this new message go to?
 *
 * The compose button used to open an empty conversation, which is a dead end:
 * an SMS needs a recipient before there is anything to show. This sheet lists
 * the address book and, below it, the conversations that already exist, and
 * typing filters both.
 *
 * The list is built in code rather than as layouts because it is a list of one
 * consistent row shape used in exactly one place.
 */
class RecipientPicker(context: Context) {

    private val appContext = context.applicationContext

    /** Used to hand the recent-conversation list back to the UI thread. */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** One selectable row: either a stored contact or a recent conversation. */
    private class Target(
        val address: String,
        val label: String,
        val isContact: Boolean
    )

    fun show(activity: Context, onPicked: (String) -> Unit) {
        val density = activity.resources.displayMetrics.density
        val sheet = BottomSheetDialog(activity)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, padding(density, 8), 0, 0)
        }

        root.addView(
            TextView(activity).apply {
                text = activity.getString(R.string.pick_recipient)
                setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
                setTypeface(null, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setPadding(padding(density, 20), 0, padding(density, 20), padding(density, 12))
            }
        )

        val search = EditText(activity).apply {
            hint = activity.getString(R.string.recipient_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setSingleLine()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(padding(density, 20), padding(density, 12), padding(density, 20), padding(density, 12))
        }
        root.addView(
            search,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            View(activity).apply { setBackgroundColor(ContextCompat.getColor(activity, R.color.divider)) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, padding(density, 1))
        )

        val list = ListView(activity).apply {
            divider = null
            dividerHeight = 0
        }
        root.addView(
            list,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        val rows = ArrayList<Target>()
        val adapter = RowAdapter(activity, rows) { target ->
            sheet.dismiss()
            onPicked(target.address)
        }
        list.adapter = adapter

        fun reload(query: String) {
            rows.clear()
            // Typing something that is not a contact name is very likely a raw
            // number the user wants to write to, so it is offered first.
            val typed = query.trim()
            val looksNumeric = typed.count { it.isDigit() } >= 4
            if (looksNumeric) {
                rows.add(Target(typed, activity.getString(R.string.send_to_new, typed), false))
            }
            for (entry in ContactsIndex.search(typed)) {
                rows.add(Target(entry.digits, entry.name, true))
            }
            if (!looksNumeric) {
                for (thread in recent) {
                    if (rows.none { it.address == thread.address }) {
                        rows.add(Target(thread.address, thread.display, false))
                    }
                }
            }
            adapter.notifyDataSetChanged()
        }

        // The recent conversations come from the stored inbox, which is a file
        // read; it is filled in on a worker and the sheet simply repaints when
        // it arrives. The address book is already in memory by this point.
        val loader = java.util.concurrent.Executors.newSingleThreadExecutor()
        val recent = ArrayList<RecentTarget>()
        loader.execute {
            val loaded = try {
                recentThreads()
            } catch (t: Throwable) {
                emptyList()
            }
            mainHandler.post {
                recent.clear()
                recent.addAll(loaded)
                if (sheet.isShowing) reload(search.text?.toString().orEmpty())
            }
            loader.shutdown()
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = reload(s?.toString().orEmpty())
        })
        sheet.setOnDismissListener { loader.shutdownNow() }

        reload("")
        sheet.setContentView(root)
        // The keyboard should be up immediately: this sheet exists to be typed
        // into. `show()` has to run first, so the window exists to focus.
        sheet.setOnShowListener {
            search.requestFocus()
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
        }
        sheet.show()
    }

    /**
     * Conversations already on the phone, so a message can be sent to a number
     * that is not in the address book without typing it out.
     */
    private fun recentThreads(): List<RecentTarget> {
        val threads = SmsRepository(appContext).cachedThreads()
        return threads.take(30).mapNotNull { row ->
            val name = ContactsIndex.readyEntryFor(row.address)?.name ?: row.address
            if (name.isBlank()) null else RecentTarget(row.address, name)
        }
    }

    private class RecentTarget(val address: String, val display: String)

    /**
     * One row: a soft-tinted initial, the name, and the number underneath when
     * the name is not the number itself.
     */
    private class RowAdapter(
        private val context: Context,
        private val rows: List<Target>,
        private val onPick: (Target) -> Unit
    ) : ArrayAdapter<Target>(context, 0, rows) {

        private val density = context.resources.displayMetrics.density

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: buildRow()
            val target = rows[position]
            val holder = row.tag as Holder

            holder.title.text = target.label
            TextDir.apply(holder.title, target.label)
            val (container, ink) = AvatarHelper.softPair(target.label.ifBlank { target.address })
            holder.avatar.background = AvatarHelper.circle(container)
            holder.letter.setTextColor(ink)
            holder.letter.text = AvatarHelper.monogram(target.label) ?: "#"

            val showNumber = target.isContact && target.label != target.address
            holder.subtitle.visibility = if (showNumber) View.VISIBLE else View.GONE
            holder.subtitle.text = if (showNumber) target.address else ""

            row.setOnClickListener { onPick(target) }
            return row
        }

        private class Holder(
            val avatar: FrameLayout,
            val letter: TextView,
            val title: TextView,
            val subtitle: TextView
        )

        private fun buildRow(): View {
            val avatarSize = (44 * density).toInt()
            val avatar = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(avatarSize, avatarSize)
            }
            val letter = TextView(context).apply {
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            }
            avatar.addView(
                letter,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )

            val title = TextView(context).apply {
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val subtitle = TextView(context).apply {
                setTextColor(ContextCompat.getColor(context, R.color.text_muted))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                maxLines = 1
                visibility = View.GONE
            }
            val column = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginStart = (12 * density).toInt() }
            }
            column.addView(title)
            column.addView(subtitle)

            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((16 * density).toInt(), (10 * density).toInt(), (16 * density).toInt(), (10 * density).toInt())
                isClickable = true
                setBackgroundColor(Color.TRANSPARENT)
                setMinimumHeight((56 * density).toInt())
            }
            row.addView(avatar)
            row.addView(column)
            row.tag = Holder(avatar, letter, title, subtitle)
            return row
        }
    }

    private fun padding(density: Float, value: Int): Int = (value * density).toInt()
}
