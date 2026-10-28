package moe.ono.activity

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import java.nio.charset.StandardCharsets

/** TNT launch surface; all helper operations are local unless the user explicitly opens a link. */
class TNTLauncherActivity : AppCompatTransferActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "TNT 模块"
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(16)) }
        body.addView(TextView(this).apply {
            text = "TNT 模块"
            textSize = 28f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, lp())
        body.addView(TextView(this).apply {
            text = "QQ 增强工具箱 · 作者：亦是光流\nAndroid ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\n此工具页以本地处理为主；云预设必须手动指定 HTTPS 地址。"
            textSize = 14f
        }, lp())
        addButton(body, "黑壳 / AWA 预设工坊") {
            startActivity(Intent(this, TNTPresetStudioActivity::class.java))
        }
        addButton(body, "快捷文案") { phraseTool() }
        addButton(body, "JSON 美化 / 校验") { jsonTool() }
        addButton(body, "QQ Scheme / 链接分析") { linkTool() }
        addButton(body, "文本与字节长度检查") { textTool() }
        addButton(body, "常用表情复制") { emojiTool() }
        addButton(body, "复制环境诊断") { copyDiagnostic() }
        addButton(body, "关于与许可") {
            MaterialAlertDialogBuilder(this).setTitle("TNT 模块")
                .setMessage("二次开发作者：亦是光流\n上游项目：cwuom/ono\n许可证：GPL-3.0\n\n本模块基于 ONO 二次开发，保留上游版权与许可要求。内部 QQNT Hook 需根据 QQ 版本测试。")
                .setPositiveButton("关闭", null).show()
        }
        setContentView(ScrollView(this).apply { addView(body) })
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

    private fun addButton(root: LinearLayout, label: String, action: () -> Unit) {
        root.addView(Button(this).apply { text = label; isAllCaps = false; setOnClickListener { try { action() } catch (t: Throwable) { toast("操作失败：${t.javaClass.simpleName}") } } }, lp())
    }
    private fun lp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(n: Int) = (n * resources.displayMetrics.density + 0.5f).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun copy(label: String, s: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, s))
        toast("已复制到剪贴板")
    }
}
