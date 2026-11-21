package moe.ono.hooks.item.chat

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lxj.xpopup.XPopup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedHelpers
import moe.ono.R
import moe.ono.bridge.ntapi.ChatTypeConstants
import moe.ono.config.CacheConfig
import moe.ono.config.ConfigManager
import moe.ono.constants.Constants
import moe.ono.creator.FakeFileSender
import moe.ono.creator.GetChannelArkDialog
import moe.ono.creator.JumpSchemeUriDialog
import moe.ono.creator.PacketHelperDialog
import moe.ono.creator.QQMessageTrackerDialog
import moe.ono.dexkit.TargetManager
import moe.ono.hooks.XHook
import moe.ono.hooks._base.BaseClickableFunctionHookItem
import moe.ono.hooks._core.annotation.HookItem
import moe.ono.hooks._core.factory.HookItemFactory.getItem
import moe.ono.hooks.base.util.Toasts
import moe.ono.hooks.item.developer.GetBknByCookie
import moe.ono.hooks.item.developer.GetCookie
import moe.ono.hooks.item.developer.JumpSchemeUri
import moe.ono.hooks.item.developer.QQHookCodec
import moe.ono.hooks.item.developer.QQPacketHelperEntry
import moe.ono.hooks.item.sigma.QQMessageTracker
import moe.ono.loader.hookapi.IShortcutMenu
import moe.ono.reflex.XMethod
import moe.ono.ui.CommonContextWrapper
import moe.ono.util.Initiator
import moe.ono.util.Logger
import moe.ono.util.Session
import moe.ono.util.SyncUtils
import java.util.Collections
import java.util.WeakHashMap

@SuppressLint("DiscouragedApi")
@HookItem(
    path = "聊天与消息/快捷菜单",
    description = "点击查看调出方式，部分功能依赖此选项，推荐开启\n* 重启生效"
)
class BottomShortcutMenu : BaseClickableFunctionHookItem() {
    private val classNames: List<String> = mutableListOf(
        "com.tencent.mobileqq.aio.msglist.holder.component.avatar.AIOAvatarContentComponent",
        "com.tencent.mobileqq.aio.msglist.holder.component.nick.block.MainNickNameBlock"
    )

    private fun hook() {
        val mode = ConfigManager.dGetInt(Constants.PrekCfgXXX + "bottom_shortcut_menu_mode", 0)
        if (mode == 1) {
            // Red-packet mode remains unchanged.
            try {
                val method = XMethod.clz(Constants.CLAZZ_PANEL_ICON_LINEAR_LAYOUT)
                    .ret(ImageView::class.java)
                    .ignoreParam()
                    .get()

                hookAfter(method) { param: MethodHookParam ->
                    if (!isSupportedChat() || !this.isEnabled) return@hookAfter
                    val imageView = param.result as? ImageView ?: return@hookAfter
                    if (containsLabel(imageView, "红包")) {
                        imageView.setOnLongClickListener { view ->
                            val fixContext = CommonContextWrapper.createAppCompatContext(imageView.context)
                            popMenu(fixContext, view)
                            true
                        }
                    }
                }
            } catch (e: Throwable) {
                Logger.e("BottomShortcutMenu red-packet hook", e)
            }
        } else {
            hookBubbleMessageMode()
        }

        // The nickname/avatar hooks are independent from the shortcut-bar hook.
        runCatching { processForInstance() }
            .onFailure { Logger.e("BottomShortcutMenu processForInstance", it) }
    }

    /**
     * QQ 9.3.85 uses PanelIconLinearLayout -> RedTouch -> ImageView.
     * Keep the legacy method when it exists, and also observe the QQNT shortcut bar.
     */
    private fun hookBubbleMessageMode() {
        // Legacy route for older QQ layouts.
        runCatching {
            val method = TargetManager.requireMethod(Constants.MethodCacheKey_ChatPanelBtn)
            if (method != null) {
                hookAfter(method) { param: MethodHookParam ->
                    if (!this.isEnabled || !isSupportedChat()) return@hookAfter
                    val root = param.thisObject as? ViewGroup ?: return@hookAfter
                    installBubbleMenu(root)
                }
            }
        }.onFailure {
            Logger.w("BottomShortcutMenu: legacy ChatPanelBtn hook unavailable; using QQNT shortcut bar")
        }

        // The method used by the red-packet mode also gives us a stable QQNT entry point.
        runCatching {
            val method = XMethod.clz(Constants.CLAZZ_PANEL_ICON_LINEAR_LAYOUT)
                .ret(ImageView::class.java)
                .ignoreParam()
                .get()

            hookAfter(method) { param: MethodHookParam ->
                if (!this.isEnabled || !isSupportedChat()) return@hookAfter
                val panel = (param.thisObject as? View)?.let { findPanelIconRoot(it) }
                    ?: (param.result as? View)?.let { findPanelIconRoot(it) }
                if (panel != null) {
                    panel.post {
                        if (this.isEnabled && isSupportedChat()) installBubbleMenu(panel)
                    }
                }
            }
        }.onFailure {
            Logger.w("BottomShortcutMenu: QQNT PanelIconLinearLayout image method was not found")
        }

        // Catch children added after hook initialization (including RedTouch wrappers).
        runCatching {
            val onViewAdded = ViewGroup::class.java
                .getDeclaredMethod("onViewAdded", View::class.java)
                .apply { isAccessible = true }

            hookAfter(onViewAdded) { param: MethodHookParam ->
                val parent = param.thisObject as? ViewGroup ?: return@hookAfter
                val name = parent.javaClass.name
                if (name != Constants.CLAZZ_PANEL_ICON_LINEAR_LAYOUT && name != RED_TOUCH_CLASS) {
                    return@hookAfter
                }
                if (!this.isEnabled || !isSupportedChat()) return@hookAfter

                val panel = if (name == Constants.CLAZZ_PANEL_ICON_LINEAR_LAYOUT) {
                    parent
                } else {
                    findPanelIconRoot(parent)
                } ?: return@hookAfter

                panel.post {
                    if (this.isEnabled && isSupportedChat()) installBubbleMenu(panel)
                }
            }
        }.onFailure {
            Logger.e("BottomShortcutMenu: QQNT ViewGroup child observer failed", it)
        }
    }

    private fun isSupportedChat(): Boolean = runCatching {
        val type = Session.getContact().chatType
        type == ChatTypeConstants.C2C || type == ChatTypeConstants.GROUP
    }.getOrDefault(false)

    /**
     * QQ 9.8.5+ may expose "泡泡消息", English labels, or no useful contentDescription.
     * Prefer a semantic match; if QQ removes all semantics, use a conservative toolbar
     * fallback that excludes clearly identified actions like红包/语音/图片.
     */
    /**
     * Scan the real QQNT shortcut-bar subtree. In 9.3.85 the bubble action is
     * an ImageView nested inside the first RedTouch wrapper (toolbar child #4).
     */
    private fun installBubbleMenu(root: ViewGroup) {
        val panel = if (root.javaClass.name == Constants.CLAZZ_PANEL_ICON_LINEAR_LAYOUT) {
            root
        } else {
            findPanelIconRoot(root) ?: root
        }

        val images = collectImageViews(panel)
        val semanticMatches = images.filter { view ->
            val label = viewSemanticLabel(view)
            label.contains("泡泡") ||
                label.contains("bubble") ||
                label.contains("filtervideo") ||
                label.contains("bubblevideo") ||
                label.contains("livevideo")
        }

        // Do not guess among every ImageView. Use the exact container signature
        // observed on QQ 9.3.85 only when no semantic label is exposed.
        val candidates = if (semanticMatches.isNotEmpty()) {
            semanticMatches
        } else {
            listOfNotNull(
                if (panel.javaClass.name == Constants.CLAZZ_PANEL_ICON_LINEAR_LAYOUT) {
                    findQQ935BubbleAnchor(panel)
                } else {
                    null
                }
            )
        }

        candidates.distinct().forEach { attachBubbleMenuAnchor(it) }
        if (candidates.isEmpty()) {
            Logger.w(
                "BottomShortcutMenu: bubble anchor not identified; " +
                    "panelClass=${panel.javaClass.name}, childCount=${panel.childCount}"
            )
        }
    }

    private fun collectImageViews(root: View): List<ImageView> {
        val result = ArrayList<ImageView>()
        var visited = 0

        fun visit(view: View, depth: Int) {
            if (visited++ >= 160 || depth > 8 || result.size >= 48) return
            if (view is ImageView && view.tag != BUBBLE_MENU_MARKER_TAG) {
                result.add(view)
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    visit(view.getChildAt(index), depth + 1)
                }
            }
        }

        visit(root, 0)
        return result
    }

    private fun findQQ935BubbleAnchor(panel: ViewGroup): ImageView? {
        // Device report: six direct slots; child 3 is RedTouch at x=540..720,
        // with one ImageView child. This avoids selecting the neighboring actions.
        if (panel.childCount < 6) return null
        val bubbleSlot = panel.getChildAt(3) as? ViewGroup ?: return null
        if (bubbleSlot.javaClass.name != RED_TOUCH_CLASS) return null
        return collectImageViews(bubbleSlot).singleOrNull()
    }

    private fun findPanelIconRoot(view: View): ViewGroup? {
        var current: View? = view
        while (current != null) {
            if (current is ViewGroup &&
                current.javaClass.name == Constants.CLAZZ_PANEL_ICON_LINEAR_LAYOUT
            ) {
                return current
            }
            current = current.parent as? View
        }
        return null
    }

    private fun findDirectChildUnder(root: ViewGroup, descendant: View): View? {
        var current = descendant
        var parent = current.parent as? View
        while (parent != null && parent !== root) {
            current = parent
            parent = current.parent as? View
        }
        return current.takeIf { parent === root }
    }

    private fun viewSemanticLabel(view: View): String {
        val description = view.contentDescription?.toString().orEmpty()
        val tag = view.tag?.toString().orEmpty()
        val idName = if (view.id != View.NO_ID) {
            runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("")
        } else ""
        return listOf(description, tag, idName, view.javaClass.simpleName)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .lowercase(java.util.Locale.ROOT)
    }

    private fun containsLabel(view: View, label: String): Boolean =
        viewSemanticLabel(view).contains(label.lowercase(java.util.Locale.ROOT))

    private fun attachBubbleMenuAnchor(anchor: ImageView) {
        synchronized(trackedBubbleAnchors) {
            if (!trackedBubbleAnchors.add(anchor)) return
        }
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                addBubbleMenuMarker(v)
            }
            override fun onViewDetachedFromWindow(v: View) = Unit
        }
        anchor.addOnAttachStateChangeListener(listener)
        if (anchor.isAttachedToWindow) addBubbleMenuMarker(anchor)
    }

    private fun addBubbleMenuMarker(anchor: View) {
        anchor.post {
            if (!anchor.isAttachedToWindow) return@post

            val panel = findPanelIconRoot(anchor)
            val anchorParent = anchor.parent as? ViewGroup ?: return@post

            // RedTouch is a frame-style wrapper on QQ 9.3.85. Prefer a small
            // in-slot overlay so the six toolbar cells do not get pushed off-screen.
            val overlayParent = anchorParent.takeIf {
                it.javaClass.name == RED_TOUCH_CLASS && it is FrameLayout
            }

            val insertionParent = overlayParent ?: panel ?: anchorParent
            if ((0 until insertionParent.childCount).any {
                    insertionParent.getChildAt(it).tag == BUBBLE_MENU_MARKER_TAG
                }
            ) return@post

            val density = anchor.resources.displayMetrics.density
            val markerSize = (18f * density).toInt().coerceAtLeast(1)
            val marker = ImageView(anchor.context).apply {
                tag = BUBBLE_MENU_MARKER_TAG
                contentDescription = "TNT 快捷菜单"
                setImageResource(R.drawable.ic_module_settings)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(
                    (2f * density).toInt(),
                    (2f * density).toInt(),
                    (2f * density).toInt(),
                    (2f * density).toInt()
                )
                isClickable = true
                isFocusable = true
                setOnClickListener { clicked ->
                    runCatching {
                        popMenu(CommonContextWrapper.createAppCompatContext(clicked.context), clicked)
                    }.onFailure { Logger.e("TNT bubble shortcut menu", it) }
                }
            }

            runCatching {
                when {
                    overlayParent != null -> {
                        val params = FrameLayout.LayoutParams(
                            markerSize,
                            markerSize,
                            Gravity.END or Gravity.CENTER_VERTICAL
                        ).apply {
                            marginEnd = (2f * density).toInt()
                        }
                        overlayParent.addView(marker, params)
                    }
                    panel != null -> {
                        val slot = findDirectChildUnder(panel, anchor) ?: return@runCatching
                        val index = panel.indexOfChild(slot)
                        if (index < 0) return@runCatching
                        panel.addView(
                            marker,
                            (index + 1).coerceAtMost(panel.childCount),
                            ViewGroup.LayoutParams(markerSize, markerSize)
                        )
                    }
                    else -> {
                        val index = insertionParent.indexOfChild(anchor)
                        if (index < 0) return@runCatching
                        insertionParent.addView(
                            marker,
                            (index + 1).coerceAtMost(insertionParent.childCount),
                            ViewGroup.LayoutParams(markerSize, markerSize)
                        )
                    }
                }
            }.onFailure { Logger.e("BottomShortcutMenu bubble marker attach", it) }
        }
    }

    private fun popMenu(fixCtx: Context, view: View) {
        val sendFakeFile =
            ConfigManager.getDefaultConfig().getBooleanOrFalse(Constants.PrekSendFakeFile)
        val qqPacketHelper = ConfigManager.getDefaultConfig().getBooleanOrFalse(
            Constants.PrekClickableXXX + getItem(
                QQPacketHelperEntry::class.java
            ).path
        )
        val qqMessageTracker = ConfigManager.getDefaultConfig().getBooleanOrFalse(
            Constants.PrekClickableXXX + getItem(
                QQMessageTracker::class.java
            ).path
        )

        val messageEncryptor = ConfigManager.getDefaultConfig().getBooleanOrFalse(
            Constants.PrekClickableXXX + getItem(
                MessageEncryptor::class.java
            ).path
        )

        val getCookie = ConfigManager.getDefaultConfig().getBooleanOrFalse(Constants.PrekXXX + getItem(
            GetCookie::class.java).path)

        val getBknByCookie = ConfigManager.getDefaultConfig().getBooleanOrFalse(Constants.PrekXXX + getItem(
            GetBknByCookie::class.java).path)

        val getChannelArk = ConfigManager.getDefaultConfig().getBooleanOrFalse(Constants.PrekXXX + getItem(
            GetChannelArk::class.java).path)

        val jumpSchemeUri = ConfigManager.getDefaultConfig().getBooleanOrFalse(Constants.PrekXXX + getItem(
            JumpSchemeUri::class.java).path)

        val items = ArrayList<String>()
        if (qqPacketHelper) {
            items.add("QQPacketHelper")
        }
        if (sendFakeFile) {
            items.add("假文件")
        }
        if (qqMessageTracker) {
            items.add("已读追踪")
        }
        if (getCookie) {
            items.add("获取 Cookie")
        }
        if (getBknByCookie) {
            items.add("获取 Bkn")
        }
        if (getChannelArk) {
            items.add("获取频道卡片")
        }
        if (jumpSchemeUri) {
            items.add("打开 Scheme 链接")
        }

        menus.forEach { menu ->
            if (menu.isAdd()) {
                items.add(menu.menuName)
            }
        }

        if (getItem(QQHookCodec::class.java).isEnabled) {
            if (!messageEncryptor) {
                items.add("开启加密抄送")
            } else {
                items.add("关闭加密抄送")
            }
        }



        items.add("匿名化")
        XPopup.Builder(fixCtx)
            .hasShadowBg(false)
            .atView(view)
            .asAttachList(
                items.toTypedArray<String>(),
                intArrayOf()
            ) { _: Int, text: String? ->
                menus.forEach { menu ->
                    if (text == menu.menuName) {
                        menu.clickHandle(view.context)
                        return@asAttachList
                    }
                }

                when (text) {
                    "QQPacketHelper" -> SyncUtils.runOnUiThread {
                        PacketHelperDialog.createView(
                            null,
                            view.context,
                            ""
                        )
                    }

                    "匿名化" -> autoMosaicNameNT()
                    "假文件" -> try {
                        SyncUtils.runOnUiThread { FakeFileSender.createView(view.context) }
                    } catch (e: Exception) {
                        Toasts.error(view.context, "请求失败")
                    }
                    "已读追踪" -> try {
                        SyncUtils.runOnUiThread { QQMessageTrackerDialog.createView(view.context) }
                    } catch (e: Exception) {
                        Toasts.error(view.context, "请求失败")
                    }
                    "开启加密抄送" -> {
                        ConfigManager.dPutBoolean(
                            Constants.PrekClickableXXX + getItem(
                                MessageEncryptor::class.java
                            ).path, true
                        )
                    }
                    "关闭加密抄送" -> {
                        ConfigManager.dPutBoolean(
                            Constants.PrekClickableXXX + getItem(
                                MessageEncryptor::class.java
                            ).path, false
                        )
                    }
                    "获取 Cookie" -> {
                        SyncUtils.runOnUiThread {
                            val builder = MaterialAlertDialogBuilder(
                                CommonContextWrapper.createAppCompatContext(view.context)
                            )

                            builder.setTitle("请输入域名")

                            val domain =
                                EditText(CommonContextWrapper.createAppCompatContext(view.context))
                            domain.setHint("请输入域名")
                            domain.setText("qzone.qq.com")

                            builder.setView(domain)

                            builder.setNegativeButton("取消") { dialog, i ->
                                dialog.dismiss()
                            }
                            builder.setPositiveButton("确定") { dialog, i ->
                                GetCookie.getCookie(view.context, domain.text.toString())
                            }

                            builder.show()
                        }
                    }
                    "获取 Bkn" -> {
                        SyncUtils.runOnUiThread {
                            val builder = MaterialAlertDialogBuilder(CommonContextWrapper.createAppCompatContext(view.context))

                            builder.setTitle("请输入 Cookie")

                            val cookie = EditText(CommonContextWrapper.createAppCompatContext(view.context))
                            cookie.setHint("请输入 Cookie")

                            builder.setView(cookie)

                            builder.setNegativeButton("取消") { dialog, i ->
                                dialog.dismiss()
                            }
                            builder.setPositiveButton("确定") { dialog, i ->
                                GetBknByCookie.getBkn(CommonContextWrapper.createAppCompatContext(view.context), cookie.text.toString())
                            }

                            builder.show()
                        }
                    }
                    "获取频道卡片" -> {
                        SyncUtils.runOnUiThread { GetChannelArkDialog.createView(view.context) }
                    }
                    "打开 Scheme 链接" -> {
                        SyncUtils.runOnUiThread { JumpSchemeUriDialog.createView(view.context) }
                    }
                }
            }
            .show()
    }


    private fun processForInstance() {
        // com.tencent.mobileqq.aio.msglist.holder.component.avatar.AIOAvatarContentComponent
        try {
            val clazz = checkNotNull(Initiator.load(classNames[0]))
            for (method in clazz.declaredMethods) {
                try {
                    if (method.parameterCount == 1 && method.parameterTypes[0] == java.lang.Boolean.TYPE) {
                        componentMethodName = method.name

                        XHook.hookBefore(method) { param: MethodHookParam ->
                            if (instanceForComponent.size >= MAX_SIZE) {
                                instanceForComponent.removeAt(0)
                            }
                            instanceForComponent.add(param.thisObject)
                            if (CacheConfig.isAutoMosaicNameNT()) {
                                param.args[0] = true
                            }
                        }
                    }
                } catch (ignored: ArrayIndexOutOfBoundsException) {
                }
            }
        } catch (e: Exception) {
            Logger.e(e)
        }

        // com.tencent.mobileqq.aio.msglist.holder.component.nick.block.MainNickNameBlock
        try {
            val clazz = checkNotNull(Initiator.load(classNames[1]))
            for (method in clazz.declaredMethods) {
                try {
                    if (method.parameterCount == 1 && method.parameterTypes[0] == java.lang.Boolean.TYPE) {
                        componentMethodNameForMainNickNameBlock = method.name
                        XHook.hookBefore(method) { param: MethodHookParam ->
                            if (instancesForMainNickNameBlock.size >= MAX_SIZE) {
                                instancesForMainNickNameBlock.removeAt(
                                    0
                                )
                            }
                            instancesForMainNickNameBlock.add(param.thisObject)
                            if (CacheConfig.isAutoMosaicNameNT()) {
                                param.args[0] = true
                            }
                        }
                    }
                } catch (ignored: ArrayIndexOutOfBoundsException) {
                }
            }
        } catch (e: Exception) {
            Logger.e(e)
        }
    }


    private fun autoMosaicNameNT() {
        CacheConfig.setAutoMosaicNameNT(!CacheConfig.isAutoMosaicNameNT())

        val instancesCopy: List<Any> = ArrayList(instanceForComponent)

        for (instance in instancesCopy) {
            try {
                val matchingMethod = componentMethodName?.let {
                    instance.javaClass.getDeclaredMethod(
                        it,
                        Boolean::class.javaPrimitiveType
                    )
                }
                matchingMethod?.isAccessible = true
                matchingMethod?.invoke(instance, CacheConfig.isAutoMosaicNameNT())
            } catch (e: Exception) {
                Logger.e("Error invoking method on instance: " + e.message)
            }
        }

        val instancesCopyForMainNickNameBlock: List<Any> = ArrayList(
            instancesForMainNickNameBlock
        )

        for (instance in instancesCopyForMainNickNameBlock) {
            try {
                val matchingMethod = componentMethodNameForMainNickNameBlock?.let {
                    instance.javaClass.getDeclaredMethod(
                        it,
                        Boolean::class.javaPrimitiveType
                    )
                }
                matchingMethod?.isAccessible = true
                matchingMethod?.invoke(instance, CacheConfig.isAutoMosaicNameNT())
            } catch (e: Exception) {
                Logger.e("Error invoking method on instance: " + e.message)
            }
        }
    }

    @Throws(Throwable::class)
    override fun entry(classLoader: ClassLoader) {
        try {
            val clazz = Initiator.loadClass(Constants.CLAZZ_ACTIVITY_SPLASH)
            XposedHelpers.findAndHookMethod(clazz, "doOnCreate", Bundle::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        hook()
                    }
                })
        } catch (e: ClassNotFoundException) {
            Logger.e(this.itemName, e)
        }
    }

    companion object {
        private const val BUBBLE_MENU_MARKER_TAG = "tnt_bubble_menu_marker_v2"
        private const val RED_TOUCH_CLASS = "com.tencent.mobileqq.tianshu.ui.RedTouch"
        private val trackedBubbleAnchors: MutableSet<View> =
            Collections.newSetFromMap(WeakHashMap<View, Boolean>())
        private val instanceForComponent = ArrayList<Any>()
        private val instancesForMainNickNameBlock = ArrayList<Any>()
        private var componentMethodName: String? = null
        private var componentMethodNameForMainNickNameBlock: String? = null
        private const val MAX_SIZE = 15

        fun showCFGDialog(context: Context) {
            val fixContext = CommonContextWrapper.createAppCompatContext(context)

            val builder = MaterialAlertDialogBuilder(fixContext)
            builder.setTitle("快捷菜单")

            val layout = LinearLayout(fixContext).apply {
                orientation = LinearLayout.VERTICAL
                val pad = 32
                setPadding(pad, pad, pad, pad)
            }

            val checkBox = MaterialCheckBox(fixContext).apply {
                text = "启用"
                isChecked = ConfigManager.dGetBoolean(
                    Constants.PrekClickableXXX + getItem(BottomShortcutMenu::class.java).path
                )
                setOnCheckedChangeListener { _, p1 ->
                    ConfigManager.dPutBoolean(
                        Constants.PrekClickableXXX + getItem(BottomShortcutMenu::class.java).path,
                        p1
                    )
                    getItem(BottomShortcutMenu::class.java).isEnabled = p1
                    Toasts.success(fixContext, "重启生效")
                }
            }
            layout.addView(checkBox)

            val tips = listOf(
                "点击聊天界面下方泡泡消息按钮调出菜单",
                "长按聊天界面下方红包按钮调出菜单"
            )
            val currentIndex = ConfigManager.dGetInt(Constants.PrekCfgXXX + "bottom_shortcut_menu_mode", 0)

            val tipsText = TextView(fixContext).apply {
                text = tips[currentIndex]
                textSize = 14f
                setPadding(0, 16, 0, 0)
            }
            val dropdown = AutoCompleteTextView(fixContext).apply {
                val options = listOf("泡泡消息", "红包")
                isFocusable = false
                isCursorVisible = false
                isLongClickable = false
                keyListener = null
                inputType = 0
                setTextIsSelectable(false)
                setCompoundDrawablesWithIntrinsicBounds(
                    0, 0,
                    com.google.android.material.R.drawable.mtrl_dropdown_arrow, 0
                )
                compoundDrawablePadding = 16
                val adapter = ArrayAdapter(fixContext, android.R.layout.simple_list_item_1, options)
                setAdapter(adapter)
                setText(options[currentIndex], false)
                setOnClickListener { showDropDown() }
                setOnItemClickListener { _, _, position, _ ->
                    ConfigManager.dPutInt(
                        Constants.PrekCfgXXX + "bottom_shortcut_menu_mode",
                        position
                    )
                    tipsText.text = tips[position]
                }
            }
            val title = TextView(fixContext).apply {
                text = "选择 Hook 模式"
                textSize = 15f
                setPadding(0, 24, 0, 8)
            }
            layout.addView(title)
            layout.addView(dropdown)
            layout.addView(tipsText)

            builder.setView(layout)
            builder.setPositiveButton("确定") { dialog, _ -> dialog.dismiss() }
            builder.show()
        }
        val menus = arrayListOf<IShortcutMenu>()
    }
}