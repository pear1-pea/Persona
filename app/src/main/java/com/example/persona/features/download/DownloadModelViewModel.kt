package com.example.persona.features.download

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.persona.core.ai.DownloadState
import com.example.persona.core.ai.ModelDownloadManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DownloadModelViewModel @Inject constructor(
    private val downloadManager: ModelDownloadManager
) : ViewModel() {

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Progress(0))
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    fun startDownload(url: String) {
        viewModelScope.launch {
            downloadManager.downloadModel(url).collect { _state.value = it }
        }
    }
}
