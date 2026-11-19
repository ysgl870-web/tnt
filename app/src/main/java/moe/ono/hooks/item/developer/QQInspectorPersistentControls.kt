package moe.ono.hooks.item.developer

import android.app.Activity
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import moe.ono.config.ConfigManager
import moe.ono.util.Logger
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Stores explicit visual edits and same-class-follow rules.
 *
 * Named controls are resolved by resource ID plus their parent hierarchy, not by a
 * brittle child index. A one-off edit is applied only when it resolves unambiguously;
 * a same-class-follow rule intentionally applies to every matching peer.
 *
 * The watcher retries after Activity resume and subsequent layout passes because QQ
 * frequently creates WebView/RecyclerView content asynchronously.
 */
internal object QQInspectorPersistentControls {
    private const val STORAGE_KEY = "tnt_qq_inspector_visual_rules_v1"
    private const val MAX_RULES = 120
    private const val MAX_NODES_PER_SCAN = 2200
    private const val MAX_DEPTH = 40
    private const val WATCH_THROTTLE_MS = 420L

    private data class Signature(
        val activity: String,
        val viewClass: String,
        val resource: String,
        val parentClass: String,
        val parentResource: String,
        val grandParentClass: String,
        val childIndex: Int
    ) {
        // Child index is a tie-breaker only, never the primary identity of a named View.
        fun key(): String = listOf(
            activity, viewClass, resource, parentClass, parentResource, grandParentClass
        ).joinToString("|")

        fun toJson(state: JSONObject, follow: Boolean) = JSONObject()
            .put("activity", activity)
            .put("class", viewClass)
            .put("resource", resource)
            .put("parentClass", parentClass)
            .put("parentResource", parentResource)
            .put("grandParentClass", grandParentClass)
            .put("childIndex", childIndex)
            .put("follow", follow)
            .put("state", state)
    }

    private data class Candidate(val view: View, val childIndex: Int)

    private data class Watcher(
        val layoutListener: ViewTreeObserver.OnGlobalLayoutListener,
        val attachListener: View.OnAttachStateChangeListener
    )

    private val watchers = WeakHashMap<ViewGroup, Watcher>()

    private fun resourceName(view: View): String = if (view.id != View.NO_ID) {
        runCatching { view.resources.getResourceName(view.id) }
            .getOrDefault("0x${view.id.toString(16)}")
    } else "NO_ID"

    private fun signature(activity: Activity, view: View): Signature? {
        val parent = view.parent as? ViewGroup ?: return null
        val grandParent = parent.parent as? View
        val index = parent.indexOfChild(view)
        if (index < 0) return null
        return Signature(
            activity = activity.javaClass.name,
            viewClass = view.javaClass.name,
            resource = resourceName(view),
            parentClass = parent.javaClass.name,
            parentResource = resourceName(parent),
            grandParentClass = grandParent?.javaClass?.name.orEmpty(),
            childIndex = index
        )
    }

    private fun readRules(): JSONArray = runCatching {
        JSONArray(ConfigManager.getDefaultConfig().getStringOrDefault(STORAGE_KEY, "[]"))
    }.getOrDefault(JSONArray())

    private fun signatureKey(item: JSONObject): String = listOf(
        item.optString("activity"),
        item.optString("class"),
        item.optString("resource"),
        item.optString("parentClass"),
        item.optString("parentResource"),
        item.optString("grandParentClass")
    ).joinToString("|")

    private fun isFollowRule(item: JSONObject): Boolean =
        item.optBoolean("follow", false)

    @Synchronized
    fun save(activity: Activity, view: View): Boolean = runCatching {
        val follow = QQInspectorGeometry.hasSameClassFollow(view)
        val signature = signature(activity, view) ?: return false
        // A one-off edit needs a resource ID. Follow can use an anonymous View only
        // with its parent structure and index as a conservative fallback.
        if (signature.resource == "NO_ID" && !follow) return false

        val state = QQInspectorGeometry.exportPersistentState(view) ?: JSONObject()
        val shouldStore = state.length() > 0 || follow
        val old = readRules()
        val next = JSONArray()
        for (index in 0 until old.length()) {
            val item = old.optJSONObject(index) ?: continue
            if (signatureKey(item) != signature.key()) next.put(item)
        }
        if (shouldStore) next.put(signature.toJson(state, follow))

        val trimmed = JSONArray()
        val start = (next.length() - MAX_RULES).coerceAtLeast(0)
        for (index in start until next.length()) next.optJSONObject(index)?.let(trimmed::put)
        ConfigManager.getDefaultConfig().edit().putString(STORAGE_KEY, trimmed.toString()).apply()

        val root = runCatching { activity.findViewById<ViewGroup>(android.R.id.content) }.getOrNull()
        if (root != null && trimmed.length() > 0) {
            ensureWatcher(activity, root)
            root.post { applyRulesToRoot(activity, root) }
        }
        true
    }.getOrElse {
        Logger.w("TNT visual rule save failed: " + it.javaClass.simpleName)
        false
    }

    @Synchronized
    fun clear(): Int {
        val previous = readRules().length()
        ConfigManager.getDefaultConfig().edit().putString(STORAGE_KEY, "[]").apply()
        watchers.keys.toList().forEach(::removeWatcher)
        return previous
    }

    @Synchronized
    fun apply(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val root = runCatching {
            activity.findViewById<ViewGroup>(android.R.id.content)
        }.getOrNull() ?: return
        val hasRules = readRules().let { all ->
            (0 until all.length()).any { all.optJSONObject(it)?.optString("activity") == activity.javaClass.name }
        }
        if (!hasRules) return

        ensureWatcher(activity, root)
        // First pass plus bounded retries cover QQ pages whose views are inflated later.
        root.post { applyRulesToRoot(activity, root) }
        listOf(160L, 520L, 1100L, 1800L).forEach { delay ->
            root.postDelayed({ applyRulesToRoot(activity, root) }, delay)
        }
    }

    private fun ensureWatcher(activity: Activity, root: ViewGroup) {
        if (watchers.containsKey(root)) return
        val activityRef = WeakReference(activity)
        val rootRef = WeakReference(root)
        var lastApplyAt = 0L

        fun applyIfAlive() {
            val currentActivity = activityRef.get() ?: return
            val currentRoot = rootRef.get() ?: return
            if (currentActivity.isFinishing || currentActivity.isDestroyed || !currentRoot.isAttachedToWindow) return
            applyRulesToRoot(currentActivity, currentRoot)
        }

        val layoutListener = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val now = SystemClock.uptimeMillis()
                if (now - lastApplyAt < WATCH_THROTTLE_MS) return
                lastApplyAt = now
                applyIfAlive()
            }
        }
        val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                val currentRoot = rootRef.get() ?: return
                currentRoot.viewTreeObserver.takeIf { it.isAlive }?.addOnGlobalLayoutListener(layoutListener)
                currentRoot.postDelayed({ applyIfAlive() }, 100L)
            }

            override fun onViewDetachedFromWindow(view: View) {
                val currentRoot = rootRef.get() ?: return
                runCatching {
                    currentRoot.viewTreeObserver.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(layoutListener)
                }
            }
        }

        watchers[root] = Watcher(layoutListener, attachListener)
        root.addOnAttachStateChangeListener(attachListener)
        if (root.isAttachedToWindow) {
            root.viewTreeObserver.takeIf { it.isAlive }?.addOnGlobalLayoutListener(layoutListener)
        }
    }

    private fun removeWatcher(root: ViewGroup) {
        val watcher = watchers.remove(root) ?: return
        runCatching {
            root.viewTreeObserver.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(watcher.layoutListener)
            root.removeOnAttachStateChangeListener(watcher.attachListener)
        }
    }

    private fun applyRulesToRoot(activity: Activity, root: ViewGroup) {
        if (activity.isFinishing || activity.isDestroyed || root.childCount == 0) return
        val rules = runCatching {
            val all = readRules()
            (0 until all.length()).mapNotNull { all.optJSONObject(it) }
                .filter { it.optString("activity") == activity.javaClass.name }
                .associateBy { signatureKey(it) }
        }.getOrDefault(emptyMap())
        if (rules.isEmpty()) return

        runCatching {
            val matches = HashMap<String, MutableList<Candidate>>()
            val needsAnonymousViews = rules.values.any { it.optString("resource") == "NO_ID" }
            var visited = 0
            fun walk(view: View, depth: Int) {
                if (visited >= MAX_NODES_PER_SCAN || depth > MAX_DEPTH) return
                visited++
                if (view !== root && (view.id != View.NO_ID || needsAnonymousViews)) {
                    if (view.javaClass.simpleName != "InspectorOverlay" &&
                        view.tag != "tnt.qq.inspector.entry"
                    ) {
                        val candidate = signature(activity, view)
                        if (candidate != null) {
                            val key = candidate.key()
                            if (rules.containsKey(key)) {
                                matches.getOrPut(key) { ArrayList() }
                                    .add(Candidate(view, candidate.childIndex))
                            }
                        }
                    }
                }
                if (view is ViewGroup) {
                    for (index in 0 until view.childCount) walk(view.getChildAt(index), depth + 1)
                }
            }
            walk(root, 0)

            rules.forEach { (key, rule) ->
                val candidates = matches[key].orEmpty()
                if (candidates.isEmpty()) return@forEach
                val state = rule.optJSONObject("state") ?: return@forEach
                val savedIndex = rule.optInt("childIndex", -1)
                if (isFollowRule(rule)) {
                    // Anonymous Views need the old index as an extra guard against false matches.
                    val followCandidates = if (rule.optString("resource") == "NO_ID") {
                        candidates.filter { it.childIndex == savedIndex }
                    } else candidates
                    // Follow is explicit fan-out: apply the saved delta to every same-class peer.
                    followCandidates.forEach { candidate ->
                        QQInspectorGeometry.applyPersistentState(candidate.view, state)
                    }
                } else {
                    val exact = candidates.filter { it.childIndex == savedIndex }
                    val unique = when {
                        exact.size == 1 -> exact.first()
                        candidates.size == 1 -> candidates.first()
                        else -> null
                    }
                    // A normal single-control edit must never spill into repeated list rows.
                    unique?.let { QQInspectorGeometry.applyPersistentState(it.view, state) }
                }
            }
        }.onFailure {
            Logger.w("TNT visual rules apply failed: " + it.javaClass.simpleName)
        }
    }
}
