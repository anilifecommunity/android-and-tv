package com.anilili.data.discord

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscordOAuthRedirectTest {
    @Test
    fun `mobile redirect matches the registered Discord callback`() {
        val expected = "discord-${DiscordAuthManager.APPLICATION_ID}:/authorize/callback"

        assertEquals(expected, DiscordRedirect.MOBILE.uri)
        assertTrue(isDiscordRedirectUrl("$expected?code=answer&state=state", DiscordAuthManager.APPLICATION_ID))
        assertTrue(isDiscordRedirectUrl("$expected?error=access_denied&state=state", DiscordAuthManager.APPLICATION_ID))
    }

    @Test
    fun `unregistered and incomplete callbacks are rejected`() {
        val rejected = listOf(
            "http://127.0.0.1/discord/callback?code=answer&state=state",
            "https://127.0.0.1?code=answer&state=state",
            "discord-${DiscordAuthManager.APPLICATION_ID}:/other?code=answer&state=state",
            "discord-${DiscordAuthManager.APPLICATION_ID}:/authorize/callback?state=state",
            "discord-${DiscordAuthManager.APPLICATION_ID}.evil:/authorize/callback?code=answer&state=state",
        )

        rejected.forEach { assertFalse(it, isDiscordRedirectUrl(it, DiscordAuthManager.APPLICATION_ID)) }
    }
}
