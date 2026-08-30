package eu.kanade.tachiyomi.animesource

interface ConfigurableAnimeSource : AnimeSource {
    fun setupPreferenceScreen(screen: Any) {}
}
