@file:OptIn(ExperimentalMaterial3Api::class)

package com.junkfood.seal.ui.page.downloadv2.configure

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.junkfood.seal.util.DownloadUtil
import kotlinx.coroutines.launch

/**
 * Application-level host for the download configuration sheet and its
 * sub-selections. Rendered once above the whole navigation shell so the
 * sheet can open over ANY tab (e.g. directly on top of search results),
 * exactly like the quality sheet did in the original GetTube app.
 */
@Composable
fun DownloadDialogHost(viewModel: DownloadDialogViewModel) {

    var preferences by remember {
        mutableStateOf(DownloadUtil.DownloadPreferences.createFromPreferences())
    }
    val sheetValue by viewModel.sheetValueFlow.collectAsStateWithLifecycle()
    val state by viewModel.sheetStateFlow.collectAsStateWithLifecycle()

    val selectionState = viewModel.selectionStateFlow.collectAsStateWithLifecycle().value

    var showDialog by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(sheetValue) {
        if (sheetValue == DownloadDialogViewModel.SheetValue.Expanded) {
            showDialog = true
        } else {
            launch { sheetState.hide() }.invokeOnCompletion { showDialog = false }
        }
    }

    if (showDialog) {
        DownloadDialog(
            state = state,
            sheetState = sheetState,
            config = Config(),
            preferences = preferences,
            onPreferencesUpdate = { preferences = it },
            onActionPost = { viewModel.postAction(it) },
        )
    }

    when (selectionState) {
        is DownloadDialogViewModel.SelectionState.FormatSelection ->
            FormatPage(
                state = selectionState,
                onDismissRequest = { viewModel.postAction(DownloadDialogViewModel.Action.Reset) },
            )

        is DownloadDialogViewModel.SelectionState.PlaylistSelection -> {
            PlaylistSelectionPage(
                state = selectionState,
                onDismissRequest = { viewModel.postAction(DownloadDialogViewModel.Action.Reset) },
            )
        }

        DownloadDialogViewModel.SelectionState.Idle -> {}
    }
}
