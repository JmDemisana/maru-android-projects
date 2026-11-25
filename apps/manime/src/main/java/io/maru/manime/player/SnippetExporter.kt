package io.maru.manime.player

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExoPlayerAssetLoader
import com.google.common.collect.ImmutableList
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import io.maru.manime.torrent.STREAM_READ_TIMEOUT_MS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

const val SNIPPET_MIN_DURATION_MS = 1_000L
const val SNIPPET_DEFAULT_DURATION_MS = 15_000L

data class SnippetRange(
    val startMs: Long,
    val endMs: Long
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

sealed interface SnippetExportState {
    data object Idle : SnippetExportState
    data class Exporting(val progressPercent: Int?, val statusLabel: String) : SnippetExportState
    data class Saved(val displayName: String, val galleryPath: String, val savedUri: Uri?) : SnippetExportState
    data class Error(val message: String) : SnippetExportState
}

internal fun normalizeSnippetRange(
    startMs: Long,
    endMs: Long,
    durationMs: Long,
    minDurationMs: Long = SNIPPET_MIN_DURATION_MS,
    maxDurationMs: Long = Long.MAX_VALUE
): SnippetRange {
    if (durationMs <= 0L) return SnippetRange(0L, 0L)
    val effectiveMin = minDurationMs.coerceAtMost(durationMs)
    val effectiveMax = maxDurationMs.coerceAtLeast(effectiveMin).coerceAtMost(durationMs)

    var s = startMs.coerceIn(0L, durationMs)
    var e = endMs.coerceIn(0L, durationMs)
    if (e < s) {
        val tmp = s
        s = e
        e = tmp
    }

    if (e - s < effectiveMin) {
        if (s + effectiveMin <= durationMs) {
            e = s + effectiveMin
        } else {
            e = durationMs
            s = (durationMs - effectiveMin).coerceAtLeast(0L)
        }
    }
    if (e - s > effectiveMax) {
        e = (s + effectiveMax).coerceAtMost(durationMs)
    }
    return SnippetRange(s, e)
}

internal fun defaultSnippetRange(
    positionMs: Long,
    durationMs: Long,
    defaultLengthMs: Long = SNIPPET_DEFAULT_DURATION_MS
): SnippetRange {
    if (durationMs <= 0L) return SnippetRange(0L, 0L)
    val length = defaultLengthMs.coerceIn(SNIPPET_MIN_DURATION_MS.coerceAtMost(durationMs), durationMs)
    val clampedPos = positionMs.coerceIn(0L, durationMs)
    val leadIn = (length / 3L).coerceAtMost(5_000L)
    val start = (clampedPos - leadIn).coerceIn(0L, (durationMs - length).coerceAtLeast(0L))
    return normalizeSnippetRange(start, start + length, durationMs)
}

internal fun applyDurationPreset(
    current: SnippetRange,
    presetLengthMs: Long,
    durationMs: Long
): SnippetRange {
    if (durationMs <= 0L) return SnippetRange(0L, 0L)
    val length = presetLengthMs.coerceIn(SNIPPET_MIN_DURATION_MS.coerceAtMost(durationMs), durationMs)
    val start = current.startMs.coerceIn(0L, (durationMs - length).coerceAtLeast(0L))
    return normalizeSnippetRange(start, start + length, durationMs)
}

internal fun shiftSnippetWindow(
    current: SnippetRange,
    deltaMs: Long,
    durationMs: Long
): SnippetRange {
    if (durationMs <= 0L) return SnippetRange(0L, 0L)
    val length = current.durationMs.coerceIn(SNIPPET_MIN_DURATION_MS.coerceAtMost(durationMs), durationMs)
    val newStart = (current.startMs + deltaMs).coerceIn(0L, (durationMs - length).coerceAtLeast(0L))
    return SnippetRange(newStart, (newStart + length).coerceAtMost(durationMs))
}

internal fun buildSnippetDisplayName(
    animeTitle: String,
    episodeNum: Int,
    startMs: Long,
    endMs: Long
): String {
    val cleanTitle = animeTitle
        .replace(Regex("[^A-Za-z0-9]+"), "_")
        .trim('_')
        .take(36)
        .ifBlank { "Anime" }
    val epTag = "Ep" + episodeNum.coerceAtLeast(0).toString().padStart(2, '0')
    val startTag = formatFilenameTimestamp(startMs)
    val endTag = formatFilenameTimestamp(endMs)
    return "Nanami_${cleanTitle}_${epTag}_${startTag}-${endTag}.mp4"
}

internal fun formatFilenameTimestamp(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0L) / 1000L)
    val hours = totalSec / 3600L
    val mins = (totalSec % 3600L) / 60L
    val secs = totalSec % 60L
    return if (hours > 0L) {
        "%dh%02dm%02ds".format(hours, mins, secs)
    } else {
        "%02dm%02ds".format(mins, secs)
    }
}

internal fun formatSnippetDuration(durationMs: Long): String {
    val totalSec = (durationMs.coerceAtLeast(0L) / 1000L)
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    return when {
        mins > 0L && secs == 0L -> "${mins}m"
        mins > 0L -> "${mins}m ${secs}s"
        else -> "${secs}s"
    }
}

@OptIn(UnstableApi::class)
internal class SnippetExporter(private val context: Context) {

    @Volatile
    private var activeTransformer: Transformer? = null

    fun cancel() {
        val t = activeTransformer
        activeTransformer = null
        if (t != null) {
            CoroutineScope(Dispatchers.Main.immediate).launch {
                runCatching { t.cancel() }
            }
        }
    }

    suspend fun exportToGallery(
        videoUri: Uri,
        mimeType: String?,
        headers: Map<String, String>,
        animeTitle: String,
        episodeNum: Int,
        range: SnippetRange,
        burnSubtitles: Boolean = true,
        externalSubtitleUrl: String? = null,
        externalSubtitleHeaders: Map<String, String> = emptyMap(),
        fallbackSubtitleCues: List<BurnInCue> = emptyList(),
        subtitleBlackFill: Boolean = true,
        subtitleSize: String = "MEDIUM",
        onProgress: (SnippetExportState.Exporting) -> Unit
    ): SnippetExportState.Saved {
        val appContext = context.applicationContext
        val cacheDir = File(appContext.cacheDir, "snippets").apply { mkdirs() }
        val tempFile = File(cacheDir, "clip_${System.currentTimeMillis()}.mp4")
        val displayName = buildSnippetDisplayName(animeTitle, episodeNum, range.startMs, range.endMs)

        try {
            val clipCues = if (burnSubtitles) {
                onProgress(SnippetExportState.Exporting(null, "Loading subtitles…"))
                val fetched = if (!externalSubtitleUrl.isNullOrBlank()) {
                    withContext(Dispatchers.IO) {
                        fetchExternalSubtitleCues(externalSubtitleUrl, externalSubtitleHeaders)
                    }
                } else {
                    emptyList()
                }
                val sourceCues = if (fetched.isNotEmpty()) fetched else fallbackSubtitleCues
                sourceCues.filter { it.endMs >= range.startMs - 500L && it.startMs <= range.endMs + 500L }
            } else {
                emptyList()
            }

            onProgress(
                SnippetExportState.Exporting(
                    null,
                    if (clipCues.isNotEmpty()) "Preparing clip with subtitles…" else "Preparing clip…"
                )
            )

            withContext(Dispatchers.Main.immediate) {
                runTransformer(
                    appContext = appContext,
                    videoUri = videoUri,
                    mimeType = mimeType,
                    headers = headers,
                    range = range,
                    clipCues = clipCues,
                    subtitleBlackFill = subtitleBlackFill,
                    subtitleSize = subtitleSize,
                    outputPath = tempFile.absolutePath,
                    onProgress = onProgress
                )
            }

            if (!tempFile.exists() || tempFile.length() <= 0L) {
                throw IllegalStateException("Exported snippet file was empty.")
            }

            onProgress(SnippetExportState.Exporting(100, "Saving to Gallery…"))
            val (savedUri, galleryPath) = withContext(Dispatchers.IO) {
                saveMp4ToGallery(appContext, tempFile, displayName)
            }

            return SnippetExportState.Saved(
                displayName = displayName,
                galleryPath = galleryPath,
                savedUri = savedUri
            )
        } finally {
            activeTransformer = null
            runCatching { if (tempFile.exists()) tempFile.delete() }
        }
    }

    private suspend fun runTransformer(
        appContext: Context,
        videoUri: Uri,
        mimeType: String?,
        headers: Map<String, String>,
        range: SnippetRange,
        clipCues: List<BurnInCue>,
        subtitleBlackFill: Boolean,
        subtitleSize: String,
        outputPath: String,
        onProgress: (SnippetExportState.Exporting) -> Unit
    ) {
        val baseDataSource = DefaultDataSource.Factory(
            appContext,
            DefaultHttpDataSource.Factory().setReadTimeoutMs(STREAM_READ_TIMEOUT_MS)
        )
        val dataSourceFactory = if (headers.isEmpty()) {
            baseDataSource
        } else {
            ResolvingDataSource.Factory(baseDataSource) { request ->
                request.withAdditionalHeaders(headers)
            }
        }
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        val assetLoaderFactory = ExoPlayerAssetLoader.Factory(
            appContext,
            DefaultDecoderFactory.Builder(appContext).build(),
            Clock.DEFAULT,
            mediaSourceFactory
        )

        val mediaItemBuilder = MediaItem.Builder()
            .setUri(videoUri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(range.startMs)
                    .setEndPositionMs(range.endMs)
                    .build()
            )
        if (!mimeType.isNullOrBlank()) {
            mediaItemBuilder.setMimeType(mimeType)
        }
        val editedBuilder = EditedMediaItem.Builder(mediaItemBuilder.build())
            .setRemoveAudio(false)
            .setRemoveVideo(false)
        if (clipCues.isNotEmpty()) {
            val subtitleOverlay = SubtitleBitmapOverlay(
                cues = clipCues,
                clipStartMs = range.startMs,
                blackFill = subtitleBlackFill,
                subtitleSize = subtitleSize
            )
            editedBuilder.setEffects(
                Effects(
                    emptyList(),
                    listOf(OverlayEffect(ImmutableList.of(subtitleOverlay)))
                )
            )
        }
        val editedMediaItem = editedBuilder.build()

        var progressJob: Job? = null
        try {
            suspendCancellableCoroutine<Unit> { cont ->
                val transformer = Transformer.Builder(appContext)
                    .setAssetLoaderFactory(assetLoaderFactory)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            progressJob?.cancel()
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            progressJob?.cancel()
                            if (cont.isActive) cont.resumeWithException(exportException)
                        }
                    })
                    .build()

                activeTransformer = transformer
                cont.invokeOnCancellation {
                    progressJob?.cancel()
                    runCatching { transformer.cancel() }
                    activeTransformer = null
                }

                transformer.start(editedMediaItem, outputPath)

                val holder = ProgressHolder()
                progressJob = CoroutineScope(Dispatchers.Main.immediate).launch {
                    while (isActive && cont.isActive) {
                        val state = runCatching { transformer.getProgress(holder) }
                            .getOrDefault(Transformer.PROGRESS_STATE_NOT_STARTED)
                        when (state) {
                            Transformer.PROGRESS_STATE_AVAILABLE -> {
                                val pct = holder.progress.coerceIn(0, 99)
                                onProgress(SnippetExportState.Exporting(pct, "Encoding MP4… $pct%"))
                            }
                            Transformer.PROGRESS_STATE_WAITING_FOR_AVAILABILITY -> {
                                onProgress(SnippetExportState.Exporting(null, "Buffering clip…"))
                            }
                            else -> Unit
                        }
                        delay(150L)
                    }
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } finally {
            progressJob?.cancel()
        }
    }

    private fun saveMp4ToGallery(
        appContext: Context,
        sourceFile: File,
        displayName: String
    ): Pair<Uri?, String> {
        val albumSubdir = "Nanami Anime"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = appContext.contentResolver
            val relativePath = "${Environment.DIRECTORY_MOVIES}/$albumSubdir"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val itemUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create Gallery entry.")

            try {
                resolver.openOutputStream(itemUri)?.use { out ->
                    sourceFile.inputStream().use { input ->
                        input.copyTo(out, bufferSize = 64 * 1024)
                    }
                } ?: throw IllegalStateException("Could not write snippet to Gallery.")

                val publishValues = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                resolver.update(itemUri, publishValues, null, null)
                return itemUri to "$relativePath/$displayName"
            } catch (e: Exception) {
                runCatching { resolver.delete(itemUri, null, null) }
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            val targetDir = File(moviesDir, albumSubdir).apply { mkdirs() }
            val destFile = File(targetDir, displayName)
            sourceFile.copyTo(destFile, overwrite = true)
            MediaScannerConnection.scanFile(
                appContext,
                arrayOf(destFile.absolutePath),
                arrayOf("video/mp4"),
                null
            )
            return Uri.fromFile(destFile) to "Movies/$albumSubdir/$displayName"
        }
    }
}
