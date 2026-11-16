package moe.ono.hooks.item.developer

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
import de.robv.android.xposed.XC_MethodHook
import moe.ono.R
import moe.ono.creator.QQMessageExtractDialog
import moe.ono.extract.ExtractedMessage
import moe.ono.extract.QQMsgExtractStore
import moe.ono.extract.QQMsgExtractor
import moe.ono.hooks._base.BaseClickableFunctionHookItem
import moe.ono.hooks._base.BaseSwitchFunctionHookItem
import moe.ono.hooks._core.annotation.HookItem
import moe.ono.hooks._core.factory.HookItemFactory.getItem
import moe.ono.hooks.base.api.QQMessageViewListener
import moe.ono.hooks.base.util.Toasts
import moe.ono.hooks.dispatcher.OnMenuBuilder
import moe.ono.reflex.MethodUtils
import moe.ono.reflex.Reflex
import moe.ono.util.ContextUtils
import moe.ono.util.CustomMenu
import moe.ono.util.Logger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 消息信息提取：直接从 QQ 已加载的消息模型里取数据，而不是靠界面上「猜」。
 *
 * 能力：
 *  - 图片原始直链（originImageUrl + 当前会话 rkey，缺失时用 md5 地址兜底）
 *  - Ark / 结构化卡片完整 JSON
 *  - 文本、卡片内链接、小程序 appid
 *  - 消息文本与发送者、会话、时间等界面消息信息
 *
 * 数据只在模块进程内存里保留最近 80 条，不写数据库、不联网。
 */
@SuppressLint("DiscouragedApi")
@HookItem(
    path = "黑壳区/QQ消息信息提取",
    description = "自动提取消息里的图片直链、Ark 卡片 JSON、正文链接与消息文本。开启后长按任意消息点「提取」可查看单条；在「查看消息提取记录」里查看最近 30 条。"
)
class QQMessageExtractor : BaseSwitchFunctionHookItem(), OnMenuBuilder,
    QQMessageViewListener.OnChatViewUpdateListener {

    // Message view callbacks may arrive during RecyclerView scrolling. Parse MsgRecord and
    // persist the bounded cache away from the UI thread; pending IDs also prevent duplicate
    // work when the same recycled bubble reports multiple updates before the first parse ends.
    private val extractionExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "TNT-MsgExtract").apply { isDaemon = true }
    }
    private val pendingMessageIds = ConcurrentHashMap.newKeySet<Long>()

    /** 只在有内容可提取的气泡类型上挂菜单，避免给每个控件都加长按项 */
    override val targetTypes: Array<String> = arrayOf(
        "com.tencent.mobileqq.aio.msglist.holder.component.ark.AIOArkContentComponent",
        "com.tencent.mobileqq.aio.msglist.holder.component.ark.AIOCenterArkContentComponent",
        "com.tencent.mobileqq.aio.shop.AIOShopArkContentComponent",
        "com.tencent.mobileqq.aio.msglist.holder.component.pic.AIOPicContentComponent",
        "com.tencent.mobileqq.aio.msglist.holder.component.multipci.AIOMultiPicContentComponent",
        "com.tencent.mobileqq.aio.msglist.holder.component.mix.AIOMixContentComponent",
        "com.tencent.mobileqq.aio.msglist.holder.component.text.AIOTextContentComponent",
        "com.tencent.mobileqq.aio.msglist.holder.component.longmsg.AIOLongMsgContentComponent",
        "com.tencent.mobileqq.aio.msglist.holder.component.markdown.AIORichContentComponent",
        "com.tencent.mobileqq.aio.msglist.holder.component.markdown.AIOMarkdownContentComponent",
        "com.tencent.mobileqq.aio.msglist.holder.component.template.AIOTemplateMsgComponent",
        "com.tencent.mobileqq.aio.msglist.holder.component.fold.AIOFoldContentComponent",
        "com.tencent.mobileqq.aio.msglist.holder.component.video.AIOVideoContentComponent",
        "com.tencent.mobileqq.aio.msglist.holder.component.file.AIOFileContentComponent",
        "com.tencent.mobileqq.aio.msglist.holder.component.file.AIOOnlineFileContentComponent"
    )

    override fun entry(classLoader: ClassLoader) {
        // 气泡更新时自动提取一次（同一 msgId 会覆盖，不会重复堆积）
        QQMessageViewListener.addMessageViewUpdateListener(this, this)
    }

    override fun onViewUpdateAfter(msgItemView: View, msgRecord: Any) {
        val record = msgRecord as? MsgRecord ?: return
        val msgId = record.msgId
        if (msgId == 0L || QQMsgExtractStore.contains(msgId) || !pendingMessageIds.add(msgId)) return
        extractionExecutor.execute {
            try {
                val extracted = QQMsgExtractor.extract(record) ?: return@execute
                remember(extracted)
            } catch (error: Throwable) {
                Logger.w("TNT async message extraction failed: " + error.javaClass.simpleName)
            } finally {
                pendingMessageIds.remove(msgId)
            }
        }
    }

    override fun onGetMenu(
        aioMsgItem: Any,
        targetType: String,
        param: XC_MethodHook.MethodHookParam
    ) {
        if (!getItem(this.javaClass).isEnabled) return

        val menuItem = runCatching {
            CustomMenu.createItemIconNt(
                aioMsgItem,
                "提取",
                R.drawable.ic_twotone_message_24,
                R.id.item_extract_msg
            ) {
                openExtracted(aioMsgItem)
            }
        }.getOrElse {
            Logger.w("TNT extract menu item failed: " + it.javaClass.simpleName)
            null
        } ?: return

        val existing = param.result as? List<*> ?: emptyList<Any>()
        param.result = listOf(menuItem) + existing
    }

    private fun openExtracted(aioMsgItem: Any) {
        val activity = ContextUtils.getCurrentActivity() ?: return
        val extracted = runCatching { extractFromItem(aioMsgItem) }.getOrNull()
        if (extracted == null) {
            Toasts.info(activity, "这条消息没有可提取的内容")
            return
        }
        remember(extracted)
        QQMessageExtractDialog.show(activity, extracted)
    }

    private fun extractFromItem(aioMsgItem: Any): ExtractedMessage? {
        // 走 getMsgRecord；不同 QQ 版本方法名与签名一致，取不到时退回 getMsgId 再查内核
        val record = runCatching {
            MethodUtils.create(aioMsgItem.javaClass).methodName("getMsgRecord").callFirst(aioMsgItem) as? MsgRecord
        }.getOrNull() ?: runCatching {
            Reflex.invokeVirtual(aioMsgItem, "getMsgRecord") as? MsgRecord
        }.getOrNull()
        return QQMsgExtractor.extract(record)
    }

    /** 同时写入内存记录和模块统一的链接历史（图片/卡片不过滤、不脱敏，保证直链可用） */
    private fun remember(extracted: ExtractedMessage) {
        QQMsgExtractStore.put(extracted)
        runCatching {
            val entries = ArrayList<QQInspectorLinks.LinkEntry>()
            extracted.imageUrls.forEach { entries.add(QQInspectorLinks.LinkEntry("MSG_IMAGE", it, "消息图片", full = true)) }
            extracted.arkCards.forEach { entries.add(QQInspectorLinks.LinkEntry("MSG_ARK", it, "消息卡片", full = true)) }
            extracted.xmlCards.forEach { entries.add(QQInspectorLinks.LinkEntry("MSG_STRUCT_XML", it, "结构化消息卡片", full = true)) }
            extracted.links.forEach { entries.add(QQInspectorLinks.LinkEntry("MSG_LINK", it, "消息文本")) }
            extracted.cardLinks.forEach { entries.add(QQInspectorLinks.LinkEntry("MSG_CARD_LINK", it, "卡片跳转")) }
            QQInspectorLinks.addBatch(entries, force = true)
        }.onFailure { Logger.w("TNT extract link store: " + it.javaClass.simpleName) }
    }
}

/**
 * 设置页入口：查看最近提取的消息。
 */
@HookItem(
    path = "黑壳区/消息提取记录",
    description = "查看本机最近 30 条消息提取结果，可复制单条、全部图片直链，或导出 JSON。需先开启「QQ消息信息提取」。"
)
class QQMessageExtractHistory : BaseClickableFunctionHookItem() {
    override fun entry(classLoader: ClassLoader) = Unit

    override fun onClick(context: Context) {
        QQMessageExtractDialog.showHistory(context)
    }
}
