package io.maru.manime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class AniListUser(
    val id: Int,
    val name: String,
    val avatarUrl: String?
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
    val updatedAt: Long?
) {
    val isReleasing: Boolean get() = status.equals("RELEASING", ignoreCase = true)

    val latestAiredEpisode: Int? get() {
        if (!isReleasing) return episodes
        return if (nextEpisode != null && nextEpisode > 1) nextEpisode - 1 else null
    }
}

data class ExternalLink(
    val id: Int,
    val url: String,
    val site: String,
    val type: String,
    val language: String?
) {
    val isEnglishDub: Boolean get() {
        val isExplicitEnglishLang = language?.equals("ENGLISH", ignoreCase = true) == true
        val isExplicitEnglishSite = site.contains("English", ignoreCase = true) && site.contains("Dub", ignoreCase = true)
        val isExplicitEnglishUrl = (url.contains("english", ignoreCase = true) && url.contains("dub", ignoreCase = true)) ||
                                   url.contains("/en-dub", ignoreCase = true) ||
                                   url.contains("en_dub", ignoreCase = true)
        return isExplicitEnglishLang || isExplicitEnglishSite || isExplicitEnglishUrl
    }
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

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    fun JSONObject?.optNullableString(name: String): String? {
        if (this == null || isNull(name)) return null
        val s = optString(name, "").trim()
        return if (s.isEmpty() || s.equals("null", ignoreCase = true)) null else s
    }

    private suspend fun query(
        gql: String,
        variables: Map<String, Any?> = emptyMap(),
        token: String? = null
    ): JSONObject = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("query", gql)
            put("variables", JSONObject(variables))
        }.toString().toRequestBody(JSON_MEDIA_TYPE)

        val reqBuilder = Request.Builder()
            .url(ENDPOINT)
            .post(body)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
        if (!token.isNullOrBlank()) reqBuilder.header("Authorization", "Bearer $token")

        val resp = client.newCall(reqBuilder.build()).execute()
        val json = JSONObject(resp.body?.string() ?: "{}")
        if (json.has("errors")) {
            val msg = json.getJSONArray("errors").getJSONObject(0).optString("message", "AniList error")
            throw Exception(msg)
        }
        json.getJSONObject("data")
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
            val l = rawLinks.getJSONObject(i)
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

        val titleEng = title?.optNullableString("english")
        val titleRom = title?.optNullableString("romaji")
        val titlePref = title?.optNullableString("userPreferred")
        val titleNative = title?.optNullableString("native")

        val displayTitle = titleEng ?: titleRom ?: titlePref ?: titleNative ?: "Untitled"

        return AnimeMedia(
            mediaId       = obj.getInt("id"),
            title         = displayTitle,
            titleEnglish  = titleEng,
            titleRomaji   = titleRom,
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
            updatedAt     = le?.optLong("updatedAt")?.takeIf { it > 0 }
        )
    }

    private fun mediaFields() = """
        id episodes format status season seasonYear meanScore averageScore isAdult siteUrl bannerImage
        title { userPreferred english romaji native }
        coverImage { large color }
        nextAiringEpisode { episode airingAt }
        externalLinks { id url site type language }
        genres
        description(asHtml: false)
        mediaListEntry { id status score progress private notes updatedAt }
    """.trimIndent()

    suspend fun getViewer(token: String): AniListUser {
        val data = query("""
            query { Viewer { id name avatar { medium } } }
        """, token = token)
        val v = data.getJSONObject("Viewer")
        return AniListUser(
            id        = v.getInt("id"),
            name      = v.getString("name"),
            avatarUrl = v.optJSONObject("avatar")?.optNullableString("medium")
        )
    }

    fun parseUserListJson(data: JSONObject): Map<String, List<AnimeMedia>> {
        val result = mutableMapOf<String, MutableList<AnimeMedia>>()
        val collection = data.optJSONObject("MediaListCollection") ?: return emptyMap()
        val lists = collection.optJSONArray("lists") ?: return emptyMap()
        for (i in 0 until lists.length()) {
            val list = lists.getJSONObject(i)
            val listName = list.optString("name", "List")
            val entries = list.optJSONArray("entries") ?: continue
            val bucket = result.getOrPut(listName) { mutableListOf() }
            for (j in 0 until entries.length()) {
                val entry = entries.getJSONObject(j)
                val media = entry.optJSONObject("media") ?: continue
                if (!isMangaOrNovel(media)) {
                    bucket += mapMedia(media, entry)
                }
            }
        }
        return result
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
                  name status
                  entries {
                    id status score progress private notes updatedAt
                    media { ${mediaFields()} }
                  }
                }
              }
            }
        """, mapOf("name" to username), token)
        return Pair(data.toString(), parseUserListJson(data))
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

            val (_, lists) = getUserListRawJson(username, token)

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

    suspend fun getTrending(perPage: Int = 20): List<AnimeMedia> {
        val data = query("""
            query {
              Page(page: 1, perPage: $perPage) {
                media(type: ANIME, sort: [TRENDING_DESC, POPULARITY_DESC]) { ${mediaFields()} }
              }
            }
        """)
        return parsePageMedia(data)
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
        val vars = mutableMapOf<String, Any?>()
        val genreFilter = if (genre != null) ", genre: \$genre" else ""
        val tagFilter   = if (tag != null)   ", tag_in: [\$tag]" else ""
        if (genre != null) vars["genre"] = genre
        if (tag != null)   vars["tag"]   = tag
        val data = query("""
            query (${'$'}genre: String, ${'$'}tag: String) {
              Page(page: 1, perPage: $perPage) {
                media(type: ANIME, sort: [TRENDING_DESC, POPULARITY_DESC]$genreFilter$tagFilter) { ${mediaFields()} }
              }
            }
        """, vars)
        return parsePageMedia(data)
    }

    suspend fun getRecommendations(mediaIds: List<Int>): List<AnimeMedia> {
        if (mediaIds.isEmpty()) return emptyList()
        val ids = mediaIds.take(20)
        val data = query("""
            query (${'$'}ids: [Int]) {
              Page(page: 1, perPage: 50) {
                media(id_in: ${'$'}ids, type: ANIME) {
                  id
                  recommendations(perPage: 10, sort: [RATING_DESC]) {
                    nodes {
                      mediaRecommendation { ${mediaFields()} }
                    }
                  }
                }
              }
            }
        """, mapOf("ids" to ids))
        val seen = mutableSetOf<Int>()
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
        return results
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
                      ${mediaFields()}
                    }
                  }
                }
                recommendations(sort: [RATING_DESC], perPage: 16) {
                  nodes {
                    mediaRecommendation {
                      type
                      ${mediaFields()}
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
                val node = relEdges.getJSONObject(i).optJSONObject("node") ?: continue
                val id = node.optInt("id")
                if (!isMangaOrNovel(node) && id > 0 && id != mediaId && seen.add(id)) {
                    results.add(mapMedia(node))
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

        return results
    }

    suspend fun getFriendsMediaStatus(mediaId: Int, token: String? = null): List<FriendAnimeStatus> {
        val isFollow = !token.isNullOrBlank()
        val data = try {
            query("""
                query (${'$'}mediaId: Int, ${'$'}isFollowing: Boolean) {
                  Page(page: 1, perPage: 15) {
                    activities(mediaId: ${'$'}mediaId, isFollowing: ${'$'}isFollowing, type_in: [ANIME_LIST], sort: ID_DESC) {
                      ... on ListActivity {
                        id userId status progress createdAt
                        user { id name avatar { medium } }
                      }
                    }
                  }
                }
            """, mapOf("mediaId" to mediaId, "isFollowing" to isFollow), token)
        } catch (_: Exception) {
            query("""
                query (${'$'}mediaId: Int) {
                  Page(page: 1, perPage: 15) {
                    activities(mediaId: ${'$'}mediaId, sort: ID_DESC) {
                      ... on ListActivity {
                        id userId status progress createdAt
                        user { id name avatar { medium } }
                      }
                    }
                  }
                }
            """, mapOf("mediaId" to mediaId))
        }

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
        mediaId: Int, status: String, progress: Int?,
        score: Double?, notes: String?, isPrivate: Boolean,
        token: String
    ): AnimeMedia {
        val data = query("""
            mutation (${'$'}mediaId: Int!, ${'$'}status: MediaListStatus, ${'$'}score: Float,
                      ${'$'}progress: Int, ${'$'}private: Boolean, ${'$'}notes: String) {
              SaveMediaListEntry(mediaId: ${'$'}mediaId, status: ${'$'}status, score: ${'$'}score,
                                 progress: ${'$'}progress, private: ${'$'}private, notes: ${'$'}notes) {
                id status score progress private notes updatedAt
                media { ${mediaFields()} }
              }
            }
        """, mapOf(
            "mediaId" to mediaId, "status" to status, "score" to score,
            "progress" to progress, "private" to isPrivate, "notes" to notes
        ), token)
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
        val isFollow = if (isFollowing && !token.isNullOrBlank()) true else false
        val data = try {
            query("""
                query (${'$'}page: Int, ${'$'}perPage: Int, ${'$'}isFollowing: Boolean) {
                  Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                    activities(isFollowing: ${'$'}isFollowing, type_in: [TEXT, ANIME_LIST], sort: ID_DESC) {
                      ... on TextActivity {
                        id userId text replyCount likeCount createdAt
                        user { name avatar { medium } }
                      }
                      ... on ListActivity {
                        id userId status progress replyCount likeCount createdAt
                        user { name avatar { medium } }
                        media {
                          type
                          ${mediaFields()}
                        }
                      }
                    }
                  }
                }
            """, mapOf("page" to page, "perPage" to perPage, "isFollowing" to isFollow), token)
        } catch (_: Exception) {
            query("""
                query (${'$'}page: Int, ${'$'}perPage: Int) {
                  Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                    activities(sort: ID_DESC) {
                      ... on TextActivity {
                        id userId text replyCount likeCount createdAt
                        user { name avatar { medium } }
                      }
                      ... on ListActivity {
                        id userId status progress replyCount likeCount createdAt
                        user { name avatar { medium } }
                        media {
                          type
                          ${mediaFields()}
                        }
                      }
                    }
                  }
                }
            """, mapOf("page" to page, "perPage" to perPage))
        }

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
                        createdAt = createdAt
                    )
                )
            }
        }
        return list
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

        return (0 until eps.length()).map { i ->
            val ep = eps.getJSONObject(i)
            StreamingEpisode(
                episodeNumber = i + 1,
                title = ep.optNullableString("title"),
                thumbnail = ep.optNullableString("thumbnail"),
                site = ep.optNullableString("site"),
                url = ep.optNullableString("url")
            )
        }
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
                    id episodes format status season seasonYear meanScore averageScore isAdult siteUrl bannerImage
                    title { userPreferred english romaji native }
                    coverImage { large color }
                    genres
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
            val obj = arr.getJSONObject(it)
            if (!isMangaOrNovel(obj)) runCatching { mapMedia(obj) }.getOrNull() else null 
        }
    }
}
