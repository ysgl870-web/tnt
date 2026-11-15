package moe.ono.hooks.item.developer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.ono.hooks._base.BaseClickableFunctionHookItem
import moe.ono.hooks._core.annotation.HookItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@HookItem(
    path = "开发者选项/查看QQ抓取记录",
    description = "查看本机保存的最近100条界面链接和HTTP请求元数据；可复制或清空。"
)
class QQLinkInspectorViewer : BaseClickableFunctionHookItem() {

    override fun entry(classLoader: ClassLoader) {
        // A settings-only action; it does not install any hooks.
    }

    override fun onClick(context: Context) {
        val rows = QQLinkCaptureStore.readAll()
        val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
        val content = if (rows.isEmpty()) {
            "暂无记录。先在「开发者选项」中开启「QQ界面链接抓取」，然后正常浏览 QQ 页面。"
        } else {
            rows.asReversed().joinToString("\n\n") { row ->
                val time = dateFormat.format(Date(row.optLong("time")))
                val kind = row.optString("kind")
                val method = row.optString("method")
                val url = row.optString("url")
                val source = row.optString("source")
                val process = row.optString("process")
                "[$time] $kind  $method\n$url\n来源：$source  进程：$process"
            }
        }

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            val text = TextView(context).apply {
                setPadding(20, 12, 20, 12)
                textSize = 12f
                typeface = Typeface.MONOSPACE
                this.text = content
                setTextIsSelectable(true)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            addView(text)
        }

        MaterialAlertDialogBuilder(context)
            .setTitle("QQ 本地抓取记录 (" + rows.size + "/100)")
            .setView(scroll)
            .setPositiveButton("复制") { _, _ ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("QQ 本地抓取记录", content))
                Toast.makeText(context, "记录已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("清空") { _, _ ->
                QQLinkCaptureStore.clear()
                Toast.makeText(context, "本地记录已清空", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }
}
