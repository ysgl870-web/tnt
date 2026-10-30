package moe.ono.activity

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.ono.BuildConfig
import moe.ono.R
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/**
 * TNT launcher and local toolbox.
 *
 * This screen intentionally keeps utilities local-first. Sharing is delegated to Android's
 * visible Sharesheet; imported preset content is treated as data and is never executed.
 */
class TNTLauncherActivity : BaseActivity() {

    private data class ToolEntry(
        val id: String,
        val title: String,
        val summary: String,
        val category: String,
        val icon: String,
        val action: () -> Unit
    )

    private val launcherPrefs by lazy { getSharedPreferences("tnt_launcher_v2", MODE_PRIVATE) }
    private lateinit var toolGrid: GridLayout
    private lateinit var searchField: EditText
    private lateinit var resultSummary: TextView
    private var selectedCategory = "全部"
    private val categories = listOf("全部", "收藏", "常用", "内容工具", "开发辅助")
    private val tools by lazy { createTools() }

    override fun doOnEarlyCreate(savedInstanceState: Bundle?, isInitializing: Boolean) {
        super.doOnEarlyCreate(savedInstanceState, isInitializing)
        setTheme(R.style.Theme_Ono)
    }

    override fun doOnCreate(savedInstanceState: Bundle?): Boolean {
        super.doOnCreate(null)
        title = "TNT · 光流工具箱"
        window.statusBarColor = Color.rgb(7, 14, 29)
        window.navigationBarColor = Color.rgb(7, 14, 29)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(7, 14, 29))
        }
        val scroll = ScrollView(this).apply {
            clipToPadding = false
            isFillViewport = true
            setPadding(0, 0, 0, dp(10))
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(14), dp(15), dp(20))
        }
        buildHero(body)
        buildQuickActions(body)

        searchField = EditText(this).apply {
            hint = "搜索工具名称、说明或分类"
            textSize = 14f
            singleLine = true
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF8293B0.toInt())
            inputType = InputType.TYPE_CLASS_TEXT
            background = rounded(0xFF111F36.toInt(), 0xFF263B5B.toInt(), 14)
            setPadding(dp(14), dp(11), dp(14), dp(11))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = renderTools()
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        body.addView(searchField, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
        ).apply { topMargin = dp(16) })

        val categoryScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(0, dp(12), 0, dp(6))
        }
        val categoryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        categories.forEach { category ->
            val chip = TextView(this).apply {
                text = category
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(9), dp(14), dp(9))
                setTextColor(if (category == selectedCategory) Color.WHITE else 0xFF9BAAC2.toInt())
                background = rounded(
                    if (category == selectedCategory) 0xFF246FE8.toInt() else 0xFF111F36.toInt(),
                    if (category == selectedCategory) 0xFF4D92FF.toInt() else 0xFF263B5B.toInt(),
                    30
                )
                setOnClickListener {
                    selectedCategory = category
                    rebuildCategories(categoryRow)
                    renderTools()
                }
            }
            categoryRow.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)
            ).apply { rightMargin = dp(7) })
        }
        categoryScroll.addView(categoryRow)
        body.addView(categoryScroll)

        resultSummary = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF8293B0.toInt())
            setPadding(dp(2), dp(6), dp(2), dp(8))
        }
        body.addView(resultSummary)

        toolGrid = GridLayout(this).apply {
            columnCount = 2
            useDefaultMargins = false
            alignmentMode = GridLayout.ALIGN_BOUNDS
        }
        body.addView(toolGrid, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val footer = TextView(this).apply {
            text = "TNT · 亦是光流开发维护    |    本地优先 · 可控扩展 · 持续改进"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(0xFF657895.toInt())
            setPadding(0, dp(20), 0, dp(10))
        }
        body.addView(footer)

        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)
        renderTools()
        return true
    }

    private fun buildHero(body: LinearLayout) {
        val hero = MaterialCardView(this).apply {
            radius = dp(23).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = 0xFF315A9B.toInt()
            background = rounded(0xFF142B50.toInt(), 0xFF244D83.toInt(), 23)
            setCardBackgroundColor(Color.TRANSPARENT)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        brandRow.addView(TextView(this).apply {
            text = "T"
            textSize = 32f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = rounded(0xFF2478E8.toInt(), 0xFF69C8FF.toInt(), 15)
        }, LinearLayout.LayoutParams(dp(54), dp(54)))
        val titleColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        titleColumn.addView(TextView(this).apply {
            text = "TNT  光流工具箱"
            textSize = 21f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        titleColumn.addView(TextView(this).apply {
            text = "QQNT 增强 · 实用工具 · 模块化扩展"
            textSize = 12f
            setTextColor(0xFFD7E7FF.toInt())
            setPadding(0, dp(4), 0, 0)
        })
        brandRow.addView(titleColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(brandRow)
        content.addView(TextView(this).apply {
            text = "工具整合与功能扩展，让常用操作更清晰、更顺手。"
            textSize = 13f
            setTextColor(0xFFE7F0FF.toInt())
            setPadding(0, dp(14), 0, dp(10))
        })
        content.addView(TextView(this).apply {
            text = "Android " + Build.VERSION.RELEASE + "  ·  API " + Build.VERSION.SDK_INT +
                "  ·  TNT " + BuildConfig.VERSION_NAME
            textSize = 11f
            setTextColor(0xFFB3C9E9.toInt())
        })
        hero.addView(content)
        body.addView(hero, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
    }

    private fun buildQuickActions(body: LinearLayout) {
        val label = TextView(this).apply {
            text = "快速入口"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(2), dp(16), 0, dp(8))
        }
        body.addView(label)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val actions = listOf(
            Triple("预设工坊", "模板与云目录", "▣") {
                startActivity(Intent(this, TNTPresetStudioActivity::class.java))
            },
            Triple("模块设置", "功能开关与配置", "⚙") {
                startActivity(Intent(this, OUOSettingActivity::class.java))
            },
            Triple("关于 TNT", "作者与开源许可", "ⓘ") { aboutTool() }
        )
        actions.forEach { item ->
            val card = MaterialCardView(this).apply {
                radius = dp(14).toFloat()
                cardElevation = 0f
                strokeWidth = dp(1)
                strokeColor = 0xFF2B4162.toInt()
                setCardBackgroundColor(0xFF111F36.toInt())
                isClickable = true
                isFocusable = true
                setOnClickListener { item.third.invoke() }
            }
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(5), dp(10), dp(5), dp(10))
            }
            column.addView(TextView(this).apply {
                text = item.second
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(0xFF9FB0CA.toInt())
            })
            column.addView(TextView(this).apply {
                text = item.first
                textSize = 12f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                setPadding(0, dp(4), 0, 0)
            })
            card.addView(column)
            row.addView(card, LinearLayout.LayoutParams(0, dp(70), 1f).apply {
                rightMargin = dp(5)
                leftMargin = dp(5)
            })
        }
        body.addView(row)
    }

    private fun rebuildCategories(row: LinearLayout) {
        row.removeAllViews()
        categories.forEach { category ->
            val chip = TextView(this).apply {
                text = category
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(9), dp(14), dp(9))
                setTextColor(if (category == selectedCategory) Color.WHITE else 0xFF9BAAC2.toInt())
                background = rounded(
                    if (category == selectedCategory) 0xFF246FE8.toInt() else 0xFF111F36.toInt(),
                    if (category == selectedCategory) 0xFF4D92FF.toInt() else 0xFF263B5B.toInt(),
                    30
                )
                setOnClickListener {
                    selectedCategory = category
                    rebuildCategories(row)
                    renderTools()
                }
            }
            row.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)
            ).apply { rightMargin = dp(7) })
        }
    }

    private fun createTools(): List<ToolEntry> = listOf(
        ToolEntry("preset", "AWA / 黑壳预设工坊", "本地预设、批量导入导出、云目录预览与校验", "常用", "▣") {
            startActivity(Intent(this, TNTPresetStudioActivity::class.java))
        },
        ToolEntry("phrase", "快捷文案", "管理常用文本，快速复制或交给系统分享", "常用", "✎") { phraseTool() },
        ToolEntry("share", "系统分享助手", "使用 Android Sharesheet 分享文本与链接", "常用", "↗") { nativeShare() },
        ToolEntry("json", "JSON 美化 / 校验", "本地解析、格式化与结构检查", "内容工具", "{ }") { jsonTool() },
        ToolEntry("jsondiff", "JSON 差异比较", "比较两个 JSON 文档，定位新增、删除和修改字段", "内容工具", "⇄") { jsonDiffTool() },
        ToolEntry("link", "QQ Scheme / URL 分析", "解析协议、主机、路径与参数名，不访问目标地址", "内容工具", "⌁") { linkTool() },
        ToolEntry("encoding", "URL / Base64 编解码", "本地编码、解码与结果复制", "内容工具", "⌘") { encodingTool() },
        ToolEntry("text", "文本与字节统计", "字符、代码点、UTF-8 字节、行数与单词统计", "内容工具", "≡") { textTool() },
        ToolEntry("hash", "SHA-256 摘要", "在本机计算文本摘要，便于文件与配置校验", "开发辅助", "#") { hashTool() },
        ToolEntry("emoji", "常用表情复制", "点选常用 Emoji 并复制到剪贴板", "常用", "☺") { emojiTool() },
        ToolEntry("diagnostic", "环境诊断", "生成 Android、ABI、QQ 版本与模块信息", "开发辅助", "ⓘ") { copyDiagnostic() },
        ToolEntry("settings", "功能设置", "打开 TNT 模块功能开关与现有配置页面", "开发辅助", "⚙") {
            startActivity(Intent(this, OUOSettingActivity::class.java))
        },
        ToolEntry("about", "关于与开源许可", "查看作者信息、上游项目和 GPL-3.0 说明", "开发辅助", "©") { aboutTool() }
    )

    private fun renderTools() {
        if (!::toolGrid.isInitialized || !::searchField.isInitialized) return
        toolGrid.removeAllViews()
        val query = searchField.text.toString().trim().lowercase(Locale.ROOT)
        val favorites = launcherPrefs.getStringSet("favorites", emptySet()).orEmpty()
        val filtered = tools.filter { tool ->
            val matchesCategory = when (selectedCategory) {
                "全部" -> true
                "收藏" -> favorites.contains(tool.id)
                else -> selectedCategory == tool.category
            }
            val matchesQuery = query.isBlank() ||
                tool.title.lowercase(Locale.ROOT).contains(query) ||
                tool.summary.lowercase(Locale.ROOT).contains(query) ||
                tool.category.lowercase(Locale.ROOT).contains(query)
            matchesCategory && matchesQuery
        }.sortedWith(compareByDescending<ToolEntry> { favorites.contains(it.id) }.thenBy { tools.indexOf(it) })

        resultSummary.text = "共 " + filtered.size + " 项工具  ·  长按卡片可收藏 / 取消收藏"
        filtered.forEachIndexed { index, tool ->
            val card = MaterialCardView(this).apply {
                radius = dp(16).toFloat()
                cardElevation = 0f
                strokeWidth = dp(1)
                strokeColor = if (favorites.contains(tool.id)) 0xFF426DB0.toInt() else 0xFF263B5B.toInt()
                setCardBackgroundColor(0xFF111F36.toInt())
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    try {
                        tool.action.invoke()
                    } catch (t: Throwable) {
                        android.util.Log.e("TNT", "Tool failed: " + tool.id, t)
                        MaterialAlertDialogBuilder(this@TNTLauncherActivity)
                            .setTitle("工具暂时无法打开")
                            .setMessage(tool.title + "\n" + t.javaClass.simpleName + ": " + (t.message ?: "未知错误"))
                            .setPositiveButton("关闭", null).show()
                    }
                }
                setOnLongClickListener {
                    toggleFavorite(tool.id)
                    true
                }
            }
            val inner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                minimumHeight = dp(118)
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }
            val top = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            top.addView(TextView(this).apply {
                text = tool.icon
                textSize = 19f
                gravity = Gravity.CENTER
                setTextColor(0xFFBBD9FF.toInt())
                background = rounded(0xFF1B3760.toInt(), 0xFF315C96.toInt(), 12)
            }, LinearLayout.LayoutParams(dp(38), dp(38)))
            top.addView(TextView(this).apply {
                text = if (favorites.contains(tool.id)) "★" else "＋"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(if (favorites.contains(tool.id)) 0xFFFFD36B.toInt() else 0xFF7185A4.toInt())
            }, LinearLayout.LayoutParams(dp(22), dp(30)).apply { marginStart = dp(4) })
            inner.addView(top)
            inner.addView(TextView(this).apply {
                text = tool.title
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(10), 0, dp(4))
            })
            inner.addView(TextView(this).apply {
                text = tool.summary
                textSize = 11f
                setTextColor(0xFF9BAAC2.toInt())
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            card.addView(inner)
            val params = GridLayout.LayoutParams(
                GridLayout.spec(index / 2),
                GridLayout.spec(index % 2, 1f)
            ).apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            toolGrid.addView(card, params)
        }
        if (filtered.isEmpty()) {
            val empty = TextView(this).apply {
                text = if (selectedCategory == "收藏") "还没有收藏工具。长按工具卡片即可收藏。" else "没有找到匹配的工具。"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(0xFF8FA1BC.toInt())
                setPadding(dp(10), dp(28), dp(10), dp(28))
            }
            toolGrid.addView(empty, GridLayout.LayoutParams().apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(0, 2)
            })
        }
    }

    private fun toggleFavorite(id: String) {
        val current = launcherPrefs.getStringSet("favorites", emptySet()).orEmpty().toMutableSet()
        val added = if (current.contains(id)) {
            current.remove(id)
            false
        } else {
            current.add(id)
            true
        }
        launcherPrefs.edit().putStringSet("favorites", current).apply()
        toast(if (added) "已收藏" else "已取消收藏")
        renderTools()
    }

    private fun phraseTool() {
        val input = EditText(this).apply {
            hint = "输入常用文本、回复或链接"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 5
            setText(launcherPrefs.getString("last_phrase", "").orEmpty())
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("快捷文案")
            .setMessage("文案保存在本机，不会自动发送。")
            .setView(input)
            .setNegativeButton("关闭", null)
            .setNeutralButton("保存", null)
            .setPositiveButton("复制", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(-3).setOnClickListener {
                val value = input.text.toString().trim()
                if (value.isBlank()) {
                    toast("文案不能为空")
                    return@setOnClickListener
                }
                launcherPrefs.edit().putString("last_phrase", value).apply()
                val arr = runCatching { JSONArray(launcherPrefs.getString("saved_phrases", "[]")) }.getOrDefault(JSONArray())
                val next = JSONArray()
                next.put(value)
                for (i in 0 until arr.length()) {
                    val old = arr.optString(i)
                    if (old.isNotBlank() && old != value && next.length() < 30) next.put(old)
                }
                launcherPrefs.edit().putString("saved_phrases", next.toString()).apply()
                toast("已保存到本机（最多保留 30 条）")
            }
            dialog.getButton(-1).setOnClickListener {
                val value = input.text.toString()
                if (value.isBlank()) {
                    toast("请先输入文案")
                    return@setOnClickListener
                }
                copy("TNT 文案", value)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun nativeShare() {
        val input = EditText(this).apply {
            hint = "输入文本、链接或分享说明"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
        }
        MaterialAlertDialogBuilder(this).setTitle("系统分享助手")
            .setMessage("通过 Android 系统分享面板交给 QQ 或其他已安装应用。TNT 不读取登录态，也不构造内部协议包。")
            .setView(input).setNegativeButton("取消", null)
            .setNeutralButton("只复制") { _, _ -> copy("TNT 分享草稿", input.text.toString()) }
            .setPositiveButton("选择应用") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isBlank()) {
                    toast("请先输入文本或链接")
                    return@setPositiveButton
                }
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, value)
                }
                try {
                    startActivity(Intent.createChooser(share, "分享至"))
                } catch (t: Throwable) {
                    toast("系统分享面板不可用：" + t.javaClass.simpleName)
                }
            }.show()
    }

    private fun jsonTool() {
        val input = EditText(this).apply {
            hint = "粘贴 JSON 对象或数组"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 7
        }
        MaterialAlertDialogBuilder(this).setTitle("JSON 美化 / 校验")
            .setView(input).setNegativeButton("关闭", null).setPositiveButton("校验并复制", null).create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(-1).setOnClickListener {
                        try {
                            val value = JSONTokener(input.text.toString()).nextValue()
                            val pretty = when (value) {
                                is JSONObject -> value.toString(2)
                                is JSONArray -> value.toString(2)
                                else -> value.toString()
                            }
                            copy("TNT JSON", pretty)
                            toast("JSON 结构有效，已复制美化结果")
                            dialog.dismiss()
                        } catch (t: Throwable) {
                            MaterialAlertDialogBuilder(this).setTitle("JSON 无效")
                                .setMessage(t.message ?: "解析失败")
                                .setPositiveButton("关闭", null).show()
                        }
                    }
                }
                dialog.show()
            }
    }

    private fun jsonDiffTool() {
        val left = EditText(this).apply {
            hint = "原始 JSON"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 5
        }
        val right = EditText(this).apply {
            hint = "新 JSON"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 5
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(5), dp(4), dp(5), 0)
            addView(left, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(130)))
            addView(right, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(130)).apply { topMargin = dp(8) })
        }
        val dialog = MaterialAlertDialogBuilder(this).setTitle("JSON 差异比较")
            .setMessage("在本机比较字段路径和值，不会上传内容。")
            .setView(box).setNegativeButton("关闭", null).setPositiveButton("比较", null).create()
        dialog.setOnShowListener {
            dialog.getButton(-1).setOnClickListener {
                try {
                    val a = JSONTokener(left.text.toString()).nextValue()
                    val b = JSONTokener(right.text.toString()).nextValue()
                    val am = linkedMapOf<String, String>()
                    val bm = linkedMapOf<String, String>()
                    flattenJson(a, "$", am)
                    flattenJson(b, "$", bm)
                    val keys = (am.keys + bm.keys).toSortedSet()
                    val report = StringBuilder()
                    var added = 0
                    var removed = 0
                    var changed = 0
                    keys.forEach { key ->
                        val old = am[key]
                        val fresh = bm[key]
                        when {
                            old == null -> {
                                added++
                                report.append("+ ").append(key).append(" = ").append(fresh).append('\n')
                            }
                            fresh == null -> {
                                removed++
                                report.append("- ").append(key).append(" = ").append(old).append('\n')
                            }
                            old != fresh -> {
                                changed++
                                report.append("~ ").append(key).append('\n  旧: ").append(old)
                                    .append("\n  新: ").append(fresh).append('\n')
                            }
                        }
                    }
                    if (report.isEmpty()) report.append("两个 JSON 的字段和值一致。")
                    val summary = "新增 " + added + " 项 · 删除 " + removed + " 项 · 修改 " + changed + " 项\n\n" +
                        report.toString().take(12000)
                    MaterialAlertDialogBuilder(this).setTitle("比较结果").setMessage(summary)
                        .setNeutralButton("复制结果") { _, _ -> copy("JSON 差异", summary) }
                        .setPositiveButton("关闭", null).show()
                    dialog.dismiss()
                } catch (t: Throwable) {
                    MaterialAlertDialogBuilder(this).setTitle("比较失败")
                        .setMessage(t.message ?: "请检查两份 JSON 格式")
                        .setPositiveButton("关闭", null).show()
                }
            }
        }
        dialog.show()
    }

    private fun flattenJson(value: Any?, path: String, output: MutableMap<String, String>) {
        when (value) {
            is JSONObject -> {
                val iterator = value.keys()
                val keys = mutableListOf<String>()
                while (iterator.hasNext()) keys.add(iterator.next())
                if (keys.isEmpty()) output[path] = "{}"
                keys.sorted().forEach { key ->
                    flattenJson(value.opt(key), if (path == "$") "$." + key else path + "." + key, output)
                }
            }
            is JSONArray -> {
                if (value.length() == 0) output[path] = "[]"
                for (i in 0 until value.length()) flattenJson(value.opt(i), path + "[" + i + "]", output)
            }
            else -> output[path] = value?.toString() ?: "null"
        }
    }

    private fun linkTool() {
        val input = EditText(this).apply {
            hint = "粘贴 HTTPS、HTTP 或 QQ Scheme 链接"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
        }
        MaterialAlertDialogBuilder(this).setTitle("QQ Scheme / URL 分析")
            .setMessage("仅在本机解析链接。不会访问目标地址；默认隐藏参数值。")
            .setView(input).setNegativeButton("关闭", null).setPositiveButton("分析", null).create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(-1).setOnClickListener {
                        try {
                            val raw = input.text.toString().trim()
                            require(raw.isNotBlank()) { "请先粘贴链接" }
                            val uri = Uri.parse(raw)
                            require(!uri.scheme.isNullOrBlank()) { "链接缺少协议" }
                            val names = uri.queryParameterNames.toList().sorted()
                            val report = StringBuilder()
                                .append("协议：").append(uri.scheme).append('\n')
                                .append("主机：").append(uri.authority ?: "（无）").append('\n')
                                .append("路径：").append(uri.path ?: "").append('\n')
                                .append("参数数量：").append(names.size).append('\n')
                                .append("参数名：").append(if (names.isEmpty()) "（无）" else names.joinToString("、"))
                                .append("\n\n参数值已隐藏；本工具不会联网访问。")
                                .toString()
                            MaterialAlertDialogBuilder(this).setTitle("链接分析结果").setMessage(report)
                                .setNeutralButton("复制报告") { _, _ -> copy("链接分析", report) }
                                .setPositiveButton("关闭", null).show()
                            dialog.dismiss()
                        } catch (t: Throwable) {
                            MaterialAlertDialogBuilder(this).setTitle("链接格式无效")
                                .setMessage(t.message ?: "无法解析")
                                .setPositiveButton("关闭", null).show()
                        }
                    }
                }
                dialog.show()
            }
    }

    private fun encodingTool() {
        val input = EditText(this).apply {
            hint = "输入需要处理的文本"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
        }
        val output = EditText(this).apply {
            hint = "转换结果"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(110)))
            addView(output, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(110)).apply { topMargin = dp(8) })
        }
        val dialog = MaterialAlertDialogBuilder(this).setTitle("URL / Base64 编解码")
            .setView(box).setNegativeButton("关闭", null).setNeutralButton("复制结果", null).create()
        dialog.setOnShowListener {
            dialog.getButton(-3).setOnClickListener {
                if (output.text.isNotBlank()) copy("TNT 编码结果", output.text.toString())
            }
            val buttons = listOf("URL 编码", "URL 解码", "Base64 编码", "Base64 解码")
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                val firstRow = listOf(buttons[0], buttons[1])
                firstRow.forEach { label ->
                    addView(smallAction(label) {
                        convertEncoding(label, input, output)
                    }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(4) })
                }
            }
            box.addView(actions, 0)
            val second = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                buttons.drop(2).forEach { label ->
                    addView(smallAction(label) {
                        convertEncoding(label, input, output)
                    }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(4) })
                }
            }
            box.addView(second, 1)
        }
        dialog.show()
    }

    private fun convertEncoding(label: String, input: EditText, output: EditText) {
        try {
            val raw = input.text.toString()
            val result = when (label) {
                "URL 编码" -> URLEncoder.encode(raw, "UTF-8")
                "URL 解码" -> URLDecoder.decode(raw, "UTF-8")
                "Base64 编码" -> android.util.Base64.encodeToString(raw.toByteArray(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP)
                "Base64 解码" -> String(android.util.Base64.decode(raw.trim(), android.util.Base64.DEFAULT), StandardCharsets.UTF_8)
                else -> raw
            }
            output.setText(result)
        } catch (t: Throwable) {
            toast("转换失败：" + (t.message ?: t.javaClass.simpleName))
        }
    }

    private fun smallAction(label: String, action: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 11f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = rounded(0xFF1B3D6C.toInt(), 0xFF315C96.toInt(), 10)
        setOnClickListener { action() }
    }

    private fun textTool() {
        val input = EditText(this).apply {
            hint = "输入需要统计的文本"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 5
        }
        MaterialAlertDialogBuilder(this).setTitle("文本 / 字节统计")
            .setView(input).setNegativeButton("关闭", null).setPositiveButton("统计", null).create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(-1).setOnClickListener {
                        val value = input.text.toString()
                        val bytes = value.toByteArray(StandardCharsets.UTF_8).size
                        val codePoints = value.codePointCount(0, value.length)
                        val lines = if (value.isEmpty()) 0 else value.count { it == '\n' } + 1
                        val words = value.trim().split(Regex("\\s+")).count { it.isNotBlank() }
                        val report = "字符数： " + value.length +
                            "\nUnicode 代码点： " + codePoints +
                            "\nUTF-8 字节： " + bytes +
                            "\n行数： " + lines +
                            "\n空白分隔词数： " + words
                        MaterialAlertDialogBuilder(this).setTitle("统计结果").setMessage(report)
                            .setNeutralButton("复制报告") { _, _ -> copy("文本统计", report) }
                            .setPositiveButton("关闭", null).show()
                        dialog.dismiss()
                    }
                }
                dialog.show()
            }
    }

    private fun hashTool() {
        val input = EditText(this).apply {
            hint = "输入文本，计算 SHA-256"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 5
        }
        MaterialAlertDialogBuilder(this).setTitle("SHA-256 摘要")
            .setMessage("内容仅在本机计算，不会上传。")
            .setView(input).setNegativeButton("关闭", null).setPositiveButton("计算", null).create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(-1).setOnClickListener {
                        val bytes = input.text.toString().toByteArray(StandardCharsets.UTF_8)
                        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                        val hex = digest.joinToString("") { "%02x".format(it) }
                        MaterialAlertDialogBuilder(this).setTitle("SHA-256")
                            .setMessage(hex)
                            .setNeutralButton("复制摘要") { _, _ -> copy("SHA-256", hex) }
                            .setPositiveButton("关闭", null).show()
                        dialog.dismiss()
                    }
                }
                dialog.show()
            }
    }

    private fun emojiTool() {
        val emojis = arrayOf("😂", "🤣", "😭", "🥹", "👍", "❤️", "🤔", "🎉", "🙏", "✨", "🤝", "😎", "🐱", "💯", "🔥", "🥳", "😅", "🌟", "✅", "🚀")
        MaterialAlertDialogBuilder(this).setTitle("点选表情复制")
            .setItems(emojis) { _, which -> copy("TNT Emoji", emojis[which]) }.show()
    }

    private fun copyDiagnostic() {
        @Suppress("DEPRECATION")
        val qqVersion = try {
            packageManager.getPackageInfo("com.tencent.mobileqq", 0).versionName ?: "未知"
        } catch (_: Throwable) {
            "未安装或不可见"
        }
        val report = "TNT · 光流工具箱" +
            "\n作者：亦是光流" +
            "\n模块版本：" + BuildConfig.VERSION_NAME +
            "\nAndroid：" + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")" +
            "\nABI：" + Build.SUPPORTED_ABIS.joinToString() +
            "\nQQ 包名：com.tencent.mobileqq" +
            "\nQQ 版本：" + qqVersion +
            "\n诊断信息不包含账号、Cookie 或聊天内容。"
        copy("TNT 环境诊断", report)
    }

    private fun aboutTool() {
        val message = "TNT · 光流 Android 模块\n\n二次开发作者：亦是光流\n上游项目：cwuom/ono\n项目仓库：github.com/ysgl870-web/tnt\n许可证：GPL-3.0\n\n专注 QQNT 使用体验优化、日常实用工具和模块化扩展。TNT 保留上游及贡献者的版权、许可证与免责声明；作者署名仅用于说明二次开发工作。\n\n本地工具不会自动发送协议包；分享操作使用 Android 系统分享面板。"
        MaterialAlertDialogBuilder(this).setTitle("关于 TNT")
            .setMessage(message)
            .setNeutralButton("复制项目信息") { _, _ -> copy("TNT 项目信息", message) }
            .setPositiveButton("关闭", null).show()
    }

    private fun rounded(color: Int, stroke: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1), stroke)
        }

    private fun lp() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun toast(value: String) =
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show()

    private fun copy(label: String, value: String) {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText(label, value))
        toast("已复制到剪贴板")
    }
}
