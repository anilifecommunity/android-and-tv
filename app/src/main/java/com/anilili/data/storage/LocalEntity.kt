package com.anilili.data.storage

import com.anilili.data.model.MediaRef
import com.anilili.data.model.MediaUnitRef
import java.net.URI
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

internal enum class LocalCollection(val table: String, val fields: Set<String>, val portable: Boolean = true) {
    LIBRARY("library_items", setOf("ref", "title", "poster", "isAdult", "savedAt", "watchedAt", "status", "score", "currentUnitId", "updatedAt")),
    PROGRESS("unit_progress", setOf("ref", "unitId", "displayOrdinal", "sourceOrdinal", "sourceSeason", "absoluteOrdinal", "positionMs", "durationMs", "completed", "readingPage", "readingFraction", "textOffset", "sectionId", "updatedAt")),
    LIST("custom_lists", setOf("id", "name", "createdAt", "updatedAt")),
    GROUP("groups", setOf("id", "name", "coverMode", "coverAssetId", "createdAt", "updatedAt")),
    LIST_MEMBER("list_memberships", setOf("parentId", "ref", "position", "addedAt", "updatedAt")),
    GROUP_MEMBER("group_memberships", setOf("parentId", "ref", "position", "addedAt", "updatedAt")),
    PORTABLE_SETTING("portable_settings", setOf("key", "value")),
    LOCAL_SETTING("local_settings", setOf("key", "value"), false),
}

/** Payloads here contain portable state/metadata; credentials and legacy opaque sections have no path into the outbox. */
internal data class LocalEntity(val collection: LocalCollection, val value: JsonObject) {
    val ref = value["ref"]?.let { storageJson.decodeFromJsonElement<MediaRef>(it) }
    val parentId = value["parentId"]?.jsonPrimitive?.content?.also(::validateLocalId)
    val key: String = when (collection) {
        LocalCollection.LIBRARY -> requireNotNull(ref).target.let { "${it.kind}:${it.mediaKey}" }
        LocalCollection.PROGRESS -> {
            val unit = MediaUnitRef(requireNotNull(ref).target, value.getValue("unitId").jsonPrimitive.content)
            "${unit.media.kind}:${unit.media.mediaKey}:unit:${unit.unitId}"
        }
        LocalCollection.LIST_MEMBER, LocalCollection.GROUP_MEMBER ->
            requireNotNull(ref).target.let { "${requireNotNull(parentId)}:${it.kind}:${it.mediaKey}" }
        LocalCollection.LIST, LocalCollection.GROUP -> value.getValue("id").jsonPrimitive.content.also(::validateLocalId)
        LocalCollection.PORTABLE_SETTING, LocalCollection.LOCAL_SETTING -> value.getValue("key").jsonPrimitive.content
    }

    init {
        require(value.keys.all { it in collection.fields } && key.length in 1..512)
        require(value.toString().toByteArray(Charsets.UTF_8).size <= MAX_ENTITY_BYTES)
        for (field in setOf("positionMs", "durationMs", "readingPage", "textOffset", "sourceSeason", "position", "addedAt", "createdAt", "updatedAt", "savedAt", "watchedAt")) {
            value[field]?.takeUnless { it == JsonNull }?.let { require(!it.jsonPrimitive.isString && it.jsonPrimitive.longOrNull?.let { n -> n in 0..MAX_JSON_INTEGER } == true) }
        }
        value["readingFraction"]?.takeUnless { it == JsonNull }?.let { require(!it.jsonPrimitive.isString && it.jsonPrimitive.doubleOrNull?.let { n -> n.isFinite() && n in 0.0..1.0 } == true) }
        value["score"]?.takeUnless { it == JsonNull }?.let { require(!it.jsonPrimitive.isString && it.jsonPrimitive.doubleOrNull?.let { n -> n.isFinite() && n in 0.0..10.0 } == true) }
        value["completed"]?.let { require(!it.jsonPrimitive.isString && it.jsonPrimitive.booleanOrNull != null) }
        value["isAdult"]?.takeUnless { it == JsonNull }?.let { require(!it.jsonPrimitive.isString && it.jsonPrimitive.booleanOrNull != null) }
        for (field in setOf("poster", "status", "currentUnitId", "displayOrdinal", "absoluteOrdinal", "sourceOrdinal", "sectionId", "coverMode", "coverAssetId")) {
            value[field]?.takeUnless { it == JsonNull }?.let { require(it.jsonPrimitive.isString && it.jsonPrimitive.content.length <= 2048) }
        }
        for (field in setOf("displayOrdinal", "absoluteOrdinal")) value[field]?.takeUnless { it == JsonNull }?.let {
            require(it.jsonPrimitive.content.length <= 64 && Regex("-?[0-9]+(?:\\.[0-9]+)?").matches(it.jsonPrimitive.content))
        }
        value["sourceOrdinal"]?.takeUnless { it == JsonNull }?.let { require(it.jsonPrimitive.content.length <= 128) }
        for (field in setOf("title", "name")) value[field]?.let { require(it.jsonPrimitive.isString && it.jsonPrimitive.content.length in 1..1024) }
        value["coverAssetId"]?.takeUnless { it == JsonNull }?.let { validateLocalId(it.jsonPrimitive.content) }
        if (collection == LocalCollection.LIBRARY) require("title" in value)
        if (collection in setOf(LocalCollection.LIST, LocalCollection.GROUP)) require("name" in value)
        if (collection == LocalCollection.PORTABLE_SETTING) validatePortableSetting(key, value.getValue("value"))
        if (collection == LocalCollection.LOCAL_SETTING) {
            require(key in LOCAL_SETTING_KEYS)
            if (key == "hiddenLibraryListIds") {
                val ids = value.getValue("value").jsonArray
                ids.forEach { require(it.jsonPrimitive.isString); validateLocalId(it.jsonPrimitive.content) }
            } else require(value.getValue("value").jsonPrimitive.isString)
        }
    }

    fun portableValue(): JsonObject {
        require(collection.portable)
        val portable = value.filter { (field, payload) ->
            field != "poster" || payload == JsonNull || runCatching {
                URI(payload.jsonPrimitive.content).let { it.scheme == "https" && it.host != null && it.userInfo == null }
            }.getOrDefault(false)
        }
        if (ref == null) return JsonObject(portable)
        // A cloud reference retains the canonical/source identity; secondary tracker evidence stays local.
        val aliases = if (ref.mediaKey.startsWith("src-")) emptyList() else ref.aliases.filter { alias ->
            alias.verified && when (alias.namespace) {
                "anilist:anime", "anilist:manga" -> ref.mediaKey == "al-${alias.id.content}"
                "tmdb:movie" -> ref.mediaKey == "tmdb-movie-${alias.id.content}"
                "tmdb:tv" -> ref.mediaKey == "tmdb-tv-${alias.id.content}"
                else -> false
            }
        }
        return JsonObject(portable + ("ref" to storageJson.encodeToJsonElement(ref.copy(aliases = aliases))))
    }
}

internal val storageJson = Json { encodeDefaults = true }
internal const val MAX_ENTITY_BYTES = 262144
internal const val MAX_OPERATION_BYTES = 10240
private const val MAX_JSON_INTEGER = 9_007_199_254_740_991L
private val LOCAL_ID = Regex("[A-Za-z0-9._-]{1,128}")
internal fun validateLocalId(id: String) { require(LOCAL_ID.matches(id)) }
private val LOCAL_SETTING_KEYS = setOf("libraryViewNames", "hiddenLibraryListIds", "selectedMediaMode", "selectedStreamingFilter", "selectedReadingFilter")
private val BOOLEAN_SETTINGS = setOf("autoPlayNext", "autoSkipIntro", "skipFiller", "showSkipButton", "subtitlesOnDub", "showSubtitlesByDefault", "hideAdultContent", "displayRelatedManga", "blurSpoilers")

internal fun validatePortableSetting(key: String, value: JsonElement) {
    val primitive = value as? JsonPrimitive ?: throw IllegalArgumentException("Portable setting must be a scalar")
    require(primitive != JsonNull)
    when (key) {
        in BOOLEAN_SETTINGS -> require(!primitive.isString && primitive.booleanOrNull != null)
        "titleLanguage" -> require(primitive.isString && primitive.content in setOf("english", "romaji", "native"))
        "defaultAudioMode" -> require(primitive.isString && primitive.content in setOf("SUB", "DUB"))
        "defaultQuality" -> require(primitive.isString && primitive.content in setOf("auto", "360p", "480p", "720p", "1080p"))
        "preferredSubLanguage", "secondChoiceSubtitles" -> require(primitive.isString && primitive.content.length in 2..32)
        "themePreset" -> require(primitive.isString && primitive.content.length in 1..32)
        "accentColor" -> require(primitive.isString && Regex("#[0-9a-fA-F]{6}").matches(primitive.content))
        "watchedThreshold" -> require(!primitive.isString && primitive.doubleOrNull?.let { it.isFinite() && it in 50.0..100.0 } == true)
        "skipSeconds" -> require(!primitive.isString && primitive.intOrNull?.let { it in 5..180 } == true)
        else -> throw IllegalArgumentException("Setting is not portable")
    }
}
