package com.anilili.data.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the Nostr fallback against a real manifest signed by the Anilife release key.
 *
 * The event below is a local, non-published fixture made during the Anilife key ceremony. It has
 * a real BIP-340 signature and exercises the whole chain the app runs:
 * NIP-01 id re-serialization, the d-tag check, and the secp256k1 Schnorr verification.
 *
 * This is the test that would have caught the release being silently broken: R8 had shrunk the
 * secp256k1 JNI backend away, `Secp256k1.verifySchnorr` threw ExceptionInInitializerError, and
 * `runCatching { ... }.getOrDefault(false)` turned that into "signature invalid" — so every
 * release build rejected every manifest and the channel was inert.
 */
class NostrManifestVerificationTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val liveEventJson = """{"id":"c62f6d32e23173c63d44ad9ed9af342b51380a0fc1ff060f69e3ef61a015a9ad","pubkey":"dc30dfa5ab26f3fa076dd6ce0cdade53fa47e7db6a750908a954084afb06b46c","created_at":1793736000,"kind":30078,"tags":[["d","anilife-update"]],"content":"{\"version\":\"0.1.0-beta.1\",\"versionCode\":1,\"changelog\":\"Initial Anilife signed update channel fixture.\",\"apkUrl\":\"https://github.com/anilifecommunity/android-and-tv/releases/latest/download/Anilife.apk\",\"sizeBytes\":15022786}","sig":"27dd371cb1e60a5fa078203a61e195073bd0a4277e535bf3b72013ab3b6b8fb3a882b94fb66f99fee197f6aeb468f623cb30ffe6c98c7c9859113ceb3a7cc3cf"}"""

    private fun event() = json.parseToJsonElement(liveEventJson).jsonObject.toNostrEvent()

    @Test
    fun `the published manifest event passes full NIP-01 verification`() {
        val event = event()
        assertEquals(NostrUpdateSource.MANIFEST_PUBKEY_HEX, event.pubkey)
        assertEquals(NostrUpdateSource.MANIFEST_KIND, event.kind)
        // Re-serializing the event exactly as NIP-01 specifies must reproduce its id.
        assertEquals(event.id, event.computeEventIdHex())
        // ...and the Schnorr signature must check out against the project key.
        assertTrue("live manifest must verify", event.isValidManifest())
    }

    @Test
    fun `the manifest parses into an installable update`() {
        val info = event().parseManifest(json)
        assertEquals("0.1.0", info.version)
        assertTrue("apk must be fetched over TLS", info.apkUrl.startsWith("https://"))
        assertTrue("size should be populated", info.sizeBytes > 0)
    }

    /** Manifests published before the flavor split have no variants; a TV must still update. */
    @Test
    fun `a legacy manifest still gives a TV something to install`() {
        val info = event().parseManifest(json, isTv = true)
        assertEquals("0.1.0", info.version)
        assertTrue("falls back to the single apkUrl", info.apkUrl.endsWith("Anilife.apk"))
    }

    @Test
    fun `each form factor takes its own variant when the manifest offers them`() {
        val withVariants = event().copy(
            content = """
                {"version":"0.1.56","apkUrl":"https://example.test/Anilife.apk","sizeBytes":11,
                 "variants":{
                   "mobile":{"url":"https://example.test/Anilife.apk","sizeBytes":11},
                   "tv":{"url":"https://example.test/Anilife_tv.apk","sizeBytes":22}}}
            """.trimIndent(),
        )
        val tv = withVariants.parseManifest(json, isTv = true)
        assertEquals("https://example.test/Anilife_tv.apk", tv.apkUrl)
        assertEquals(22L, tv.sizeBytes)

        val phone = withVariants.parseManifest(json, isTv = false)
        assertEquals("https://example.test/Anilife.apk", phone.apkUrl)
        assertEquals(11L, phone.sizeBytes)
    }

    @Test
    fun `the build number is read when published and absent otherwise`() {
        assertEquals(1, event().parseManifest(json).build)
        val withBuild = event().copy(
            content = """
                {"version":"0.1.80","versionCode":82,"apkUrl":"https://example.test/Anilife.apk",
                 "variants":{"tv":{"url":"https://example.test/Anilife_tv.apk","versionCode":83}}}
            """.trimIndent(),
        )
        assertEquals(82, withBuild.parseManifest(json, isTv = false).build)
        // A variant's own number wins, like its URL and size do.
        assertEquals(83, withBuild.parseManifest(json, isTv = true).build)
    }

    @Test
    fun `a manifest with only a mobile variant does not strand a TV`() {
        val mobileOnly = event().copy(
            content = """
                {"version":"0.1.56","apkUrl":"https://example.test/Anilife.apk",
                 "variants":{"mobile":{"url":"https://example.test/Anilife.apk"}}}
            """.trimIndent(),
        )
        assertEquals(
            "https://example.test/Anilife.apk",
            mobileOnly.parseManifest(json, isTv = true).apkUrl,
        )
    }

    @Test
    fun `a tampered manifest is rejected`() {
        // Same signature, different content: the id no longer matches, so it must not be trusted.
        val forged = event().copy(content = event().content.replace("0.1.0-beta.1", "9.9.9"))
        assertFalse("content tampering must fail verification", forged.isValidManifest())
    }

    @Test
    fun `an event from another key is rejected`() {
        val impostor = event().copy(pubkey = "0".repeat(64))
        assertFalse("only the project key is trusted", impostor.isValidManifest())
    }
}
