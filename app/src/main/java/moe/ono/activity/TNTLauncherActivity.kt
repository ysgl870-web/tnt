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
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(16)) }
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
        body.addView(hero, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(14)
        })
        addSection(body, "常用工具", "复制、分析和格式化，不会自动发送协议包")
        addButton(body, "黑壳 / AWA 预设工坊", "本地预设、云目录预览与格式工具", "▤") {
            startActivity(Intent(this, TNTPresetStudioActivity::class.java))
        }
        addButton(body, "快捷文案", "保存常用回复，一键复制", "✎") { phraseTool() }
        addButton(body, "QQ 原生分享助手", "通过系统分享面板交给 QQ 等应用", "↗") { nativeShare() }
        addButton(body, "JSON 美化 / 校验", "校验结构并复制格式化内容", "{ }") { jsonTool() }
        addButton(body, "QQ Scheme / 链接分析", "显示协议、主机、路径和参数名", "⌁") { linkTool() }
        addButton(body, "文本与字节长度检查", "字符、代码点、UTF-8 字节和行数", "≡") { textTool() }
        addButton(body, "常用表情复制", "快速复制常见 Emoji", "☺") { emojiTool() }
        addButton(body, "复制环境诊断", "生成设备和模块基础信息", "ⓘ") { copyDiagnostic() }
        addSection(body, "模块信息", "作者、上游和开源许可证")
        addButton(body, "关于与许可", "查看作者信息与 GPL-3.0 说明", "©") {
            MaterialAlertDialogBuilder(this).setTitle("TNT 模块")
                .setMessage("二次开发作者：亦是光流\n上游项目：cwuom/ono\n许可证：GPL-3.0\n\n本模块基于 ONO 二次开发，保留上游版权与许可要求。内部 QQNT Hook 需根据 QQ 版本测试。")
                .setPositiveButton("关闭", null).show()
        }
        val scroll = ScrollView(this).apply {
            clipToPadding = false
            isFillViewport = true
            setPadding(0, 0, 0, dp(12))
            addView(body)
        }
        setContentView(scroll)
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

    private fun addSection(root: LinearLayout, title: String, summary: String) {
        val group = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(2), dp(7), dp(2), dp(5)) }
        group.addView(TextView(this).apply {
            text = title; textSize = 17f
            setTextColor(if (Build.VERSION.SDK_INT >= 23) getColor(R.color.firstTextColor) else resources.getColor(R.color.firstTextColor))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, lp())
        group.addView(TextView(this).apply {
            text = summary; textSize = 12f
            setTextColor(if (Build.VERSION.SDK_INT >= 23) getColor(R.color.secondTextColor) else resources.getColor(R.color.secondTextColor))
        }, lp())
        root.addView(group, lp())
    }

    private fun addButton(root: LinearLayout, label: String, summary: String, icon: String, action: () -> Unit) {
        val card = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = 0x223F6FA5
            setCardBackgroundColor(if (Build.VERSION.SDK_INT >= 23) getColor(R.color.card_bg) else resources.getColor(R.color.card_bg))
            isClickable = true
            isFocusable = true
            rippleColor = android.content.res.ColorStateList.valueOf(0x223F8CFF)
            setOnClickListener {
                try { action() } catch (t: Throwable) {
                    android.util.Log.e("TNT", "Tool failed: $label", t)
                    MaterialAlertDialogBuilder(this@TNTLauncherActivity)
                        .setTitle("工具暂时无法打开")
                        .setMessage("$label\n${t.javaClass.simpleName}: ${t.message ?: "未知错误"}")
                        .setPositiveButton("关闭", null).show()
                }
            }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), dp(12), dp(12), dp(12))
        }
        row.addView(TextView(this).apply {
            text = icon
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(0xFF1769D2.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0x1D3987E8)
            }
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        textColumn.addView(TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(if (Build.VERSION.SDK_INT >= 23) getColor(R.color.firstTextColor) else resources.getColor(R.color.firstTextColor))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, lp())
        textColumn.addView(TextView(this).apply {
            text = summary
            textSize = 12f
            setTextColor(if (Build.VERSION.SDK_INT >= 23) getColor(R.color.secondTextColor) else resources.getColor(R.color.secondTextColor))
            setPadding(0, dp(3), 0, 0)
        }, lp())
        row.addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply {
            text = "›"; textSize = 25f; setTextColor(0xFF7F8EA3.toInt())
        }, LinearLayout.LayoutParams(dp(22), ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(row)
        root.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        })
    }

    private fun lp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(n: Int) = (n * resources.displayMetrics.density + 0.5f).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun copy(label: String, s: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, s))
        toast("已复制到剪贴板")
    }
}
