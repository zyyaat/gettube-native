package com.junkfood.seal.ui.page.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.PlaylistEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SearchViewModel : ViewModel() {

    data class ViewState(
        val query: String = "",
        val isLoading: Boolean = false,
        val hasSearched: Boolean = false,
        val results: List<PlaylistEntry> = emptyList(),
        val error: Boolean = false,
    )

    private val mutableViewStateFlow = MutableStateFlow(ViewState())
    val viewStateFlow = mutableViewStateFlow.asStateFlow()

    private var searchJob: Job? = null

    fun updateQuery(query: String) = mutableViewStateFlow.update { it.copy(query = query) }

    fun search() {
        val query = viewStateFlow.value.query.trim()
        if (query.isEmpty() || viewStateFlow.value.isLoading) return

        searchJob?.cancel()
        mutableViewStateFlow.update {
            it.copy(isLoading = true, hasSearched = true, error = false)
        }
        searchJob =
            viewModelScope.launch(Dispatchers.IO) {
                DownloadUtil.searchYouTube(query)
                    .onSuccess { results ->
                        withContext(Dispatchers.Main) {
                            mutableViewStateFlow.update {
                                it.copy(isLoading = false, results = results, error = false)
                            }
                        }
                    }
                    .onFailure {
                        withContext(Dispatchers.Main) {
                            mutableViewStateFlow.update {
                                it.copy(isLoading = false, results = emptyList(), error = true)
                            }
                        }
                    }
            }
    }
}
