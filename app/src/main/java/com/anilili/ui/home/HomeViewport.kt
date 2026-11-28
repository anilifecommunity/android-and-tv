package com.anilili.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
internal fun ObserveHomeViewport(state: LazyListState, rows: List<HomeRow>, onRows: (Set<HomeRow>) -> Unit) {
    val latestOnRows by rememberUpdatedState(onRows)
    LaunchedEffect(state, rows) {
        snapshotFlow { homeRowsNearViewport(rows, state.layoutInfo.visibleItemsInfo.map { it.key }.toSet()) }
            .distinctUntilChanged().collect { latestOnRows(it) }
    }
}

/** Reserves a row's position until its first attempt settles, without invisible focus targets. */
@Composable
internal fun PendingHomeRow(row: HomeRow, isTv: Boolean) {
    Column(Modifier.height(220.dp).padding(24.dp)) {
        Text(row.label(isTv))
        Spacer(Modifier.height(16.dp))
        CircularProgressIndicator()
    }
}
