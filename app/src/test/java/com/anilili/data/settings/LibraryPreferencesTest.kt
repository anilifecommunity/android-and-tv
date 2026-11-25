package com.anilili.data.settings

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryPreferencesTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val layout = stringPreferencesKey("app_layout_mode")
    private val migration = intPreferencesKey("app_layout_migration_version")
    private val views = stringPreferencesKey("library_view_names")
    private val hiddenLists = stringPreferencesKey("hidden_library_custom_list_keys")

    @Test fun `each old profile migrates to Legacy once then preserves Cinema`() {
        for (oldValue in listOf(null, "cinema", "legacy", "unknown")) {
            val prefs = mutablePreferencesOf()
            oldValue?.let { prefs[layout] = it }
            SettingsStore.migrateLayoutPreferences(prefs)
            assertEquals("legacy", prefs[layout])
            assertEquals(1, prefs[migration])
            prefs[layout] = "cinema"
            SettingsStore.migrateLayoutPreferences(prefs)
            assertEquals("cinema", prefs[layout])
        }
    }

    @Test fun `restoring old and current backups respects the migration marker`() {
        val prefs = mutablePreferencesOf(migration to 1, layout to "cinema")
        SettingsStore.applyRestoredSettings(prefs, JsonObject(mapOf("app_layout_mode" to JsonPrimitive("cinema"))))
        assertEquals("legacy", prefs[layout])
        SettingsStore.applyRestoredSettings(prefs, JsonObject(mapOf(
            "app_layout_mode" to JsonPrimitive("cinema"),
            "app_layout_migration_version" to JsonPrimitive(1),
            "library_view_names" to JsonPrimitive(""),
            "hidden_library_custom_list_keys" to JsonPrimitive("favorites"),
        )))
        assertEquals("cinema", prefs[layout])
        assertEquals("", prefs[views])
        assertEquals("favorites", prefs[hiddenLists])
    }

    @Test fun `empty selection and hidden lists survive disk reload independently per profile`() = runBlocking {
        val file = temporaryFolder.root.resolve("profile-a.preferences_pb")
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }
        assertNull(store.data.first()[views])
        store.edit {
            SettingsStore.migrateLayoutPreferences(it)
            it[layout] = "cinema"
            it[views] = ""
            it[hiddenLists] = "favorites"
        }
        job.cancelAndJoin()
        val nextJob = SupervisorJob()
        try {
            val reopened = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + nextJob)) { file }
            val restored = reopened.edit { SettingsStore.migrateLayoutPreferences(it) }
            assertEquals("cinema", restored[layout])
            assertEquals("", restored[views])
            assertEquals("favorites", restored[hiddenLists])
            val otherFile = temporaryFolder.root.resolve("profile-b.preferences_pb")
            val other = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + nextJob)) { otherFile }
            val fresh = other.edit { SettingsStore.migrateLayoutPreferences(it) }
            assertEquals("legacy", fresh[layout])
            assertNull(fresh[views])
            assertNull(fresh[hiddenLists])
        } finally {
            nextJob.cancelAndJoin()
        }
    }

    @Test fun `renaming transfers visibility and deleting or recreating removes stale keys`() {
        assertEquals("other,new", updatedHiddenLibraryListKeys("old,other", "old", "new"))
        assertEquals("other", updatedHiddenLibraryListKeys("other,new", "visible", "new"))
        assertEquals("other", updatedHiddenLibraryListKeys("old,other", "old", null))
        assertEquals("old,other", updatedHiddenLibraryListKeys("old,other", "old", "old"))
    }
}
