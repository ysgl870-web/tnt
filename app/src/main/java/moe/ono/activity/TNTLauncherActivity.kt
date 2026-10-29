package moe.ono.activity

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.card.MaterialCardView
import moe.ono.R
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import java.nio.charset.StandardCharsets

/** TNT launch surface; all helper operations are local unless the user explicitly opens a link. */
class TNTLauncherActivity : BaseActivity() {
    override fun doOnEarlyCreate(savedInstanceState: Bundle?, isInitializing: Boolean) {
        super.doOnEarlyCreate(savedInstanceState, isInitializing)
        setTheme(R.style.Theme_Ono)
    }

    override fun doOnCreate(savedInstanceState: Bundle?): Boolean {
        super.doOnCreate(null)
        title = "光流工具中心"
        try {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(18)) }
        val hero = MaterialCardView(this).apply {
            radius = dp(22).toFloat()
            cardElevation = dp(2).toFloat()
            setCardBackgroundColor(android.graphics.Color.rgb(23, 105, 210))
            strokeWidth = 0
        }
        val heroContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(18))
        }
        heroContent.addView(TextView(this).apply {
            text = "光流工具中心"
            textSize = 25f
            setTextColor(android.graphics.Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, lp())
        heroContent.addView(TextView(this).apply {
            text = "TNT 模块 · 亦是光流\nAndroid ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}"
            textSize = 13f
            setTextColor(0xFFEAF2FF.toInt())
            setPadding(0, dp(6), 0, 0)
        }, lp())
        heroContent.addView(TextView(this).apply {
            text = "本地优先 · 预设可管理 · 云内容先预览后导入"
            textSize = 12f
            setTextColor(0xFFD9E8FF.toInt())
            setPadding(0, dp(12), 0, 0)
        }, lp())
        hero.addView(heroContent)
        body.addView(hero, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) })
        addSection(body, "常用工具", "双列布局 · 单项失败会显示错误，不直接退出")
        addActionGrid(body, listOf(
            ToolAction("黑壳 / AWA 预设工坊", "本地预设、云目录预览与格式工具", "▤") { startActivity(Intent(this, TNTPresetStudioActivity::class.java)) },
            ToolAction("快捷文案", "保存常用回复，一键复制", "✎") { phraseTool() },
            ToolAction("QQ 原生分享助手", "通过系统分享面板交给 QQ 等应用", "↗") { nativeShare() },
            ToolAction("JSON 美化 / 校验", "检查 JSON 并复制美化结果", "{ }") { jsonTool() },
            ToolAction("QQ Scheme / 链接分析", "显示协议、主机、路径和参数名", "⌁") { linkTool() },
            ToolAction("文本与字节长度", "字符、代码点、UTF-8 字节、行数", "≡") { textTool() },
            ToolAction("常用表情复制", "快速复制常见 Emoji", "☺") { emojiTool() },
            ToolAction("复制环境诊断", "生成设备与模块基础信息", "ⓘ") { copyDiagnostic() }
        ))
        addSection(body, "模块信息", "作者、上游和许可证")
        addActionGrid(body, listOf(
            ToolAction("关于与许可", "TNT 二次开发 · GPL-3.0", "©") {

                MaterialAlertDialogBuilder(this).setTitle("TNT 模块")
                    .setMessage("二次开发作者：亦是光流\n上游项目：cwuom/ono\n许可证：GPL-3.0\n\n本模块基于 ONO 二次开发，保留上游版权与许可要求。内部 QQNT Hook 需根据 QQ 版本测试。")
                    .setPositiveButton("关闭", null).show()
            }
        ))
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(0, 0, 0, dp(12))
            addView(body)
        })
        } catch (t: Throwable) {
            android.util.Log.e("TNT", "工具中心初始化失败", t)
            val fallback = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(20), dp(20), dp(20))
            }
            fallback.addView(TextView(this).apply {
                text = "光流工具中心已进入安全模式"
                textSize = 22f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }, lp())
            fallback.addView(TextView(this).apply {
                text = "界面初始化遇到异常，已保留基础功能。请复制环境诊断并反馈以下错误：\n${t.javaClass.simpleName}: ${t.message ?: "无详细信息"}"
                textSize = 14f
            }, lp())
            fallback.addView(Button(this).apply {
                text = "复制环境诊断"; isAllCaps = false; setOnClickListener { copyDiagnostic() }
            }, lp())
            setContentView(ScrollView(this).apply { isFillViewport = true; addView(fallback) })
        }
        return true
    }

    private fun phraseTool() {
        val input = EditText(this).apply { hint = "输入或编辑常用文案"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 5 }
        val prefs = getSharedPreferences("tnt_quick_phrases", MODE_PRIVATE)
        input.setText(prefs.getString("last", "").orEmpty())
        MaterialAlertDialogBuilder(this).setTitle("快捷文案")
            .setView(input).setNegativeButton("关闭", null)
            .setNeutralButton("保存") { _, _ -> prefs.edit().putString("last", input.text.toString()).apply(); toast("已在本机保存") }
            .setPositiveButton("复制") { _, _ -> copy("TNT 文案", input.text.toString()) }.show()
    }

    private fun nativeShare() {
        val input = EditText(this).apply {
            hint = "输入文本、链接或媒体分享说明"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
        }
        MaterialAlertDialogBuilder(this).setTitle("QQ 原生分享助手")
            .setMessage("通过 Android 系统分享面板调用设备上已安装的应用（包括 QQ）。TNT 不读取登录态、不构造内部协议包。")
            .setView(input).setNegativeButton("取消", null)
            .setNeutralButton("只复制") { _, _ -> copy("TNT 分享草稿", input.text.toString()) }
            .setPositiveButton("选择应用") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isBlank()) { toast("请先输入文本或链接"); return@setPositiveButton }
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                try {
                    startActivity(Intent.createChooser(share, "分享至"))
                } catch (t: Throwable) {
                    toast("系统分享面板不可用：${t.javaClass.simpleName}")
                }
            }.show()
    }

    private fun jsonTool() {
        val input = EditText(this).apply { hint = "粘贴 JSON"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 6 }
        MaterialAlertDialogBuilder(this).setTitle("JSON 美化 / 校验")
            .setView(input).setNegativeButton("关闭", null).setPositiveButton("校验并复制美化结果", null).create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(-1).setOnClickListener {
                        try {
                            val value = JSONTokener(input.text.toString()).nextValue()
                            val pretty = when (value) {
                                is JSONObject -> value.toString(2)
                                is JSONArray -> value.toString(2)
                                else -> value.toString()
                            }
                            copy("JSON", pretty)
                            toast("JSON 结构有效，已复制")
                            dialog.dismiss()
                        } catch (t: Throwable) {
                            MaterialAlertDialogBuilder(this).setTitle("JSON 无效").setMessage(t.message ?: "解析失败").setPositiveButton("关闭", null).show()
                        }
                    }
                }
                dialog.show()
            }
    }

    private fun linkTool() {
        val input = EditText(this).apply { hint = "粘贴 HTTPS/QQ Scheme 链接"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
        MaterialAlertDialogBuilder(this).setTitle("链接分析（不自动访问）")
            .setView(input).setNegativeButton("关闭", null).setPositiveButton("分析", null).create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(-1).setOnClickListener {
                        val raw = input.text.toString().trim()
                        try {
                            val uri = URI(raw)
                            require(!uri.scheme.isNullOrBlank()) { "链接缺少协议" }
                            val report = "协议：${uri.scheme}\n主机：${uri.host ?: "（无）"}\n路径：${uri.path ?: ""}\n参数名：${uri.rawQuery?.split("&")?.map { it.substringBefore("=") }?.filter { it.isNotBlank() }?.distinct()?.joinToString("、") ?: "（无）"}\n\n参数值已隐藏；本工具不会访问链接。"
                            MaterialAlertDialogBuilder(this).setTitle("链接分析结果").setMessage(report)
                                .setNeutralButton("复制报告") { _, _ -> copy("链接分析", report) }
                                .setPositiveButton("关闭", null).show()
                            dialog.dismiss()
                        } catch (t: Throwable) {
                            MaterialAlertDialogBuilder(this).setTitle("链接格式无效").setMessage(t.message ?: "无法解析").setPositiveButton("关闭", null).show()
                        }
                    }
                }
                dialog.show()
            }
    }

    private fun textTool() {
        val input = EditText(this).apply { hint = "输入需要统计的文本"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 5 }
        MaterialAlertDialogBuilder(this).setTitle("文本 / 字节长度")
            .setView(input).setNegativeButton("关闭", null).setPositiveButton("统计", null).create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(-1).setOnClickListener {
                        val value = input.text.toString()
                        val report = "字符数：${value.length}\n代码点数：${value.codePointCount(0, value.length)}\nUTF-8 字节数：${value.toByteArray(StandardCharsets.UTF_8).size}\n行数：${if (value.isEmpty()) 0 else value.count { it == '\n' } + 1}"
                        MaterialAlertDialogBuilder(this).setTitle("统计结果").setMessage(report)
                            .setNeutralButton("复制报告") { _, _ -> copy("文本统计", report) }.setPositiveButton("关闭", null).show()
                        dialog.dismiss()
                    }
                }
                dialog.show()
            }
    }

    private fun emojiTool() {
        val emojis = arrayOf("😂", "🤣", "😭", "🥹", "👍", "❤️", "🤔", "🎉", "🙏", "✨", "🤝", "😎", "🐱", "💯", "🔥")
        MaterialAlertDialogBuilder(this).setTitle("点选表情复制")
            .setItems(emojis) { _, which -> copy("TNT Emoji", emojis[which]) }.show()
    }

    private fun copyDiagnostic() {
        val info = "TNT 模块\n作者：亦是光流\nAndroid：${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\nABI：${Build.SUPPORTED_ABIS.joinToString()}\nQQ 包名：com.tencent.mobileqq\n"
        copy("TNT 诊断", info)
    }

    private data class ToolAction(val title: String, val summary: String, val icon: String, val action: () -> Unit)

    private fun addSection(root: LinearLayout, title: String, summary: String) {
        val section = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(2), dp(9), dp(2), dp(6)) }
        section.addView(TextView(this).apply {
            text = title; textSize = 17f
            setTextColor(if (Build.VERSION.SDK_INT >= 23) getColor(R.color.firstTextColor) else resources.getColor(R.color.firstTextColor))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, lp())
        section.addView(TextView(this).apply {
            text = summary; textSize = 12f
            setTextColor(if (Build.VERSION.SDK_INT >= 23) getColor(R.color.secondTextColor) else resources.getColor(R.color.secondTextColor))
        }, lp())
        root.addView(section, lp())
    }

    private fun addActionGrid(root: LinearLayout, actions: List<ToolAction>) {
        actions.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            pair.forEachIndexed { index, item ->
                val card = MaterialCardView(this).apply {
                    radius = dp(15).toFloat(); cardElevation = 0f
                    strokeWidth = dp(1); strokeColor = 0x223F6FA5
                    setCardBackgroundColor(if (Build.VERSION.SDK_INT >= 23) getColor(R.color.card_bg) else resources.getColor(R.color.card_bg))
                    isClickable = true; isFocusable = true
                    rippleColor = android.content.res.ColorStateList.valueOf(0x223F8CFF)
                    setOnClickListener {
                        try { item.action() } catch (t: Throwable) {
                            android.util.Log.e("TNT", "工具打开失败：${item.title}", t)
                            MaterialAlertDialogBuilder(this@TNTLauncherActivity).setTitle("工具暂时无法打开")
                                .setMessage("${item.title}\n${t.javaClass.simpleName}: ${t.message ?: "未知错误"}")
                                .setPositiveButton("关闭", null).show()
                        }
                    }
                }
                val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(9), dp(12)) }
                content.addView(TextView(this).apply {
                    text = "${item.icon}  ${item.title}"; textSize = 14f
                    setTextColor(if (Build.VERSION.SDK_INT >= 23) getColor(R.color.firstTextColor) else resources.getColor(R.color.firstTextColor))
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }, lp())
                content.addView(TextView(this).apply {
                    text = item.summary; textSize = 11f
                    setTextColor(if (Build.VERSION.SDK_INT >= 23) getColor(R.color.secondTextColor) else resources.getColor(R.color.secondTextColor))
                    setPadding(0, dp(5), 0, 0)
                }, lp())
                card.addView(content)
                row.addView(card, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index == 0) marginEnd = dp(6)
                })
            }
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(6) })
            root.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(7) })
        }
    }
    private fun lp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(n: Int) = (n * resources.displayMetrics.density + 0.5f).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun copy(label: String, s: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, s))
        toast("已复制到剪贴板")
    }
}
