package com.anilili.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateOfferTest {

    @Test
    fun `a new version prompts, with or without a build number`() {
        assertEquals(UpdateOffer.PROMPT, updateOffer("0.1.81", 83, "0.1.80", 82))
        assertEquals(UpdateOffer.PROMPT, updateOffer("0.1.0-beta.2", 2, "0.1.0-beta.1", 1))
        // Every manifest published before build numbers: names alone, as always.
        assertEquals(UpdateOffer.PROMPT, updateOffer("0.1.81", null, "0.1.80", 82))
    }

    @Test
    fun `a rebuild of the installed version is offered quietly`() {
        // The case this exists for: 0.1.80 republished with fixes under a higher build number.
        assertEquals(UpdateOffer.QUIET, updateOffer("0.1.80", 82, "0.1.80", 81))
    }

    @Test
    fun `the same build, or no build number, is nothing new`() {
        assertEquals(UpdateOffer.NONE, updateOffer("0.1.80", 82, "0.1.80", 82))
        assertEquals(UpdateOffer.NONE, updateOffer("0.1.80", null, "0.1.80", 82))
        assertEquals(UpdateOffer.NONE, updateOffer("0.1.79", null, "0.1.80", 82))
    }

    @Test
    fun `a lower build number is never offered, whatever the name says`() {
        // Android refuses a lower versionCode over a higher one, so this could only fail to install.
        assertEquals(UpdateOffer.NONE, updateOffer("0.1.81", 80, "0.1.80", 82))
        assertEquals(UpdateOffer.NONE, updateOffer("0.1.80", 80, "0.1.80", 82))
    }

    @Test
    fun `only a new version or an important rebuild pops up unasked`() {
        assertTrue(promptsUnasked(UpdateOffer.PROMPT, important = false))
        assertTrue(promptsUnasked(UpdateOffer.QUIET, important = true))
        assertFalse(promptsUnasked(UpdateOffer.QUIET, important = false))
        // Important never makes an old or equal build worth offering.
        assertFalse(promptsUnasked(UpdateOffer.NONE, important = true))
    }

    @Test
    fun `a build number is only shown where it tells two builds apart`() {
        assertEquals("v0.1.80 (build 82)", versionLabel("0.1.80", 82))
        assertEquals("v0.1.81", versionLabel("0.1.81", null))
    }

    @Test
    fun `semantic prereleases sort without treating beta labels as equal`() {
        assertTrue(compareAppVersions("0.1.0-beta.2", "0.1.0-beta.1") > 0)
        assertTrue(compareAppVersions("0.1.0", "0.1.0-rc.1") > 0)
        assertTrue(compareAppVersions("0.1.0-beta.10", "0.1.0-beta.2") > 0)
        assertEquals(0, compareAppVersions("0.1.0-beta.1", "0.1.0-beta.1-debug"))
    }

    @Test
    fun `manifest sequence never rolls back after a newer one was accepted`() {
        assertTrue(acceptsManifestSequence(sequence = 8, lastAccepted = 8))
        assertTrue(acceptsManifestSequence(sequence = 9, lastAccepted = 8))
        assertFalse(acceptsManifestSequence(sequence = 7, lastAccepted = 8))
    }

    @Test
    fun `candidate signer must match one trusted installed certificate`() {
        val installed = listOf("old".encodeToByteArray(), "current".encodeToByteArray())
        assertTrue(signersMatch(installed, listOf("current".encodeToByteArray())))
        assertFalse(signersMatch(installed, listOf("other".encodeToByteArray())))
        assertFalse(signersMatch(emptyList(), listOf("current".encodeToByteArray())))
    }

    @Test
    fun `beta opt in chooses the newest valid channel result`() {
        val stable = UpdateManager.UpdateInfo("0.1.0", "", "https://stable", 1, build = 1)
        val beta = UpdateManager.UpdateInfo("0.2.0-beta.1", "", "https://beta", 1, build = 2)
        assertEquals(beta, newestUpdate(listOf(stable, beta)))

        val final = beta.copy(version = "0.2.0", build = 3)
        assertEquals(final, newestUpdate(listOf(beta, final)))
        assertEquals(stable, newestUpdate(listOf(stable)))
    }
}
