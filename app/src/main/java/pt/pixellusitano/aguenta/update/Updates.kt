package pt.pixellusitano.aguenta.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import pt.pixellusitano.aguenta.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class NewVersion(val tag: String, val apkUrl: String, val notes: String)

private val TAG_VERSION = Regex("""^v?(\d+)\.(\d+)\.(\d+)$""")

/** "v1.2.3" → 10203: a mesma regra que dá o versionCode no app/build.gradle.kts. */
fun versionCodeFromTag(tag: String): Int? {
    val (major, minor, patch) = TAG_VERSION.matchEntire(tag.trim())?.destructured ?: return null
    return major.toInt() * 10000 + minor.toInt() * 100 + patch.toInt()
}

/** Lê a resposta de /releases/latest; só devolve algo se for mais recente do que a versão instalada e trouxer APK. */
fun parseLatest(json: String, currentCode: Int): NewVersion? {
    val release = JSONObject(json)
    val tag = release.getString("tag_name")
    if ((versionCodeFromTag(tag) ?: return null) <= currentCode) return null
    val assets = release.optJSONArray("assets") ?: return null
    val apk = (0 until assets.length()).map(assets::getJSONObject).firstOrNull { it.getString("name").endsWith(".apk") } ?: return null
    return NewVersion(tag, apk.getString("browser_download_url"), if (release.isNull("body")) "" else release.getString("body"))
}

/** Atualizações pelo GitHub: o repositório é público, por isso não vai nenhum token dentro do APK. */
object Updates {
    private const val LATEST = "https://api.github.com/repos/DaewonPT/aguenta/releases/latest"
    private const val DAY_MS = 24 * 3_600_000L

    /** No máximo uma vez por dia; a data só fica gravada depois de uma resposta, para sem rede tentar outra vez. */
    suspend fun check(ctx: Context): NewVersion? = withContext(Dispatchers.IO) {
        val prefs = ctx.getSharedPreferences("updates", Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong("last_check", 0) < DAY_MS) return@withContext null
        val json = get(LATEST) { it.bufferedReader().readText() }
        prefs.edit().putLong("last_check", System.currentTimeMillis()).apply()
        parseLatest(json, BuildConfig.VERSION_CODE)
    }

    /** Descarrega o APK e entrega-o ao instalador do Android, que pede confirmação e recusa outra assinatura. */
    suspend fun install(ctx: Context, version: NewVersion) = withContext(Dispatchers.IO) {
        val file = File(ctx.cacheDir, "update.apk")
        get(version.apkUrl) { input -> file.outputStream().use { input.copyTo(it) } }
        val installer = ctx.packageManager.packageInstaller
        val session = installer.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        installer.openSession(session).use { s ->
            s.openWrite("aguenta", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                s.fsync(out)
            }
            val result = PendingIntent.getBroadcast(
                ctx, session, Intent(ctx, InstallResult::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            s.commit(result.intentSender)
        }
    }

    private fun <T> get(url: String, read: (java.io.InputStream) -> T): T {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "Aguenta/${BuildConfig.VERSION_NAME}")
            check(c.responseCode == HttpURLConnection.HTTP_OK) { "HTTP ${c.responseCode} em $url" }
            return c.inputStream.use(read)
        } finally {
            c.disconnect()
        }
    }
}

/** O instalador responde aqui; quando precisa da confirmação da pessoa, abre o ecrã do sistema. */
class InstallResult : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1) == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
