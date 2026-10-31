package moe.ono.activity

import android.content.Context
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/** Consent-gated TNT tool-page usage counter. Counts foreground TNT screen time only. */
object TNTUsageTracker {
    private const val PREFS = "tnt_cloud_v1"
    private const val KEY_CONSENT = "telemetry_opt_in"
    private const val KEY_SERVER = "server_url"
    private const val KEY_TOKEN = "api_token"
    private const val KEY_DEVICE = "device_id"
    private val startedAt = AtomicLong(0L)
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var activeUin: Long = -1L

    fun start(context: Context, qqUin: Long) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_CONSENT, false) || qqUin < 10000L || prefs.getString(KEY_TOKEN, "").isNullOrBlank()) return
        if (startedAt.compareAndSet(0L, System.currentTimeMillis())) activeUin = qqUin
    }

    fun stop(context: Context, moduleVersion: String) {
        val start = startedAt.getAndSet(0L)
        val qq = activeUin
        activeUin = -1L
        if (start <= 0L || qq < 10000L) return
        val seconds = ((System.currentTimeMillis() - start) / 1000L).coerceIn(1L, 86400L)
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_CONSENT, false)) return
        val server = prefs.getString(KEY_SERVER, "").orEmpty().trimEnd('/')
        val token = prefs.getString(KEY_TOKEN, "").orEmpty()
        if (server.isBlank() || token.isBlank()) return
        val device = prefs.getString(KEY_DEVICE, "").orEmpty().ifBlank {
            UUID.randomUUID().toString().also { prefs.edit().putString(KEY_DEVICE, it).apply() }
        }
        worker.execute {
            var conn: HttpURLConnection? = null
            try {
                val uri = URI(server)
                require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null &&
                    (uri.port == -1 || uri.port == 443) && uri.query.isNullOrEmpty() && uri.fragment.isNullOrEmpty())
                conn = URL("$server/api.php?action=telemetry").openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.requestMethod = "POST"
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $token")
                val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                val payload = JSONObject().put("consent", true).put("qq", qq.toString())
                    .put("usage_seconds", seconds).put("device_id", device).put("day", day)
                    .put("module_version", moduleVersion)
                conn.outputStream.use { it.write(payload.toString().toByteArray(StandardCharsets.UTF_8)) }
                if (conn.responseCode in 200..299) conn.inputStream?.close() else conn.errorStream?.close()
            } catch (_: Throwable) {
                // Telemetry failures never block core module features.
            } finally { conn?.disconnect() }
        }
    }
}
