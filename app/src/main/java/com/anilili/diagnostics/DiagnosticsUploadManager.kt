package com.anilili.diagnostics

import android.annotation.SuppressLint
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.anilili.BuildConfig
import com.anilili.data.AppGraph
import com.anilili.data.device.isTelevision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val DIAGNOSTICS_HOST = "diagnostics.anilifecommunity.org"

internal fun diagnosticsUploadOrigin(value: String): HttpUrl? = value.trim().toHttpUrlOrNull()?.takeIf {
    it.scheme == "https" &&
        it.host == DIAGNOSTICS_HOST &&
        it.port == 443 &&
        it.encodedPath == "/" &&
        it.query == null &&
        it.fragment == null &&
        it.username.isEmpty() &&
        it.password.isEmpty()
}

internal fun diagnosticsUploadIsRetryable(status: Int): Boolean =
    status == 408 || status == 425 || status == 429 || status >= 500

internal fun nextLocalMidnightDelayMillis(now: ZonedDateTime): Long = Duration.between(
    now,
    now.toLocalDate().plusDays(1).atStartOfDay(now.zone),
).toMillis().coerceAtLeast(0L)

internal fun diagnosticsUploadShouldCatchUp(lastSuccessfulDate: String?, today: LocalDate): Boolean =
    lastSuccessfulDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.isBefore(today) == true

internal enum class DiagnosticsReceiptDecision { ACCEPT, RETRY, DROP }

internal fun diagnosticsReceiptDecision(status: Int, receiptValid: Boolean): DiagnosticsReceiptDecision = when {
    status in 200..299 && receiptValid -> DiagnosticsReceiptDecision.ACCEPT
    status in 200..299 || diagnosticsUploadIsRetryable(status) -> DiagnosticsReceiptDecision.RETRY
    else -> DiagnosticsReceiptDecision.DROP
}

internal fun diagnosticsUploadShouldProceed(
    automaticOnly: Boolean,
    stagedAutomatic: Boolean,
    automaticEnabled: Boolean,
): Boolean = automaticEnabled || (!automaticOnly && !stagedAutomatic)

internal fun diagnosticsTriggerIsAutomatic(trigger: DiagnosticTrigger): Boolean =
    trigger == DiagnosticTrigger.DAILY

internal val diagnosticsExistingWorkPolicy = ExistingWorkPolicy.REPLACE

private val diagnosticsUploadMutex = Mutex()

internal suspend fun <T> withDiagnosticsUploadLock(block: suspend () -> T): T =
    diagnosticsUploadMutex.withLock { block() }

private enum class UploadOutcome { SUCCESS, RETRY }

// Consent and retry metadata must reach disk before work is scheduled or staged files are removed.
@SuppressLint("ApplySharedPref", "UseKtx")
object DiagnosticsUploadManager {
    private const val PREFS = "anilili_diagnostics_upload"
    private const val KEY_ENABLED = "automatic_enabled"
    private const val KEY_CONSENT_VERSION = "consent_version"
    private const val KEY_WATERMARK = "capture_watermark_utc"
    private const val KEY_PENDING_ID = "pending_report_id"
    private const val KEY_PENDING_END = "pending_end_utc"
    private const val KEY_PENDING_TRIGGER = "pending_trigger"
    private const val KEY_LAST_STATUS = "last_status"
    private const val KEY_LAST_SUCCESSFUL_DATE = "last_successful_local_date"
    private const val CONSENT_VERSION = 1
    private const val WORK_NAME = "diagnostics-daily-upload"
    private const val WORK_TAG = "diagnostics-upload"
    private const val STAGING_DIRECTORY = "diagnostic_uploads"

    @Volatile private var activeUpload: ActiveUpload? = null
    private val timestamp = DateTimeFormatter.ofPattern(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        Locale.US,
    ).withZone(ZoneOffset.UTC)
    private val _automatic = MutableStateFlow(false)
    val automatic = _automatic.asStateFlow()
    private val _status = MutableStateFlow<String?>(null)
    val status = _status.asStateFlow()

    fun init(context: Context) {
        val app = context.applicationContext
        val preferences = preferences(app)
        _automatic.value = preferences.getBoolean(KEY_ENABLED, false)
        _status.value = preferences.getString(KEY_LAST_STATUS, null)
        cleanupOrphanedStaging(app, preferences.getString(KEY_PENDING_ID, null))
        if (_automatic.value && origin() != null) {
            scheduleNext(
                app,
                immediate = diagnosticsUploadShouldCatchUp(
                    preferences.getString(KEY_LAST_SUCCESSFUL_DATE, null),
                    LocalDate.now(),
                ),
            )
        }
    }

    fun setAutomatic(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        if (enabled && origin() == null) {
            _automatic.value = false
            setStatus(app, "Automatic diagnostics are unavailable in this build")
            return
        }
        val preferences = preferences(app)
        val wasEnabled = preferences.getBoolean(KEY_ENABLED, false)
        if (enabled && !wasEnabled) {
            preferences.edit()
                .putBoolean(KEY_ENABLED, true)
                .putInt(KEY_CONSENT_VERSION, CONSENT_VERSION)
                .putString(KEY_WATERMARK, nowUtc())
                .putString(KEY_LAST_SUCCESSFUL_DATE, LocalDate.now().toString())
                .commit()
            _automatic.value = true
            setStatus(app, "Automatic diagnostics enabled; only new events can be uploaded")
            scheduleNext(app)
            DiagnosticsLog.event("diagnostics", "automatic_upload.enabled")
        } else if (!enabled) {
            preferences.edit()
                .putBoolean(KEY_ENABLED, false)
                .remove(KEY_PENDING_ID)
                .remove(KEY_PENDING_END)
                .remove(KEY_PENDING_TRIGGER)
                .commit()
            _automatic.value = false
            activeUpload?.takeIf(ActiveUpload::automatic)?.call?.cancel()
            WorkManager.getInstance(app).cancelUniqueWork(WORK_NAME)
            stagingDirectory(app).deleteRecursively()
            setStatus(app, "Automatic diagnostics disabled; queued uploads deleted")
            DiagnosticsLog.event("diagnostics", "automatic_upload.disabled")
        }
    }

    suspend fun uploadNow(context: Context): String = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        when (performUpload(app, DiagnosticTrigger.MANUAL, automaticOnly = false)) {
            UploadOutcome.SUCCESS -> _status.value ?: "Diagnostics uploaded"
            UploadOutcome.RETRY -> "Upload could not finish; try again when the connection is stable"
        }
    }

    internal suspend fun performAutomatic(context: Context): Boolean =
        performUpload(context.applicationContext, DiagnosticTrigger.DAILY, automaticOnly = true) ==
            UploadOutcome.SUCCESS

    internal fun scheduleNext(
        context: Context,
        now: ZonedDateTime = ZonedDateTime.now(),
        immediate: Boolean = false,
    ) {
        val app = context.applicationContext
        if (!preferences(app).getBoolean(KEY_ENABLED, false) || origin() == null) return
        val request = OneTimeWorkRequestBuilder<DiagnosticsUploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(if (immediate) 0L else nextLocalMidnightDelayMillis(now), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .addTag(WORK_TAG)
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(WORK_NAME, diagnosticsExistingWorkPolicy, request)
    }

    private suspend fun performUpload(
        context: Context,
        requestedTrigger: DiagnosticTrigger,
        automaticOnly: Boolean,
    ): UploadOutcome = withDiagnosticsUploadLock {
        val endpoint = origin() ?: run {
            setStatus(context, "Diagnostics upload is unavailable in this build")
            return@withDiagnosticsUploadLock UploadOutcome.SUCCESS
        }
        val preferences = preferences(context)
        if (!diagnosticsUploadShouldProceed(
                automaticOnly = automaticOnly,
                stagedAutomatic = false,
                automaticEnabled = preferences.getBoolean(KEY_ENABLED, false),
            )
        ) {
            discardPending(context, preferences)
            return@withDiagnosticsUploadLock UploadOutcome.SUCCESS
        }

        val staged = stagedReport(context, preferences, requestedTrigger)
        setStatus(context, "Uploading redacted diagnostics…")
        if (!diagnosticsUploadShouldProceed(
                automaticOnly = automaticOnly,
                stagedAutomatic = staged.automatic,
                automaticEnabled = preferences.getBoolean(KEY_ENABLED, false),
            )
        ) {
            discardPending(context, preferences)
            return@withDiagnosticsUploadLock UploadOutcome.SUCCESS
        }
        DiagnosticsLog.event(
            "diagnostics",
            "upload.started",
            mapOf("trigger" to staged.trigger.logValue),
            outcome = "started",
            operationId = staged.reportId,
        )
        val call = AppGraph.httpClient.newCall(uploadRequest(context, endpoint, staged))
        activeUpload = ActiveUpload(call, staged.automatic)
        val response = try {
            call.execute()
        } catch (_: IOException) {
            if (!diagnosticsUploadShouldProceed(
                    automaticOnly = automaticOnly,
                    stagedAutomatic = staged.automatic,
                    automaticEnabled = preferences.getBoolean(KEY_ENABLED, false),
                )
            ) {
                discardPending(context, preferences)
                return@withDiagnosticsUploadLock UploadOutcome.SUCCESS
            }
            setStatus(context, "Diagnostics upload queued for retry")
            DiagnosticsLog.event(
                "diagnostics",
                "upload.deferred",
                mapOf("trigger" to staged.trigger.logValue),
                outcome = "retry",
                operationId = staged.reportId,
            )
            return@withDiagnosticsUploadLock UploadOutcome.RETRY
        } finally {
            activeUpload = null
        }
        response.use {
            val body = it.body?.string().orEmpty().take(4_096)
            when (diagnosticsReceiptDecision(it.code, receiptIsValid(body, staged.reportId))) {
                DiagnosticsReceiptDecision.ACCEPT -> {
                    completePending(context, preferences, staged, advanceWatermark = staged.automatic)
                    setStatus(context, "Uploaded ${staged.reportId}; retained privately for 30 days")
                    DiagnosticsLog.event(
                        "diagnostics",
                        "upload.accepted",
                        mapOf("trigger" to staged.trigger.logValue, "bytes" to staged.file.length()),
                        outcome = "success",
                        operationId = staged.reportId,
                    )
                    return@withDiagnosticsUploadLock UploadOutcome.SUCCESS
                }
                DiagnosticsReceiptDecision.RETRY -> {
                    setStatus(context, "Diagnostics upload queued for retry")
                    DiagnosticsLog.event(
                        "diagnostics",
                        "upload.deferred",
                        mapOf("status" to it.code, "trigger" to staged.trigger.logValue),
                        outcome = "retry",
                        operationId = staged.reportId,
                    )
                    return@withDiagnosticsUploadLock UploadOutcome.RETRY
                }
                DiagnosticsReceiptDecision.DROP -> Unit
            }
            completePending(context, preferences, staged, advanceWatermark = staged.automatic)
            setStatus(context, "Diagnostics upload was rejected (${it.code})")
            DiagnosticsLog.event(
                "diagnostics",
                "upload.rejected",
                mapOf("status" to it.code, "trigger" to staged.trigger.logValue),
                outcome = "failure",
                operationId = staged.reportId,
            )
            UploadOutcome.SUCCESS
        }
    }

    private fun stagedReport(
        context: Context,
        preferences: android.content.SharedPreferences,
        requestedTrigger: DiagnosticTrigger,
    ): StagedReport {
        val pendingId = preferences.getString(KEY_PENDING_ID, null)
        val pendingFile = pendingId?.let { stagingDirectory(context).resolve("$it.zip") }
        if (pendingId != null && pendingFile?.isFile == true) {
            val trigger = preferences.getString(KEY_PENDING_TRIGGER, null)
                ?.let { stored -> DiagnosticTrigger.entries.firstOrNull { it.logValue == stored } }
                ?: requestedTrigger
            return StagedReport(
                reportId = pendingId,
                file = pendingFile,
                endUtc = preferences.getString(KEY_PENDING_END, null) ?: nowUtc(),
                trigger = trigger,
                automatic = diagnosticsTriggerIsAutomatic(trigger),
            )
        }
        discardPending(context, preferences)
        val automatic = diagnosticsTriggerIsAutomatic(requestedTrigger)
        val endUtc = nowUtc()
        val reportId = newReportId()
        val snapshot = DiagnosticsLog.createShareSnapshot(
            context,
            minimumTimestampUtc = if (automatic) preferences.getString(KEY_WATERMARK, endUtc) else null,
        )
        val destination = stagingDirectory(context).apply { mkdirs() }.resolve("$reportId.zip")
        snapshot.copyTo(destination, overwrite = true)
        snapshot.delete()
        preferences.edit()
            .putString(KEY_PENDING_ID, reportId)
            .putString(KEY_PENDING_END, endUtc)
            .putString(KEY_PENDING_TRIGGER, requestedTrigger.logValue)
            .commit()
        return StagedReport(reportId, destination, endUtc, requestedTrigger, automatic)
    }

    private fun uploadRequest(context: Context, endpoint: HttpUrl, staged: StagedReport): Request {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("report_id", staged.reportId)
            .addFormDataPart("trigger", staged.trigger.logValue)
            .addFormDataPart("app_version", BuildConfig.VERSION_NAME)
            .addFormDataPart("version_code", BuildConfig.VERSION_CODE.toString())
            .addFormDataPart("build_sha", BuildConfig.GIT_SHA.take(80))
            .addFormDataPart("platform", if (isTelevision(context)) "tv" else "android")
            .addFormDataPart(
                "report",
                "${staged.reportId}.zip",
                staged.file.asRequestBody("application/zip".toMediaType()),
            )
            .build()
        return Request.Builder()
            .url(endpoint.newBuilder().addPathSegments("v1/reports").build())
            .post(body)
            .build()
    }

    private fun receiptIsValid(body: String, reportId: String): Boolean = runCatching {
        val json = DiagnosticEventCodec.json.parseToJsonElement(body).jsonObject
        json["status"]?.jsonPrimitive?.content == "accepted" &&
            json["reportId"]?.jsonPrimitive?.content == reportId
    }.getOrDefault(false)

    private fun completePending(
        context: Context,
        preferences: android.content.SharedPreferences,
        staged: StagedReport,
        advanceWatermark: Boolean,
    ) {
        preferences.edit().apply {
            remove(KEY_PENDING_ID)
            remove(KEY_PENDING_END)
            remove(KEY_PENDING_TRIGGER)
            if (advanceWatermark) putString(KEY_WATERMARK, staged.endUtc)
            if (advanceWatermark) putString(KEY_LAST_SUCCESSFUL_DATE, LocalDate.now().toString())
        }.commit()
        staged.file.delete()
        stagingDirectory(context).delete()
    }

    private fun discardPending(context: Context, preferences: android.content.SharedPreferences) {
        preferences.edit()
            .remove(KEY_PENDING_ID)
            .remove(KEY_PENDING_END)
            .remove(KEY_PENDING_TRIGGER)
            .commit()
        stagingDirectory(context).deleteRecursively()
    }

    private fun cleanupOrphanedStaging(context: Context, pendingId: String?) {
        val keep = pendingId?.let { "$it.zip" }
        stagingDirectory(context).listFiles().orEmpty()
            .filter { it.name != keep }
            .forEach { it.delete() }
    }

    private fun setStatus(context: Context, value: String) {
        _status.value = value
        preferences(context).edit().putString(KEY_LAST_STATUS, value).apply()
    }

    private fun origin(): HttpUrl? = diagnosticsUploadOrigin(BuildConfig.DIAGNOSTICS_UPLOAD_URL)

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun stagingDirectory(context: Context) = File(context.filesDir, STAGING_DIRECTORY)

    private fun nowUtc(): String = timestamp.format(Instant.now())

    private fun newReportId(): String = "ANL-${timestamp.format(Instant.now()).take(10).replace("-", "")}-${
        UUID.randomUUID().toString().replace("-", "").take(10).uppercase(Locale.US)
    }"

    private data class StagedReport(
        val reportId: String,
        val file: File,
        val endUtc: String,
        val trigger: DiagnosticTrigger,
        val automatic: Boolean,
    )

    private data class ActiveUpload(val call: Call, val automatic: Boolean)
}

class DiagnosticsUploadWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (!DiagnosticsUploadManager.performAutomatic(applicationContext)) return Result.retry()
        DiagnosticsUploadManager.scheduleNext(applicationContext)
        return Result.success()
    }
}
