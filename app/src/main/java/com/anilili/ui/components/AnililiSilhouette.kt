package com.anilili.ui.components

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.min
import kotlin.math.roundToInt

/** Where the square Anilife mark lands inside a canvas. */
internal fun silhouetteFrame(canvas: Size, fraction: Float = 0.9f): Rect {
    val edge = min(canvas.width, canvas.height) * fraction
    val left = (canvas.width - edge) / 2f
    val top = (canvas.height - edge) / 2f
    return Rect(left, top, left + edge, top + edge)
}

/** The canonical logo mask scaled once for the animated water-fill loader. */
internal fun silhouetteMask(
    canvas: Size,
    source: ImageBitmap,
    fraction: Float = 0.9f,
): ImageBitmap {
    val width = canvas.width.roundToInt().coerceAtLeast(1)
    val height = canvas.height.roundToInt().coerceAtLeast(1)
    val bitmap = ImageBitmap(width, height)
    val frame = silhouetteFrame(Size(width.toFloat(), height.toFloat()), fraction)
    val left = frame.left.roundToInt()
    val top = frame.top.roundToInt()
    Canvas(bitmap).drawImageRect(
        image = source,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(source.width, source.height),
        dstOffset = IntOffset(left, top),
        dstSize = IntSize((frame.right - left).roundToInt(), (frame.bottom - top).roundToInt()),
        paint = Paint().apply { filterQuality = FilterQuality.High },
    )
    return bitmap
}
