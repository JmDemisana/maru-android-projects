package io.maru.manime

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaCodecList
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.StatFs
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import io.maru.manime.extensions.CrunchyrollStreamEngine
import io.maru.manime.torrent.TorrentStreamService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.libtorrent4j.LibTorrent
import java.time.Instant

internal object NanamiDiagnostics {
    private const val MIB = 1024L * 1024L

    data class Progress(val completed: Int, val total: Int, val label: String)

    data class State(
        val isRunning: Boolean = false,
        val progress: Progress? = null,
        val report: String? = null,
        val status: String? = null
    )

    private data class Check(val label: String, val timeoutMs: Long = 12_000L, val action: suspend () -> String)

    private class ProbeFailure(message: String) : Exception(message)

    suspend fun collect(context: Context, progress: (Progress) -> Unit): String {
        val prefs = MAnimePrefs(context.applicationContext)
        val lines = deviceInfo(context).toMutableList()

        suspend fun execute(check: Check) {
            val start = SystemClock.elapsedRealtime()
            val result = try {
                withTimeoutOrNull(check.timeoutMs) { check.action() } ?: "TIMEOUT"
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProbeFailure) {
                "FAIL: ${e.message}"
            } catch (e: LinkageError) {
                "FAIL: native library couldn't load (${e.javaClass.simpleName})"
            } catch (e: Exception) {
                "FAIL: ${e.javaClass.simpleName}"
            }
            lines += "${check.label}: $result (${SystemClock.elapsedRealtime() - start} ms)"
        }

        val session = try {
            withTimeoutOrNull(5_000L) { prefs.anilistSession.first() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val token = session?.token?.takeIf(String::isNotBlank)
        val checks = listOf(
            Check("Preferences") { if (session != null) "PASS" else "FAIL: settings didn't load" },
            Check("AniList anime search") {
                val results = AniListClient.search("One Piece", perPage = 5, hideNsfwContent = true).results
                if (results.any { it.mediaId == 21 }) "PASS" else "FAIL: sample title missing"
            },
            Check("AniList character cast") {
                val count = AniListClient.getCast(14751).size
                if (count > 0) "PASS ($count characters)" else "FAIL: sample cast empty"
            },
            Check("AniList browse and simulcasts") {
                val trending = AniListClient.getTrending(4).size
                val simulcasts = AniListClient.getSimulcastSchedule(4).size
                "PASS: $trending trending entries, $simulcasts scheduled"
            },
            Check("AniList account") {
                if (token == null) "SKIP: signed out" else {
                    val viewer = AniListClient.getViewer(token)
                    AniListClient.getUserList(viewer.name, token)
                    "PASS: signed-in watchlist readable"
                }
            },
            Check("AniList following activity feed") {
                if (token == null) "SKIP: signed out" else {
                    AniListClient.getActivities(perPage = 4, isFollowing = true, token = token)
                    "PASS: feed readable"
                }
            },
            Check("AniList profiles and follow lists") {
                val sample = AniListClient.searchUsers("AniList").firstOrNull() ?: throw ProbeFailure("profile search empty")
                val profile = AniListClient.getUserProfile(sample.name) ?: throw ProbeFailure("profile unavailable")
                AniListClient.getUserPeople(profile.id, AniListPeopleList.FOLLOWING, 1, token)
                AniListClient.getUserPeople(profile.id, AniListPeopleList.FOLLOWERS, 1, token)
                "PASS"
            },
            Check("AniZip / Kitsu episode mapping", 25_000L) {
                val mappings = CrunchyrollStreamEngine.getAnimeMappings(14751, "Sailor Moon Crystal")
                if (mappings.kitsuId == null && mappings.imdbId == null) "FAIL: no provider ID"
                else "PASS: Kitsu ID present=${mappings.kitsuId != null}, IMDb ID present=${mappings.imdbId != null}"
            },
            Check("AniSkip") {
                val intervals = CrunchyrollStreamEngine.getAniSkipIntervals(21, 1, failOnError = true)
                "PASS: ${intervals.size} opening / ending intervals for the sample"
            },
            Check("OpenSubtitles", 24_000L) {
                val tracks = CrunchyrollStreamEngine.fetchOpenSubtitles(21, "One Piece", 1, failOnError = true)
                "PASS: ${tracks.size} tracks listed for the sample"
            },
            Check("Offline vault") {
                TorrentStreamService.loadOfflineVault(context)
                "PASS: vault readable"
            },
            Check("Torrent engine") { withContext(Dispatchers.IO) { "PASS: libtorrent ${LibTorrent.version()}" } },
            Check("Chromecast prerequisites") {
                @Suppress("DEPRECATION")
                val info = context.packageManager.getApplicationInfo("com.google.android.gms", 0)
                if (info.enabled) "PASS: Google Play Services enabled; receiver untested" else "FAIL: Google Play Services disabled"
            },
            Check("Clip export prerequisites") {
                val encoder = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any {
                    it.isEncoder && it.supportedTypes.any { type -> type.equals("video/avc", ignoreCase = true) }
                }
                if (encoder) "PASS: H.264 encoder present; file export untested" else "FAIL: no H.264 encoder"
            },
            Check("Stable update feed", 25_000L) {
                NanamiUpdater.latestStable()?.let { "PASS: ${it.version} (${it.code})" } ?: "SKIP: no update metadata published"
            }
        )

        for ((index, check) in checks.withIndex()) {
            progress(Progress(index, checks.size, check.label))
            execute(check)
        }
        return lines.joinToString("\n")
    }

    @Suppress("DEPRECATION")
    private fun deviceInfo(context: Context): List<String> {
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(memory)
        val pageSize = runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }.getOrDefault(-1)
        val storage = StatFs(context.cacheDir.absolutePath)
        val display = context.resources.displayMetrics
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val network = listOf(
            NetworkCapabilities.TRANSPORT_WIFI to "Wi-Fi",
            NetworkCapabilities.TRANSPORT_ETHERNET to "Ethernet",
            NetworkCapabilities.TRANSPORT_CELLULAR to "cellular",
            NetworkCapabilities.TRANSPORT_VPN to "VPN"
        ).filter { caps?.hasTransport(it.first) == true }.joinToString("/") { it.second }.ifBlank { "unknown" }
        return listOf(
            "Nanami Anime $version (code ${NanamiUpdater.installedCode(context)})",
            "UTC: ${Instant.now()}",
            "Device: ${Build.MANUFACTURER} ${Build.MODEL}",
            "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}, patch ${Build.VERSION.SECURITY_PATCH})",
            "ABI: ${Build.SUPPORTED_ABIS.joinToString()} | CPUs: ${Runtime.getRuntime().availableProcessors()} | Page size: $pageSize bytes",
            "Memory: ${memory.availMem / MIB}/${memory.totalMem / MIB} MiB free | Internal space: ${storage.availableBytes / MIB} MiB free",
            "Display: ${display.widthPixels}×${display.heightPixels} | Density: ${display.densityDpi} dpi | PiP: ${context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)}",
            "Network: $network | Validated: ${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true}",
            "Checks:"
        )
    }
}
