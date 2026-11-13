package com.anilili.data.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OAuthRedirectTest {
    @Test
    fun `production AniList callback is consumed before the web app loads`() {
        val url = "https://anilife.cc/auth/callback.html#access_token=token-1&state=right"

        assertTrue(AuthManager.isRedirect(url))
        assertEquals("token-1", validatedOAuthResult(url, OAuthService.ANILIST, "right", "access_token"))
        assertNull(validatedOAuthResult(url, OAuthService.ANILIST, "wrong", "access_token"))
    }

    @Test
    fun `exact callbacks are consumed even when authorization returns no credential`() {
        assertTrue(AuthManager.isRedirect("https://anilife.cc/auth/callback.html#error=access_denied&state=s"))
        assertTrue(MalAuthManager.isRedirect("https://localhost/?error=access_denied&state=s"))
        assertFalse(AuthManager.isRedirect("https://anilife.cc/auth/other.html#error=access_denied&state=s"))
    }

    @Test
    fun `historical loopback and native callbacks remain supported`() {
        assertEquals(
            "ani-token",
            validatedOAuthResult(
                "http://localhost/#access_token=ani-token&state=s",
                OAuthService.ANILIST,
                "s",
                "access_token",
            ),
        )
        assertEquals(
            "mal-code",
            validatedOAuthResult(
                "anilife://auth/mal?code=mal-code&state=s",
                OAuthService.MAL,
                "s",
                "code",
            ),
        )
    }

    @Test
    fun `lookalike hosts paths and services are rejected`() {
        val rejected = listOf(
            "https://anilife.cc.evil.example/auth/callback.html#access_token=t&state=s",
            "https://anilife.cc/auth/other.html#access_token=t&state=s",
            "http://anilife.cc/auth/callback.html#access_token=t&state=s",
            "anilife://auth/mal?access_token=t&state=s",
        )

        rejected.forEach { url ->
            assertNull(url, validatedOAuthResult(url, OAuthService.ANILIST, "s", "access_token"))
        }
    }
}
