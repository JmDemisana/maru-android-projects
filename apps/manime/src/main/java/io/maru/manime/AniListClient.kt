package io.maru.manime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// ---------------------------------------------------------------------------
// Data models
// ---------------------------------------------------------------------------

data class AniListUser(
    val id: Int,
    val name: String,
    val avatarUrl: String?
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
    // List entry fields (null if not on user's list)
    val listEntryId: Int?,
    val listStatus: String?,
    val progress: Int,
    val score: Double?,
    val notes: String?,
    val isPrivate: Boolean,
    val updatedAt: Long?
)

data class ExternalLink(
    val id: Int,
    val url: String,
    val site: String,
    val type: String,
    val language: String?
) {
    val isEnglishDub: Boolean get() =
        language?.uppercase() == "ENGLISH" && type.uppercase() == "STREAMING"
}

data class VoiceActor(
    val id: Int,
    val name: String,
    val image: String?,
    val language: String
)

data class CharacterRole(
    val characterName: String,
    val characterImage: String?,
    val voiceActor: VoiceActor?
)

data class DubDetails(
    val isDubbed: Boolean,
    val dubConfidence: String, // "CONFIRMED" | "PARTIAL" | "SUB_ONLY"
    val englishCast: List<CharacterRole>,
    val streamingPlatforms: List<String>
)

data class AniListActivity(
    val id: Int,
    val userId: Int,
    val userName: String,
    val userAvatar: String?,
    val type: String, // "TEXT" | "ANIME_LIST" | "MESSAGE"
    val status: String?,
    val progress: String?,
    val text: String?,
    val mediaTitle: String?,
    val mediaCover: String?,
    val replyCount: Int,
    val likeCount: Int,
    val createdAt: Long
)

data class StreamingEpisode(
    val episodeNumber: Int,    // 1-based, inferred from position
    val title: String?,        // may be null for some shows
    val thumbnail: String?,
    val site: String?,
    val url: String?
)

// ---------------------------------------------------------------------------
// AniList GraphQL Client — direct calls to https://graphql.anilist.co
// Queries mirror those in IsThisDubbed.tsx on the site.
// ---------------------------------------------------------------------------

object AniListClient {
    private const val ENDPOINT = "https://graphql.anilist.co"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    // -----------------------------------------------------------------------
    // Core request helper
    // -----------------------------------------------------------------------
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

    // -----------------------------------------------------------------------
    // Mappers
    // -----------------------------------------------------------------------
    private fun mapMedia(obj: JSONObject, listEntry: JSONObject? = null): AnimeMedia {
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
                url = l.optString("url"),
                site = l.optString("site"),
                type = l.optString("type"),
                language = l.optString("language").ifEmpty { null }
            )
        }

        val genres = mutableListOf<String>()
        val rawGenres = obj.optJSONArray("genres")
        if (rawGenres != null) for (i in 0 until rawGenres.length()) genres += rawGenres.getString(i)

        val titlePref = title?.optString("english").orEmpty()
            .ifEmpty { title?.optString("romaji").orEmpty() }
            .ifEmpty { title?.optString("userPreferred").orEmpty() }

        return AnimeMedia(
            mediaId       = obj.getInt("id"),
            title         = titlePref.ifEmpty { "Untitled" },
            titleEnglish  = title?.optString("english")?.ifEmpty { null },
            titleRomaji   = title?.optString("romaji")?.ifEmpty { null },
            coverUrl      = cover?.optString("large")?.ifEmpty { null },
            bannerUrl     = obj.optString("bannerImage").ifEmpty { null },
            accentColor   = cover?.optString("color")?.ifEmpty { null },
            episodes      = obj.optInt("episodes").takeIf { it > 0 },
            format        = obj.optString("format").ifEmpty { null },
            status        = obj.optString("status").ifEmpty { null },
            season        = obj.optString("season").ifEmpty { null },
            seasonYear    = obj.optInt("seasonYear").takeIf { it > 0 },
            averageScore  = obj.optInt("averageScore").takeIf { it > 0 },
            genres        = genres,
            description   = obj.optString("description").ifEmpty { null },
            siteUrl       = obj.optString("siteUrl").ifEmpty { null },
            isAdult       = obj.optBoolean("isAdult"),
            nextEpisode   = nextAir?.optInt("episode")?.takeIf { it > 0 },
            nextEpisodeAt = nextAir?.optLong("airingAt")?.takeIf { it > 0 },
            externalLinks = links,
            listEntryId   = le?.optInt("id")?.takeIf { it > 0 },
            listStatus    = le?.optString("status")?.ifEmpty { null },
            progress      = le?.optInt("progress") ?: 0,
            score         = le?.optDouble("score")?.takeIf { it > 0 },
            notes         = le?.optString("notes")?.ifEmpty { null },
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

    // -----------------------------------------------------------------------
    // Viewer (auth check)
    // -----------------------------------------------------------------------
    suspend fun getViewer(token: String): AniListUser {
        val data = query("""
            query { Viewer { id name avatar { medium } } }
        """, token = token)
        val v = data.getJSONObject("Viewer")
        return AniListUser(
            id        = v.getInt("id"),
            name      = v.getString("name"),
            avatarUrl = v.optJSONObject("avatar")?.optString("medium")?.ifEmpty { null }
        )
    }

    // -----------------------------------------------------------------------
    // User list (currently watching, planning, etc.)
    // -----------------------------------------------------------------------
    suspend fun getUserList(username: String, token: String? = null): Map<String, List<AnimeMedia>> {
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

        val result = mutableMapOf<String, MutableList<AnimeMedia>>()
        val lists = data.getJSONObject("MediaListCollection").getJSONArray("lists")
        for (i in 0 until lists.length()) {
            val list = lists.getJSONObject(i)
            val listName = list.getString("name")
            val entries = list.getJSONArray("entries")
            val bucket = result.getOrPut(listName) { mutableListOf() }
            for (j in 0 until entries.length()) {
                val entry = entries.getJSONObject(j)
                val media = entry.optJSONObject("media") ?: continue
                bucket += mapMedia(media, entry)
            }
        }
        return result
    }

    // -----------------------------------------------------------------------
    // Trending / Dashboard
    // -----------------------------------------------------------------------
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

    // -----------------------------------------------------------------------
    // Search
    // -----------------------------------------------------------------------
    suspend fun search(q: String, page: Int = 1, perPage: Int = 20, token: String? = null): SearchPage {
        val data = query("""
            query (${'$'}search: String, ${'$'}page: Int, ${'$'}perPage: Int) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                pageInfo { hasNextPage total }
                media(search: ${'$'}search, type: ANIME) { ${mediaFields()} }
              }
            }
        """, mapOf("search" to q, "page" to page, "perPage" to perPage), token)
        val page0 = data.getJSONObject("Page")
        val info = page0.optJSONObject("pageInfo")
        return SearchPage(
            results     = parseMediaArray(page0.optJSONArray("media")),
            hasNextPage = info?.optBoolean("hasNextPage") ?: false,
            total       = info?.optInt("total")?.takeIf { it > 0 }
        )
    }

    // -----------------------------------------------------------------------
    // Category browse (genre / tag)
    // -----------------------------------------------------------------------
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

    // -----------------------------------------------------------------------
    // Recommendations from a set of media IDs
    // -----------------------------------------------------------------------
    suspend fun getRecommendations(mediaIds: List<Int>): List<AnimeMedia> {
        if (mediaIds.isEmpty()) return emptyList()
        val ids = mediaIds.take(20) // limit to avoid oversized query
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
                if (id > 0 && seen.add(id)) results += mapMedia(rec)
            }
        }
        return results
    }

    // -----------------------------------------------------------------------
    // Save / Delete list entry (requires token)
    // -----------------------------------------------------------------------
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

data class SearchPage(
    val results: List<AnimeMedia>,
    val hasNextPage: Boolean,
    val total: Int?
)

    // -----------------------------------------------------------------------
    // AniList Activity Feed (Community Posts & List Updates)
    // -----------------------------------------------------------------------
    suspend fun getActivities(page: Int = 1, perPage: Int = 25, isFollowing: Boolean = false, token: String? = null): List<AniListActivity> {
        val data = query("""
            query (${'$'}page: Int, ${'$'}perPage: Int, ${'$'}isFollowing: Boolean) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                activities(isFollowing: ${'$'}isFollowing, sort: ID_DESC) {
                  ... on TextActivity {
                    id userId text replyCount likeCount createdAt
                    user { name avatar { medium } }
                  }
                  ... on ListActivity {
                    id userId status progress replyCount likeCount createdAt
                    user { name avatar { medium } }
                    media {
                      title { userPreferred english romaji }
                      coverImage { medium }
                    }
                  }
                  ... on MessageActivity {
                    id recipientId message replyCount likeCount createdAt
                    messenger { name avatar { medium } }
                  }
                }
              }
            }
        """, mapOf("page" to page, "perPage" to perPage, "isFollowing" to isFollowing), token)

        val pageObj = data.optJSONObject("Page") ?: return emptyList()
        val activitiesArr = pageObj.optJSONArray("activities") ?: return emptyList()
        val list = mutableListOf<AniListActivity>()

        for (i in 0 until activitiesArr.length()) {
            val act = activitiesArr.optJSONObject(i) ?: continue
            val id = act.optInt("id")
            val userObj = act.optJSONObject("user") ?: act.optJSONObject("messenger")
            val userName = userObj?.optString("name", "User") ?: "User"
            val userAvatar = userObj?.optJSONObject("avatar")?.optString("medium")
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
                        text = act.optString("text"),
                        mediaTitle = null,
                        mediaCover = null,
                        replyCount = replyCount,
                        likeCount = likeCount,
                        createdAt = createdAt
                    )
                )
            } else if (act.has("status")) {
                val mediaObj = act.optJSONObject("media")
                val titleObj = mediaObj?.optJSONObject("title")
                val mediaTitle = titleObj?.optString("english")?.ifEmpty { null }
                    ?: titleObj?.optString("romaji")?.ifEmpty { null }
                    ?: titleObj?.optString("userPreferred")
                val mediaCover = mediaObj?.optJSONObject("coverImage")?.optString("medium")

                list.add(
                    AniListActivity(
                        id = id,
                        userId = act.optInt("userId"),
                        userName = userName,
                        userAvatar = userAvatar,
                        type = "ANIME_LIST",
                        status = act.optString("status"),
                        progress = act.optString("progress").ifEmpty { null },
                        text = null,
                        mediaTitle = mediaTitle,
                        mediaCover = mediaCover,
                        replyCount = replyCount,
                        likeCount = likeCount,
                        createdAt = createdAt
                    )
                )
            } else if (act.has("message")) {
                list.add(
                    AniListActivity(
                        id = id,
                        userId = act.optInt("recipientId"),
                        userName = userName,
                        userAvatar = userAvatar,
                        type = "MESSAGE",
                        status = null,
                        progress = null,
                        text = act.optString("message"),
                        mediaTitle = null,
                        mediaCover = null,
                        replyCount = replyCount,
                        likeCount = likeCount,
                        createdAt = createdAt
                    )
                )
            }
        }
        return list
    }

    // -----------------------------------------------------------------------
    // IsThisDubbed — Query English Voice Cast & Dub Status
    // -----------------------------------------------------------------------
    suspend fun getDubDetails(mediaId: Int): DubDetails {
        val data = query("""
            query (${'$'}id: Int) {
              Media(id: ${'$'}id, type: ANIME) {
                externalLinks { site type language }
                characters(sort: [ROLE, RELEVANCE], perPage: 12) {
                  edges {
                    node { name { full } image { medium } }
                    voiceActors(language: ENGLISH) {
                      id name { full } image { medium } languageV2
                    }
                  }
                }
              }
            }
        """, mapOf("id" to mediaId))

        val media = data.optJSONObject("Media") ?: return DubDetails(false, "SUB_ONLY", emptyList(), emptyList())
        val extLinks = media.optJSONArray("externalLinks")
        val platforms = mutableListOf<String>()
        var hasExplicitDubLink = false

        if (extLinks != null) {
            for (i in 0 until extLinks.length()) {
                val l = extLinks.getJSONObject(i)
                val site = l.optString("site")
                val lang = l.optString("language")
                if (lang.equals("English", ignoreCase = true) || site.contains("Dub", ignoreCase = true)) {
                    hasExplicitDubLink = true
                    if (!platforms.contains(site)) platforms.add(site)
                }
            }
        }

        val charEdges = media.optJSONObject("characters")?.optJSONArray("edges")
        val castList = mutableListOf<CharacterRole>()

        if (charEdges != null) {
            for (i in 0 until charEdges.length()) {
                val edge = charEdges.getJSONObject(i)
                val charNode = edge.optJSONObject("node")
                val charName = charNode?.optJSONObject("name")?.optString("full", "Character") ?: "Character"
                val charImg = charNode?.optJSONObject("image")?.optString("medium")

                val vaArr = edge.optJSONArray("voiceActors")
                var va: VoiceActor? = null
                if (vaArr != null && vaArr.length() > 0) {
                    val vaObj = vaArr.getJSONObject(0)
                    va = VoiceActor(
                        id = vaObj.optInt("id"),
                        name = vaObj.optJSONObject("name")?.optString("full", "Voice Actor") ?: "Voice Actor",
                        image = vaObj.optJSONObject("image")?.optString("medium"),
                        language = vaObj.optString("languageV2", "English")
                    )
                }

                if (va != null) {
                    castList.add(CharacterRole(charName, charImg, va))
                }
            }
        }

        val isDubbed = castList.isNotEmpty() || hasExplicitDubLink
        val confidence = if (castList.size >= 3 || hasExplicitDubLink) "CONFIRMED" else if (castList.isNotEmpty()) "PARTIAL" else "SUB_ONLY"

        return DubDetails(
            isDubbed = isDubbed,
            dubConfidence = confidence,
            englishCast = castList,
            streamingPlatforms = platforms
        )
    }

    // -----------------------------------------------------------------------
    // Streaming Episodes (titles + thumbnails from AniList)
    // -----------------------------------------------------------------------
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
                title = ep.optString("title").ifEmpty { null },
                thumbnail = ep.optString("thumbnail").ifEmpty { null },
                site = ep.optString("site").ifEmpty { null },
                url = ep.optString("url").ifEmpty { null }
            )
        }
    }

    // -----------------------------------------------------------------------
    // Private parse helpers
    // -----------------------------------------------------------------------
    private fun parsePageMedia(data: JSONObject): List<AnimeMedia> =
        parseMediaArray(data.getJSONObject("Page").optJSONArray("media"))

    private fun parseMediaArray(arr: org.json.JSONArray?): List<AnimeMedia> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { runCatching { mapMedia(arr.getJSONObject(it)) }.getOrNull() }
    }
}
