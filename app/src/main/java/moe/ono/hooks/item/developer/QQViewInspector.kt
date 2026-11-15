package moe.ono.hooks.item.developer

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Spanned
import android.text.method.PasswordTransformationMethod
import android.text.style.URLSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import moe.ono.hooks._base.BaseClickableFunctionHookItem
import moe.ono.hooks._base.BaseSwitchFunctionHookItem
import moe.ono.hooks._core.annotation.HookItem
import moe.ono.config.ConfigManager
import moe.ono.constants.Constants
import moe.ono.util.ContextUtils
import moe.ono.util.HostInfo
import moe.ono.util.Logger
import moe.ono.util.SyncUtils
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.WeakHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * TNT view hierarchy inspector, implemented on the ONO hook framework.
 *
 * The inspector reads the current visible View tree of the user's own QQ process.
 * It never changes click listeners, dispatches clicks, reads input fields automatically,
 * captures screenshots, or transmits data to a server.
 */
@HookItem(
    path = "开发者选项/QQ界面控件探测器",
    description = "开启后在 QQ 页面显示「控件」入口；可绘制控件边框、按面积从小到大列出控件，并查看类名、资源ID、坐标、透明度和链接。读取文本需单独确认。"
)
class QQViewInspector : BaseSwitchFunctionHookItem() {

    override fun targetProcess(): Int {
        return SyncUtils.PROC_MAIN or SyncUtils.PROC_TOOL or
            SyncUtils.PROC_MINI or SyncUtils.PROC_WXA_CONTAINER
    }

    override fun entry(classLoader: ClassLoader) {
        QQInspectorRuntime.install()
        QQInspectorLinks.install(classLoader)
        runCatching { ContextUtils.getCurrentActivity()?.let { if (ConfigManager.getDefaultConfig().getBooleanOrFalse(Constants.PrekXXX + path)) QQInspectorRuntime.attach(it) } }
        Logger.i("TNT QQViewInspector installed")
    }
}

/**
 * A one-shot history viewer. Link capture is activated with QQ界面控件探测器.
 */
@HookItem(
    path = "开发者选项/查看QQ链接记录",
    description = "查看本机保存的最近100条QQ Scheme、Activity跳转和WebView页面链接；支持复制与清空。"
)
class QQInspectorLinkHistory : BaseClickableFunctionHookItem() {
    override fun entry(classLoader: ClassLoader) = Unit

    override fun onClick(context: Context) {
        val rows = QQInspectorLinks.readAll()
        val format = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
        val text = if (rows.isEmpty()) {
            "暂无链接记录。先开启「QQ界面控件探测器」，再正常浏览 QQ 页面。"
        } else {
            rows.asReversed().joinToString("\n\n") { row ->
                val time = format.format(Date(row.optLong("time")))
                "[$time] ${row.optString("kind")} · ${row.optString("source")}\n" +
                    row.optString("url")
            }
        }

        AlertDialog.Builder(context)
            .setTitle("QQ 链接记录（${rows.size}/100）")
            .setMessage(text)
            .setPositiveButton("复制全部") { _, _ ->
                copyText(context, "QQ 链接记录", text)
            }
            .setNeutralButton("清空") { _, _ ->
                QQInspectorLinks.clear()
                Toast.makeText(context, "本地链接记录已清空", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }
}

private fun copyText(context: Context, label: String, value: String) {
    runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
    }.onFailure {
        Toast.makeText(context, "复制失败", Toast.LENGTH_SHORT).show()
    }
}

private object QQInspectorRuntime {
    @Volatile private var installed = false
    private const val BUTTON_TAG = "tnt.qq.inspector.entry"

    fun install() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val app = runCatching { HostInfo.getApplication() }.getOrNull() ?: return
            try {
                app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                    override fun onActivityCreated(activity: Activity, state: Bundle?) {
                        if (isEnabled()) {
                            runCatching { QQInspectorLinks.captureActivity(activity) }
                                .onFailure { Logger.w("TNT Activity URL scan failed: " + it.javaClass.simpleName) }
                        }
                    }
                    override fun onActivityStarted(activity: Activity) = Unit
                    override fun onActivityResumed(activity: Activity) {
                        runCatching {
                            if (isEnabled()) {
                                QQInspectorLinks.captureActivity(activity)
                                InspectorControlState.applyPersistent(activity)
                                attach(activity)
                            } else {
                                detach(activity)
                            }
                        }.onFailure {
                            Logger.e("TNT inspector Activity resume failed", it)
                        }
                    }
                    override fun onActivityPaused(activity: Activity) = Unit
                    override fun onActivityStopped(activity: Activity) = Unit
                    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
                    override fun onActivityDestroyed(activity: Activity) = Unit
                })
                installed = true
            } catch (error: Throwable) {
                Logger.e("TNT inspector lifecycle registration failed", error)
            }
        }
    }

    private fun isEnabled(): Boolean = runCatching {
        ConfigManager.getDefaultConfig().getBooleanOrFalse(Constants.PrekXXX + "开发者选项/QQ界面控件探测器")
    }.getOrDefault(false)

    fun detach(activity: Activity) {
        // Keep user-applied changes when the probe is closed or disabled.
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        for (index in content.childCount - 1 downTo 0) {
            val child = content.getChildAt(index)
            if (child is InspectorOverlay || child.tag == BUTTON_TAG) content.removeViewAt(index)
        }
    }

    fun attach(activity: Activity) {
        if (!isEnabled() || activity.isFinishing || activity.isDestroyed) return
        val pkg = runCatching { activity.packageName }.getOrDefault("")
        if (pkg != "com.tencent.mobileqq" && pkg != "com.tencent.tim") return
        if (activity.javaClass.name.startsWith("moe.ono.")) return
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (content.findViewWithTag<View>(BUTTON_TAG) != null) return

        val button = InspectorEntryView(activity) {
            InspectorOverlay.open(activity)
        }.apply {
            tag = BUTTON_TAG
            contentDescription = "TNT 控件探测器，按住可拖动，点击打开"
            elevation = dp(activity, 12).toFloat()
            isClickable = true
            isFocusable = true
        }
        val size = dp(activity, 54)
        val lp = FrameLayout.LayoutParams(size, size, Gravity.END or Gravity.CENTER_VERTICAL).apply {
            marginEnd = dp(activity, 10)
        }
        content.addView(button, lp)
    }
}


private data class InspectorNode(
    val view: View,
    val className: String,
    val resourceId: String,
    val bounds: Rect,
    val width: Int,
    val height: Int,
    val area: Long,
    val depth: Int,
    val clickable: Boolean,
    val longClickable: Boolean,
    val focusable: Boolean,
    val enabled: Boolean,
    val alpha: Float,
    val childCount: Int,
    val directLinks: List<String>,
    val imageLinks: List<String>,
    val cardJsons: List<String>,
    val links: List<String>
)

private fun normalizeImageUrl(raw: String): String? = runCatching {
    val uri = Uri.parse(raw.trim().take(4096))
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    val host = uri.host?.lowercase(Locale.ROOT)
    if (scheme !in setOf("http", "https") || host.isNullOrBlank() || uri.userInfo != null) return null
    if (host == "localhost" || host.endsWith(".local")) return null
    uri.buildUpon().fragment(null).build().toString().take(4096)
}.getOrNull()

private object InspectorTree {
    private const val MAX_NODES = 420
    private const val MAX_DEPTH = 42

    fun collect(activity: Activity): List<InspectorNode> {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return emptyList()
        val result = ArrayList<InspectorNode>()
        val pageLinks = QQInspectorLinks.captureActivity(activity)
        val rootOnScreen = IntArray(2)
        root.getLocationOnScreen(rootOnScreen)

        fun visit(view: View, depth: Int) {
            if (result.size >= MAX_NODES || depth > MAX_DEPTH) return
            if (view is InspectorOverlay || view.tag == "tnt.qq.inspector.entry") return
            if (view.visibility != View.VISIBLE || view.alpha <= 0.03f || !view.isShown) return

            val visibleRect = Rect()
            val isVisible = view.getGlobalVisibleRect(visibleRect)
            val rect = if (isVisible && visibleRect.width() > 1 && visibleRect.height() > 1) {
                visibleRect
            } else {
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
            }

            if (rect.width() > 1 && rect.height() > 1) {
                val resourceId = if (view.id != View.NO_ID) {
                    runCatching { view.resources.getResourceName(view.id) }
                        .getOrDefault("0x${view.id.toString(16)}")
                } else {
                    "NO_ID"
                }
                val childCount = (view as? ViewGroup)?.childCount ?: 0
                val directLinks = collectLinks(view)
                val imageLinks = collectImageLinks(view)
                val cardJsons = collectCardJson(view)
                result.add(
                    InspectorNode(
                        view = view,
                        className = view.javaClass.name,
                        resourceId = resourceId,
                        bounds = rect,
                        width = rect.width(),
                        height = rect.height(),
                        area = rect.width().toLong() * rect.height().toLong(),
                        depth = depth,
                        clickable = view.isClickable,
                        longClickable = view.isLongClickable,
                        focusable = view.isFocusable,
                        enabled = view.isEnabled,
                        alpha = view.alpha,
                        childCount = childCount,
                        directLinks = directLinks,
                        imageLinks = imageLinks,
                        cardJsons = cardJsons,
                        links = (directLinks + pageLinks + imageLinks).distinct()
                    )
                )
            }

            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    visit(view.getChildAt(index), depth + 1)
                    if (result.size >= MAX_NODES) break
                }
            }
        }

        visit(root, 0)
        // Screenshot behavior: smallest controls appear first.
        return result.sortedWith(compareBy<InspectorNode> { it.area }.thenByDescending { it.depth })
    }

    /**
     * Best-effort extraction of image source URLs exposed by QQ's visible image views.
     * This reads only view metadata (tag/contentDescription and URL-like fields); it does
     * not read message databases, network traffic, cookies, request headers or image bytes.
     */
    private fun collectImageLinks(view: View): List<String> {
        val className = view.javaClass.name
        val imageLike = view is ImageView ||
            className.contains("image", ignoreCase = true) ||
            className.contains("picture", ignoreCase = true) ||
            className.contains("photo", ignoreCase = true) ||
            className.contains("pic", ignoreCase = true)
        if (!imageLike) return emptyList()

        val found = LinkedHashSet<String>()
        fun accept(value: Any?, source: String) {
            val raw = when (value) {
                is String -> value
                is Uri -> value.toString()
                else -> return
            }
            val candidate = raw.trim()
            if (candidate.length !in 8..4096) return
            val safe = normalizeImageUrl(candidate) ?: return
            val uri = runCatching { Uri.parse(safe) }.getOrNull() ?: return
            if (uri.scheme !in setOf("http", "https")) return
            val host = uri.host.orEmpty().lowercase(Locale.ROOT)
            val path = uri.path.orEmpty().lowercase(Locale.ROOT)
            val imageHint = path.matches(Regex(".*\\.(png|jpe?g|gif|webp|bmp|heic|avif)(?:$|/).*")) ||
                host.contains("qpic") || host.contains("qlogo") || host.contains("gtimg") ||
                host.contains("qq.com") || host.contains("qcloud") ||
                Regex("(image|img|pic|photo|thumb|origin|url|src)", RegexOption.IGNORE_CASE).containsMatchIn(source)
            if (imageHint && found.add(safe)) {
                // Keep the original signed query string; stripping it can invalidate CDN image URLs.
                Unit
            }
        }

        accept(view.contentDescription?.toString(), "contentDescription")
        accept(view.tag, "tag")
        if (view is ImageView) {
            val drawable = view.drawable
            accept(drawable?.toString(), "drawable")
            if (drawable != null) {
                drawable.javaClass.methods.filter { it.parameterTypes.isEmpty() &&
                    Regex("(uri|url|source|path)", RegexOption.IGNORE_CASE).containsMatchIn(it.name) }
                    .take(12).forEach { method -> runCatching { accept(method.invoke(drawable), "drawable.${method.name}") } }
                var drawableType: Class<*>? = drawable.javaClass
                var depth = 0
                while (drawableType != null && drawableType != Any::class.java && depth++ < 3) {
                    drawableType.declaredFields.take(40).forEach { field ->
                        if (Regex("(uri|url|source|path|image)", RegexOption.IGNORE_CASE).containsMatchIn(field.name))
                            runCatching { field.isAccessible = true; accept(field.get(drawable), "drawable.${field.name}") }
                    }
                    drawableType = drawableType.superclass
                }
            }
        }

        // QQ's message/image widgets sometimes retain their loader model on the view.
        // Inspect only a small set of URL-like String fields; never recurse into arbitrary objects.
        var type: Class<*>? = view.javaClass
        var inspected = 0
        while (type != null && type != View::class.java && inspected < 4) {
            type.declaredFields.take(80).forEach { field ->
                val name = field.name
                if (!Regex("(url|uri|image|img|pic|photo|origin|source|thumb|path|model|data)", RegexOption.IGNORE_CASE)
                        .containsMatchIn(name)) return@forEach
                runCatching {
                    field.isAccessible = true
                    val value = field.get(view)
                    accept(value, name)
                    if (value != null && value !is String && value !is Number && value !is Boolean) {
                        value.javaClass.methods.filter { it.parameterTypes.isEmpty() &&
                            Regex("(url|uri|image|img|pic|photo|origin|source|thumb|path)", RegexOption.IGNORE_CASE).containsMatchIn(it.name) }
                            .take(16).forEach { getter -> runCatching { accept(getter.invoke(value), "$name.${getter.name}") } }
                        value.javaClass.declaredFields.filter { Regex("(url|uri|image|img|pic|photo|origin|source|thumb|path)", RegexOption.IGNORE_CASE).containsMatchIn(it.name) }
                            .take(24).forEach { nested -> runCatching { nested.isAccessible = true; accept(nested.get(value), "$name.${nested.name}") } }
                    }
                }
            }
            type = type.superclass
            inspected++
        }
        return found.toList()
    }

    private fun collectCardJson(view: View): List<String> {
        val className = view.javaClass.name
        val cardLike = Regex("(ark|card|struct|json|share)", RegexOption.IGNORE_CASE).containsMatchIn(className)
        val found = LinkedHashSet<String>()
        fun accept(value: Any?) {
            val raw = when (value) {
                is String -> value
                is JSONObject, is JSONArray -> value.toString()
                else -> return
            }.trim()
            if (raw.length !in 2..128_000) return
            val parsed = runCatching {
                val unwrapped = if (raw.startsWith("\"") && raw.endsWith("\"")) org.json.JSONTokener(raw).nextValue()?.toString() ?: raw else raw
                when {
                    unwrapped.startsWith("{") -> JSONObject(unwrapped).toString(2)
                    unwrapped.startsWith("[") -> JSONArray(unwrapped).toString(2)
                    else -> null
                }
            }.getOrNull() ?: return
            if (cardLike || Regex("(ark|app|meta|prompt|view|config|jumpUrl|qqdocurl|schema)", RegexOption.IGNORE_CASE).containsMatchIn(parsed)) found.add(parsed)
        }
        if (view is TextView && !view.text.isNullOrBlank()) accept(view.text.toString())
        accept(view.tag)
        accept(view.contentDescription)
        var type: Class<*>? = view.javaClass
        var depth = 0
        while (type != null && type != View::class.java && depth++ < 4) {
            type.declaredFields.filter { Regex("(ark|card|json|payload|extra|meta|struct|share)", RegexOption.IGNORE_CASE).containsMatchIn(it.name) }
                .take(48).forEach { field ->
                    runCatching {
                        field.isAccessible = true
                        val value = field.get(view)
                        accept(value)
                        if (value != null && value !is String && value !is Number && value !is Boolean) {
                            value.javaClass.declaredFields.filter { Regex("(ark|card|json|payload|extra|meta|struct|share)", RegexOption.IGNORE_CASE).containsMatchIn(it.name) }
                                .take(24).forEach { nested -> runCatching { nested.isAccessible = true; accept(nested.get(value)) } }
                            value.javaClass.methods.filter { it.parameterTypes.isEmpty() && Regex("(ark|card|json|payload|extra|meta|struct|share)", RegexOption.IGNORE_CASE).containsMatchIn(it.name) }
                                .take(12).forEach { getter -> runCatching { accept(getter.invoke(value)) } }
                        }
                    }
                }
            type = type.superclass
        }
        return found.take(6)
    }

    private fun collectLinks(view: View): List<String> {
        val found = LinkedHashSet<String>()
        if (view is TextView && view !is EditText) {
            val value = view.text
            if (value is Spanned) {
                value.getSpans(0, value.length, URLSpan::class.java).forEach { span ->
                    QQInspectorLinks.add("TEXT_LINK", span.url, view.javaClass.simpleName)
                    QQInspectorLinks.sanitize(span.url)?.let(found::add)
                }
            }
        }

        val className = view.javaClass.name
        val webViewLike = view is WebView ||
            className.contains("webview", ignoreCase = true) ||
            className.contains("x5", ignoreCase = true)
        if (webViewLike) {
            listOf("getUrl", "getOriginalUrl", "getCurrentUrl", "getWebUrl").forEach { methodName ->
                val raw = runCatching {
                    view.javaClass.methods.firstOrNull {
                        it.name == methodName && it.parameterTypes.isEmpty()
                    }?.invoke(view)?.toString()
                }.getOrNull()
                if (!raw.isNullOrBlank()) {
                    QQInspectorLinks.add("WEBVIEW_CURRENT", raw, className + "." + methodName)
                    QQInspectorLinks.sanitize(raw)?.let(found::add)
                }
            }
        }
        return found.toList()
    }

}

/**
 * Reversible UI controls. Changes remain in place when the probe closes or is disabled;
 * the explicit restore action is the only normal way to undo them.
 */
private object InspectorControlState {
    private data class SavedState(
        val visibility: Int,
        val enabled: Boolean,
        val clickable: Boolean,
        val longClickable: Boolean,
        val parent: ViewGroup?,
        val index: Int,
        val layoutParams: ViewGroup.LayoutParams?
    )

    private val saved = WeakHashMap<View, SavedState>()
    private const val RULES_KEY = "tnt_qq_inspector_control_rules_v1"

    private fun remember(view: View) {
        if (!saved.containsKey(view)) {
            saved[view] = SavedState(
                visibility = view.visibility,
                enabled = view.isEnabled,
                clickable = view.isClickable,
                longClickable = view.isLongClickable,
                parent = view.parent as? ViewGroup,
                index = (view.parent as? ViewGroup)?.indexOfChild(view) ?: -1,
                layoutParams = view.layoutParams
            )
        }
    }

    private fun resourceName(view: View): String = if (view.id != View.NO_ID) {
        runCatching { view.resources.getResourceName(view.id) }.getOrDefault("0x${view.id.toString(16)}")
    } else "NO_ID"

    @Synchronized
    fun addPersistentRule(activity: Activity, view: View, mode: String): Boolean {
        if (view.id == View.NO_ID || mode !in setOf("hide", "disable")) return false
        return runCatching {
            val config = ConfigManager.getDefaultConfig()
            val array = JSONArray(config.getStringOrDefault(RULES_KEY, "[]"))
            val activityName = activity.javaClass.name
            val className = view.javaClass.name
            val resource = resourceName(view)
            val next = JSONArray()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                if (item.optString("activity") == activityName &&
                    item.optString("class") == className &&
                    item.optString("resource") == resource) continue
                next.put(item)
            }
            next.put(JSONObject().put("activity", activityName).put("class", className)
                .put("resource", resource).put("mode", mode))
            config.edit().putString(RULES_KEY, next.toString()).apply()
            true
        }.getOrDefault(false)
    }

    fun clearPersistentRules(): Int {
        val config = ConfigManager.getDefaultConfig()
        val count = runCatching { JSONArray(config.getStringOrDefault(RULES_KEY, "[]")).length() }.getOrDefault(0)
        config.edit().putString(RULES_KEY, "[]").apply()
        restoreAll()
        return count
    }

    fun applyPersistent(activity: Activity) {
        runCatching {
            val array = JSONArray(ConfigManager.getDefaultConfig().getStringOrDefault(RULES_KEY, "[]"))
            if (array.length() == 0) return
            val rules = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
                .filter { it.optString("activity") == activity.javaClass.name }
            if (rules.isEmpty()) return
            val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
            fun walk(view: View) {
                val resource = resourceName(view)
                val rule = rules.firstOrNull {
                    it.optString("class") == view.javaClass.name && it.optString("resource") == resource
                }
                if (rule != null) {
                    when (rule.optString("mode")) {
                        "hide", "remove" -> hide(view)
                        "disable" -> disable(view)
                    }
                }
                if (view is ViewGroup) {
                    for (i in view.childCount - 1 downTo 0) walk(view.getChildAt(i))
                }
            }
            walk(root)
        }.onFailure { Logger.w("TNT persistent control rules failed: " + it.javaClass.simpleName) }
    }

    @Synchronized
    fun hide(view: View): Boolean = runCatching {
        if (view.parent == null) return false
        remember(view)
        view.visibility = View.GONE
        true
    }.getOrDefault(false)

    @Synchronized
    fun remove(view: View): Boolean = runCatching {
        val parent = view.parent as? ViewGroup ?: return false
        remember(view)
        parent.removeView(view)
        true
    }.getOrDefault(false)

    @Synchronized
    fun disable(view: View): Boolean = runCatching {
        if (view.parent == null) return false
        remember(view)
        view.isEnabled = false
        view.isClickable = false
        view.isLongClickable = false
        true
    }.getOrDefault(false)

    @Synchronized
    fun restoreAll(): Int {
        val entries = saved.entries.map { it.key to it.value }
        saved.clear()
        entries.forEach { (view, state) ->
            runCatching {
                if (view.parent == null && state.parent != null && state.index >= 0) {
                    state.parent.addView(view, state.index.coerceAtMost(state.parent.childCount), state.layoutParams)
                }
                view.visibility = state.visibility
                view.isEnabled = state.enabled
                view.isClickable = state.clickable
                view.isLongClickable = state.longClickable
            }
        }
        return entries.size
    }
}

/**
 * Draggable TNT launcher. A short tap opens the inspector; a drag only moves the
 * floating launcher and never activates the underlying QQ view.
 */
private class InspectorEntryView(
    context: Context,
    private val onClick: () -> Unit
) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private var downRawX = 0f
    private var downRawY = 0f
    private var startTranslationX = 0f
    private var startTranslationY = 0f
    private var dragged = false

    init {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val inset = 2.5f * density
        val bounds = RectF(inset, inset, w - inset, h - inset)

        // ONO-inspired midnight-blue glass badge with cyan edge lighting.
        paint.style = Paint.Style.FILL
        paint.shader = android.graphics.LinearGradient(
            0f, 0f, w, h,
            intArrayOf(0xFF142D55.toInt(), 0xFF075A9D.toInt(), 0xFF102447.toInt()),
            null, android.graphics.Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(bounds, 17f * density, 17f * density, paint)
        paint.shader = null

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * density
        paint.color = 0xFF64D8FF.toInt()
        canvas.drawRoundRect(bounds, 17f * density, 17f * density, paint)

        // Subtle orbital ring and corner glints.
        paint.strokeWidth = 1.2f * density
        paint.color = 0x8869D9FF.toInt()
        canvas.drawCircle(w * .5f, h * .48f, 18f * density, paint)
        paint.style = Paint.Style.FILL
        paint.color = 0xFFBDEFFF.toInt()
        canvas.drawCircle(w * .79f, h * .22f, 2.1f * density, paint)

        paint.color = 0xFFFFFFFF.toInt()
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD)
        paint.textSize = 17f * density
        paint.setShadowLayer(4f * density, 0f, 1f * density, 0xAA55CFFF.toInt())
        val baseline = h * .57f - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText("TNT", w * .5f, baseline, paint)
        paint.clearShadowLayer()

        paint.color = 0xFF73DFFF.toInt()
        val dotY = h * .79f
        canvas.drawCircle(w * .38f, dotY, 1.5f * density, paint)
        canvas.drawCircle(w * .5f, dotY, 1.5f * density, paint)
        canvas.drawCircle(w * .62f, dotY, 1.5f * density, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val parentView = parent as? View ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                startTranslationX = translationX
                startTranslationY = translationY
                dragged = false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragged && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
                    dragged = true
                }
                if (dragged) {
                    val minX = -left.toFloat()
                    val maxX = (parentView.width - right).toFloat()
                    val minY = -top.toFloat()
                    val maxY = (parentView.height - bottom).toFloat()
                    translationX = (startTranslationX + dx).coerceIn(minX, maxX)
                    translationY = (startTranslationY + dy).coerceIn(minY, maxY)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (!dragged) performClick() else {
                    // Snap gently inside the visible content bounds.
                    val minX = -left.toFloat()
                    val maxX = (parentView.width - right).toFloat()
                    val minY = -top.toFloat()
                    val maxY = (parentView.height - bottom).toFloat()
                    translationX = translationX.coerceIn(minX, maxX)
                    translationY = translationY.coerceIn(minY, maxY)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                dragged = false
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        onClick()
        return true
    }
}

private class InspectorOverlay private constructor(
    private val activity: Activity,
    private val root: ViewGroup,
    private var nodes: List<InspectorNode>
) : FrameLayout(activity) {

    private val boxes = InspectorBoxLayer(activity)
    private val toolbar = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 8), dp(activity, 8))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(activity, 14).toFloat()
            setColor(0xF0182740.toInt())
            setStroke(dp(activity, 1), 0x6658BDEB)
        }
        elevation = dp(activity, 12).toFloat()
    }
    private val toolbarScroll = HorizontalScrollView(activity).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(toolbar, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private var bordersVisible = true
    private var pickMode = false
    private var multiSelect = false
    private val selectedViews = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<View, Boolean>())

    init {
        // Transparent overlay: never tint the entire QQ page.
        setBackgroundColor(0x00000000)
        isClickable = false
        isFocusable = false
        addView(boxes, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val toolbarParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP)
        val rootLocation = IntArray(2)
        root.getLocationOnScreen(rootLocation)
        val statusBarId = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBarHeight = if (statusBarId > 0) {
            runCatching { activity.resources.getDimensionPixelSize(statusBarId) }.getOrDefault(0)
        } else 0
        // Some QQ pages are edge-to-edge; offset the toolbar only when content begins behind the status bar.
        toolbarParams.topMargin = (statusBarHeight - rootLocation[1]).coerceAtLeast(0)
        addView(toolbarScroll, toolbarParams)
        boxes.setNodes(nodes)
        boxes.setPickMode(false)
        boxes.setOnSelectionChanged { views ->
            selectedViews.clear()
            selectedViews.addAll(views)
            buildToolbar()
        }
        boxes.setOnNodeSelected { selected ->
            if (activity.isFinishing || activity.isDestroyed) return@setOnNodeSelected
            boxes.setSelectedViews(listOf(selected.view))
            activity.runOnUiThread {
                runSafely {
                    if (selected.view.isAttachedToWindow || selected.view.parent != null) {
                        showNode(selected)
                    } else {
                        showInspectorToast("该控件已离开当前页面，请刷新控件列表")
                    }
                }
            }
        }
        buildToolbar()
    }

    private fun buildToolbar() {
        toolbar.removeAllViews()
        toolbar.addView(toolButton("控件列表 ${nodes.size}") { showList() }, buttonParams())
        toolbar.addView(toolButton(if (pickMode) "点选:开" else "点选:关") { togglePickMode() }, buttonParams())
        toolbar.addView(toolButton(if (multiSelect) "多选:开 ${selectedViews.size}" else "多选:关") { toggleMultiSelect() }, buttonParams())
        if (selectedViews.isNotEmpty()) toolbar.addView(toolButton("批量操作(${selectedViews.size})") { showBatchActions() }, buttonParams())
        toolbar.addView(toolButton("刷新") { refresh() }, buttonParams())
        toolbar.addView(toolButton(if (bordersVisible) "边框:开" else "边框:关") { toggleBorders() }, buttonParams())
        toolbar.addView(toolButton("恢复控件") { restoreControls() }, buttonParams())
        toolbar.addView(toolButton("清除持续规则") { clearPersistentRules() }, buttonParams())
        toolbar.addView(toolButton("保存全部链接") { saveCurrentLinks() }, buttonParams())
        toolbar.addView(toolButton("链接记录") { showLinkHistory() }, buttonParams())
        toolbar.addView(toolButton("复制结构") { copyTree() }, buttonParams())
        toolbar.addView(toolButton("停用探测") { disableFeature() }, buttonParams())
        toolbar.addView(toolButton("关闭") { close() }, buttonParams())
    }

    private fun buttonParams() = LinearLayout.LayoutParams(dp(activity, 82), dp(activity, 42)).apply {
        marginEnd = dp(activity, 4)
    }

    private fun showInspectorToast(message: String) {
        val rootView = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val toastView = TextView(activity).apply {
            text = "$message  ×"
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(activity, 18), dp(activity, 11), dp(activity, 18), dp(activity, 11))
            background = GradientDrawable().apply { cornerRadius = dp(activity, 22).toFloat(); setColor(0xEE172B43.toInt()); setStroke(dp(activity, 1), 0xFF55BDEB.toInt()) }
            elevation = dp(activity, 10).toFloat()
            setOnClickListener { rootView.removeView(this) }
        }
        val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(activity, 36) }
        rootView.addView(toastView, params)
        toastView.postDelayed({ runCatching { (toastView.parent as? ViewGroup)?.removeView(toastView) } }, 1500L)
    }

    private fun runSafely(action: () -> Unit) {
        try {
            if (activity.isFinishing || activity.isDestroyed) return
            action()
        } catch (error: Throwable) {
            Logger.e("TNT inspector action failed: " + error.message, error)
            val detail = error.message?.take(100)
            val message = if (detail.isNullOrBlank()) error.javaClass.simpleName
                else "${error.javaClass.simpleName}: $detail"
            showInspectorToast("控件操作失败：$message")
        }
    }

    private fun toolButton(label: String, action: () -> Unit) = TextView(activity).apply {
        text = label
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(0xFFFFFFFF.toInt())
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(activity, 10).toFloat()
            setColor(0xFF087DBB.toInt())
            setStroke(dp(activity, 1), 0x665DD7FF)
        }
        setPadding(dp(activity, 5), 0, dp(activity, 5), 0)
        isClickable = true
        isFocusable = true
        setOnClickListener { runSafely(action) }
    }

    private fun toggleMultiSelect() {
        multiSelect = !multiSelect
        boxes.setMultiSelect(multiSelect)
        if (multiSelect) { pickMode = true; boxes.setPickMode(true) }
        buildToolbar()
        showInspectorToast(if (multiSelect) "多选已开启，橙色边框为已选控件" else "多选已关闭")
    }

    private fun showBatchActions() {
        val targets = nodes.filter { it.view in selectedViews && canModify(it) }.distinctBy { it.view }
        if (targets.isEmpty()) return
        AlertDialog.Builder(activity).setTitle("批量操作 · ${targets.size} 个控件")
            .setItems(arrayOf("批量隐藏", "批量移除（可恢复）", "批量禁用", "取消选择")) { _, which ->
                when (which) {
                    0 -> targets.forEach { InspectorControlState.hide(it.view) }
                    1 -> targets.forEach { InspectorControlState.remove(it.view) }
                    2 -> targets.forEach { InspectorControlState.disable(it.view) }
                    3 -> { selectedViews.clear(); boxes.setSelectedViews(emptyList()); buildToolbar(); return@setItems }
                }
                val count = targets.size
                selectedViews.clear(); boxes.setSelectedViews(emptyList()); refresh()
                showInspectorToast("已处理 ${count} 个控件，可使用恢复控件还原")
            }.show()
    }

    private fun togglePickMode() {
        pickMode = !pickMode
        boxes.setPickMode(pickMode)
        if (!pickMode && multiSelect) {
            multiSelect = false
            boxes.setMultiSelect(false)
        }
        buildToolbar()
        Toast.makeText(
            activity,
            if (pickMode) "点选模式已开启：点蓝色控件查看详情" else "点选模式已关闭：QQ 页面点击恢复正常",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun toggleBorders() {
        bordersVisible = !bordersVisible
        boxes.visibility = if (bordersVisible) View.VISIBLE else View.GONE
        if (!bordersVisible && pickMode) {
            pickMode = false
            boxes.setPickMode(false)
        }
        buildToolbar()
    }

    private fun restoreControls() {
        val rules = InspectorControlState.clearPersistentRules()
        val count = InspectorControlState.restoreAll()
        refresh()
        showInspectorToast("已恢复隐藏/移除/禁用控件（本次 $count 个，持续规则 $rules 条）")
    }

    private fun clearPersistentRules() {
        val count = InspectorControlState.clearPersistentRules()
        refresh()
        showInspectorToast("已清除 $count 条持续规则并恢复当前控件")
    }

    private fun canModify(node: InspectorNode): Boolean {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return false
        if (node.view === content || node.view.parent == null) return false
        if (node.view is InspectorOverlay || node.view.tag == QQInspectorRuntimeEntryTag) return false
        val totalArea = content.width.toLong() * content.height.toLong()
        if (totalArea > 0 && node.area.toDouble() > totalArea.toDouble() * 0.35) return false
        return true
    }

    private fun refresh() {
        runCatching {
            QQInspectorLinks.captureActivity(activity)
            InspectorTree.collect(activity)
        }.onSuccess { refreshed ->
            nodes = refreshed
            boxes.setNodes(nodes)
            buildToolbar()
            showInspectorToast("已重新扫描 ${nodes.size} 个控件")
        }.onFailure {
            Logger.e("TNT inspector refresh failed", it)
            showInspectorToast("扫描失败：${it.javaClass.simpleName}")
        }
    }

    private fun saveCurrentLinks() {
        val activityLinks = QQInspectorLinks.captureActivity(activity)
        val visibleNodes = InspectorTree.collect(activity)
        val discovered = (activityLinks + visibleNodes.flatMap { it.links }).distinct()
        if (discovered.isEmpty()) {
            showInspectorToast("当前 Activity 暂未发现 Intent 或 WebView 链接")
            return
        }
        discovered.forEach { QQInspectorLinks.add("MANUAL_SAVE", it, "手动保存当前页面") }
        val total = QQInspectorLinks.readAll().size
        showInspectorToast("已保存 ${discovered.size} 个页面链接，历史共 ${total} 条")
    }

    private fun showLinkHistory() {
        val rows = QQInspectorLinks.readAll()
        if (rows.isEmpty()) {
            AlertDialog.Builder(activity)
                .setTitle("QQ 链接记录")
                .setMessage("暂无记录。开启探测器后浏览 QQ 页面，链接会自动保存；也可手动保存当前页面全部链接。")
                .setPositiveButton("知道了", null)
                .show()
            return
        }
        val labels = rows.asReversed().mapIndexed { index, row ->
            val date = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(row.optLong("time")))
            "$date · ${row.optString("kind")} · ${row.optString("source")}\n${row.optString("url")}"
        }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("链接历史（${rows.size}/100）")
            .setItems(labels) { _, which ->
                val row = rows.asReversed().getOrNull(which) ?: return@setItems
                val url = row.optString("url")
                AlertDialog.Builder(activity)
                    .setTitle("链接详情")
                    .setMessage("触发位置：${row.optString("source")}\n类型：${row.optString("kind")}\n\n$url")
                    .setPositiveButton("复制") { _, _ -> copyText(activity, "QQ链接", url) }
                    .setNeutralButton("跳转") { _, _ -> confirmHistoryOpen(url) }
                    .setNegativeButton("关闭", null)
                    .show()
            }
            .setNeutralButton("清空全部") { _, _ ->
                QQInspectorLinks.clear()
                showInspectorToast("链接记录已清空")
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun confirmHistoryOpen(url: String) {
        AlertDialog.Builder(activity)
            .setTitle("确认跳转？")
            .setMessage("即将打开以下链接：\\n$url\\n\\n请确认链接来源可信。")
            .setPositiveButton("确认跳转") { _, _ ->
                runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    .onFailure { showInspectorToast("无法打开链接：${it.javaClass.simpleName}") }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showList() {
        val shownNodes = nodes.take(160)
        val labels = shownNodes.mapIndexed { index, node ->
            "${index + 1}. ${node.className.substringAfterLast('.')}\n" +
                "${node.resourceId} · ${node.width}×${node.height} · ${node.area}px²" +
                if (node.directLinks.isNotEmpty()) "\n🔗 控件链接：${node.directLinks.first()}"
                else if (node.links.isNotEmpty()) "\n🌐 页面链接：${node.links.first()}" else ""
        }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("控件列表（共 ${nodes.size} 个，显示前 ${shownNodes.size} 个）")
            .setItems(labels) { _, which -> shownNodes.getOrNull(which)?.let { runSafely { showNode(it) } } }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showNode(node: InspectorNode) {
        // Merge image and control views that occupy substantially the same visible rectangle.
        // QQ often wraps an image in a clickable message/card container; selecting either layer
        // should expose both records instead of making the user guess which nested view to tap.
        val peers = nodes.filter { candidate ->
            val left = max(node.bounds.left, candidate.bounds.left)
            val top = max(node.bounds.top, candidate.bounds.top)
            val right = min(node.bounds.right, candidate.bounds.right)
            val bottom = min(node.bounds.bottom, candidate.bounds.bottom)
            val intersection = (right - left).coerceAtLeast(0).toLong() *
                (bottom - top).coerceAtLeast(0).toLong()
            val smaller = min(node.area, candidate.area).coerceAtLeast(1L)
            candidate === node || intersection.toDouble() / smaller.toDouble() >= 0.78
        }.distinctBy { it.view }
        val controlLinks = peers.flatMap { it.directLinks }.distinct()
        val imageLinks = peers.flatMap { it.imageLinks }.distinct()
        val cardJsons = peers.flatMap { it.cardJsons }.distinct()
        val pageLinks = peers.flatMap { peer ->
            peer.links.filterNot { it in peer.directLinks || it in peer.imageLinks }
        }.distinct()
        val links = (controlLinks + pageLinks).distinct()
        val message = buildString {
            append(if (peers.any { it.view is ImageView || it.imageLinks.isNotEmpty() }) "图片 + 控件合并详情" else "控件详情")
            append("\n选中视图：").append(node.className)
            append("\n资源 ID：").append(node.resourceId)
            if (peers.size > 1) {
                append("\n\n同一框范围内合并了 ").append(peers.size).append(" 个视图：")
                peers.forEachIndexed { index, peer ->
                    append("\n").append(index + 1).append(". ")
                        .append(peer.className.substringAfterLast('.'))
                        .append(" · ").append(peer.width).append("×").append(peer.height)
                        .append(if (peer.view is ImageView || peer.imageLinks.isNotEmpty()) " · 图片" else " · 控件")
                }
            }
            append("\n屏幕坐标：").append(node.bounds.left).append(",")
                .append(node.bounds.top).append(" - ")
                .append(node.bounds.right).append(",").append(node.bounds.bottom)
            append("\n尺寸：").append(node.width).append(" × ").append(node.height)
            append("\n面积：").append(node.area).append(" px²")
            append("\n层级深度：").append(node.depth)
            append("\n透明度：").append(node.alpha)
            append("\n可点击：").append(node.clickable)
            append(" · 长按：").append(node.longClickable)
            append("\n可聚焦：").append(node.focusable)
            append(" · 启用：").append(node.enabled)
            append("\n子控件数：").append(node.childCount)
            append("\nviewInfo：").append(node.view.toString().take(900))
            append("\n\n控件自身链接：")
            append(if (controlLinks.isEmpty()) "无" else "\n" + controlLinks.take(8).joinToString("\n"))
            append("\n\n当前 Activity / WebView 页面链接：")
            append(if (pageLinks.isEmpty()) "无" else "\n" + pageLinks.take(8).joinToString("\n"))
            if (imageLinks.isNotEmpty()) {
                append("\n\n图片直链：\n").append(imageLinks.take(12).joinToString("\n"))
            } else if (peers.any { it.view is ImageView }) {
                append("\n\n图片：已识别图片控件，但 QQ 未向当前视图暴露可用直链。")
            }
            if (cardJsons.isNotEmpty()) {
                append("\n\nArk / 卡片 JSON：已识别 ").append(cardJsons.size).append(" 份结构数据")
                append("\n").append(cardJsons.first().take(1800))
            } else append("\n\nArk / 卡片 JSON：当前视图未暴露可识别的卡片结构")
            append("\n\n控件文本默认隐藏；仅在你明确确认后读取。')
        }

        val actions = mutableListOf("复制控件信息")
        if (cardJsons.isNotEmpty()) {
            actions.add("复制 Ark 卡片 JSON")
            if (cardJsons.size > 1) cardJsons.indices.forEach { actions.add("复制卡片 JSON ${it + 1}") }
        }
        if (peers.size > 1) actions.add("选择同一图层全部控件")
        if (canModify(node)) {
            actions.add("隐藏此控件")
            actions.add("强制移除此控件（可恢复）")
            if (node.view.isEnabled) actions.add("禁用此控件")
            if (node.resourceId != "NO_ID") {
                actions.add("持续隐藏（重进 QQ 仍生效）")
                actions.add("持续禁用（重进 QQ 仍生效）")
            }
        }
        links.forEachIndexed { index, _ ->
            actions.add("复制链接 ${index + 1}")
            actions.add("跳转链接 ${index + 1}（需确认）")
        }
        imageLinks.forEachIndexed { index, _ ->
            actions.add("复制图片直链 ${index + 1}")
            actions.add("打开图片 ${index + 1}（需确认）")
        }
        val canReadText = node.view is TextView &&
            node.view !is EditText &&
            node.view.transformationMethod !is PasswordTransformationMethod
        if (canReadText) actions.add("读取此控件文本（可能包含聊天内容）")

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 16), dp(activity, 12), dp(activity, 16), dp(activity, 12))
            setBackgroundColor(0xEAF4F7FB.toInt())
        }
        val detailText = TextView(activity).apply {
            text = message
            textSize = 14f
            setTextColor(0xFF172B43.toInt())
            setLineSpacing(dp(activity, 3).toFloat(), 1.08f)
            setPadding(dp(activity, 10), dp(activity, 10), dp(activity, 10), dp(activity, 14))
            background = GradientDrawable().apply {
                cornerRadius = dp(activity, 12).toFloat()
                setColor(0xEFFFFFFF.toInt())
                setStroke(dp(activity, 1), 0xB8DCE6F0.toInt())
            }
            setTextIsSelectable(true)
        }
        val detailScroll = android.widget.ScrollView(activity).apply {
            isFillViewport = false
            addView(detailText, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        content.addView(detailScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 260)
        ))
        if (peers.size > 1) {
            content.addView(TextView(activity).apply { text = "同一图层控件（点击单项查看详情）"; textSize = 13f; setTextColor(0xFF48627D.toInt()); setPadding(0, dp(activity, 10), 0, dp(activity, 4)) })
            peers.forEachIndexed { index, peer ->
                content.addView(TextView(activity).apply {
                    text = "${index + 1}. ${peer.className.substringAfterLast('.')} · ${peer.width}×${peer.height}  ›"
                    textSize = 13f; setTextColor(0xFF075A9D.toInt()); setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10))
                    background = GradientDrawable().apply { cornerRadius = dp(activity, 8).toFloat(); setColor(0xFFEAF3FC.toInt()) }
                    setOnClickListener { showNode(peer) }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        val wholeScroll = android.widget.ScrollView(activity).apply {
            isFillViewport = false
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(if (peers.any { it.view is ImageView || it.imageLinks.isNotEmpty() }) "图片与控件详情" else "控件详情")
            .setView(wholeScroll)
            .setNegativeButton("关闭", null)
            .create()
        actions.forEach { actionLabel ->
            val actionButton = TextView(activity).apply {
                text = actionLabel
                textSize = 14f
                setTextColor(0xFF075A9D.toInt())
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(activity, 14), dp(activity, 13), dp(activity, 14), dp(activity, 13))
                background = GradientDrawable().apply {
                    cornerRadius = dp(activity, 9).toFloat()
                    setColor(0xE6E7F2FC.toInt())
                    setStroke(dp(activity, 1), 0xB8C9DFF2.toInt())
                }
                isClickable = true
                setOnClickListener {
                    val which = actions.indexOf(actionLabel)
                    dialog.dismiss()
                    runSafely {
                when (actions[which]) {
                    "复制控件信息" -> copyText(activity, "QQ控件", nodeJson(node).toString(2))
                    "复制 Ark 卡片 JSON" -> copyText(activity, "Ark 卡片 JSON", cardJsons.firstOrNull().orEmpty())
                    "选择同一图层全部控件" -> {
                        multiSelect = true; pickMode = true; boxes.setPickMode(true); boxes.setMultiSelect(true)
                        boxes.setSelectedViews(peers.map { it.view })
                        showInspectorToast("已选中同一图层 ${peers.size} 个控件")
                        buildToolbar()
                    }
                    "隐藏此控件" -> {
                        if (canModify(node) && InspectorControlState.hide(node.view)) {
                            if (node.view.id != View.NO_ID) InspectorControlState.addPersistentRule(activity, node.view, "hide")
                            showInspectorToast("控件已隐藏；关闭探测器后仍保持隐藏，可在探测界面点“恢复控件”还原")
                            refresh()
                        } else showInspectorToast("此控件当前不可隐藏")
                    }
                    "强制移除此控件（可恢复）" -> {
                        if (canModify(node)) {
                            val ruleSaved = node.view.id != View.NO_ID &&
                                InspectorControlState.addPersistentRule(activity, node.view, "remove")
                            if (InspectorControlState.hide(node.view)) {
                                showInspectorToast(if (ruleSaved) "控件已移除；关闭探测器后仍保持移除，可在探测界面恢复" else "控件已隐藏；无资源 ID，跨页面持久恢复能力有限")
                            refresh()
                            } else showInspectorToast("此控件当前不可移除")
                        } else showInspectorToast("此控件当前不可移除")
                    }
                    "禁用此控件" -> {
                        if (canModify(node) && InspectorControlState.disable(node.view)) {
                            showInspectorToast("控件已临时禁用，点“恢复控件”可还原")
                            refresh()
                        } else showInspectorToast("此控件当前不可禁用")
                    }
                    "持续隐藏（重进 QQ 仍生效）" -> {
                        val saved = InspectorControlState.addPersistentRule(activity, node.view, "hide")
                        val applied = saved && InspectorControlState.hide(node.view)
                        showInspectorToast(if (applied) "持续隐藏规则已保存" else "规则保存或应用失败")
                        refresh()
                    }
                    "持续禁用（重进 QQ 仍生效）" -> {
                        val saved = InspectorControlState.addPersistentRule(activity, node.view, "disable")
                        val applied = saved && InspectorControlState.disable(node.view)
                        showInspectorToast(if (applied) "持续禁用规则已保存" else "规则保存或应用失败")
                        refresh()
                    }
                    "读取此控件文本（可能包含聊天内容）" -> {
                        AlertDialog.Builder(activity)
                            .setTitle("读取此控件文本？")
                            .setMessage("文本可能包含私聊内容、群消息、昵称或其他个人信息。仅在你确认当前页面内容可以读取时继续。")
                            .setPositiveButton("确认读取") { _, _ ->
                                val text = (node.view as? TextView)?.text?.toString().orEmpty().take(600)
                                AlertDialog.Builder(activity)
                                    .setTitle("控件文本（最多600字符）")
                                    .setMessage(if (text.isBlank()) "（空）" else text)
                                    .setPositiveButton("复制") { _, _ -> copyText(activity, "QQ控件文本", text) }
                                    .setNegativeButton("关闭", null)
                                    .show()
                            }
                            .setNegativeButton("不读取", null)
                            .show()
                    }
                    else -> {
                        val label = actions[which]
                        val copyLink = Regex("^复制链接 (\\d+)$").matchEntire(label)?.groupValues?.get(1)?.toIntOrNull()
                        val openLink = Regex("^跳转链接 (\\d+)（需确认）$").matchEntire(label)?.groupValues?.get(1)?.toIntOrNull()
                        val copyImage = Regex("^复制图片直链 (\\d+)$").matchEntire(label)?.groupValues?.get(1)?.toIntOrNull()
                        val copyCard = Regex("^复制卡片 JSON (\\d+)$").matchEntire(label)?.groupValues?.get(1)?.toIntOrNull()
                        val openImage = Regex("^打开图片 (\\d+)（需确认）$").matchEntire(label)?.groupValues?.get(1)?.toIntOrNull()
                        when {
                            copyLink != null -> links.getOrNull(copyLink - 1)?.let { copyText(activity, "QQ链接", it) }
                            openLink != null -> links.getOrNull(openLink - 1)?.let { confirmOpenLink(it, "页面链接") }
                            copyImage != null -> imageLinks.getOrNull(copyImage - 1)?.let { copyText(activity, "QQ图片直链", it) }
                            copyCard != null -> cardJsons.getOrNull(copyCard - 1)?.let { copyText(activity, "Ark 卡片 JSON", it) }
                            openImage != null -> imageLinks.getOrNull(openImage - 1)?.let { confirmOpenLink(it, "图片直链") }
                        }
                    }
                }
                    }
                }
            }
            content.addView(actionButton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(activity, 6) })
        }
        dialog.show()
        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val attrs = window.attributes
            attrs.dimAmount = 0.22f
            window.attributes = attrs
        }
    }

    private fun confirmOpenLink(url: String, label: String) {
        AlertDialog.Builder(activity)
            .setTitle("确认跳转？")
            .setMessage("$label：\\n$url\\n\\n将交给系统或 QQ 打开此地址，请确认链接来源可信。")
            .setPositiveButton("确认跳转") { _, _ ->
                runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    .onFailure { showInspectorToast("无法打开链接：${it.javaClass.simpleName}") }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun nodeJson(node: InspectorNode) = JSONObject()
        .put("class", node.className)
        .put("resourceId", node.resourceId)
        .put("bounds", JSONArray(listOf(node.bounds.left, node.bounds.top, node.bounds.right, node.bounds.bottom)))
        .put("width", node.width)
        .put("height", node.height)
        .put("area", node.area)
        .put("depth", node.depth)
        .put("clickable", node.clickable)
        .put("longClickable", node.longClickable)
        .put("focusable", node.focusable)
        .put("enabled", node.enabled)
        .put("alpha", node.alpha.toDouble())
        .put("childCount", node.childCount)
        .put("viewInfo", node.view.toString().take(900))
        .put("directLinks", JSONArray(node.directLinks))
        .put("imageLinks", JSONArray(node.imageLinks))
        .put("arkCardJson", JSONArray(node.cardJsons))
        .put("pageAndControlLinks", JSONArray(node.links))

    private fun copyTree() {
        val array = JSONArray()
        nodes.forEach { array.put(nodeJson(it)) }
        copyText(activity, "QQ控件树", JSONObject().put("count", nodes.size).put("nodes", array).toString(2))
    }

    private fun disableFeature() {
        runSafely {
            ConfigManager.getDefaultConfig().edit()
                .putBoolean(Constants.PrekXXX + "开发者选项/QQ界面控件探测器", false)
                .apply()
            QQInspectorRuntime.detach(activity)
            showInspectorToast("控件探测器已停用，可在 TNT 设置中重新开启")
        }
    }

    private fun close() {
        (parent as? ViewGroup)?.removeView(this)
        QQInspectorRuntime.attach(activity)
    }

    companion object {
        fun open(activity: Activity) {
            if (activity.isFinishing || activity.isDestroyed) return
            runCatching {
                val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
                val alreadyOpen = (0 until root.childCount).any { root.getChildAt(it) is InspectorOverlay }
                if (alreadyOpen) return
                val entry = root.findViewWithTag<View>(QQInspectorRuntimeEntryTag)
                if (entry != null) root.removeView(entry)
                val initialNodes = runCatching { InspectorTree.collect(activity) }
                    .onFailure { Logger.e("TNT inspector initial scan failed", it) }
                    .getOrDefault(emptyList())
                val overlay = InspectorOverlay(activity, root, initialNodes)
                root.addView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }.onFailure {
                Logger.e("TNT inspector open failed", it)
                Toast.makeText(activity, "控件探测器打开失败：${it.javaClass.simpleName}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private class InspectorBoxLayer(context: Context) : View(context) {
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF168DDE.toInt()
            style = Paint.Style.STROKE
            strokeWidth = dp(context, 1).toFloat()
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xDD087DBB.toInt()
            style = Paint.Style.FILL
        }
        private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = dp(context, 10).toFloat()
        }
        private var nodes: List<InspectorNode> = emptyList()
        private var drawnNodes: List<InspectorNode> = emptyList()
        private var pressedNode: InspectorNode? = null
        private var onSelected: ((InspectorNode) -> Unit)? = null
        private var pickMode = false
        private var multiSelect = false
        private val selectedViews = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<View, Boolean>())
        private var onSelectionChanged: ((Set<View>) -> Unit)? = null
        fun setMultiSelect(enabled: Boolean) { multiSelect = enabled; invalidate(); onSelectionChanged?.invoke(selectedViews.toSet()) }
        fun setOnSelectionChanged(listener: (Set<View>) -> Unit) { onSelectionChanged = listener }
        fun setSelectedViews(views: Collection<View>) { selectedViews.clear(); selectedViews.addAll(views); invalidate(); onSelectionChanged?.invoke(selectedViews.toSet()) }

        fun setPickMode(enabled: Boolean) {
            pickMode = enabled
            isClickable = enabled
            isFocusable = enabled
            pressedNode = null
        }

        fun setNodes(value: List<InspectorNode>) {
            nodes = value
            val minArea = (dp(context, 9) * dp(context, 9)).toLong()
            drawnNodes = value.filter {
                it.area >= minArea && (it.view is ImageView || it.clickable || it.longClickable || it.directLinks.isNotEmpty() ||
                    it.imageLinks.isNotEmpty() || (it.focusable && it.childCount == 0))
            }.take(120)
            invalidate()
        }

        fun setOnNodeSelected(listener: (InspectorNode) -> Unit) {
            onSelected = listener
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val location = IntArray(2)
            getLocationOnScreen(location)
            val screenLeft = location[0]
            val screenTop = location[1]
            drawnNodes.forEachIndexed { index, node ->
                val rect = RectF(
                    (node.bounds.left - screenLeft).toFloat(),
                    (node.bounds.top - screenTop).toFloat(),
                    (node.bounds.right - screenLeft).toFloat(),
                    (node.bounds.bottom - screenTop).toFloat()
                )
                val selected = node.view in selectedViews
                borderPaint.color = when { selected -> 0xFFFFB020.toInt(); node.view is ImageView || node.imageLinks.isNotEmpty() -> 0xFF22C9D8.toInt(); else -> 0xFF168DDE.toInt() }
                if (selected) {
                    borderPaint.style = Paint.Style.FILL; borderPaint.alpha = 55
                    canvas.drawRect(rect, borderPaint)
                    borderPaint.style = Paint.Style.STROKE; borderPaint.alpha = 255
                    borderPaint.strokeWidth = dp(context, 2).toFloat()
                } else { borderPaint.style = Paint.Style.STROKE; borderPaint.strokeWidth = dp(context, 1).toFloat() }
                canvas.drawRect(rect, borderPaint)
                if (rect.width() >= dp(context, 28) && rect.height() >= dp(context, 18)) {
                    val label = (index + 1).toString()
                    val textWidth = numberPaint.measureText(label)
                    val labelRect = RectF(rect.left, rect.top, rect.left + textWidth + dp(context, 8), rect.top + dp(context, 15))
                    canvas.drawRoundRect(labelRect, dp(context, 3).toFloat(), dp(context, 3).toFloat(), labelPaint)
                    canvas.drawText(label, labelRect.left + dp(context, 4), labelRect.bottom - dp(context, 4).toFloat(), numberPaint)
                }
            }
        }

        private fun hitNode(x: Int, y: Int): InspectorNode? =
            drawnNodes.asSequence()
                .filter { it.bounds.contains(x, y) }
                .minWithOrNull(compareBy<InspectorNode> { it.area }.thenByDescending { it.depth })

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!pickMode) {
                pressedNode = null
                return false
            }
            val location = IntArray(2)
            getLocationOnScreen(location)
            val x = (location[0] + event.x).toInt()
            val y = (location[1] + event.y).toInt()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressedNode = hitNode(x, y)
                    return pressedNode != null
                }
                MotionEvent.ACTION_MOVE -> return pressedNode != null
                MotionEvent.ACTION_CANCEL -> {
                    pressedNode = null
                    return false
                }
                MotionEvent.ACTION_UP -> {
                    val down = pressedNode
                    pressedNode = null
                    if (down != null) {
                        val up = hitNode(x, y)
                        if (up != null && up.view === down.view) {
                            if (multiSelect) {
                                if (!selectedViews.add(down.view)) selectedViews.remove(down.view)
                                invalidate(); onSelectionChanged?.invoke(selectedViews.toSet())
                            } else {
                                selectedViews.clear()
                                selectedViews.add(down.view)
                                invalidate()
                                onSelectionChanged?.invoke(selectedViews.toSet())
                                onSelected?.invoke(down)
                            }
                        }
                        return true
                    }
                    return false
                }
            }
            return pressedNode != null
        }
    }
}


private const val QQInspectorRuntimeEntryTag = "tnt.qq.inspector.entry"

private fun dp(context: Context, value: Int): Int =
    (value * context.resources.displayMetrics.density + 0.5f).toInt()

/**
 * Small local-only URL store and hooks. Query values with credential-like names are redacted.
 * No request headers, body, response body, chat packet, or login key is accessed.
 */
private object QQInspectorLinks {
    private const val STORAGE_KEY = "tnt_qq_inspector_links_v3"
    private const val MAX_ROWS = 100
    private val lock = Any()
    private val recent = LinkedHashMap<String, Long>()
    private const val FEATURE_KEY = "开发者选项/QQ界面控件探测器"

    private fun enabled(): Boolean = runCatching {
        ConfigManager.getDefaultConfig().getBooleanOrFalse(Constants.PrekXXX + FEATURE_KEY)
    }.getOrDefault(false)

    fun install(classLoader: ClassLoader) {
        hookActivityNavigation()
        hookWebViews(classLoader)
    }

    private fun extractIntent(intent: android.content.Intent?, source: String): List<String> {
        if (intent == null) return emptyList()
        val found = LinkedHashSet<String>()
        intent.dataString?.let { raw ->
            add("ACTIVITY_INTENT", raw, source)
            sanitize(raw)?.let(found::add)
        }
        val extras = runCatching { intent.extras }.getOrNull()
        if (extras != null) {
            runCatching {
                extras.keySet().take(80).forEach { key ->
                    if (!Regex("(url|link|scheme|jump|web|target|path|redirect)", RegexOption.IGNORE_CASE).containsMatchIn(key)) return@forEach
                    val value = extras.get(key) as? String ?: return@forEach
                    val candidate = value.trim()
                    if (looksLikeLink(candidate)) {
                        add("ACTIVITY_EXTRA", candidate, "$source:$key")
                        sanitize(candidate)?.let(found::add)
                    }
                }
            }
        }
        return found.toList()
    }

    fun captureActivity(activity: Activity): List<String> {
        if (!enabled()) return emptyList()
        val found = LinkedHashSet<String>()
        found.addAll(extractIntent(activity.intent, activity.javaClass.simpleName))
        val root = runCatching { activity.findViewById<ViewGroup>(android.R.id.content) }.getOrNull()
        var inspected = 0
        fun walk(view: View) {
            if (inspected++ > 360) return
            if (view is InspectorOverlay || view.tag == "tnt.qq.inspector.entry") return
            if (view.visibility != View.VISIBLE || view.alpha <= 0.03f) return
            val className = view.javaClass.name
            val webViewLike = view is WebView ||
                className.contains("webview", ignoreCase = true) ||
                className.contains("x5", ignoreCase = true)
            if (webViewLike) {
                listOf("getUrl", "getOriginalUrl", "getCurrentUrl", "getWebUrl").forEach { name ->
                    val raw = runCatching {
                        view.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }
                            ?.invoke(view)?.toString()
                    }.getOrNull()
                    if (!raw.isNullOrBlank()) {
                        add("ACTIVITY_WEBVIEW", raw, activity.javaClass.simpleName + "/" + className)
                        sanitize(raw)?.let(found::add)
                    }
                }
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
        }
        root?.let(::walk)
        return found.toList()
    }

    private fun looksLikeLink(raw: String): Boolean {
        val scheme = runCatching { Uri.parse(raw).scheme?.lowercase(Locale.ROOT) }.getOrNull()
        return scheme in setOf("http", "https", "mqq", "mqqapi", "qq", "tencent")
    }

    private fun hookActivityNavigation() {
        val callback = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val intent = param.args.firstOrNull { it is android.content.Intent } as? android.content.Intent ?: return
                extractIntent(intent, "Activity.startActivity")
            }
        }
        runCatching {
            XposedBridge.hookAllMethods(Activity::class.java, "startActivity", callback)
            XposedBridge.hookAllMethods(Activity::class.java, "startActivityForResult", callback)
            XposedBridge.hookAllMethods(Activity::class.java, "onNewIntent", callback)
            XposedBridge.hookAllMethods(Activity::class.java, "setIntent", callback)
        }.onFailure { Logger.w("TNT Activity link hooks unavailable: " + it.javaClass.simpleName) }
    }

    private fun hookWebViews(classLoader: ClassLoader) {
        val webViews = listOf(
            "android.webkit.WebView",
            "com.tencent.smtt.sdk.WebView",
            "com.tencent.qimei.webview.QmX5Webview"
        )
        for (name in webViews) {
            val clazz = runCatching { classLoader.loadClass(name) }.getOrNull() ?: continue
            listOf("loadUrl", "postUrl").forEach { methodName ->
                runCatching {
                    XposedBridge.hookAllMethods(clazz, methodName, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val raw = param.args.firstOrNull() as? String ?: return
                            add("WEBVIEW", raw, "${clazz.simpleName}.$methodName")
                        }
                    })
                }
            }
        }

        for (name in listOf("android.webkit.WebViewClient", "com.tencent.smtt.sdk.WebViewClient")) {
            val clazz = runCatching { classLoader.loadClass(name) }.getOrNull() ?: continue
            listOf("onPageStarted", "onPageFinished", "shouldOverrideUrlLoading").forEach { methodName ->
                runCatching {
                    XposedBridge.hookAllMethods(clazz, methodName, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val candidate = param.args.getOrNull(1) ?: return
                            val raw = if (candidate is String) candidate else {
                                runCatching {
                                    candidate.javaClass.getMethod("getUrl").invoke(candidate)?.toString()
                                }.getOrNull()
                            } ?: return
                            add("WEBVIEW_NAV", raw, "${clazz.simpleName}.$methodName")
                        }
                    })
                }
            }
        }
    }

    fun sanitize(raw: String): String? {
        return runCatching {
            val trimmed = raw.trim().take(4096)
            val uri = Uri.parse(trimmed)
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
            if (scheme !in setOf("http", "https", "mqq", "mqqapi", "qq", "tencent")) return null
            val builder = uri.buildUpon().clearQuery().fragment(null)
            val sensitive = Regex("(token|cookie|auth|ticket|secret|sign|signature|skey|p_skey|pskey|uin|session|password|passwd|access.?key|refresh)", RegexOption.IGNORE_CASE)
            uri.queryParameterNames.take(32).forEach { key ->
                uri.getQueryParameters(key).take(4).forEach { value ->
                    builder.appendQueryParameter(key, if (sensitive.containsMatchIn(key)) "[redacted]" else value.take(512))
                }
            }
            builder.build().toString().take(2048)
        }.getOrNull()
    }

    fun add(kind: String, raw: String, source: String) {
        if (!enabled()) return
        val safe = sanitize(raw) ?: return
        val fingerprint = "$kind|$safe"
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val previous = recent[fingerprint]
            if (previous != null && now - previous < 900L) return
            recent[fingerprint] = now
            while (recent.size > 240) recent.remove(recent.keys.first())
            runCatching {
                val config = ConfigManager.getDefaultConfig()
                val legacy = ConfigManager.cGetString("tnt_qq_inspector_links_v2", "[]")
                val old = JSONArray(config.getStringOrDefault(STORAGE_KEY, legacy))
                val next = JSONArray()
                val first = (old.length() - (MAX_ROWS - 1)).coerceAtLeast(0)
                for (index in first until old.length()) old.optJSONObject(index)?.let { next.put(it) }
                next.put(
                    JSONObject()
                        .put("time", now)
                        .put("kind", kind.take(32))
                        .put("source", source.take(100))
                        .put("url", safe)
                )
                config.edit().putString(STORAGE_KEY, next.toString()).apply()
            }.onFailure {
                Logger.w("TNT link history write failed: " + it.javaClass.simpleName)
            }
        }
    }

    fun readAll(): List<JSONObject> = runCatching {
        val config = ConfigManager.getDefaultConfig()
        val legacy = ConfigManager.cGetString("tnt_qq_inspector_links_v2", "[]")
        val array = JSONArray(config.getStringOrDefault(STORAGE_KEY, legacy))
        (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let { row -> JSONObject(row.toString()) } }
    }.getOrDefault(emptyList())

    fun clear() {
        synchronized(lock) {
            recent.clear()
            ConfigManager.getDefaultConfig().edit().putString(STORAGE_KEY, "[]").apply()
        }
    }
}
