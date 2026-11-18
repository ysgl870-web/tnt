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
import java.util.WeakHashMap
import java.util.LinkedHashMap
import kotlin.math.abs
import android.os.SystemClock
import org.json.JSONObject

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
        val textSizePx: Float,
        val elevation: Float,
        val translationZ: Float
    )

    private data class ContainerLayerState(
        val translationZ: Float,
        val clipChildren: Boolean,
        val clipToPadding: Boolean,
        val clipToOutline: Boolean
    )

    private data class MatchSignature(
        val viewClass: String,
        val resourceId: String,
        val parentClass: String,
        val grandParentClass: String,
        val childIndex: Int
    )

    private class FollowRule(
        val source: View,
        val root: ViewGroup,
        val signature: MatchSignature,
        val sourceBase: Snapshot,
        var profile: Snapshot
    ) {
        val peerSnapshots = WeakHashMap<View, Snapshot>()
        var applying = false
        var lastApplyAt = 0L
        lateinit var listener: ViewTreeObserver.OnGlobalLayoutListener
    }

    private val originals = WeakHashMap<View, Snapshot>()
    private val elevatedContainers = WeakHashMap<ViewGroup, ContainerLayerState>()
    private val visualOwners = WeakHashMap<View, List<ViewGroup>>()
    private val raisedViews = WeakHashMap<View, Float>()
    private val raisedViewOwners = WeakHashMap<View, List<View>>()
    private val visualPriorities = WeakHashMap<View, Float>()
    // A bounded strong map keeps rules alive while RecyclerView rows are detached/recycled.
    private val followRules = LinkedHashMap<View, FollowRule>()

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
            textSizePx = textView?.textSize ?: 0f,
            elevation = view.elevation,
            translationZ = view.translationZ
        )
    }

    @Synchronized
    fun beginEditing(view: View) {
        remember(view)
        applyVisualPriority(view)
    }

    /** Finalize one gesture: expensive sibling synchronization and z-order work happens once. */
    @Synchronized
    fun finishEditing(view: View) {
        if (view.parent == null) return
        applyVisualPriority(view)
        applySameClassFollow(view)
    }

    /**
     * Return only properties explicitly changed from the first edit snapshot.
     * This avoids persisting volatile QQ-bound text such as each user's nickname.
     */
    @Synchronized
    fun exportPersistentState(view: View): JSONObject? {
        val original = originals[view] ?: return null
        val result = JSONObject()
        val dx = view.translationX - original.translationX
        val dy = view.translationY - original.translationY
        if (abs(dx) > 0.05f) result.put("dx", dx.toDouble())
        if (abs(dy) > 0.05f) result.put("dy", dy.toDouble())
        val params = view.layoutParams
        val width = params?.width ?: view.width
        val height = params?.height ?: view.height
        if (width != original.layoutWidth && width > 0) result.put("width", width)
        if (height != original.layoutHeight && height > 0) result.put("height", height)
        if (abs(view.alpha - original.alpha) > 0.001f) result.put("alpha", view.alpha.toDouble())

        if (view is TextView) {
            if (view.text?.toString() != original.text?.toString()) {
                result.put("text", view.text?.toString().orEmpty().take(120))
            }
            if (view.textColors.defaultColor != original.textColors?.defaultColor) {
                result.put("textColor", view.textColors.defaultColor)
            }
            if (abs(view.textSize - original.textSizePx) > 0.5f) {
                result.put("textSizePx", view.textSize.toDouble())
            }
        }
        if (view.backgroundTintList?.defaultColor != original.backgroundTint?.defaultColor &&
            view.backgroundTintList != null
        ) {
            result.put("backgroundTint", view.backgroundTintList!!.defaultColor)
        } else {
            val color = (view.background as? ColorDrawable)?.color
            val oldColor = original.background?.let { (it as? ColorDrawable)?.color }
            if (color != null && color != oldColor) result.put("backgroundColor", color)
        }
        if (view is ImageView && view.imageTintList?.defaultColor != original.imageTint?.defaultColor &&
            view.imageTintList != null
        ) {
            result.put("imageTint", view.imageTintList!!.defaultColor)
        }
        return result.takeIf { it.length() > 0 }
    }

    @Synchronized
    fun applyPersistentState(view: View, state: JSONObject) {
        if (view.parent == null || state.length() == 0) return
        remember(view)
        var geometryChanged = false
        if (state.has("dx")) {
            view.translationX = originals[view]!!.translationX + state.optDouble("dx").toFloat()
            geometryChanged = true
        }
        if (state.has("dy")) {
            view.translationY = originals[view]!!.translationY + state.optDouble("dy").toFloat()
            geometryChanged = true
        }
        val params = view.layoutParams
        if (params != null) {
            if (state.has("width")) { params.width = state.optInt("width").coerceAtLeast(1); geometryChanged = true }
            if (state.has("height")) { params.height = state.optInt("height").coerceAtLeast(1); geometryChanged = true }
            view.layoutParams = params
        }
        if (state.has("alpha")) view.alpha = state.optDouble("alpha").toFloat().coerceIn(0.1f, 1f)
        if (view is TextView) {
            if (state.has("text")) view.text = state.optString("text").take(120)
            if (state.has("textColor")) view.setTextColor(state.optInt("textColor"))
            if (state.has("textSizePx")) view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, state.optDouble("textSizePx").toFloat())
        }
        if (state.has("backgroundTint")) {
            view.backgroundTintList = ColorStateList.valueOf(state.optInt("backgroundTint"))
        } else if (state.has("backgroundColor")) {
            val color = state.optInt("backgroundColor")
            if (view.background == null) view.background = ColorDrawable(color)
            else view.backgroundTintList = ColorStateList.valueOf(color)
        }
        if (view is ImageView && state.has("imageTint")) view.imageTintList = ColorStateList.valueOf(state.optInt("imageTint"))
        if (geometryChanged) {
            view.requestLayout()
            applyVisualPriority(view)
        }
        view.invalidate()
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
        applyVisualPriority(view)
        view.invalidate()
    }

    private fun resizeTo(view: View, width: Int, height: Int) {
        val params = view.layoutParams ?: return
        params.width = width.coerceAtLeast(1)
        params.height = height.coerceAtLeast(1)
        view.layoutParams = params
        view.requestLayout()
    }

    /**
     * A translated child can be clipped by any ancestor ViewGroup and may remain
     * underneath a sibling/overlay. Temporarily relax clipping on its ancestor
     * chain and raise the edited branch in Android's Z ordering. Original values
     * are stored once and restored when the last edited owner leaves that branch.
     */
    @Synchronized
    private fun applyVisualPriority(view: View) {
        if (view.parent == null) return
        val density = view.resources.displayMetrics.density
        val content = runCatching {
            view.rootView.findViewById<View>(android.R.id.content) as? ViewGroup
        }.getOrNull()
        val ancestors = ArrayList<ViewGroup>(8)
        var current = view.parent
        var depth = 0
        while (current is ViewGroup && depth++ < 32) {
            ancestors.add(current)
            if (!elevatedContainers.containsKey(current)) {
                elevatedContainers[current] = ContainerLayerState(
                    translationZ = current.translationZ,
                    clipChildren = current.clipChildren,
                    clipToPadding = current.clipToPadding,
                    clipToOutline = current.clipToOutline
                )
            }
            current.clipChildren = false
            current.clipToPadding = false
            if (current.clipToOutline) current.clipToOutline = false
            if (current === content) break
            current = current.parent
        }
        visualOwners[view] = ancestors

        // Raise the actual control modestly, and only its direct content branch slightly.
        // InspectorOverlay uses a much higher elevation so it can never disappear under it.
        val priority = 8f * density
        raisedViews.putIfAbsent(view, view.translationZ)
        visualPriorities[view] = priority
        view.translationZ = maxOf(view.translationZ, priority)

        if (content != null) {
            var branch: View = view
            var parent = branch.parent
            var hops = 0
            while (parent is View && parent !== content && hops++ < 32) {
                branch = parent
                parent = branch.parent
            }
            if (branch !== view && branch.parent === content) {
                raisedViews.putIfAbsent(branch, branch.translationZ)
                branch.translationZ = maxOf(branch.translationZ, 4f * density)
                raisedViewOwners[view] = listOf(view, branch)
            } else {
                raisedViewOwners[view] = listOf(view)
            }
        } else {
            raisedViewOwners[view] = listOf(view)
        }
        view.invalidate()
    }

    @Synchronized
    private fun releaseVisualPriority(view: View) {
        val raised = raisedViewOwners.remove(view).orEmpty()
        visualPriorities.remove(view)
        val activeRaised = raisedViewOwners.values.flatten().toHashSet()
        raised.forEach { target ->
            if (target !in activeRaised) {
                val originalZ = raisedViews.remove(target) ?: return@forEach
                runCatching { target.translationZ = originalZ; target.invalidate() }
            } else {
                val maxPriority = raisedViewOwners.entries
                    .filter { (_, values) -> target in values }
                    .mapNotNull { (owner, _) -> visualPriorities[owner] }
                    .maxOrNull() ?: 0f
                runCatching { target.translationZ = maxOf(raisedViews[target] ?: 0f, maxPriority) }
            }
        }

        visualOwners.remove(view)
        val stillUsed = visualOwners.values.flatten().toHashSet()
        val noLongerUsed = elevatedContainers.keys.filter { it !in stillUsed }
        noLongerUsed.forEach { group ->
            val state = elevatedContainers.remove(group) ?: return@forEach
            runCatching {
                group.translationZ = state.translationZ
                group.clipChildren = state.clipChildren
                group.clipToPadding = state.clipToPadding
                group.clipToOutline = state.clipToOutline
                group.invalidate()
            }
        }
    }

    @Synchronized
    private fun restoreAllVisualPriority() {
        visualOwners.clear()
        raisedViewOwners.clear()
        visualPriorities.clear()
        val raised = raisedViews.entries.map { it.key to it.value }
        raisedViews.clear()
        raised.forEach { (view, originalZ) -> runCatching { view.translationZ = originalZ; view.invalidate() } }
        val entries = elevatedContainers.entries.map { it.key to it.value }
        elevatedContainers.clear()
        entries.forEach { (group, state) ->
            runCatching {
                group.translationZ = state.translationZ
                group.clipChildren = state.clipChildren
                group.clipToPadding = state.clipToPadding
                group.clipToOutline = state.clipToOutline
                group.invalidate()
            }
        }
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
        // Both branches must escape ancestor clipping and sit above their old siblings.
        applyVisualPriority(first)
        applyVisualPriority(second)
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
                            applyVisualPriority(replacement)
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
                    applyVisualPriority(replacement)
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
    fun setText(view: View, value: CharSequence): Boolean = runCatching {
        val target = view as? TextView ?: return false
        remember(view)
        target.text = value
        target.requestLayout()
        target.invalidate()
        true
    }.getOrDefault(false)

    @Synchronized
    fun setTextColor(view: View, color: Int): Boolean = runCatching {
        val target = view as? TextView ?: return false
        remember(view)
        target.setTextColor(color)
        target.invalidate()
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
        true
    }.getOrDefault(false)

    @Synchronized
    fun setAlpha(view: View, value: Float): Boolean = runCatching {
        if (value !in 0.1f..1f) return false
        remember(view)
        view.alpha = value
        view.invalidate()
        true
    }.getOrDefault(false)

    @Synchronized
    fun applySameClassFollow(view: View): Int {
        val rule = followRules[view] ?: return 0
        // Only explicit editor/geometry actions refresh the profile. Global layout callbacks
        // reuse this snapshot so QQ recycling cannot accidentally copy a new user's text.
        if (view.parent != null && view.isAttachedToWindow) rule.profile = capture(view)
        return applyRule(rule)
    }

    @Synchronized
    fun enableSameClassFollow(view: View): Int {
        if (view.parent == null || !view.isAttachedToWindow) return -1
        remember(view)
        val root = view.rootView as? ViewGroup ?: return -1
        val signature = signatureOf(view) ?: return -1

        // One rule per structural signature prevents overlapping rules from fighting each other.
        followRules.entries.toList().forEach { (existingView, existingRule) ->
            if (existingRule.signature == signature) cancelSameClassFollow(existingView)
        }
        while (followRules.size >= 24) {
            followRules.keys.firstOrNull()?.let(::cancelSameClassFollow) ?: break
        }

        val sourceBase = originals[view] ?: capture(view)
        val rule = FollowRule(view, root, signature, sourceBase, capture(view))
        rule.listener = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                // The source may be temporarily detached by a recycled list row. Keep using
                // its last user-edited profile and apply it to newly-created matching rows.
                if (!root.isAttachedToWindow) return
                val now = android.os.SystemClock.uptimeMillis()
                if (now - rule.lastApplyAt < 280L) return
                rule.lastApplyAt = now
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
                releaseVisualPriority(peer)
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
        val root = rule.root
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
            if (visited >= 520 || depth > 32 || matches.size >= 48) return
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
        val source = rule.source
        val root = rule.root
        if (!root.isAttachedToWindow) return 0
        rule.applying = true
        var changedCount = 0
        try {
            val base = rule.sourceBase
            val profile = rule.profile
            val dx = profile.translationX - base.translationX
            val dy = profile.translationY - base.translationY
            val sourceWidthChanged = profile.layoutWidth != base.layoutWidth
            val sourceHeightChanged = profile.layoutHeight != base.layoutHeight
            val sourceWidth = if (profile.layoutWidth > 0) profile.layoutWidth else profile.measuredWidth
            val sourceHeight = if (profile.layoutHeight > 0) profile.layoutHeight else profile.measuredHeight
            val scaleXChanged = abs(profile.scaleX - base.scaleX) > 0.001f
            val scaleYChanged = abs(profile.scaleY - base.scaleY) > 0.001f
            val alphaChanged = abs(profile.alpha - base.alpha) > 0.001f
            val sourceIsText = source is TextView
            val sourceTextChanged = sourceIsText && profile.text?.toString() != base.text?.toString()
            val sourceTextColorChanged = sourceIsText && !sameColorState(profile.textColors, base.textColors)
            val sourceTextSizeChanged = sourceIsText && abs(profile.textSizePx - base.textSizePx) > 0.5f
            val sourceBackgroundTintChanged = !sameColorState(profile.backgroundTint, base.backgroundTint)
            val sourceBackgroundColor = (profile.background as? ColorDrawable)?.color
            val sourceBackgroundColorChanged = sourceBackgroundColor != base.background?.let { (it as? ColorDrawable)?.color }
            val sourceIsImage = source is ImageView
            val sourceImageTintChanged = sourceIsImage && !sameColorState(profile.imageTint, base.imageTint)

            val peers = collectMatching(root, rule.signature)
            peers.forEach { peer ->
                if (peer === source || peer.rootView !== root) return@forEach
                if (peer.layoutParams != null && source.layoutParams != null &&
                    peer.layoutParams!!::class.java != source.layoutParams!!::class.java
                ) return@forEach

                remember(peer)
                val peerBase = rule.peerSnapshots.getOrPut(peer) { capture(peer) }
                var touched = false
                var needsLayout = false
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
                        touched = true
                        needsLayout = true
                    }
                }
                if (sourceHeightChanged && peer.layoutParams != null && sourceHeight > 0) {
                    if (layoutHeight(peer) != sourceHeight) {
                        peer.layoutParams.height = sourceHeight
                        touched = true
                        needsLayout = true
                    }
                }
                if (scaleXChanged && abs(peer.scaleX - profile.scaleX) > 0.001f) {
                    peer.scaleX = profile.scaleX
                    touched = true
                }
                if (scaleYChanged && abs(peer.scaleY - profile.scaleY) > 0.001f) {
                    peer.scaleY = profile.scaleY
                    touched = true
                }
                if (alphaChanged && abs(peer.alpha - profile.alpha) > 0.001f) {
                    peer.alpha = profile.alpha
                    touched = true
                }
                if (sourceBackgroundTintChanged && !sameColorState(peer.backgroundTintList, profile.backgroundTint)) {
                    peer.backgroundTintList = profile.backgroundTint
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
                if (sourceImageTintChanged && peer is ImageView &&
                    !sameColorState(peer.imageTintList, profile.imageTint)
                ) {
                    peer.imageTintList = profile.imageTint
                    touched = true
                }
                if (sourceIsText && peer is TextView) {
                    if (sourceTextChanged && peer.text?.toString() != profile.text?.toString()) {
                        peer.text = profile.text
                        touched = true
                        needsLayout = true
                    }
                    if (sourceTextColorChanged && !sameColorState(peer.textColors, profile.textColors)) {
                        profile.textColors?.let(peer::setTextColor)
                        touched = true
                    }
                    if (sourceTextSizeChanged && abs(peer.textSize - profile.textSizePx) > 0.5f) {
                        peer.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, profile.textSizePx)
                        touched = true
                        needsLayout = true
                    }
                }
                if (touched) {
                    if (needsLayout) peer.requestLayout()
                    if (sourceWidthChanged || sourceHeightChanged || abs(dx) > 0.05f || abs(dy) > 0.05f) {
                        applyVisualPriority(peer)
                    }
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
        view.translationZ = saved.translationZ
        view.elevation = saved.elevation
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
        releaseVisualPriority(view)
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
        restoreAllVisualPriority()
        entries.forEach { (view, state) -> runCatching { restoreSnapshot(view, state) } }
        return entries.size
    }
}
