package com.anilili.data.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.anilili.BuildConfig
import com.anilili.data.AppGraph
import com.anilili.data.settings.SettingsStore
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.errors.AnililiError
import com.anilili.errors.AnililiFailure
import com.anilili.errors.raise
import com.anilili.errors.report
import com.anilili.errors.toFailure
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import com.anilili.util.cached

/**
 * In-app updates for a sideloaded install: reads the signed Nostr manifest (see
 * [NostrUpdateSource]), downloads the APK from its current primary host or mirrors,
 * and hands it to the system package installer. Android rejects the install unless
 * the new APK is signed with the same key, so a hijacked host can't replace the app.
 */
object UpdateManager {
    data class UpdateInfo(
        val version: String,
        val changelog: String,
        val apkUrl: String,
        val sizeBytes: Long,
        /**
         * Alternative hosts for the same APK, tried in order after [apkUrl] fails.
         *
         * A release lives or dies with one host otherwise, and the host is the part an outsider
         * can take away. Empty for a GitHub release, which is its own host; the Nostr manifest is
         * where mirrors are named, so they can be added or moved without shipping a new build.
         */
        val mirrors: List<String> = emptyList(),
        /**
         * The published build's internal build number (its `versionCode`), or null for a manifest
         * that predates the field. Lets a rebuild that keeps the version name still count as newer;
         * see [updateOffer].
         */
        val build: Int? = null,
        /**
         * The publisher marked this build as one installs should not sit on: a rebuild carrying
         * fixes that matter is then popped up like a new version instead of waiting in Settings.
         * See [promptsUnasked].
         */
        val important: Boolean = false,
        /** SHA-256 of the selected APK, lowercase hexadecimal. */
        val sha256: String? = null,
        /** Monotonic signed-manifest sequence, used to reject replay after a newer manifest. */
        val sequence: Long? = null,
        /** Signed manifest expiry as Unix seconds. */
        val expiresAtEpochSeconds: Long? = null,
        /** `mobile` or `tv`, when declared by the canonical manifest schema. */
        val formFactor: String? = null,
        /** ABIs carried by the selected APK. */
        val abis: List<String> = emptyList(),
    ) {
        /** Every place this APK can be fetched from, preferred first, without duplicates. */
        val downloadUrls: List<String> get() = (listOf(apkUrl) + mirrors).distinct()
    }

    sealed interface State {
        data object Idle : State
        data object Checking : State
        /** Manual check found nothing newer. */
        data class UpToDate(val latestPublishedVersion: String) : State
        data class Available(val update: UpdateInfo) : State
        data class Downloading(val update: UpdateInfo, val progress: Float) : State
        data class ReadyToInstall(val update: UpdateInfo, val file: File) : State
        /**
         * A catalogued failure, so "the update didn't finish" can be told apart in a bug report:
         * `UPD-01` is the check, `UPD-03` the download, `UPD-05` the device refusing the install.
         * They all looked identical when this carried whichever sentence the exception happened to
         * have, and an update that cannot install is the one failure a viewer cannot work around.
         */
        data class Failed(val failure: AnililiFailure) : State
    }

    private const val PREFS = "anilili_updates"
    private const val KEY_LAST_CHECK = "last_check_ms"
    private const val KEY_LAST_SEQUENCE = "last_manifest_sequence"
    /**
     * The shortest gap between two automatic checks.
     *
     * This used to be 12 hours, which quietly contradicted the setting that turns the check on:
     * "Prompt when a new version is available each time the app opens". Someone who updated and
     * then opened the app an hour later to a newer release got nothing, and no part of the UI
     * explained why — reported exactly that way after 0.1.60 shipped an hour behind 0.1.59.
     *
     * The gate on whether to check at all is the setting. This floor only exists because the
     * caller re-runs on activity recreation (rotation, theme change), and a burst of those should
     * not become a burst of requests.
     */
    private val CHECK_INTERVAL_MS = TimeUnit.MINUTES.toMillis(5)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state = _state.asStateFlow()

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /** The installed build's internal build number. */
    val currentBuild: Int get() = BuildConfig.VERSION_CODE

    private val _quietUpdate = MutableStateFlow<UpdateInfo?>(null)

    /**
     * A newer build of the installed version that an automatic check found and deliberately did
     * not pop up for — a rebuild carrying fixes under the same version name. Settings shows it, and
     * checking from there offers it like any update. Null when there is none.
     */
    val quietUpdate = _quietUpdate.asStateFlow()

    private val _pendingUpdate = MutableStateFlow<UpdateInfo?>(null)

    /**
     * Any newer build the last check found — a new version or a quiet rebuild — for as long as it
     * is not installed. It badges Settings' App section, and outlives [state] on purpose: dismissing
     * the pop-up returns that to Idle, and the update is still waiting afterwards.
     */
    val pendingUpdate = _pendingUpdate.asStateFlow()

    /**
     * How to name [update] beside the installed build: with build numbers when the version names
     * are the same, since "v0.1.80 is available, you have v0.1.80" says nothing.
     */
    fun offeredLabel(update: UpdateInfo): String =
        versionLabel(update.version, update.build.takeIf { update.version == currentVersion })

    fun installedLabel(update: UpdateInfo?): String =
        versionLabel(currentVersion, currentBuild.takeIf { update?.version == currentVersion })

    /**
     * Throttled startup check; only surfaces a prompt when an update exists and [prompt] allows it.
     *
     * It runs even with "Check for updates on launch" off — that setting is passed as [prompt] and
     * only decides the pop-up. The check itself still has to happen, or [pendingUpdate] never learns
     * of the update and Settings' badge stays dark for exactly the viewer who asked not to be
     * interrupted.
     */
    fun autoCheckIfDue(context: Context, prompt: Boolean = true) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastCheckMs = prefs.getLong(KEY_LAST_CHECK, 0L)
        if (!updateCheckIsDue(System.currentTimeMillis(), lastCheckMs, CHECK_INTERVAL_MS)) {
            DiagnosticsLog.event(
                "UpdateManager.autoCheckIfDue throttled sinceLastMs=" +
                    "${System.currentTimeMillis() - lastCheckMs}",
            )
            return
        }
        check(context, manual = false, prompt = prompt)
    }

    /**
     * Debug builds only: opens the update prompt with [changelog] as its notes, so a changelog's
     * layout can be checked on a device before it is published. The offer points nowhere; pressing
     * Update just fails.
     */
    fun previewForDebug(changelog: String) {
        if (!com.anilili.BuildConfig.DEBUG) return
        _state.value = State.Available(UpdateInfo(version = "preview", changelog = changelog, apkUrl = "", sizeBytes = 0L))
    }

    fun check(context: Context, manual: Boolean, prompt: Boolean = true) {
        val current = _state.value
        if (current is State.Checking || current is State.Downloading) {
            DiagnosticsLog.event(
                "Update check ignored busyState=${current.javaClass.simpleName} manual=$manual",
            )
            return
        }
        val includeBeta = SettingsStore.receiveBetaUpdates.value
        DiagnosticsLog.event(
            "Update check requested manual=$manual installed=$currentVersion includeBeta=$includeBeta",
        )
        _state.value = State.Checking
        val appContext = context.applicationContext
        scope.launch {
            runCatching {
                val info = fetchLatest(includeBeta)
                val lastSequence = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getLong(KEY_LAST_SEQUENCE, -1L)
                if (!acceptsManifestSequence(info.sequence, lastSequence)) {
                    error("Update manifest sequence rolled back")
                }
                info
            }
                .onSuccess { info ->
                    appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                        .apply { info.sequence?.let { putLong(KEY_LAST_SEQUENCE, it) } }
                        .apply()
                    val offer = updateOffer(info.version, info.build, currentVersion, currentBuild)
                    _quietUpdate.value = info.takeIf { offer == UpdateOffer.QUIET }
                    _pendingUpdate.value = info.takeIf { offer != UpdateOffer.NONE }
                    _state.value = when {
                        promptsUnasked(offer, info.important) && (prompt || manual) -> State.Available(info)
                        // Asked for from Settings, a rebuild is offered like anything else.
                        offer == UpdateOffer.QUIET && manual -> State.Available(info)
                        manual -> State.UpToDate(info.version)
                        else -> State.Idle
                    }
                    DiagnosticsLog.event(
                        "Update check complete manual=$manual prompt=$prompt installed=$currentVersion build=$currentBuild " +
                            "published=${info.version} publishedBuild=${info.build ?: "none"} " +
                            "result=${offer.name.lowercase()} important=${info.important}",
                    )
                }
                .onFailure { error ->
                    val failure = error.toFailure(
                        AnililiError.UPDATE_CHECK_FAILED,
                        attributes = mapOf("manual" to manual, "installed" to currentVersion),
                    )
                    // An automatic check that fails stays silent, as it always has — nobody asked —
                    // but it is still reported, because a check failing for everyone is worth seeing
                    // in the bundles and used to leave no trace at all outside a manual attempt.
                    _state.value = if (manual) State.Failed(failure) else State.Idle
                }
        }
    }

    fun download(context: Context) {
        val info = (_state.value as? State.Available)?.update
        if (info == null) {
            DiagnosticsLog.event("Update download ignored state=${_state.value.javaClass.simpleName}")
            return
        }
        DiagnosticsLog.event(
            "Update download requested version=${info.version} sizeBytes=${info.sizeBytes} " +
                "host=${runCatching { Uri.parse(info.apkUrl).host }.getOrNull() ?: "unknown"}",
        )
        _state.value = State.Downloading(info, 0f)
        val appContext = context.applicationContext
        scope.launch {
            runCatching { downloadApk(appContext, info) }
                .onSuccess { file ->
                    DiagnosticsLog.event(
                        "Update download complete version=${info.version} bytes=${file.length()}",
                    )
                    _state.value = State.ReadyToInstall(info, file)
                    install(appContext)
                }
                .onFailure { error ->
                    _state.value = State.Failed(
                        error.toFailure(
                            AnililiError.UPDATE_DOWNLOAD_FAILED,
                            attributes = mapOf(
                                "version" to info.version,
                                "sizeBytes" to info.sizeBytes,
                                "hosts" to info.downloadUrls.size,
                            ),
                        ),
                    )
                }
        }
    }

    /**
     * Launches the system installer for the downloaded APK. If the app can't request
     * installs yet, opens the "install unknown apps" settings screen instead; the
     * ReadyToInstall state is kept so the user can retry after granting.
     */
    fun install(context: Context) {
        val ready = _state.value as? State.ReadyToInstall
        if (ready == null) {
            DiagnosticsLog.event("Update install ignored state=${_state.value.javaClass.simpleName}")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            DiagnosticsLog.event(
                "Update install permission required version=${ready.update.version}; opening Android settings",
            )
            // Some TV builds don't resolve the per-app screen; fall back to the general list.
            val perApp = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val generic = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(perApp) }
                .recoverCatching { context.startActivity(generic) }
                .onSuccess {
                    DiagnosticsLog.event("Update install permission settings launched")
                }
                .onFailure { error ->
                    _state.value = State.Failed(
                        AnililiError.UPDATE_INSTALL_REFUSED.report(
                            // The instruction is the detail rather than the line, because the line
                            // has to fit a row and this one only makes sense in the dialog: there is
                            // no button the app can offer once its own settings intent has failed.
                            detail = "Allow \"install unknown apps\" for Anilife in Android's settings, " +
                                "then try the update again.",
                            cause = error,
                            attributes = mapOf("version" to ready.update.version, "stage" to "permission_settings"),
                        ),
                    )
            }
            return
        }
        runCatching {
            launchSystemInstaller(context, ready.file)
        }.onSuccess {
            DiagnosticsLog.event(
                "Update installer launched version=${ready.update.version} bytes=${ready.file.length()}",
            )
        }.onFailure { error ->
            _state.value = State.Failed(
                AnililiError.UPDATE_INSTALLER_MISSING.report(
                    cause = error,
                    attributes = mapOf("version" to ready.update.version, "bytes" to ready.file.length()),
                ),
            )
        }
    }

    fun dismiss() {
        if (_state.value !is State.Downloading) _state.value = State.Idle
    }

    /**
     * Where the version is read from: the mirrored manifest, then the relays that back it up.
     *
     * Both carry the same signed event and both verify it, so the order is a latency choice and
     * not a trust one — the mirror is one CDN request, while the relay scan waits on several
     * sockets and routinely sits through timeouts.
     *
     * GitHub is no longer consulted. The release keeps its notes but carries no APK, so the call
     * could only ever fail, and asking it first is what made every check pay for a doomed request.
     */
    private suspend fun fetchLatest(includeBeta: Boolean): UpdateInfo = coroutineScope {
        val stable = async {
            runCatching { NostrUpdateSource.fetchManifestFromMirror(AppGraph.httpClient, json) }
                .recoverCatching { mirrorError ->
                    DiagnosticsLog.throwable("Manifest mirror failed; falling back to Nostr relays", mirrorError)
                    NostrUpdateSource.fetchManifest(AppGraph.httpClient, json)
                }
        }
        if (!includeBeta) return@coroutineScope stable.await().getOrThrow()

        val beta = async {
            runCatching {
                NostrUpdateSource.fetchManifest(
                    AppGraph.httpClient,
                    json,
                    NostrUpdateSource.BETA_MANIFEST_D_TAG,
                )
            }.onFailure { DiagnosticsLog.throwable("Beta update check failed", it) }
        }
        val stableResult = stable.await()
        val betaResult = beta.await()
        newestUpdate(listOfNotNull(stableResult.getOrNull(), betaResult.getOrNull()))
            ?: throw stableResult.exceptionOrNull()
            ?: betaResult.exceptionOrNull()
            ?: error("No update manifest was found")
    }

    private fun downloadApk(context: Context, info: UpdateInfo): File {
        val policy = updateInstallerPolicy(Build.VERSION.SDK_INT)
        // Raised rather than returned so the failure keeps UPD-04 all the way out through the
        // download's catch: "couldn't make room" and "the download stopped halfway" ask different
        // things of the viewer, and toFailure above passes an already-catalogued failure through
        // untouched rather than relabelling it UPD-03.
        val cacheRoot = when (policy.uriKind) {
            UpdateInstallerUriKind.LEGACY_FILE -> context.externalCacheDir
                ?: AnililiError.UPDATE_STORAGE_FAILED.raise(
                    detail = "This Android version installs from shared storage, and it is " +
                        "unavailable. Download the update manually and open it from Downloads.",
                    attributes = mapOf("uriKind" to policy.uriKind.name),
                )
            UpdateInstallerUriKind.FILE_PROVIDER -> context.cacheDir
        }
        val dir = File(cacheRoot, "updates")
        if (!dir.exists() && !dir.mkdirs()) {
            AnililiError.UPDATE_STORAGE_FAILED.raise(
                detail = "Couldn't create $dir for the download.",
                attributes = mapOf("path" to dir.path),
            )
        }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "Anilife-${info.version}.apk")
        // No call timeout: the release APK takes longer than the API client's 45s cap on slow links.
        val client = AppGraph.httpClient.newBuilder()
            .cache(null)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
        var lastFailure: Exception? = null
        for ((index, url) in info.downloadUrls.withIndex()) {
            try {
                downloadFrom(client, url, file, info)
                verifyDownloadedApk(context, file, info)
                if (index > 0) DiagnosticsLog.event("Update downloaded from mirror #$index")
                return file
            } catch (e: Exception) {
                lastFailure = e
                DiagnosticsLog.throwable("Update download source ${index + 1}/${info.downloadUrls.size} failed", e)
            }
        }
        throw lastFailure ?: IOException("Download failed")
    }

    /**
     * Hands a completed APK to the device's package installer.
     *
     * Android 6's PackageInstaller only advertises APK intents for the `file:` scheme. A
     * FileProvider `content:` URI therefore fails before the installer is even opened on those
     * devices. Android 7 added `content:` support and also rejects exposed `file:` URIs, so the
     * URI and download location must change together at that boundary.
     */
    @Suppress("DEPRECATION")
    private fun launchSystemInstaller(context: Context, file: File) {
        val policy = updateInstallerPolicy(Build.VERSION.SDK_INT)
        val uri = when (policy.uriKind) {
            UpdateInstallerUriKind.LEGACY_FILE -> Uri.fromFile(file)
            UpdateInstallerUriKind.FILE_PROVIDER -> FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
        }

        var lastFailure: Exception? = null
        for (action in policy.actions) {
            val intent = Intent(
                when (action) {
                    UpdateInstallerAction.VIEW -> Intent.ACTION_VIEW
                    UpdateInstallerAction.INSTALL_PACKAGE -> Intent.ACTION_INSTALL_PACKAGE
                },
            )
                .setDataAndType(uri, APK_MIME_TYPE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (policy.uriKind == UpdateInstallerUriKind.FILE_PROVIDER) {
                intent.clipData = ClipData.newRawUri("Anilife update", uri)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                context.startActivity(intent)
                DiagnosticsLog.event(
                    "Update installer intent accepted action=${action.name.lowercase()} " +
                        "uri=${policy.uriKind.name.lowercase()} sdk=${Build.VERSION.SDK_INT}",
                )
                return
            } catch (error: Exception) {
                lastFailure = error
                DiagnosticsLog.event(
                    "Update installer intent rejected action=${action.name.lowercase()} " +
                        "uri=${policy.uriKind.name.lowercase()} sdk=${Build.VERSION.SDK_INT} " +
                        "error=${error.javaClass.simpleName}",
                )
            }
        }
        throw lastFailure ?: IllegalStateException("No package installer accepted the update")
    }

    /** One attempt against one host. Throws so [downloadApk] can move to the next. */
    private fun downloadFrom(client: OkHttpClient, url: String, file: File, info: UpdateInfo): File {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) error("Download failed (HTTP ${response.code})")
            val responseBody = response.body ?: error("Download failed (empty response)")
            val total = responseBody.contentLength().takeIf { it > 0 } ?: info.sizeBytes
            responseBody.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    val digest = MessageDigest.getInstance("SHA-256")
                    var written = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        written += read
                        if (total > 0) {
                            _state.value = State.Downloading(info, (written.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                    if (info.sizeBytes > 0 && written != info.sizeBytes) {
                        error("Download size mismatch ($written of ${info.sizeBytes} bytes)")
                    }
                    val expectedSha256 = info.sha256
                    if (expectedSha256 != null && digest.digest().toHexString() != expectedSha256) {
                        error("Download checksum mismatch")
                    }
                }
            }
        }
        return file
    }

    @Suppress("DEPRECATION")
    private fun verifyDownloadedApk(context: Context, file: File, info: UpdateInfo) {
        val packageManager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val candidate = packageManager.getPackageArchiveInfo(file.path, flags)
            ?: error("Downloaded file is not an APK")
        val installed = packageManager.getPackageInfo(context.packageName, flags)
        if (candidate.packageName != context.packageName) error("Downloaded APK package does not match Anilife")
        info.build?.let { expected ->
            val actual = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                candidate.longVersionCode
            } else {
                candidate.versionCode.toLong()
            }
            if (actual != expected.toLong()) error("Downloaded APK build does not match the manifest")
        }
        if (candidate.versionName != info.version) error("Downloaded APK version does not match the manifest")
        if (!signersMatch(installed.signerBytes(installed = true), candidate.signerBytes(installed = false))) {
            error("Downloaded APK signer does not match the installed app")
        }
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.signerBytes(installed: Boolean): List<ByteArray> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = signingInfo ?: return emptyList()
            val signers = if (installed) signing.signingCertificateHistory else signing.apkContentsSigners
            signers.orEmpty().map { it.toByteArray() }
        } else {
            signatures.orEmpty().map { it.toByteArray() }
        }
    }

}

internal enum class UpdateInstallerUriKind {
    /** Android 5–6 installer contract: a public/external-cache file exposed as `file:`. */
    LEGACY_FILE,

    /** Android 7+ installer contract: an internal-cache file shared through FileProvider. */
    FILE_PROVIDER,
}

internal enum class UpdateInstallerAction {
    VIEW,
    INSTALL_PACKAGE,
}

internal data class UpdateInstallerPolicy(
    val uriKind: UpdateInstallerUriKind,
    val actions: List<UpdateInstallerAction>,
)

/** Keeps Android 6 compatibility isolated and testable without constructing framework URIs. */
internal fun updateInstallerPolicy(sdkInt: Int): UpdateInstallerPolicy = UpdateInstallerPolicy(
    uriKind = if (sdkInt <= Build.VERSION_CODES.M) {
        UpdateInstallerUriKind.LEGACY_FILE
    } else {
        UpdateInstallerUriKind.FILE_PROVIDER
    },
    // OEM installers disagree on which action they expose. Preserve the established VIEW path,
    // then try the action Android documents specifically for package installation.
    actions = listOf(UpdateInstallerAction.VIEW, UpdateInstallerAction.INSTALL_PACKAGE),
)

private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

/** Shared version extraction used by both the GitHub and Nostr update sources. */
internal fun parseVersion(text: String): String? =
    Regex.cached("""^v?(\d+(?:\.\d+)+(?:-[0-9A-Za-z.-]+)?)$""")
        .matchEntire(text.trim())?.groupValues?.get(1)

/**
 * Whether an automatic check may run now.
 *
 * A never-checked install ([lastCheckMs] of 0) is always due. A clock that has moved backwards --
 * a timezone change, or a user setting the date -- would otherwise park the check indefinitely, so
 * a last-check in the future counts as due too.
 */
internal fun updateCheckIsDue(nowMs: Long, lastCheckMs: Long, floorMs: Long): Boolean =
    lastCheckMs <= 0L || nowMs < lastCheckMs || nowMs - lastCheckMs >= floorMs

internal fun acceptsManifestSequence(sequence: Long?, lastAccepted: Long): Boolean =
    sequence == null || lastAccepted < 0L || sequence >= lastAccepted

internal fun signersMatch(installed: List<ByteArray>, candidate: List<ByteArray>): Boolean {
    if (installed.isEmpty() || candidate.isEmpty()) return false
    fun ByteArray.digest() = MessageDigest.getInstance("SHA-256").digest(this).toHexString()
    val trusted = installed.map(ByteArray::digest).toSet()
    return candidate.map(ByteArray::digest).any(trusted::contains)
}

internal fun newestUpdate(updates: List<UpdateManager.UpdateInfo>): UpdateManager.UpdateInfo? =
    updates.maxWithOrNull { left, right ->
        compareAppVersions(left.version, right.version).takeIf { it != 0 }
            ?: (left.build ?: 0).compareTo(right.build ?: 0)
    }

/** "v0.1.80", or "v0.1.80 (build 82)" when the build number is what tells two apart. */
internal fun versionLabel(version: String, build: Int?): String =
    if (build != null) "v$version (build $build)" else "v$version"

/** What a check should do with the published build it found. */
internal enum class UpdateOffer {
    /** Nothing newer, or nothing this install could accept. */
    NONE,
    /** A new version: pop the update up, as every release always has. */
    PROMPT,
    /** The same version rebuilt under a higher build number: offer it only when asked. */
    QUIET,
}

/**
 * Decides between a pop-up, a quiet offer and nothing.
 *
 * The version name still decides releases — a newer name prompts exactly as it always has, and
 * every install from before build numbers were published compares names alone, so they behave as
 * they always did. What the build number adds is the case the name cannot see: a rebuild published
 * under the same name. 0.1.80 was republished that way with three fixes, and every install already
 * on 0.1.80 read the manifest as "up to date" and never got them. With the published build number
 * higher than the installed one, that rebuild is now offered — quietly, because it is a patch to
 * the version the viewer already has, not a release worth interrupting them for.
 *
 * A published build number below the installed one is never offered, whatever its name says:
 * Android refuses to install a lower versionCode over a higher one, so offering it could only end
 * in a failed install.
 */
internal fun updateOffer(
    remoteVersion: String,
    remoteBuild: Int?,
    installedVersion: String,
    installedBuild: Int,
): UpdateOffer {
    if (remoteBuild != null && remoteBuild < installedBuild) return UpdateOffer.NONE
    val names = compareAppVersions(remoteVersion, installedVersion)
    return when {
        names > 0 -> UpdateOffer.PROMPT
        names == 0 && remoteBuild != null && remoteBuild > installedBuild -> UpdateOffer.QUIET
        else -> UpdateOffer.NONE
    }
}

/**
 * Whether a check that nobody asked for pops the update up.
 *
 * A new version always does. A rebuild of the installed version normally waits in Settings, which
 * is easy to miss on a remote-first device. A rebuild the publisher marks important (`--important`
 * in scripts/nostr_update.py, per form factor) therefore pops up like a release; the "Check for
 * updates on launch" setting still decides whether anything pops up.
 */
internal fun promptsUnasked(offer: UpdateOffer, important: Boolean): Boolean =
    offer == UpdateOffer.PROMPT || (offer == UpdateOffer.QUIET && important)

internal fun compareAppVersions(remote: String, installed: String): Int {
    fun parsed(version: String): Pair<List<Int>, List<String>> {
        val split = version.removePrefix("v").removeSuffix("-debug").split('-', limit = 2)
        return split[0].split('.').map { it.toIntOrNull() ?: 0 } to
            split.getOrNull(1)?.split('.').orEmpty()
    }
    val (remoteCore, remotePre) = parsed(remote)
    val (installedCore, installedPre) = parsed(installed)
    for (index in 0 until maxOf(remoteCore.size, installedCore.size)) {
        val diff = (remoteCore.getOrNull(index) ?: 0)
            .compareTo(installedCore.getOrNull(index) ?: 0)
        if (diff != 0) return diff
    }
    if (remotePre.isEmpty() || installedPre.isEmpty()) {
        return when {
            remotePre.isEmpty() && installedPre.isNotEmpty() -> 1
            remotePre.isNotEmpty() && installedPre.isEmpty() -> -1
            else -> 0
        }
    }
    for (index in 0 until maxOf(remotePre.size, installedPre.size)) {
        val left = remotePre.getOrNull(index) ?: return -1
        val right = installedPre.getOrNull(index) ?: return 1
        val leftNumber = left.toLongOrNull()
        val rightNumber = right.toLongOrNull()
        val diff = when {
            leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
            leftNumber != null -> -1
            rightNumber != null -> 1
            else -> left.compareTo(right, ignoreCase = true)
        }
        if (diff != 0) return diff
    }
    return 0
}

/** TV builds publish assets carrying this segment; every other asset belongs to the phone build. */
private const val TV_ASSET_MARKER = "_tv"
private val ANILIFE_APK_NAME = Regex(
    "^Anilife(?:_tv)?(?:_(?:arm64-v8a|armeabi-v7a))?\\.apk$",
    RegexOption.IGNORE_CASE,
)

/**
 * Chooses the smallest compatible split while retaining the universal APK as a safe fallback.
 *
 * Anilife is a fresh install identity, so historical publisher names and cross-form-factor
 * fallbacks are deliberately rejected. A TV install must never update itself into the phone build,
 * and a phone must never take a TV asset.
 */
internal fun preferredReleaseApkName(
    names: List<String>,
    supportedAbis: List<String>,
    isTv: Boolean = false,
): String? {
    val apks = names.filter(ANILIFE_APK_NAME::matches)
    if (apks.isEmpty()) return null
    val isTvAsset = { name: String -> name.contains(TV_ASSET_MARKER, ignoreCase = true) }
    val family = apks.filter { isTvAsset(it) == isTv }
    if (family.isEmpty()) return null
    val preferredAbi = when {
        supportedAbis.any { it.equals("arm64-v8a", ignoreCase = true) } -> "arm64-v8a"
        supportedAbis.any { it.equals("armeabi-v7a", ignoreCase = true) } -> "armeabi-v7a"
        else -> null
    }
    return preferredAbi?.let { abi ->
        family.firstOrNull { it.contains(abi, ignoreCase = true) }
    } ?: family.firstOrNull { it.equals("Anilife.apk", ignoreCase = true) }
        ?: family.firstOrNull { it.equals("Anilife_tv.apk", ignoreCase = true) }
        ?: return null
}
