package io.maru.manime.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.annotation.OptIn
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlaySettings
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

data class BurnInCue(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

/**
 * Records live ExoPlayer cues during playback/preview so even embedded MKV subtitle
 * tracks can be burned into exported MP4 snippets.
 */
internal class LiveCueRecorder {
    private val recorded = mutableListOf<BurnInCue>()
    private var openStartMs: Long = -1L
    private var openText: String = ""

    @Synchronized
    fun onCues(positionMs: Long, text: String) {
        val cleaned = cleanSubtitleText(text)
        val pos = positionMs.coerceAtLeast(0L)
        if (openText.isNotEmpty() && cleaned != openText) {
            closeOpenCue(pos)
        }
        if (cleaned.isNotEmpty() && cleaned != openText) {
            openStartMs = pos
            openText = cleaned
        }
    }

    @Synchronized
    fun flush(positionMs: Long) {
        if (openText.isNotEmpty()) {
            closeOpenCue(positionMs.coerceAtLeast(openStartMs + 250L))
        }
    }

    @Synchronized
    fun snapshot(): List<BurnInCue> {
        val list = recorded.toMutableList()
        if (openText.isNotEmpty() && openStartMs >= 0L) {
            list += BurnInCue(openStartMs, openStartMs + 4_000L, openText)
        }
        return list.sortedBy { it.startMs }
    }

    @Synchronized
    fun hasCuesInRange(startMs: Long, endMs: Long): Boolean {
        return snapshot().any { it.endMs > startMs && it.startMs < endMs }
    }

    @Synchronized
    fun clear() {
        recorded.clear()
        openStartMs = -1L
        openText = ""
    }

    private fun closeOpenCue(endMs: Long) {
        val safeEnd = if (endMs > openStartMs + 120L && endMs - openStartMs <= 15_000L) {
            endMs
        } else {
            openStartMs + 3_500L
        }
        // Avoid duplicates when looping preview
        val duplicate = recorded.any {
            it.text == openText && kotlin.math.abs(it.startMs - openStartMs) < 600L
        }
        if (!duplicate) {
            recorded += BurnInCue(openStartMs, safeEnd, openText)
        }
        openStartMs = -1L
        openText = ""
    }
}

internal fun cleanSubtitleText(raw: String): String {
    if (raw.isBlank()) return ""
    return raw
        .replace("\\N", "\n")
        .replace("\\n", "\n")
        .replace("\\h", " ")
        .replace(Regex("\\{[^}]*\\}"), "") // ASS override tags like {\an8}, {\i1}
        .replace(Regex("<[^>]+>"), "")     // HTML/VTT tags like <i>, <b>, <c>
        .lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
        .trim()
}

internal fun parseSubtitleCues(rawContent: String): List<BurnInCue> {
    val text = rawContent.removePrefix("\uFEFF").replace("\r\n", "\n").replace("\r", "\n")
    if (text.isBlank()) return emptyList()

    val isAss = text.contains("[Events]", ignoreCase = true) ||
        text.lineSequence().any { it.trimStart().startsWith("Dialogue:", ignoreCase = true) }

    return if (isAss) parseAssCues(text) else parseSrtOrVttCues(text)
}

private fun parseSrtOrVttCues(text: String): List<BurnInCue> {
    val result = mutableListOf<BurnInCue>()
    val timeRegex = Regex(
        """(\d{1,2}:)?(\d{2}):(\d{2})[.,](\d{1,3})\s*-->\s*(\d{1,2}:)?(\d{2}):(\d{2})[.,](\d{1,3})"""
    )

    val blocks = text.split(Regex("\n{2,}"))
    for (block in blocks) {
        val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) continue
        val timeLineIdx = lines.indexOfFirst { it.contains("-->") }
        if (timeLineIdx < 0) continue

        val match = timeRegex.find(lines[timeLineIdx]) ?: continue
        val startMs = parseTimestampParts(
            match.groupValues[1],
            match.groupValues[2],
            match.groupValues[3],
            match.groupValues[4]
        )
        val endMs = parseTimestampParts(
            match.groupValues[5],
            match.groupValues[6],
            match.groupValues[7],
            match.groupValues[8]
        )
        if (endMs <= startMs) continue

        val body = lines.drop(timeLineIdx + 1).joinToString("\n")
        val cleaned = cleanSubtitleText(body)
        if (cleaned.isNotEmpty()) {
            result += BurnInCue(startMs, endMs, cleaned)
        }
    }
    return result.sortedBy { it.startMs }
}

private fun parseAssCues(text: String): List<BurnInCue> {
    val result = mutableListOf<BurnInCue>()
    var startIdx = 1
    var endIdx = 2
    var textIdx = 9

    for (rawLine in text.lineSequence()) {
        val line = rawLine.trim()
        if (line.startsWith("Format:", ignoreCase = true)) {
            val cols = line.substringAfter(':').split(',').map { it.trim().lowercase() }
            val s = cols.indexOf("start")
            val e = cols.indexOf("end")
            val t = cols.indexOf("text")
            if (s >= 0 && e >= 0 && t >= 0) {
                startIdx = s
                endIdx = e
                textIdx = t
            }
            continue
        }
        if (!line.startsWith("Dialogue:", ignoreCase = true)) continue
        val payload = line.substringAfter(':').trimStart()
        val parts = payload.split(',', limit = textIdx + 1)
        if (parts.size <= textIdx) continue

        val startMs = parseAssTimestamp(parts[startIdx].trim()) ?: continue
        val endMs = parseAssTimestamp(parts[endIdx].trim()) ?: continue
        if (endMs <= startMs) continue

        val cleaned = cleanSubtitleText(parts[textIdx])
        if (cleaned.isNotEmpty()) {
            result += BurnInCue(startMs, endMs, cleaned)
        }
    }
    return result.sortedBy { it.startMs }
}

private fun parseTimestampParts(hoursPart: String, minPart: String, secPart: String, msPart: String): Long {
    val hours = hoursPart.removeSuffix(":").toLongOrNull() ?: 0L
    val mins = minPart.toLongOrNull() ?: 0L
    val secs = secPart.toLongOrNull() ?: 0L
    val millis = msPart.padEnd(3, '0').take(3).toLongOrNull() ?: 0L
    return ((hours * 3600L) + (mins * 60L) + secs) * 1000L + millis
}

private fun parseAssTimestamp(raw: String): Long? {
    val match = Regex("""(\d+):(\d{2}):(\d{2})[.,](\d{1,3})""").find(raw) ?: return null
    val hours = match.groupValues[1].toLongOrNull() ?: return null
    val mins = match.groupValues[2].toLongOrNull() ?: return null
    val secs = match.groupValues[3].toLongOrNull() ?: return null
    val frac = match.groupValues[4]
    val millis = when (frac.length) {
        1 -> (frac.toLongOrNull() ?: 0L) * 100L
        2 -> (frac.toLongOrNull() ?: 0L) * 10L
        else -> frac.take(3).toLongOrNull() ?: 0L
    }
    return ((hours * 3600L) + (mins * 60L) + secs) * 1000L + millis
}

internal fun cuesActiveAt(cues: List<BurnInCue>, timeMs: Long): String {
    if (cues.isEmpty()) return ""
    val active = cues.filter { timeMs >= it.startMs && timeMs < it.endMs }
    if (active.isEmpty()) return ""
    return active.map { it.text }.distinct().joinToString("\n")
}

private val subtitleHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
}

internal fun fetchExternalSubtitleCues(
    url: String,
    headers: Map<String, String> = emptyMap()
): List<BurnInCue> {
    if (url.isBlank()) return emptyList()
    return runCatching {
        val reqBuilder = Request.Builder().url(url)
        for ((k, v) in headers) {
            if (k.isNotBlank() && v.isNotBlank()) {
                reqBuilder.header(k, v)
            }
        }
        subtitleHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string().orEmpty()
            parseSubtitleCues(body)
        }
    }.getOrDefault(emptyList())
}

/**
 * Renders crisp Crunchyroll-style subtitles directly onto exported MP4 frames
 * using Media3's GPU BitmapOverlay pipeline.
 */
@OptIn(UnstableApi::class)
internal class SubtitleBitmapOverlay(
    private val cues: List<BurnInCue>,
    private val clipStartMs: Long,
    private val blackFill: Boolean,
    private val subtitleSize: String
) : BitmapOverlay() {

    private var videoWidth = 1280
    private var videoHeight = 720
    private var frameWidth = 1280
    private var frameHeight = 720
    private var lastRenderedText: String? = null
    private var cachedBitmap: Bitmap? = null
    private val emptyBitmap: Bitmap by lazy {
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    }

    override fun configure(videoSize: Size) {
        super.configure(videoSize)
        if (videoSize.width > 0 && videoSize.height > 0) {
            videoWidth = videoSize.width
            videoHeight = videoSize.height
            val scale = minOf(1f, 1920f / videoWidth.coerceAtLeast(1), 1080f / videoHeight.coerceAtLeast(1))
            frameWidth = (videoWidth * scale).roundToInt().coerceAtLeast(320)
            frameHeight = (videoHeight * scale).roundToInt().coerceAtLeast(180)
            lastRenderedText = null
        }
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val sx = videoWidth.toFloat() / frameWidth.coerceAtLeast(1)
        val sy = videoHeight.toFloat() / frameHeight.coerceAtLeast(1)
        return OverlaySettings.Builder()
            .setScale(sx, sy)
            .build()
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val sourceTimeMs = clipStartMs + (presentationTimeUs / 1000L)
        val text = cuesActiveAt(cues, sourceTimeMs)
        if (text.isEmpty()) {
            return emptyBitmap
        }
        if (text == lastRenderedText && cachedBitmap != null) {
            return cachedBitmap!!
        }
        val rendered = renderSubtitleFrame(text)
        cachedBitmap?.recycle()
        cachedBitmap = rendered
        lastRenderedText = text
        return rendered
    }

    override fun release() {
        super.release()
        runCatching { cachedBitmap?.recycle() }
        cachedBitmap = null
        lastRenderedText = null
    }

    private fun renderSubtitleFrame(text: String): Bitmap {
        val bitmap = Bitmap.createBitmap(frameWidth, frameHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val sizeMultiplier = when (subtitleSize.uppercase()) {
            "SMALL" -> 0.042f
            "LARGE" -> 0.064f
            else -> 0.052f
        }
        val textSizePx = (frameHeight * sizeMultiplier).coerceIn(18f, 76f)
        val horizontalPadding = (frameWidth * 0.07f).roundToInt()
        val maxTextWidth = (frameWidth - horizontalPadding * 2).coerceAtLeast(120)

        val fillPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = textSizePx
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setShadowLayer(textSizePx * 0.12f, 0f, textSizePx * 0.06f, Color.argb(220, 0, 0, 0))
        }
        val strokePaint = TextPaint(fillPaint).apply {
            style = Paint.Style.STROKE
            strokeWidth = (textSizePx * 0.13f).coerceAtLeast(2.5f)
            strokeJoin = Paint.Join.ROUND
            color = Color.argb(235, 0, 0, 0)
            clearShadowLayer()
        }

        val fillLayout = StaticLayout.Builder.obtain(text, 0, text.length, fillPaint, maxTextWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(textSizePx * 0.12f, 1.0f)
            .setIncludePad(false)
            .build()
        val strokeLayout = StaticLayout.Builder.obtain(text, 0, text.length, strokePaint, maxTextWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(textSizePx * 0.12f, 1.0f)
            .setIncludePad(false)
            .build()

        val bottomMargin = frameHeight * 0.065f
        val drawTop = (frameHeight - bottomMargin - fillLayout.height).coerceAtLeast(frameHeight * 0.08f)
        val drawLeft = (frameWidth - maxTextWidth) / 2f

        if (blackFill) {
            var maxLineW = 0f
            for (i in 0 until fillLayout.lineCount) {
                maxLineW = maxOf(maxLineW, fillLayout.getLineWidth(i))
            }
            val boxPadX = textSizePx * 0.42f
            val boxPadY = textSizePx * 0.22f
            val centerX = frameWidth / 2f
            val bgRect = RectF(
                (centerX - maxLineW / 2f - boxPadX).coerceAtLeast(8f),
                (drawTop - boxPadY).coerceAtLeast(8f),
                (centerX + maxLineW / 2f + boxPadX).coerceAtMost(frameWidth - 8f),
                (drawTop + fillLayout.height + boxPadY).coerceAtMost(frameHeight - 8f)
            )
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(175, 0, 0, 0)
            }
            val radius = textSizePx * 0.22f
            canvas.drawRoundRect(bgRect, radius, radius, bgPaint)
        }

        canvas.save()
        canvas.translate(drawLeft, drawTop)
        strokeLayout.draw(canvas)
        fillLayout.draw(canvas)
        canvas.restore()

        return bitmap
    }
}
