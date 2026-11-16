package moe.ono.creator

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.method.ScrollingMovementMethod
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import moe.ono.extract.ExtractedMessage
import moe.ono.extract.QQMsgExtractStore
import moe.ono.hooks.base.util.Toasts
import org.json.JSONArray

/**
 * 消息信息提取结果展示：文本、链接、图片直链、Ark 卡片 JSON 分项可复制。
 */
object QQMessageExtractDialog {

    fun show(context: Context, message: ExtractedMessage) {
        val report = message.toText()
        val viewer = TextView(context).apply {
            text = report
            textSize = 12.5f
            setTextColor(0xFF172B43.toInt())
            setTextIsSelectable(true)
            movementMethod = ScrollingMovementMethod()
            setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12))
        }
        showScrollable(
            context = context,
            title = "消息提取 · ${message.chatLabel}",
            body = viewer,
            positiveText = "复制全部",
            onPositive = { copy(context, "消息提取结果", report) },
            neutralText = "复制单项",
            onNeutral = { showCopyMenu(context, message) },
            negativeText = "关闭"
        )
    }

    private fun showCopyMenu(context: Context, message: ExtractedMessage) {
        val actions = ArrayList<String>()
        val runnables = ArrayList<() -> Unit>()

        if (message.imageUrls.isNotEmpty()) {
            actions.add("复制图片直链（${message.imageUrls.size}）")
            runnables.add { copy(context, "图片直链", message.imageUrls.joinToString("\n")) }
            actions.add("打开第一张图片直链")
            runnables.add { openFirstImage(context, message.imageUrls.first()) }
        }
        if (message.arkCards.isNotEmpty()) {
            actions.add("复制 Ark 卡片 JSON（${message.arkCards.size}）")
            runnables.add { copy(context, "Ark 卡片 JSON", message.arkCards.joinToString("\n\n")) }
        }
        if (message.xmlCards.isNotEmpty()) {
            actions.add("复制结构化 XML（${message.xmlCards.size}）")
            runnables.add { copy(context, "结构化 XML", message.xmlCards.joinToString("\n\n")) }
        }
        if (message.links.isNotEmpty() || message.cardLinks.isNotEmpty()) {
            actions.add("复制全部链接（${message.links.size + message.cardLinks.size}）")
            runnables.add {
                copy(context, "消息链接", (message.links + message.cardLinks).distinct().joinToString("\n"))
            }
        }
        if (message.appIds.isNotEmpty()) {
            actions.add("复制小程序 appid")
            runnables.add { copy(context, "appid", message.appIds.joinToString(", ")) }
        }
        if (message.text.isNotBlank()) {
            actions.add("复制消息文本")
            runnables.add { copy(context, "消息文本", message.text) }
        }
        actions.add("复制整条为 JSON")
        runnables.add { copy(context, "消息 JSON", message.toJson().toString(2)) }

        AlertDialog.Builder(context)
            .setTitle("复制哪一项")
            .setItems(actions.toTypedArray()) { _, which -> runCatching { runnables[which]() } }
            .setNegativeButton("取消", null)
            .show()
    }

    fun showHistory(context: Context) {
        val rows = QQMsgExtractStore.recent()
        if (rows.isEmpty()) {
            val hint = TextView(context).apply {
                text = "暂无记录。\n\n开启「QQ消息信息提取」后，正常收发或浏览消息会自动记录最近 30 条；" +
                    "也可以长按任意消息选择「提取」单条查看。"
                textSize = 13f
                setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12))
            }
            showScrollable(
                context = context,
                title = "QQ 消息提取记录",
                body = hint,
                positiveText = "导出 JSON",
                onPositive = { copy(context, "消息提取记录", QQMsgExtractStore.exportJson()) },
                neutralText = "清空",
                onNeutral = {
                    QQMsgExtractStore.clear()
                    Toasts.info(context, "已清空本地记录")
                },
                negativeText = "关闭"
            )
            return
        }

        val labels = rows.map { message ->
            "${message.chatLabel} · ${message.senderName.ifEmpty { message.senderUin.toString() }} · ${message.timeLabel}\n" +
                "${message.summary()}\n" +
                "链接 ${message.links.size + message.cardLinks.size} · 图片 ${message.imageUrls.size} · 卡片 ${message.arkCards.size}"
        }.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle("QQ 消息提取记录（${rows.size}）")
            .setItems(labels) { _, which ->
                rows.getOrNull(which)?.let { show(context, it) }
            }
            .setNeutralButton("导出 JSON") { _, _ ->
                copy(context, "消息提取记录", QQMsgExtractStore.exportJson())
            }
            .setPositiveButton("全部图片直链") { _, _ ->
                val images = QQMsgExtractStore.allImages()
                if (images.isEmpty()) Toasts.info(context, "暂无图片直链")
                else copy(context, "全部图片直链", images.joinToString("\n"))
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun openFirstImage(context: Context, url: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }.onFailure {
            Toasts.info(context, "无法打开：${it.javaClass.simpleName}")
        }
    }

    fun copy(context: Context, label: String, value: String) {
        if (value.isBlank()) {
            Toasts.info(context, "内容为空")
            return
        }
        runCatching {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
            Toasts.info(context, "已复制：$label")
        }.onFailure {
            Toasts.info(context, "复制失败：${it.javaClass.simpleName}")
        }
    }

    /** 批量导出：把多条消息拼成 JSON 数组 */
    fun exportBatch(messages: List<ExtractedMessage>): String = runCatching {
        JSONArray().apply { messages.forEach { put(it.toJson()) } }.toString(2)
    }.getOrDefault("[]")

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    /** 统一的可滚动对话框，避免每个入口重复拼装 */
    private fun showScrollable(
        context: Context,
        title: String,
        body: TextView,
        positiveText: String,
        onPositive: () -> Unit,
        neutralText: String? = null,
        onNeutral: (() -> Unit)? = null,
        negativeText: String = "关闭"
    ) {
        val scroll = ScrollView(context).apply {
            addView(
                body,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }
        val builder = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(positiveText) { _, _ -> onPositive() }
            .setNegativeButton(negativeText, null)
        if (neutralText != null && onNeutral != null) {
            builder.setNeutralButton(neutralText) { _, _ -> onNeutral() }
        }
        builder.show()
    }
}
