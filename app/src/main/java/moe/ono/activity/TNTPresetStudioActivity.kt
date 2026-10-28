package moe.ono.activity

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

/**
 * TNT local/cloud preset editor. Preset data is treated as untrusted text only:
 * this Activity intentionally does not dispatch arbitrary QQNT protocol packets.
 */
class TNTPresetStudioActivity : AppCompatTransferActivity() {
    private data class Preset(
        val id: String = UUID.randomUUID().toString(),
        val name: String,
        val content: String,
        val packageName: String = "",
        val updatedAt: Long = System.currentTimeMillis()
    )

    private val prefs by lazy { getSharedPreferences("tnt_preset_store_v1", MODE_PRIVATE) }
    private var presets = mutableListOf<Preset>()
    private lateinit var listRoot: LinearLayout
    private lateinit var status: TextView
    private lateinit var search: EditText

    private val createJson = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) safely("导出预设失败") { exportTo(uri) }
    }
    private val openJson = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) safely("导入预设失败") { importFrom(uri) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "TNT 预设工坊"
        presets = loadPresets().toMutableList()
        buildUi()
        render()
    }

    private fun buildUi() {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(14))
        }
        outer.addView(TextView(this).apply {
            text = "TNT 发包草稿 / 预设工坊"
            textSize = 23f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, lp())
        outer.addView(TextView(this).apply {
            text = "作者：亦是光流\n兼容 AWA/黑壳常见 name/content 预设。支持本地编辑、JSON 校验、导入导出、云目录预览和离线草稿。模板不会被执行，也不会自动发送原始协议包。"
            textSize = 13f
        }, lp())

        addButton(outer, "新建预设") { editPreset(null) }
        addButton(outer, "导入 JSON / 黑壳分隔符") {
            openJson.launch(arrayOf("application/json", "text/json", "text/plain", "*/*"))
        }
        addButton(outer, "导出 AWA/黑壳兼容 JSON") {
            createJson.launch("TNT-presets-${System.currentTimeMillis()}.json")
        }
        addButton(outer, "云预设目录（手动 HTTPS）") { askCloudUrl() }
        addButton(outer, "Ark JSON 离线草稿构建器") { arkBuilder() }
        addButton(outer, "HEX / Base64 工具") { byteTool() }
        addButton(outer, "导入安全示例预设") { installExamples() }
        addButton(outer, "检查整个本地预设库") { validateAll() }

        search = EditText(this).apply {
            hint = "搜索名称、内容或包名"
            singleLine = true
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = render()
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            })
        }
        outer.addView(search, lp())
        status = TextView(this).apply { textSize = 12f; setPadding(0, dp(4), 0, dp(4)) }
        outer.addView(status, lp())
        val scroll = ScrollView(this)
        listRoot = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(listRoot, ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        outer.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(outer)
    }

    private fun render() {
        if (!::listRoot.isInitialized || !::status.isInitialized) return
        listRoot.removeAllViews()
        val q = if (::search.isInitialized) search.text.toString().trim().lowercase(Locale.ROOT) else ""
        val shown = presets.filter {
            q.isBlank() || it.name.lowercase(Locale.ROOT).contains(q) ||
                it.content.lowercase(Locale.ROOT).contains(q) || it.packageName.lowercase(Locale.ROOT).contains(q)
        }
        status.text = "本地预设：${presets.size} 项 · 当前显示：${shown.size} 项 · 上限：${MAX_PRESETS}"
        if (shown.isEmpty()) {
            listRoot.addView(TextView(this).apply {
                text = if (presets.isEmpty()) "还没有预设。可以新建或导入 AWA/黑壳导出的 JSON。" else "没有匹配的预设。"
                textSize = 14f
                setPadding(dp(4), dp(18), dp(4), dp(18))
            }, lp())
        }
        shown.forEach { item ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(0x11000000)
                    setStroke(dp(1), 0x22000000)
                }
                isClickable = true
                isFocusable = true
                setOnClickListener { showPreset(item) }
            }
            card.addView(TextView(this).apply {
                text = item.name
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }, lp())
            card.addView(TextView(this).apply {
                text = "${kindOf(item.content)} · ${item.content.length} 字符" +
                    if (item.packageName.isNotBlank()) " · ${item.packageName}" else ""
                textSize = 12f
            }, lp())
            listRoot.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(7)
            })
        }
    }

    private fun showPreset(item: Preset) {
        MaterialAlertDialogBuilder(this)
            .setTitle(item.name)
            .setMessage("类型：${kindOf(item.content)}\n包名：${item.packageName.ifBlank { "（无）" }}\n修改：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(java.util.Date(item.updatedAt))}\n\n${item.content.take(1800)}${if (item.content.length > 1800) "\n…（预览截断）" else ""}")
            .setItems(arrayOf("检查 / 美化 JSON", "复制内容", "编辑", "删除")) { _, which ->
                when (which) {
                    0 -> validateItem(item)
                    1 -> copy("TNT preset", item.content)
                    2 -> editPreset(item)
                    3 -> MaterialAlertDialogBuilder(this).setTitle("确认删除")
                        .setMessage("删除本地预设“${item.name}”？")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("删除") { _, _ -> presets.removeAll { it.id == item.id }; persist(); render() }
                        .show()
                }
            }.setNegativeButton("关闭", null).show()
    }

    private fun editPreset(old: Preset?) {
        val name = EditText(this).apply { hint = "预设名称（必填）"; singleLine = true; setText(old?.name.orEmpty()) }
        val pkg = EditText(this).apply { hint = "包名/来源（可选）"; singleLine = true; setText(old?.packageName.orEmpty()) }
        val content = EditText(this).apply {
            hint = "文本、JSON、Ark 草稿或 HEX 样例"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 7; maxLines = 16
            setText(old?.content.orEmpty())
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(4), dp(6), 0)
            addView(name, lp()); addView(pkg, lp())
            addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(210)))
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (old == null) "新建预设" else "编辑预设")
            .setView(box).setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        dialog.setOnShowListener {
            dialog.getButton(-1).setOnClickListener {
                val n = name.text.toString().trim()
                val c = content.text.toString()
                val p = pkg.text.toString().trim()
                if (n.isBlank() || c.isBlank()) { toast("名称和内容不能为空"); return@setOnClickListener }
                if (n.length > 100 || c.length > MAX_CONTENT || p.length > 200) { toast("名称、内容或包名超过长度限制"); return@setOnClickListener }
                if (old == null && presets.size >= MAX_PRESETS) { toast("本地预设数量已达上限"); return@setOnClickListener }
                val updated = Preset(old?.id ?: UUID.randomUUID().toString(), n, c, p, System.currentTimeMillis())
                if (old == null) presets.add(updated) else {
                    val index = presets.indexOfFirst { it.id == old.id }
                    if (index >= 0) presets[index] = updated else presets.add(updated)
                }
                persist(); render(); dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun validateItem(item: Preset) {
        try {
            val trimmed = item.content.trim()
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                val parsed = JSONTokener(trimmed).nextValue()
                val pretty = when (parsed) {
                    is JSONObject -> parsed.toString(2)
                    is JSONArray -> parsed.toString(2)
                    else -> parsed.toString()
                }
                MaterialAlertDialogBuilder(this).setTitle("JSON 有效")
                    .setMessage("JSON 解析成功。选择复制可复制美化后的内容。\n字符数：${item.content.length}")
                    .setNeutralButton("复制美化 JSON") { _, _ -> copy("TNT JSON", pretty) }
                    .setPositiveButton("关闭", null).show()
            } else if (isHex(trimmed)) {
                toast("HEX 样例有效，${trimmed.replace(Regex("[\\s,:-]"), "").length / 2} 字节（仅校验）")
            } else toast("普通文本：${item.content.length} 字符 / ${item.content.toByteArray(StandardCharsets.UTF_8).size} 字节")
        } catch (t: Throwable) { errorDialog("JSON 无效", "${t.javaClass.simpleName}: ${t.message ?: "解析失败"}") }
    }

    private fun arkBuilder() {
        val templateId = EditText(this).apply { hint = "template_id（正整数）"; inputType = InputType.TYPE_CLASS_NUMBER }
        val kv = EditText(this).apply {
            hint = "kv JSON 数组，例如 [{\"key\":\"title\",\"value\":\"Hello\"}]"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 5; setText("[{\\\"key\\\":\\\"title\\\",\\\"value\\\":\\\"TNT 示例\\\"}]")
        }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(4), dp(6), 0); addView(templateId, lp()); addView(kv, lp()) }
        val dialog = MaterialAlertDialogBuilder(this).setTitle("Ark JSON 离线草稿")
            .setView(body).setNegativeButton("取消", null).setPositiveButton("生成", null).create()
        dialog.setOnShowListener {
            dialog.getButton(-1).setOnClickListener {
                try {
                    val id = templateId.text.toString().trim().toIntOrNull()
                    require(id != null && id > 0) { "template_id 必须是正整数" }
                    val arr = JSONTokener(kv.text.toString()).nextValue() as? JSONArray ?: error("kv 必须是 JSON 数组")
                    require(arr.length() in 1..50) { "kv 需要包含 1–50 项" }
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: error("kv 第 ${i + 1} 项不是对象")
                        require(item.optString("key").isNotBlank()) { "第 ${i + 1} 项缺少 key" }
                        require(item.has("value") || item.has("obj")) { "第 ${i + 1} 项需要 value 或 obj" }
                    }
                    val out = JSONObject().put("msg_type", 3).put("ark", JSONObject().put("template_id", id).put("kv", arr)).toString(2)
                    presets.add(Preset(name = "Ark 模板 $id", content = out, packageName = "QQ Bot Ark JSON"))
                    if (presets.size > MAX_PRESETS) presets.removeAt(0)
                    persist(); render(); copy("Ark JSON", out); dialog.dismiss()
                } catch (t: Throwable) { errorDialog("生成失败", t.message ?: "输入有误") }
            }
        }
        dialog.show()
    }

    private fun byteTool() {
        val input = EditText(this).apply {
            hint = "输入 HEX 或 Base64"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
        }
        MaterialAlertDialogBuilder(this).setTitle("HEX / Base64（本地转换）")
            .setView(input).setNegativeButton("关闭", null)
            .setNeutralButton("HEX → Base64", null).setPositiveButton("Base64 → HEX", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(-3).setOnClickListener {
                        try { val bytes = hexBytes(input.text.toString()); copy("Base64", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)) }
                        catch (t: Throwable) { errorDialog("HEX 输入无效", t.message ?: "格式不正确") }
                    }
                    dialog.getButton(-1).setOnClickListener {
                        try {
                            val bytes = android.util.Base64.decode(input.text.toString().trim(), android.util.Base64.DEFAULT)
                            copy("HEX", bytes.joinToString("") { "%02x".format(it) })
                        } catch (t: Throwable) { errorDialog("Base64 输入无效", t.message ?: "格式不正确") }
                    }
                }
                dialog.show()
            }
    }

    private fun installExamples() {
        val examples = listOf(
            Preset(name = "纯文本示例", content = "TNT 模块 · 亦是光流", packageName = "text"),
            Preset(name = "离线卡片预览草稿", content = JSONObject().put("title", "TNT 预览").put("description", "仅本地模板，不直接发送").toString(2), packageName = "preview"),
            Preset(name = "黑壳分隔符导入示例", content = "示例名称*awa_pkg_name_pkg_content_split*示例内容", packageName = "awa-compat")
        )
        var count = 0
        examples.forEach { e -> if (presets.size < MAX_PRESETS && presets.none { it.name == e.name && it.content == e.content }) { presets.add(e); count++ } }
        persist(); render(); toast("已添加 $count 个示例")
    }

    private fun askCloudUrl() {
        val field = EditText(this).apply {
            hint = "粘贴最终 HTTPS JSON 地址"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.getString(CLOUD_URL, "").orEmpty())
        }
        MaterialAlertDialogBuilder(this).setTitle("云预设目录")
            .setMessage("只读取你手动指定的 HTTPS 地址，不跟随重定向、不发送 Cookie 或账号、不上传本地预设。目录先预览，只有确认后才导入。")
            .setView(field).setNegativeButton("取消", null).setPositiveButton("获取目录") { _, _ ->
                val raw = field.text.toString().trim()
                prefs.edit().putString(CLOUD_URL, raw).apply()
                fetchCloud(raw)
            }.show()
    }

    private fun fetchCloud(raw: String) {
        Thread {
            var conn: HttpsURLConnection? = null
            try {
                val url = URL(raw)
                require(url.protocol.equals("https", true) && url.userInfo == null && (url.port == -1 || url.port == 443)) {
                    "只允许 HTTPS 默认端口，且 URL 不能含用户名/密码"
                }
                require(!isPrivateHost(url.host)) { "禁止访问本机或私有网络地址" }
                val resolved = InetAddress.getAllByName(url.host)
                require(resolved.isNotEmpty() && resolved.none { isPrivateAddress(it) }) { "云主机解析到本机/私有地址，已阻止" }
                conn = (url.openConnection() as HttpsURLConnection).apply {
                    connectTimeout = 8000; readTimeout = 8000; requestMethod = "GET"; instanceFollowRedirects = false
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "TNT-PresetStudio/1.0")
                }
                val code = conn.responseCode
                require(code in 200..299) { if (code in 300..399) "服务器返回重定向，请填写最终 URL" else "HTTP 状态码：$code" }
                val out = ByteArrayOutputStream()
                conn.inputStream.use { stream ->
                    val buf = ByteArray(8192); var size = 0
                    while (true) { val n = stream.read(buf); if (n < 0) break; size += n; require(size <= MAX_IMPORT_BYTES) { "云目录超过 1 MB 限制" }; out.write(buf, 0, n) }
                }
                val received = parseCatalog(String(out.toByteArray(), StandardCharsets.UTF_8))
                runOnUiThread { showImportPicker(received, "云目录 ${url.host}") }
            } catch (t: Throwable) {
                runOnUiThread { errorDialog("获取云预设失败", t.message ?: t.javaClass.simpleName) }
            } finally { conn?.disconnect() }
        }.start()
    }

    private fun importFrom(uri: Uri) {
        val stream = contentResolver.openInputStream(uri) ?: error("无法打开文件")
        val raw = stream.use {
            val out = ByteArrayOutputStream(); val buf = ByteArray(8192); var size = 0
            while (true) { val n = it.read(buf); if (n < 0) break; size += n; require(size <= MAX_IMPORT_BYTES) { "文件超过 1 MB" }; out.write(buf, 0, n) }
            String(out.toByteArray(), StandardCharsets.UTF_8)
        }
        val items = parseCatalog(raw)
        require(items.isNotEmpty()) { "没有识别到 name/content 预设" }
        showImportPicker(items, "本地文件")
    }

    private fun showImportPicker(items: List<Preset>, source: String) {
        val selected = items.take(MAX_PRESETS)
        if (selected.isEmpty()) { toast("没有可导入的预设"); return }
        val checked = BooleanArray(selected.size) { true }
        val labels = selected.map { "${it.name} · ${kindOf(it.content)}" }.toTypedArray()
        MaterialAlertDialogBuilder(this).setTitle("预览 $source · ${selected.size} 项")
            .setMessage("所有数据都会先作为文本/JSON 保存到本地，不会执行或发送。相同名称+内容会去重。")
            .setMultiChoiceItems(labels, checked) { _, which, value -> checked[which] = value }
            .setNegativeButton("取消", null)
            .setPositiveButton("导入所选") { _, _ ->
                var added = 0
                selected.filterIndexed { i, _ -> checked[i] }.forEach { item ->
                    if (presets.none { it.name == item.name && it.content == item.content } && presets.size < MAX_PRESETS) {
                        presets.add(item.copy(id = UUID.randomUUID().toString(), updatedAt = System.currentTimeMillis())); added++
                    }
                }
                persist(); render(); toast("已导入 $added 项")
            }.show()
    }

    private fun parseCatalog(raw: String): List<Preset> {
        require(raw.toByteArray(StandardCharsets.UTF_8).size <= MAX_IMPORT_BYTES) { "内容超过 1 MB" }
        val root = try { JSONTokener(raw).nextValue() } catch (_: Throwable) {
            val parsed = raw.lineSequence().mapNotNull { decodeDelimited(it) }.toList()
            if (parsed.isNotEmpty()) return parsed.distinctBy { it.name to it.content }.take(MAX_PRESETS)
            throw IllegalArgumentException("不是有效 JSON，也没有识别到黑壳分隔符记录")
        }
        val rows = when (root) {
            is JSONArray -> root
            is JSONObject -> {
                val containers = listOf("presets", "presetelems", "elements", "data", "list", "items")
                containers.firstNotNullOfOrNull { root.optJSONArray(it) }
                    ?: if (root.has("name") && (root.has("content") || root.has("packet_body"))) JSONArray().put(root)
                    else JSONArray().also { arr ->
                        val keys = root.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            val value = root.opt(key)
                            if (value is String) {
                                val pair = decodeDelimited(value)
                                if (pair != null) arr.put(JSONObject().put("name", pair.name).put("content", pair.content))
                                else arr.put(JSONObject().put("name", key).put("content", value))
                            }
                        }
                    }
            }
            else -> error("顶层必须是 JSON 数组或包含预设列表的对象")
        }
        val result = mutableListOf<Preset>()
        for (i in 0 until minOf(rows.length(), MAX_PRESETS)) {
            val v = rows.opt(i)
            val item = if (v is JSONObject) v else if (v is String) {
                decodeDelimited(v)?.let { JSONObject().put("name", it.name).put("content", it.content) }
            } else null
            if (item != null) {
                var name = first(item, listOf("name", "title", "label", "id")).orEmpty()
                var content = first(item, listOf("content", "packet_body", "body", "value", "json")).orEmpty()
                val pkg = first(item, listOf("package_name", "packageName", "pkg", "app_package")).orEmpty()
                decodeDelimited(content)?.let { pair -> if (name.isBlank()) name = pair.name; content = pair.content }
                if (name.isNotBlank() && content.isNotBlank()) result.add(Preset(name = name.take(100), content = content.take(MAX_CONTENT), packageName = pkg.take(200)))
            }
        }
        return result.distinctBy { Triple(it.name, it.content, it.packageName) }.take(MAX_PRESETS)
    }

    private fun decodeDelimited(raw: String): Preset? {
        val delimiter = "*awa_pkg_name_pkg_content_split*"
        val pos = raw.indexOf(delimiter)
        if (pos <= 0) return null
        val name = raw.substring(0, pos).trim()
        val content = raw.substring(pos + delimiter.length)
        if (name.isBlank() || content.isBlank() || name.length > 100 || content.length > MAX_CONTENT) return null
        return Preset(name = name, content = content)
    }

    private fun first(obj: JSONObject, keys: List<String>): String? =
        keys.asSequence().map { obj.optString(it, "").trim() }.firstOrNull { it.isNotBlank() && it != "null" }

    private fun exportTo(uri: Uri) {
        val array = JSONArray()
        presets.take(MAX_PRESETS).forEach {
            array.put(JSONObject().put("name", it.name).put("content", it.content).put("package_name", it.packageName))
        }
        val output = contentResolver.openOutputStream(uri) ?: error("无法创建目标文件")
        output.use { it.write(array.toString(2).toByteArray(StandardCharsets.UTF_8)) }
        toast("已导出 ${presets.size} 项 name/content JSON")
    }

    private fun loadPresets(): List<Preset> = runCatching {
        val raw = prefs.getString(PRESET_KEY, "").orEmpty()
        if (raw.isBlank()) emptyList() else {
            val arr = JSONArray(raw)
            (0 until minOf(arr.length(), MAX_PRESETS)).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val n = o.optString("name", "").trim(); val c = o.optString("content", "")
                if (n.isBlank() || c.isBlank()) null else Preset(
                    id = o.optString("id", UUID.randomUUID().toString()), name = n, content = c,
                    packageName = o.optString("packageName", ""), updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun persist() {
        val arr = JSONArray()
        presets.take(MAX_PRESETS).forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("content", it.content)
                .put("packageName", it.packageName).put("updatedAt", it.updatedAt))
        }
        prefs.edit().putString(PRESET_KEY, arr.toString()).apply()
    }

    private fun validateAll() {
        var json = 0; var hex = 0; var text = 0; var bad = 0
        presets.forEach {
            val c = it.content.trim()
            when {
                c.startsWith("{") || c.startsWith("[") -> try { JSONTokener(c).nextValue(); json++ } catch (_: Throwable) { bad++ }
                isHex(c) -> hex++
                else -> text++
            }
        }
        MaterialAlertDialogBuilder(this).setTitle("本地预设检查")
            .setMessage("总计：${presets.size}\nJSON：$json\nHEX：$hex\n文本：$text\n疑似 JSON 错误：$bad\n\n仅检查本地数据，不联网、不发包。")
            .setPositiveButton("关闭", null).show()
    }

    private fun kindOf(raw: String): String {
        val s = raw.trim()
        if (s.contains(DELIMITER)) return "黑壳分隔符记录"
        if (s.startsWith("{") || s.startsWith("[")) {
            val l = s.lowercase(Locale.ROOT)
            return if (l.contains("\"template_id\"") || l.contains("\"msg_type\"")) "Ark/Bot JSON 草稿" else "JSON/卡片草稿"
        }
        return if (isHex(s)) "HEX 样例" else "文本模板"
    }

    private fun isHex(s: String): Boolean {
        val c = s.trim().removePrefix("0x").replace(Regex("[\\s,:-]"), "")
        return c.isNotEmpty() && c.length % 2 == 0 && c.all { it in "0123456789abcdefABCDEF" }
    }

    private fun hexBytes(raw: String): ByteArray {
        val s = raw.trim().removePrefix("0x").replace(Regex("[\\s,:-]"), "")
        require(s.isNotEmpty() && s.length % 2 == 0 && s.all { it in "0123456789abcdefABCDEF" }) { "请输入偶数长度的 HEX 字符串" }
        return ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private fun isPrivateHost(host: String): Boolean {
        val h = host.lowercase(Locale.ROOT).trim('[', ']')
        if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local") || h == "::1" || h == "0.0.0.0") return true
        val parts = h.split('.')
        if (parts.size == 4) {
            val nums = parts.map { it.toIntOrNull() }
            if (nums.all { it != null && it in 0..255 }) {
                val a = nums[0]!!; val b = nums[1]!!
                if (a == 10 || a == 127 || a == 0 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 169 && b == 254)) return true
            }
        }
        return false
    }

    private fun isPrivateAddress(a: InetAddress): Boolean =
        a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress

    private fun copy(label: String, text: String) {
        val mgr = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        mgr.setPrimaryClip(ClipData.newPlainText(label, text))
        toast("已复制到剪贴板")
    }

    private fun addButton(root: LinearLayout, text: String, action: () -> Unit) {
        root.addView(Button(this).apply { this.text = text; isAllCaps = false; setOnClickListener { safely("操作失败", action) } }, lp())
    }
    private fun lp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(n: Int) = (n * resources.displayMetrics.density + 0.5f).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun safely(title: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) { errorDialog(title, "${t.javaClass.simpleName}: ${t.message ?: "未知错误"}") }
    }
    private fun errorDialog(title: String, message: String) {
        MaterialAlertDialogBuilder(this).setTitle(title).setMessage(message).setPositiveButton("关闭", null).show()
    }

    companion object {
        private const val PRESET_KEY = "tnt.presets.v1"
        private const val CLOUD_URL = "tnt.cloud.url"
        private const val MAX_PRESETS = 200
        private const val MAX_CONTENT = 500 * 1024
        private const val MAX_IMPORT_BYTES = 1024 * 1024
        private const val DELIMITER = "*awa_pkg_name_pkg_content_split*"
    }
}
