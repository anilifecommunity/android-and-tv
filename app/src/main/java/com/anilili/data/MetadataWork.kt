package com.anilili.data

import kotlinx.coroutines.sync.Semaphore

/** Shared by optional home rows and hero enrichment, away from playback-critical requests. */
internal val backgroundMetadataGate = Semaphore(2)
