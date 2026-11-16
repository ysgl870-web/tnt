package moe.ono.extract

import com.tencent.qqnt.kernel.nativeinterface.MsgElement
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
import com.tencent.qqnt.kernel.nativeinterface.PicElement
import moe.ono.config.CacheConfig
import moe.ono.util.Logger
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 消息级信息提取。
 *
 * 数据来源只有一处：QQ 已经加载到内存里的 MsgRecord（消息模型）。
 * 不读取数据库文件、不抓网络包、不上传任何内容；提取结果默认只在本进程内存中保留。
 *
 * 覆盖范围：
 *  - 文本消息里的 http(s) / mqqapi / qqdocurl 链接
 *  - 图片消息的原始直链（originImageUrl，按会话类型自动补 rkey；无 origin 时用 md5 兜底）
 *  - Ark / 结构化卡片的完整 JSON（ArkElement.bytesData、StructMsgElement.xmlContent）
 *  - 卡片 JSON 里内嵌的跳转链接、小程序 appid、封面图
 *  - 消息文本、发送者、会话、时间等界面消息元信息
 */
data class ExtractedMessage(
    val msgId: Long,
    val msgSeq: Long,
    val chatType: Int,
    val peerUid: String,
    val peerName: String,
    val senderUin: Long,
    val senderName: String,
    val time: Long,
    val text: String,
    val links: List<String>,
    val imageUrls: List<String>,
    val arkCards: List<String>,
    val cardLinks: List<String>,
    val appIds: List<String>,
    val xmlCards: List<String>
) {
    val chatLabel: String
        get() = when (chatType) {
            1 -> "私聊"
            2 -> "群聊"
            3 -> "频道"
            else -> "会话($chatType)"
        }

    val timeLabel: String
        get() = runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(time * 1000L))
        }.getOrDefault("-")

    /** 界面消息摘要，用于列表一行显示 */
    fun summary(): String {
        val head = text.replace("\n", " ").trim()
        return head.ifEmpty {
            when {
                imageUrls.isNotEmpty() -> "[图片]"
                arkCards.isNotEmpty() -> "[卡片]"
                else -> "[其他消息]"
            }
        }.take(48)
    }

    fun toText(): String = buildString {
        append("【消息】").append(chatLabel).append(" · ").append(peerName.ifEmpty { peerUid })
        append("\n【发送者】").append(senderName.ifEmpty { senderUin.toString() })
        append("\n【时间】").append(timeLabel)
        append("\n【msgId】").append(msgId).append("   msgSeq=").append(msgSeq)

        if (text.isNotBlank()) {
            append("\n\n【文本】\n").append(text)
        }
        append("\n\n【链接】(").append(links.size).append(")")
        if (links.isEmpty()) append("\n未发现") else links.forEachIndexed { i, u -> append("\n").append(i + 1).append(". ").append(u) }

        append("\n\n【图片直链】(").append(imageUrls.size).append(")")
        if (imageUrls.isEmpty()) append("\n未发现") else imageUrls.forEachIndexed { i, u -> append("\n").append(i + 1).append(". ").append(u) }

        if (cardLinks.isNotEmpty()) {
            append("\n\n【卡片内链接】(").append(cardLinks.size).append(")")
            cardLinks.forEachIndexed { i, u -> append("\n").append(i + 1).append(". ").append(u) }
        }
        if (appIds.isNotEmpty()) {
            append("\n\n【小程序 appid】\n").append(appIds.joinToString(", "))
        }
        if (arkCards.isNotEmpty()) {
            append("\n\n【Ark 卡片 JSON】(").append(arkCards.size).append(")")
            arkCards.forEachIndexed { i, json ->
                append("\n\n--- 卡片 ").append(i + 1).append(" ---\n").append(json)
            }
        }
        if (xmlCards.isNotEmpty()) {
            append("\n\n【结构化 XML】(").append(xmlCards.size).append(")")
            xmlCards.forEachIndexed { i, xml ->
                append("\n\n--- XML ").append(i + 1).append(" ---\n").append(xml)
            }
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("msgId", msgId)
        put("msgSeq", msgSeq)
        put("chatType", chatType)
        put("chatLabel", chatLabel)
        put("peerUid", peerUid)
        put("peerName", peerName)
        put("senderUin", senderUin)
        put("senderName", senderName)
        put("time", time)
        put("timeLabel", timeLabel)
        put("text", text)
        put("links", JSONArray(links))
        put("imageUrls", JSONArray(imageUrls))
        put("cardLinks", JSONArray(cardLinks))
        put("appIds", JSONArray(appIds))
        put("arkCards", JSONArray(arkCards))
        put("xmlCards", JSONArray(xmlCards))
    }

    companion object {
        fun fromJson(raw: JSONObject): ExtractedMessage? = runCatching {
            fun list(key: String): List<String> {
                val array = raw.optJSONArray(key) ?: return emptyList()
                return (0 until array.length()).mapNotNull { array.opt(it)?.toString() }
            }
            ExtractedMessage(
                msgId = raw.optLong("msgId"),
                msgSeq = raw.optLong("msgSeq"),
                chatType = raw.optInt("chatType"),
                peerUid = raw.optString("peerUid"),
                peerName = raw.optString("peerName"),
                senderUin = raw.optLong("senderUin"),
                senderName = raw.optString("senderName"),
                time = raw.optLong("time"),
                text = raw.optString("text"),
                links = list("links"),
                imageUrls = list("imageUrls"),
                arkCards = list("arkCards"),
                cardLinks = list("cardLinks"),
                appIds = list("appIds"),
                xmlCards = list("xmlCards")
            )
        }.getOrNull()
    }
}

object QQMsgExtractor {

    /** 只匹配明确的链接结构，避免把中文正文误判成 URL */
    private val URL_PATTERN = Regex(
        "(?i)(?:https?|mqqapi|mqqwpa|mqqopensdkapi|qqdocurl)://[A-Za-z0-9\\-._~:/?#\\[\\]@!&'()*+,;=%]+",
        RegexOption.IGNORE_CASE
    )

    private val LINK_KEYS = setOf(
        "url", "jumpurl", "jump_url", "qqdocurl", "qqdoc_url", "scheme", "mqqapi",
        "shareurl", "share_url", "link", "href", "target", "redirect", "deeplink",
        "weburl", "web_url", "appurl", "app_url", "callback"
    )

    private val IMAGE_KEYS = setOf(
        "image", "img", "pic", "picture", "cover", "coverurl", "cover_url",
        "icon", "iconurl", "icon_url", "thumb", "thumburl", "preview"
    )

    /**
     * @param record QQ 已加载在内存中的消息模型
     * @return 提取失败或消息没有任何可提取内容时返回 null
     */
    fun extract(record: MsgRecord?): ExtractedMessage? {
        if (record == null) return null
        return runCatching {
            val text = StringBuilder()
            val links = LinkedHashSet<String>()
            val images = LinkedHashSet<String>()
            val cards = LinkedHashSet<String>()
            val cardLinks = LinkedHashSet<String>()
            val appIds = LinkedHashSet<String>()
            val xmlCards = LinkedHashSet<String>()

            val elements = record.elements ?: arrayListOf()
            for (element in elements) {
                inspectElement(element, record.chatType, text, links, images, cards, cardLinks, appIds, xmlCards)
            }

            val body = text.toString().trim()
            // 文本里出现的链接，统一归到 links
            body.takeIf { it.isNotEmpty() }?.let { links.addAll(findUrls(it)) }

            val extracted = ExtractedMessage(
                msgId = record.msgId,
                msgSeq = record.msgSeq,
                chatType = record.chatType,
                peerUid = record.peerUid ?: "",
                peerName = record.peerName ?: "",
                senderUin = record.senderUin,
                senderName = (record.sendNickName ?: "").ifEmpty { record.sendMemberName ?: "" },
                time = record.msgTime,
                text = body,
                links = links.filterNot { it in cardLinks }.toList(),
                imageUrls = images.toList(),
                arkCards = cards.toList(),
                cardLinks = cardLinks.toList(),
                appIds = appIds.toList(),
                xmlCards = xmlCards.toList()
            )

            if (extracted.text.isBlank() && extracted.isContentEmpty()) null else extracted
        }.onFailure {
            Logger.w("TNT msg extract failed: " + it.javaClass.simpleName)
        }.getOrNull()
    }

    private fun ExtractedMessage.isContentEmpty(): Boolean =
        imageUrls.isEmpty() && arkCards.isEmpty() && cardLinks.isEmpty() && xmlCards.isEmpty()

    private fun inspectElement(
        element: MsgElement,
        chatType: Int,
        text: StringBuilder,
        links: MutableSet<String>,
        images: MutableSet<String>,
        cards: MutableSet<String>,
        cardLinks: MutableSet<String>,
        appIds: MutableSet<String>,
        xmlCards: MutableSet<String>
    ) {
        runCatching {
            element.textElement?.let { textElement ->
                val content = textElement.content
                if (!content.isNullOrEmpty()) {
                    if (text.isNotEmpty()) text.append('\n')
                    text.append(content)
                    links.addAll(findUrls(content))
                    textElement.linkInfo?.let { linkInfo ->
                        findUrls(linkInfo.toString()).forEach(links::add)
                    }
                }
            }
        }.onFailure { Logger.w("TNT extract text element: " + it.javaClass.simpleName) }

        runCatching {
            element.picElement?.let { pic ->
                buildPicUrl(pic, chatType)?.let(images::add)
                pic.emojiWebUrl?.takeIf { it.startsWith("http") }?.let(images::add)
            }
        }.onFailure { Logger.w("TNT extract pic element: " + it.javaClass.simpleName) }

        runCatching {
            element.arkElement?.let { ark ->
                val raw = ark.bytesData ?: ""
                if (raw.isNotBlank()) {
                    cards.add(prettyJson(raw))
                    collectFromJson(raw, cardLinks, images, appIds)
                }
                ark.linkInfo?.let { findUrls(it.toString()).forEach(cardLinks::add) }
            }
        }.onFailure { Logger.w("TNT extract ark element: " + it.javaClass.simpleName) }

        runCatching {
            element.structMsgElement?.let { struct ->
                val xml = struct.xmlContent ?: ""
                if (xml.isNotBlank()) {
                    xmlCards.add(xml.take(128_000))
                    findUrls(xml).forEach(cardLinks::add)
                }
            }
        }.onFailure { Logger.w("TNT extract struct element: " + it.javaClass.simpleName) }

        runCatching {
            element.markdownElement?.let { md ->
                val content = md.content ?: ""
                if (content.isNotBlank()) {
                    if (text.isNotEmpty()) text.append('\n')
                    text.append(content)
                    links.addAll(findUrls(content))
                }
            }
        }.onFailure { Logger.w("TNT extract markdown element: " + it.javaClass.simpleName) }
    }

    /** 图片原始直链：优先 originImageUrl + rkey，退化为 gchat 的 md5 地址 */
    fun buildPicUrl(pic: PicElement, chatType: Int): String? = runCatching {
        val origin = pic.originImageUrl?.trim().orEmpty()
        if (origin.isEmpty()) {
            val md5 = (pic.md5HexStr ?: pic.originImageMd5 ?: "").uppercase(Locale.US)
            return@runCatching if (md5.isEmpty()) null
            else "https://gchat.qpic.cn/gchatpic_new/0/0-0-$md5/0"
        }
        when {
            origin.startsWith("http://") || origin.startsWith("https://") -> origin
            origin.startsWith("/download") -> {
                // 群图与私聊图使用不同的 rkey；appid=1406 是群图固定标识
                val groupFirst = chatType == 2 || origin.contains("appid=1406")
                val rkey = if (groupFirst) groupRkey() else privateRkey()
                appendRkey("https://multimedia.nt.qq.com.cn$origin", rkey)
            }
            else -> "https://gchat.qpic.cn$origin"
        }
    }.getOrNull()

    /** rkey 可能是 "&rkey=xxx"、也可能是 "xxx"，统一拼成合法参数 */
    fun appendRkey(url: String, rkey: String?): String {
        if (rkey.isNullOrBlank()) return url
        val value = rkey.trim()
        return when {
            value.startsWith("&") -> url + value
            value.contains("rkey=") -> url + "&" + value
            else -> url + "&rkey=" + value
        }
    }

    private fun groupRkey(): String? = runCatching { CacheConfig.getRKeyGroup() }.getOrNull()

    private fun privateRkey(): String? = runCatching { CacheConfig.getRKeyPrivate() }.getOrNull()

    fun findUrls(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return URL_PATTERN.findAll(raw.take(200_000))
            .map { it.value.trimEnd('.', ',', ';', ')', ']', '\'', '"', '。', '，', '）') }
            .filter { it.length in 10..4096 }
            .take(64)
            .toList()
    }

    /**
     * 在卡片 JSON / XML 里按 key 语义收集链接、图片和小程序 appid。
     * 只做一次浅层递归（最多 8 层），避免异常结构拖慢消息列表。
     */
    private fun collectFromJson(
        raw: String,
        links: MutableSet<String>,
        images: MutableSet<String>,
        appIds: MutableSet<String>
    ) {
        val root = runCatching { JSONObject(raw) }.getOrNull()
        if (root != null) {
            walkJson(root, links, images, appIds, 0)
            return
        }
        // 非 JSON（部分卡片是转义后的字符串或 XML）
        val unescaped = runCatching {
            if (raw.startsWith("\"") && raw.endsWith("\"")) org.json.JSONTokener(raw).nextValue()?.toString() ?: raw else raw
        }.getOrNull() ?: raw
        val nested = runCatching { JSONObject(unescaped) }.getOrNull()
        if (nested != null) {
            walkJson(nested, links, images, appIds, 0)
        } else {
            findUrls(unescaped).forEach(links::add)
        }
    }

    private fun walkJson(
        value: Any?,
        links: MutableSet<String>,
        images: MutableSet<String>,
        appIds: MutableSet<String>,
        depth: Int
    ) {
        if (depth > 8) return
        when (value) {
            is JSONObject -> {
                for (key in value.keys()) {
                    val child = value.opt(key)
                    val lower = key.lowercase(Locale.ROOT)
                    when {
                        child is String -> {
                            val trimmed = child.trim()
                            if (trimmed.length in 8..4096 &&
                                (trimmed.startsWith("http://") || trimmed.startsWith("https://") ||
                                    trimmed.startsWith("mqqapi://") || trimmed.startsWith("qqdocurl"))
                            ) {
                                if (lower in IMAGE_KEYS || Regex("(image|img|pic|cover|icon|thumb)").containsMatchIn(lower)) images.add(trimmed)
                                if (lower in LINK_KEYS || findUrls(trimmed).isNotEmpty()) links.add(trimmed)
                            }
                            if (lower.contains("appid") || lower == "app_id") {
                                trimmed.takeIf { it.isNotEmpty() && it.length < 24 }?.let(appIds::add)
                            }
                            if (trimmed.length > 8) findUrls(trimmed).take(8).forEach(links::add)
                            walkJson(child, links, images, appIds, depth + 1)
                        }

                        child is JSONObject || child is JSONArray -> walkJson(child, links, images, appIds, depth + 1)
                    }
                }
            }

            is JSONArray -> {
                for (index in 0 until value.length()) {
                    val child = value.opt(index)
                    if (child is JSONObject || child is JSONArray || child is String) {
                        walkJson(child, links, images, appIds, depth + 1)
                    }
                }
            }

            is String -> findUrls(value).forEach(links::add)
        }
    }

    /** 卡片原始数据一般是压缩过的一行 JSON，格式化后便于阅读与复制 */
    fun prettyJson(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.length > 128_000) return trimmed.take(128_000)
        return runCatching {
            when {
                trimmed.startsWith("{") -> JSONObject(trimmed).toString(2)
                trimmed.startsWith("[") -> JSONArray(trimmed).toString(2)
                else -> trimmed
            }
        }.getOrDefault(trimmed)
    }
}
