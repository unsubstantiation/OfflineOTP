// Sandbox-only stand-ins, see android_content.kt.
package androidx.recyclerview.widget

import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup

class RecyclerView(context: android.content.Context = android.content.Context()) : android.view.View() {
    /**
     * The platform's `getLayoutManager()`/`setLayoutManager(LayoutManager)` pair is a pair of Java
     * methods, so the stand-in keeps the setter as a function and exposes the getter as a
     * read-only property.
     */
    private var layoutManagerValue: LayoutManager? = null

    /** The platform's `getAdapter()`/`setAdapter(Adapter)` pair; see [layoutManagerValue]. */
    private var adapterValue: Adapter<*>? = null

    /** The layout manager that was set. */
    val layoutManager: LayoutManager?
        get() = layoutManagerValue

    /** The adapter that was set. */
    val adapter: Adapter<*>?
        get() = adapterValue

    /** Whether the size is fixed. */
    var hasFixedSize: Boolean = false
        private set

    fun setHasFixedSize(hasFixedSize: Boolean) {
        this.hasFixedSize = hasFixedSize
    }

    fun setAdapter(adapter: Adapter<*>?) {
        adapterValue = adapter
    }

    fun setLayoutManager(layoutManager: LayoutManager?) {
        layoutManagerValue = layoutManager
    }

    open class LayoutManager

    /**
     * The platform class is abstract with final type/position getters; the stand-in leaves the
     * values open so the checks can build fakes, and no recycling/adapter machinery is modelled.
     */
    abstract class ViewHolder(val itemView: View) {
        open val itemViewType: Int = 0
        open val adapterPosition: Int = 0
    }

    /**
     * The list adapter. The stand-in records the notifications instead of dispatching them to a
     * recycler view; no view recycling, layout or diffing is modelled.
     */
    abstract class Adapter<VH : ViewHolder> {
        /** The notifications the adapter published, in order. */
        val notifications: MutableList<String> = ArrayList()

        /** The observers that were registered. */
        val observers: MutableList<AdapterDataObserver> = ArrayList()

        abstract fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH

        abstract fun onBindViewHolder(holder: VH, position: Int)

        open fun onViewRecycled(holder: VH) {}

        open fun getItemCount(): Int = 0

        open fun getItemId(position: Int): Long = RecyclerView.NO_ID

        /** Whether the adapter publishes stable ids; the platform keeps the flag. */
        var stableIds: Boolean = false
            private set

        open fun setHasStableIds(hasStableIds: Boolean) {
            stableIds = hasStableIds
        }

        open fun hasStableIds(): Boolean = stableIds

        open fun registerAdapterDataObserver(observer: AdapterDataObserver) {
            observers.add(observer)
        }

        open fun notifyDataSetChanged() {
            notifications.add("changed")
        }

        open fun notifyItemChanged(position: Int) {
            notifications.add("itemChanged:$position")
        }

        open fun notifyItemChanged(position: Int, payload: Any?) {
            notifications.add("itemChanged:$position")
        }

        open fun notifyItemRemoved(position: Int) {
            notifications.add("itemRemoved:$position")
        }

        open fun notifyItemMoved(fromPosition: Int, toPosition: Int) {
            notifications.add("itemMoved:$fromPosition->$toPosition")
        }
    }

    /** The observer of the adapter's change notifications. */
    abstract class AdapterDataObserver {
        open fun onChanged() {}
        open fun onItemRangeChanged(positionStart: Int, itemCount: Int) {}
        open fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {}
        open fun onItemRangeInserted(positionStart: Int, itemCount: Int) {}
        open fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {}
        open fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) {}
    }

    companion object {
        const val NO_POSITION = -1
        const val NO_ID = -1L
    }
}

class GridLayoutManager(context: android.content.Context = android.content.Context()) : RecyclerView.LayoutManager()
class LinearLayoutManager(context: android.content.Context = android.content.Context()) : RecyclerView.LayoutManager() {
    /** The orientation of the list. */
    var orientation: Int = VERTICAL
        private set

    fun setOrientation(orientation: Int) {
        this.orientation = orientation
    }

    companion object {
        const val HORIZONTAL = 0
        const val VERTICAL = 1
    }
}

/**
 * The constants and the nested callback surface used by the converted drag & drop helper.
 * `makeMovementFlags` replicates the documented androidx composition
 * (`(swipe|drag) | swipe << 8 | drag << 16`), the same caveat as the TypedValue replication.
 */
class ItemTouchHelper(private val callback: Callback) {
    /** The recycler view the helper was attached to. */
    var attachedRecyclerView: RecyclerView? = null
        private set

    fun attachToRecyclerView(recyclerView: RecyclerView) {
        attachedRecyclerView = recyclerView
    }

    companion object {
        const val UP = 1
        const val DOWN = 2
        const val LEFT = 4
        const val RIGHT = 8
        const val ACTION_STATE_IDLE = 0
        const val ACTION_STATE_SWIPE = 1
        const val ACTION_STATE_DRAG = 2
    }

    abstract class Callback {
        open fun isLongPressDragEnabled(): Boolean = true
        open fun isItemViewSwipeEnabled(): Boolean = true
        abstract fun onMove(recyclerView: RecyclerView, source: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean
        abstract fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int)
        open fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int = 0
        open fun onChildDraw(c: Canvas, recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder,
                             dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean) {}
        open fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {}
        open fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {}

        fun makeMovementFlags(dragFlags: Int, swipeFlags: Int): Int {
            return makeFlag(ACTION_STATE_IDLE, swipeFlags or dragFlags) or
                    makeFlag(ACTION_STATE_SWIPE, swipeFlags) or
                    makeFlag(ACTION_STATE_DRAG, dragFlags)
        }

        private fun makeFlag(actionState: Int, directions: Int): Int = directions shl (actionState * 8)
    }
}
