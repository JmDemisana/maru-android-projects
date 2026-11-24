package io.maru.manime

import android.content.Context
import android.util.AtomicFile
import io.maru.manime.extensions.CrunchyrollStreamEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks confirmed English Dub / Dual-Audio anime IDs from both AniList English VA cast data
 * AND live stream verification (catching new seasonal simuldubs before AniList moderators add English VAs),
 * persisting results to disk so every card across Home, My Lists, Search, and Detail shows "Dub | Sub" accurately.
 */
object KnownDubRegistry {
    private val ioLock = Any()
    private val probeSemaphore = Semaphore(2)
    private val probedSessionMap = ConcurrentHashMap<Int, Boolean>() // mediaId -> hasDub
    private val persistedTimestampMap = ConcurrentHashMap<Int, Long>()

    private val _dubbedIds = MutableStateFlow<Set<Int>>(emptySet())
    val dubbedIds: StateFlow<Set<Int>> = _dubbedIds.asStateFlow()

    @Volatile
    private var initialized = false

    private const val NEGATIVE_TTL_MS = 6L * 3600L * 1000L // Re-check undubbed airing shows after 6 hours
    private const val POSITIVE_TTL_MS = 30L * 24L * 3600L * 1000L

    private fun cacheFile(context: Context) = AtomicFile(File(context.filesDir, "known_dub_registry.json"))

    suspend fun ensureLoaded(context: Context) = withContext(Dispatchers.IO) {
        synchronized(ioLock) {
            if (initialized) return@withContext
            readFromDisk(cacheFile(context))
            initialized = true
        }
    }

    fun recordKnownDub(mediaId: Int, context: Context? = null) {
        if (mediaId <= 0) return
        probedSessionMap[mediaId] = true
        persistedTimestampMap[mediaId] = System.currentTimeMillis()
        val current = _dubbedIds.value
        if (mediaId !in current) {
            _dubbedIds.value = current + mediaId
            if (context != null) {
                synchronized(ioLock) {
                    runCatching { writeToDisk(cacheFile(context.applicationContext)) }
                }
            }
        }
    }

    fun isKnownDub(mediaId: Int): Boolean = mediaId in _dubbedIds.value

    /**
     * Quietly probes live stream sources for visible anime that AniList hasn't marked dubbed yet
     * (especially airing seasonal shows & movies), updating [dubbedIds] immediately when a dub is found.
     */
    suspend fun verifyCandidatesInBackground(
        context: Context,
        candidates: List<AnimeMedia>,
        maxProbes: Int = 12
    ) = withContext(Dispatchers.IO) {
        ensureLoaded(context.applicationContext)
        val now = System.currentTimeMillis()

        for (media in candidates) {
            if (media.hasEnglishDub || media.mediaId in _dubbedIds.value) {
                recordKnownDub(media.mediaId)
            }
        }

        val toProbe = candidates
            .filter { media ->
                media.mediaId > 0 &&
                    !isExplicitAdultAnime(media.isAdult, media.genres) &&
                    !media.hasEnglishDub &&
                    media.mediaId !in _dubbedIds.value &&
                    !media.status.equals("NOT_YET_RELEASED", ignoreCase = true) &&
                    (media.latestAiredEpisode == null || media.latestAiredEpisode!! > 0) &&
                    shouldProbe(media.mediaId, now)
            }
            .sortedByDescending { it.isReleasing } // Prioritize currently airing seasonal simuldubs!
            .take(maxProbes)

        for (media in toProbe) {
            probeSemaphore.withPermit {
                try {
                    val status = CrunchyrollStreamEngine.checkStreamDubAvailability(
                        anilistId = media.mediaId,
                        animeTitle = media.title,
                        episodeNum = 1,
                        isMovie = media.format.equals("MOVIE", ignoreCase = true),
                        altTitles = media.candidateTitlesForStream
                    )
                    probedSessionMap[media.mediaId] = status.hasStreamDubOrDual
                    persistedTimestampMap[media.mediaId] = System.currentTimeMillis()
                    if (status.hasStreamDubOrDual) {
                        recordKnownDub(media.mediaId, context)
                    } else {
                        synchronized(ioLock) {
                            runCatching { writeToDisk(cacheFile(context.applicationContext)) }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {}
            }
        }
    }

    private fun shouldProbe(mediaId: Int, nowMs: Long): Boolean {
        val prevResult = probedSessionMap[mediaId] ?: return true
        val ts = persistedTimestampMap[mediaId] ?: 0L
        val ttl = if (prevResult) POSITIVE_TTL_MS else NEGATIVE_TTL_MS
        return (nowMs - ts) >= ttl
    }

    private fun readFromDisk(file: AtomicFile) {
        try {
            val raw = file.readFully().toString(Charsets.UTF_8)
            val arr = JSONArray(raw)
            val now = System.currentTimeMillis()
            val positives = LinkedHashSet<Int>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optInt("id")
                if (id <= 0) continue
                val hasDub = o.optBoolean("dub", false)
                val ts = o.optLong("ts", 0L)
                val ttl = if (hasDub) POSITIVE_TTL_MS else NEGATIVE_TTL_MS
                if (now - ts < ttl) {
                    probedSessionMap[id] = hasDub
                    persistedTimestampMap[id] = ts
                    if (hasDub) positives += id
                }
            }
            if (positives.isNotEmpty()) {
                _dubbedIds.value = _dubbedIds.value + positives
            }
        } catch (_: FileNotFoundException) {
        } catch (_: Exception) {}
    }

    private fun writeToDisk(file: AtomicFile) {
        val arr = JSONArray()
        for ((id, hasDub) in probedSessionMap) {
            val ts = persistedTimestampMap[id] ?: System.currentTimeMillis()
            arr.put(JSONObject().apply {
                put("id", id)
                put("dub", hasDub)
                put("ts", ts)
            })
        }
        val out = file.startWrite()
        try {
            out.write(arr.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }
}
