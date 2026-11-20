package moe.ono.hooks.item.developer

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import moe.ono.config.ConfigManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.min

/**
 * Local-only library of controls encountered while scanning the current visible QQ view tree.
 * It stores a compact style sample, not a live View reference, click listener, or host model.
 */
internal object QQInspectorCollectedControls {
    private const val STORAGE_KEY = "tnt_qq_inspector_collected_controls_v1"
    private const val MAX_RECORDS = 220
    private const val MAX_PREVIEW_LENGTH = 30_000
    private const val MAX_STORE_LENGTH = 1_050_000

    private fun readArray(): JSONArray = runCatching {
        JSONArray(ConfigManager.getDefaultConfig().getStringOrDefault(STORAGE_KEY, "[]"))
    }.getOrDefault(JSONArray())

    private fun digest(value: String): String = runCatching {
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(24)
    }.getOrDefault(value.hashCode().toString(16))

    private fun resourceName(view: View): String = if (view.id != View.NO_ID) {
        runCatching { view.resources.getResourceName(view.id) }
            .getOrDefault("0x" + view.id.toString(16))
    } else "NO_ID"

    private fun parentName(view: View): String {
        val parent = view.parent as? ViewGroup ?: return "ROOT"
        val parentId = if (parent.id == View.NO_ID) "NO_ID" else resourceName(parent)
        return parent.javaClass.name + "/" + parentId
    }

    private fun visibleText(view: View): String? {
        val textView = view as? TextView ?: return null
        if (view is EditText || textView.transformationMethod is android.text.method.PasswordTransformationMethod) return null
        return runCatching { textView.text?.toString()?.take(240) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    private fun colorFromBackground(view: View): Int? =
        view.backgroundTintList?.defaultColor
            ?: (view.background as? ColorDrawable)?.color
            ?: (view.background as? GradientDrawable)?.color?.defaultColor

    private fun preview(view: View): String? = runCatching {
        val width = view.width.coerceIn(1, 900)
        val height = view.height.coerceIn(1, 900)
        val scale = min(1f, 144f / maxOf(width, height).toFloat())
        val outWidth = (width * scale).toInt().coerceAtLeast(1)
        val outHeight = (height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.scale(scale, scale)
        view.draw(canvas)
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 52, stream)
        bitmap.recycle()
        val bytes = stream.toByteArray()
        if (bytes.size > 22_000) return@runCatching null
        Base64.encodeToString(bytes, Base64.NO_WRAP)
    }.getOrNull()?.takeIf { it.length <= MAX_PREVIEW_LENGTH }

    private fun kindOf(view: View): String = when {
        view is ImageView || Regex("(image|picture|photo|avatar|thumbnail|\\bpic\\b)", RegexOption.IGNORE_CASE)
            .containsMatchIn(view.javaClass.name) -> "IMAGE"
        view is TextView -> "TEXT"
        view is ViewGroup -> "CONTAINER"
        else -> "CONTROL"
    }

    private fun toRecord(activity: Activity, node: InspectorNode, allowPreview: Boolean): JSONObject? {
        val view = node.view
        if (view.parent == null || !view.isAttachedToWindow || view.width <= 0 || view.height <= 0) return null
        val text = visibleText(view)
        val kind = if (view is ImageView || node.imageLinks.isNotEmpty()) "IMAGE" else kindOf(view)
        val previewBase64 = if (allowPreview) preview(view) else null
        val parent = parentName(view)
        val textColor = (view as? TextView)?.currentTextColor
        val textSize = (view as? TextView)?.textSize
        val backgroundColor = colorFromBackground(view)
        val identity = listOf(
            activity.javaClass.name,
            view.javaClass.name,
            resourceName(view),
            parent,
            kind,
            text.orEmpty(),
            previewBase64?.hashCode()?.toString().orEmpty()
        ).joinToString("|")
        val id = digest(identity)
        val now = System.currentTimeMillis()
        return JSONObject()
            .put("id", id)
            .put("activity", activity.javaClass.name)
            .put("class", view.javaClass.name)
            .put("resource", resourceName(view))
            .put("parent", parent)
            .put("kind", kind)
            .put("title", text?.take(48)?.replace('\n', ' ') ?: resourceName(view).substringAfterLast('/').takeIf { it != "NO_ID" } ?: view.javaClass.simpleName)
            .put("textContent", text.orEmpty())
            .put("hasText", text != null)
            .put("textColor", textColor ?: 0)
            .put("hasTextColor", textColor != null)
            .put("textSizePx", textSize ?: 0f)
            .put("backgroundColor", backgroundColor ?: 0)
            .put("hasBackgroundColor", backgroundColor != null)
            .put("backgroundClass", view.background?.javaClass?.name.orEmpty())
            .put("alpha", view.alpha.toDouble())
            .put("width", view.width)
            .put("height", view.height)
            .put("clickable", view.isClickable)
            .put("imageLinks", JSONArray(node.imageLinks))
            .put("links", JSONArray(node.directLinks))
            .put("json", JSONArray(node.cardJsons))
            .put("previewBase64", previewBase64.orEmpty())
            .put("firstSeen", now)
            .put("lastSeen", now)
            .put("seenCount", 1)
    }

    /** Merge current scan results into the local library. Returns the number of records touched. */
    @Synchronized
    fun capture(activity: Activity, nodes: List<InspectorNode>): Int {
        if (activity.isFinishing || activity.isDestroyed || nodes.isEmpty()) return 0
        if (Looper.myLooper() != Looper.getMainLooper()) {
            val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return 0
            root.post { capture(activity, nodes) }
            return 0
        }
        return runCatching {
            val existing = LinkedHashMap<String, JSONObject>()
            val old = readArray()
            for (i in 0 until old.length()) {
                val row = old.optJSONObject(i) ?: continue
                val id = row.optString("id")
                if (id.isNotBlank()) existing[id] = JSONObject(row.toString())
            }
            var touched = 0
            val previewViews = nodes
                .filter {
                    kindOf(it.view) == "IMAGE" ||
                        it.view.background != null ||
                        (it.view is TextView && it.view !is EditText && ((it.view as? ViewGroup)?.childCount ?: 0) == 0)
                }
                .sortedByDescending { it.area }
                .take(32)
                .map { it.view }
                .toSet()
            nodes.forEach { node ->
                val row = toRecord(activity, node, node.view in previewViews) ?: return@forEach
                val id = row.optString("id")
                val previous = existing.remove(id)
                if (previous != null) {
                    row.put("firstSeen", previous.optLong("firstSeen", row.optLong("firstSeen")))
                    row.put("seenCount", previous.optInt("seenCount", 0) + 1)
                    if (row.optString("previewBase64").isBlank()) {
                        row.put("previewBase64", previous.optString("previewBase64"))
                    }
                }
                existing[id] = row
                touched++
            }
            val rows = existing.values.toList().sortedByDescending { it.optLong("lastSeen") }.toMutableList()
            while (rows.size > MAX_RECORDS) rows.removeAt(rows.lastIndex)
            var encoded = JSONArray(rows).toString()
            while (encoded.length > MAX_STORE_LENGTH && rows.size > 20) {
                rows.removeAt(rows.lastIndex)
                encoded = JSONArray(rows).toString()
            }
            ConfigManager.getDefaultConfig().edit().putString(STORAGE_KEY, encoded).apply()
            touched
        }.getOrDefault(0)
    }

    @Synchronized
    fun all(): List<JSONObject> = runCatching {
        val array = readArray()
        (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let { row -> JSONObject(row.toString()) } }
            .sortedByDescending { it.optLong("lastSeen") }
    }.getOrDefault(emptyList())

    @Synchronized
    fun count(): Int = readArray().length()

    @Synchronized
    fun delete(id: String): Boolean {
        val old = readArray()
        val next = JSONArray()
        var removed = false
        for (i in 0 until old.length()) {
            val row = old.optJSONObject(i) ?: continue
            if (row.optString("id") == id) removed = true else next.put(row)
        }
        if (removed) ConfigManager.getDefaultConfig().edit().putString(STORAGE_KEY, next.toString()).apply()
        return removed
    }

    @Synchronized
    fun clear(): Int {
        val count = readArray().length()
        ConfigManager.getDefaultConfig().edit().putString(STORAGE_KEY, "[]").apply()
        return count
    }

    fun decodePreview(record: JSONObject): Bitmap? = runCatching {
        val encoded = record.optString("previewBase64")
        if (encoded.length !in 8..MAX_PREVIEW_LENGTH) null
        else {
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }.getOrNull()

    /** Apply a collected visual template to a currently selected compatible control. */
    fun applyTemplate(target: View, record: JSONObject): Int {
        if (target.parent == null) return 0
        var changed = 0
        QQInspectorGeometry.beginEditing(target)

        if (record.optBoolean("hasText") && target is TextView && target !is EditText) {
            if (QQInspectorGeometry.setText(target, record.optString("textContent").take(240))) changed++
        }
        if (record.optBoolean("hasTextColor") && target is TextView) {
            if (QQInspectorGeometry.setTextColor(target, record.optInt("textColor"))) changed++
        }
        if (record.optDouble("textSizePx", 0.0) > 0.0 && target is TextView) {
            val sp = (record.optDouble("textSizePx").toFloat() /
                target.resources.displayMetrics.scaledDensity).coerceIn(6f, 48f)
            if (QQInspectorGeometry.setTextSizeSp(target, sp)) changed++
        }
        if (record.optBoolean("hasBackgroundColor")) {
            if (QQInspectorGeometry.setBackgroundColor(target, record.optInt("backgroundColor"))) changed++
        }
        val alpha = record.optDouble("alpha", 1.0).toFloat().coerceIn(0.1f, 1f)
        if (kotlin.math.abs(target.alpha - alpha) > 0.001f && QQInspectorGeometry.setAlpha(target, alpha)) changed++

        if (target is ImageView && record.optString("kind") == "IMAGE") {
            val bitmap = decodePreview(record)
            if (bitmap != null && QQInspectorGeometry.setImageBitmap(target, bitmap)) changed++
        }
        QQInspectorGeometry.finishEditing(target)
        return changed
    }
}
