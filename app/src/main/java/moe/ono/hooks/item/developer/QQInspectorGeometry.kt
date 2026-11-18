package moe.ono.hooks.item.developer

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import java.util.WeakHashMap

/**
 * Reversible, in-memory geometry edits for the currently attached QQ view tree.
 * It uses translations and the view's own LayoutParams; it never dispatches clicks
 * or reparents arbitrary host controls.
 */
internal object QQInspectorGeometry {
    private data class Snapshot(
        val translationX: Float,
        val translationY: Float,
        val scaleX: Float,
        val scaleY: Float,
        val visibility: Int,
        val width: Int,
        val height: Int,
        val layoutParams: ViewGroup.LayoutParams?
    )

    private val originals = WeakHashMap<View, Snapshot>()

    @Synchronized
    private fun remember(view: View) {
        if (originals.containsKey(view)) return
        val params = view.layoutParams
        originals[view] = Snapshot(
            translationX = view.translationX,
            translationY = view.translationY,
            scaleX = view.scaleX,
            scaleY = view.scaleY,
            visibility = view.visibility,
            width = params?.width ?: view.width,
            height = params?.height ?: view.height,
            layoutParams = params
        )
    }

    private fun isAncestor(ancestor: View, descendant: View): Boolean {
        var current = descendant.parent
        while (current is View) {
            if (current === ancestor) return true
            current = current.parent
        }
        return false
    }

    private fun screenPosition(view: View): IntArray = IntArray(2).also(view::getLocationOnScreen)

    private fun moveToScreen(view: View, left: Int, top: Int) {
        val current = screenPosition(view)
        view.translationX += (left - current[0]).toFloat()
        view.translationY += (top - current[1]).toFloat()
        view.invalidate()
    }

    private fun resizeTo(view: View, width: Int, height: Int) {
        val params = view.layoutParams ?: return
        params.width = width.coerceAtLeast(1)
        params.height = height.coerceAtLeast(1)
        view.layoutParams = params
        view.requestLayout()
    }

    @Synchronized
    fun swapPositions(first: View, second: View): Boolean = runCatching {
        if (first === second || first.parent == null || second.parent == null) return false
        if (first.windowToken == null || second.windowToken == null || first.rootView !== second.rootView) return false
        // Moving a parent and one of its descendants independently produces ambiguous geometry.
        if (isAncestor(first, second) || isAncestor(second, first)) return false
        val firstPosition = screenPosition(first)
        val secondPosition = screenPosition(second)
        remember(first)
        remember(second)
        moveToScreen(first, secondPosition[0], secondPosition[1])
        moveToScreen(second, firstPosition[0], firstPosition[1])
        true
    }.getOrDefault(false)

    /**
     * Replaces the target visually and in the parent's layout calculation.
     * The original view remains attached as GONE so its exact state can be restored.
     * A one-shot layout callback re-aligns the replacement after QQ reflows siblings.
     */
    @Synchronized
    fun replaceAtPosition(target: View, replacement: View): Boolean = runCatching {
        if (target === replacement || target.parent == null || replacement.parent == null) return false
        if (target.windowToken == null || replacement.windowToken == null || target.rootView !== replacement.rootView) return false
        if (isAncestor(target, replacement) || isAncestor(replacement, target)) return false
        val targetPosition = screenPosition(target)
        val targetWidth = target.width.coerceAtLeast(1)
        val targetHeight = target.height.coerceAtLeast(1)
        remember(target)
        remember(replacement)
        moveToScreen(replacement, targetPosition[0], targetPosition[1])
        resizeTo(replacement, targetWidth, targetHeight)
        target.visibility = View.GONE
        target.requestLayout()
        target.invalidate()
        replacement.invalidate()

        val root = target.rootView
        val observer = root.viewTreeObserver
        if (observer.isAlive) {
            val listener = object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    runCatching {
                        val currentObserver = root.viewTreeObserver
                        if (currentObserver.isAlive) currentObserver.removeOnGlobalLayoutListener(this)
                        if (replacement.parent != null) {
                            moveToScreen(replacement, targetPosition[0], targetPosition[1])
                        }
                    }
                }
            }
            observer.addOnGlobalLayoutListener(listener)
            root.requestLayout()
        } else {
            root.post {
                if (replacement.parent != null) moveToScreen(replacement, targetPosition[0], targetPosition[1])
            }
        }
        true
    }.getOrDefault(false)

    @Synchronized
    fun moveBy(view: View, dx: Int, dy: Int): Boolean = runCatching {
        if (view.parent == null) return false
        remember(view)
        view.translationX += dx.toFloat()
        view.translationY += dy.toFloat()
        view.invalidate()
        true
    }.getOrDefault(false)

    @Synchronized
    fun resizeBy(view: View, dw: Int, dh: Int): Boolean = runCatching {
        if (view.parent == null) return false
        remember(view)
        val params = view.layoutParams ?: return false
        val width = if (params.width > 0) params.width else view.width
        val height = if (params.height > 0) params.height else view.height
        resizeTo(view, width + dw, height + dh)
        true
    }.getOrDefault(false)

    @Synchronized
    fun currentBounds(view: View): Rect? = runCatching {
        if (view.parent == null || view.visibility != View.VISIBLE) return null
        val position = screenPosition(view)
        Rect(position[0], position[1], position[0] + view.width, position[1] + view.height)
    }.getOrNull()

    @Synchronized
    fun restore(view: View): Boolean {
        val saved = originals.remove(view) ?: return false
        return runCatching {
            val params = saved.layoutParams
            if (params != null) {
                params.width = saved.width
                params.height = saved.height
                view.layoutParams = params
            }
            view.translationX = saved.translationX
            view.translationY = saved.translationY
            view.scaleX = saved.scaleX
            view.scaleY = saved.scaleY
            view.visibility = saved.visibility
            view.requestLayout()
            view.invalidate()
            true
        }.getOrDefault(false)
    }

    @Synchronized
    fun restoreAll(): Int {
        val entries = originals.keys.toList()
        entries.forEach(::restore)
        return entries.size
    }
}
