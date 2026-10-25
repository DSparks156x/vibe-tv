package com.vibe.tvlauncher.data.voice

/** What the user wants, before it is grounded in real catalog/app hits. */
enum class Action { PLAY, RESUME, NEXT_EPISODE, SHOW_RESULTS, OPEN_APP, YOUTUBE }

/** A title the user (or the model) named, to be looked up in real catalogs. */
data class TitleGuess(val name: String, val year: Int? = null, val type: String? = null)

/**
 * Parsed request. [query] is the free-text search, [titles] are concrete titles to resolve,
 * [refs] point at Continue watching / library items by their context id (e.g. "c2", "l5").
 */
data class VoiceIntent(
    val action: Action,
    val query: String,
    val titles: List<TitleGuess> = emptyList(),
    val refs: List<String> = emptyList(),
    val app: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    /** Short line shown above the results ("Here are some 90s comedies"). */
    val reply: String? = null,
    /** Where the interpretation came from, for the UI hint and logs. */
    val source: Source = Source.RULES,
) {
    enum class Source { RULES, LLM }
}
