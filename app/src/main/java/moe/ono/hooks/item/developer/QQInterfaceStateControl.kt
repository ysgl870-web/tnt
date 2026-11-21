package moe.ono.hooks.item.developer

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import moe.ono.config.ConfigManager
import moe.ono.hooks._base.BaseClickableFunctionHookItem
import moe.ono.hooks._core.annotation.HookItem
import moe.ono.util.ContextUtils
import java.util.WeakHashMap

/**
 * TNT interface state controller.
 *
 * System-bar color is applied through Window.statusBarColor/navigationBarColor.
 * A genuinely transparent bar additionally requires drawing the app content behind it,
 * enabled with LAYOUT_FULLSCREEN / LAYOUT_HIDE_NAVIGATION. The original flags are
 * retained per Activity and restored when the user selects "follow system".
 */
@HookItem(
    path = "抓取区/界面状态控制",
    description = "控制 QQ 顶栏、底栏颜色与透明度，并同步设置控件探测悬浮面板外观。"
)
class QQInterfaceStateControl : BaseClickableFunctionHookItem() {
    override fun entry(classLoader: ClassLoader) = InterfaceStateRuntime.install()

    override fun onClick(context: Context) = InterfaceStateDialog.show(context)
}

private object InterfaceStateRuntime {
    private const val ENABLED = "tnt_interface_state_enabled"
    private const val TOP_MODE = "tnt_interface_top_mode"
    private const val BOTTOM_MODE = "tnt_interface_bottom_mode"
    private const val TOP_COLOR = "tnt_interface_top_color"
    private const val BOTTOM_COLOR = "tnt_interface_bottom_color"
    private const val TOP_ALPHA = "tnt_interface_top_alpha"
    private const val BOTTOM_ALPHA = "tnt_interface_bottom_alpha"
    private const val FLOAT_ALPHA = "tnt_interface_float_alpha"
    private const val FLOAT_COLOR = "tnt_interface_float_color"

    private data class Original(
        val statusColor: Int,
        val navigationColor: Int,
        val uiFlags: Int
    )
    private val originals = WeakHashMap<Activity, Original>()
    @Volatile private var installed = false

    fun install() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val app = runCatching { moe.ono.util.HostInfo.getApplication() }.getOrNull() as? Application
                ?: return
            runCatching {
                app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
                    override fun onActivityStarted(activity: Activity) = Unit
                    override fun onActivityResumed(activity: Activity) { apply(activity) }
                    override fun onActivityPaused(activity: Activity) = Unit
                    override fun onActivityStopped(activity: Activity) = Unit
                    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
                    override fun onActivityDestroyed(activity: Activity) { originals.remove(activity) }
                })
                installed = true
            }
        }
    }

    fun saveAndApply(
        context: Context,
        topMode: Int,
        bottomMode: Int,
        topColor: Int,
        bottomColor: Int,
        topAlpha: Int,
        bottomAlpha: Int,
        floatAlpha: Int,
        floatColor: Int
    ) {
        val cfg = ConfigManager.getDefaultConfig()
        cfg.edit()
            .putBoolean(ENABLED, true)
            .putInt(TOP_MODE, topMode).putInt(BOTTOM_MODE, bottomMode)
            .putInt(TOP_COLOR, topColor).putInt(BOTTOM_COLOR, bottomColor)
            .putInt(TOP_ALPHA, topAlpha).putInt(BOTTOM_ALPHA, bottomAlpha)
            .putInt(FLOAT_ALPHA, floatAlpha).putInt(FLOAT_COLOR, floatColor)
            // Keep the existing TNT floating panel renderer in sync with this page.
            .putInt("tnt_qq_inspector_panel_alpha", floatAlpha)
            .putInt("tnt_qq_inspector_panel_color", floatColor)
            .apply()
        val activity = context as? Activity ?: ContextUtils.getCurrentActivity()
        if (activity != null) apply(activity)
    }

    fun disableAndRestore(context: Context) {
        ConfigManager.getDefaultConfig().edit().putBoolean(ENABLED, false).apply()
        val activity = context as? Activity ?: ContextUtils.getCurrentActivity()
        if (activity != null) restore(activity)
    }

    private fun restore(activity: Activity) {
        val original = synchronized(originals) { originals.remove(activity) } ?: return
        runCatching {
            activity.window.statusBarColor = original.statusColor
            activity.window.navigationBarColor = original.navigationColor
            activity.window.decorView.systemUiVisibility = original.uiFlags
        }
    }

    fun apply(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val cfg = ConfigManager.getDefaultConfig()
        if (!cfg.getBooleanOrFalse(ENABLED)) {
            restore(activity)
            return
        }
        val window = activity.window ?: return
        val original = synchronized(originals) {
            originals.getOrPut(activity) {
                Original(window.statusBarColor, window.navigationBarColor, window.decorView.systemUiVisibility)
            }
        }
        val topMode = cfg.getIntOrDefault(TOP_MODE, 0)
        val bottomMode = cfg.getIntOrDefault(BOTTOM_MODE, 0)
        val flags = window.decorView.systemUiVisibility
        var nextFlags = flags and (View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE).inv()

        if (topMode == 0) {
            window.statusBarColor = original.statusColor
        } else {
            val alpha = if (topMode == 2) 0 else cfg.getIntOrDefault(TOP_ALPHA, 100).coerceIn(0, 100)
            window.statusBarColor = withAlpha(cfg.getIntOrDefault(TOP_COLOR, 0x182740), alpha)
            if (topMode == 2) nextFlags = nextFlags or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }

        if (bottomMode == 0) {
            window.navigationBarColor = original.navigationColor
        } else {
            val alpha = if (bottomMode == 2) 0 else cfg.getIntOrDefault(BOTTOM_ALPHA, 100).coerceIn(0, 100)
            window.navigationBarColor = withAlpha(cfg.getIntOrDefault(BOTTOM_COLOR, 0x182740), alpha)
            if (bottomMode == 2) nextFlags = nextFlags or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }

        // Preserve all unrelated QQ UI flags; only replace the layout bits managed here.
        if (topMode == 0 && bottomMode == 0) {
            nextFlags = (nextFlags and (View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE).inv()) or
                (original.uiFlags and (View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE))
        }
        window.decorView.systemUiVisibility = nextFlags

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                window.isStatusBarContrastEnforced = false
                window.isNavigationBarContrastEnforced = false
            }
        }
    }

    private fun withAlpha(rgb: Int, alpha: Int): Int =
        Color.argb((alpha * 255 / 100).coerceIn(0, 255), Color.red(rgb), Color.green(rgb), Color.blue(rgb))
}

private object InterfaceStateDialog {
    private val colors = listOf(
        "深海蓝" to 0x182740, "曜石黑" to 0x1B1D22, "星空紫" to 0x251D3A,
        "翡翠绿" to 0x15302B, "雾面白" to 0xF4F7FB
    )

    fun show(context: Context) {
        val cfg = ConfigManager.getDefaultConfig()
        var topMode = cfg.getIntOrDefault("tnt_interface_top_mode", 2).coerceIn(0, 2)
        var bottomMode = cfg.getIntOrDefault("tnt_interface_bottom_mode", 2).coerceIn(0, 2)
        var topColor = cfg.getIntOrDefault("tnt_interface_top_color", 0x182740)
        var bottomColor = cfg.getIntOrDefault("tnt_interface_bottom_color", 0x182740)
        var topAlpha = cfg.getIntOrDefault("tnt_interface_top_alpha", 86).coerceIn(0, 100)
        var bottomAlpha = cfg.getIntOrDefault("tnt_interface_bottom_alpha", 86).coerceIn(0, 100)
        val floatAlpha = cfg.getIntOrDefault("tnt_interface_float_alpha", 94).coerceIn(35, 100)
        val floatColor = cfg.getIntOrDefault("tnt_interface_float_color", 0x182740)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 18), dp(context, 8), dp(context, 18), dp(context, 8))
        }
        fun heading(value: String) {
            root.addView(TextView(context).apply {
                text = value; textSize = 14f; setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, dp(context, 10), 0, dp(context, 5))
            })
        }
        fun modeRow(title: String, initial: Int, changed: (Int) -> Unit) {
            heading(title)
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            listOf("跟随页面", "颜色/半透明", "完全透明").forEachIndexed { index, label ->
                row.addView(TextView(context).apply {
                    text = if (index == initial) "● $label" else label
                    textSize = 12f; gravity = android.view.Gravity.CENTER
                    setPadding(dp(context, 7), dp(context, 9), dp(context, 7), dp(context, 9))
                    setOnClickListener {
                        for (i in 0 until row.childCount) (row.getChildAt(i) as TextView).text =
                            listOf("跟随页面", "颜色/半透明", "完全透明")[i]
                        text = "● $label"
                        changed(index)
                    }
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            root.addView(row)
        }
        fun alphaControl(title: String, initial: Int, min: Int, max: Int, onChanged: (Int) -> Unit) {
            heading("$title：$initial%")
            val label = root.getChildAt(root.childCount - 1) as TextView
            val seek = SeekBar(context).apply { this.max = max - min; progress = initial.coerceIn(min, max) - min }
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    label.text = "$title：${progress + min}%"
                    onChanged(progress + min)
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
            root.addView(seek)
        }

        modeRow("顶栏状态", topMode) { topMode = it }
        alphaControl("顶栏透明度", topAlpha, 0, 100) { topAlpha = it }
        modeRow("底栏状态", bottomMode) { bottomMode = it }
        alphaControl("底栏透明度", bottomAlpha, 0, 100) { bottomAlpha = it }
        heading("悬浮面板同步透明度")
        var currentFloatAlpha = floatAlpha
        val floatLabel = root.getChildAt(root.childCount - 1) as TextView
        root.addView(SeekBar(context).apply {
            max = 65; progress = floatAlpha - 35
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    currentFloatAlpha = progress + 35
                    floatLabel.text = "悬浮面板同步透明度：$currentFloatAlpha%"
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        })
        heading("统一颜色")
        colors.forEach { (name, color) ->
            root.addView(TextView(context).apply {
                text = "●  $name"; textSize = 13f
                setTextColor(Color.rgb(Color.red(color), Color.green(color), Color.blue(color)))
                setPadding(dp(context, 6), dp(context, 5), dp(context, 6), dp(context, 5))
                setOnClickListener { topColor = color; bottomColor = color }
            })
        }
        AlertDialog.Builder(context)
            .setTitle("抓取区 · 界面状态控制")
            .setMessage("颜色/半透明模式按透明度绘制系统栏；完全透明模式会让 QQ 内容延伸到系统栏后方。若页面布局不适配，改回“跟随页面”。")
            .setView(root)
            .setPositiveButton("保存并应用") { _, _ ->
                InterfaceStateRuntime.saveAndApply(
                    context, topMode, bottomMode, topColor, bottomColor,
                    topAlpha, bottomAlpha, currentFloatAlpha, topColor
                )
                Toast.makeText(context, "界面状态已保存并应用；其他页面切换后自动同步", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("恢复系统默认") { _, _ ->
                cfg.edit().putBoolean("tnt_interface_state_enabled", false).apply()
                val activity = context as? Activity ?: ContextUtils.getCurrentActivity()
                if (activity != null) {
                    val window = activity.window
                    val top = window.statusBarColor
                    val bottom = window.navigationBarColor
                    window.statusBarColor = Color.BLACK
                    window.navigationBarColor = Color.BLACK
                    window.decorView.systemUiVisibility = window.decorView.systemUiVisibility and
                        (View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION).inv()
                    Toast.makeText(context, "已关闭 TNT 界面覆盖；重新进入页面后由 QQ 自行管理系统栏", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + .5f).toInt()
}
