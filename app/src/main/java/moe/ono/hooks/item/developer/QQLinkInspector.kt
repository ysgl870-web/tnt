package moe.ono.hooks.item.developer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import moe.ono.hooks._base.BaseSwitchFunctionHookItem
import moe.ono.hooks._core.annotation.HookItem
import moe.ono.util.Logger
import moe.ono.util.SyncUtils
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Local-only URL and request-metadata inspector.
 * It intentionally never reads request/response bodies, headers, cookies,
 * message payloads, login material, cryptographic keys, or QQ protocol frames.
 */
@HookItem(
    path = "开发者选项/QQ界面链接抓取",
    description = "本机可见调试：记录 QQ 页面跳转、WebView 链接及部分 HTTP 请求元数据。" +
        "仅保存在本地；自动隐藏所有查询参数值，不读取请求头、Cookie、正文或聊天内容。开启后会增加少量开销。"
)
class QQLinkInspector : BaseSwitchFunctionHookItem() {

    override fun targetProcess(): Int {
        return SyncUtils.PROC_MAIN or SyncUtils.PROC_TOOL or
            SyncUtils.PROC_MINI or SyncUtils.PROC_WXA_CONTAINER
    }

    override fun entry(classLoader: ClassLoader) {
        hookActivityNavigation()
        hookWebViews(classLoader)
        hookWebViewClients(classLoader)
        hookOkHttpMetadata(classLoader)
        Logger.i("QQLinkInspector: local URL inspector enabled")
    }

    private fun hookActivityNavigation() {
        val callback = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val intent = param.args.firstOrNull { it is Intent } as? Intent ?: return
                    val raw = intent.dataString ?: return
                    QQLinkCaptureStore.record("SCHEME", "OPEN", raw, "Activity")
                } catch (_: Throwable) {
                    // Diagnostics must never interfere with QQ navigation.
                }
            }
        }

        runCatching {
            XposedBridge.hookAllMethods(Activity::class.java, "startActivity", callback)
            XposedBridge.hookAllMethods(Activity::class.java, "startActivityForResult", callback)
        }.onFailure {
            Logger.w("QQLinkInspector: Activity navigation hook unavailable: " + it.javaClass.simpleName)
        }
    }

    private fun hookWebViews(classLoader: ClassLoader) {
        val classNames = listOf(
            "android.webkit.WebView",
            "com.tencent.smtt.sdk.WebView",
            "com.tencent.qimei.webview.QmX5Webview"
        )

        classNames.forEach { className ->
            val clazz = runCatching { classLoader.loadClass(className) }.getOrNull() ?: return@forEach
            hookUrlMethod(clazz, "loadUrl", "GET")
            hookUrlMethod(clazz, "postUrl", "POST")
        }
    }

    private fun hookUrlMethod(clazz: Class<*>, methodName: String, method: String) {
        runCatching {
            XposedBridge.hookAllMethods(clazz, methodName, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val raw = param.args.firstOrNull() as? String ?: return
                    QQLinkCaptureStore.record("WEBVIEW", method, raw, clazz.simpleName + "." + methodName)
                }
            })
        }
    }

    private fun hookWebViewClients(classLoader: ClassLoader) {
        val classNames = listOf(
            "android.webkit.WebViewClient",
            "com.tencent.smtt.sdk.WebViewClient"
        )
        classNames.forEach { className ->
            val clazz = runCatching { classLoader.loadClass(className) }.getOrNull() ?: return@forEach
            listOf("onPageStarted", "shouldOverrideUrlLoading").forEach { methodName ->
                runCatching {
                    XposedBridge.hookAllMethods(clazz, methodName, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val candidate = param.args.getOrNull(1) ?: return
                            val raw = when (candidate) {
                                is String -> candidate
                                else -> runCatching {
                                    candidate.javaClass.getMethod("getUrl").invoke(candidate)?.toString()
                                }.getOrNull()
                            } ?: return
                            QQLinkCaptureStore.record(
                                "WEBVIEW",
                                "NAVIGATE",
                                raw,
                                clazz.simpleName + "." + methodName
                            )
                        }
                    })
                }
            }
        }
    }

    /**
     * Records only Request.method() and Request.url() at OkHttpClient.newCall().
     * No headers or request bodies are accessed.
     */
    private fun hookOkHttpMetadata(classLoader: ClassLoader) {
        try {
            val clientClass = classLoader.loadClass("okhttp3.OkHttpClient")
            val requestClass = classLoader.loadClass("okhttp3.Request")
            val methods = clientClass.methods.filter {
                it.name == "newCall" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0].isAssignableFrom(requestClass)
            }

            methods.forEach { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val request = param.args.firstOrNull() ?: return
                            val requestType = request.javaClass
                            val httpMethod = requestType.getMethod("method").invoke(request)?.toString() ?: "?"
                            val url = requestType.getMethod("url").invoke(request)?.toString() ?: return
                            QQLinkCaptureStore.record("HTTP", httpMethod, url, "OkHttp.newCall")
                        } catch (_: Throwable) {
                            // Never change or block the request.
                        }
                    }
                })
            }
        } catch (_: Throwable) {
            // OkHttp is optional and may be relocated or absent in some QQ versions.
        }
    }
}

/** Shared, bounded MMKV-backed store. No server endpoint or background uploader exists. */
internal object QQLinkCaptureStore {
    private const val STORAGE_KEY = "qq_link_inspector_records_v1"
    private const val MAX_RECORDS = 100
    private const val MAX_URL_LENGTH = 1200
    private val lock = Any()
    private var lastFingerprint: String? = null
    private var lastRecordedAt: Long = 0L

    fun record(kind: String, method: String, rawUrl: String, source: String) {
        val safeUrl = sanitizeUrl(rawUrl) ?: return
        if (safeUrl.length > MAX_URL_LENGTH) return

        val now = System.currentTimeMillis()
        val fingerprint = kind + "|" + method + "|" + safeUrl
        synchronized(lock) {
            if (fingerprint == lastFingerprint && now - lastRecordedAt < 500L) return
            lastFingerprint = fingerprint
            lastRecordedAt = now

            try {
                val old = JSONArray(moe.ono.config.ConfigManager.cGetString(STORAGE_KEY, "[]"))
                val next = JSONArray()
                val first = (old.length() - (MAX_RECORDS - 1)).coerceAtLeast(0)
                for (index in first until old.length()) {
                    old.optJSONObject(index)?.let { next.put(it) }
                }

                val row = JSONObject()
                    .put("time", now)
                    .put("kind", kind.take(24))
                    .put("method", method.take(16))
                    .put("url", safeUrl)
                    .put("source", source.take(64))
                    .put("process", runCatching { SyncUtils.getProcessName() }.getOrDefault("QQ"))
                next.put(row)
                moe.ono.config.ConfigManager.cPutString(STORAGE_KEY, next.toString())
            } catch (error: Throwable) {
                Logger.w("QQLinkInspector: local store write failed: " + error.javaClass.simpleName)
            }
        }
    }

    fun readAll(): List<JSONObject> {
        return try {
            val array = JSONArray(moe.ono.config.ConfigManager.cGetString(STORAGE_KEY, "[]"))
            buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let { add(JSONObject(it.toString())) }
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun clear() {
        synchronized(lock) {
            runCatching {
                moe.ono.config.ConfigManager.cPutString(STORAGE_KEY, "[]")
                lastFingerprint = null
                lastRecordedAt = 0L
            }
        }
    }

    private fun sanitizeUrl(raw: String): String? {
        return try {
            val uri = Uri.parse(raw.trim().take(4096))
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
            val allowed = scheme == "http" || scheme == "https" ||
                scheme == "mqq" || scheme == "mqqapi" ||
                scheme == "qq" || scheme == "tencent"
            if (!allowed) return null

            // Retain query-key names for debugging but replace every value.
            val queryKeys = runCatching { uri.queryParameterNames.toList().take(16) }
                .getOrDefault(emptyList())
                .filter { it.matches(Regex("[A-Za-z0-9_.-]{1,40}")) }

            val builder = uri.buildUpon().clearQuery().fragment(null)
            queryKeys.forEach { key -> builder.appendQueryParameter(key, "[redacted]") }
            var safe = builder.build().toString()

            // Defensive cleanup for URL user-info and unusually long path tokens.
            safe = safe.replace(Regex("(?<=://)[^/@?#]+@"), "[redacted]@")
            safe = safe.replace(Regex("(?<=/)[A-Za-z0-9_=-]{48,}(?=/|$)"), "[redacted]")
            safe.take(MAX_URL_LENGTH)
        } catch (_: Throwable) {
            null
        }
    }
}
