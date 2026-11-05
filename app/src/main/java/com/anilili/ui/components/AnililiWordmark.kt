package com.anilili.ui.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.anilili.R

@Composable
fun AnililiWordmark(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.anilili_header_wordmark),
        contentDescription = "Anilife",
        contentScale = ContentScale.Fit,
        modifier = modifier,
    )
}
