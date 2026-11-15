package moe.ono.hooks.item.developer

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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

        MaterialAlertDialogBuilder(context)
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
        runCatching { InspectorControlState.restoreAll() }
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

        val button = InspectorEntryView(activity).apply {
            tag = BUTTON_TAG
            text = "控件"
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xE52683C6.toInt())
            gravity = Gravity.CENTER
            elevation = dp(activity, 8).toFloat()
            isClickable = true
            isFocusable = true
            setPadding(dp(activity, 10), dp(activity, 6), dp(activity, 10), dp(activity, 6))
            setOnClickListener { InspectorOverlay.open(activity) }
        }
        val lp = FrameLayout.LayoutParams(dp(activity, 48), dp(activity, 40), Gravity.END or Gravity.CENTER_VERTICAL)
        lp.marginEnd = dp(activity, 6)
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
    val links: List<String>
)

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
                        links = (directLinks + pageLinks).distinct()
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
 * Reversible UI controls. Changes are temporary and are restored when the inspector closes,
 * is disabled, or the host Activity detaches.
 */
private object InspectorControlState {
    private data class SavedState(
        val visibility: Int,
        val enabled: Boolean,
        val clickable: Boolean,
        val longClickable: Boolean
    )

    private val saved = WeakHashMap<View, SavedState>()

    private fun remember(view: View) {
        if (!saved.containsKey(view)) {
            saved[view] = SavedState(
                visibility = view.visibility,
                enabled = view.isEnabled,
                clickable = view.isClickable,
                longClickable = view.isLongClickable
            )
        }
    }

    @Synchronized
    fun hide(view: View): Boolean = runCatching {
        if (view.parent == null) return false
        remember(view)
        view.visibility = View.GONE
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
                view.visibility = state.visibility
                view.isEnabled = state.enabled
                view.isClickable = state.clickable
                view.isLongClickable = state.longClickable
            }
        }
        return entries.size
    }
}

private class InspectorEntryView(context: Context) : TextView(context)

private class InspectorOverlay private constructor(
    private val activity: Activity,
    private val root: ViewGroup,
    private var nodes: List<InspectorNode>
) : FrameLayout(activity) {

    private val boxes = InspectorBoxLayer(activity)
    private val toolbar = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 8), dp(activity, 8))
        setBackgroundColor(0xE62C3038.toInt())
        elevation = dp(activity, 10).toFloat()
    }
    private val toolbarScroll = HorizontalScrollView(activity).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(toolbar, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private var bordersVisible = true
    private var pickMode = false

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
        boxes.setOnNodeSelected { runSafely { showNode(it) } }
        buildToolbar()
    }

    private fun buildToolbar() {
        toolbar.removeAllViews()
        toolbar.addView(toolButton("控件列表 ${nodes.size}") { showList() }, buttonParams())
        toolbar.addView(toolButton(if (pickMode) "点选:开" else "点选:关") { togglePickMode() }, buttonParams())
        toolbar.addView(toolButton("刷新") { refresh() }, buttonParams())
        toolbar.addView(toolButton(if (bordersVisible) "边框:开" else "边框:关") { toggleBorders() }, buttonParams())
        toolbar.addView(toolButton("恢复控件") { restoreControls() }, buttonParams())
        toolbar.addView(toolButton("保存链接") { saveCurrentLinks() }, buttonParams())
        toolbar.addView(toolButton("链接记录") { showLinkHistory() }, buttonParams())
        toolbar.addView(toolButton("复制结构") { copyTree() }, buttonParams())
        toolbar.addView(toolButton("停用探测") { disableFeature() }, buttonParams())
        toolbar.addView(toolButton("关闭") { close() }, buttonParams())
    }

    private fun buttonParams() = LinearLayout.LayoutParams(dp(activity, 82), dp(activity, 42)).apply {
        marginEnd = dp(activity, 4)
    }

    private fun runSafely(action: () -> Unit) {
        try {
            if (activity.isFinishing || activity.isDestroyed) return
            action()
        } catch (error: Throwable) {
            Logger.e("TNT inspector action failed", error)
            Toast.makeText(activity, "控件操作失败：${error.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }

    private fun toolButton(label: String, action: () -> Unit) = TextView(activity).apply {
        text = label
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(0xFFFFFFFF.toInt())
        setBackgroundColor(0xFF087DBB.toInt())
        isClickable = true
        isFocusable = true
        setOnClickListener { runSafely(action) }
    }

    private fun togglePickMode() {
        pickMode = !pickMode
        boxes.setPickMode(pickMode)
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
        val count = InspectorControlState.restoreAll()
        refresh()
        Toast.makeText(activity, "已恢复本次修改的控件（$count）", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(activity, "已重新扫描 ${nodes.size} 个控件", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Logger.e("TNT inspector refresh failed", it)
            Toast.makeText(activity, "扫描失败：${it.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveCurrentLinks() {
        val activityLinks = QQInspectorLinks.captureActivity(activity)
        val visibleNodes = InspectorTree.collect(activity)
        val discovered = (activityLinks + visibleNodes.flatMap { it.directLinks }).distinct()
        if (discovered.isEmpty()) {
            Toast.makeText(activity, "当前 Activity 暂未发现 Intent 或 WebView 链接", Toast.LENGTH_LONG).show()
            return
        }
        discovered.forEach { QQInspectorLinks.add("MANUAL_SAVE", it, "手动保存当前页面") }
        val total = QQInspectorLinks.readAll().size
        Toast.makeText(activity, "已保存 ${discovered.size} 个页面链接，历史共 ${total} 条", Toast.LENGTH_SHORT).show()
    }

    private fun showLinkHistory() {
        val rows = QQInspectorLinks.readAll()
        if (rows.isEmpty()) {
            MaterialAlertDialogBuilder(activity)
                .setTitle("QQ 链接记录")
                .setMessage("暂时没有记录。打开 QQ 页面后点击「保存链接」，或浏览内置网页触发自动记录。")
                .setPositiveButton("知道了", null)
                .show()
            return
        }
        val labels = rows.asReversed().mapIndexed { index, row ->
            val date = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(row.optLong("time")))
            "$date · ${row.optString("kind")} · ${row.optString("source")}\n${row.optString("url")}"
        }.toTypedArray()
        MaterialAlertDialogBuilder(activity)
            .setTitle("链接历史（${rows.size}/100）")
            .setItems(labels) { _, which ->
                val row = rows.asReversed().getOrNull(which) ?: return@setItems
                val url = row.optString("url")
                MaterialAlertDialogBuilder(activity)
                    .setTitle("链接详情")
                    .setMessage(url)
                    .setPositiveButton("复制") { _, _ -> copyText(activity, "QQ链接", url) }
                    .setNeutralButton("清空记录") { _, _ ->
                        QQInspectorLinks.clear()
                        Toast.makeText(activity, "链接记录已清空", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("关闭", null)
                    .show()
            }
            .setNeutralButton("清空全部") { _, _ ->
                QQInspectorLinks.clear()
                Toast.makeText(activity, "链接记录已清空", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
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
        MaterialAlertDialogBuilder(activity)
            .setTitle("控件列表（共 ${nodes.size} 个，显示前 ${shownNodes.size} 个）")
            .setItems(labels) { _, which -> shownNodes.getOrNull(which)?.let { runSafely { showNode(it) } } }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showNode(node: InspectorNode) {
        val controlLinks = node.directLinks.distinct()
        val pageLinks = node.links.filterNot { it in controlLinks }.distinct()
        val links = (controlLinks + pageLinks).ifEmpty {
            QQInspectorLinks.readAll().takeLast(8).map { it.optString("url") }
        }
        val message = buildString {
            append("控件类名：").append(node.className)
            append("\n资源 ID：").append(node.resourceId)
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
            if (controlLinks.isEmpty() && pageLinks.isEmpty() && links.isNotEmpty()) {
                append("\n最近保存的链接：\n").append(links.take(8).joinToString("\n"))
            }
            append("\n\n控件文本默认隐藏；仅在你明确确认后读取。")
        }

        val actions = mutableListOf("复制控件信息")
        if (canModify(node)) {
            actions.add("临时隐藏此控件")
            if (node.view.isEnabled) actions.add("临时禁用此控件")
        }
        if (links.isNotEmpty()) actions.add("复制第一个链接")
        val canReadText = node.view is TextView &&
            node.view !is EditText &&
            node.view.transformationMethod !is PasswordTransformationMethod
        if (canReadText) actions.add("读取此控件文本（可能包含聊天内容）")

        MaterialAlertDialogBuilder(activity)
            .setTitle("控件详情")
            .setMessage(message)
            .setItems(actions.toTypedArray()) { _, which ->
                when (actions[which]) {
                    "复制控件信息" -> copyText(activity, "QQ控件", nodeJson(node).toString(2))
                    "临时隐藏此控件" -> {
                        if (canModify(node) && InspectorControlState.hide(node.view)) {
                            Toast.makeText(activity, "控件已临时隐藏，点“恢复控件”可还原", Toast.LENGTH_SHORT).show()
                            refresh()
                        } else {
                            Toast.makeText(activity, "此控件当前不可隐藏", Toast.LENGTH_SHORT).show()
                        }
                    }
                    "临时禁用此控件" -> {
                        if (canModify(node) && InspectorControlState.disable(node.view)) {
                            Toast.makeText(activity, "控件已临时禁用，点“恢复控件”可还原", Toast.LENGTH_SHORT).show()
                            refresh()
                        } else {
                            Toast.makeText(activity, "此控件当前不可禁用", Toast.LENGTH_SHORT).show()
                        }
                    }
                    "复制第一个链接" -> links.firstOrNull()?.let { copyText(activity, "QQ链接", it) }
                    "读取此控件文本（可能包含聊天内容）" -> {
                        MaterialAlertDialogBuilder(activity)
                            .setTitle("读取此控件文本？")
                            .setMessage("文本可能包含私聊内容、群消息、昵称或其他个人信息。仅在你确认当前页面内容可以读取时继续。")
                            .setPositiveButton("确认读取") { _, _ ->
                                val text = (node.view as? TextView)?.text?.toString().orEmpty().take(600)
                                MaterialAlertDialogBuilder(activity)
                                    .setTitle("控件文本（最多600字符）")
                                    .setMessage(if (text.isBlank()) "（空）" else text)
                                    .setPositiveButton("复制") { _, _ -> copyText(activity, "QQ控件文本", text) }
                                    .setNegativeButton("关闭", null)
                                    .show()
                            }
                            .setNegativeButton("不读取", null)
                            .show()
                    }
                }
            }
            .setNegativeButton("关闭", null)
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
        .put("pageAndControlLinks", JSONArray(node.links))

    private fun copyTree() {
        val array = JSONArray()
        nodes.forEach { array.put(nodeJson(it)) }
        copyText(activity, "QQ控件树", JSONObject().put("count", nodes.size).put("nodes", array).toString(2))
    }

    private fun disableFeature() {
        runCatching {
            ConfigManager.getDefaultConfig().edit()
                .putBoolean(Constants.PrekXXX + "开发者选项/QQ界面控件探测器", false)
                .apply()
        }
        QQInspectorRuntime.detach(activity)
        Toast.makeText(activity, "控件探测器已停用，可在 TNT 设置中重新开启", Toast.LENGTH_LONG).show()
    }

    private fun close() {
        InspectorControlState.restoreAll()
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
                val overlay = InspectorOverlay(activity, root, InspectorTree.collect(activity))
                root.addView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }.onFailure {
                Logger.e("TNT inspector open failed", it)
                Toast.makeText(activity, "控件探测器打开失败：${it.javaClass.simpleName}", Toast.LENGTH_LONG).show()
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
                it.area >= minArea && (it.clickable || it.longClickable || it.directLinks.isNotEmpty() ||
                    (it.focusable && it.childCount == 0))
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
                // Outline only: no translucent fill, avoiding the accumulated full-blue screen.
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
                        if (up != null) onSelected?.invoke(up)
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
    private const val STORAGE_KEY = "tnt_qq_inspector_links_v2"
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
                val old = JSONArray(ConfigManager.cGetString(STORAGE_KEY, "[]"))
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
                ConfigManager.cPutString(STORAGE_KEY, next.toString())
            }.onFailure {
                Logger.w("TNT link history write failed: " + it.javaClass.simpleName)
            }
        }
    }

    fun readAll(): List<JSONObject> = runCatching {
        val array = JSONArray(ConfigManager.cGetString(STORAGE_KEY, "[]"))
        (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let { row -> JSONObject(row.toString()) } }
    }.getOrDefault(emptyList())

    fun clear() {
        synchronized(lock) {
            recent.clear()
            ConfigManager.cPutString(STORAGE_KEY, "[]")
        }
    }
}
