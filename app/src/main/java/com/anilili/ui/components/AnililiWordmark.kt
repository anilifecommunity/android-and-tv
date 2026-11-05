package com.anilili.ui.components

import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

/**
 * A rights-safe development wordmark until approved Anilife artwork is supplied.
 * The historical image asset is deliberately not rendered as new product branding.
 */
@Composable
fun AnililiWordmark(modifier: Modifier = Modifier) {
    Text(
        text = "ANILIFE",
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
        maxLines = 1,
        overflow = TextOverflow.Clip,
        modifier = modifier,
    )
}
