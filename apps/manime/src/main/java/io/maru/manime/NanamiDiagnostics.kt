package io.maru.manime

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.media.MediaCodecList
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import io.maru.manime.extensions.CrunchyrollStreamEngine
import io.maru.manime.extensions.StreamLink
import io.maru.manime.extensions.StreamMatchUnverifiedException
import io.maru.manime.extensions.StreamSearchIncompleteException
import io.maru.manime.torrent.TorrentStreamService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.libtorrent4j.LibTorrent
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@OptIn(UnstableApi::class)
internal object NanamiDiagnostics {
    private const val MIB = 1024L * 1024L

    private data class PlaybackCase(val label: String, val id: Int, val episode: Int, val adult: Boolean = false)

    private val playbackCases = listOf(
        PlaybackCase("One Piece E1", 21, 1),
        PlaybackCase("One Piece E2", 21, 2),
        PlaybackCase("Sailor Moon Crystal Seasons 1–2 E1", 14751, 1),
        PlaybackCase("Sailor Moon Crystal Seasons 1–2 E18", 14751, 18),
        PlaybackCase("Sailor Moon Crystal Season 3 E1", 21462, 1),
        PlaybackCase("Your Name", 21519, 1),
        PlaybackCase("Rune's Pharmacy E1", 105562, 1, adult = true),
        PlaybackCase("euphoria E1", 10851, 1, adult = true),
        PlaybackCase("Otome Domain E1", 99441, 1, adult = true)
    )

    private class ProbeFailure(message: String) : Exception(message)

    suspend fun collect(context: Context, includeAdult: Boolean, progress: (String) -> Unit): String {
        val prefs = MAnimePrefs(context.applicationContext)
        val lines = deviceInfo(context).toMutableList()

        suspend fun check(label: String, timeoutMs: Long = 12_000L, action: suspend () -> String) {
            progress(label)
            val start = SystemClock.elapsedRealtime()
            val result = try {
                withTimeoutOrNull(timeoutMs) { action() } ?: "TIMEOUT"
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProbeFailure) {
                "FAIL: ${e.message}"
            } catch (_: StreamMatchUnverifiedException) {
                "FAIL: listings found but episode did not match"
            } catch (_: StreamSearchIncompleteException) {
                "FAIL: provider search incomplete"
            } catch (e: PlaybackException) {
                "FAIL: ${e.errorCodeName}"
            } catch (e: LinkageError) {
                "FAIL: native library couldn't load (${e.javaClass.simpleName})"
            } catch (e: Exception) {
                "FAIL: ${e.javaClass.simpleName}"
            }
            lines += "$label: $result (${SystemClock.elapsedRealtime() - start} ms)"
        }

        val session = try {
            withTimeoutOrNull(5_000L) { prefs.anilistSession.first() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val token = session?.token?.takeIf(String::isNotBlank)
        check("Preferences") { if (session != null) "PASS" else "FAIL: settings didn't load" }
        check("AniList anime search") {
            val results = AniListClient.search("One Piece", perPage = 5, hideNsfwContent = true).results
            if (results.any { it.mediaId == 21 }) "PASS" else "FAIL: sample title missing"
        }
        check("AniList character cast") {
            val count = AniListClient.getCast(14751).size
            if (count > 0) "PASS ($count characters)" else "FAIL: sample cast empty"
        }
        check("AniList browse and simulcasts") {
            val trending = AniListClient.getTrending(4).size
            val simulcasts = AniListClient.getSimulcastSchedule(4).size
            "PASS: $trending trending entries, $simulcasts scheduled"
        }
        check("AniList account") {
            if (token == null) "SKIP: signed out" else {
                val viewer = AniListClient.getViewer(token)
                AniListClient.getUserList(viewer.name, token)
                "PASS: signed-in watchlist readable"
            }
        }
        check("AniList following activity feed") {
            if (token == null) "SKIP: signed out" else {
                AniListClient.getActivities(perPage = 4, isFollowing = true, token = token)
                "PASS: feed readable"
            }
        }
        check("AniList profiles and follow lists") {
            val sample = AniListClient.searchUsers("AniList").firstOrNull() ?: throw ProbeFailure("profile search empty")
            val profile = AniListClient.getUserProfile(sample.name) ?: throw ProbeFailure("profile unavailable")
            AniListClient.getUserPeople(profile.id, AniListPeopleList.FOLLOWING, 1, token)
            AniListClient.getUserPeople(profile.id, AniListPeopleList.FOLLOWERS, 1, token)
            "PASS"
        }
        check("AniZip / Kitsu episode mapping", 25_000L) {
            val mappings = CrunchyrollStreamEngine.getAnimeMappings(14751, "Sailor Moon Crystal")
            if (mappings.kitsuId == null && mappings.imdbId == null) "FAIL: no provider ID"
            else "PASS: Kitsu ID present=${mappings.kitsuId != null}, IMDb ID present=${mappings.imdbId != null}"
        }
        check("AniSkip") {
            val intervals = CrunchyrollStreamEngine.getAniSkipIntervals(21, 1, failOnError = true)
            "PASS: ${intervals.size} opening / ending intervals for the sample"
        }
        check("OpenSubtitles", 24_000L) {
            val tracks = CrunchyrollStreamEngine.fetchOpenSubtitles(21, "One Piece", 1, failOnError = true)
            "PASS: ${tracks.size} tracks listed for the sample"
        }
        check("Offline vault") {
            TorrentStreamService.loadOfflineVault(context)
            "PASS: vault readable"
        }
        check("Torrent engine") { withContext(Dispatchers.IO) { "PASS: libtorrent ${LibTorrent.version()}" } }
        check("Chromecast prerequisites") {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getApplicationInfo("com.google.android.gms", 0)
            if (info.enabled) "PASS: Google Play Services enabled; receiver untested" else "FAIL: Google Play Services disabled"
        }
        check("Clip export prerequisites") {
            val encoder = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any {
                it.isEncoder && it.supportedTypes.any { type -> type.equals("video/avc", ignoreCase = true) }
            }
            if (encoder) "PASS: H.264 encoder present; file export untested" else "FAIL: no H.264 encoder"
        }
        check("Stable update feed", 25_000L) {
            NanamiUpdater.latestStable()?.let { "PASS: ${it.version} (${it.code})" } ?: "SKIP: no update metadata published"
        }

        val details = mutableMapOf<Int, AnimeMedia>()
        val allowedAdult = includeAdult && token != null && session?.contentOptions?.displayAdultContent == true
        if (!allowedAdult) lines += "Adult playback samples: SKIP: adult content hidden"
        for (sample in playbackCases.filter { !it.adult || allowedAdult }) {
            check("Playback ${sample.label}", 85_000L) {
                val media = details[sample.id] ?: AniListClient.getMediaDetail(sample.id, token.takeIf { sample.adult })
                    .also { details[sample.id] = it }
                if ((sample.adult || isExplicitAdultAnime(media.isAdult, media.genres)) && !allowedAdult) {
                    return@check "SKIP: adult content hidden"
                }
                val streams = withTimeoutOrNull(28_000L) {
                    CrunchyrollStreamEngine.resolveRankedStreams(
                        anilistId = media.mediaId,
                        animeTitle = media.title,
                        episodeNum = sample.episode,
                        preferredAudio = prefs.preferredAudio.first(),
                        preferredQuality = prefs.preferredQuality.first(),
                        isMovie = media.format == "MOVIE",
                        altTitles = media.candidateTitlesForStream,
                        isStandalone = media.isStandaloneFeature,
                        isAdult = media.isAdult,
                        genres = media.genres,
                        isMusicOrConcert = media.isMusicOrConcert,
                        trailerYoutubeId = media.trailerYoutubeId,
                        externalLinks = media.externalLinks
                    )
                } ?: throw ProbeFailure("source lookup timed out")
                if (streams.isEmpty()) throw ProbeFailure("no playable source found")
                val torrentAllowed = TorrentStreamService.isWifiOrEthernetActive(context)
                val eligible = streams.filter { !it.isTorrent || (torrentAllowed && it.seeders != 0) }
                if (eligible.isEmpty()) {
                    return@check if (!torrentAllowed) "SKIP: torrent checks require Wi-Fi or Ethernet"
                    else "FAIL: all listed torrents reported zero seeds"
                }
                var failure = "no video frame"
                for (source in eligible.take(2)) {
                    try {
                        renderSource(context, source, sample)
                        return@check "PASS: first frame via ${if (source.isTorrent) "torrent" else "HTTP"}, ${source.quality}"
                    } catch (e: PlaybackException) {
                        failure = e.errorCodeName
                    } catch (e: ProbeFailure) {
                        failure = e.message.orEmpty()
                    }
                }
                throw ProbeFailure(failure)
            }
        }
        lines += "A rendered frame confirms startup and decoding. Episode identity still needs a visual check."
        return lines.joinToString("\n")
    }

    private suspend fun renderSource(context: Context, source: StreamLink, sample: PlaybackCase) {
        if (!source.isTorrent) {
            renderFirstFrame(context, source.url, source.headers)
            return
        }
        if (!TorrentStreamService.isWifiOrEthernetActive(context)) throw ProbeFailure("Wi-Fi or Ethernet disconnected")
        if (TorrentStreamService.streamStatus.value.requestId != null) throw ProbeFailure("another stream is active")
        val request = TorrentStreamService.startStream(context, source.url, source.fileIdx, sample.label, sample.episode)
        try {
            val state = withTimeoutOrNull(45_000L) {
                TorrentStreamService.streamStatus.first { it.requestId == request && (it.isReady || it.error != null) }
            } ?: throw ProbeFailure("torrent didn't reach playback readiness")
            val url = state.streamUrl ?: throw ProbeFailure("torrent failed at ${state.phase}")
            renderFirstFrame(context, url, emptyMap())
        } finally {
            TorrentStreamService.stopStream(request)
        }
    }

    private suspend fun renderFirstFrame(context: Context, url: String, headers: Map<String, String>) =
        withContext(Dispatchers.Main.immediate) {
            val texture = SurfaceTexture(false)
            val surface = Surface(texture)
            try {
                val dataSource = DefaultDataSource.Factory(
                    context, DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers)
                )
                val renderers = DefaultRenderersFactory(context)
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                    .setEnableDecoderFallback(true)
                val player = ExoPlayer.Builder(context, renderers)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
                    .build()
                try {
                    val rendered = withTimeoutOrNull(16_000L) {
                        suspendCancellableCoroutine { continuation ->
                            player.addListener(object : Player.Listener {
                                override fun onRenderedFirstFrame() {
                                    if (continuation.isActive) continuation.resume(true)
                                }

                                override fun onPlayerError(error: PlaybackException) {
                                    if (continuation.isActive) continuation.resumeWithException(error)
                                }
                            })
                            player.volume = 0f
                            player.setVideoSurface(surface)
                            player.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
                            player.prepare()
                            player.play()
                        }
                    }
                    if (rendered != true) throw ProbeFailure("player didn't render a frame")
                } finally {
                    player.release()
                }
            } finally {
                surface.release()
                texture.release()
            }
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
