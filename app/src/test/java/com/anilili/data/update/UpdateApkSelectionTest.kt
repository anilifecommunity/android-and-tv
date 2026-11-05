package com.anilili.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateApkSelectionTest {
    private val assets = listOf(
        "Anilife.apk",
        "Anilife_arm64-v8a.apk",
        "Anilife_armeabi-v7a.apk",
        "Anilife_tv.apk",
        "Anilife_tv_arm64-v8a.apk",
        "Anilife_tv_armeabi-v7a.apk",
    )

    @Test
    fun phoneSelectsOnlyTheMatchingAnilifeFamily() {
        assertEquals(
            "Anilife_arm64-v8a.apk",
            preferredReleaseApkName(assets, listOf("arm64-v8a", "armeabi-v7a"), isTv = false),
        )
        assertEquals("Anilife.apk", preferredReleaseApkName(assets, listOf("x86_64"), isTv = false))
    }

    @Test
    fun tvSelectsOnlyTheMatchingAnilifeFamily() {
        assertEquals(
            "Anilife_tv_armeabi-v7a.apk",
            preferredReleaseApkName(assets, listOf("armeabi-v7a"), isTv = true),
        )
        assertEquals("Anilife_tv.apk", preferredReleaseApkName(assets, listOf("x86"), isTv = true))
    }

    @Test
    fun historicalPublisherAssetNamesAreNotAcceptedAsAnilifeReleases() {
        val historical = listOf("Anilili.apk", "Anilili_arm64-v8a.apk", "Anilili_tv.apk")
        assertNull(preferredReleaseApkName(historical, listOf("arm64-v8a"), isTv = false))
        assertNull(preferredReleaseApkName(historical, listOf("arm64-v8a"), isTv = true))
    }

    @Test
    fun universalPhoneAssetSortsFirst() {
        assertEquals("Anilife.apk", assets.sorted().first())
    }

    @Test
    fun versionComparisonDistinguishesNewerEqualAndOlderReleases() {
        assertEquals(1, compareAppVersions(remote = "0.1.1", installed = "0.1.0"))
        assertEquals(0, compareAppVersions(remote = "0.1.0", installed = "0.1.0"))
        assertEquals(-1, compareAppVersions(remote = "0.0.9", installed = "0.1.0"))
        assertEquals(0, compareAppVersions(remote = "v0.1.0", installed = "0.1.0-debug"))
    }
}
