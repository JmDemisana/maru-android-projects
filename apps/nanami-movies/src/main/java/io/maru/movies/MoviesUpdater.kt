package io.maru.movies

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import io.maru.manime.readResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class MoviesUpdateFileProvider : FileProvider()

internal data class MoviesUpdate(val name: String, val code: Long, val bytes: Long, val sha256: String)

internal object MoviesUpdater {
    const val releasePage = "https://github.com/JmDemisana/maru-android-projects/releases/tag/nanami-movies-rotator"
    private const val assets = "https://github.com/JmDemisana/maru-android-projects/releases/download/nanami-movies-rotator"
    private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    private val downloadClient = client.newBuilder().callTimeout(3, TimeUnit.MINUTES).build()

    suspend fun available(): MoviesUpdate? {
        val request = Request.Builder().url("$assets/update.json").build()
        val value = client.newCall(request).readResponse { response ->
            if (response.code == 404) return@readResponse null
            if (!response.isSuccessful) throw IOException("GitHub returned HTTP ${response.code}.")
            JSONObject(response.body?.string() ?: error("The update manifest is empty."))
        } ?: return null
        val update = MoviesUpdate(value.getString("versionName"), value.getLong("versionCode"),
            value.getLong("apkSize"), value.getString("sha256").lowercase())
        require(update.name.matches(Regex("[0-9.]+-rotator\\.[0-9]+")) && update.code > 0 &&
            update.bytes in 1..160 * 1024 * 1024L && update.sha256.matches(Regex("[a-f0-9]{64}"))) {
            "The release manifest is invalid."
        }
        return update.takeIf { it.code > BuildConfig.VERSION_CODE }
    }

    suspend fun download(context: Context, update: MoviesUpdate): File = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "movies-updates")
        check(directory.isDirectory || directory.mkdirs()) { "Couldn't prepare update storage." }
        val temporary = File.createTempFile("part-", ".apk", directory)
        try {
            val request = Request.Builder().url("$assets/nanami-movies-rotator.apk").build()
            downloadClient.newCall(request).readResponse { response ->
                if (!response.isSuccessful) throw IOException("The download returned HTTP ${response.code}.")
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                val body = response.body ?: error("The update is empty.")
                body.byteStream().use { input ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count == -1) break
                            size += count
                            check(size <= update.bytes) { "The update size changed. Check again." }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                }
                val checksum = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                check(size == update.bytes && checksum == update.sha256) { "The update checksum changed. Check again." }
            }
            verify(context, temporary, update)
            val verified = File(directory, "nanami-movies-${update.code}.apk")
            check(temporary.renameTo(verified)) { "Couldn't stage the verified APK." }
            directory.listFiles()?.filter { it != verified && it.name.startsWith("nanami-movies-") }?.forEach { it.delete() }
            verified
        } finally {
            temporary.delete()
        }
    }

    @Suppress("DEPRECATION")
    private fun verify(context: Context, file: File, update: MoviesUpdate) {
        val manager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val current = manager.getPackageInfo(context.packageName, flags)
        val candidate = manager.getPackageArchiveInfo(file.absolutePath, flags) ?: error("Android couldn't read the update APK.")
        fun PackageInfo.code() = if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()
        fun PackageInfo.signers() = (if (Build.VERSION.SDK_INT >= 28) signingInfo?.apkContentsSigners else signatures)
            .orEmpty().map { it.toCharsString() }.toSet()
        check(candidate.packageName == current.packageName && candidate.versionName == update.name &&
            candidate.code() == update.code && candidate.code() > current.code()) { "The APK version doesn't match the release." }
        val trusted = current.signers()
        check(trusted.isNotEmpty() && trusted == candidate.signers()) { "The APK signing certificate doesn't match." }
    }

    fun install(context: Context, apk: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        return true
    }
}
