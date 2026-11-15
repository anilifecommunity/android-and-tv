package com.anilili.diagnostics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.anilili.ui.dialogs.MidnightConfirmDialog

@Composable
fun DiagnosticsDisclosureHost() {
    val context = LocalContext.current
    val pending by DiagnosticsUploadManager.defaultNoticePending.collectAsState()
    if (!pending) return

    val acknowledge = { DiagnosticsUploadManager.acknowledgeDefaultNotice(context) }
    MidnightConfirmDialog(
        kicker = "Privacy",
        title = "Diagnostics are on",
        body = "Anilife will send one private, redacted report per day to help catch issues and " +
            "identify their source. Diagnostic detail is Standard unless you previously changed " +
            "it. You can turn uploads off anytime in Settings.",
        confirmText = "Got it",
        onConfirm = acknowledge,
        onDismiss = acknowledge,
        dismissText = null,
        destructive = false,
    )
}
