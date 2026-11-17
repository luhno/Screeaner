package app.screeaner

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Newer tablet apps from the project's GitHub Releases, so the tablet stays
 * up to date even away from the Mac. The download must match the size and
 * SHA-256 GitHub publishes for it, and Android itself only installs it over
 * this app when it carries the same signature. Network calls: background
 * threads only.
 */
class GitHubUpdate(private val userAgent: String) {
    data class Release(val versionName: String, val apkUrl: String, val size: Long, val sha256: String?)

    /** The latest release, or null (offline, rate-limited, no APK in it). */
    fun latest(): Release? = try {
        val connection = open(API)
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        if (connection.responseCode != 200) {
            null
        } else {
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name", "")
            val assets = json.optJSONArray("assets")
            var release: Release? = null
            if (tag.isNotEmpty() && assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    if (asset.optString("name") != APK_NAME) continue
                    val digest = asset.optString("digest", "")
                    release = Release(
                        versionName = tag.removePrefix("v"),
                        apkUrl = asset.optString("browser_download_url"),
                        size = asset.optLong("size", 0L),
                        sha256 = if (digest.startsWith("sha256:")) digest.removePrefix("sha256:").lowercase() else null,
                    )
                }
            }
            release
        }
    } catch (e: Exception) {
        null
    }

    /** Downloads [release] into [dir]; null when it fails or does not match what GitHub published. */
    fun download(release: Release, dir: File): File? {
        val file = File(dir, "github-update.apk")
        return try {
            dir.mkdirs()
            val connection = open(release.apkUrl)
            if (connection.responseCode != 200) return null
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        total += count
                    }
                }
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            val ok = (release.size <= 0 || total == release.size) && (release.sha256 == null || hex == release.sha256)
            if (ok) file else null.also { file.delete() }
        } catch (e: Exception) {
            file.delete()
            null
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", userAgent)
        }

    companion object {
        const val REPOSITORY = "luhno/Screeaner"
        private const val API = "https://api.github.com/repos/$REPOSITORY/releases/latest"
        private const val APK_NAME = "Screeaner.apk"

        /** "0.10.0" > "0.9.3"; a leading "v", a "-beta" suffix and a " (11)" build are ignored. */
        fun isNewer(candidate: String, current: String): Boolean {
            fun parts(text: String) = text.trim().removePrefix("v").substringBefore(' ').substringBefore('-')
                .split('.').map { it.toIntOrNull() ?: 0 }
            val a = parts(candidate)
            val b = parts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
