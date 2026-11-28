package com.anilili.data.storage

import android.app.Application
import com.anilili.data.library.*
import com.anilili.data.model.legacyAndroidAnimeRef
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [22, 35], manifest = Config.NONE, application = Application::class)
class LegacyMediaImportTest {
    private lateinit var database: MediaDatabase
    private val name = "import.db"
    private val saved = WatchlistEntry(42, "Saved title", "file:///sdcard/poster.png", "TV", 87, 10)
    private val history = HistoryEntry(42, "Watched title", "https://example.org/poster.png", 3.5,
        "Special", "old-provider", "SUB", 12_000, 24_000, 30, setOf(1.0, 2.0), false,
        mapOf("3.5" to EpisodeResume(13_000, 24_000), "4" to EpisodeResume(5_000, 25_000)))

    @Before fun open() {
        val context = RuntimeEnvironment.getApplication()
        val directory = context.getDatabasePath(name).parentFile!!
        check(directory.isDirectory || directory.mkdirs())
        database = MediaDatabase(context, name)
    }

    @After fun close() { database.close(); RuntimeEnvironment.getApplication().deleteDatabase(name) }

    private fun snapshot() = LegacyMediaSnapshot(mapOf(
        "watchlist" to storageJson.encodeToString(listOf(saved)),
        "history" to storageJson.encodeToString(listOf(history)),
        "local_watched" to storageJson.encodeToString(listOf(WatchedEntry(-42, "Catalogue", null, watchedAt = 25))),
        "custom_lists" to storageJson.encodeToString(listOf(CustomList("Favourites", listOf(saved), 20))),
        "custom_list_ops" to storageJson.encodeToString(CustomListOps(creates = listOf("Favourites"))),
        "remote_statuses" to storageJson.encodeToString(listOf(RemoteListStatus(42, "CURRENT"))),
        "remote_scores" to storageJson.encodeToString(listOf(RemoteListScore(42, 8.5))),
        "dismissed_remote_history" to "[999]",
        "pending_saved_sync" to "[42]",
        "watch_activity_days" to "{\"20784\":2}",
    ), buildJsonObject { put("showSkipButton", false); put("skipSeconds", 85) }, "", "favourites,removed-list")

    @Test fun `complete profile is retained and projected without changing files or claiming cloud work`() = runBlocking<Unit> {
        val source = snapshot()
        assertTrue(database.importLegacyProfile("local-one", source))
        database.transaction("local-one") {
            assertTrue(migrationComplete(LEGACY_MIGRATION)); assertTrue(outbox(true).isEmpty())
            source.sections.forEach { (key, raw) -> assertEquals(raw, legacySection(key)) }
            assertEquals(history, storageJson.decodeFromString<List<HistoryEntry>>(legacySection("history")!!).single())
            val library = entities(LocalCollection.LIBRARY)
            assertEquals(2, library.size)
            val anime = library.single { it.ref!!.anilistAnimeIdOrNull() == 42 }
            assertEquals(10, anime.value.getValue("savedAt").jsonPrimitive.int)
            assertEquals("CURRENT", anime.value.getValue("status").jsonPrimitive.content)
            assertEquals(8.5, anime.value.getValue("score").jsonPrimitive.double, 0.0)
            assertEquals(legacyAndroidAnimeRef(-42), library.single { it.ref!!.mediaKey.startsWith("src-") }.ref)
            val progress = entities(LocalCollection.PROGRESS).associateBy { it.value.getValue("unitId").jsonPrimitive.content }
            assertEquals(4, progress.size)
            assertTrue(progress.getValue("legacy-episode:1").value.getValue("completed").jsonPrimitive.boolean)
            assertEquals(13_000, progress.getValue("legacy-episode:3.5").value.getValue("positionMs").jsonPrimitive.int)
            assertEquals(5_000, progress.getValue("legacy-episode:4").value.getValue("positionMs").jsonPrimitive.int)
            val list = entities(LocalCollection.LIST).single()
            assertEquals(list.key, entities(LocalCollection.LIST_MEMBER).single().parentId)
            assertEquals(JsonPrimitive(""), entity(LocalCollection.LOCAL_SETTING, "libraryViewNames")!!.value["value"])
            assertEquals(JsonArray(listOf(JsonPrimitive(list.key))), entity(LocalCollection.LOCAL_SETTING, "hiddenLibraryListIds")!!.value["value"])
            assertEquals(JsonPrimitive(false), entity(LocalCollection.PORTABLE_SETTING, "showSkipButton")!!.value["value"])
        }
        val revision = database.changes.value
        database.close(); database = MediaDatabase(RuntimeEnvironment.getApplication(), name)
        assertFalse(database.importLegacyProfile("local-one", source))
        assertEquals(0L, database.changes.value)
        assertTrue(revision > 0)
        assertTrue(database.importLegacyProfile("local-two", source))
        val first = database.transaction("local-one") { entities(LocalCollection.LIST).single().key }
        val second = database.transaction("local-two") { entities(LocalCollection.LIST).single().key }
        assertNotEquals(first, second)
    }

    @Test fun `old inferred history is compact and explicit empty watched sets remain empty`() = runBlocking<Unit> {
        val old = buildJsonObject {
            put("anilistId", 42); put("title", "Old history"); put("cover", JsonNull)
            put("episodeNumber", 1_000_000_000.5); put("provider", "auto"); put("category", "SUB")
        }
        val explicit = storageJson.encodeToJsonElement(history.copy(anilistId = 43, watchedEpisodes = emptySet(), resumePoints = emptyMap()))
        assertTrue(database.importLegacyProfile("local-one", LegacyMediaSnapshot(mapOf("history" to JsonArray(listOf(old, explicit)).toString()))))
        database.transaction("local-one") {
            assertEquals(2, entities(LocalCollection.PROGRESS).size)
            val inferred = entities(LocalCollection.LIBRARY).single { it.ref!!.anilistAnimeIdOrNull() == 42 }
            assertEquals(JsonPrimitive("1000000000.5"), inferred.value["legacyWatchedBefore"])
            assertFalse("inferred local progress became cloud evidence", "legacyWatchedBefore" in inferred.portableValue())
            assertFalse("explicit empty watched set was inferred", "legacyWatchedBefore" in entities(LocalCollection.LIBRARY).single { it.ref!!.anilistAnimeIdOrNull() == 43 }.value)
        }
    }

    @Test fun `invalid or ambiguous input never commits a partial migration`() = runBlocking<Unit> {
        val source = snapshot()
        val invalid = listOf(
            source.copy(sections = source.sections + ("history" to "not json")),
            source.copy(sections = source.sections + ("watchlist" to storageJson.encodeToString(listOf(saved, saved)))),
            source.copy(sections = source.sections + ("history" to storageJson.encodeToString(listOf(history.copy(resumePoints = mapOf("4" to EpisodeResume(), "4.0" to EpisodeResume())))))),
            source.copy(sections = source.sections + ("watchlist" to storageJson.encodeToString(listOf(saved.copy(anilistId = 0))))),
            source.copy(sections = source.sections + ("local_watched" to storageJson.encodeToString(listOf(WatchedEntry(99, "Invalid time", null, watchedAt = -1))))),
        )
        invalid.forEach {
            try { database.importLegacyProfile("local-one", it); fail("invalid import succeeded") } catch (_: IllegalArgumentException) { }
            database.transaction("local-one") {
                assertFalse(migrationComplete(LEGACY_MIGRATION)); assertNull(legacySection("history"))
                LocalCollection.entries.forEach { type -> assertTrue(entities(type).isEmpty()) }
            }
            assertEquals(0L, database.changes.value)
        }
        assertTrue(database.importLegacyProfile("local-one", source))
    }

    @Test fun `migration cannot adopt a bound account or overwrite unrelated guest rows`() = runBlocking<Unit> {
        val binding = CloudBinding("account-one", "remote-one")
        database.transaction("local-one") { activateBinding(binding) }
        assertTrue(database.importLegacyProfile("local-one", snapshot()))
        database.transaction("local-one") {
            assertTrue(entities(LocalCollection.LIBRARY).isEmpty()); assertTrue(outbox(true).isEmpty())
            assertNotNull(entity(LocalCollection.LOCAL_SETTING, "libraryViewNames"))
            signOut(binding)
            assertEquals(2, entities(LocalCollection.LIBRARY).size)
        }
        val extra = LocalEntity(LocalCollection.LIBRARY, buildJsonObject {
            put("ref", storageJson.encodeToJsonElement(legacyAndroidAnimeRef(555))); put("title", "Existing")
        })
        database.transaction("local-two") { put(extra) }
        try { database.importLegacyProfile("local-two", snapshot()); fail("count mismatch committed") } catch (_: IllegalArgumentException) { }
        database.transaction("local-two") {
            assertEquals(listOf(extra), entities(LocalCollection.LIBRARY))
            assertFalse(migrationComplete(LEGACY_MIGRATION)); assertNull(legacySection("watchlist"))
        }
    }

    @Test fun `capture rejects secrets unbounded sections and nonportable settings`() {
        assertThrows(IllegalArgumentException::class.java) { LegacyMediaSnapshot(mapOf("oauth_token" to "secret")) }
        assertThrows(IllegalArgumentException::class.java) { LegacyMediaSnapshot(mapOf("history" to "x".repeat(8 * 1024 * 1024 + 1))) }
        assertThrows(IllegalArgumentException::class.java) { LegacyMediaSnapshot(emptyMap(), buildJsonObject { put("token", "secret") }) }
        val original = mapOf("history" to "[]", "oauth_token" to "secret", "dismissed_remote_history" to setOf("42", "99"))
        val captured = captureLegacyMedia(original)
        assertEquals(setOf("history", "dismissed_remote_history"), captured.sections.keys)
        assertEquals(JsonArray(listOf(JsonPrimitive("42"), JsonPrimitive("99"))), Json.parseToJsonElement(captured.sections.getValue("dismissed_remote_history")))
        assertEquals("secret", original["oauth_token"])
        assertEquals(setOf("42", "99"), original["dismissed_remote_history"])
        assertThrows(IllegalArgumentException::class.java) { captureLegacyMedia(mapOf("history" to 1)) }
    }
}
