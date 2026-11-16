package moe.ono.extract

import moe.ono.config.ConfigManager
import moe.ono.util.Logger
import org.json.JSONArray
import org.json.JSONObject

/**
 * 消息提取结果缓存。
 *
 * 保存最近 30 条，存在模块自己的本机配置里（MMKV），这样设置页在另一个进程也能读到。
 * 不联网、不外传，随时可在「查看QQ消息提取记录」里一键清空。
 */
object QQMsgExtractStore {
    private const val KEY = "tnt_qq_msg_extract_v1"
    private const val MAX_MESSAGES = 30

    private val lock = Any()

    /** 本进程已处理过的 msgId：列表滚动会反复回调同一气泡，用它直接短路 */
    private val seen = HashSet<Long>()

    private fun load(): JSONArray = runCatching {
        JSONArray(ConfigManager.getDefaultConfig().getStringOrDefault(KEY, "[]"))
    }.getOrDefault(JSONArray())

    fun put(message: ExtractedMessage) {
        if (message.msgId == 0L) return
        synchronized(lock) {
            seen.add(message.msgId)
            runCatching {
                val old = load()
                val next = JSONArray()
                for (index in 0 until old.length()) {
                    val row = old.optJSONObject(index) ?: continue
                    if (row.optLong("msgId") != message.msgId) next.put(row)
                }
                next.put(message.toJson())
                while (next.length() > MAX_MESSAGES) next.remove(0)
                ConfigManager.getDefaultConfig().edit().putString(KEY, next.toString()).apply()
            }.onFailure {
                Logger.w("TNT msg extract store failed: " + it.javaClass.simpleName)
            }
        }
    }

    fun contains(msgId: Long): Boolean = synchronized(lock) { seen.contains(msgId) }

    fun find(msgId: Long): ExtractedMessage? = recent().firstOrNull { it.msgId == msgId }

    /** 最新的在前 */
    fun recent(limit: Int = MAX_MESSAGES): List<ExtractedMessage> = runCatching {
        val array = load()
        val parsed = ArrayList<ExtractedMessage>(array.length())
        for (index in 0 until array.length()) {
            array.optJSONObject(index)?.let { row ->
                ExtractedMessage.fromJson(row)?.let(parsed::add)
            }
        }
        parsed.asReversed().take(limit)
    }.getOrDefault(emptyList())

    fun size(): Int = runCatching { load().length() }.getOrDefault(0)

    fun clear() {
        synchronized(lock) {
            seen.clear()
            ConfigManager.getDefaultConfig().edit().putString(KEY, "[]").apply()
        }
    }

    fun allImages(): List<String> = recent().flatMap { it.imageUrls }.distinct()

    fun allCards(): List<String> = recent().flatMap { it.arkCards }.distinct()

    fun allLinks(): List<String> = recent().flatMap { it.links + it.cardLinks }.distinct()

    fun exportJson(): String = runCatching {
        JSONArray().apply { recent().forEach { put(it.toJson()) } }.toString(2)
    }.getOrDefault("[]")

    fun exportText(): String {
        val list = recent()
        if (list.isEmpty()) return "暂无消息提取记录"
        return list.joinToString("\n\n==========\n\n") { it.toText() }
    }

    fun parse(raw: JSONObject) {
        ExtractedMessage.fromJson(raw)?.let(::put)
    }
}
