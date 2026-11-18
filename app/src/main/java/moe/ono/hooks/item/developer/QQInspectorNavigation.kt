package moe.ono.hooks.item.developer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import java.nio.charset.StandardCharsets

/**
 * Opens user-confirmed links through QQ's own browser route when possible.
 *
 * QQ's mqqapi://forward/url route is the in-app browser entry. Ordinary
 * http(s) URLs are Base64 encoded into url_prefix; QQ/TIM app-specific schemes
 * are passed through unchanged so their native handlers can process them.
 */
internal object QQInspectorNavigation {
    data class Result(val opened: Boolean, val message: String)

    private val qqPackages = listOf("com.tencent.mobileqq", "com.tencent.tim")
    private val browserPackages = listOf(
        "com.android.chrome",
        "com.google.android.apps.chrome",
        "com.android.browser",
        "com.sec.android.app.sbrowser",
        "org.mozilla.firefox",
        "com.microsoft.emmx",
        "com.brave.browser",
        "com.opera.browser"
    )

    fun open(context: Context, rawUrl: String, preferQQBrowser: Boolean = true): Result {
        val value = rawUrl.trim()
        if (value.isEmpty()) return Result(false, "链接内容为空")
        if (value.length > 16_384) return Result(false, "链接过长，已阻止跳转")

        val uri = runCatching { Uri.parse(value) }.getOrNull()
            ?: return Result(false, "链接格式无效")
        val scheme = uri.scheme?.lowercase() ?: return Result(false, "链接缺少协议")
        val isWeb = scheme == "http" || scheme == "https"

        fun launch(intent: Intent): Boolean {
            return runCatching {
                if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            }.getOrDefault(false)
        }

        val hostPackages = LinkedHashSet<String>().apply {
            val current = (context as? Activity)?.packageName
            if (current in qqPackages) add(current!!)
            addAll(qqPackages)
        }

        if (isWeb && preferQQBrowser) {
            val encoded = Base64.encodeToString(
                value.toByteArray(StandardCharsets.UTF_8),
                Base64.NO_WRAP
            )
            val browserRoute = Uri.Builder()
                .scheme("mqqapi")
                .authority("forward")
                .appendPath("url")
                .appendQueryParameter("version", "1")
                .appendQueryParameter("src_type", "web")
                .appendQueryParameter("url_prefix", encoded)
                .build()

            // This is the route used by QQ's in-app browser. Try the host package
            // first, then TIM as a compatibility option. Some QQ builds declare
            // BROWSABLE and others only match the plain VIEW filter.
            for (pkg in hostPackages) {
                if (launch(Intent(Intent.ACTION_VIEW, browserRoute)
                        .addCategory(Intent.CATEGORY_BROWSABLE)
                        .setPackage(pkg))) {
                    return Result(true, if (pkg == "com.tencent.tim") "已交给 TIM 内置浏览器打开" else "已交给 QQ 内置浏览器打开")
                }
                if (launch(Intent(Intent.ACTION_VIEW, browserRoute).setPackage(pkg))) {
                    return Result(true, if (pkg == "com.tencent.tim") "已交给 TIM 内置浏览器打开" else "已交给 QQ 内置浏览器打开")
                }
            }

            // Compatibility path for QQ builds whose VIEW filter accepts the
            // original web URL but does not expose the mqqapi forward route.
            for (pkg in hostPackages) {
                if (launch(Intent(Intent.ACTION_VIEW, uri)
                        .addCategory(Intent.CATEGORY_BROWSABLE)
                        .setPackage(pkg))) {
                    return Result(true, if (pkg == "com.tencent.tim") "已交给 TIM 网页处理器打开" else "已交给 QQ 网页处理器打开")
                }
            }
        } else {
            // Never wrap QQ/custom schemes inside forward/url: doing so would
            // turn a native action into a plain web page.
            val parsedIntent = if (value.startsWith("intent:", ignoreCase = true)) {
                runCatching { Intent.parseUri(value, Intent.URI_INTENT_SCHEME) }.getOrNull()
            } else null

            if (parsedIntent != null && launch(parsedIntent)) {
                return Result(true, "已按 Intent Scheme 交给目标应用")
            }

            for (pkg in hostPackages) {
                val direct = Intent(Intent.ACTION_VIEW, uri)
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .setPackage(pkg)
                if (launch(direct)) {
                    return Result(true, if (pkg == "com.tencent.tim") "已交给 TIM 处理" else "已交给 QQ 处理")
                }
                if (launch(Intent(Intent.ACTION_VIEW, uri).setPackage(pkg))) {
                    return Result(true, if (pkg == "com.tencent.tim") "已交给 TIM 处理" else "已交给 QQ 处理")
                }
            }
        }

        if (isWeb) {
            // Keep the system/browser fallbacks. These are only reached after
            // QQ's native in-app browser attempts have failed.
            if (launch(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))) {
                return Result(true, "QQ 内置浏览器未接管，已交给系统默认浏览器")
            }
            for (pkg in browserPackages) {
                if (launch(Intent(Intent.ACTION_VIEW, uri)
                        .addCategory(Intent.CATEGORY_BROWSABLE)
                        .setPackage(pkg))) {
                    return Result(true, "已交给浏览器打开")
                }
            }
        } else {
            if (launch(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))) {
                return Result(true, "已交给系统应用处理")
            }
            if (launch(Intent(Intent.ACTION_VIEW, uri))) {
                return Result(true, "已交给系统链接处理器")
            }
        }

        return Result(false, if (isWeb) {
            "QQ 内置浏览器和系统浏览器均无法打开该链接"
        } else {
            "QQ/TIM 与系统均没有可处理此 Scheme 的应用"
        })
    }
}
