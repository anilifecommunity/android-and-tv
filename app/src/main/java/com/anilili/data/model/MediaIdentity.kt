@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.anilili.data.model

import java.security.MessageDigest
import java.time.OffsetDateTime
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

// Wire/hash authority: media-index v2-contract-2.0.0, source 7acf098c792fe53c280d98ef360ff4ea1f725bbd.
@Serializable
enum class MediaKind { ANIME, MOVIE, TV, MANGA, COMIC, NOVEL }

@Serializable
data class MediaTarget(val kind: MediaKind, val mediaKey: String) {
    init { validateMediaKey(kind, mediaKey) }
}

@Serializable
data class IdentityProvenance(
    val source: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val capturedAt: String? = null,
) {
    init {
        require(source.codePointCount(0, source.length) in 1..128)
        capturedAt?.let { OffsetDateTime.parse(it) }
    }
}

@Serializable
data class MediaSourceRef(
    val engine: String,
    val repositoryId: String,
    val providerId: String,
    val stableItemId: String,
    val provenance: IdentityProvenance,
) {
    init {
        for (value in listOf(engine, repositoryId, providerId, stableItemId)) {
            require(value.codePointCount(0, value.length) in 1..512 && Charsets.UTF_8.newEncoder().canEncode(value))
            require(value.none { it.isWhitespace() || it == '\u0085' || it.code < 32 || it in "?#@" })
            require(!value.startsWith("http:", true) && !value.startsWith("https:", true))
        }
    }

    fun mediaKey(kind: MediaKind): String {
        val bytes = JsonArray(listOf(engine, repositoryId, providerId, kind.name, stableItemId).map(::JsonPrimitive))
            .toString().toByteArray(Charsets.UTF_8)
        return "src-" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

@Serializable
data class MediaAlias(
    val namespace: String,
    val id: JsonPrimitive,
    val verified: Boolean,
    val provenance: IdentityProvenance,
) {
    init {
        require(namespace in ALIAS_KINDS)
        if (namespace == "imdb") require(id.isString && IMDB_ID.matches(id.content))
        else require(!id.isString && id.longOrNull?.let { it in 1..MAX_ID } == true)
    }
}

@Serializable
data class MediaRef(
    val kind: MediaKind,
    val mediaKey: String,
    val aliases: List<MediaAlias>,
    val format: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val sourceRef: MediaSourceRef? = null,
) {
    init {
        validateMediaKey(kind, mediaKey)
        require(format == null || format.codePointCount(0, format.length) <= 32)
        require(aliases.size <= 32 && aliases.distinctBy { it.namespace to it.id }.size == aliases.size)
        require(aliases.all { kind in ALIAS_KINDS.getValue(it.namespace) })
        if (mediaKey.startsWith("src-")) require(sourceRef?.mediaKey(kind) == mediaKey)
        else require(aliases.any { it.verified && canonicalMediaKey(kind, it.namespace, it.id) == mediaKey })
        require(kind != MediaKind.ANIME || format !in setOf("MANGA", "NOVEL", "ONE_SHOT"))
        require(kind != MediaKind.MANGA || format != "NOVEL")
        require(kind != MediaKind.NOVEL || !mediaKey.startsWith("al-") || format == "NOVEL")
    }

    val target: MediaTarget get() = MediaTarget(kind, mediaKey)

    /** Only the historical canonical anime boundary; secondary/source aliases do not rename rows. */
    fun anilistAnimeIdOrNull(): Int? = mediaKey.removePrefix("al-").toIntOrNull()
        ?.takeIf { kind == MediaKind.ANIME && mediaKey == "al-$it" }
}

@Serializable
data class MediaUnitRef(val media: MediaTarget, val unitId: String) {
    init { require(UNIT_ID.matches(unitId)) }
}

/** Current Android v1 IDs; imported predecessor/source backups need their own explicit origin. */
fun legacyAndroidAnimeRef(id: Int): MediaRef {
    require(id != 0)
    val evidence = IdentityProvenance("android-v1-local-identity")
    if (id > 0) return MediaRef(MediaKind.ANIME, "al-$id",
        listOf(MediaAlias("anilist:anime", JsonPrimitive(id), true, evidence)))
    val onePace = id == -1_000_000_000
    val source = MediaSourceRef("anilife", "builtin-v1", if (onePace) "onepace" else "hanime",
        if (onePace) "onepace" else (-id.toLong()).toString(), evidence)
    return MediaRef(MediaKind.ANIME, source.mediaKey(MediaKind.ANIME), emptyList(), sourceRef = source)
}

/** Missing reading format remains unresolved; it cannot silently become a manga or novel. */
fun anilistMediaKind(type: String, format: String?): MediaKind? = when (type) {
    "ANIME" -> MediaKind.ANIME
    "MANGA" -> when (format) { "NOVEL" -> MediaKind.NOVEL; "MANGA", "ONE_SHOT" -> MediaKind.MANGA; else -> null }
    else -> null
}

private const val MAX_ID = 9_007_199_254_740_991L
private val KEY = Regex("(?:al-[1-9][0-9]*|tmdb-movie-[1-9][0-9]*|tmdb-tv-[1-9][0-9]*|src-[0-9a-f]{64})")
private val IMDB_ID = Regex("tt[0-9]{7,10}")
private val UNIT_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}")
private val ALIAS_KINDS = mapOf(
    "anilist:anime" to setOf(MediaKind.ANIME), "mal:anime" to setOf(MediaKind.ANIME),
    "anilist:manga" to setOf(MediaKind.MANGA, MediaKind.NOVEL), "mal:manga" to setOf(MediaKind.MANGA, MediaKind.NOVEL),
    "tmdb:movie" to setOf(MediaKind.ANIME, MediaKind.MOVIE), "tmdb:tv" to setOf(MediaKind.ANIME, MediaKind.TV),
    "imdb" to setOf(MediaKind.ANIME, MediaKind.MOVIE, MediaKind.TV),
)

private fun validateMediaKey(kind: MediaKind, key: String) {
    require(key.length <= 96 && KEY.matches(key))
    if (key.startsWith("src-")) return
    val prefix = when (kind) {
        MediaKind.ANIME, MediaKind.MANGA, MediaKind.NOVEL -> "al-"
        MediaKind.MOVIE -> "tmdb-movie-"
        MediaKind.TV -> "tmdb-tv-"
        MediaKind.COMIC -> throw IllegalArgumentException("Comics require a source identity")
    }
    require(key.startsWith(prefix) && key.removePrefix(prefix).toLongOrNull()?.let { it in 1..MAX_ID } == true)
}

private fun canonicalMediaKey(kind: MediaKind, namespace: String, id: JsonPrimitive): String? {
    if (id.isString || id.longOrNull?.let { it in 1..MAX_ID } != true) return null
    return when {
        namespace == "anilist:anime" && kind == MediaKind.ANIME -> "al-${id.content}"
        namespace == "anilist:manga" && kind in setOf(MediaKind.MANGA, MediaKind.NOVEL) -> "al-${id.content}"
        namespace == "tmdb:movie" && kind == MediaKind.MOVIE -> "tmdb-movie-${id.content}"
        namespace == "tmdb:tv" && kind == MediaKind.TV -> "tmdb-tv-${id.content}"
        else -> null
    }
}
