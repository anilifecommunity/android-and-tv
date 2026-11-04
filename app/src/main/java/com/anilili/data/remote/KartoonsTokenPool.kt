package com.anilili.data.remote

import com.anilili.BuildConfig
import com.anilili.diagnostics.DiagnosticsLog
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Network-free token rotation with sticky selection, cooldown escalation, and ban handling. */
internal class KartoonsTokenRotator(
    tokens: List<String>,
    private val now: () -> Long = System::currentTimeMillis,
    random: Random = Random.Default,
) {
    private data class State(var failures: Int = 0, var cooldownUntil: Long = 0, var banned: Boolean = false)

    private val states = LinkedHashMap<String, State>()
    @Volatile private var sticky: String? = null

    init {
        tokens.filter(String::isNotBlank).distinct().shuffled(random).forEach { states[it] = State() }
    }

    @Synchronized
    fun pick(): String? {
        sticky?.let { current ->
            val state = states[current]
            if (state != null && !state.banned && now() >= state.cooldownUntil) return current
        }
        val candidate = states.entries
            .firstOrNull { (_, state) -> !state.banned && now() >= state.cooldownUntil }
            ?.key ?: return null
        sticky = candidate
        return candidate
    }

    @Synchronized
    fun markFailed(token: String) {
        val state = states[token] ?: return
        state.failures++
        state.cooldownUntil = now() + COOLDOWNS[(state.failures - 1).coerceIn(0, COOLDOWNS.lastIndex)]
        if (sticky == token) sticky = null
    }

    @Synchronized
    fun markBanned(token: String) {
        val state = states[token] ?: return
        state.banned = true
        if (sticky == token) sticky = null
    }

    @Synchronized
    fun addAll(tokens: Collection<String>) {
        tokens.filter(String::isNotBlank).forEach { states.putIfAbsent(it, State()) }
    }

    @Synchronized
    fun hasHealthy(): Boolean = states.values.any { !it.banned && now() >= it.cooldownUntil }

    private companion object {
        val COOLDOWNS = listOf(
            TimeUnit.MINUTES.toMillis(10),
            TimeUnit.HOURS.toMillis(1),
            TimeUnit.HOURS.toMillis(6),
            TimeUnit.HOURS.toMillis(48),
        )
    }
}

/**
 * Kartoons account-token failover without committed shared credentials.
 *
 * Tokens may be supplied to a local build through `KARTOONS_ACCOUNT_TOKENS` as a comma or
 * newline-separated list. They are still extractable from that APK, so only accounts explicitly
 * authorised for distribution may be used. An optional mirror can refresh the list at runtime.
 */
internal class KartoonsTokenPool(
    private val client: OkHttpClient,
    private val json: Json,
    private val scope: CoroutineScope,
    tokens: List<String> = configuredTokens(BuildConfig.KARTOONS_ACCOUNT_TOKENS),
    private val mirrorUrl: String = BuildConfig.KARTOONS_TOKENS_MIRROR_URL,
) {
    private val rotator = KartoonsTokenRotator(tokens)
    private val mirrorStarted = AtomicBoolean(false)
    private val mirrorRetriedOnExhaustion = AtomicBoolean(false)

    fun pick(): String {
        if (mirrorUrl.isNotBlank() && mirrorStarted.compareAndSet(false, true)) {
            scope.launch { refreshFromMirror(forced = false) }
        }
        rotator.pick()?.let { return it }
        if (mirrorUrl.isNotBlank() && mirrorRetriedOnExhaustion.compareAndSet(false, true)) {
            refreshFromMirror(forced = true)
        }
        return rotator.pick() ?: error("Kartoons has no usable authorised account token")
    }

    fun markFailed(token: String) {
        DiagnosticsLog.event(
            category = "provider",
            name = "kartoons_token_refused",
            attributes = mapOf("token" to describe(token)),
        )
        rotator.markFailed(token)
    }

    fun markBanned(token: String) {
        DiagnosticsLog.event(
            category = "provider",
            name = "kartoons_token_banned",
            attributes = mapOf("token" to describe(token)),
        )
        rotator.markBanned(token)
    }

    private fun refreshFromMirror(forced: Boolean) {
        if (mirrorUrl.isBlank()) return
        val fetched = runCatching {
            val request = Request.Builder()
                .url(mirrorUrl)
                .header("Accept", "application/json")
                .cacheControl(CacheControl.FORCE_NETWORK)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                KartoonsParser.parseTokenPool(json, response.body?.string().orEmpty())
            }
        }
            .onFailure { DiagnosticsLog.throwable("Kartoons token mirror failed", it) }
            .getOrNull()
            .orEmpty()
        if (fetched.isNotEmpty()) rotator.addAll(fetched)
        DiagnosticsLog.event(
            category = "provider",
            name = "kartoons_token_mirror",
            attributes = mapOf("count" to fetched.size, "forced" to forced),
        )
    }

    private fun describe(token: String) = token.take(4) + "..."

    private companion object {
        fun configuredTokens(raw: String): List<String> = raw
            .split(',', '\n', '\r')
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
    }
}
