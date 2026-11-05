package com.anilili.data.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fixture event is a real Nostr manifest signed by the project update key
 * (see nostr-update-key.properties / scripts/nostr_update.py) with a fixed
 * created_at so the expected id stays stable.
 */
class NostrUpdateEventTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val fixtureJson = """
        {
          "id": "c62f6d32e23173c63d44ad9ed9af342b51380a0fc1ff060f69e3ef61a015a9ad",
          "pubkey": "dc30dfa5ab26f3fa076dd6ce0cdade53fa47e7db6a750908a954084afb06b46c",
          "created_at": 1793736000,
          "kind": 30078,
          "tags": [["d", "anilife-update"]],
          "content": "{\"version\":\"0.1.0-beta.1\",\"versionCode\":1,\"changelog\":\"Initial Anilife signed update channel fixture.\",\"apkUrl\":\"https://github.com/anilifecommunity/android-and-tv/releases/latest/download/Anilife.apk\",\"sizeBytes\":15022786}",
          "sig": "27dd371cb1e60a5fa078203a61e195073bd0a4277e535bf3b72013ab3b6b8fb3a882b94fb66f99fee197f6aeb468f623cb30ffe6c98c7c9859113ceb3a7cc3cf"
        }
    """.trimIndent()

    private fun fixture(): NostrEvent =
        json.parseToJsonElement(fixtureJson).jsonObject.toNostrEvent()

    @Test
    fun eventIdMatchesNip01Serialization() {
        assertEquals(
            "c62f6d32e23173c63d44ad9ed9af342b51380a0fc1ff060f69e3ef61a015a9ad",
            fixture().computeEventIdHex(),
        )
    }

    @Test
    fun correctlySignedManifestIsAccepted() {
        assertTrue(fixture().isValidManifest())
    }

    @Test
    fun tamperedContentIsRejected() {
        val tampered = fixture().copy(content = fixture().content.replace("0.1.0-beta.1", "9.9.9"))
        assertFalse(tampered.isValidManifest())
    }

    @Test
    fun tamperedDTagIsRejected() {
        val tags = kotlinx.serialization.json.buildJsonArray {
            add(kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("d"))
                add(kotlinx.serialization.json.JsonPrimitive("other-app"))
            })
        }
        assertFalse(fixture().copy(tags = tags).isValidManifest())
    }

    @Test
    fun foreignPubkeyIsRejected() {
        assertFalse(
            fixture().copy(pubkey = "0".repeat(64)).isValidManifest(),
        )
    }

    @Test
    fun garbageSignatureIsRejected() {
        assertFalse(fixture().copy(sig = "f".repeat(128)).isValidManifest())
    }

    @Test
    fun manifestParsesIntoUpdateInfo() {
        val info = fixture().parseManifest(json)
        assertEquals("0.1.0-beta.1", info.version)
        assertEquals("Initial Anilife signed update channel fixture.", info.changelog)
        assertEquals("https://github.com/anilifecommunity/android-and-tv/releases/latest/download/Anilife.apk", info.apkUrl)
        assertEquals(15022786L, info.sizeBytes)
    }

    @Test
    fun compareVersionsHandlesNostrVersionFormat() {
        assertTrue(compareAppVersions("0.1.54", "0.1.53") > 0)
        assertEquals(0, compareAppVersions("v0.1.54", "0.1.54"))
    }
}

/**
 * A release otherwise lives or dies with one host, and the host is the part an outsider can take
 * away. Mirrors ride in the manifest rather than the APK so a host can be added, moved or dropped
 * without shipping a build — and a manifest written before the field existed must still parse.
 */
class NostrManifestMirrorTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // Only `content` matters here; parseManifest never looks at the envelope, and signature
    // verification is covered by NostrManifestVerificationTest.
    private fun manifest(content: String) = NostrEvent(
        id = "",
        pubkey = "",
        createdAt = 0,
        kind = NostrUpdateSource.MANIFEST_KIND,
        tags = JsonArray(emptyList()),
        content = content,
        sig = "",
    )

    @Test
    fun `mirrors are read for the variant that was selected`() {
        val info = manifest(
            """
            {"version":"0.1.60","apkUrl":"https://primary/a.apk",
             "variants":{
               "mobile":{"url":"https://primary/a.apk","sizeBytes":10,"mirrors":["https://m1/a.apk"]},
               "tv":{"url":"https://primary/tv.apk","sizeBytes":20,"mirrors":["https://m1/tv.apk"]}}}
            """.trimIndent(),
        )
        assertEquals(listOf("https://m1/tv.apk"), info.parseManifest(json, isTv = true).mirrors)
        assertEquals(listOf("https://m1/a.apk"), info.parseManifest(json, isTv = false).mirrors)
    }

    @Test
    fun `important is read for the variant that was selected`() {
        val info = manifest(
            """
            {"version":"0.1.84","apkUrl":"https://primary/a.apk","versionCode":97,
             "variants":{
               "mobile":{"url":"https://primary/a.apk","versionCode":97},
               "tv":{"url":"https://primary/tv.apk","versionCode":97,"important":true}}}
            """.trimIndent(),
        )
        assertTrue(info.parseManifest(json, isTv = true).important)
        assertFalse(info.parseManifest(json, isTv = false).important)
        // Top level applies to both, and a manifest without the field is not important.
        assertTrue(manifest("""{"version":"0.1.84","apkUrl":"https://p/a.apk","important":true}""").parseManifest(json).important)
        assertFalse(manifest("""{"version":"0.1.84","apkUrl":"https://p/a.apk"}""").parseManifest(json).important)
    }

    @Test
    fun `a manifest without mirrors still parses`() {
        val info = manifest("""{"version":"0.1.60","apkUrl":"https://primary/a.apk","sizeBytes":10}""")
            .parseManifest(json)
        assertTrue(info.mirrors.isEmpty())
        assertEquals("https://primary/a.apk", info.apkUrl)
    }

    @Test
    fun `the primary is tried before any mirror and never twice`() {
        val info = UpdateManager.UpdateInfo(
            version = "0.1.60",
            changelog = "",
            apkUrl = "https://primary/a.apk",
            sizeBytes = 10,
            // A publisher repeating the primary in the mirror list must not make it retry itself.
            mirrors = listOf("https://primary/a.apk", "https://m1/a.apk"),
        )
        assertEquals(listOf("https://primary/a.apk", "https://m1/a.apk"), info.downloadUrls)
    }

    @Test
    fun `canonical manifests enforce expiry checksum form factor and ABI`() {
        val content = """
            {"schema":1,"version":"0.2.0-beta.1","versionCode":2,"sequence":2,
             "expiresAt":2000,"apkUrl":"https://primary/a.apk","sha256":"${"a".repeat(64)}",
             "formFactor":"mobile","abis":["arm64-v8a"]}
        """.trimIndent()
        val info = manifest(content).parseManifest(
            json,
            supportedAbis = listOf("arm64-v8a"),
            nowEpochSeconds = 1000,
        )
        assertEquals(2L, info.sequence)
        assertEquals("a".repeat(64), info.sha256)

        listOf(
            content.replace("\"sequence\":2,", ""),
            content.replace("\"expiresAt\":2000", "\"expiresAt\":999"),
            content.replace("\"${"a".repeat(64)}\"", "\"bad\""),
            content.replace("\"formFactor\":\"mobile\"", "\"formFactor\":\"tv\""),
            content.replace("\"arm64-v8a\"", "\"x86\""),
            content.replace("[\"arm64-v8a\"]", "[]"),
        ).forEach { invalid ->
            assertTrue(
                runCatching {
                    manifest(invalid).parseManifest(
                        json,
                        supportedAbis = listOf("arm64-v8a"),
                        nowEpochSeconds = 1000,
                    )
                }.isFailure,
            )
        }
    }
}
