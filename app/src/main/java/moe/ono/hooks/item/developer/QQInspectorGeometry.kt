package moe.ono.hooks.item.developer

import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.text.SpannedString
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * Reversible, in-memory geometry and style edits for the currently attached QQ view tree.
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
        val alpha: Float,
        val layoutWidth: Int,
        val layoutHeight: Int,
        val measuredWidth: Int,
        val measuredHeight: Int,
        val layoutParams: ViewGroup.LayoutParams?,
        val background: Drawable?,
        val backgroundTint: ColorStateList?,
        val imageTint: ColorStateList?,
        val text: CharSequence?,
        val textColors: ColorStateList?,
        val textSizePx: Float
    )

    private data class MatchSignature(
        val viewClass: String,
        val resourceId: String,
        val parentClass: String,
        val grandParentClass: String,
        val childIndex: Int
    )

    private class FollowRule(
        source: View,
        root: ViewGroup,
        val signature: MatchSignature,
        val sourceBase: Snapshot
    ) {
        val source = WeakReference(source)
        val root = WeakReference(root)
        val peerSnapshots = WeakHashMap<View, Snapshot>()
        var applying = false
        lateinit var listener: ViewTreeObserver.OnGlobalLayoutListener
    }

    private val originals = WeakHashMap<View, Snapshot>()
    private val followRules = WeakHashMap<View, FollowRule>()

    private fun resourceName(view: View): String = if (view.id != View.NO_ID) {
        runCatching { view.resources.getResourceName(view.id) }
            .getOrDefault("0x${view.id.toString(16)}")
    } else "NO_ID"

    private fun capture(view: View): Snapshot {
        val params = view.layoutParams
        val textView = view as? TextView
        return Snapshot(
            translationX = view.translationX,
            translationY = view.translationY,
            scaleX = view.scaleX,
            scaleY = view.scaleY,
            visibility = view.visibility,
            alpha = view.alpha,
            layoutWidth = params?.width ?: view.width,
            layoutHeight = params?.height ?: view.height,
            measuredWidth = view.width,
            measuredHeight = view.height,
            layoutParams = params,
            background = view.background,
            backgroundTint = view.backgroundTintList,
            imageTint = (view as? ImageView)?.imageTintList,
            text = textView?.text?.let(::SpannedString),
            textColors = textView?.textColors,
            textSizePx = textView?.textSize ?: 0f
        )
    }

    @Synchronized
    fun beginEditing(view: View) {
        remember(view)
    }

    @Synchronized
    private fun remember(view: View) {
        if (!originals.containsKey(view)) originals[view] = capture(view)
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
        if (isAncestor(first, second) || isAncestor(second, first)) return false
        val firstPosition = screenPosition(first)
        val secondPosition = screenPosition(second)
        remember(first)
        remember(second)
        moveToScreen(first, secondPosition[0], secondPosition[1])
        moveToScreen(second, firstPosition[0], firstPosition[1])
        applySameClassFollow(first)
        applySameClassFollow(second)
        true
    }.getOrDefault(false)

    /**
     * Replaces the target visually and in the parent's layout calculation.
     * The original view remains attached as GONE so its exact state can be restored.
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
            val listener = object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    runCatching {
                        val currentObserver = root.viewTreeObserver
                        if (currentObserver.isAlive) currentObserver.removeOnGlobalLayoutListener(this)
                        if (replacement.parent != null) {
                            moveToScreen(replacement, targetPosition[0], targetPosition[1])
                            applySameClassFollow(replacement)
                        }
                    }
                }
            }
            observer.addOnGlobalLayoutListener(listener)
            root.requestLayout()
        } else {
            root.post {
                if (replacement.parent != null) {
                    moveToScreen(replacement, targetPosition[0], targetPosition[1])
                    applySameClassFollow(replacement)
                }
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
        applySameClassFollow(view)
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
        applySameClassFollow(view)
        true
    }.getOrDefault(false)

    @Synchronized
    fun setText(view: View, value: CharSequence): Boolean = runCatching {
        val target = view as? TextView ?: return false
        remember(view)
        target.text = value
        target.requestLayout()
        target.invalidate()
        applySameClassFollow(view)
        true
    }.getOrDefault(false)

    @Synchronized
    fun setTextColor(view: View, color: Int): Boolean = runCatching {
        val target = view as? TextView ?: return false
        remember(view)
        target.setTextColor(color)
        target.invalidate()
        applySameClassFollow(view)
        true
    }.getOrDefault(false)

    @Synchronized
    fun setBackgroundColor(view: View, color: Int): Boolean = runCatching {
        remember(view)
        if (view.background != null) {
            view.backgroundTintList = ColorStateList.valueOf(color)
        } else {
            view.background = ColorDrawable(color)
        }
        view.invalidate()
        applySameClassFollow(view)
        true
    }.getOrDefault(false)

    @Synchronized
    fun setTextSizeSp(view: View, sizeSp: Float): Boolean = runCatching {
        val target = view as? TextView ?: return false
        if (sizeSp !in 6f..48f) return false
        remember(view)
        target.textSize = sizeSp
        target.requestLayout()
        target.invalidate()
        applySameClassFollow(view)
        true
    }.getOrDefault(false)

    @Synchronized
    fun setAlpha(view: View, value: Float): Boolean = runCatching {
        if (value !in 0.1f..1f) return false
        remember(view)
        view.alpha = value
        view.invalidate()
        applySameClassFollow(view)
        true
    }.getOrDefault(false)

    @Synchronized
    fun applySameClassFollow(view: View): Int {
        val rule = followRules[view] ?: return 0
        return applyRule(rule)
    }

    @Synchronized
    fun enableSameClassFollow(view: View): Int {
        if (view.parent == null || view.windowToken == null) return -1
        remember(view)
        val root = view.rootView as? ViewGroup ?: return -1
        val signature = signatureOf(view) ?: return -1

        // One rule per structural signature prevents overlapping rules from fighting each other.
        followRules.entries.toList().forEach { (existingView, existingRule) ->
            if (existingRule.signature == signature) cancelSameClassFollow(existingView)
        }

        val sourceBase = originals[view] ?: capture(view)
        val rule = FollowRule(view, root, signature, sourceBase)
        rule.listener = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (rule.source.get()?.parent == null || rule.root.get() == null) {
                    removeRuleListener(rule)
                    return
                }
                applyRule(rule)
            }
        }
        followRules[view] = rule
        runCatching {
            root.viewTreeObserver.takeIf { it.isAlive }?.addOnGlobalLayoutListener(rule.listener)
        }
        return applyRule(rule)
    }

    @Synchronized
    fun hasSameClassFollow(view: View): Boolean = followRules.containsKey(view)

    @Synchronized
    fun activeFollowCount(): Int = followRules.size

    @Synchronized
    fun activeFollowDescriptions(): List<String> = followRules.keys.mapNotNull { view ->
        view?.let {
            val id = resourceName(it).takeIf { value -> value != "NO_ID" }?.substringAfterLast('/')
                ?.let { value -> " · $value" }.orEmpty()
            "${it.javaClass.simpleName}$id"
        }
    }

    @Synchronized
    fun cancelSameClassFollow(view: View): Int {
        val rule = followRules.remove(view) ?: return 0
        removeRuleListener(rule)
        val peers = rule.peerSnapshots.entries.map { it.key to it.value }
        peers.forEach { (peer, snapshot) ->
            runCatching {
                restoreSnapshot(peer, snapshot)
                peer.requestLayout()
                peer.invalidate()
            }
        }
        rule.peerSnapshots.clear()
        return peers.size
    }

    @Synchronized
    fun clearAllSameClassFollows(restorePeers: Boolean = true): Int {
        val sources = followRules.keys.toList()
        var peers = 0
        sources.forEach { source ->
            peers += if (restorePeers) cancelSameClassFollow(source) else {
                val rule = followRules.remove(source) ?: return@forEach
                removeRuleListener(rule)
                val count = rule.peerSnapshots.size
                rule.peerSnapshots.clear()
                count
            }
        }
        return sources.size
    }

    private fun removeRuleListener(rule: FollowRule) {
        val root = rule.root.get() ?: return
        runCatching {
            val observer = root.viewTreeObserver
            if (observer.isAlive) observer.removeOnGlobalLayoutListener(rule.listener)
        }
    }

    private fun signatureOf(view: View): MatchSignature? {
        val parent = view.parent as? ViewGroup ?: return null
        val grandParentClass = (parent.parent as? View)?.javaClass?.name.orEmpty()
        val childIndex = parent.indexOfChild(view)
        if (childIndex < 0) return null
        return MatchSignature(
            viewClass = view.javaClass.name,
            resourceId = resourceName(view),
            parentClass = parent.javaClass.name,
            grandParentClass = grandParentClass,
            childIndex = childIndex
        )
    }

    private fun matches(view: View, signature: MatchSignature): Boolean {
        if (view.javaClass.name != signature.viewClass || resourceName(view) != signature.resourceId) return false
        val parent = view.parent as? ViewGroup ?: return false
        if (parent.javaClass.name != signature.parentClass || parent.indexOfChild(view) != signature.childIndex) return false
        val grandParentClass = (parent.parent as? View)?.javaClass?.name.orEmpty()
        return grandParentClass == signature.grandParentClass
    }

    private fun collectMatching(root: ViewGroup, signature: MatchSignature): List<View> {
        val matches = ArrayList<View>()
        var visited = 0
        fun walk(view: View, depth: Int) {
            if (visited >= 1400 || depth > 36 || matches.size >= 96) return
            visited++
            if (view !== root && matches(view, signature) && view.isAttachedToWindow &&
                view.visibility == View.VISIBLE && view.alpha > 0.03f &&
                view.javaClass.simpleName != "InspectorOverlay" && view.tag != "tnt.qq.inspector.entry"
            ) {
                matches.add(view)
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) walk(view.getChildAt(index), depth + 1)
            }
        }
        walk(root, 0)
        return matches
    }

    private fun sameColorState(a: ColorStateList?, b: ColorStateList?): Boolean {
        if (a == null || b == null) return a == null && b == null
        return a.defaultColor == b.defaultColor && a.isStateful == b.isStateful
    }

    private fun layoutWidth(view: View): Int = view.layoutParams?.width ?: view.width
    private fun layoutHeight(view: View): Int = view.layoutParams?.height ?: view.height

    private fun applyRule(rule: FollowRule): Int {
        if (rule.applying) return 0
        val source = rule.source.get() ?: return 0
        val root = rule.root.get() ?: return 0
        if (source.parent == null || source.rootView !== root) return 0
        rule.applying = true
        var changedCount = 0
        try {
            val base = rule.sourceBase
            val dx = source.translationX - base.translationX
            val dy = source.translationY - base.translationY
            val sourceWidthChanged = layoutWidth(source) != base.layoutWidth
            val sourceHeightChanged = layoutHeight(source) != base.layoutHeight
            val sourceWidth = if (layoutWidth(source) > 0) layoutWidth(source) else source.width
            val sourceHeight = if (layoutHeight(source) > 0) layoutHeight(source) else source.height
            val scaleXChanged = abs(source.scaleX - base.scaleX) > 0.001f
            val scaleYChanged = abs(source.scaleY - base.scaleY) > 0.001f
            val alphaChanged = abs(source.alpha - base.alpha) > 0.001f
            val sourceText = (source as? TextView)
            val baseTextChanged = sourceText != null && sourceText.text?.toString() != base.text?.toString()
            val sourceTextColorChanged = sourceText != null && !sameColorState(sourceText.textColors, base.textColors)
            val sourceTextSizeChanged = sourceText != null && abs(sourceText.textSize - base.textSizePx) > 0.5f
            val sourceBackgroundTintChanged = !sameColorState(source.backgroundTintList, base.backgroundTint)
            val sourceBackgroundColor = (source.background as? ColorDrawable)?.color
            val sourceBackgroundColorChanged = sourceBackgroundColor != base.background?.let { (it as? ColorDrawable)?.color }
            val sourceImageTint = (source as? ImageView)?.imageTintList
            val sourceImageTintChanged = source is ImageView && !sameColorState(sourceImageTint, base.imageTint)

            val peers = collectMatching(root, rule.signature)
            peers.forEach { peer ->
                if (peer === source || peer.rootView !== source.rootView) return@forEach
                if (peer.layoutParams != null && source.layoutParams != null &&
                    peer.layoutParams!!::class.java != source.layoutParams!!::class.java
                ) return@forEach

                remember(peer)
                val peerBase = rule.peerSnapshots.getOrPut(peer) { capture(peer) }
                var touched = false
                if (abs(dx) > 0.05f) {
                    val targetX = peerBase.translationX + dx
                    if (abs(peer.translationX - targetX) > 0.05f) {
                        peer.translationX = targetX
                        touched = true
                    }
                }
                if (abs(dy) > 0.05f) {
                    val targetY = peerBase.translationY + dy
                    if (abs(peer.translationY - targetY) > 0.05f) {
                        peer.translationY = targetY
                        touched = true
                    }
                }
                if (sourceWidthChanged && peer.layoutParams != null && sourceWidth > 0) {
                    if (layoutWidth(peer) != sourceWidth) {
                        peer.layoutParams.width = sourceWidth
                        peer.requestLayout()
                        touched = true
                    }
                }
                if (sourceHeightChanged && peer.layoutParams != null && sourceHeight > 0) {
                    if (layoutHeight(peer) != sourceHeight) {
                        peer.layoutParams.height = sourceHeight
                        peer.requestLayout()
                        touched = true
                    }
                }
                if (scaleXChanged && abs(peer.scaleX - source.scaleX) > 0.001f) {
                    peer.scaleX = source.scaleX
                    touched = true
                }
                if (scaleYChanged && abs(peer.scaleY - source.scaleY) > 0.001f) {
                    peer.scaleY = source.scaleY
                    touched = true
                }
                if (alphaChanged && abs(peer.alpha - source.alpha) > 0.001f) {
                    peer.alpha = source.alpha
                    touched = true
                }
                if (sourceBackgroundTintChanged && !sameColorState(peer.backgroundTintList, source.backgroundTintList)) {
                    peer.backgroundTintList = source.backgroundTintList
                    touched = true
                }
                if (sourceBackgroundColorChanged && sourceBackgroundColor != null) {
                    if (peer.background == null) {
                        if ((peer.background as? ColorDrawable)?.color != sourceBackgroundColor) {
                            peer.background = ColorDrawable(sourceBackgroundColor)
                            touched = true
                        }
                    } else if (!sameColorState(peer.backgroundTintList, ColorStateList.valueOf(sourceBackgroundColor))) {
                        peer.backgroundTintList = ColorStateList.valueOf(sourceBackgroundColor)
                        touched = true
                    }
                }
                if (sourceImageTintChanged && peer is ImageView && !sameColorState(peer.imageTintList, sourceImageTint)) {
                    peer.imageTintList = sourceImageTint
                    touched = true
                }
                if (sourceText != null && peer is TextView) {
                    if (baseTextChanged && peer.text?.toString() != sourceText.text?.toString()) {
                        peer.text = sourceText.text
                        touched = true
                    }
                    if (sourceTextColorChanged && !sameColorState(peer.textColors, sourceText.textColors)) {
                        peer.setTextColor(sourceText.textColors)
                        touched = true
                    }
                    if (sourceTextSizeChanged && abs(peer.textSize - sourceText.textSize) > 0.5f) {
                        peer.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sourceText.textSize)
                        touched = true
                    }
                }
                if (touched) {
                    peer.invalidate()
                    changedCount++
                }
            }
        } finally {
            rule.applying = false
        }
        return changedCount
    }

    private fun restoreSnapshot(view: View, saved: Snapshot) {
        val params = view.layoutParams ?: saved.layoutParams
        if (params != null) {
            params.width = saved.layoutWidth
            params.height = saved.layoutHeight
            view.layoutParams = params
        }
        view.background = saved.background
        view.backgroundTintList = saved.backgroundTint
        if (view is ImageView) view.imageTintList = saved.imageTint
        if (view is TextView) {
            if (saved.text != null) view.text = saved.text
            if (saved.textColors != null) view.setTextColor(saved.textColors)
            if (saved.textSizePx > 0f) view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, saved.textSizePx)
        }
        view.translationX = saved.translationX
        view.translationY = saved.translationY
        view.scaleX = saved.scaleX
        view.scaleY = saved.scaleY
        view.alpha = saved.alpha
        view.visibility = saved.visibility
        view.requestLayout()
        view.invalidate()
    }

    @Synchronized
    fun currentBounds(view: View): Rect? = runCatching {
        if (view.parent == null || view.visibility != View.VISIBLE) return null
        val position = screenPosition(view)
        Rect(position[0], position[1], position[0] + view.width, position[1] + view.height)
    }.getOrNull()

    @Synchronized
    fun restore(view: View): Boolean {
        val saved = originals.remove(view) ?: return false
        cancelSameClassFollow(view)
        return runCatching {
            restoreSnapshot(view, saved)
            true
        }.getOrDefault(false)
    }

    @Synchronized
    fun restoreAll(): Int {
        clearAllSameClassFollows(restorePeers = false)
        val entries = originals.entries.map { it.key to it.value }
        originals.clear()
        entries.forEach { (view, state) -> runCatching { restoreSnapshot(view, state) } }
        return entries.size
    }
}
