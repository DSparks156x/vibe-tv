package com.vibe.tvlauncher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OverseerrTest {
    private val search = """
        {"page":1,"totalPages":1,"totalResults":4,"results":[
          {"id":438631,"mediaType":"movie","title":"Dune","releaseDate":"2021-09-15"},
          {"id":841,"mediaType":"movie","title":"Dune","releaseDate":"1984-12-14"},
          {"id":90228,"mediaType":"tv","name":"Dune: Prophecy","firstAirDate":"2024-11-17"},
          {"id":1234,"mediaType":"person","name":"Dune"}
        ]}
    """.trimIndent()

    @Test fun matchesTypeNameAndYear() {
        assertEquals(438631, Overseerr.matchSearch(search, "movie", "Dune", "2021"))
        assertEquals(841, Overseerr.matchSearch(search, "movie", "Dune", "1984"))
        assertEquals(90228, Overseerr.matchSearch(search, "tv", "Dune: Prophecy", "2024–"))
    }

    @Test fun ambiguousOrMissingIsNull() {
        // Two movies called Dune and no year to tell them apart.
        assertNull(Overseerr.matchSearch(search, "movie", "Dune", null))
        assertNull(Overseerr.matchSearch(search, "tv", "Dune", "2021"))
        assertNull(Overseerr.matchSearch("""{"results":[]}""", "movie", "Dune", "2021"))
    }

    @Test fun singleNameMatchIgnoresYear() {
        assertEquals(90228, Overseerr.matchSearch(search, "tv", "dune prophecy", "2025"))
    }

    @Test fun status() {
        assertEquals(Overseerr.Status.None, Overseerr.parseStatus("""{"id":1,"title":"x"}"""))
        assertEquals(Overseerr.Status.None, Overseerr.parseStatus("""{"mediaInfo":{"status":1}}"""))
        assertEquals(Overseerr.Status.Requested, Overseerr.parseStatus("""{"mediaInfo":{"status":2}}"""))
        assertEquals(Overseerr.Status.Requested, Overseerr.parseStatus("""{"mediaInfo":{"status":3}}"""))
        assertEquals(Overseerr.Status.Partial, Overseerr.parseStatus("""{"mediaInfo":{"status":4}}"""))
        assertEquals(Overseerr.Status.Available, Overseerr.parseStatus("""{"mediaInfo":{"status":5}}"""))
    }

    @Test fun errors() {
        assertEquals("Overseerr rejected the API key", Overseerr.serverError(401, ""))
        assertEquals("Overseerr rejected the API key", Overseerr.serverError(403, """{"message":"You do not have permission to access this endpoint"}"""))
        assertEquals("Movie Quota exceeded.", Overseerr.serverError(403, """{"message":"Movie Quota exceeded."}"""))
        assertEquals("Already requested", Overseerr.serverError(500, """{"message":"Request for this media already exists."}"""))
        assertEquals("Overseerr error (500)", Overseerr.serverError(500, "<html>"))
    }
}
