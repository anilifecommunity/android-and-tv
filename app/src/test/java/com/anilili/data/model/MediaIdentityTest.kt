package com.anilili.data.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MediaIdentityTest {
    private val json = Json { encodeDefaults = true }
    private val provenance = IdentityProvenance("native-contract-test")
    private fun fixture(name: String): JsonObject = javaClass.classLoader!!
        .getResourceAsStream("media-contract/v2/$name")!!.bufferedReader(Charsets.UTF_8)
        .use { Json.parseToJsonElement(it.readText()).jsonObject }

    @Test fun `published canonical and Unicode source vectors match exact native identities`() {
        val vectors = fixture("identity-vectors.json")
        for (element in vectors.getValue("canonical").jsonArray) {
            val vector = element.jsonObject
            val ref = MediaRef(MediaKind.valueOf(vector.getValue("kind").jsonPrimitive.content),
                vector.getValue("mediaKey").jsonPrimitive.content,
                listOf(MediaAlias(vector.getValue("namespace").jsonPrimitive.content,
                    vector.getValue("id").jsonPrimitive, true, provenance)),
                vector.getValue("format").jsonPrimitive.content)
            assertEquals(ref, json.decodeFromString<MediaRef>(json.encodeToString(ref)))
        }
        for (element in vectors.getValue("source").jsonArray) {
            val vector = element.jsonObject
            val parts = vector.getValue("parts").jsonArray.map { it.jsonPrimitive.content }
            val source = MediaSourceRef(parts[0], parts[1], parts[2], parts[4], provenance)
            assertEquals(vector.getValue("mediaKey").jsonPrimitive.content, source.mediaKey(MediaKind.valueOf(parts[3])))
        }
    }

    @Test fun `all published detail references retain all six kinds and exact wire fields`() {
        val records = fixture("record-vectors.json").getValue("records").jsonArray
            .map { it.jsonObject }.filter { it["recordType"]?.jsonPrimitive?.content == "detail" }
        val kinds = mutableSetOf<MediaKind>()
        for (record in records) {
            val wire = record.getValue("ref")
            val ref = json.decodeFromJsonElement<MediaRef>(wire)
            kinds += ref.kind
            val encoded = json.encodeToJsonElement(ref).jsonObject
            assertEquals(wire.jsonObject.filterValues { it != JsonNull }, encoded.filterValues { it != JsonNull })
        }
        assertEquals(MediaKind.entries.toSet(), kinds)
    }

    @Test fun `same numeric ID cannot join different kinds or provider namespaces`() {
        val anime = legacyAndroidAnimeRef(42)
        val manga = MediaRef(MediaKind.MANGA, "al-42", listOf(MediaAlias("anilist:manga", JsonPrimitive(42), true, provenance)))
        val movie = MediaRef(MediaKind.MOVIE, "tmdb-movie-42", listOf(MediaAlias("tmdb:movie", JsonPrimitive(42), true, provenance)))
        val tv = MediaRef(MediaKind.TV, "tmdb-tv-42", listOf(MediaAlias("tmdb:tv", JsonPrimitive(42), true, provenance)))
        assertEquals(4, setOf(anime.target, manga.target, movie.target, tv.target).size)
        assertNull(manga.anilistAnimeIdOrNull())
        assertNull(movie.anilistAnimeIdOrNull())
        assertEquals(42, anime.anilistAnimeIdOrNull())
        assertThrows(IllegalArgumentException::class.java) { anime.copy(kind = MediaKind.MANGA) }
        assertThrows(IllegalArgumentException::class.java) { anime.copy(aliases = anime.aliases.map { it.copy(verified = false) }) }
        assertThrows(IllegalArgumentException::class.java) { anime.copy(aliases = anime.aliases + anime.aliases) }
    }

    @Test fun `historical catalogue IDs remain source identities without tracker aliases`() {
        val hanime = legacyAndroidAnimeRef(-42)
        val onePace = legacyAndroidAnimeRef(-1_000_000_000)
        assertNotEquals(hanime.target, onePace.target)
        assertEquals("hanime", hanime.sourceRef!!.providerId)
        assertEquals("42", hanime.sourceRef!!.stableItemId)
        assertEquals("onepace", onePace.sourceRef!!.providerId)
        for (ref in listOf(hanime, onePace, legacyAndroidAnimeRef(Int.MIN_VALUE))) {
            assertTrue(ref.mediaKey.startsWith("src-"))
            assertTrue(ref.aliases.isEmpty())
            assertNull(ref.anilistAnimeIdOrNull())
            assertEquals(ref, json.decodeFromString<MediaRef>(json.encodeToString(ref)))
        }
        assertThrows(IllegalArgumentException::class.java) { legacyAndroidAnimeRef(0) }
    }

    @Test fun `unsafe source IDs malformed aliases and fabricated canonical keys are refused`() {
        val source = MediaSourceRef("stremio", "official", "video", "42", provenance)
        for (id in listOf("", "https://host/title", "a?token=secret", "user@host", "a#fragment", "a b", "a\u0085b", "\uD800")) {
            assertThrows(IllegalArgumentException::class.java) { source.copy(stableItemId = id) }
        }
        for (id in listOf(JsonPrimitive("42"), JsonPrimitive(true), JsonPrimitive(0), JsonPrimitive(9_007_199_254_740_992L))) {
            assertThrows(IllegalArgumentException::class.java) { MediaAlias("anilist:anime", id, true, provenance) }
        }
        assertThrows(IllegalArgumentException::class.java) { MediaTarget(MediaKind.ANIME, "tmdb-movie-42") }
        assertThrows(IllegalArgumentException::class.java) { MediaTarget(MediaKind.ANIME, "al-9007199254740992") }
        assertThrows(IllegalArgumentException::class.java) {
            MediaRef(MediaKind.MOVIE, "src-" + "0".repeat(64), emptyList(), sourceRef = source)
        }
    }

    @Test fun `reading format projection and stable unit identity do not guess or renumber`() {
        assertEquals(MediaKind.NOVEL, anilistMediaKind("MANGA", "NOVEL"))
        assertEquals(MediaKind.MANGA, anilistMediaKind("MANGA", "ONE_SHOT"))
        assertNull(anilistMediaKind("MANGA", null))
        val first = MediaUnitRef(legacyAndroidAnimeRef(146984).target, "episode-1")
        val second = MediaUnitRef(legacyAndroidAnimeRef(162314).target, "episode-1")
        assertNotEquals(first, second)
        assertThrows(IllegalArgumentException::class.java) { first.copy(unitId = "https://host/episode?token=secret") }
        assertThrows(IllegalArgumentException::class.java) { first.copy(unitId = "") }
    }
}
