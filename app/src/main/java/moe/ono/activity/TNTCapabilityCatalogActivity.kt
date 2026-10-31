package moe.ono.activity

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import moe.ono.R

/** Evidence-labelled capability map; no decompiled third-party implementation is embedded. */
class TNTCapabilityCatalogActivity : BaseActivity() {
    private data class Entry(val title: String, val category: String, val status: String, val evidence: String, val note: String)

    private val categories = listOf("全部", "预设与卡片", "聊天消息", "媒体", "目录查询", "隐私控制", "主题")
    private val entries = listOf(
        Entry("本地卡片 / Ark 预设", "预设与卡片", "TNT 已具备本地版", "LocalCardPresetManager · load/save/import/export", "与 TNT 预设工坊重叠。TNT 已支持 name/content、AWA/黑壳分隔符、JSON 校验与本地导入导出；导入内容只作为数据，不执行。"),
        Entry("图片转卡片工作流", "预设与卡片", "可评估", "PicView2ArkMenu · UploadedPicArkFlow · SendUploadedPicArk", "符号指向图片选择、卡片字段编辑及上传结果处理。上传接口和会话校验未验证，当前只适合先做离线草稿。"),
        Entry("群聊 / 世界玩法卡片", "预设与卡片", "可评估", "WorldPlayCardMenu · GroupJumpArkMenu", "可见 buildArk、defaultValues、chooseImage、downloadRemotePreview、verifySession 等方法名。可做本地模板与 JSON 导出，不复制私有发送链路。"),
        Entry("消息元信息展示", "聊天消息", "可评估", "MessageMeta · MessageMetaDisplay · MessageMetaFormatter · MessageMetaConfig", "可研究对用户当前可见消息提供本地诊断展示，并允许关闭；不采集聊天记录或账号标识。"),
        Entry("表情互动菜单", "聊天消息", "需真机验证", "MessageEmojiLikesMenu", "类名提示有消息表情互动菜单，但触发条件、宿主接口与服务端结果不能由静态符号确认。"),
        Entry("快捷栏与菜单预设", "聊天消息", "可评估", "ShortcutBarHelper · ShortcutBarPresetHelper · ShortcutMenuPanel", "存在快捷栏配置、路由预设和菜单面板组件；TNT 当前搜索、分类、收藏为独立实现。"),
        Entry("图片消息链接工具", "媒体", "可评估", "CopyPictureLink.copy/normalize · PictureMessageTools · MagicPicPrefill", "符号包含链接规范化、复制和消息菜单挂接。实现时应由用户主动选择图片，不默认上传或外传。"),
        Entry("短视频分享链接解析", "媒体", "暂不移植", "DouyinVideoParser.extractShareUrl/fetch/parseResponse · DirectVideoCardSender", "存在链接解析与远程请求方向；接口授权和当前可用性未知。TNT 不绕过访问控制或抓取受限媒体。"),
        Entry("音乐选择器", "媒体", "暂不移植", "QqMusicSongPicker", "外部服务、版权授权和分享行为未验证，不复制第三方接口或受限媒体资源。"),
        Entry("SubId 目录与映射", "目录查询", "可评估", "SubIdCatalogUi · SubIdResolver · SubIdGitHubUpdater · SubIdCustomManager", "适合做本地只读目录；远程更新必须使用用户选择的可信 HTTPS 数据源，不携带 QQ Cookie 或 APK 凭证。"),
        Entry("应用信息 / App ID 查询", "目录查询", "暂不移植", "AppTreasureSearch.fetch/parseSearchPage · MagicAppInfoLookup", "存在远程查询线索，服务端与数据来源未经验证；不复用旧接口，可独立实现本地目录。"),
        Entry("位置消息操作", "隐私控制", "默认不启用", "LocationMessageMenu · LocationShareArkMenu", "位置读取或分享必须由用户主动选择并逐次确认，明确显示接收对象；不后台采集或自动广播位置。"),
        Entry("自动位置分享", "隐私控制", "不移植", "AutoLocationShare", "不加入后台自动定位或自动分享，避免扩大敏感位置数据暴露。"),
        Entry("RTC 来电处理", "隐私控制", "需真机验证", "AutoRejectRTC.inspect/targetProcess", "可评估用户显式配置的本地拒接选项；需按 QQ 版本测试，不进行隐蔽控制。"),
        Entry("长语音菜单 / 时长过滤", "隐私控制", "可评估", "LongVoiceMenu · BlockLongDurationVoice.thresholdSeconds/matches/filter", "可研究带开关、阈值及白名单的本地过滤；须验证边界，避免误过滤。"),
        Entry("群资料卡编辑", "聊天消息", "需真机验证", "SetGroupCard", "具体权限和 QQ 内部方法未验证，需由宿主权限控制并向用户显示执行结果。"),
        Entry("重复发送 / 自动红包", "隐私控制", "不移植", "LinkRepeater · CustomCardLongSender · FestivalRedPacket", "不复制循环群发、刷屏、红包自动化或任意协议包注入。"),
        Entry("自定义主题与背景", "主题", "仅借鉴布局", "GenshinTheme · GenshinBackground · GenshinSettingsSkin", "只参考导航、分组和表单布局，不复制未确认授权的字体、视频或图片资源。")
    )

    private lateinit var search: EditText
    private lateinit var list: LinearLayout
    private lateinit var summary: TextView
    private var selected = "全部"

    override fun doOnEarlyCreate(savedInstanceState: Bundle?, isInitializing: Boolean) {
        super.doOnEarlyCreate(savedInstanceState, isInitializing)
        setTheme(R.style.Theme_Ono)
    }

    override fun doOnCreate(savedInstanceState: Bundle?): Boolean {
        super.doOnCreate(null)
        title = "AWA / 黑壳能力清单"
        window.statusBarColor = Color.rgb(7, 14, 29)
        window.navigationBarColor = Color.rgb(7, 14, 29)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(22))
            setBackgroundColor(Color.rgb(7, 14, 29))
        }
        body.addView(TextView(this).apply {
            text = "AWA / 黑壳静态研究"
            textSize = 21f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        body.addView(TextView(this).apply {
            text = "AWA：12,441 类定义 · 2 个 DEX\n黑壳：12,835 类定义 · 3 个 DEX\n共有 12,439 个；黑壳独有 396 个，AWA 独有 2 个。\n\n类定义差异不等于独立功能数量，也不代表已在当前 QQ 版本验证。"
            textSize = 12f
            setTextColor(0xFFD5E4F8.toInt())
            setPadding(dp(13), dp(12), dp(13), dp(12))
            background = panel(0xFF142B50.toInt(), 0xFF315A89.toInt(), 15)
        }, fullWidth().apply { topMargin = dp(12) })
        body.addView(TextView(this).apply {
            text = "附件说明：awa-main.zip 仅包含 README 与编译后的 awa.apk，没有可直接构建的 Gradle 源码工程。本页是类名、方法名与资源线索的归纳；未执行真机运行或动态网络验证。"
            textSize = 12f
            setTextColor(0xFFFFD99A.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = panel(0xFF33291B.toInt(), 0xFF6C5634.toInt(), 12)
        }, fullWidth().apply { topMargin = dp(10) })

        search = EditText(this).apply {
            hint = "搜索功能名、类名或说明"
            singleLine = true
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF8293B0.toInt())
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(dp(13), dp(10), dp(13), dp(10))
            background = panel(0xFF111F36.toInt(), 0xFF263B5B.toInt(), 13)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = render()
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        body.addView(search, fullWidth().apply { topMargin = dp(14) })

        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        body.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, dp(10), 0, dp(6))
            addView(chips)
        })
        rebuildChips(chips)
        summary = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF8293B0.toInt())
            setPadding(dp(2), dp(5), dp(2), dp(8))
        }
        body.addView(summary)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(list)

        setContentView(ScrollView(this).apply { addView(body) })
        render()
        return true
    }

    private fun rebuildChips(row: LinearLayout) {
        row.removeAllViews()
        categories.forEach { name ->
            row.addView(TextView(this).apply {
                text = name
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setTextColor(if (selected == name) Color.WHITE else 0xFF9BAAC2.toInt())
                background = panel(
                    if (selected == name) 0xFF246FE8.toInt() else 0xFF111F36.toInt(),
                    if (selected == name) 0xFF4D92FF.toInt() else 0xFF263B5B.toInt(), 24
                )
                setOnClickListener { selected = name; rebuildChips(row); render() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply {
                rightMargin = dp(7)
            })
        }
    }

    private fun render() {
        if (!::list.isInitialized || !::search.isInitialized) return
        list.removeAllViews()
        val query = search.text.toString().trim().lowercase(Locale.ROOT)
        val shown = entries.filter { item ->
            (selected == "全部" || item.category == selected) &&
                listOf(item.title, item.category, item.status, item.evidence, item.note)
                    .any { query.isBlank() || it.lowercase(Locale.ROOT).contains(query) }
        }
        summary.text = "显示 " + shown.size + " / " + entries.size + " 项能力线索 · DEX 静态分析"
        shown.forEach { item ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(13), dp(12), dp(13), dp(12))
                background = panel(0xFF111F36.toInt(), 0xFF263B5B.toInt(), 14)
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(this).apply {
                text = item.title
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = item.status
                textSize = 10f
                setTextColor(
                    when {
                        item.status.contains("已具备") -> 0xFF7ED6B0.toInt()
                        item.status.contains("不移植") || item.status.contains("暂不移植") ||
                            item.status.contains("默认不启用") -> 0xFFFFA59E.toInt()
                        else -> 0xFFFFD27D.toInt()
                    }
                )
                setPadding(dp(7), dp(5), dp(7), dp(5))
                background = panel(0xFF1B2A40.toInt(), 0xFF40516D.toInt(), 9)
            })
            card.addView(row)
            card.addView(TextView(this).apply {
                text = item.category + " · " + item.evidence
                textSize = 10f
                setTextColor(0xFF8FAED4.toInt())
                setPadding(0, dp(7), 0, dp(5))
            })
            card.addView(TextView(this).apply {
                text = item.note
                textSize = 12f
                setTextColor(0xFFB9C7DC.toInt())
            })
            list.addView(card, fullWidth().apply { bottomMargin = dp(9) })
        }
        if (shown.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "没有找到匹配的功能线索，请调整搜索词或切换分类。"
                gravity = Gravity.CENTER
                setTextColor(0xFF8FA1BC.toInt())
                setPadding(dp(10), dp(28), dp(10), dp(28))
            })
        }
    }

    private fun panel(color: Int, stroke: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), stroke)
    }
    private fun fullWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}