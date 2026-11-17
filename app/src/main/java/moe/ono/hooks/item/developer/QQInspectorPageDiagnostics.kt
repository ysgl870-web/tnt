package moe.ono.hooks.item.developer

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import android.os.Process
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout
import moe.ono.config.ConfigManager
import moe.ono.constants.Constants
import moe.ono.hooks._base.BaseClickableFunctionHookItem
import moe.ono.hooks._core.annotation.HookItem
import moe.ono.util.ContextUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@HookItem(
    path = "黑壳区/页面诊断",
    description = "查看当前 QQ 页面、宿主版本、进程、屏幕参数、可见控件统计与 WebView 状态；报告仅在本机生成。"
)
class QQInspectorPageDiagnostics : BaseClickableFunctionHookItem() {
    override fun entry(classLoader: ClassLoader) = Unit

    override fun onClick(context: Context) {
        val activity = context as? Activity ?: ContextUtils.getCurrentActivity()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            Toast.makeText(context, "请先进入 QQ 页面再打开诊断", Toast.LENGTH_SHORT).show()
            return
        }
        QQInspectorDiagnostics.show(activity)
    }
}

/** Local-only, read-only runtime/page diagnostics inspired by modular hook diagnostics UIs. */
internal object QQInspectorDiagnostics {
    private data class WebInfo(val className: String, val url: String, val title: String, val progress: String)

    fun show(activity: Activity) {
        val report = buildReport(activity)
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density + .5f).toInt()

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF123C61.toInt(), 0xFF126F91.toInt(), 0xFF1B3E68.toInt())
            ).apply {
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), 0x9957D6F5.toInt())
            }
        }
        header.addView(TextView(activity).apply {
            text = "TNT  /  BLACK SHELL"
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            letterSpacing = .06f
        })
        header.addView(TextView(activity).apply {
            text = "PAGE DIAGNOSTICS · 本机只读诊断"
            textSize = 10f
            setTextColor(0xFFBCEEFF.toInt())
            setPadding(0, dp(4), 0, 0)
        })
        root.addView(header)
        val body = TextView(activity).apply {
            text = report
            textSize = 12f
            setTextColor(0xFF243B50.toInt())
            setTextIsSelectable(true)
            setPadding(dp(4), dp(12), dp(4), dp(12))
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        val scroll = ScrollView(activity).apply {
            isFillViewport = false
            addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(420)))
        AlertDialog.Builder(activity)
            .setTitle("黑壳区 · 页面诊断")
            .setView(root)
            .setPositiveButton("复制诊断报告") { _, _ ->
                runCatching {
                    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("TNT 页面诊断", report))
                    Toast.makeText(activity, "诊断报告已复制", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(activity, "复制失败", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("重新诊断") { _, _ -> show(activity) }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun buildReport(activity: Activity): String {
        val metrics = activity.resources.displayMetrics
        val packageName = activity.packageName
        val packageInfo = packageInfo(activity)
        val processName = currentProcessName(activity)
        val root = runCatching { activity.findViewById<ViewGroup>(android.R.id.content) }.getOrNull()
        var visited = 0
        var visible = 0
        var groups = 0
        var clickable = 0
        var enabled = 0
        val webViews = ArrayList<WebInfo>()

        fun walk(view: View, depth: Int) {
            if (visited >= 520 || depth > 48) return
            visited++
            if (view.visibility != View.VISIBLE || view.alpha <= .03f || !view.isShown) return
            visible++
            if (view is ViewGroup) groups++
            if (view.isClickable) clickable++
            if (view.isEnabled) enabled++
            val name = view.javaClass.name
            if (name.contains("webview", true) || name.contains("x5", true)) {
                val url = callString(view, "getUrl").ifBlank { callString(view, "getOriginalUrl") }
                val title = callString(view, "getTitle")
                val progress = callString(view, "getProgress")
                webViews.add(
                    WebInfo(
                        className = name.substringAfterLast('.'),
                        url = url,
                        title = title,
                        progress = progress
                    )
                )
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    if (visited >= 520) break
                    walk(view.getChildAt(index), depth + 1)
                }
            }
        }
        root?.let { walk(it, 0) }
        val density = String.format(Locale.getDefault(), "%.2f", metrics.density)
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val versionName = packageInfo?.versionName ?: "未知"
        val versionCode = packageInfo?.let { packageVersionCode(it) } ?: "未知"
        val hostType = when (packageName) {
            "com.tencent.mobileqq" -> "QQ"
            "com.tencent.tim" -> "TIM"
            else -> "其他宿主 / 外部页面"
        }

        return buildString {
            appendLine("TNT 黑壳区 · 页面诊断报告")
            appendLine("诊断时间：$now")
            appendLine()
            appendLine("【宿主环境】")
            appendLine("宿主类型：$hostType")
            appendLine("包名：$packageName")
            appendLine("版本名称：$versionName")
            appendLine("版本代码：$versionCode")
            appendLine("Activity：${activity.javaClass.name}")
            appendLine("进程：$processName")
            appendLine("Android：${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("设备指纹：${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine()
            appendLine("【屏幕与窗口】")
            appendLine("屏幕：${metrics.widthPixels} × ${metrics.heightPixels} px")
            appendLine("密度：$density")
            appendLine("Content 区域：${root?.width ?: 0} × ${root?.height ?: 0} px")
            appendLine("状态栏高度 / 导航栏高度：以宿主 Window Insets 为准（本工具不强行读取）")
            appendLine()
            appendLine("【当前可见控件统计】")
            appendLine("扫描节点：$visited")
            appendLine("可见节点：$visible")
            appendLine("容器节点：$groups")
            appendLine("可点击节点：$clickable")
            appendLine("启用节点：$enabled")
            appendLine("WebView / X5：${webViews.size}")
            appendLine()
            appendLine("【WebView 状态】")
            if (webViews.isEmpty()) {
                appendLine("当前页面未发现可识别的 WebView / X5 视图")
            } else {
                webViews.forEachIndexed { index, web ->
                    appendLine("${index + 1}. ${web.className}")
                    appendLine("   URL：${if (web.url.isBlank()) "未暴露" else moe.ono.hooks.item.developer.QQInspectorLinks.mask(web.url)}")
                    appendLine("   标题：${web.title.ifBlank { "未暴露" }.take(160)}")
                    appendLine("   加载进度：${web.progress.ifBlank { "未暴露" }}")
                }
            }
            appendLine()
            appendLine("【诊断范围】")
            appendLine("只读取当前 Activity 的可见 View 树、宿主包信息与 WebView 的公开状态方法。")
            appendLine("不读取聊天数据库、Cookie、网络请求/响应正文、登录票据或隐藏控件内容。")
            appendLine("未获取到的宿主内部状态会标记为未暴露，不进行猜测。")
        }
    }

    @Suppress("DEPRECATION")
    private fun packageVersionCode(info: PackageInfo): String =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toString() else info.versionCode.toString()

    private fun packageInfo(context: Context): PackageInfo? = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0)
    }.getOrNull()

    private fun currentProcessName(context: Context): String {
        return runCatching {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            manager?.runningAppProcesses?.firstOrNull { it.pid == Process.myPid() }?.processName
                ?: context.packageName
        }.getOrDefault(context.packageName)
    }

    private fun callString(target: Any, methodName: String): String = runCatching {
        target.javaClass.methods.firstOrNull { it.name == methodName && it.parameterTypes.isEmpty() }
            ?.invoke(target)?.toString().orEmpty()
    }.getOrDefault("")
}
