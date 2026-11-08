package com.example.persona.features.diagnostics

import com.example.persona.core.ai.telemetry.AttemptOutcome
import com.example.persona.core.ai.telemetry.FileInferenceJournal
import com.example.persona.core.base.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class DiagnosticsError { LOAD, CLEAR, EXPORT, EXPIRED }

data class InferenceDiagnosticsUiState(
    val total: Int = 0,
    val completed: Int = 0,
    val failed: Int = 0,
    val cancelled: Int = 0,
    val busy: Boolean = false,
    val error: DiagnosticsError? = null,
    val exportData: String? = null,
    val awaitingDocument: Boolean = false
)

@HiltViewModel
class InferenceDiagnosticsViewModel @Inject constructor(
    private val journal: FileInferenceJournal
) : BaseViewModel() {
    private val state = MutableStateFlow(InferenceDiagnosticsUiState())
    val uiState = state.asStateFlow()

    init { refresh() }

    fun refresh() {
        if (state.value.busy) return
        operate(DiagnosticsError.LOAD) {
            val rows = journal.snapshot()
            state.value = state.value.copy(
                total = rows.size,
                completed = rows.count { it.outcome == AttemptOutcome.COMPLETED },
                failed = rows.count { it.outcome == AttemptOutcome.FAILED },
                cancelled = rows.count { it.outcome == AttemptOutcome.CANCELLED }
            )
        }
    }

    fun prepareExport() {
        if (state.value.busy || state.value.awaitingDocument || state.value.exportData != null) return
        operate(DiagnosticsError.EXPORT) {
            val data = journal.exportJson()
            state.value = state.value.copy(exportData = data)
        }
    }

    fun markDocumentLaunched(): Boolean {
        if (state.value.busy || state.value.awaitingDocument || state.value.exportData == null) return false
        state.value = state.value.copy(awaitingDocument = true)
        return true
    }

    fun cancelExport() {
        state.value = state.value.copy(exportData = null, awaitingDocument = false)
    }

    fun exportFailed() {
        cancelExport()
        state.value = state.value.copy(error = DiagnosticsError.EXPORT)
    }

    fun clear() {
        if (state.value.busy) return
        // Keep the picker reservation until its callback, but revoke its payload immediately.
        state.value = state.value.copy(exportData = null)
        operate(DiagnosticsError.CLEAR) {
            journal.clear()
            state.value = state.value.copy(total = 0, completed = 0, failed = 0, cancelled = 0)
        }
    }

    fun exportTo(write: suspend (String) -> Unit) {
        val data = state.value.exportData
        val authorized = state.value.awaitingDocument
        cancelExport()
        if (!authorized || data == null) {
            state.value = state.value.copy(error = DiagnosticsError.EXPIRED)
            return
        }
        if (state.value.busy) {
            state.value = state.value.copy(error = DiagnosticsError.EXPORT)
            return
        }
        operate(DiagnosticsError.EXPORT) {
            withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                write(data)
            }
        }
    }

    private fun operate(error: DiagnosticsError, block: suspend () -> Unit) {
        state.value = state.value.copy(busy = true, error = null)
        launchCatching(block = {
            try {
                block()
            } finally {
                state.value = state.value.copy(busy = false)
            }
        }, onError = {
            if (it is CancellationException) throw it
            state.value = state.value.copy(error = error)
        })
    }
}
