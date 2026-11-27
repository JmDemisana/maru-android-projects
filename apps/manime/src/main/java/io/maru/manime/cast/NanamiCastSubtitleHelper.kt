package io.maru.manime.cast

import io.maru.manime.extensions.SubtitleTrack
import io.maru.manime.player.BurnInCue
import io.maru.manime.player.cleanSubtitleText
import io.maru.manime.player.parseSubtitleCues
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.TimeUnit

internal data class CastSubtitleSidecar(
    val id: Long,
    val label: String,
    val language: String,
    val vttContent: String,
)

internal object NanamiCastSubtitleHelper {
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    internal fun subtitleSidecarFile(videoFile: File): File =
        File(videoFile.parentFile, "${videoFile.name}.subs.json")

    internal fun deleteSidecarForVideo(videoFile: File) {
        runCatching { subtitleSidecarFile(videoFile).delete() }
    }

    /**
     * Converts raw subtitle file text (.srt, .ass, .ssa, or .vtt) into valid Chromecast-ready WebVTT.
     */
    internal fun convertRawToWebVtt(rawContent: String): String? {
        val text = rawContent.removePrefix("\uFEFF").trim()
        if (text.isBlank()) return null
        val cues = parseSubtitleCues(text)
        if (cues.isNotEmpty()) {
            return cuesToWebVtt(cues)
        }
        if (text.startsWith("WEBVTT", ignoreCase = true)) {
            return text.replace("\r\n", "\n").replace("\r", "\n")
        }
        return null
    }

    internal fun cuesToWebVtt(cues: List<BurnInCue>): String {
        val sorted = cues
            .filter { it.text.isNotBlank() && it.startMs >= 0L }
            .sortedBy { it.startMs }
        return buildString(sorted.size * 64 + 16) {
            append("WEBVTT\n\n")
            sorted.forEachIndexed { idx, cue ->
                val start = cue.startMs.coerceAtLeast(0L)
                val end = maxOf(cue.endMs, start + 500L)
                append(idx + 1).append('\n')
                append(formatWebVttTime(start))
                append(" --> ")
                append(formatWebVttTime(end))
                append('\n')
                append(cue.text.trim())
                append("\n\n")
            }
        }
    }

    private fun formatWebVttTime(ms: Long): String {
        val clamped = ms.coerceAtLeast(0L)
        val hours = clamped / 3_600_000L
        val mins = (clamped % 3_600_000L) / 60_000L
        val secs = (clamped % 60_000L) / 1_000L
        val millis = clamped % 1_000L
        return "%02d:%02d:%02d.%03d".format(Locale.US, hours, mins, secs, millis)
    }

    internal fun formatSubtitleLabel(rawLabel: String?, rawLanguage: String?, index: Int): String {
        val cleanName = rawLabel?.trim().orEmpty()
        val langName = languageDisplayName(rawLanguage)
        return when {
            cleanName.isNotBlank() && !cleanName.equals(langName, ignoreCase = true) ->
                if (langName != null && !cleanName.contains(langName, ignoreCase = true)) {
                    "$langName ($cleanName)"
                } else {
                    cleanName
                }
            langName != null -> langName
            else -> "Subtitle Track $index"
        }
    }

    private fun languageDisplayName(raw: String?): String? {
        val code = raw?.trim()?.lowercase(Locale.US).orEmpty()
        if (code.isBlank()) return null
        return when (code) {
            "en", "eng", "english" -> "English"
            "ja", "jp", "jpn", "japanese" -> "Japanese"
            "es", "spa", "spanish", "lat", "es-419" -> "Spanish"
            "pt", "por", "portuguese", "pt-br", "pob" -> "Portuguese"
            "fr", "fre", "fra", "french" -> "French"
            "de", "ger", "deu", "german" -> "German"
            "it", "ita", "italian" -> "Italian"
            "ko", "kor", "korean" -> "Korean"
            "zh", "chi", "zho", "chinese", "chs", "cht" -> "Chinese"
            "ar", "ara", "arabic" -> "Arabic"
            "id", "ind", "indonesian" -> "Indonesian"
            "tl", "fil", "tagalog", "filipino" -> "Filipino"
            else -> Locale.forLanguageTag(code).displayLanguage.takeIf {
                it.isNotBlank() && !it.equals(code, ignoreCase = true)
            }?.replaceFirstChar { it.titlecase(Locale.US) } ?: code.uppercase(Locale.US)
        }
    }

    internal fun saveDownloadedSubtitles(videoFile: File, tracks: List<SubtitleTrack>) {
        if (tracks.isEmpty()) return
        val prioritized = tracks
            .distinctBy { it.url.trim() }
            .filter { it.url.startsWith("http://") || it.url.startsWith("https://") }
            .sortedByDescending {
                val lang = it.language.lowercase(Locale.US)
                lang == "en" || lang == "eng" || lang.contains("english")
            }
            .take(8)
        if (prioritized.isEmpty()) return

        val sidecars = JSONArray()
        var nextId = 1L
        val seenLanguages = mutableMapOf<String, Int>()
        for (track in prioritized) {
            val raw = runCatching {
                val req = Request.Builder().url(track.url).build()
                httpClient.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) return@use null
                    val body = res.body?.string() ?: return@use null
                    if (body.length > 2_500_000) return@use null
                    body
                }
            }.getOrNull() ?: continue

            val vtt = convertRawToWebVtt(raw) ?: continue
            val langCode = track.language.trim().ifBlank { "en" }.lowercase(Locale.US)
            val count = (seenLanguages[langCode] ?: 0) + 1
            seenLanguages[langCode] = count
            val baseLabel = languageDisplayName(langCode) ?: langCode.uppercase(Locale.US)
            val label = if (count > 1) "$baseLabel #$count" else baseLabel

            sidecars.put(
                JSONObject()
                    .put("id", nextId++)
                    .put("label", label)
                    .put("language", langCode)
                    .put("vtt", vtt),
            )
        }

        if (sidecars.length() > 0) {
            runCatching {
                subtitleSidecarFile(videoFile).writeText(sidecars.toString(), Charsets.UTF_8)
            }
        }
    }

    internal fun loadDownloadedSidecars(videoFile: File): List<CastSubtitleSidecar> {
        val file = subtitleSidecarFile(videoFile)
        if (!file.isFile) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText(Charsets.UTF_8))
            (0 until array.length()).mapNotNull { idx ->
                val obj = array.optJSONObject(idx) ?: return@mapNotNull null
                val vtt = obj.optString("vtt").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val id = obj.optLong("id", idx + 1L)
                CastSubtitleSidecar(
                    id = id,
                    label = obj.optString("label", "Subtitles $id"),
                    language = obj.optString("language", "en").ifBlank { "en" },
                    vttContent = vtt,
                )
            }
        }.getOrDefault(emptyList())
    }

    internal fun collectAllSubtitlesForCast(videoFile: File): List<CastSubtitleSidecar> {
        val savedExternal = loadDownloadedSidecars(videoFile)
        val embedded = runCatching { extractEmbeddedMkvSubtitles(videoFile) }.getOrDefault(emptyList())
        if (savedExternal.isEmpty()) return embedded
        if (embedded.isEmpty()) return savedExternal

        val combined = mutableListOf<CastSubtitleSidecar>()
        var idCounter = 1L
        embedded.forEach { sidecar ->
            combined += sidecar.copy(id = idCounter++)
        }
        savedExternal.forEach { sidecar ->
            combined += sidecar.copy(id = idCounter++)
        }
        return combined
    }

    /**
     * Ultra-fast pure Kotlin Matroska EBML subtitle scanner.
     * Seeks straight over video/audio SimpleBlock payloads so scanning a 1.5 GB MKV
     * finishes in tens of milliseconds while converting embedded SRT/ASS/VTT softsubs to WebVTT.
     */
    internal fun extractEmbeddedMkvSubtitles(file: File, maxScanMillis: Long = 10_000L): List<CastSubtitleSidecar> {
        if (!file.isFile || file.length() < 16L) return emptyList()
        val deadlineNanos = System.nanoTime() + maxScanMillis * 1_000_000L

        RandomAccessFile(file, "r").use { raf ->
            val reader = BufferedSeekReader(raf)
            val magic = ByteArray(4)
            reader.readFully(magic)
            if (magic[0] != 0x1A.toByte() || magic[1] != 0x45.toByte() ||
                magic[2] != 0xDF.toByte() || magic[3] != 0xA3.toByte()
            ) {
                return emptyList()
            }
            reader.seek(0L)

            var timecodeScaleNs = 1_000_000L
            val tracks = linkedMapOf<Long, MkvSubtitleTrack>()
            val fileLength = file.length()

            // First skip EBML header element
            val headerId = readElementId(reader) ?: return emptyList()
            val headerSize = readVintDataSize(reader) ?: return emptyList()
            if (headerId == 0x1A45DFA3L && headerSize > 0L) {
                reader.seek((reader.position + headerSize).coerceAtMost(fileLength))
            } else {
                reader.seek(0L)
            }

            // Locate Segment
            var segmentEnd = fileLength
            while (reader.position < fileLength && System.nanoTime() < deadlineNanos) {
                val id = readElementId(reader) ?: break
                val size = readVintDataSize(reader) ?: break
                val dataStart = reader.position
                val elemEnd = if (size < 0L) fileLength else (dataStart + size).coerceAtMost(fileLength)
                if (id == 0x18538067L) { // Segment
                    segmentEnd = elemEnd
                    break
                }
                reader.seek(elemEnd)
            }

            // Walk top-level Segment children (Info, Tracks, Clusters)
            var currentClusterTimecode = 0L
            while (reader.position < segmentEnd && System.nanoTime() < deadlineNanos) {
                val id = readElementId(reader) ?: break
                val size = readVintDataSize(reader) ?: break
                val dataStart = reader.position
                val elemEnd = if (size < 0L) segmentEnd else (dataStart + size).coerceAtMost(segmentEnd)

                when (id) {
                    0x1549A966L -> { // Info
                        while (reader.position < elemEnd) {
                            val subId = readElementId(reader) ?: break
                            val subSize = readVintDataSize(reader) ?: break
                            val subEnd = if (subSize < 0L) elemEnd else (reader.position + subSize).coerceAtMost(elemEnd)
                            if (subId == 0x2AD7B1L && subSize in 1L..8L) {
                                timecodeScaleNs = readUIntBE(reader, subSize.toInt()).coerceAtLeast(1L)
                            }
                            reader.seek(subEnd)
                        }
                    }
                    0x1654AE6BL -> { // Tracks
                        parseMkvTracks(reader, elemEnd, tracks)
                        if (tracks.isEmpty()) {
                            return emptyList()
                        }
                    }
                    0x1F43B675L -> { // Cluster
                        currentClusterTimecode = 0L
                        while (reader.position < elemEnd && System.nanoTime() < deadlineNanos) {
                            val subId = readElementId(reader) ?: break
                            val subSize = readVintDataSize(reader) ?: break
                            val subStart = reader.position
                            val subEnd = if (subSize < 0L) elemEnd else (subStart + subSize).coerceAtMost(elemEnd)

                            when (subId) {
                                0xE7L -> { // Cluster Timecode
                                    if (subSize in 1L..8L) {
                                        currentClusterTimecode = readUIntBE(reader, subSize.toInt())
                                    }
                                }
                                0xA3L -> { // SimpleBlock
                                    if (tracks.isNotEmpty() && subSize > 4L) {
                                        readBlockForSubtitle(
                                            reader = reader,
                                            blockEnd = subEnd,
                                            clusterTimecode = currentClusterTimecode,
                                            timecodeScaleNs = timecodeScaleNs,
                                            explicitDurationTicks = null,
                                            tracks = tracks,
                                        )
                                    }
                                }
                                0xA0L -> { // BlockGroup
                                    if (tracks.isNotEmpty()) {
                                        var blockStart = -1L
                                        var blockEnd = -1L
                                        var blockDurationTicks: Long? = null
                                        while (reader.position < subEnd) {
                                            val bgId = readElementId(reader) ?: break
                                            val bgSize = readVintDataSize(reader) ?: break
                                            val bgDataStart = reader.position
                                            val bgEnd = if (bgSize < 0L) subEnd else (bgDataStart + bgSize).coerceAtMost(subEnd)
                                            when (bgId) {
                                                0xA1L -> {
                                                    blockStart = bgDataStart
                                                    blockEnd = bgEnd
                                                }
                                                0x9BL -> if (bgSize in 1L..8L) {
                                                    blockDurationTicks = readUIntBE(reader, bgSize.toInt())
                                                }
                                            }
                                            reader.seek(bgEnd)
                                        }
                                        if (blockStart >= 0L && blockEnd > blockStart + 4L) {
                                            reader.seek(blockStart)
                                            readBlockForSubtitle(
                                                reader = reader,
                                                blockEnd = blockEnd,
                                                clusterTimecode = currentClusterTimecode,
                                                timecodeScaleNs = timecodeScaleNs,
                                                explicitDurationTicks = blockDurationTicks,
                                                tracks = tracks,
                                            )
                                        }
                                    }
                                }
                            }
                            reader.seek(subEnd)
                        }
                    }
                    else -> {
                        // Fast skip SeekHead, Cues, Attachments, Void, Tags, etc.
                    }
                }
                reader.seek(elemEnd)
            }

            if (tracks.isEmpty()) return emptyList()
            val out = mutableListOf<CastSubtitleSidecar>()
            var idSeq = 1L
            tracks.values.forEachIndexed { idx, track ->
                if (track.cues.isEmpty()) return@forEachIndexed
                val vtt = cuesToWebVtt(track.cues)
                val label = formatSubtitleLabel(track.name, track.language, idx + 1)
                val lang = track.language?.trim()?.ifBlank { "en" }?.lowercase(Locale.US) ?: "en"
                out += CastSubtitleSidecar(
                    id = idSeq++,
                    label = label,
                    language = lang,
                    vttContent = vtt,
                )
            }
            return out
        }
    }

    private data class MkvSubtitleTrack(
        val trackNumber: Long,
        val codecId: String,
        val name: String?,
        val language: String?,
        val defaultDurationNs: Long,
        val cues: MutableList<BurnInCue> = mutableListOf(),
    )

    private fun parseMkvTracks(
        reader: BufferedSeekReader,
        tracksEnd: Long,
        out: MutableMap<Long, MkvSubtitleTrack>,
    ) {
        while (reader.position < tracksEnd) {
            val id = readElementId(reader) ?: break
            val size = readVintDataSize(reader) ?: break
            val end = if (size < 0L) tracksEnd else (reader.position + size).coerceAtMost(tracksEnd)
            if (id == 0xAEL) { // TrackEntry
                var number = -1L
                var type = -1L
                var codecId = ""
                var name: String? = null
                var language: String? = null
                var defaultDuration = 0L

                while (reader.position < end) {
                    val fId = readElementId(reader) ?: break
                    val fSize = readVintDataSize(reader) ?: break
                    val fEnd = if (fSize < 0L) end else (reader.position + fSize).coerceAtMost(end)
                    when (fId) {
                        0xD7L -> if (fSize in 1L..8L) number = readUIntBE(reader, fSize.toInt())
                        0x83L -> if (fSize in 1L..8L) type = readUIntBE(reader, fSize.toInt())
                        0x86L -> if (fSize in 1L..128L) codecId = readUtf8(reader, fSize.toInt())
                        0x536EL -> if (fSize in 1L..256L) name = readUtf8(reader, fSize.toInt())
                        0x22B59CL -> if (fSize in 1L..32L) language = readUtf8(reader, fSize.toInt())
                        0x22B59DL -> if (fSize in 1L..32L) language = readUtf8(reader, fSize.toInt()) // LanguageBCP47
                        0x23E383L -> if (fSize in 1L..8L) defaultDuration = readUIntBE(reader, fSize.toInt())
                    }
                    reader.seek(fEnd)
                }
                if (type == 17L && number > 0L && codecId.startsWith("S_TEXT/", ignoreCase = true)) {
                    out[number] = MkvSubtitleTrack(
                        trackNumber = number,
                        codecId = codecId.uppercase(Locale.US),
                        name = name,
                        language = language,
                        defaultDurationNs = defaultDuration,
                    )
                }
            }
            reader.seek(end)
        }
    }

    private fun readBlockForSubtitle(
        reader: BufferedSeekReader,
        blockEnd: Long,
        clusterTimecode: Long,
        timecodeScaleNs: Long,
        explicitDurationTicks: Long?,
        tracks: Map<Long, MkvSubtitleTrack>,
    ) {
        val trackNumber = readVintValue(reader) ?: return
        val track = tracks[trackNumber] ?: return
        if (reader.position + 3L > blockEnd) return

        val relHigh = reader.readUnsignedByte() ?: return
        val relLow = reader.readUnsignedByte() ?: return
        val relativeTicks = ((relHigh shl 8) or relLow).toShort().toLong()
        reader.readUnsignedByte() ?: return // flags byte

        val payloadLen = (blockEnd - reader.position).toInt()
        if (payloadLen <= 0 || payloadLen > 64 * 1024) return
        val rawText = readUtf8(reader, payloadLen)
        val dialogue = extractMkvTextPayload(rawText, track.codecId)
        if (dialogue.isBlank()) return

        val startMs = ((clusterTimecode + relativeTicks) * timecodeScaleNs) / 1_000_000L
        if (startMs < 0L) return
        val durationMs = when {
            explicitDurationTicks != null && explicitDurationTicks > 0L ->
                (explicitDurationTicks * timecodeScaleNs) / 1_000_000L
            track.defaultDurationNs > 0L ->
                track.defaultDurationNs / 1_000_000L
            else -> 3_000L
        }.coerceIn(400L, 25_000L)

        track.cues += BurnInCue(
            startMs = startMs,
            endMs = startMs + durationMs,
            text = dialogue,
        )
    }

    private fun extractMkvTextPayload(raw: String, codecId: String): String {
        val body = if (codecId.contains("ASS") || codecId.contains("SSA")) {
            val parts = raw.split(',', limit = 9)
            if (parts.size >= 9) parts[8] else raw
        } else {
            raw
        }
        return cleanSubtitleText(body)
    }

    private class BufferedSeekReader(private val raf: RandomAccessFile) {
        private val fileLen = raf.length()
        private val buf = ByteArray(64 * 1024)
        private var bufStart = 0L
        private var bufLen = 0
        var position = 0L
            private set

        fun seek(target: Long) {
            val clamped = target.coerceIn(0L, fileLen)
            position = clamped
        }

        fun readUnsignedByte(): Int? {
            if (position >= fileLen) return null
            ensureBuffered()
            if (position >= bufStart + bufLen) return null
            val b = buf[(position - bufStart).toInt()].toInt() and 0xFF
            position++
            return b
        }

        fun readFully(dst: ByteArray) {
            var off = 0
            while (off < dst.size) {
                if (position >= fileLen) throw EOFException()
                ensureBuffered()
                val available = (bufLen - (position - bufStart).toInt()).coerceAtLeast(0)
                if (available == 0) throw EOFException()
                val copy = minOf(available, dst.size - off)
                System.arraycopy(buf, (position - bufStart).toInt(), dst, off, copy)
                position += copy
                off += copy
            }
        }

        private fun ensureBuffered() {
            if (position in bufStart until (bufStart + bufLen)) return
            raf.seek(position)
            bufStart = position
            bufLen = raf.read(buf).coerceAtLeast(0)
        }
    }

    private fun readElementId(reader: BufferedSeekReader): Long? {
        val first = reader.readUnsignedByte() ?: return null
        if (first == 0) return null
        val len = vintLength(first).coerceAtMost(4)
        var value = first.toLong()
        for (i in 1 until len) {
            val next = reader.readUnsignedByte() ?: return null
            value = (value shl 8) or next.toLong()
        }
        return value
    }

    private fun readVintDataSize(reader: BufferedSeekReader): Long? {
        val first = reader.readUnsignedByte() ?: return null
        if (first == 0) return null
        val len = vintLength(first).coerceAtMost(8)
        val mask = (1 shl (8 - len)) - 1
        var value = (first and mask).toLong()
        for (i in 1 until len) {
            val next = reader.readUnsignedByte() ?: return null
            value = (value shl 8) or next.toLong()
        }
        val unknownSentinel = (1L shl (7 * len)) - 1L
        return if (value == unknownSentinel) -1L else value
    }

    private fun readVintValue(reader: BufferedSeekReader): Long? {
        val first = reader.readUnsignedByte() ?: return null
        if (first == 0) return null
        val len = vintLength(first).coerceAtMost(8)
        val mask = (1 shl (8 - len)) - 1
        var value = (first and mask).toLong()
        for (i in 1 until len) {
            val next = reader.readUnsignedByte() ?: return null
            value = (value shl 8) or next.toLong()
        }
        return value
    }

    private fun vintLength(firstUnsignedByte: Int): Int {
        var mask = 0x80
        for (len in 1..8) {
            if ((firstUnsignedByte and mask) != 0) return len
            mask = mask ushr 1
        }
        return 8
    }

    private fun readUIntBE(reader: BufferedSeekReader, byteCount: Int): Long {
        var value = 0L
        for (i in 0 until byteCount) {
            val b = reader.readUnsignedByte() ?: break
            value = (value shl 8) or b.toLong()
        }
        return value
    }

    private fun readUtf8(reader: BufferedSeekReader, byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        reader.readFully(bytes)
        return bytes.toString(Charsets.UTF_8).trimEnd('\u0000')
    }
}
