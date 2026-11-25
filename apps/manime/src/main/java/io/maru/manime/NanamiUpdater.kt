package io.maru.manime

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal data class NanamiRelease(
    val tag: String,
    val version: String,
    val code: Long,
    val sha256: String,
    val bytes: Long
)

class NanamiUpdateFileProvider : FileProvider()

internal object NanamiUpdater {
    private const val REPO = "JmDemisana/maru-android-projects"
    private const val MAX_APK_BYTES = 160L * 1024L * 1024L
    private val stableTag = Regex("""nanami-anime-v(\d{1,4})\.(\d{1,4})\.(\d{1,4})""")
    private val sha256Pattern = Regex("[0-9a-f]{64}")
    private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    private val downloadClient = client.newBuilder().callTimeout(3, TimeUnit.MINUTES).build()

    private fun assetUrl(tag: String, file: String) = "https://github.com/$REPO/releases/download/$tag/$file"

    @Suppress("DEPRECATION")
    private fun PackageInfo.code(): Long =
        if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()

    @Suppress("DEPRECATION")
    fun installedCode(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).code()

    suspend fun latestStable(): NanamiRelease? = withContext(Dispatchers.IO) {
        val api = "https://api.github.com/repos/$REPO"
        val refs = JSONArray(checkNotNull(fetchText("$api/git/matching-refs/tags/nanami-anime-v")))
        val tags = (0 until refs.length())
            .mapNotNull { refs.optJSONObject(it)?.optString("ref")?.removePrefix("refs/tags/") }
            .filter(stableTag::matches)
            .sortedByDescending(::versionOrder)
        for (tag in tags.take(12)) {
            val release = fetchText("$api/releases/tags/$tag", acceptNotFound = true)?.let(::JSONObject) ?: continue
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue
            val version = tag.removePrefix("nanami-anime-v")
            val assets = release.optJSONArray("assets") ?: continue
            val names = (0 until assets.length()).mapNotNull { assets.optJSONObject(it)?.optString("name") }.toSet()
            if ("update.json" !in names || "nanami-anime-$version.apk" !in names) continue
            return@withContext readMetadata(tag, version)
        }
        null
    }

    private suspend fun readMetadata(tag: String, version: String): NanamiRelease {
        val data = JSONObject(checkNotNull(fetchText(assetUrl(tag, "update.json"))))
        val code = data.getLong("versionCode")
        val hash = data.getString("sha256").lowercase()
        val bytes = data.getLong("apkSize")
        check(data.getString("versionName") == version && code > 0 && bytes in 1..MAX_APK_BYTES && sha256Pattern.matches(hash)) {
            "The published update information is invalid."
        }
        return NanamiRelease(tag, version, code, hash, bytes)
    }

    private fun versionOrder(tag: String): Long {
        val (major, minor, patch) = stableTag.matchEntire(tag)!!.destructured
        return major.toLong() * 100_000_000L + minor.toLong() * 10_000L + patch.toLong()
    }

    private suspend fun fetchText(url: String, acceptNotFound: Boolean = false): String? {
        val request = Request.Builder().url(url).header("User-Agent", "NanamiAnime-Updater").build()
        return client.newCall(request).readResponse { response ->
            if (acceptNotFound && response.code == 404) return@readResponse null
            if (!response.isSuccessful) throw IOException("Update server returned HTTP ${response.code}.")
            response.body?.string() ?: throw IOException("The update server returned an empty response.")
        }
    }

    suspend fun download(context: Context, release: NanamiRelease): File = withContext(Dispatchers.IO) {
        require(release.code > installedCode(context)) { "This update is already installed." }
        val directory = File(context.cacheDir, "nanami-updates")
        check(directory.isDirectory || directory.mkdirs()) { "Couldn't prepare update storage." }
        val temporary = File.createTempFile("download-", ".apk", directory)
        try {
            val request = Request.Builder().url(assetUrl(release.tag, "nanami-anime-${release.version}.apk")).build()
            downloadClient.newCall(request).readResponse { response ->
                if (!response.isSuccessful) throw IOException("Update download returned HTTP ${response.code}.")
                val body = response.body ?: throw IOException("The update download was empty.")
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                body.byteStream().use { input ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            size += read
                            check(size <= release.bytes) { "Update size doesn't match." }
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                    }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                check(size == release.bytes && hash == release.sha256) { "The update checksum doesn't match." }
            }
            verifyApk(context, temporary, release)
            val verified = File(directory, "nanami-anime-${release.code}.apk")
            check(temporary.renameTo(verified)) { "Couldn't save the verified update." }
            directory.listFiles()?.filter { it != verified && it.name.startsWith("nanami-anime-") }?.forEach { it.delete() }
            verified
        } finally {
            temporary.delete()
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyApk(context: Context, apk: File, release: NanamiRelease) {
        val manager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val current = manager.getPackageInfo(context.packageName, flags)
        val candidate = manager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: error("Android couldn't read the update APK.")
        check(candidate.packageName == context.packageName &&
            candidate.versionName == release.version && candidate.code() == release.code && candidate.code() > current.code()
        ) { "The APK doesn't match the advertised update." }
        val trusted = signers(current)
        check(trusted.isNotEmpty() && signers(candidate) == trusted) { "The update signing certificate doesn't match." }
    }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures.orEmpty().map { it.toCharsString() }.toSet()
    }

    /** Returns false if Android sent the user to Apps settings to grant installer access. */
    fun openInstaller(context: Context, apk: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
        return true
    }
}
