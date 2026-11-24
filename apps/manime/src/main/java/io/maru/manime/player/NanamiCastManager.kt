package io.maru.manime.player

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.common.images.WebImage
import io.maru.manime.CrunchyBorder
import io.maru.manime.CrunchyCardBg
import io.maru.manime.CrunchyOrange
import io.maru.manime.CrunchySurface
import io.maru.manime.CrunchyTeal
import io.maru.manime.MaruTextMuted
import io.maru.manime.torrent.TorrentStreamService
import java.io.File

/**
 * Registered in AndroidManifest.xml so Google Cast Framework configures the official Default Media Receiver ("CC1AD845").
 */
class NanamiCastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions {
        return CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

internal data class CastDeviceRoute(
    val id: String,
    val name: String,
    val description: String?,
    val isSelected: Boolean,
    val isConnecting: Boolean
)

internal class NanamiCastController(private val context: Context) {
    private val appContext = context.applicationContext
    private val castContext: CastContext? = runCatching {
        CastContext.getSharedInstance(appContext)
    }.getOrNull()

    private val mediaRouter: MediaRouter? = runCatching {
        MediaRouter.getInstance(appContext)
    }.getOrNull()

    private val selector: MediaRouteSelector = MediaRouteSelector.Builder()
        .addControlCategory(
            CastMediaControlIntent.categoryForCast(
                CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
            )
        )
        .build()

    var routes by mutableStateOf<List<CastDeviceRoute>>(emptyList())
        private set

    var connectedDeviceName by mutableStateOf<String?>(null)
        private set

    var isConnecting by mutableStateOf(false)
        private set

    var activeSession by mutableStateOf<CastSession?>(null)
        private set

    val isConnected: Boolean
        get() = activeSession?.isConnected == true

    private var lastLoadedUrl: String? = null

    private val routerCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshRoutes()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshRoutes()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshRoutes()
        override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = refreshRoutes()
        override fun onRouteUnselected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = refreshRoutes()
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {
            isConnecting = true
            connectedDeviceName = session.castDevice?.friendlyName
        }

        override fun onSessionStarted(session: CastSession, sessionId: String) {
            isConnecting = false
            activeSession = session
            connectedDeviceName = session.castDevice?.friendlyName ?: "Chromecast"
            lastLoadedUrl = null
            refreshRoutes()
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            isConnecting = false
            activeSession = null
            connectedDeviceName = null
            refreshRoutes()
        }

        override fun onSessionEnding(session: CastSession) {}

        override fun onSessionEnded(session: CastSession, error: Int) {
            isConnecting = false
            activeSession = null
            connectedDeviceName = null
            lastLoadedUrl = null
            refreshRoutes()
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) {
            isConnecting = true
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            isConnecting = false
            activeSession = session
            connectedDeviceName = session.castDevice?.friendlyName ?: "Chromecast"
            refreshRoutes()
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            isConnecting = false
            activeSession = null
            connectedDeviceName = null
            refreshRoutes()
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) {
            isConnecting = true
        }
    }

    fun startDiscovery() {
        runCatching {
            mediaRouter?.addCallback(
                selector,
                routerCallback,
                MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY or MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN
            )
            castContext?.sessionManager?.addSessionManagerListener(sessionListener, CastSession::class.java)
            val existing = castContext?.sessionManager?.currentCastSession
            if (existing?.isConnected == true) {
                activeSession = existing
                connectedDeviceName = existing.castDevice?.friendlyName ?: "Chromecast"
            }
            refreshRoutes()
        }
    }

    fun stopDiscovery() {
        runCatching {
            mediaRouter?.removeCallback(routerCallback)
            castContext?.sessionManager?.removeSessionManagerListener(sessionListener, CastSession::class.java)
        }
    }

    fun refreshRoutes() {
        val router = mediaRouter ?: return
        routes = router.routes
            .filter { !it.isDefault && it.isEnabled && it.matchesSelector(selector) }
            .map { route ->
                CastDeviceRoute(
                    id = route.id,
                    name = route.name,
                    description = route.description?.takeIf { it.isNotBlank() },
                    isSelected = route.isSelected,
                    isConnecting = route.connectionState == MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTING
                )
            }
            .distinctBy { it.id }
    }

    fun selectRoute(routeId: String) {
        val router = mediaRouter ?: return
        val target = router.routes.firstOrNull { it.id == routeId } ?: return
        isConnecting = true
        connectedDeviceName = target.name
        runCatching { router.selectRoute(target) }
    }

    fun disconnect() {
        lastLoadedUrl = null
        runCatching {
            castContext?.sessionManager?.endCurrentSession(true)
            mediaRouter?.unselect(MediaRouter.UNSELECT_REASON_STOPPED)
        }
        activeSession = null
        connectedDeviceName = null
        isConnecting = false
        refreshRoutes()
    }

    fun resolveCastableUrl(rawUri: Uri, offlineCastUrl: String?): String? {
        val scheme = rawUri.scheme?.lowercase()
        if (scheme == "file") {
            val path = rawUri.path ?: return null
            if (offlineCastUrl != null) return offlineCastUrl
            TorrentStreamService.startOfflineCastServer(appContext, File(path))
            return null
        }
        val str = rawUri.toString()
        if (str.startsWith("http://127.0.0.1:") || str.startsWith("http://localhost:")) {
            val lanIp = TorrentStreamService.findLanIpv4Address() ?: return null
            return str
                .replaceFirst("http://127.0.0.1:", "http://$lanIp:")
                .replaceFirst("http://localhost:", "http://$lanIp:")
        }
        return str
    }

    fun loadMediaIfNeeded(
        castUrl: String,
        mimeType: String?,
        animeTitle: String,
        episodeNum: Int,
        episodeTitle: String,
        coverUrl: String?,
        subtitleUrl: String?,
        startPositionMs: Long,
        forceReload: Boolean = false
    ): Boolean {
        val client = activeSession?.remoteMediaClient ?: return false
        if (!forceReload && lastLoadedUrl == castUrl) return true

        val resolvedMime = mimeType ?: when {
            castUrl.contains(".m3u8", true) -> "application/x-mpegURL"
            castUrl.contains(".webm", true) -> "video/webm"
            castUrl.contains(".mkv", true) -> "video/x-matroska"
            else -> "video/mp4"
        }

        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_TV_SHOW).apply {
            putString(MediaMetadata.KEY_SERIES_TITLE, animeTitle)
            putString(MediaMetadata.KEY_TITLE, episodeTitle.ifBlank { "Episode $episodeNum" })
            putInt(MediaMetadata.KEY_EPISODE_NUMBER, episodeNum)
            if (!coverUrl.isNullOrBlank()) {
                runCatching { addImage(WebImage(Uri.parse(coverUrl))) }
            }
        }

        val tracks = buildList {
            if (!subtitleUrl.isNullOrBlank() && (subtitleUrl.contains(".vtt", true) || subtitleUrl.contains(".srt", true))) {
                add(
                    MediaTrack.Builder(1L, MediaTrack.TYPE_TEXT)
                        .setName("English Subtitles")
                        .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                        .setContentId(subtitleUrl)
                        .setContentType(if (subtitleUrl.contains(".vtt", true)) "text/vtt" else "application/x-subrip")
                        .setLanguage("en")
                        .build()
                )
            }
        }

        val mediaInfoBuilder = MediaInfo.Builder(castUrl)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(resolvedMime)
            .setMetadata(metadata)
        if (tracks.isNotEmpty()) {
            mediaInfoBuilder.setMediaTracks(tracks)
        }

        val requestBuilder = MediaLoadRequestData.Builder()
            .setMediaInfo(mediaInfoBuilder.build())
            .setAutoplay(true)
            .setCurrentTime(startPositionMs.coerceAtLeast(0L))
        if (tracks.isNotEmpty()) {
            requestBuilder.setActiveTrackIds(longArrayOf(1L))
        }

        runCatching {
            client.load(requestBuilder.build())
            lastLoadedUrl = castUrl
        }.onFailure {
            return false
        }
        return true
    }

    fun togglePlayPause() {
        val client = activeSession?.remoteMediaClient ?: return
        runCatching {
            if (client.isPlaying) client.pause() else client.play()
        }
    }

    fun seekTo(positionMs: Long) {
        val client = activeSession?.remoteMediaClient ?: return
        runCatching { client.seek(positionMs.coerceAtLeast(0L)) }
    }

    fun currentPositionMs(): Long? {
        val client = activeSession?.remoteMediaClient ?: return null
        return runCatching { client.approximateStreamPosition.takeIf { it >= 0L } }.getOrNull()
    }

    fun durationMs(): Long? {
        val client = activeSession?.remoteMediaClient ?: return null
        return runCatching { client.streamDuration.takeIf { it > 0L } }.getOrNull()
    }

    fun isRemotePlaying(): Boolean {
        val client = activeSession?.remoteMediaClient ?: return false
        return runCatching { client.isPlaying }.getOrDefault(false)
    }
}

@Composable
internal fun CastDevicePickerDialog(
    routes: List<CastDeviceRoute>,
    connectedDeviceName: String?,
    isConnecting: Boolean,
    onSelectRoute: (String) -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = CrunchySurface,
            border = BorderStroke(1.dp, CrunchyBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = if (connectedDeviceName != null) Icons.Default.CastConnected else Icons.Default.Cast,
                            contentDescription = null,
                            tint = CrunchyOrange
                        )
                        Text(
                            text = if (connectedDeviceName != null) "Connected to $connectedDeviceName" else "Cast to TV / Chromecast",
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 15.5.sp
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = MaruTextMuted)
                    }
                }

                HorizontalDivider(color = CrunchyBorder, thickness = 1.dp)

                if (routes.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            color = CrunchyOrange,
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(26.dp)
                        )
                        Text(
                            text = "Searching for Chromecast & Google TV devices on your Wi-Fi…",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Make sure your phone and TV are connected to the same Wi-Fi network.",
                            color = MaruTextMuted,
                            fontSize = 11.5.sp
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 260.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(routes, key = { it.id }) { route ->
                            val active = route.isSelected || route.name == connectedDeviceName
                            Surface(
                                onClick = {
                                    onSelectRoute(route.id)
                                    onDismiss()
                                },
                                shape = RoundedCornerShape(6.dp),
                                color = if (active) CrunchyOrange.copy(alpha = 0.18f) else CrunchyCardBg,
                                border = BorderStroke(1.dp, if (active) CrunchyOrange else CrunchyBorder),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Tv,
                                        contentDescription = null,
                                        tint = if (active) CrunchyOrange else Color.White
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = route.name,
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.5.sp
                                        )
                                        val sub = when {
                                            route.isConnecting || (isConnecting && active) -> "Connecting…"
                                            active -> "Casting now"
                                            !route.description.isNullOrBlank() -> route.description
                                            else -> "Chromecast / Google Cast"
                                        }
                                        Text(
                                            text = sub,
                                            color = if (active) CrunchyTeal else MaruTextMuted,
                                            fontSize = 11.5.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (connectedDeviceName != null || isConnecting) {
                    Button(
                        onClick = {
                            onDisconnect()
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CrunchyOrange,
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Stop Casting & Play on Phone", fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        }
    }
}
