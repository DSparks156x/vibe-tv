package com.vibe.tvlauncher.data.voice

import java.text.Normalizer

/**
 * Fast offline parser for the common phrasings. Returns the intent plus whether it is
 * sure enough to act without asking the language model.
 */
object RuleParser {
    data class Parse(val intent: VoiceIntent, val certain: Boolean)

    private val numberWords = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "first" to 1,
        "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5, "sixth" to 6, "seventh" to 7,
        "eighth" to 8, "ninth" to 9, "tenth" to 10, "to" to 2, "too" to 2, "for" to 4,
    )
    private const val NUM = "(\\d+|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth|to|too|for)"

    private val politeness = Regex("^(?:(?:hey|ok|okay)\\s+\\w+\\s*,?\\s*)?(?:can you |could you |would you |please |i want to |i wanna |i'd like to |let's |lets |let me )*")
    private val trailingPolite = Regex("\\s*(?:please|for me|now|thanks|thank you)$")

    private val nextEpisode = Regex("^(?:play |watch |start |put on )?(?:the )?next (?:episode|ep)(?: of (.+))?$")
    private val resume = Regex("^(?:continue|resume|keep watching|finish|go back to|pick up)(?: watching)?(?: where i left off(?: on| in| with)?)?\\s*(.*)$")
    private val youtubeSuffix = Regex("^(?:play |watch |search |find |search for |look up |show me |put on )?(.+?) (?:on|in|from) youtube$")
    private val youtubePrefix = Regex("^(?:open |search |play |go to )?youtube(?: and)?(?: search| play| for| search for)* (.+)$")
    private val openApp = Regex("^(?:open|launch|start|go to|switch to|run)(?: the)? (.+?)(?: app| application)?$")
    private val play = Regex("^(?:play|watch|put on|stream|start|show)(?: me)? (.+)$")
    private val search = Regex("^(?:search(?: for)?|find|look up|look for|show me|i'm looking for|looking for)(?: me)? (.+)$")
    private val seasonEp = Regex("\\s*,?\\s*(?:season|series) $NUM,?\\s*(?:episode|ep) $NUM\\b")
    private val seasonOnly = Regex("\\s*,?\\s*(?:season|series) $NUM\\b")
    private val sxe = Regex("\\s*\\bs(\\d{1,2})\\s*e(\\d{1,3})\\b")

    /** Words that make a query need real understanding (moods, eras, descriptions, questions). */
    private val fuzzyMarkers = Regex(
        "\\b(something|anything|some|a movie|a film|a show|movies|films|shows|funny|scary|sad|romantic|" +
            "good|best|new|newest|latest|recent|old|classic|like|similar|about|with the|that one|that show|that movie|" +
            "the one|starring|where|who|what|which|why|how|recommend|suggest|mood|feel|tonight|yesterday|last night|" +
            "earlier|ago|kids|family|\\d0s|nineties|eighties|seventies|sixties|decade|century|genre|comedy|comedies|horror|" +
            "thriller|documentar\\w*|anime|cartoon|action|drama|sci-?fi|western|musical|christmas|halloween)\\b"
    )

    fun normalize(text: String): String {
        val n = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return n.replace(Regex("[\"“”?!.]"), "").replace(Regex("\\s+"), " ").trim()
            .replace(politeness, "").replace(trailingPolite, "").trim()
    }

    fun parse(raw: String): Parse {
        val t = normalize(raw)
        if (t.isEmpty()) return Parse(VoiceIntent(Action.SHOW_RESULTS, ""), false)

        nextEpisode.matchEntire(t)?.let { m ->
            val show = m.groupValues[1].trim()
            return Parse(VoiceIntent(Action.NEXT_EPISODE, show), true)
        }
        resume.matchEntire(t)?.let { m ->
            val what = m.groupValues[1].trim().removePrefix("watching ").removePrefix("the show ").trim()
            return Parse(VoiceIntent(Action.RESUME, what), true)
        }
        (youtubeSuffix.matchEntire(t) ?: youtubePrefix.matchEntire(t))?.let { m ->
            return Parse(VoiceIntent(Action.YOUTUBE, m.groupValues[1].trim()), true)
        }
        if (t == "youtube") return Parse(VoiceIntent(Action.OPEN_APP, "youtube", app = "youtube"), true)

        openApp.matchEntire(t)?.let { m ->
            val app = m.groupValues[1].trim()
            // "start the matrix" is a movie, not an app; the resolver only acts if an app label matches.
            return Parse(VoiceIntent(Action.OPEN_APP, app, app = app), true)
        }

        val (core, season, episode) = splitEpisode(t)
        play.matchEntire(core)?.let { m ->
            val what = m.groupValues[1].trim().removePrefix("the movie ").removePrefix("the show ").removePrefix("the film ")
            val fuzzy = fuzzyMarkers.containsMatchIn(what)
            return Parse(
                VoiceIntent(Action.PLAY, what, titles = if (fuzzy) emptyList() else listOf(TitleGuess(what)), season = season, episode = episode),
                certain = !fuzzy,
            )
        }
        search.matchEntire(core)?.let { m ->
            val what = m.groupValues[1].trim()
            return Parse(VoiceIntent(Action.SHOW_RESULTS, what, titles = listOf(TitleGuess(what)), season = season, episode = episode), !fuzzyMarkers.containsMatchIn(what))
        }
        // A bare title ("breaking bad") is a search; anything longer or descriptive goes to the model.
        val short = core.split(' ').size <= 5 && !fuzzyMarkers.containsMatchIn(core)
        return Parse(VoiceIntent(Action.SHOW_RESULTS, core, titles = listOf(TitleGuess(core)), season = season, episode = episode), short)
    }

    private fun splitEpisode(t: String): Triple<String, Int?, Int?> {
        seasonEp.find(t)?.let { m ->
            return Triple(clean(t.removeRange(m.range)), num(m.groupValues[1]), num(m.groupValues[2]))
        }
        sxe.find(t)?.let { m ->
            return Triple(clean(t.removeRange(m.range)), m.groupValues[1].toInt(), m.groupValues[2].toInt())
        }
        seasonOnly.find(t)?.let { m ->
            return Triple(clean(t.removeRange(m.range)), num(m.groupValues[1]), null)
        }
        return Triple(t, null, null)
    }

    /** "play of the office" / "the office of" -> "play the office" / "the office". */
    private fun clean(s: String): String =
        s.replace(Regex("\\s+"), " ").trim()
            .replace(Regex("^((?:play|watch|put on|start|search for|find|show me) )?of "), "$1")
            .replace(Regex(" of$"), "")
            .trim()

    private fun num(s: String): Int? = s.toIntOrNull() ?: numberWords[s]
}
