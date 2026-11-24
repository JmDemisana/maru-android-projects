package io.maru.manime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal fun aniListVariables(variables: Map<String, Any?>): JSONObject = JSONObject().apply {
    for ((key, value) in variables) if (value != null) put(key, JSONObject.wrap(value))
}

data class AniListUser(
    val id: Int,
    val name: String,
    val avatarUrl: String?,
    val scoreFormat: String = "POINT_100" // POINT_100 | POINT_10_DECIMAL | POINT_10 | POINT_5 | POINT_3
)

data class AniListProfile(
    val id: Int,
    val name: String,
    val avatar: String?,
    val banner: String?,
    val about: String?,
    val animeCount: Int,
    val minutesWatched: Int,
    val meanScore: Double,
    val isFollowing: Boolean = false,
    val userLists: Map<String, List<AnimeMedia>> = emptyMap()
)

data class AnimeMedia(
    val mediaId: Int,
    val title: String,
    val titleEnglish: String?,
    val titleRomaji: String?,
    val coverUrl: String?,
    val bannerUrl: String?,
    val accentColor: String?,
    val episodes: Int?,
    val format: String?,
    val status: String?,
    val season: String?,
    val seasonYear: Int?,
    val averageScore: Int?,
    val genres: List<String>,
    val description: String?,
    val siteUrl: String?,
    val isAdult: Boolean,
    val nextEpisode: Int?,
    val nextEpisodeAt: Long?,
    val externalLinks: List<ExternalLink>,
    val listEntryId: Int?,
    val listStatus: String?,
    val progress: Int,
    val score: Double?,
    val notes: String?,
    val isPrivate: Boolean,
    val updatedAt: Long?,
    val hasEnglishDubCast: Boolean = false,
    val relationType: String? = null,
    val tags: List<MediaTag> = emptyList(),
    val trailerYoutubeId: String? = null,
    val titleNative: String? = null
) {
    val candidateTitlesForStream: List<String>
        get() = buildList {
            titleRomaji?.takeIf { it.isNotBlank() }?.let { add(it) }
            titleNative?.takeIf { it.isNotBlank() }?.let { add(it) }
            titleEnglish?.takeIf { it.isNotBlank() }?.let { add(it) }
            if (title.isNotBlank()) add(title)

            val snapshot = toList()
            for (t in snapshot) {
                val cleaned = cleanAnimeTitle(t)
                if (cleaned.isNotBlank() && cleaned != t) {
                    add(cleaned)
                }
            }
        }.distinct()

    val isReleasing: Boolean get() = status.equals("RELEASING", ignoreCase = true)

    val hasUncertainReleaseCount: Boolean get() = !status.equals("FINISHED", ignoreCase = true)

    val hasEnglishDub: Boolean get() = hasEnglishDubCast || KnownDubRegistry.isKnownDub(mediaId) || externalLinks.any { it.isEnglishDub }

    val contentAdvisory: ContentAdvisory get() = deriveContentAdvisory(isAdult, genres, tags)

    val isMusicOrConcert: Boolean
        get() = format.equals("MUSIC", ignoreCase = true) ||
            tags.any {
                it.name.contains("concert", true) ||
                    it.name.contains("band", true) ||
                    (it.name.contains("idol", true) && format.equals("SPECIAL", true))
            } ||
            title.contains(" Live ", ignoreCase = true) ||
            title.contains("Concert", ignoreCase = true) ||
            title.endsWith(" Live", ignoreCase = true)

    val isStandaloneFeature: Boolean
        get() {
            val fmt = format?.uppercase().orEmpty()
            return fmt == "MOVIE" || fmt == "MUSIC" ||
                (fmt in setOf("SPECIAL", "OVA", "ONA") && (episodes ?: 1) <= 1)
        }

    val latestAiredEpisode: Int? get() {
        return latestReleasedEpisode(status, episodes, nextEpisode)
    }
}

data class ExternalLink(
    val id: Int,
    val url: String,
    val site: String,
    val type: String,
    val language: String?
) {
    /**
     * Note: AniList sets `language = "English"` on English-region streaming sites (e.g. Crunchyroll, HIDIVE)
     * even for sub-only simulcasts. Never treat `language == "English"` alone as proof of an English dub!
     */
    val isEnglishDub: Boolean get() {
        if (!type.equals("STREAMING", ignoreCase = true)) return false
        val isExplicitEnglishSite = site.contains("English", ignoreCase = true) && site.contains("Dub", ignoreCase = true)
        val isExplicitEnglishUrl = (url.contains("english", ignoreCase = true) && url.contains("dub", ignoreCase = true)) ||
                                   url.contains("/en-dub", ignoreCase = true) ||
                                   url.contains("en_dub", ignoreCase = true)
        return isExplicitEnglishSite || isExplicitEnglishUrl
    }
}

internal fun cleanAnimeTitle(raw: String): String {
    var s = raw.trim()
    s = s.replace(Regex("(?i)\\s*\\((?:tv|ova|oad|ona|movie|special|specials|bd|preview|raw)\\)"), "")
    s = s.replace(Regex("(?i)\\s*:\\s*seasons?\\s*\\d+(?:-\\d+)?"), "")
    s = s.replace(Regex("(?i)\\s+seasons?\\s*\\d+(?:-\\d+)?"), "")
    s = s.replace(Regex("(?i)\\s+\\d+(?:st|nd|rd|th)\\s+season"), "")
    s = s.replace(Regex("[\\s!?.:~-]+$"), "")
    return s.trim()
}

data class VoiceActor(
    val id: Int,
    val name: String,
    val image: String?,
    val language: String
)

data class CastMember(
    val characterId: Int,
    val characterName: String,
    val characterNameNative: String?,
    val characterImage: String?,
    val role: String?,
    val japaneseVa: VoiceActor?,
    val englishVa: VoiceActor?
)

data class StaffWorks(
    val staffId: Int,
    val name: String,
    val nameNative: String?,
    val image: String?,
    val language: String?,
    val works: List<AnimeMedia>
)

data class FriendAnimeStatus(
    val id: Int,
    val userId: Int,
    val userName: String,
    val userAvatar: String?,
    val status: String,
    val progress: String?,
    val createdAt: Long
)

data class AniListActivity(
    val id: Int,
    val userId: Int,
    val userName: String,
    val userAvatar: String?,
    val type: String,
    val status: String?,
    val progress: String?,
    val text: String?,
    val mediaTitle: String?,
    val mediaCover: String?,
    val rawMedia: AnimeMedia?,
    val replyCount: Int,
    val likeCount: Int,
    val isLiked: Boolean = false,
    val createdAt: Long
)

data class ActivityReply(
    val id: Int,
    val userId: Int,
    val userName: String,
    val userAvatar: String?,
    val text: String,
    val likeCount: Int,
    val createdAt: Long
)

data class StreamingEpisode(
    val episodeNumber: Int,
    val title: String?,
    val thumbnail: String?,
    val site: String?,
    val url: String?
)

data class SearchPage(
    val results: List<AnimeMedia>,
    val hasNextPage: Boolean,
    val total: Int?
)

object AniListClient {
    private const val ENDPOINT = "https://graphql.anilist.co"
    const val MASTER_CLIENT_ID = "52524"
    const val REDIRECT_URI = "https://maruchan.pages.dev/anilist-integration"
    private const val TOKEN_EXCHANGE_URL = "https://maruchan.pages.dev/api/anilist-token"
    private const val TOKEN_EXCHANGE_FALLBACK_URL = "https://maru-api-wine.vercel.app/api/anilist-token"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private val dubCastCache = ConcurrentHashMap<Int, Boolean>()

    suspend fun exchangeAuthCode(code: String): String? {
        val payload = JSONObject().apply {
            put("code", code)
            put("clientId", MASTER_CLIENT_ID)
            put("redirectUri", REDIRECT_URI)
        }.toString().toRequestBody(JSON_MEDIA_TYPE)

        for (endpoint in listOf(TOKEN_EXCHANGE_URL, TOKEN_EXCHANGE_FALLBACK_URL)) {
            try {
                val req = Request.Builder()
                    .url(endpoint)
                    .post(payload)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .build()
                val token = client.newCall(req).readResponse { response ->
                    if (response.isSuccessful) {
                        response.body?.string()?.let { JSONObject(it).optNullableString("access_token") }
                    } else null
                }
                if (token != null) return token
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        }
        return null
    }

    fun JSONObject?.optNullableString(name: String): String? {
        if (this == null || isNull(name)) return null
        val s = optString(name, "").trim()
        return if (s.isEmpty() || s.equals("null", ignoreCase = true)) null else s
    }

    private suspend fun query(
        gql: String,
        variables: Map<String, Any?> = emptyMap(),
        token: String? = null
    ): JSONObject {
        val varsJson = aniListVariables(variables)
        val body = JSONObject().apply {
            put("query", gql)
            put("variables", varsJson)
        }.toString().toRequestBody(JSON_MEDIA_TYPE)

        val reqBuilder = Request.Builder()
            .url(ENDPOINT)
            .post(body)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
        if (!token.isNullOrBlank()) reqBuilder.header("Authorization", "Bearer $token")

        val payload = client.newCall(reqBuilder.build()).readResponse { response ->
            val raw = response.body?.string()
            if (!response.isSuccessful) {
                val error = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
                    ?.optJSONArray("errors")?.optJSONObject(0)?.optNullableString("message")
                throw IllegalStateException(error ?: "AniList HTTP ${response.code}")
            }
            raw ?: throw IllegalStateException("AniList returned an empty response")
        }
        val json = JSONObject(payload)
        val error = json.optJSONArray("errors")?.optJSONObject(0)?.optNullableString("message")
        if (error != null) throw IllegalStateException(error)
        return json.getJSONObject("data")
    }

    fun isMangaOrNovel(obj: JSONObject): Boolean {
        val type = obj.optNullableString("type")
        val format = obj.optNullableString("format")
        return type.equals("MANGA", ignoreCase = true) ||
               format.equals("MANGA", ignoreCase = true) ||
               format.equals("NOVEL", ignoreCase = true) ||
               format.equals("ONE_SHOT", ignoreCase = true)
    }

    fun mapMedia(obj: JSONObject, listEntry: JSONObject? = null): AnimeMedia {
        val title = obj.optJSONObject("title")
        val cover = obj.optJSONObject("coverImage")
        val nextAir = obj.optJSONObject("nextAiringEpisode")
        val le = listEntry ?: obj.optJSONObject("mediaListEntry")

        val links = mutableListOf<ExternalLink>()
        val rawLinks = obj.optJSONArray("externalLinks")
        if (rawLinks != null) for (i in 0 until rawLinks.length()) {
            val l = rawLinks.optJSONObject(i) ?: continue
            links += ExternalLink(
                id = l.optInt("id"),
                url = l.optString("url", ""),
                site = l.optString("site", ""),
                type = l.optString("type", ""),
                language = l.optNullableString("language")
            )
        }

        val genres = mutableListOf<String>()
        val rawGenres = obj.optJSONArray("genres")
        if (rawGenres != null) for (i in 0 until rawGenres.length()) {
            val g = rawGenres.optString(i, "").trim()
            if (g.isNotEmpty() && !g.equals("null", ignoreCase = true)) genres += g
        }

        val mediaTags = mutableListOf<MediaTag>()
        val rawTags = obj.optJSONArray("tags")
        if (rawTags != null) for (i in 0 until rawTags.length()) {
            val t = rawTags.optJSONObject(i) ?: continue
            val tName = t.optString("name", "").trim()
            if (tName.isNotEmpty() && !tName.equals("null", ignoreCase = true)) {
                mediaTags += MediaTag(
                    name = tName,
                    rank = t.optInt("rank", 0),
                    isAdult = t.optBoolean("isAdult", false),
                    isMediaSpoiler = t.optBoolean("isMediaSpoiler", false)
                )
            }
        }

        val titleEng = title?.optNullableString("english")
        val titleRom = title?.optNullableString("romaji")
        val titlePref = title?.optNullableString("userPreferred")
        val titleNative = title?.optNullableString("native")

        val displayTitle = titleEng ?: titleRom ?: titlePref ?: titleNative ?: "Untitled"

        val mediaId = obj.getInt("id")
        var hasEngVa = false
        val charactersObj = obj.optJSONObject("characters")
        val charEdges = charactersObj?.optJSONArray("edges")
        if (charEdges != null) {
            for (i in 0 until charEdges.length()) {
                val vas = charEdges.optJSONObject(i)?.optJSONArray("voiceActors")
                if (vas != null && vas.length() > 0) {
                    hasEngVa = true
                    break
                }
            }
            dubCastCache[mediaId] = hasEngVa
            if (hasEngVa) KnownDubRegistry.recordKnownDub(mediaId)
        } else {
            hasEngVa = dubCastCache[mediaId] ?: KnownDubRegistry.isKnownDub(mediaId)
        }

        val trailerObj = obj.optJSONObject("trailer")
        val trailerYtId = if (trailerObj?.optString("site", "")?.equals("youtube", ignoreCase = true) == true) {
            trailerObj.optNullableString("id")
        } else null

        return AnimeMedia(
            mediaId       = mediaId,
            title         = displayTitle,
            titleEnglish  = titleEng,
            titleRomaji   = titleRom,
            titleNative   = titleNative,
            coverUrl      = cover?.optNullableString("large") ?: cover?.optNullableString("medium"),
            bannerUrl     = obj.optNullableString("bannerImage"),
            accentColor   = cover?.optNullableString("color"),
            episodes      = obj.optInt("episodes").takeIf { it > 0 },
            format        = obj.optNullableString("format"),
            status        = obj.optNullableString("status"),
            season        = obj.optNullableString("season"),
            seasonYear    = obj.optInt("seasonYear").takeIf { it > 0 },
            averageScore  = obj.optInt("averageScore").takeIf { it > 0 },
            genres        = genres,
            description   = obj.optNullableString("description"),
            siteUrl       = obj.optNullableString("siteUrl"),
            isAdult       = obj.optBoolean("isAdult"),
            nextEpisode   = nextAir?.optInt("episode")?.takeIf { it > 0 },
            nextEpisodeAt = nextAir?.optLong("airingAt")?.takeIf { it > 0 },
            externalLinks = links,
            listEntryId   = le?.optInt("id")?.takeIf { it > 0 },
            listStatus    = le?.optNullableString("status"),
            progress      = le?.optInt("progress") ?: 0,
            score         = le?.optDouble("score")?.takeIf { it > 0 },
            notes         = le?.optNullableString("notes"),
            isPrivate     = le?.optBoolean("private") ?: false,
            updatedAt     = le?.optLong("updatedAt")?.takeIf { it > 0 },
            hasEnglishDubCast = hasEngVa,
            tags          = mediaTags,
            trailerYoutubeId = trailerYtId
        )
    }

    private fun mediaFields(includeDubCast: Boolean = true) = """
        id episodes format status season seasonYear meanScore averageScore isAdult siteUrl bannerImage
        title { userPreferred english romaji native }
        coverImage { large color }
        nextAiringEpisode { episode airingAt }
        externalLinks { id url site type language }
        trailer { id site }
        ${if (includeDubCast) "characters(perPage: 15, sort: [ROLE, RELEVANCE, ID]) { edges { voiceActors(language: ENGLISH) { id } } }" else ""}
        genres
        tags { name rank isAdult isMediaSpoiler }
        description(asHtml: false)
        mediaListEntry { id status score progress private notes updatedAt }
    """.trimIndent()

    private suspend fun hydrateDubCastMap(mediaIds: Collection<Int>): Map<Int, Boolean> = coroutineScope {
        val uncached = mediaIds.filter { it > 0 && !dubCastCache.containsKey(it) }.distinct()
        if (uncached.isNotEmpty()) {
            uncached.chunked(50).take(6).map { chunk ->
                async {
                    try {
                        val data = query(
                            """
                            query (${'$'}ids: [Int]) {
                              Page(page: 1, perPage: 50) {
                                media(id_in: ${'$'}ids, type: ANIME) {
                                  id
                                  characters(perPage: 15, sort: [ROLE, RELEVANCE, ID]) {
                                    edges { voiceActors(language: ENGLISH) { id } }
                                  }
                                }
                              }
                            }
                            """.trimIndent(),
                            mapOf("ids" to chunk)
                        )
                        val arr = data.optJSONObject("Page")?.optJSONArray("media") ?: return@async
                        for (i in 0 until arr.length()) {
                            val m = arr.optJSONObject(i) ?: continue
                            val id = m.optInt("id")
                            if (id <= 0) continue
                            val edges = m.optJSONObject("characters")?.optJSONArray("edges")
                            var found = false
                            if (edges != null) {
                                for (j in 0 until edges.length()) {
                                    val vas = edges.optJSONObject(j)?.optJSONArray("voiceActors")
                                    if (vas != null && vas.length() > 0) {
                                        found = true
                                        break
                                    }
                                }
                            }
                            dubCastCache[id] = found
                            if (found) KnownDubRegistry.recordKnownDub(id)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {}
                }
            }.awaitAll()
        }
        dubCastCache
    }

    private suspend fun hydrateDubCast(items: List<AnimeMedia>): List<AnimeMedia> {
        if (items.isEmpty()) return items
        val map = hydrateDubCastMap(items.map { it.mediaId })
        return items.map { item ->
            val cached = map[item.mediaId]
            if (cached != null && cached != item.hasEnglishDubCast) item.copy(hasEnglishDubCast = cached) else item
        }
    }

    suspend fun getViewer(token: String): AniListUser {
        val data = query("""
            query {
              Viewer {
                id name
                avatar { medium }
                mediaListOptions { scoreFormat }
              }
            }
        """, token = token)
        val v = data.getJSONObject("Viewer")
        val scoreFmt = v.optJSONObject("mediaListOptions")?.optNullableString("scoreFormat") ?: "POINT_100"
        return AniListUser(
            id          = v.getInt("id"),
            name        = v.getString("name"),
            avatarUrl   = v.optJSONObject("avatar")?.optNullableString("medium"),
            scoreFormat = scoreFmt
        )
    }

    fun parseUserListJson(data: JSONObject): Map<String, List<AnimeMedia>> {
        val rawBuckets = linkedMapOf<String, MutableList<AnimeMedia>>()
        val customListNames = mutableSetOf<String>()
        val collection = data.optJSONObject("MediaListCollection") ?: return emptyMap()
        val lists = collection.optJSONArray("lists") ?: return emptyMap()
        val listOrder = mutableListOf<String>()

        for (i in 0 until lists.length()) {
            val list = lists.optJSONObject(i) ?: continue
            val status = list.optNullableString("status")?.uppercase()
            val rawName = list.optString("name", "List").trim().ifEmpty { "List" }
            val canonicalName = when {
                rawName.equals("Current", ignoreCase = true) || (status == "CURRENT" && rawName.equals("Watching", ignoreCase = true)) -> "Watching"
                rawName.equals("Repeating", ignoreCase = true) || (status == "REPEATING" && rawName.equals("Rewatching", ignoreCase = true)) -> "Rewatching"
                rawName.equals("Planning", ignoreCase = true) || (status == "PLANNING" && rawName.equals("Planning", ignoreCase = true)) -> "Planning"
                rawName.equals("Completed", ignoreCase = true) || (status == "COMPLETED" && rawName.equals("Completed", ignoreCase = true)) -> "Completed"
                rawName.equals("Paused", ignoreCase = true) || (status == "PAUSED" && rawName.equals("Paused", ignoreCase = true)) -> "Paused"
                rawName.equals("Dropped", ignoreCase = true) || (status == "DROPPED" && rawName.equals("Dropped", ignoreCase = true)) -> "Dropped"
                else -> {
                    // Split completed lists (e.g. "Completed TV", "Completed Movie") or custom lists
                    customListNames += rawName
                    rawName
                }
            }
            if (canonicalName !in listOrder) {
                listOrder += canonicalName
            }
            val entries = list.optJSONArray("entries") ?: continue
            val bucket = rawBuckets.getOrPut(canonicalName) { mutableListOf() }
            val seenInBucket = bucket.mapTo(mutableSetOf()) { it.mediaId }
            for (j in 0 until entries.length()) {
                val entry = entries.optJSONObject(j) ?: continue
                val media = entry.optJSONObject("media") ?: continue
                val mId = media.optInt("id")
                if (mId > 0 && !isMangaOrNovel(media) && seenInBucket.add(mId)) {
                    bucket += mapMedia(media, entry)
                }
            }
        }
        val ordered = linkedMapOf<String, List<AnimeMedia>>()
        if ("Watching" in listOrder && rawBuckets.containsKey("Watching")) {
            rawBuckets.remove("Watching")?.let { ordered["Watching"] = it }
        }
        for (name in listOrder) {
            val items = rawBuckets.remove(name) ?: continue
            if (items.isNotEmpty() || name in setOf("Planning", "Paused", "Dropped") || name in customListNames) {
                ordered[name] = items
            }
        }
        for ((name, items) in rawBuckets) {
            if (items.isNotEmpty() || name in customListNames) {
                ordered[name] = items
            }
        }
        return ordered
    }

    fun parseUserListJsonString(jsonStr: String): Map<String, List<AnimeMedia>> {
        return try {
            parseUserListJson(JSONObject(jsonStr))
        } catch (_: Exception) {
            emptyMap()
        }
    }

    suspend fun getUserListRawJson(username: String, token: String? = null): Pair<String, Map<String, List<AnimeMedia>>> {
        val data = query("""
            query (${'$'}name: String) {
              MediaListCollection(userName: ${'$'}name, type: ANIME) {
                lists {
                  name status isCustomList
                  entries {
                    id status score progress private notes updatedAt
                    media { ${mediaFields(includeDubCast = false)} }
                  }
                }
              }
            }
        """, mapOf("name" to username), token)
        val parsed = parseUserListJson(data)
        val allIds = parsed.values.asSequence().flatten().map { it.mediaId }.distinct().toList()
        val dubMap = hydrateDubCastMap(allIds)
        val hydrated = LinkedHashMap<String, List<AnimeMedia>>(parsed.size)
        for ((k, list) in parsed) {
            hydrated[k] = list.map { m ->
                val hasDub = dubMap[m.mediaId]
                if (hasDub != null && hasDub != m.hasEnglishDubCast) m.copy(hasEnglishDubCast = hasDub) else m
            }
        }
        return Pair(data.toString(), hydrated)
    }

    suspend fun getUserList(username: String, token: String? = null): Map<String, List<AnimeMedia>> {
        return getUserListRawJson(username, token).second
    }

    suspend fun getUserProfile(username: String, token: String? = null): AniListProfile? {
        return try {
            val data = query("""
                query (${'$'}name: String) {
                  User(name: ${'$'}name) {
                    id name bannerImage about(asHtml: false)
                    avatar { large medium }
                    isFollowing
                    statistics {
                      anime {
                        count
                        minutesWatched
                        meanScore
                      }
                    }
                  }
                }
            """, mapOf("name" to username), token)

            val u = data.optJSONObject("User") ?: return null
            val stats = u.optJSONObject("statistics")?.optJSONObject("anime")
            val avatarObj = u.optJSONObject("avatar")

            val lists = try {
                getUserList(username, token)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyMap()
            }

            AniListProfile(
                id = u.getInt("id"),
                name = u.getString("name"),
                avatar = avatarObj?.optNullableString("large") ?: avatarObj?.optNullableString("medium"),
                banner = u.optNullableString("bannerImage"),
                about = u.optNullableString("about"),
                animeCount = stats?.optInt("count") ?: 0,
                minutesWatched = stats?.optInt("minutesWatched") ?: 0,
                meanScore = stats?.optDouble("meanScore") ?: 0.0,
                isFollowing = u.optBoolean("isFollowing"),
                userLists = lists
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    suspend fun searchUsers(queryStr: String, page: Int = 1, token: String? = null): List<AniListProfile> {
        val data = query("""
            query (${'$'}search: String, ${'$'}page: Int) {
              Page(page: ${'$'}page, perPage: 20) {
                users(search: ${'$'}search) {
                  id name bannerImage
                  avatar { large medium }
                  isFollowing
                  statistics {
                    anime { count minutesWatched meanScore }
                  }
                }
              }
            }
        """, mapOf("search" to queryStr, "page" to page), token)

        val arr = data.optJSONObject("Page")?.optJSONArray("users") ?: return emptyList()
        val list = mutableListOf<AniListProfile>()
        for (i in 0 until arr.length()) {
            val u = arr.getJSONObject(i)
            val stats = u.optJSONObject("statistics")?.optJSONObject("anime")
            val avatarObj = u.optJSONObject("avatar")
            list.add(
                AniListProfile(
                    id = u.getInt("id"),
                    name = u.getString("name"),
                    avatar = avatarObj?.optNullableString("large") ?: avatarObj?.optNullableString("medium"),
                    banner = u.optNullableString("bannerImage"),
                    about = null,
                    animeCount = stats?.optInt("count") ?: 0,
                    minutesWatched = stats?.optInt("minutesWatched") ?: 0,
                    meanScore = stats?.optDouble("meanScore") ?: 0.0,
                    isFollowing = u.optBoolean("isFollowing")
                )
            )
        }
        return list
    }

    suspend fun getUserFollowers(userId: Int, page: Int = 1, token: String? = null): List<AniListProfile> {
        val data = query("""
            query (${'$'}userId: Int, ${'$'}page: Int) {
              Page(page: ${'$'}page, perPage: 25) {
                followers(userId: ${'$'}userId) {
                  id name bannerImage
                  avatar { large medium }
                  isFollowing
                  statistics { anime { count minutesWatched meanScore } }
                }
              }
            }
        """, mapOf("userId" to userId, "page" to page), token)

        val arr = data.optJSONObject("Page")?.optJSONArray("followers") ?: return emptyList()
        val list = mutableListOf<AniListProfile>()
        for (i in 0 until arr.length()) {
            val u = arr.getJSONObject(i)
            val stats = u.optJSONObject("statistics")?.optJSONObject("anime")
            val avatarObj = u.optJSONObject("avatar")
            list.add(
                AniListProfile(
                    id = u.getInt("id"),
                    name = u.getString("name"),
                    avatar = avatarObj?.optNullableString("large") ?: avatarObj?.optNullableString("medium"),
                    banner = u.optNullableString("bannerImage"),
                    about = null,
                    animeCount = stats?.optInt("count") ?: 0,
                    minutesWatched = stats?.optInt("minutesWatched") ?: 0,
                    meanScore = stats?.optDouble("meanScore") ?: 0.0,
                    isFollowing = u.optBoolean("isFollowing")
                )
            )
        }
        return list
    }

    suspend fun getUserFollowing(userId: Int, page: Int = 1, token: String? = null): List<AniListProfile> {
        val data = query("""
            query (${'$'}userId: Int, ${'$'}page: Int) {
              Page(page: ${'$'}page, perPage: 25) {
                following(userId: ${'$'}userId) {
                  id name bannerImage
                  avatar { large medium }
                  isFollowing
                  statistics { anime { count minutesWatched meanScore } }
                }
              }
            }
        """, mapOf("userId" to userId, "page" to page), token)

        val arr = data.optJSONObject("Page")?.optJSONArray("following") ?: return emptyList()
        val list = mutableListOf<AniListProfile>()
        for (i in 0 until arr.length()) {
            val u = arr.getJSONObject(i)
            val stats = u.optJSONObject("statistics")?.optJSONObject("anime")
            val avatarObj = u.optJSONObject("avatar")
            list.add(
                AniListProfile(
                    id = u.getInt("id"),
                    name = u.getString("name"),
                    avatar = avatarObj?.optNullableString("large") ?: avatarObj?.optNullableString("medium"),
                    banner = u.optNullableString("bannerImage"),
                    about = null,
                    animeCount = stats?.optInt("count") ?: 0,
                    minutesWatched = stats?.optInt("minutesWatched") ?: 0,
                    meanScore = stats?.optDouble("meanScore") ?: 0.0,
                    isFollowing = u.optBoolean("isFollowing")
                )
            )
        }
        return list
    }

    suspend fun toggleFollow(userId: Int, token: String): Boolean {
        val data = query("""
            mutation (${'$'}userId: Int!) {
              ToggleFollow(userId: ${'$'}userId) {
                isFollowing
              }
            }
        """, mapOf("userId" to userId), token)
        return data.getJSONObject("ToggleFollow").optBoolean("isFollowing")
    }

    suspend fun getTrending(perPage: Int = 18): List<AnimeMedia> {
        val data = query("""
            query {
              Page(page: 1, perPage: $perPage) {
                media(type: ANIME, sort: [TRENDING_DESC, POPULARITY_DESC]) { ${mediaFields(includeDubCast = true)} }
              }
            }
        """)
        return parsePageMedia(data)
    }

    suspend fun getMediaDetail(mediaId: Int, token: String? = null): AnimeMedia {
        val data = query("""
            query (${'$'}id: Int!) {
              Media(id: ${'$'}id, type: ANIME) { ${mediaFields(includeDubCast = true)} }
            }
        """, mapOf("id" to mediaId), token)
        return mapMedia(data.getJSONObject("Media"))
    }

    suspend fun getSimulcastSchedule(perPage: Int = 20): List<AnimeMedia> {
        val data = query("""
            query {
              Page(page: 1, perPage: $perPage) {
                media(type: ANIME, status: RELEASING, sort: [POPULARITY_DESC, TRENDING_DESC]) { ${mediaFields(includeDubCast = true)} }
              }
            }
        """)
        return parsePageMedia(data).filter { it.nextEpisodeAt != null }
    }

    suspend fun search(
        queryStr: String,
        page: Int = 1,
        perPage: Int = 20,
        token: String? = null
    ): SearchPage {
        val data = query("""
            query (${'$'}search: String, ${'$'}page: Int, ${'$'}perPage: Int) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                pageInfo { total hasNextPage }
                media(search: ${'$'}search, type: ANIME, sort: [SEARCH_MATCH, POPULARITY_DESC]) { ${mediaFields()} }
              }
            }
        """, mapOf("search" to queryStr, "page" to page, "perPage" to perPage), token)

        val page0 = data.getJSONObject("Page")
        val info = page0.optJSONObject("pageInfo")
        return SearchPage(
            results     = parseMediaArray(page0.optJSONArray("media")),
            hasNextPage = info?.optBoolean("hasNextPage") ?: false,
            total       = info?.optInt("total")?.takeIf { it > 0 }
        )
    }

    suspend fun browseCategory(genre: String? = null, tag: String? = null, perPage: Int = 40): List<AnimeMedia> {
        val data = query("""
            query (${'$'}genre: String, ${'$'}tag: String, ${'$'}perPage: Int) {
              Page(page: 1, perPage: ${'$'}perPage) {
                media(type: ANIME, genre: ${'$'}genre, tag: ${'$'}tag, sort: [TRENDING_DESC, POPULARITY_DESC]) { ${mediaFields()} }
              }
            }
        """, mapOf("genre" to genre, "tag" to tag, "perPage" to perPage))
        return parsePageMedia(data)
    }

    suspend fun getRecommendations(mediaIds: List<Int>): List<AnimeMedia> {
        if (mediaIds.isEmpty()) return emptyList()
        val ids = mediaIds.distinct().take(20)
        val data = query("""
            query (${'$'}ids: [Int]) {
              Page(page: 1, perPage: 50) {
                media(id_in: ${'$'}ids, type: ANIME) {
                  id
                  recommendations(perPage: 10, sort: [RATING_DESC]) {
                    nodes {
                      mediaRecommendation { ${mediaFields(includeDubCast = false)} }
                    }
                  }
                }
              }
            }
        """, mapOf("ids" to ids))
        val seen = mediaIds.toMutableSet()
        val results = mutableListOf<AnimeMedia>()
        val media = data.getJSONObject("Page").optJSONArray("media") ?: return emptyList()
        for (i in 0 until media.length()) {
            val recs = media.getJSONObject(i).optJSONObject("recommendations")?.optJSONArray("nodes") ?: continue
            for (j in 0 until recs.length()) {
                val rec = recs.getJSONObject(j).optJSONObject("mediaRecommendation") ?: continue
                val id = rec.optInt("id")
                if (!isMangaOrNovel(rec) && id > 0 && seen.add(id)) results += mapMedia(rec)
            }
        }
        return hydrateDubCast(results.take(60))
    }

    suspend fun getSimilarAnime(mediaId: Int): List<AnimeMedia> {
        val data = query("""
            query (${'$'}id: Int) {
              Media(id: ${'$'}id, type: ANIME) {
                relations {
                  edges {
                    relationType
                    node {
                      type
                      ${mediaFields(includeDubCast = false)}
                    }
                  }
                }
                recommendations(sort: [RATING_DESC], perPage: 16) {
                  nodes {
                    mediaRecommendation {
                      type
                      ${mediaFields(includeDubCast = false)}
                    }
                  }
                }
              }
            }
        """, mapOf("id" to mediaId))

        val media = data.optJSONObject("Media") ?: return emptyList()
        val results = mutableListOf<AnimeMedia>()
        val seen = mutableSetOf<Int>()

        val relEdges = media.optJSONObject("relations")?.optJSONArray("edges")
        if (relEdges != null) {
            for (i in 0 until relEdges.length()) {
                val edgeObj = relEdges.getJSONObject(i)
                val relType = edgeObj.optNullableString("relationType")
                val node = edgeObj.optJSONObject("node") ?: continue
                val id = node.optInt("id")
                if (!isMangaOrNovel(node) && id > 0 && id != mediaId && seen.add(id)) {
                    results.add(mapMedia(node).copy(relationType = relType))
                }
            }
        }

        val recNodes = media.optJSONObject("recommendations")?.optJSONArray("nodes")
        if (recNodes != null) {
            for (i in 0 until recNodes.length()) {
                val rec = recNodes.getJSONObject(i).optJSONObject("mediaRecommendation") ?: continue
                val id = rec.optInt("id")
                if (!isMangaOrNovel(rec) && id > 0 && id != mediaId && seen.add(id)) {
                    results.add(mapMedia(rec))
                }
            }
        }

        return hydrateDubCast(results)
    }

    suspend fun getFriendsMediaStatus(mediaId: Int, token: String? = null): List<FriendAnimeStatus> {
        val followingFilter = if (!token.isNullOrBlank()) ", isFollowing: true" else ""
        val data = query("""
                query (${'$'}mediaId: Int!) {
                  Page(page: 1, perPage: 15) {
                    activities(mediaId: ${'$'}mediaId$followingFilter, type_in: [ANIME_LIST], sort: ID_DESC) {
                      ... on ListActivity {
                        id userId status progress createdAt
                        user { id name avatar { medium } }
                      }
                    }
                  }
                }
        """, mapOf("mediaId" to mediaId), token)

        val pageObj = data.optJSONObject("Page") ?: return emptyList()
        val arr = pageObj.optJSONArray("activities") ?: return emptyList()
        val list = mutableListOf<FriendAnimeStatus>()
        val seenUsers = mutableSetOf<Int>()

        for (i in 0 until arr.length()) {
            val act = arr.optJSONObject(i) ?: continue
            val userObj = act.optJSONObject("user") ?: continue
            val uId = userObj.optInt("id")
            val statusRaw = act.optNullableString("status") ?: "watched"
            val progressRaw = act.optNullableString("progress")

            if (uId > 0 && seenUsers.add(uId)) {
                list.add(
                    FriendAnimeStatus(
                        id = act.optInt("id"),
                        userId = uId,
                        userName = userObj.optNullableString("name") ?: "Friend",
                        userAvatar = userObj.optJSONObject("avatar")?.optNullableString("medium"),
                        status = statusRaw,
                        progress = progressRaw,
                        createdAt = act.optLong("createdAt", 0L)
                    )
                )
            }
        }
        return list
    }

    suspend fun saveEntry(
        mediaId: Int, status: String?, progress: Int?,
        score: Double?, notes: String?, isPrivate: Boolean?,
        token: String,
        scoreFormat: String? = null
    ): AnimeMedia {
        val vars = mutableMapOf<String, Any?>("mediaId" to mediaId)
        if (!status.isNullOrBlank()) vars["status"] = status.uppercase()
        if (progress != null) vars["progress"] = progress
        if (score != null) {
            if (scoreFormat == null) vars["score"] = score
            else vars["scoreRaw"] = nativeScoreToRaw(score, scoreFormat)
        }
        if (notes != null) vars["notes"] = notes
        if (isPrivate != null) vars["private"] = isPrivate

        val data = query("""
            mutation (${'$'}mediaId: Int!, ${'$'}status: MediaListStatus, ${'$'}score: Float, ${'$'}scoreRaw: Int,
                      ${'$'}progress: Int, ${'$'}private: Boolean, ${'$'}notes: String) {
              SaveMediaListEntry(mediaId: ${'$'}mediaId, status: ${'$'}status, score: ${'$'}score, scoreRaw: ${'$'}scoreRaw,
                                 progress: ${'$'}progress, private: ${'$'}private, notes: ${'$'}notes) {
                id status score progress private notes updatedAt
                media { ${mediaFields(includeDubCast = true)} }
              }
            }
        """, vars, token)
        val entry = data.getJSONObject("SaveMediaListEntry")
        return mapMedia(entry.getJSONObject("media"), entry)
    }

    suspend fun deleteEntry(entryId: Int, token: String): Boolean {
        val data = query("""
            mutation (${'$'}id: Int!) { DeleteMediaListEntry(id: ${'$'}id) { deleted } }
        """, mapOf("id" to entryId), token)
        return data.getJSONObject("DeleteMediaListEntry").optBoolean("deleted")
    }

    suspend fun getActivities(page: Int = 1, perPage: Int = 25, isFollowing: Boolean = false, token: String? = null): List<AniListActivity> {
        val followingFilter = if (isFollowing && !token.isNullOrBlank()) "isFollowing: true, " else ""
        val data = query("""
                query (${'$'}page: Int, ${'$'}perPage: Int) {
                  Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                    activities(${followingFilter}type_in: [TEXT, ANIME_LIST], sort: ID_DESC) {
                      ... on TextActivity {
                        id userId text replyCount likeCount isLiked createdAt
                        user { name avatar { medium } }
                      }
                      ... on ListActivity {
                        id userId status progress replyCount likeCount isLiked createdAt
                        user { name avatar { medium } }
                        media {
                          type
                          ${mediaFields()}
                        }
                      }
                    }
                  }
                }
        """, mapOf("page" to page, "perPage" to perPage), token)

        val pageObj = data.optJSONObject("Page") ?: return emptyList()
        val activitiesArr = pageObj.optJSONArray("activities") ?: return emptyList()
        val list = mutableListOf<AniListActivity>()

        for (i in 0 until activitiesArr.length()) {
            val act = activitiesArr.optJSONObject(i) ?: continue
            val id = act.optInt("id")
            val userObj = act.optJSONObject("user")
            val userName = userObj?.optNullableString("name") ?: "User"
            val userAvatar = userObj?.optJSONObject("avatar")?.optNullableString("medium")
            val replyCount = act.optInt("replyCount", 0)
            val likeCount = act.optInt("likeCount", 0)
            val isLiked = act.optBoolean("isLiked", false)
            val createdAt = act.optLong("createdAt", 0L)

            if (act.has("text")) {
                list.add(
                    AniListActivity(
                        id = id,
                        userId = act.optInt("userId"),
                        userName = userName,
                        userAvatar = userAvatar,
                        type = "TEXT",
                        status = null,
                        progress = null,
                        text = act.optNullableString("text"),
                        mediaTitle = null,
                        mediaCover = null,
                        rawMedia = null,
                        replyCount = replyCount,
                        likeCount = likeCount,
                        isLiked = isLiked,
                        createdAt = createdAt
                    )
                )
            } else if (act.has("status")) {
                val mediaObj = act.optJSONObject("media")
                if (mediaObj != null && isMangaOrNovel(mediaObj)) continue

                val parsedMedia = if (mediaObj != null) runCatching { mapMedia(mediaObj) }.getOrNull() else null
                val titleObj = mediaObj?.optJSONObject("title")
                val mediaTitle = titleObj?.optNullableString("english")
                    ?: titleObj?.optNullableString("romaji")
                    ?: titleObj?.optNullableString("userPreferred")
                    ?: parsedMedia?.title
                val mediaCover = mediaObj?.optJSONObject("coverImage")?.optNullableString("medium")
                    ?: parsedMedia?.coverUrl

                list.add(
                    AniListActivity(
                        id = id,
                        userId = act.optInt("userId"),
                        userName = userName,
                        userAvatar = userAvatar,
                        type = "ANIME_LIST",
                        status = act.optNullableString("status"),
                        progress = act.optNullableString("progress"),
                        text = null,
                        mediaTitle = mediaTitle,
                        mediaCover = mediaCover,
                        rawMedia = parsedMedia,
                        replyCount = replyCount,
                        likeCount = likeCount,
                        isLiked = isLiked,
                        createdAt = createdAt
                    )
                )
            }
        }
        return list
    }

    suspend fun toggleActivityLike(activityId: Int, token: String): Pair<Boolean, Int> {
        val data = query("""
            mutation (${'$'}id: Int) {
              ToggleLikeV2(id: ${'$'}id, type: ACTIVITY) {
                ... on TextActivity { isLiked likeCount }
                ... on ListActivity { isLiked likeCount }
              }
            }
        """, mapOf("id" to activityId), token)
        val obj = data.getJSONObject("ToggleLikeV2")
        return Pair(obj.getBoolean("isLiked"), obj.getInt("likeCount"))
    }

    suspend fun getActivityReplies(activityId: Int, token: String? = null): List<ActivityReply> {
        val data = query("""
            query (${'$'}id: Int) {
              Page(page: 1, perPage: 30) {
                activityReplies(activityId: ${'$'}id) {
                  id userId text likeCount createdAt
                  user { name avatar { medium } }
                }
              }
            }
        """, mapOf("id" to activityId), token)
        val arr = data.optJSONObject("Page")?.optJSONArray("activityReplies") ?: return emptyList()
        val list = mutableListOf<ActivityReply>()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            val u = r.optJSONObject("user")
            list.add(
                ActivityReply(
                    id = r.optInt("id"),
                    userId = r.optInt("userId"),
                    userName = u?.optNullableString("name") ?: "User",
                    userAvatar = u?.optJSONObject("avatar")?.optNullableString("medium"),
                    text = r.optNullableString("text") ?: "",
                    likeCount = r.optInt("likeCount", 0),
                    createdAt = r.optLong("createdAt", 0L)
                )
            )
        }
        return list
    }

    suspend fun postActivityReply(activityId: Int, text: String, token: String): ActivityReply {
        val data = query("""
            mutation (${'$'}activityId: Int, ${'$'}text: String) {
              SaveActivityReply(activityId: ${'$'}activityId, text: ${'$'}text) {
                id userId text likeCount createdAt
                user { name avatar { medium } }
              }
            }
        """, mapOf("activityId" to activityId, "text" to text), token)
        val r = data.getJSONObject("SaveActivityReply")
        val u = r.optJSONObject("user")
        return ActivityReply(
            id = r.optInt("id"),
            userId = r.optInt("userId"),
            userName = u?.optNullableString("name") ?: "You",
            userAvatar = u?.optJSONObject("avatar")?.optNullableString("medium"),
            text = r.optNullableString("text") ?: text,
            likeCount = r.optInt("likeCount", 0),
            createdAt = r.optLong("createdAt", System.currentTimeMillis() / 1000)
        )
    }

    suspend fun postTextActivity(text: String, token: String): AniListActivity {
        val data = query("""
            mutation (${'$'}text: String!) {
              SaveTextActivity(text: ${'$'}text) {
                id userId text replyCount likeCount createdAt
                user { name avatar { medium } }
              }
            }
        """, mapOf("text" to text), token)

        val act = data.getJSONObject("SaveTextActivity")
        val userObj = act.optJSONObject("user")
        return AniListActivity(
            id = act.getInt("id"),
            userId = act.optInt("userId"),
            userName = userObj?.optNullableString("name") ?: "You",
            userAvatar = userObj?.optJSONObject("avatar")?.optNullableString("medium"),
            type = "TEXT",
            status = null,
            progress = null,
            text = act.optNullableString("text"),
            mediaTitle = null,
            mediaCover = null,
            rawMedia = null,
            replyCount = 0,
            likeCount = 0,
            createdAt = act.optLong("createdAt", System.currentTimeMillis() / 1000)
        )
    }

    suspend fun getStreamingEpisodes(mediaId: Int): List<StreamingEpisode> {
        val data = query("""
            query (${'$'}id: Int) {
              Media(id: ${'$'}id, type: ANIME) {
                streamingEpisodes {
                  title
                  thumbnail
                  url
                  site
                }
              }
            }
        """, mapOf("id" to mediaId))

        val eps = data.optJSONObject("Media")?.optJSONArray("streamingEpisodes")
            ?: return emptyList()

        val parsed = mutableListOf<StreamingEpisode>()
        val numRegex = Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE)

        for (i in 0 until eps.length()) {
            val ep = eps.optJSONObject(i) ?: continue
            val rawTitle = ep.optNullableString("title")
            val extractedNum = rawTitle?.let { numRegex.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
                ?: (i + 1)

            parsed.add(
                StreamingEpisode(
                    episodeNumber = extractedNum,
                    title = rawTitle,
                    thumbnail = ep.optNullableString("thumbnail"),
                    site = ep.optNullableString("site"),
                    url = ep.optNullableString("url")
                )
            )
        }
        return parsed.distinctBy { it.episodeNumber }.sortedBy { it.episodeNumber }
    }

    suspend fun getCast(mediaId: Int): List<CastMember> {
        val data = query("""
            query (${'$'}id: Int) {
              Media(id: ${'$'}id, type: ANIME) {
                characters(sort: [ROLE, RELEVANCE], perPage: 30) {
                  edges {
                    role
                    node {
                      id
                      name { full native }
                      image { medium }
                    }
                    voiceActors {
                      id
                      name { full native }
                      image { medium }
                      languageV2
                    }
                  }
                }
              }
            }
        """, mapOf("id" to mediaId))

        val media = data.optJSONObject("Media") ?: return emptyList()
        val edges = media.optJSONObject("characters")?.optJSONArray("edges") ?: return emptyList()
        val castList = mutableListOf<CastMember>()

        for (i in 0 until edges.length()) {
            val edge = edges.getJSONObject(i)
            val role = edge.optNullableString("role")
            val charNode = edge.optJSONObject("node") ?: continue
            val charId = charNode.optInt("id")
            val nameObj = charNode.optJSONObject("name")
            val charName = nameObj?.optNullableString("full") ?: "Character"
            val charNameNative = nameObj?.optNullableString("native")
            val charImg = charNode.optJSONObject("image")?.optNullableString("medium")

            val vaArr = edge.optJSONArray("voiceActors")
            var jpVa: VoiceActor? = null
            var enVa: VoiceActor? = null

            if (vaArr != null) {
                for (j in 0 until vaArr.length()) {
                    val vaObj = vaArr.getJSONObject(j)
                    val lang = vaObj.optString("languageV2", "")
                    val vaId = vaObj.optInt("id")
                    val vaNameObj = vaObj.optJSONObject("name")
                    val vaName = vaNameObj?.optNullableString("full") ?: "Voice Actor"
                    val vaImg = vaObj.optJSONObject("image")?.optNullableString("medium")

                    if (lang.equals("Japanese", ignoreCase = true) && jpVa == null) {
                        jpVa = VoiceActor(vaId, vaName, vaImg, "Japanese")
                    } else if (lang.equals("English", ignoreCase = true) && enVa == null) {
                        enVa = VoiceActor(vaId, vaName, vaImg, "English")
                    }
                }
            }

            castList.add(
                CastMember(
                    characterId = charId,
                    characterName = charName,
                    characterNameNative = charNameNative,
                    characterImage = charImg,
                    role = role,
                    japaneseVa = jpVa,
                    englishVa = enVa
                )
            )
        }
        return castList
    }

    suspend fun getStaffWorks(staffId: Int): StaffWorks? {
        val data = query("""
            query (${'$'}id: Int) {
              Staff(id: ${'$'}id) {
                id
                name { full native }
                image { large }
                languageV2
                characterMedia(type: ANIME, sort: [POPULARITY_DESC], perPage: 30) {
                  nodes {
                    ${mediaFields(includeDubCast = true)}
                  }
                }
              }
            }
        """, mapOf("id" to staffId))

        val staff = data.optJSONObject("Staff") ?: return null
        val nameObj = staff.optJSONObject("name")
        val name = nameObj?.optNullableString("full") ?: "Staff"
        val nameNative = nameObj?.optNullableString("native")
        val image = staff.optJSONObject("image")?.optNullableString("large")
        val lang = staff.optNullableString("languageV2")

        val mediaNodes = staff.optJSONObject("characterMedia")?.optJSONArray("nodes")
        val works = mutableListOf<AnimeMedia>()
        val seenIds = mutableSetOf<Int>()

        if (mediaNodes != null) {
            for (i in 0 until mediaNodes.length()) {
                val node = mediaNodes.getJSONObject(i)
                val id = node.optInt("id")
                if (!isMangaOrNovel(node) && id > 0 && seenIds.add(id)) {
                    works.add(mapMedia(node))
                }
            }
        }

        return StaffWorks(
            staffId = staffId,
            name = name,
            nameNative = nameNative,
            image = image,
            language = lang,
            works = works
        )
    }

    private fun parsePageMedia(data: JSONObject): List<AnimeMedia> =
        parseMediaArray(data.getJSONObject("Page").optJSONArray("media"))

    private fun parseMediaArray(arr: org.json.JSONArray?): List<AnimeMedia> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { 
            val obj = arr.optJSONObject(it) ?: return@mapNotNull null
            if (!isMangaOrNovel(obj)) runCatching { mapMedia(obj) }.getOrNull() else null 
        }
    }
}
