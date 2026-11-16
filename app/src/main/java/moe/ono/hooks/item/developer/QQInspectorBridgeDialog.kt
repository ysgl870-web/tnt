package moe.ono.hooks.item.developer

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import moe.ono.hooks._base.BaseClickableFunctionHookItem
import moe.ono.hooks._core.annotation.HookItem
import moe.ono.util.MqqApiUrlTool
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

@HookItem(
    path = "黑壳区/跳板转换",
    description = "解析 QQ Scheme、解包多层 URL/Base64 跳转参数、转换网页 URL 与 Android Intent Scheme，并可确认后打开。"
)
class QQInspectorBridgeEntry : BaseClickableFunctionHookItem() {
    override fun entry(classLoader: ClassLoader) = Unit
    override fun onClick(context: Context) = QQInspectorBridgeDialog.show(context)
}

/** Local-only bridge converter; it does not invent private QQ mqqapi routes. */
internal object QQInspectorBridgeDialog {
    private data class Result(val converted: String, val detail: String, val openTarget: String)

    fun show(context: Context, initial: String = "") {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(8))
        }
        root.addView(TextView(context).apply {
            text = "支持 http/https、intent://、mqqapi://、QQ Scheme、URL 编码与 Base64 嵌套参数"
            textSize = 12f
        })
        val input = EditText(context).apply {
            setText(initial)
            hint = "粘贴需要解析或转换的链接"
            minLines = 3
            maxLines = 6
            setSingleLine(false)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        root.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })
        val output = TextView(context).apply {
            text = "转换结果将在这里显示"
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(0x142A78B8)
        }
        val detail = TextView(context).apply {
            text = "解析详情"
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(dp(4), dp(10), dp(4), dp(4))
        }
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            addView(detail)
        }
        root.addView(TextView(context).apply {
            text = "转换输出"
            textSize = 13f
            setPadding(0, dp(12), 0, dp(6))
        })
        root.addView(output)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)))
        var last: Result? = null
        val dialog = AlertDialog.Builder(context)
            .setTitle("黑壳区 · 跳板转换")
            .setView(root)
            .setNegativeButton("关闭", null)
            .create()

        fun actionButton(label: String, action: () -> Unit): TextView = TextView(context).apply {
            text = label
            textSize = 13f
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(8), dp(11), dp(8), dp(11))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xFF087DBB.toInt(), 0xFF1456A0.toInt())
            ).apply { cornerRadius = dp(10).toFloat() }
            setOnClickListener { action() }
        }
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        val parseButton = actionButton("解析 / 转换") {
            val result = convert(input.text?.toString().orEmpty())
            last = result
            output.text = result.converted.ifBlank { "未找到可转换内容" }
            detail.text = result.detail
        }
        val copyButton = actionButton("复制结果") {
            val value = last?.converted.orEmpty()
            if (value.isBlank()) Toast.makeText(context, "请先解析或转换", Toast.LENGTH_SHORT).show()
            else {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("TNT 跳板转换结果", value))
                Toast.makeText(context, "转换结果已复制", Toast.LENGTH_SHORT).show()
            }
        }
        val openButton = actionButton("确认跳转") {
            val result = last ?: convert(input.text?.toString().orEmpty()).also {
                last = it
                output.text = it.converted
                detail.text = it.detail
            }
            if (result.openTarget.isBlank()) Toast.makeText(context, "没有可打开的目标链接", Toast.LENGTH_SHORT).show()
            else AlertDialog.Builder(context)
                .setTitle("确认通过跳板打开？")
                .setMessage("将打开解析出的目标链接，请确认来源可信：\n\n${result.openTarget}")
                .setPositiveButton("打开") { _, _ -> openTarget(context, result.openTarget) }
                .setNegativeButton("取消", null)
                .show()
        }
        listOf(parseButton, copyButton, openButton).forEachIndexed { index, view ->
            actions.addView(view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (index > 0) marginStart = dp(6)
            })
        }
        root.addView(actions)
        dialog.show()
        dialog.window?.setLayout((context.resources.displayMetrics.widthPixels * 0.92f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        if (initial.isNotBlank()) {
            val result = convert(initial)
            last = result
            output.text = result.converted
            detail.text = result.detail
        }
    }

    private fun convert(rawInput: String): Result {
        val raw = rawInput.trim()
        if (raw.isBlank()) return Result("", "请输入链接后再解析。", "")
        val decoded = decodeLayers(raw)
        val detail = StringBuilder()
        detail.appendLine("原始内容：\n$raw")
        if (decoded != raw) detail.appendLine("\n多层解码结果：\n$decoded")
        val target = extractTarget(decoded)
        val uri = runCatching { Uri.parse(target) }.getOrNull()
        val scheme = uri?.scheme?.lowercase()
        var converted = target
        var openTarget = target
        when {
            target.startsWith("intent://", true) || target.startsWith("intent:", true) -> {
                val parsed = runCatching { Intent.parseUri(target, Intent.URI_INTENT_SCHEME) }.getOrNull()
                val data = parsed?.data
                if (data != null) {
                    converted = data.toString()
                    detail.appendLine("\n识别类型：Android Intent Scheme")
                    detail.appendLine("还原数据 URL：$converted")
                    detail.appendLine("目标包名：${parsed.getPackage() ?: "未指定"}")
                    detail.appendLine("Action：${parsed.action ?: "未指定"}")
                } else detail.appendLine("\n识别类型：Intent Scheme，但未包含可还原的 data URL")
            }
            scheme == "http" || scheme == "https" -> {
                val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
                converted = runCatching { intent.toUri(Intent.URI_INTENT_SCHEME) }.getOrDefault(target)
                detail.appendLine("\n识别类型：普通网页 URL")
                detail.appendLine("转换方式：封装为 Android intent://，保留原始网页地址")
                detail.appendLine("注意：这不是伪造的 QQ 私有 mqqapi Scheme。")
            }
            scheme in setOf("mqqapi", "mqq", "tim", "qq", "qqdocurl") -> {
                detail.appendLine("\n识别类型：QQ / TIM Scheme")
                if (scheme == "mqqapi") {
                    val parsed = runCatching { MqqApiUrlTool.parse(target) }.getOrNull()
                    if (parsed != null) {
                        detail.appendLine("协议：${parsed.scheme}://${parsed.authority.orEmpty()}${parsed.path.orEmpty()}")
                        parsed.params.forEach { (key, value) ->
                            val decodedValue = MqqApiUrlTool.decodeValue(value)
                            detail.appendLine("\n参数：$key\n原值：$value\n解码：${decodedValue.finalDecoded}")
                        }
                    }
                } else detail.appendLine("原始 Scheme 保留，不对未知协议结构做猜测性改写。")
            }
            else -> {
                detail.appendLine("\n未识别为可直接打开的 URL / Scheme。")
                val candidates = findUrls(decoded)
                if (candidates.isNotEmpty()) {
                    converted = candidates.first()
                    openTarget = converted
                    detail.appendLine("提取到的候选链接：\n" + candidates.joinToString("\n"))
                }
            }
        }
        if (decoded != raw) detail.appendLine("\n说明：已尝试 URL 解码与 Base64 解码，最多递归 5 层。")
        return Result(converted, detail.toString(), openTarget)
    }

    private fun extractTarget(value: String): String {
        val trimmed = value.trim().trim('"')
        if (trimmed.startsWith("intent:", true) || trimmed.startsWith("http://", true) ||
            trimmed.startsWith("https://", true) ||
            trimmed.substringBefore(':').matches(Regex("[A-Za-z][A-Za-z0-9+.-]*"))) return trimmed
        return findUrls(trimmed).firstOrNull() ?: trimmed
    }

    private fun decodeLayers(input: String): String {
        var current = input
        repeat(5) {
            val next = decodeBase64OrUrl(current)
            if (next == current || next.isBlank()) return current
            current = next
        }
        return current
    }

    private fun decodeBase64OrUrl(value: String): String {
        val urlDecoded = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
        if (urlDecoded != value) return urlDecoded
        val candidate = value.trim()
        if (candidate.length < 8 || !candidate.matches(Regex("[A-Za-z0-9_+/=-]+"))) return value
        return runCatching {
            val normalized = candidate.replace('-', '+').replace('_', '/').let {
                it + "=".repeat((4 - it.length % 4) % 4)
            }
            val decoded = String(Base64.decode(normalized, Base64.DEFAULT), StandardCharsets.UTF_8)
            if (decoded.any { ch -> ch.isLetterOrDigit() || ch in ":/?&=._-" }) decoded else value
        }.getOrDefault(value)
    }

    private fun findUrls(text: String): List<String> {
        val pattern = Regex("""(?:https?://|intent://|mqqapi://|mqq://|tim://|qqdocurl://)[^\s"'<>]+""", RegexOption.IGNORE_CASE)
        return pattern.findAll(text).map { it.value.trimEnd('.', ',', ')', ']', '}', '，', '。') }.distinct().take(20).toList()
    }

    private fun openTarget(context: Context, target: String) {
        try {
            val intent = if (target.startsWith("intent:", true)) Intent.parseUri(target, Intent.URI_INTENT_SCHEME)
            else Intent(Intent.ACTION_VIEW, Uri.parse(target)).addCategory(Intent.CATEGORY_BROWSABLE)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Toast.makeText(context, "已提交给系统处理", Toast.LENGTH_SHORT).show()
        } catch (error: ActivityNotFoundException) {
            Toast.makeText(context, "没有应用能处理该链接", Toast.LENGTH_LONG).show()
        } catch (error: Throwable) {
            Toast.makeText(context, "跳转失败：${error.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }
}
