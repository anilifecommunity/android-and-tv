package com.anilili.data.storage

import com.anilili.data.library.*
import com.anilili.data.model.legacyAndroidAnimeRef
import java.math.BigDecimal
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Captured before legacy stores initialize; callers keep the original profile files untouched. */
internal data class LegacyMediaSnapshot(
    val sections: Map<String, String>,
    val portableSettings: JsonObject = JsonObject(emptyMap()),
    val libraryViewNames: String? = null,
    val hiddenCustomListKeys: String? = null,
) {
    init {
        require(sections.keys.all { it in LEGACY_SECTIONS })
        require(sections.values.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() } +
            portableSettings.toString().toByteArray(Charsets.UTF_8).size +
            (libraryViewNames?.toByteArray(Charsets.UTF_8)?.size ?: 0) +
            (hiddenCustomListKeys?.toByteArray(Charsets.UTF_8)?.size ?: 0) <= 8 * 1024 * 1024)
        portableSettings.forEach { (key, value) -> validatePortableSetting(key, value) }
    }
}

internal fun captureLegacyMedia(
    preferences: Map<String, *>,
    portableSettings: JsonObject = JsonObject(emptyMap()),
    libraryViewNames: String? = null,
    hiddenCustomListKeys: String? = null,
): LegacyMediaSnapshot = LegacyMediaSnapshot(preferences.filterKeys { it in LEGACY_SECTIONS }.mapValues { (key, value) ->
    when {
        value is String -> value
        key == "dismissed_remote_history" && value is Set<*> && value.all { it is String } ->
            JsonArray(value.map { JsonPrimitive(it as String) }.sortedBy { it.content }).toString()
        else -> throw IllegalArgumentException("Unsupported legacy section type: $key")
    }
}, portableSettings, libraryViewNames, hiddenCustomListKeys)

/** A failed projection/count/readback rolls back recovery sections, entities and completion together. */
internal suspend fun MediaDatabase.importLegacyProfile(profileId: String, snapshot: LegacyMediaSnapshot): Boolean =
    transaction(profileId, MutationOrigin.MIGRATION) {
        if (migrationComplete(LEGACY_MIGRATION)) return@transaction false
        val projection = legacyProjection(profileId, snapshot)
        snapshot.sections.forEach { (name, raw) -> retainLegacySection(name, raw) }
        retainLegacySection("portable_settings", snapshot.portableSettings.toString())
        retainLegacySection("library_controls", buildJsonObject {
            snapshot.libraryViewNames?.let { put("libraryViewNames", it) }
            snapshot.hiddenCustomListKeys?.let { put("hiddenCustomListKeys", it) }
        }.toString())
        retainLegacySection("custom_list_ids", projection.listIds.toString())
        projection.entities.forEach { entity ->
            put(entity)
            check(this.entity(entity.collection, entity.key) == entity) { "Legacy migration readback differs" }
        }
        val source = buildJsonObject {
            put("profileId", profileId)
            put("sections", JsonObject(snapshot.sections.toSortedMap().mapValues { JsonPrimitive(it.value) }))
            put("portableSettings", JsonObject(snapshot.portableSettings.toSortedMap()))
            put("libraryViewNames", snapshot.libraryViewNames?.let(::JsonPrimitive) ?: JsonNull)
            put("hiddenCustomListKeys", snapshot.hiddenCustomListKeys?.let(::JsonPrimitive) ?: JsonNull)
        }
        completeMigration(LEGACY_MIGRATION, sha256(source.toString()), projection.entities.groupingBy { it.collection }.eachCount())
        true
    }

internal const val LEGACY_MIGRATION = "android-v1"
private val LEGACY_SECTIONS = setOf("history", "watchlist", "local_watched", "custom_lists", "custom_list_ops",
    "remote_statuses", "remote_scores", "dismissed_remote_history", "pending_saved_sync", "watch_activity_days")
private val legacyJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private data class LegacyProjection(val entities: List<LocalEntity>, val listIds: JsonObject)

private fun legacyProjection(profileId: String, snapshot: LegacyMediaSnapshot): LegacyProjection {
    val library = linkedMapOf<Int, JsonObject>()
    val progress = linkedMapOf<String, LocalEntity>()
    val entities = mutableListOf<LocalEntity>()
    fun metadata(id: Int, title: String, poster: String?, extra: JsonObject = JsonObject(emptyMap())) {
        val previous = library[id].orEmpty()
        library[id] = JsonObject(previous + buildJsonObject {
            put("ref", storageJson.encodeToJsonElement(legacyAndroidAnimeRef(id)))
            put("title", title); put("poster", poster?.let(::JsonPrimitive) ?: JsonNull)
        } + extra)
    }
    fun unit(id: Int, number: Double, resume: EpisodeResume?, completed: Boolean, updatedAt: Long) {
        val ordinal = legacyOrdinal(number)
        val entity = LocalEntity(LocalCollection.PROGRESS, buildJsonObject {
            put("ref", storageJson.encodeToJsonElement(legacyAndroidAnimeRef(id)))
            put("unitId", "legacy-episode:$ordinal"); put("displayOrdinal", ordinal)
            put("completed", completed); put("updatedAt", updatedAt)
            if (!completed && resume != null) { put("positionMs", resume.positionMs); put("durationMs", resume.durationMs) }
        })
        val previous = progress[entity.key]
        require(previous == null || previous == entity) { "Conflicting legacy episode keys" }
        progress[entity.key] = entity
    }
    val saved = snapshot.rows<WatchlistEntry>("watchlist")
    requireUnique(saved.map { it.anilistId }, "saved titles")
    saved.forEach { row -> metadata(row.anilistId, row.title, row.cover, buildJsonObject { put("savedAt", row.addedAt) }) }
    val watched = snapshot.rows<WatchedEntry>("local_watched")
    requireUnique(watched.map { it.anilistId }, "watched titles")
    watched.forEach { row -> metadata(row.anilistId, row.title, row.cover, buildJsonObject { put("watchedAt", row.watchedAt) }) }

    val lists = snapshot.rows<CustomList>("custom_lists")
    requireUnique(lists.map { listKey(it.name) }, "custom list names")
    val ids = linkedMapOf<String, JsonElement>()
    for (list in lists) {
        val nameKey = listKey(list.name)
        val id = "legacy-list-" + sha256(JsonArray(listOf(JsonPrimitive(profileId), JsonPrimitive(nameKey))).toString())
        ids[nameKey] = JsonPrimitive(id)
        entities += LocalEntity(LocalCollection.LIST, buildJsonObject {
            put("id", id); put("name", list.name); put("createdAt", list.createdAt)
        })
        requireUnique(list.entries.map { it.anilistId }, "custom list membership")
        list.entries.forEachIndexed { position, row ->
            if (row.anilistId !in library) metadata(row.anilistId, row.title, row.cover)
            entities += LocalEntity(LocalCollection.LIST_MEMBER, buildJsonObject {
                put("parentId", id); put("ref", storageJson.encodeToJsonElement(legacyAndroidAnimeRef(row.anilistId)))
                put("position", position); put("addedAt", row.addedAt)
            })
        }
    }

    val history = snapshot.rows<HistoryEntry>("history")
    requireUnique(history.map { it.anilistId }, "history titles")
    val historyWire = snapshot.sections["history"]?.let { legacyJson.parseToJsonElement(it).jsonArray }.orEmpty()
    history.forEachIndexed { index, row ->
        val ordinal = legacyOrdinal(row.episodeNumber)
        val boundary = row.inferredWatchedBefore ?: row.episodeNumber.takeIf {
            "watchedEpisodes" !in historyWire[index].jsonObject && it > 1
        }
        val effective = row.copy(inferredWatchedBefore = boundary)
        metadata(row.anilistId, row.title, row.cover, buildJsonObject {
            put("currentUnitId", "legacy-episode:$ordinal"); put("updatedAt", row.updatedAt)
            // Keep the old inference compact; never allocate 1..episodeNumber from an imported number.
            boundary?.let { put("legacyWatchedBefore", legacyOrdinal(it)) }
        })
        val resumes = linkedMapOf<String, Pair<Double, EpisodeResume>>()
        row.resumePoints.forEach { (key, resume) ->
            val number = key.toDoubleOrNull() ?: throw IllegalArgumentException("Invalid legacy resume key")
            val canonical = legacyOrdinal(number)
            require(resumes.put(canonical, number to resume) == null) { "Ambiguous legacy resume keys" }
        }
        // Per-episode clocks take priority over the old single current-episode clock.
        if (ordinal !in resumes) resumes[ordinal] = row.episodeNumber to EpisodeResume(row.positionMs, row.durationMs)
        resumes.values.forEach { (_, resume) ->
            require(resume.positionMs >= 0 && resume.durationMs >= 0)
        }
        val numbers = row.watchedEpisodes + row.unwatchedEpisodes + resumes.values.map { it.first }
        numbers.forEach { number ->
            val completed = effective.isEpisodeWatched(number)
            unit(row.anilistId, number, if (completed) null else resumes[legacyOrdinal(number)]?.second, completed, row.updatedAt)
        }
    }
    snapshot.rows<RemoteListStatus>("remote_statuses").forEach { row ->
        library[row.anilistId]?.let { library[row.anilistId] = JsonObject(it + ("status" to JsonPrimitive(row.status))) }
    }
    snapshot.rows<RemoteListScore>("remote_scores").forEach { row ->
        library[row.anilistId]?.let { library[row.anilistId] = JsonObject(it + ("score" to JsonPrimitive(row.score))) }
    }
    library.values.forEach { entities += LocalEntity(LocalCollection.LIBRARY, it) }
    entities += progress.values
    snapshot.portableSettings.forEach { (key, value) -> entities += setting(LocalCollection.PORTABLE_SETTING, key, value) }
    snapshot.libraryViewNames?.let { entities += setting(LocalCollection.LOCAL_SETTING, "libraryViewNames", JsonPrimitive(it)) }
    snapshot.hiddenCustomListKeys?.let { stored ->
        val hiddenIds = stored.split(',').filter { it.isNotBlank() }.mapNotNull { ids[it] }.distinct()
        entities += setting(LocalCollection.LOCAL_SETTING, "hiddenLibraryListIds", JsonArray(hiddenIds))
    }
    return LegacyProjection(entities, JsonObject(ids))
}

private inline fun <reified T> LegacyMediaSnapshot.rows(key: String): List<T> =
    sections[key]?.let { legacyJson.decodeFromString<List<T>>(it) }.orEmpty()

private fun requireUnique(keys: List<Any>, label: String) { require(keys.size == keys.toSet().size) { "Duplicate legacy $label" } }
private fun setting(type: LocalCollection, key: String, value: JsonElement) =
    LocalEntity(type, buildJsonObject { put("key", key); put("value", value) })
private fun legacyOrdinal(number: Double): String {
    require(number.isFinite() && number >= 0)
    return BigDecimal.valueOf(number).stripTrailingZeros().toPlainString().also { require(it.length <= 64) }
}
private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
