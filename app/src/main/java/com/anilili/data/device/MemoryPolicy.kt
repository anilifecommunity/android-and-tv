package com.anilili.data.device

import android.app.ActivityManager
import android.content.Context

private const val TWO_GIB = 2L * 1024 * 1024 * 1024
private const val CONSERVATIVE_IMAGE_BYTES = 16 * 1024 * 1024

internal fun useConservativeMemoryBudgets(lowRam: Boolean, heapMb: Int?, physicalBytes: Long?): Boolean =
    lowRam || heapMb == null || heapMb <= 256 || physicalBytes == null || physicalBytes <= TWO_GIB

internal fun physicalMemoryBytes(manager: ActivityManager?): Long? = manager?.let {
    runCatching { ActivityManager.MemoryInfo().also(it::getMemoryInfo).totalMem.takeIf { bytes -> bytes > 0 } }.getOrNull()
}

internal fun hasConservativeMemoryBudget(context: Context): Boolean {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    return useConservativeMemoryBudgets(manager?.isLowRamDevice == true, manager?.memoryClass, physicalMemoryBytes(manager))
}

internal fun imageMemoryCacheBytes(maxHeapBytes: Long, isTv: Boolean, conservative: Boolean): Int {
    val fraction = if (conservative) 0.06 else if (isTv) 0.12 else 0.25
    val limit = if (conservative) CONSERVATIVE_IMAGE_BYTES else Int.MAX_VALUE
    return (maxHeapBytes * fraction).toLong().coerceIn(1L, limit.toLong()).toInt()
}

internal fun effectiveBackdropBlur(stored: Boolean?, isTv: Boolean, conservative: Boolean): Boolean =
    stored ?: !(isTv && conservative)
