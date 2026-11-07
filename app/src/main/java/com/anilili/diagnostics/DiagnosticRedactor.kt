package com.anilili.diagnostics

import java.net.URI

/** Central privacy boundary for every diagnostic sink and exported report. */
internal object DiagnosticRedactor {
    private const val REDACTED = "<redacted>"
    private const val MAX_ATTRIBUTES = 48
    private const val MAX_ATTRIBUTE_VALUE = 512

    private val urlPattern = Regex("(?i)\\b(?:https?|wss?)://[^\\s\\]})>,\"']+")
    private val bearerPattern = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+\\-/]+=*")
    private val emailPattern = Regex("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b")
    private val jwtPattern = Regex("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b")
    private val sensitiveAssignment = Regex(
        pattern = "(?i)([\\\"']?)(authorization|cookie|set-cookie|access[_-]?token|refresh[_-]?token|token|" +
            "password|passwd|secret|query|title|slug|url|uri|referer|mediaId|data|body)\\1" +
            "\\s*([=:])\\s*(\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;}]+)",
    )
    private val sensitiveFreeTextAssignment = Regex(
        pattern = "(?i)\\b(query|title|slug|data|body)\\s*([=:])\\s*[^,;}\\r\\n]*",
    )
    private val sensitiveKeys = Regex(
        "(?i)^(authorization|cookie|set-cookie|access[_-]?token|refresh[_-]?token|token|" +
            "password|passwd|secret|query|title|slug|url|uri|referer|mediaId|data|body)$",
    )
    private val attributeKey = Regex("^[A-Za-z][A-Za-z0-9_.-]{0,63}$")
    private val correlationValue = Regex("^[A-Za-z0-9_-]{1,64}$")

    fun redactText(value: String): String {
        var redacted = value
        redacted = urlPattern.replace(redacted) { match -> redactUrl(match.value) }
        redacted = bearerPattern.replace(redacted, "Bearer $REDACTED")
        redacted = jwtPattern.replace(redacted, REDACTED)
        redacted = emailPattern.replace(redacted, "<redacted-email>")
        // Unquoted page titles and search text contain spaces; token-shaped replacement leaked the
        // remainder (for example `title=<redacted> A Silent Voice`). Structured callers should use
        // attributes, so consuming the free-text field to its delimiter is the safe fallback.
        redacted = sensitiveFreeTextAssignment.replace(redacted) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}$REDACTED"
        }
        redacted = sensitiveAssignment.replace(redacted) { match ->
            val keyQuote = match.groupValues[1]
            val value = match.groupValues[4]
            val valueQuote = value.firstOrNull()?.takeIf { it == '\'' || it == '\"' }?.toString().orEmpty()
            "$keyQuote${match.groupValues[2]}$keyQuote${match.groupValues[3]}$valueQuote$REDACTED$valueQuote"
        }
        return redacted
    }

    fun redactAttributes(attributes: Map<String, Any?>): Map<String, String> = attributes
        .asSequence()
        .filter { (key, _) -> attributeKey.matches(key) }
        .take(MAX_ATTRIBUTES)
        .associate { (key, value) ->
            key to if (sensitiveKeys.matches(key)) {
                REDACTED
            } else {
                redactText(value?.toString() ?: "null").take(MAX_ATTRIBUTE_VALUE)
            }
        }

    fun redact(event: DiagnosticEvent): DiagnosticEvent = event.copy(
        outcome = event.outcome?.let(::boundedLabel),
        operationId = event.operationId?.takeIf(correlationValue::matches),
        traceId = event.traceId?.takeIf(correlationValue::matches),
        spanId = event.spanId?.takeIf(correlationValue::matches),
        message = event.message?.let(::redactText),
        attributes = redactAttributes(event.attributes),
        exception = event.exception?.let { exception ->
            exception.copy(
                message = exception.message?.let(::redactText),
                stackTrace = redactText(exception.stackTrace),
            )
        },
    )

    private fun boundedLabel(value: String): String = value
        .lowercase()
        .replace(Regex("[^a-z0-9_.-]"), "_")
        .take(32)

    private fun redactUrl(raw: String): String = runCatching {
        val uri = URI(raw)
        val scheme = uri.scheme?.lowercase() ?: return@runCatching "<redacted-url>"
        val host = uri.host ?: return@runCatching "<redacted-url>"
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        "$scheme://$host$port/<redacted>"
    }.getOrDefault("<redacted-url>")
}
