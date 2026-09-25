package ir.inod.smsguard

import android.animation.ValueAnimator
import android.view.View
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

/** Small, consistent motion primitives; never animate every row on a data refresh. */
object UiMotion {
    fun enabled(): Boolean = ValueAnimator.areAnimatorsEnabled()

    fun list(recycler: RecyclerView) {
        recycler.itemAnimator = DefaultItemAnimator().apply {
            supportsChangeAnimations = false // avoids a white flash on read/category changes
            addDuration = 140
            removeDuration = 170
            moveDuration = 190
            changeDuration = 0
        }
    }

    fun reveal(view: View) {
        view.animate().cancel()
        if (!enabled()) {
            view.alpha = 1f
            return
        }
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(170).start()
    }

    /** Diff only the affected cards, leaving the visible scroll anchor in place. */
    fun <T> update(
        adapter: RecyclerView.Adapter<*>,
        items: MutableList<T>,
        next: List<T>,
        sameItem: (T, T) -> Boolean,
        sameContent: (T, T) -> Boolean = { a, b -> a == b }
    ) {
        val old = items.toList()
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = next.size
            override fun areItemsTheSame(oldPosition: Int, newPosition: Int) =
                sameItem(old[oldPosition], next[newPosition])
            override fun areContentsTheSame(oldPosition: Int, newPosition: Int) =
                sameContent(old[oldPosition], next[newPosition])
        }, old.size + next.size <= 600)
        items.clear()
        items.addAll(next)
        diff.dispatchUpdatesTo(adapter)
    }
}
