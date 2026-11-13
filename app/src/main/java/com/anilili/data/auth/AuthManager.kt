package com.anilili.data.auth

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.anilili.BuildConfig
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.anilili.data.reminder.AniListNotificationPushManager
import com.anilili.data.reminder.AiringPollScheduler
import com.anilili.data.reminder.ReleaseSyncScheduler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.intOrNull

/**
 * AniList OAuth (Implicit Grant). The login WebView intercepts the registered callback before its
 * page loads. No client secret is stored in the app. Tokens are long-lived (~1 year; AniList has
 * no refresh).
 */
object AuthManager {
    val CLIENT_ID: String get() = BuildConfig.ANILIST_CLIENT_ID
    private const val AUTHORIZE_ENDPOINT = "https://anilist.co/api/v2/oauth/authorize"

    private lateinit var tokenStore: SecureTokenStore
    private lateinit var pendingStore: SecureTokenStore
    private lateinit var appContext: Context

    private val _token = MutableStateFlow<String?>(null)
    val token = _token.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        tokenStore = SecureTokenStore(appContext)
        pendingStore = SecureTokenStore(appContext, SecureTokenStore.KEY_ANILIST_PENDING_AUTH)
        _token.value = tokenStore.load()?.takeUnless(::isJwtExpired)
        if (_token.value == null) tokenStore.clear()
    }

    fun current(): String? {
        val value = _token.value ?: return null
        if (!isJwtExpired(value)) return value
        clearToken(scheduleSync = false)
        return null
    }

    val isLoggedIn: Boolean get() = current() != null
    val isConfigured: Boolean get() = CLIENT_ID.isNotBlank()
    fun viewerId(): Int? = current()?.let(::jwtSubject)

    fun authorizeUrl(): String {
        check(isConfigured) { "AniList is not configured for this Anilife build." }
        val state = ByteArray(24).also(SecureRandom()::nextBytes)
            .let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }
        pendingStore.save(state)
        // AniList's implicit flow uses the redirect registered for the client. Supplying the same
        // redirect explicitly currently makes its post-login grant step fail with unsupported_grant_type.
        return Uri.parse(AUTHORIZE_ENDPOINT).buildUpon()
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("response_type", "token")
            .appendQueryParameter("state", state)
            .build()
            .toString()
    }

    fun setToken(token: String) {
        val normalized = token.trim()
        require(normalized.isNotEmpty()) { "AniList returned an empty token" }
        tokenStore.save(normalized)
        _token.value = normalized
        pendingStore.clear()
        AniListNotificationPushManager.clearDelivered(appContext)
        ReleaseSyncScheduler.runNow(appContext)
        AiringPollScheduler.runNow(appContext)
    }

    fun logout() {
        clearToken(scheduleSync = true)
    }

    /**
     * True once a redirect URL carries the token; extract it with [extractToken].
     *
     * The production web callback is accepted as well as the historical loopback and native
     * callback. State is still mandatory, so a lookalike page cannot inject a token.
     */
    fun isRedirect(url: String): Boolean {
        return oauthCallbackParameters(url, OAuthService.ANILIST) != null
    }

    fun extractToken(url: String): String? {
        val expectedState = pendingStore.load() ?: return null
        return tokenFrom(url, expectedState)
    }

    /**
     * A login run on behalf of a *paired television*, which needs its own AniList token rather
     * than a copy of this device's: AniList issues a fresh one per authorization, so each device
     * can be signed out without disturbing the other.
     *
     * The caller owns [state]. The local login keeps its own encrypted pending state, and a pairing
     * must not overwrite it — cancelling one would otherwise invalidate the other.
     */
    fun pairingAuthorizeUrl(state: String): String {
        check(isConfigured) { "AniList is not configured for this Anilife build." }
        return Uri.parse(AUTHORIZE_ENDPOINT).buildUpon()
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("response_type", "token")
            .appendQueryParameter("state", state)
            .build()
            .toString()
    }

    /** The token from a pairing redirect. Never stored here — it belongs to the television. */
    fun extractPairingToken(url: String, state: String): String? = tokenFrom(url, state)

    fun newOAuthState(): String = ByteArray(24).also(SecureRandom()::nextBytes)
        .let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }

    fun cancelPendingAuthorization() = pendingStore.clear()

    private fun tokenFrom(url: String, expectedState: String): String? {
        return validatedOAuthResult(url, OAuthService.ANILIST, expectedState, "access_token")
    }

    private fun clearToken(scheduleSync: Boolean) {
        tokenStore.clear()
        pendingStore.clear()
        _token.value = null
        AniListNotificationPushManager.clearDelivered(appContext)
        if (scheduleSync) ReleaseSyncScheduler.runNow(appContext)
    }
}

private val jwtJson = Json { ignoreUnknownKeys = true }

internal fun isJwtExpired(token: String, nowEpochSeconds: Long = System.currentTimeMillis() / 1_000): Boolean {
    val expiration = jwtLongClaim(token, "exp") ?: return false
    return expiration <= nowEpochSeconds + TOKEN_EXPIRY_SKEW_SECONDS
}

internal fun jwtSubject(token: String): Int? = jwtLongClaim(token, "sub")?.toInt()

private fun jwtLongClaim(token: String, name: String): Long? = runCatching {
    val payload = token.split('.').getOrNull(1) ?: return@runCatching null
    val decoded = com.anilili.util.Base64Compat.decode(payload)
    val value = jwtJson.parseToJsonElement(decoded.toString(Charsets.UTF_8)).jsonObject[name]?.jsonPrimitive
    value?.longOrNull ?: value?.intOrNull?.toLong()
}.getOrNull()

private const val TOKEN_EXPIRY_SKEW_SECONDS = 60L
