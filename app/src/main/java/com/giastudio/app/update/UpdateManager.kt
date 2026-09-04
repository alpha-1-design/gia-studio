package com.giastudio.app.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.giastudio.app.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Over-the-air updates, straight from the project's GitHub Releases.
 *
 * The build workflow publishes the signed release APK as a release asset
 * named GIA-Studio-vX.Y.Z.apk; this checks for it, downloads it and hands
 * the file to the system package installer. No third-party update service
 * and no server: the repo's public releases page is the update channel.
 */
data class UpdateInfo(
    val version: String,
    val apkUrl: String,
    val notes: String,
)

object UpdateManager {

    private const val LATEST_URL =
        "https://api.github.com/repos/alpha-1-design/gia-studio/releases/latest"
    private const val ASSET_PREFIX = "GIA-Studio-"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    /** Returns an update when a newer release exists, else null. Never throws. */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val conn = open(LATEST_URL) ?: return@withContext null
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val release = JSONObject(body)
                val tag = release.optString("tag_name", "")
                val version = tag.removePrefix("v")
                if (version.isEmpty() || !isNewer(version, BuildConfig.VERSION_NAME)) {
                    return@withContext null
                }
                val assets = release.optJSONArray("assets") ?: return@withContext null
                var apkUrl: String? = null
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    if (name.startsWith(ASSET_PREFIX) && name.endsWith(".apk")) {
                        apkUrl = asset.optString("browser_download_url")
                        break
                    }
                }
                if (apkUrl == null) return@withContext null
                val notes = release.optString("body", "").trim().lineSequence()
                    .firstOrNull { it.isNotBlank() }.orEmpty()
                UpdateInfo(version, apkUrl!!, notes)
            } finally {
                conn.disconnect()
            }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Downloads the APK into the cache dir. Returns the file, or null on
     * failure. [onProgress] receives (bytesDone, bytesTotal); total is -1
     * when the server did not send a content length.
     */
    suspend fun download(
        context: Context,
        info: UpdateInfo,
        onProgress: (Long, Long) -> Unit,
    ): File? = withContext(Dispatchers.IO) {
        try {
            val conn = open(info.apkUrl) ?: return@withContext null
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
                val total = conn.contentLengthLong
                val dir = File(File(context.cacheDir, "updates")).apply { mkdirs() }
                val target = File(dir, "gia-update.apk")
                val input = conn.inputStream
                val output = target.outputStream()
                val buf = ByteArray(64 * 1024)
                var done = 0L
                try {
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                    output.flush()
                } finally {
                    output.close()
                    input.close()
                }
                if (done <= 0L) return@withContext null
                target
            } finally {
                conn.disconnect()
            }
            } catch (t: Throwable) {
                null
            }
        }

    /**
     * Asks the system to install the downloaded APK. Returns null on
     * success (the system installer takes over), or an error message.
     */
    fun install(context: Context, apk: File): String? {
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apk,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            null
        } catch (t: Throwable) {
            t.message ?: "Could not start the installer"
        }
    }

    /** Version comparison: "0.3.0" > "0.2.1" > "0.2.0-beta". Lenient about junk. */
    private fun isNewer(candidate: String, current: String): Boolean {
        val c = parse(candidate)
        val k = parse(current)
        if (c == null || k == null) return false
        for (i in 0..2) {
            if (c[i] != k[i]) return c[i] > k[i]
        }
        return false
    }

    private fun parse(v: String): IntArray? {
        val core = v.trim().substringBefore('-')
        val parts = core.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 3) return null
        return intArrayOf(parts[0], parts[1], parts[2])
    }

    private fun open(url: String): HttpURLConnection? {
        return try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "GIA-Studio/${BuildConfig.VERSION_NAME}")
            }
        } catch (t: Throwable) {
            null
        }
    }
}