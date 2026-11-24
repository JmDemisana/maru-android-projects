package io.maru.manime

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

data class PendingAniListUpdate(
    val mediaId: Int,
    val title: String,
    val status: String?,
    val progress: Int?,
    val score: Double?,
    val totalEpisodes: Int?,
    val isWatchedAutoBump: Boolean = false,
    val accountUsername: String = "",
    val queuedAt: Long = System.currentTimeMillis(),
    val accountId: Int? = null,
    val isRemoval: Boolean = false
)

internal fun PendingAniListUpdate.belongsTo(account: SavedAniListAccount, includeUnassigned: Boolean = false): Boolean =
    if (accountId != null) accountId == account.id
    else if (accountUsername.isBlank()) includeUnassigned
    else accountUsername.equals(account.username, ignoreCase = true)

private fun PendingAniListUpdate.sameDestination(other: PendingAniListUpdate): Boolean =
    if (accountId != null && other.accountId != null) accountId == other.accountId
    else accountUsername.equals(other.accountUsername, ignoreCase = true)

/**
 * Provides two essential capabilities:
 * 1. Full local watchlist & episode progress tracking so Nanami Anime works 100% without an AniList account.
 * 2. Persistent offline AniList sync queue: when 80% of an episode is watched (or an entry is edited) while offline,
 *    local UI updates immediately and the queued mutation flushes automatically as soon as internet connectivity returns.
 */
object LocalLibraryStore {
    private val ioLock = Any()
    private val flushMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _localEntries = MutableStateFlow<Map<Int, AnimeMedia>>(emptyMap())
    val localEntries: StateFlow<Map<Int, AnimeMedia>> = _localEntries.asStateFlow()

    private val _pendingQueue = MutableStateFlow<List<PendingAniListUpdate>>(emptyList())
    val pendingQueue: StateFlow<List<PendingAniListUpdate>> = _pendingQueue.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    @Volatile
    private var loaded = false

    private fun libraryFile(context: Context) = AtomicFile(File(context.filesDir, "local_anime_library.json"))
    private fun queueFile(context: Context) = AtomicFile(File(context.filesDir, "pending_anilist_queue.json"))

    suspend fun ensureLoaded(context: Context) = withContext(Dispatchers.IO) {
        synchronized(ioLock) {
            if (loaded) return@withContext
            _localEntries.value = readLocalLibrary(libraryFile(context))
            _pendingQueue.value = readQueue(queueFile(context))
            loaded = true
        }
    }

    suspend fun applyAdultRatings(context: Context, ratings: Map<Int, Boolean>) = withContext(Dispatchers.IO) {
        ensureLoaded(context)
        synchronized(ioLock) {
            val updated = _localEntries.value.mapValues { (id, media) ->
                val isAdult = ratings[id]
                if (!media.hasKnownAdultRating && isAdult != null) {
                    media.copy(isAdult = isAdult, hasKnownAdultRating = true)
                } else {
                    media
                }
            }
            if (updated != _localEntries.value) {
                writeLocalLibrary(libraryFile(context), updated)
                _localEntries.value = updated
            }
        }
    }

    /**
     * Merges local entries and pending offline updates onto AniList lists (or builds full lists when signed out).
     */
    fun buildMergedLists(
        remoteLists: Map<String, List<AnimeMedia>>,
        localMap: Map<Int, AnimeMedia>,
        pending: List<PendingAniListUpdate>,
        isLoggedIn: Boolean,
        activeUsername: String = "",
        activeAccountId: Int? = null
    ): Map<String, List<AnimeMedia>> {
        val relevantPending = pending.filter {
            if (it.accountId != null && activeAccountId != null) it.accountId == activeAccountId
            else it.accountUsername.isBlank() || it.accountUsername.equals(activeUsername, ignoreCase = true)
        }
        val pendingByMedia = relevantPending.associateBy { it.mediaId }

        if (!isLoggedIn) {
            return groupMediaIntoLists(localMap.values.toList())
        }

        val mergedById = LinkedHashMap<Int, AnimeMedia>()
        for (list in remoteLists.values) {
            for (item in list) {
                mergedById[item.mediaId] = item
            }
        }
        for (item in relevantPending) {
            if (item.isRemoval) mergedById.remove(item.mediaId)
        }

        // Overlay local progress/entries that have pending updates or were tracked locally while offline
        for ((id, localItem) in localMap) {
            val remote = mergedById[id]
            val queued = pendingByMedia[id]
            if (queued?.isRemoval == true) continue
            if (remote == null) {
                if (queued != null) {
                    val legacy = queued.accountId == null
                    mergedById[id] = localItem.copy(
                        listEntryId = null,
                        listStatus = queued.status ?: localItem.listStatus ?: "CURRENT",
                        progress = if (legacy) maxOf(localItem.progress, queued.progress ?: 0) else queued.progress ?: 0,
                        score = queued.score ?: localItem.score.takeIf { legacy },
                        notes = null,
                        isPrivate = false,
                        createdAt = null,
                        updatedAt = queued.queuedAt / 1000L
                    )
                }
            } else if (queued != null) {
                val nextProgress = if (queued.isWatchedAutoBump) {
                    maxOf(remote.progress, queued.progress ?: remote.progress)
                } else {
                    queued.progress ?: remote.progress
                }
                val nextStatus = if (queued.isWatchedAutoBump) {
                    when {
                        remote.listStatus in setOf("COMPLETED", "REPEATING") -> remote.listStatus
                        remote.episodes != null && nextProgress >= remote.episodes -> "COMPLETED"
                        else -> remote.listStatus ?: "CURRENT"
                    }
                } else {
                    queued.status ?: remote.listStatus
                }
                mergedById[id] = remote.copy(
                    listStatus = nextStatus,
                    progress = nextProgress,
                    score = queued.score ?: remote.score,
                    updatedAt = maxOf(remote.updatedAt ?: 0L, queued.queuedAt / 1000L)
                )
            }
        }

        if (remoteLists.isEmpty()) {
            return groupMediaIntoLists(mergedById.values.toList())
        }

        val result = LinkedHashMap<String, MutableList<AnimeMedia>>()
        val placedMediaIds = mutableSetOf<Int>()

        for ((listName, items) in remoteLists) {
            val list = mutableListOf<AnimeMedia>()
            for (item in items) {
                if (pendingByMedia[item.mediaId]?.isRemoval == true) continue
                val updated = mergedById[item.mediaId] ?: item
                list.add(updated)
                placedMediaIds.add(item.mediaId)
            }
            result[listName] = list
        }

        val unplaced = mergedById.values.filter { it.mediaId !in placedMediaIds }
        for (item in unplaced) {
            val targetList = when (item.listStatus?.uppercase()) {
                "CURRENT" -> if (result.containsKey("Watching")) "Watching" else result.keys.firstOrNull() ?: "Watching"
                "REPEATING" -> if (result.containsKey("Rewatching")) "Rewatching" else "Watching"
                "PLANNING" -> if (result.containsKey("Planning")) "Planning" else "Watching"
                "PAUSED" -> if (result.containsKey("Paused")) "Paused" else "Watching"
                "DROPPED" -> if (result.containsKey("Dropped")) "Dropped" else "Watching"
                "COMPLETED" -> {
                    val fmt = item.format?.uppercase()
                    val matchingList = when (fmt) {
                        "TV", "TV_SHORT" -> result.keys.firstOrNull { it.contains("TV", ignoreCase = true) && it.contains("Completed", ignoreCase = true) }
                        "MOVIE" -> result.keys.firstOrNull { it.contains("Movie", ignoreCase = true) && it.contains("Completed", ignoreCase = true) }
                        "OVA", "OAD" -> result.keys.firstOrNull { it.contains("OVA", ignoreCase = true) && it.contains("Completed", ignoreCase = true) }
                        "ONA" -> result.keys.firstOrNull { it.contains("ONA", ignoreCase = true) && it.contains("Completed", ignoreCase = true) }
                        "MUSIC" -> result.keys.firstOrNull { it.contains("Music", ignoreCase = true) && it.contains("Completed", ignoreCase = true) }
                        "SPECIAL" -> result.keys.firstOrNull { it.contains("Special", ignoreCase = true) && it.contains("Completed", ignoreCase = true) }
                        else -> null
                    }
                    matchingList ?: result.keys.firstOrNull { it.contains("Completed", ignoreCase = true) } ?: "Completed"
                }
                else -> "Watching"
            }
            result.getOrPut(targetList) { mutableListOf() }.add(item)
        }

        return result
    }

    fun groupMediaIntoLists(entries: List<AnimeMedia>): Map<String, List<AnimeMedia>> {
        val buckets = linkedMapOf(
            "Watching" to mutableListOf<AnimeMedia>(),
            "Rewatching" to mutableListOf<AnimeMedia>(),
            "Planning" to mutableListOf<AnimeMedia>(),
            "Completed" to mutableListOf<AnimeMedia>(),
            "Paused" to mutableListOf<AnimeMedia>(),
            "Dropped" to mutableListOf<AnimeMedia>()
        )
        val sorted = entries.sortedByDescending { it.updatedAt ?: 0L }
        for (item in sorted) {
            val key = when (item.listStatus?.uppercase()) {
                "CURRENT" -> "Watching"
                "REPEATING" -> "Rewatching"
                "PLANNING" -> "Planning"
                "COMPLETED" -> "Completed"
                "PAUSED" -> "Paused"
                "DROPPED" -> "Dropped"
                else -> if (item.progress > 0) "Watching" else null
            } ?: continue
            buckets[key]?.add(item)
        }
        val result = linkedMapOf<String, List<AnimeMedia>>()
        for ((name, list) in buckets) {
            if (name == "Rewatching" && list.isEmpty()) continue
            result[name] = list
        }
        return result
    }

    /**
     * Saves or updates an anime entry locally (works completely offline and for guests without AniList).
     */
    suspend fun saveLocalEntry(
        context: Context,
        media: AnimeMedia,
        status: String?,
        progress: Int?,
        score: Double?
    ): AnimeMedia = withContext(Dispatchers.IO) {
        ensureLoaded(context)
        val nowSec = System.currentTimeMillis() / 1000L
        synchronized(ioLock) {
            val current = _localEntries.value[media.mediaId] ?: media
            val nextProgress = (progress ?: current.progress).coerceIn(0, media.episodes ?: current.episodes ?: 9999)
            val nextStatus = (status ?: current.listStatus ?: "CURRENT").uppercase()
            val nextScore = when {
                score == null -> current.score
                score <= 0.0 -> null
                else -> score
            }
            val preserveRating = !media.hasKnownAdultRating && current.hasKnownAdultRating
            val updated = media.copy(
                listStatus = nextStatus,
                progress = nextProgress,
                score = nextScore,
                updatedAt = nowSec,
                hasEnglishDubCast = media.hasEnglishDubCast || current.hasEnglishDubCast,
                isAdult = if (preserveRating) current.isAdult else media.isAdult,
                genres = media.genres.ifEmpty { current.genres },
                hasKnownAdultRating = media.hasKnownAdultRating || current.hasKnownAdultRating
            )
            val nextMap = LinkedHashMap(_localEntries.value).apply { put(media.mediaId, updated) }
            writeLocalLibrary(libraryFile(context), nextMap)
            _localEntries.value = nextMap
            updated
        }
    }

    internal suspend fun removeLocalEntry(
        context: Context,
        mediaId: Int,
        account: SavedAniListAccount? = null,
        includeUnassigned: Boolean = false
    ) = withContext(Dispatchers.IO) {
        ensureLoaded(context)
        synchronized(ioLock) {
            val nextMap = LinkedHashMap(_localEntries.value).apply { remove(mediaId) }
            writeLocalLibrary(libraryFile(context), nextMap)
            _localEntries.value = nextMap

            val nextQueue = _pendingQueue.value.filterNot {
                it.mediaId == mediaId && account != null && it.belongsTo(account, includeUnassigned)
            }
            if (nextQueue.size != _pendingQueue.value.size) {
                writeQueue(queueFile(context), nextQueue)
                _pendingQueue.value = nextQueue
            }
        }
    }

    /**
     * Marks an episode watched when the configured watch percentage (default 80%) is viewed.
     * - Honors `doNotTrackNsfw` so explicit adult anime is skipped completely when NSFW tracking is disabled.
     * - Updates local tracking immediately (if already tracked OR in guest mode so Continue Watching works!).
     * - If signed into AniList with `reportProgress`, enqueues a durable offline update and flushes immediately if online.
     */
    internal suspend fun recordEpisodeWatchedAt80Percent(
        context: Context,
        media: AnimeMedia,
        episodeNum: Int,
        reportProgress: Boolean,
        accounts: AniListTrackingAccounts
    ): Boolean = withContext(Dispatchers.IO) {
        ensureLoaded(context)
        val isNsfw = isExplicitAdultAnime(media.isAdult, media.genres)
        if (accounts.doNotTrackNsfw && (isNsfw || !media.hasKnownAdultRating)) {
            return@withContext false
        }
        val account = accounts.forTitle(isNsfw)
        val existingLocal = _localEntries.value[media.mediaId]
        val isTracked = media.listStatus != null || existingLocal?.listStatus != null

        // For guests (no AniList), auto-track watched shows in Watching/Completed so Continue Watching works seamlessly!
        // For AniList users, mark watched if tracked (or already in list / auto-sync enabled).
        val shouldMarkLocally = account == null || isTracked || reportProgress
        if (!shouldMarkLocally) return@withContext false

        val currentProgress = maxOf(media.progress, existingLocal?.progress ?: 0)
        val totalEps = media.episodes ?: existingLocal?.episodes
        val nextProgress = maxOf(currentProgress, episodeNum)
        val priorStatus = media.listStatus ?: existingLocal?.listStatus
        val nextStatus = when {
            priorStatus in setOf("COMPLETED", "REPEATING") -> priorStatus
            totalEps != null && totalEps > 0 && nextProgress >= totalEps -> "COMPLETED"
            else -> "CURRENT"
        }

        saveLocalEntry(
            context = context,
            media = media,
            status = nextStatus,
            progress = nextProgress,
            score = media.score ?: existingLocal?.score
        )

        if (account != null && reportProgress) {
            enqueueAniListUpdate(
                context = context,
                update = PendingAniListUpdate(
                    mediaId = media.mediaId,
                    title = media.title,
                    status = nextStatus,
                    progress = episodeNum,
                    score = null,
                    totalEpisodes = totalEps,
                    isWatchedAutoBump = true,
                    accountUsername = account.username,
                    accountId = account.id.takeIf { it > 0 }
                )
            )
            flushPendingQueue(context, accounts)
        }
        true
    }

    suspend fun enqueueAniListUpdate(context: Context, update: PendingAniListUpdate) = withContext(Dispatchers.IO) {
        ensureLoaded(context)
        synchronized(ioLock) {
            val current = _pendingQueue.value.toMutableList()
            val idx = current.indexOfFirst {
                it.mediaId == update.mediaId && it.sameDestination(update)
            }
            if (idx >= 0) {
                val prev = current[idx]
                val merged = if (update.isRemoval || prev.isRemoval) {
                    if (prev.isRemoval && update.isWatchedAutoBump) prev else update
                } else if (update.isWatchedAutoBump && !prev.isWatchedAutoBump) {
                    prev.copy(
                        progress = maxOf(prev.progress ?: 0, update.progress ?: 0),
                        queuedAt = update.queuedAt,
                        accountId = update.accountId ?: prev.accountId
                    )
                } else {
                    update.copy(
                        progress = if (update.isWatchedAutoBump) maxOf(prev.progress ?: 0, update.progress ?: 0) else update.progress,
                        accountId = update.accountId ?: prev.accountId,
                        score = update.score ?: prev.score
                    )
                }
                current[idx] = merged
            } else {
                current.add(update)
            }
            writeQueue(queueFile(context), current)
            _pendingQueue.value = current
        }
    }

    /**
     * Flushes all queued AniList updates in order. Returns the number of updates synced during this pass.
     */
    internal suspend fun flushPendingQueue(
        context: Context,
        accounts: AniListTrackingAccounts
    ): Int = withContext(Dispatchers.IO) {
        if (accounts.active == null || !isNetworkAvailable(context)) return@withContext 0
        ensureLoaded(context)
        flushMutex.withLock {
            val snapshot = _pendingQueue.value.mapNotNull { update ->
                accounts.forUpdate(update)?.let { update to it }
            }
            if (snapshot.isEmpty()) return@withLock 0
            _isSyncing.value = true
            var syncedCount = 0
            val failedAccounts = mutableSetOf<String>()
            try {
                for ((item, account) in snapshot) {
                    if (!isNetworkAvailable(context)) break
                    if (account.token in failedAccounts) continue
                    val anilistToken = account.token
                    val scoreFormat = account.scoreFormat
                    try {
                        if (item.isRemoval) {
                            val entryId = AniListClient.getMediaDetail(item.mediaId, anilistToken).listEntryId
                            if (entryId != null) AniListClient.deleteEntry(entryId, anilistToken)
                        } else if (item.isWatchedAutoBump) {
                            val remote = AniListClient.getMediaDetail(item.mediaId, anilistToken)
                            // Only sync watched auto-bumps if the anime is tracked on AniList or user started watching it!
                            val targetEp = item.progress ?: 0
                            if (remote.listStatus != null && targetEp > remote.progress &&
                                remote.listStatus !in setOf("COMPLETED", "REPEATING")
                            ) {
                                val statusToSave = if (remote.episodes != null && targetEp >= remote.episodes) {
                                    "COMPLETED"
                                } else {
                                    "CURRENT"
                                }
                                val saved = AniListClient.saveEntry(
                                    mediaId = remote.mediaId,
                                    status = statusToSave,
                                    progress = targetEp,
                                    score = null,
                                    notes = null,
                                    isPrivate = null,
                                    token = anilistToken,
                                    scoreFormat = scoreFormat
                                )
                                saveLocalEntry(context, saved, saved.listStatus, saved.progress, saved.score)
                            } else if (remote.listStatus == null) {
                                // Also support auto-adding watched anime when reportProgress is enabled
                                val total = remote.episodes ?: item.totalEpisodes
                                val statusToSave = if (total != null && total > 0 && targetEp >= total) "COMPLETED" else "CURRENT"
                                val saved = AniListClient.saveEntry(
                                    mediaId = item.mediaId,
                                    status = statusToSave,
                                    progress = targetEp,
                                    score = null,
                                    notes = null,
                                    isPrivate = null,
                                    token = anilistToken,
                                    scoreFormat = scoreFormat
                                )
                                saveLocalEntry(context, saved, saved.listStatus, saved.progress, saved.score)
                            }
                        } else {
                            val saved = AniListClient.saveEntry(
                                mediaId = item.mediaId,
                                status = item.status,
                                progress = item.progress,
                                score = item.score,
                                notes = null,
                                isPrivate = null,
                                token = anilistToken,
                                scoreFormat = scoreFormat
                            )
                            saveLocalEntry(context, saved, saved.listStatus, saved.progress, saved.score)
                        }

                        synchronized(ioLock) {
                            val remaining = _pendingQueue.value.filterNot { it == item }
                            writeQueue(queueFile(context), remaining)
                            _pendingQueue.value = remaining
                        }
                        syncedCount++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("LocalLibraryStore", "Deferred AniList sync for ${item.mediaId}: ${e.message}")
                        failedAccounts.add(account.token)
                    }
                }
            } finally {
                _isSyncing.value = false
            }
            syncedCount
        }
    }

    fun isNetworkAvailable(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            true
        }
    }

    fun observeNetworkOnline(context: Context): Flow<Boolean> = callbackFlow {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            trySend(true)
            close()
            return@callbackFlow
        }
        trySend(isNetworkAvailable(context))
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(true)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    trySend(true)
                }
            }

            override fun onLost(network: Network) {
                trySend(isNetworkAvailable(context))
            }
        }
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { cm.registerNetworkCallback(req, callback) }
        awaitClose {
            runCatching { cm.unregisterNetworkCallback(callback) }
        }
    }.distinctUntilChanged()

    private fun readLocalLibrary(file: AtomicFile): Map<Int, AnimeMedia> {
        return try {
            val raw = file.readFully().toString(Charsets.UTF_8)
            parseLocalLibraryJson(raw)
        } catch (_: FileNotFoundException) {
            emptyMap()
        } catch (e: Exception) {
            Log.w("LocalLibraryStore", "Could not read local anime library", e)
            emptyMap()
        }
    }

    private fun writeLocalLibrary(file: AtomicFile, entries: Map<Int, AnimeMedia>) {
        val arr = JSONArray()
        for (m in entries.values) {
            arr.put(JSONObject().apply {
                put("mediaId", m.mediaId)
                put("title", m.title)
                put("titleEnglish", m.titleEnglish ?: "")
                put("titleRomaji", m.titleRomaji ?: "")
                put("titleNative", m.titleNative ?: "")
                put("coverUrl", m.coverUrl ?: "")
                put("bannerUrl", m.bannerUrl ?: "")
                put("episodes", m.episodes ?: 0)
                put("format", m.format ?: "TV")
                put("status", m.status ?: "")
                put("averageScore", m.averageScore ?: 0)
                put("listStatus", m.listStatus ?: "CURRENT")
                put("progress", m.progress)
                if (m.score != null) put("score", m.score)
                put("updatedAt", m.updatedAt ?: (System.currentTimeMillis() / 1000L))
                put("hasEnglishDubCast", m.hasEnglishDubCast)
                if (m.hasKnownAdultRating) put("isAdult", m.isAdult)
                put("genres", JSONArray(m.genres))
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

    internal fun parseLocalLibraryJson(raw: String): Map<Int, AnimeMedia> {
        val arr = JSONArray(raw)
        val map = LinkedHashMap<Int, AnimeMedia>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optInt("mediaId")
            if (id <= 0) continue
            val title = o.optString("title", "Anime")
            map[id] = AnimeMedia(
                mediaId = id,
                title = title,
                titleEnglish = o.optString("titleEnglish").takeIf { it.isNotBlank() },
                titleRomaji = o.optString("titleRomaji").takeIf { it.isNotBlank() },
                titleNative = o.optString("titleNative").takeIf { it.isNotBlank() },
                coverUrl = o.optString("coverUrl").takeIf { it.isNotBlank() },
                bannerUrl = o.optString("bannerUrl").takeIf { it.isNotBlank() },
                accentColor = null,
                episodes = o.optInt("episodes").takeIf { it > 0 },
                format = o.optString("format").takeIf { it.isNotBlank() },
                status = o.optString("status").takeIf { it.isNotBlank() },
                season = null,
                seasonYear = null,
                averageScore = o.optInt("averageScore").takeIf { it > 0 },
                genres = o.optJSONArray("genres")?.let { genres ->
                    (0 until genres.length()).mapNotNull { genres.optString(it).takeIf(String::isNotBlank) }
                }.orEmpty(),
                description = null,
                siteUrl = null,
                isAdult = o.optBoolean("isAdult", false),
                nextEpisode = null,
                nextEpisodeAt = null,
                externalLinks = emptyList(),
                listEntryId = null,
                listStatus = o.optString("listStatus", "CURRENT").takeIf { it.isNotBlank() },
                progress = o.optInt("progress", 0),
                score = if (o.has("score") && !o.isNull("score")) o.optDouble("score").takeIf { it > 0 } else null,
                notes = null,
                isPrivate = false,
                updatedAt = o.optLong("updatedAt").takeIf { it > 0 },
                hasEnglishDubCast = o.optBoolean("hasEnglishDubCast", false),
                hasKnownAdultRating = o.has("isAdult")
            )
        }
        return map
    }

    private fun readQueue(file: AtomicFile): List<PendingAniListUpdate> {
        return try {
            val raw = file.readFully().toString(Charsets.UTF_8)
            parseQueueJson(raw)
        } catch (_: FileNotFoundException) {
            emptyList()
        } catch (e: Exception) {
            Log.w("LocalLibraryStore", "Could not read pending sync queue", e)
            emptyList()
        }
    }

    private fun writeQueue(file: AtomicFile, queue: List<PendingAniListUpdate>) {
        val arr = JSONArray()
        for (u in queue) {
            arr.put(JSONObject().apply {
                put("mediaId", u.mediaId)
                put("title", u.title)
                if (u.status != null) put("status", u.status)
                if (u.progress != null) put("progress", u.progress)
                if (u.score != null) put("score", u.score)
                if (u.totalEpisodes != null) put("totalEpisodes", u.totalEpisodes)
                put("isWatchedAutoBump", u.isWatchedAutoBump)
                put("accountUsername", u.accountUsername)
                put("queuedAt", u.queuedAt)
                if (u.accountId != null) put("accountId", u.accountId)
                if (u.isRemoval) put("isRemoval", true)
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

    internal fun parseQueueJson(raw: String): List<PendingAniListUpdate> {
        val arr = JSONArray(raw)
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optInt("mediaId")
                if (id <= 0) continue
                add(
                    PendingAniListUpdate(
                        mediaId = id,
                        title = o.optString("title", "Anime"),
                        status = o.optString("status").takeIf { it.isNotBlank() },
                        progress = if (o.has("progress") && !o.isNull("progress")) o.optInt("progress") else null,
                        score = if (o.has("score") && !o.isNull("score")) o.optDouble("score") else null,
                        totalEpisodes = if (o.has("totalEpisodes") && !o.isNull("totalEpisodes")) o.optInt("totalEpisodes") else null,
                        isWatchedAutoBump = o.optBoolean("isWatchedAutoBump", false),
                        accountUsername = o.optString("accountUsername", ""),
                        queuedAt = o.optLong("queuedAt", 0L),
                        accountId = o.optInt("accountId").takeIf { it > 0 },
                        isRemoval = o.optBoolean("isRemoval", false)
                    )
                )
            }
        }
    }
}
