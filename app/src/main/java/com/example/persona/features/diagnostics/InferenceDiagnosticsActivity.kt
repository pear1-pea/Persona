package com.example.persona.features.diagnostics

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.persona.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.IOException

@AndroidEntryPoint
class InferenceDiagnosticsActivity : AppCompatActivity() {
    private val viewModel: InferenceDiagnosticsViewModel by viewModels()
    private lateinit var counts: TextView
    private lateinit var status: TextView
    private lateinit var export: Button
    private lateinit var clear: Button
    private lateinit var refresh: Button

    private val createDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) {
            viewModel.cancelExport()
        } else {
            val resolver = applicationContext.contentResolver
            viewModel.exportTo { data ->
                currentCoroutineContext().ensureActive()
                val output = resolver.openOutputStream(uri, "wt")
                    ?: throw IOException("Document unavailable")
                output.use {
                    currentCoroutineContext().ensureActive()
                    it.write(data.toByteArray(Charsets.UTF_8))
                    it.flush()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        title = getString(R.string.diagnostics_title)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        val scroll = ScrollView(this).apply { addView(body) }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        setContentView(scroll)
        ViewCompat.requestApplyInsets(scroll)
        fun text(resource: Int) = TextView(this).apply {
            setText(resource)
            setPadding(0, 12, 0, 12)
            body.addView(this)
        }
        fun button(resource: Int, action: () -> Unit) = Button(this).apply {
            setText(resource)
            body.addView(this, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            setOnClickListener { action() }
        }
        button(R.string.diagnostics_close) { finish() }
        text(R.string.diagnostics_title).textSize = 22f
        text(R.string.diagnostics_policy)
        text(R.string.diagnostics_quality_notice)
        counts = text(R.string.diagnostics_loading)
        status = text(R.string.diagnostics_loading).apply {
            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        refresh = button(R.string.diagnostics_refresh) { viewModel.refresh() }
        export = button(R.string.diagnostics_export) { viewModel.prepareExport() }
        clear = button(R.string.diagnostics_clear) {
            AlertDialog.Builder(this)
                .setMessage(R.string.diagnostics_clear_confirm)
                .setNegativeButton(R.string.diagnostics_cancel, null)
                .setPositiveButton(R.string.diagnostics_clear) { _, _ -> viewModel.clear() }
                .show()
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.uiState.collect(::render) }
        }
    }

    private fun render(state: InferenceDiagnosticsUiState) {
        counts.text = getString(R.string.diagnostics_counts, state.total, state.completed,
            state.failed, state.cancelled, state.total - state.completed - state.failed - state.cancelled)
        status.setText(when {
            state.error != null -> when (state.error) {
                DiagnosticsError.LOAD -> R.string.diagnostics_error_load
                DiagnosticsError.CLEAR -> R.string.diagnostics_error_clear
                DiagnosticsError.EXPORT -> R.string.diagnostics_error_export
                DiagnosticsError.EXPIRED -> R.string.diagnostics_error_expired
            }
            state.busy -> R.string.diagnostics_loading
            state.awaitingDocument -> R.string.diagnostics_waiting
            else -> R.string.diagnostics_ready
        })
        refresh.isEnabled = !state.busy
        clear.isEnabled = !state.busy
        export.isEnabled = !state.busy && !state.awaitingDocument && state.exportData == null
        if (viewModel.markDocumentLaunched()) {
            try {
                createDocument.launch("persona-inference-diagnostics.json")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                viewModel.exportFailed()
            }
        }
    }
}
