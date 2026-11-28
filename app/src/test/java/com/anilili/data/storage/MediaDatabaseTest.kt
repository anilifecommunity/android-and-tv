package com.anilili.data.storage

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import com.anilili.data.model.*
import java.io.IOException
import kotlinx.coroutines.runBlocking
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
class MediaDatabaseTest {
    private lateinit var database: MediaDatabase
    private lateinit var name: String
    private val binding = CloudBinding("account-one", "remote-profile-one")

    @Before fun open() {
        // Robolectric already isolates each test; long filenames exceed Windows native SQLite paths.
        name = "test.db"
        val directory = RuntimeEnvironment.getApplication().getDatabasePath(name).parentFile!!
        check(directory.isDirectory || directory.mkdirs()) { "Test database directory could not be created" }
        database = MediaDatabase(RuntimeEnvironment.getApplication(), name)
    }

    @After fun close() {
        database.close()
        RuntimeEnvironment.getApplication().deleteDatabase(name)
    }

    private fun item(id: Int = 42, title: String = "Title $id"): LocalEntity = LocalEntity(LocalCollection.LIBRARY, buildJsonObject {
        put("ref", storageJson.encodeToJsonElement(legacyAndroidAnimeRef(id)))
        put("title", title); put("savedAt", 100L); put("isAdult", JsonNull)
    })

    private suspend fun bind(value: CloudBinding = binding, profile: String = "local-one", replace: Boolean = false) {
        database.transaction(profile) { activateBinding(value, replace) }
    }

    @Test fun `entity outbox and notification commit together or all roll back`() = runBlocking<Unit> {
        bind()
        val before = database.changes.value
        try {
            database.transaction("local-one") { put(item()); throw IOException("process interrupted before commit") }
            fail("interrupted transaction committed")
        } catch (_: IOException) { }
        assertEquals(before, database.changes.value)
        database.transaction("local-one") { assertTrue(entities(LocalCollection.LIBRARY).isEmpty()); assertTrue(outbox().isEmpty()) }
        database.transaction("local-one") { put(item()) }
        assertEquals(before + 1, database.changes.value)
        val operation = database.transaction("local-one") { outbox().single() }
        database.close()
        database = MediaDatabase(RuntimeEnvironment.getApplication(), name)
        database.transaction("local-one") {
            assertEquals(item(), entity(LocalCollection.LIBRARY, item().key))
            assertEquals(operation, outbox().single())
        }
    }

    @Test fun `unsent edits coalesce but claimed requests retain original ID base and bytes`() = runBlocking<Unit> {
        bind()
        database.transaction("local-one") { recordRevision(LocalCollection.LIBRARY, item().key, 7); put(item()) }
        val first = database.transaction("local-one") { outbox().single() }
        database.transaction("local-one") { put(item(title = "Latest")) }
        val latest = database.transaction("local-one") { claimOutbox().single() }
        assertEquals(first.opId, latest.opId)
        assertEquals(7L, latest.baseRevision)
        assertEquals("Latest", latest.value!!["title"]!!.jsonPrimitive.content)
        database.transaction("local-one") { put(item(title = "After send")) }
        database.transaction("local-one") {
            assertEquals(2, outbox().size)
            assertEquals(listOf(latest), claimOutbox())
            val unsent = outbox().last()
            assertEquals(7L, unsent.baseRevision)
            assertTrue(acknowledge(latest.opId, 8))
            val next = outbox().single()
            assertNotEquals(latest.opId, next.opId)
            assertNotEquals(unsent.opId, next.opId)
            assertEquals(8L, next.baseRevision)
            assertEquals("After send", next.value!!["title"]!!.jsonPrimitive.content)
        }
    }

    @Test fun `guest accounts remote profiles local profiles and cursors cannot leak across bindings`() = runBlocking<Unit> {
        database.transaction("local-one") { put(item(title = "Guest")) }
        bind()
        database.transaction("local-one") { assertTrue(entities(LocalCollection.LIBRARY).isEmpty()); put(item(title = "Account one")); setCursor("cursor_one_0123456789") }
        database.transaction("local-one") { signOut(binding) }
        database.transaction("local-one") { assertEquals("Guest", entities(LocalCollection.LIBRARY).single().value["title"]!!.jsonPrimitive.content); assertNull(cursor()); assertTrue(outbox().isEmpty()) }
        val other = CloudBinding("account-two", "remote-profile-two")
        try { bind(other); fail("silently changed account") } catch (_: IllegalArgumentException) { }
        bind(other, replace = true)
        database.transaction("local-one") { assertTrue(entities(LocalCollection.LIBRARY).isEmpty()); assertNull(cursor()); assertTrue(outbox().isEmpty()); put(item(title = "Account two")) }
        try { database.transaction("local-one", MutationOrigin.REMOTE, binding) { fail("stale account was allowed") } } catch (_: IllegalStateException) { }
        bind(binding, replace = true)
        database.transaction("local-one") { assertEquals("Account one", entities(LocalCollection.LIBRARY).single().value["title"]!!.jsonPrimitive.content); assertEquals("cursor_one_0123456789", cursor()); assertEquals(1, outbox().size) }
        database.transaction("local-two") { assertTrue(entities(LocalCollection.LIBRARY).isEmpty()); assertTrue(outbox().isEmpty()) }
        bind(binding.copy(remoteProfileId = "another-remote-profile"), replace = true)
        database.transaction("local-one") { assertTrue(entities(LocalCollection.LIBRARY).isEmpty()); assertTrue(outbox().isEmpty()) }
    }

    @Test fun `migration counts marker and raw recovery inputs are one idempotent guest transaction`() = runBlocking<Unit> {
        bind()
        try {
            database.transaction("local-one", MutationOrigin.MIGRATION) {
                put(item()); retainLegacySection("history", "original bytes")
                completeMigration("android-v1", "0".repeat(64), emptyMap())
            }
            fail("accepted missing records")
        } catch (_: IllegalArgumentException) { }
        database.transaction("local-one", MutationOrigin.MIGRATION) {
            assertFalse(migrationComplete("android-v1")); assertNull(legacySection("history")); assertTrue(entities(LocalCollection.LIBRARY).isEmpty())
            put(item()); retainLegacySection("history", "original bytes")
            completeMigration("android-v1", "0".repeat(64), mapOf(LocalCollection.LIBRARY to 1))
        }
        val committed = database.changes.value
        database.transaction("local-one", MutationOrigin.MIGRATION) {
            if (!migrationComplete("android-v1")) fail("migration replay would run")
            assertEquals("original bytes", legacySection("history"))
            assertEquals(1, entities(LocalCollection.LIBRARY).size)
            assertTrue(outbox().isEmpty())
        }
        assertEquals(committed, database.changes.value)
        database.transaction("local-one") { assertTrue(entities(LocalCollection.LIBRARY).isEmpty()); assertNull(legacySection("history")) }
    }

    @Test fun `renames preserve members and parent deletion is atomic without orphan cloud work`() = runBlocking<Unit> {
        bind()
        val list = LocalEntity(LocalCollection.LIST, buildJsonObject { put("id", "stable-list"); put("name", "Before") })
        val member = LocalEntity(LocalCollection.LIST_MEMBER, buildJsonObject { put("parentId", "stable-list"); put("ref", storageJson.encodeToJsonElement(legacyAndroidAnimeRef(42))) })
        database.transaction("local-one") { put(list); put(member); put(list.copy(value = JsonObject(list.value + ("name" to JsonPrimitive("After"))))) }
        database.transaction("local-one") { assertEquals(1, entities(LocalCollection.LIST_MEMBER).size); assertEquals(2, outbox().size) }
        database.transaction("local-one") {
            val definition = claimOutbox().first { it.collection == LocalCollection.LIST }
            acknowledge(definition.opId, 1)
        }
        try {
            database.transaction("local-one", MutationOrigin.REMOTE, binding) { remove(LocalCollection.LIST, list.key) }
            fail("remote delete lost local edits")
        } catch (_: IllegalStateException) { }
        database.transaction("local-one") {
            remove(LocalCollection.LIST, list.key)
            assertTrue(entities(LocalCollection.LIST).isEmpty()); assertTrue(entities(LocalCollection.LIST_MEMBER).isEmpty())
            assertEquals("delete", outbox().single().action)
        }
        try {
            database.transaction("local-two") { put(member) }
            fail("membership crossed its profile")
        } catch (_: android.database.sqlite.SQLiteConstraintException) { }
    }

    @Test fun `mode paging no-op edits and local display controls stay bounded and private`() = runBlocking<Unit> {
        bind()
        database.transaction("local-one") { for (i in 1..5) put(item(i)) }
        database.transaction("local-one") {
            val first = entities(LocalCollection.LIBRARY, MediaKind.ANIME, 2)
            val next = entities(LocalCollection.LIBRARY, MediaKind.ANIME, 2, 2)
            assertEquals(2, first.size); assertEquals(2, next.size); assertTrue(first.map { it.key }.intersect(next.map { it.key }.toSet()).isEmpty())
        }
        val previous = database.changes.value
        database.transaction("local-one") { assertFalse(put(item(1))) }
        assertEquals(previous, database.changes.value)
        database.transaction("local-one") {
            val count = outbox().size
            put(LocalEntity(LocalCollection.LOCAL_SETTING, buildJsonObject { put("key", "libraryViewNames"); put("value", "") }))
            assertEquals("", entities(LocalCollection.LOCAL_SETTING).single().value["value"]!!.jsonPrimitive.content)
            assertEquals(count, outbox().size)
        }
        bind(CloudBinding("other-account", "other-remote-profile"), replace = true)
        database.transaction("local-one") {
            assertEquals("", entities(LocalCollection.LOCAL_SETTING).single().value["value"]!!.jsonPrimitive.content)
            assertTrue(outbox().isEmpty())
        }
    }

    @Test fun `private fields and device paths cannot enter a portable payload`() {
        val normal = item()
        assertThrows(IllegalArgumentException::class.java) { normal.copy(value = JsonObject(normal.value + ("credentials" to JsonPrimitive("not portable")))) }
        assertThrows(IllegalArgumentException::class.java) { LocalEntity(LocalCollection.PORTABLE_SETTING, buildJsonObject { put("key", "accessToken"); put("value", "not portable") }) }
        val localPoster = normal.copy(value = JsonObject(normal.value + ("poster" to JsonPrimitive("file:///storage/local.jpg"))))
        assertTrue("poster" in localPoster.value)
        assertFalse("poster" in localPoster.portableValue())
        LocalEntity(LocalCollection.PORTABLE_SETTING, buildJsonObject { put("key", "skipSeconds"); put("value", 85) })
        assertThrows(IllegalArgumentException::class.java) { LocalEntity(LocalCollection.PORTABLE_SETTING, buildJsonObject { put("key", "skipSeconds"); put("value", 181) }) }
    }

    @Test fun `oversized portable intent retains the local item and stays out of transport`() = runBlocking<Unit> {
        bind()
        val component = "\uD83D\uDCFA".repeat(512)
        val source = MediaSourceRef(component, component, component, component, IdentityProvenance("native-boundary-test"))
        val ref = MediaRef(MediaKind.ANIME, source.mediaKey(MediaKind.ANIME), emptyList(), sourceRef = source)
        val large = LocalEntity(LocalCollection.LIBRARY, buildJsonObject {
            put("ref", storageJson.encodeToJsonElement(ref)); put("title", "x".repeat(1024))
        })
        database.transaction("local-one") {
            put(large)
            assertEquals(large, entity(LocalCollection.LIBRARY, large.key))
            assertTrue(outbox(includeBlocked = true).single().blocked)
            assertTrue(claimOutbox().isEmpty())
        }
    }

    @Test fun `published kinds retain separate keys and mode queries only return their own rows`() = runBlocking<Unit> {
        val records = javaClass.classLoader!!.getResourceAsStream("media-contract/v2/record-vectors.json")!!
            .bufferedReader(Charsets.UTF_8).use { storageJson.parseToJsonElement(it.readText()).jsonObject["records"]!!.jsonArray }
            .map { it.jsonObject }.filter { it["recordType"]?.jsonPrimitive?.content == "detail" }
        database.transaction("local-one") {
            records.forEach { record -> put(LocalEntity(LocalCollection.LIBRARY, buildJsonObject {
                put("ref", record.getValue("ref")); put("title", record.getValue("titles").jsonObject.getValue("display")); put("isAdult", record.getValue("isAdult"))
            })) }
            assertEquals(records.size, entities(LocalCollection.LIBRARY).size)
            for (kind in MediaKind.entries) {
                val expected = records.count { it.getValue("ref").jsonObject.getValue("kind").jsonPrimitive.content == kind.name }
                val rows = entities(LocalCollection.LIBRARY, kind)
                assertEquals(expected, rows.size)
                assertTrue(rows.all { it.ref!!.kind == kind })
            }
            assertTrue(outbox().isEmpty())
        }
    }

    @Test fun `SQLite full rolls back entities and outbox and leaves the owner usable`() = runBlocking<Unit> {
        bind()
        val sqlite = database.writableDatabase
        fun pragma(name: String): Long = sqlite.rawQuery("PRAGMA $name", null).use { it.moveToFirst(); it.getLong(0) }
        var previousLimit = 0L
        val before = database.changes.value
        var full = false
        try {
            try {
                database.transaction("local-one") {
                    // The page limit is connection-local: set it on this transaction's writer.
                    previousLimit = sqlite.maximumSize
                    val maximum = pragma("page_count") * sqlite.pageSize
                    sqlite.setMaximumSize(maximum)
                    assertEquals(maximum, sqlite.maximumSize)
                    for (id in 1..100) put(item(id, "x".repeat(1024)))
                }
            } catch (error: SQLiteException) {
                assertTrue(error.toString(), error is android.database.sqlite.SQLiteFullException || error.toString().contains("SQLITE_FULL"))
                full = true
            }
        } finally {
            if (previousLimit > 0) database.transaction("local-one") { sqlite.setMaximumSize(previousLimit) }
        }
        assertTrue("did not reach the controlled SQLite page limit", full)
        assertEquals(before, database.changes.value)
        database.transaction("local-one") {
            assertTrue(entities(LocalCollection.LIBRARY).isEmpty()); assertTrue(outbox().isEmpty())
            put(item()); assertEquals(1, outbox().size)
        }
    }

    @Test fun `opening a future schema cannot downgrade or erase existing data`() = runBlocking<Unit> {
        database.transaction("local-one") { put(item()) }
        database.writableDatabase.version = 2
        database.close()
        database = MediaDatabase(RuntimeEnvironment.getApplication(), name)
        try {
            database.transaction("local-one") { entities(LocalCollection.LIBRARY) }
            fail("future database was silently downgraded")
        } catch (_: SQLiteException) { }
        SQLiteDatabase.openDatabase(RuntimeEnvironment.getApplication().getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
            assertEquals(2, sqlite.version)
            sqlite.rawQuery("SELECT payload FROM library_items", null).use {
                assertTrue(it.moveToFirst())
                assertEquals(item().value, storageJson.parseToJsonElement(it.getString(0)).jsonObject)
            }
        }
    }

    @Test fun `corruption never deletes the original database`() = runBlocking<Unit> {
        database.transaction("local-one") { put(item()) }
        database.close()
        val file = RuntimeEnvironment.getApplication().getDatabasePath(name)
        val damaged = file.readBytes().also { bytes -> for (i in 0..15) bytes[i] = 127 }
        file.writeBytes(damaged)
        database = MediaDatabase(RuntimeEnvironment.getApplication(), name)
        var reported = false
        try {
            database.transaction("local-one") { entities(LocalCollection.LIBRARY) }
        } catch (_: android.database.sqlite.SQLiteDatabaseCorruptException) { reported = true }
        assertTrue("corrupt database was silently recreated", reported)
        assertTrue(file.exists())
        assertArrayEquals(damaged, file.readBytes())
    }
}
