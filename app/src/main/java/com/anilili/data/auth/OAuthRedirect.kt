package com.anilili.data.auth

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal enum class OAuthService(val path: String) {
    ANILIST("anilist"),
    MAL("mal"),
}

/** Parameters from an exact account callback, or null for every ordinary/navigation URL. */
internal fun oauthCallbackParameters(url: String, service: OAuthService): Map<String, String>? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    val host = uri.host?.lowercase()
    val path = uri.path.orEmpty()
    val allowed = when {
        scheme in setOf("http", "https") && host == "localhost" -> true
        service == OAuthService.ANILIST && scheme == "https" && host == "anilife.cc" &&
            path == "/auth/callback.html" -> true
        scheme == "anilife" && host == "auth" && path == "/${service.path}" -> true
        else -> false
    }
    if (!allowed) return null
    return parseParameters(if (service == OAuthService.ANILIST) uri.rawFragment else uri.rawQuery)
}

internal fun validatedOAuthResult(
    url: String,
    service: OAuthService,
    expectedState: String,
    resultKey: String,
): String? {
    val parameters = oauthCallbackParameters(url, service) ?: return null
    if (parameters["state"] != expectedState) return null
    return parameters[resultKey]?.takeIf(String::isNotBlank)
}

private fun parseParameters(raw: String?): Map<String, String> = raw.orEmpty()
    .split('&')
    .mapNotNull { pair ->
        val separator = pair.indexOf('=')
        if (separator <= 0) return@mapNotNull null
        runCatching {
            decode(pair.substring(0, separator)) to decode(pair.substring(separator + 1))
        }.getOrNull()
    }
    .toMap()

private fun decode(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8.name())
