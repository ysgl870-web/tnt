package moe.ono.hooks.item.developer

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import moe.ono.config.ConfigManager
import moe.ono.util.Logger
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stores user-edited visual properties for controls with a stable resource ID.
 * A rule is applied only when its structural signature resolves to exactly one View;
 * ambiguous repeated RecyclerView rows are intentionally skipped to avoid collateral edits.
 */
internal object QQInspectorPersistentControls {
    private const val STORAGE_KEY = "tnt_qq_inspector_visual_rules_v1"
    private const val MAX_RULES = 120

    private data class Signature(
        val activity: String,
        val viewClass: String,
        val resource: String,
        val parentClass: String,
        val parentResource: String,
        val grandParentClass: String,
        val childIndex: Int
    ) {
        fun key(): String = listOf(
            activity, viewClass, resource, parentClass, parentResource,
            grandParentClass, childIndex.toString()
        ).joinToString("|")

        fun toJson(state: JSONObject) = JSONObject()
            .put("activity", activity)
            .put("class", viewClass)
            .put("resource", resource)
            .put("parentClass", parentClass)
            .put("parentResource", parentResource)
            .put("grandParentClass", grandParentClass)
            .put("childIndex", childIndex)
            .put("state", state)
    }

    private fun resourceName(view: View): String = if (view.id != View.NO_ID) {
        runCatching { view.resources.getResourceName(view.id) }
            .getOrDefault("0x${view.id.toString(16)}")
    } else "NO_ID"

    private fun signature(activity: Activity, view: View): Signature? {
        if (view.id == View.NO_ID) return null
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
        item.optString("grandParentClass"),
        item.optInt("childIndex", -1).toString()
    ).joinToString("|")

    @Synchronized
    fun save(activity: Activity, view: View): Boolean = runCatching {
        val signature = signature(activity, view) ?: return false
        val state = QQInspectorGeometry.exportPersistentState(view)
        val old = readRules()
        val next = JSONArray()
        for (index in 0 until old.length()) {
            val item = old.optJSONObject(index) ?: continue
            if (signatureKey(item) != signature.key()) next.put(item)
        }
        if (state != null) next.put(signature.toJson(state))

        val trimmed = JSONArray()
        val start = (next.length() - MAX_RULES).coerceAtLeast(0)
        for (index in start until next.length()) next.optJSONObject(index)?.let(trimmed::put)
        ConfigManager.getDefaultConfig().edit().putString(STORAGE_KEY, trimmed.toString()).apply()
        true
    }.getOrElse {
        Logger.w("TNT visual rule save failed: " + it.javaClass.simpleName)
        false
    }

    @Synchronized
    fun clear(): Int {
        val previous = readRules().length()
        ConfigManager.getDefaultConfig().edit().putString(STORAGE_KEY, "[]").apply()
        return previous
    }

    fun apply(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val root = runCatching {
            activity.findViewById<ViewGroup>(android.R.id.content)
        }.getOrNull() ?: return
        val rules = runCatching {
            val all = readRules()
            (0 until all.length()).mapNotNull { all.optJSONObject(it) }
                .filter { it.optString("activity") == activity.javaClass.name }
                .associateBy { signatureKey(it) }
        }.getOrDefault(emptyMap())
        if (rules.isEmpty()) return

        root.post {
            if (activity.isFinishing || activity.isDestroyed || root.childCount == 0) return@post
            runCatching {
                val matches = HashMap<String, MutableList<View>>()
                var visited = 0
                fun walk(view: View, depth: Int) {
                    if (visited >= 1400 || depth > 36) return
                    visited++
                    if (view !== root && view.id != View.NO_ID && view.parent is ViewGroup &&
                        view.javaClass.simpleName != "InspectorOverlay" && view.tag != "tnt.qq.inspector.entry"
                    ) {
                        val parent = view.parent as ViewGroup
                        val sig = Signature(
                            activity = activity.javaClass.name,
                            viewClass = view.javaClass.name,
                            resource = resourceName(view),
                            parentClass = parent.javaClass.name,
                            parentResource = resourceName(parent),
                            grandParentClass = (parent.parent as? View)?.javaClass?.name.orEmpty(),
                            childIndex = parent.indexOfChild(view)
                        )
                        if (rules.containsKey(sig.key())) {
                            matches.getOrPut(sig.key()) { ArrayList(1) }.add(view)
                        }
                    }
                    if (view is ViewGroup) {
                        for (index in 0 until view.childCount) walk(view.getChildAt(index), depth + 1)
                    }
                }
                walk(root, 0)

                var applied = 0
                rules.forEach { (key, rule) ->
                    val candidates = matches[key].orEmpty()
                    // Multiple matches usually mean a repeated list row: do not fan out silently.
                    if (candidates.size != 1 || applied >= 120) return@forEach
                    val state = rule.optJSONObject("state") ?: return@forEach
                    QQInspectorGeometry.applyPersistentState(candidates[0], state)
                    applied++
                }
            }.onFailure {
                Logger.w("TNT visual rules apply failed: " + it.javaClass.simpleName)
            }
        }
    }
}
