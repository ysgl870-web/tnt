package moe.ono.hooks.dispatcher

import de.robv.android.xposed.XC_MethodHook
import moe.ono.hooks._base.ApiHookItem
import moe.ono.hooks._core.annotation.HookItem
import moe.ono.hooks.item.chat.MessageEncryptor
import moe.ono.hooks.item.chat.FakeFileRecall
import moe.ono.hooks.item.chat.StickerPanelEntry
import moe.ono.hooks.item.developer.QQMessageExtractor
import moe.ono.hooks.item.developer.QQMessageFetcher
import moe.ono.hooks.item.entertainment.ModifyTextMessage
import moe.ono.hooks.item.entertainment.RespondFace
import moe.ono.hooks.item.sigma.QQMessageTracker
import moe.ono.util.Logger
import java.lang.reflect.Modifier

@HookItem(path = "API/对应类型消息菜单构建时回调接口")
class MenuBuilderHook : ApiHookItem() {
    private val decorators: Array<OnMenuBuilder> = arrayOf(
        StickerPanelEntry(),
        QQMessageTracker(),
        QQMessageFetcher(),
        QQMessageExtractor(),
        ModifyTextMessage(),
        RespondFace(),
        MessageEncryptor(),
        FakeFileRecall(),
    )

    override fun entry(classLoader: ClassLoader) {
        // QQ 9.8.5+ changes component implementations across gray releases. Resolve
        // optional host APIs defensively so a single missing class/signature never
        // prevents the remaining message-menu decorators from being installed.
        val baseClass = runCatching {
            classLoader.loadClass("com.tencent.mobileqq.aio.msglist.holder.component.BaseContentComponent")
        }.getOrNull()
        val msgItemClass = runCatching {
            classLoader.loadClass("com.tencent.mobileqq.aio.msg.AIOMsgItem")
        }.getOrNull()

        val baseGetMsg = baseClass?.let { findMessageItemGetter(it, msgItemClass) }
        val baseGetList = baseClass?.let { findNoArgListMethod(it, abstractOnly = true) }
        val preferredListName = baseGetList?.name
        val targets = decorators.flatMap { it.targetTypes.asIterable() }.toSet()
        var hooked = 0
        var skipped = 0

        targets.forEach { targetName ->
            try {
                val targetClass = classLoader.loadClass(targetName)
                val listMethod = findListMethod(targetClass, preferredListName)
                val messageGetter = baseGetMsg
                    ?: findMessageItemGetter(targetClass, msgItemClass)
                    ?: baseClass?.let { findMessageItemGetter(it, msgItemClass) }

                if (listMethod == null || messageGetter == null) {
                    skipped++
                    Logger.w("MenuBuilderHook: skip $targetName; compatible menu/message method not found")
                    return@forEach
                }

                messageGetter.isAccessible = true
                listMethod.isAccessible = true
                hookAfter(listMethod) { param ->
                    try {
                        val item = messageGetter.invoke(param.thisObject) ?: return@hookAfter
                        decorators.forEach { decorator ->
                            if (targetName in decorator.targetTypes) {
                                runCatching {
                                    decorator.onGetMenu(item, targetName, param)
                                }.onFailure { failure ->
                                    Logger.e("MenuBuilderHook decorator failed: $targetName / " + decorator.javaClass.simpleName, failure)
                                }
                            }
                        }
                    } catch (failure: Throwable) {
                        Logger.e("MenuBuilderHook callback failed: $targetName", failure)
                    }
                }
                hooked++
            } catch (failure: Throwable) {
                skipped++
                Logger.w("MenuBuilderHook: unavailable component $targetName (" + failure.javaClass.simpleName + ")")
            }
        }
        Logger.i("MenuBuilderHook: installed $hooked component hooks; skipped $skipped")
    }

    private fun hierarchyMethods(type: Class<*>): List<java.lang.reflect.Method> {
        val result = ArrayList<java.lang.reflect.Method>()
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            result.addAll(current.declaredMethods)
            current = current.superclass
        }
        return result
    }

    private fun findNoArgListMethod(
        type: Class<*>,
        abstractOnly: Boolean
    ): java.lang.reflect.Method? {
        return hierarchyMethods(type).firstOrNull { method ->
            method.parameterTypes.isEmpty() &&
                java.util.List::class.java.isAssignableFrom(method.returnType) &&
                !Modifier.isStatic(method.modifiers) &&
                (!abstractOnly || Modifier.isAbstract(method.modifiers))
        }
    }

    private fun findListMethod(
        targetClass: Class<*>,
        preferredName: String?
    ): java.lang.reflect.Method? {
        val methods = hierarchyMethods(targetClass).filter { method ->
            method.parameterTypes.isEmpty() &&
                java.util.List::class.java.isAssignableFrom(method.returnType) &&
                !Modifier.isStatic(method.modifiers) &&
                !method.isBridge && !method.isSynthetic
        }
        if (preferredName != null) {
            methods.firstOrNull { it.name == preferredName }?.let { return it }
        }
        // Fallback only when unambiguous: guessing among multiple zero-arg List
        // getters can hook an unrelated collection and break QQ's message menu.
        val distinct = methods.distinctBy { it.name + "#" + it.returnType.name }
        return distinct.singleOrNull()
    }

    private fun findMessageItemGetter(
        type: Class<*>,
        msgItemClass: Class<*>?
    ): java.lang.reflect.Method? {
        return hierarchyMethods(type).firstOrNull { method ->
            method.parameterTypes.isEmpty() &&
                !Modifier.isStatic(method.modifiers) &&
                !method.isBridge && !method.isSynthetic &&
                (
                    (msgItemClass != null && method.returnType == msgItemClass) ||
                        method.returnType.simpleName == "AIOMsgItem"
                    )
        }
    }
}

interface OnMenuBuilder {
    val targetTypes: Array<String>
        get() = arrayOf(
            "com.tencent.mobileqq.aio.aiogift.AIOTroopGiftComponent",                     // 群礼物
            "com.tencent.mobileqq.aio.msglist.holder.component.anisticker.AIOAniStickerContentComponent", // 动态表情
            "com.tencent.mobileqq.aio.msglist.holder.component.ark.AIOArkContentComponent",               // Ark 卡片
            "com.tencent.mobileqq.aio.msglist.holder.component.ark.AIOCenterArkContentComponent",         // Ark 中央卡片
            "com.tencent.mobileqq.aio.msglist.holder.component.avatar.AIOAvatarContentComponent",         // 头像
            "com.tencent.mobileqq.aio.msglist.holder.component.chain.ChainAniStickerContentComponent",    // 连续动态表情
            "com.tencent.mobileqq.aio.msglist.holder.component.facebubble.AIOFaceBubbleContentComponent", // 表情泡泡
            "com.tencent.mobileqq.aio.msglist.holder.component.file.AIOFileContentComponent",             // 本地文件
            "com.tencent.mobileqq.aio.msglist.holder.component.file.AIOOnlineFileContentComponent",       // 在线文件
            "com.tencent.mobileqq.aio.msglist.holder.component.flashpic.AIOFlashPicContentComponent",     // 闪照
            "com.tencent.mobileqq.aio.msglist.holder.component.fold.AIOFoldContentComponent",             // 折叠消息
            "com.tencent.mobileqq.aio.msglist.holder.component.ickbreak.AIOIceBreakContentComponent",     // 破冰消息
            "com.tencent.mobileqq.aio.msglist.holder.component.leftswipearea.AIOLeftSwipeAreaComponent",  // 左滑区域
            "com.tencent.mobileqq.aio.msglist.holder.component.longmsg.AIOLongMsgContentComponent",       // 长消息
            "com.tencent.mobileqq.aio.msglist.holder.component.markdown.AIORichContentComponent",         // 富文本 / Markdown
            "com.tencent.mobileqq.aio.msglist.holder.component.markdown.AIOMarkdownContentComponent",     // Markdown 富文本
            "com.tencent.mobileqq.aio.msglist.holder.component.marketface.AIOMarketFaceComponent",        // 商城表情
            "com.tencent.mobileqq.aio.msglist.holder.component.mask.AIOContentMaskComponent",             // 内容蒙版
            "com.tencent.mobileqq.aio.msglist.holder.component.mix.AIOMixContentComponent",               // 图文混排
            "com.tencent.mobileqq.aio.msglist.holder.component.msgaction.AIOMsgRecommendComponent",       // 推荐操作
            "com.tencent.mobileqq.aio.msglist.holder.component.msgfollow.AIOMsgFollowComponent",          // 关注提示
            "com.tencent.mobileqq.aio.msglist.holder.component.msgreply.AIOMsgItemReplyComponent",        // 回复子项
            "com.tencent.mobileqq.aio.msglist.holder.component.msgstatus.AIOMsgStatusComponent",          // 发送状态
            "com.tencent.mobileqq.aio.msglist.holder.component.msgtail.AIOGeneralMsgTailContentComponent",// 消息尾
            "com.tencent.mobileqq.aio.msglist.holder.component.multifoward.AIOMultifowardContentComponent",// 合并转发
            "com.tencent.mobileqq.aio.msglist.holder.component.multipci.AIOMultiPicContentComponent",     // 多图
            "com.tencent.mobileqq.aio.msglist.holder.component.nick.AIONickComponent",                    // 昵称
            "com.tencent.mobileqq.aio.msglist.holder.component.pic.AIOPicContentComponent",               // 单图
            "com.tencent.mobileqq.aio.msglist.holder.component.poke.AIOPokeContentComponent",             // 戳一戳
            "com.tencent.mobileqq.aio.msglist.holder.component.position.AIOPositionMsgComponent",         // 地图位置
            "com.tencent.mobileqq.aio.msglist.holder.component.prologue.AIOPrologueContentComponent",     // 新手引导
            "com.tencent.mobileqq.aio.msglist.holder.component.ptt.AIOPttContentComponent",               // 语音
            "com.tencent.mobileqq.aio.msglist.holder.component.reply.AIOReplyComponent",                  // 回复面板
            "com.tencent.mobileqq.aio.msglist.holder.component.select.AIOSelectComponent",                // 选择器
            "com.tencent.mobileqq.aio.msglist.holder.component.sysface.AIOSingleSysFaceContentComponent", // 系统表情
            "com.tencent.mobileqq.aio.msglist.holder.component.template.AIOTemplateMsgComponent",         // 模板消息
            "com.tencent.mobileqq.aio.msglist.holder.component.text.AIOTextContentComponent",             // 文本
            "com.tencent.mobileqq.aio.msglist.holder.component.text.AIOUnsuportContentComponent",         // 不支持的文本
            "com.tencent.mobileqq.aio.msglist.holder.component.timestamp.AIOTimestampComponent",          // 时间戳
            "com.tencent.mobileqq.aio.msglist.holder.component.tofu.AIOTofuContentComponent",             // 豆腐卡片
            "com.tencent.mobileqq.aio.msglist.holder.component.video.AIOVideoContentComponent",           // 视频
            "com.tencent.mobileqq.aio.msglist.holder.component.videochat.AIOVideoResultContentComponent", // 视频通话结果
            "com.tencent.mobileqq.aio.msglist.holder.component.zplan.AIOZPlanContentComponent",           // Z 运营位
            "com.tencent.mobileqq.aio.msglist.holder.component.graptips.common.CommonGrayTipsComponent",  // 提示文本
            "com.tencent.mobileqq.aio.msglist.holder.component.graptips.revoke.RevokeGrayTipsComponent",  // 撤回提示
            "com.tencent.mobileqq.aio.msglist.holder.component.LocationShare.AIOLocationShareComponent",  // 位置共享
            "com.tencent.mobileqq.aio.qwallet.AIOQWalletComponent",                                       // 红包转账
            "com.tencent.mobileqq.aio.shop.AIOShopArkContentComponent",                                   // 商城 Ark 卡片
            "com.tencent.qqnt.aio.sample.BusinessSampleContentComponent",                                 // 业务示例
            "com.tencent.mobileqq.aio.msglist.holder.component.template.AIOTemplateMsgComponent",          // 新版合并转发
            "com.tencent.mobileqq.aio.msglist.holder.component.troopdragonladder.TroopDragonLadderComponent",// 群接龙
            "com.tencent.mobileqq.aio.msglist.holder.component.filtervideo.AIOLiveVideoContentComponent", // 泡泡消息
            "com.tencent.mobileqq.aio.msglist.holder.component.aiaction.AIAvatarActionContentComponent",  // 超级 QQ 秀动作表情
        )

    fun onGetMenu(
        aioMsgItem: Any,
        targetType: String,
        param: XC_MethodHook.MethodHookParam
    )
}
