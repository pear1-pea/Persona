package com.example.persona.features.memory

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.viewModels
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.persona.BuildConfig
import com.example.persona.R
import com.example.persona.domain.model.MemoryType
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MemoryCurationActivity : AppCompatActivity() {
    private val viewModel: MemoryCurationViewModel by viewModels()
    private lateinit var body: LinearLayout
    private lateinit var title: TextView
    private lateinit var enabled: CheckBox
    private lateinit var cloudConsent: CheckBox
    private lateinit var status: TextView
    private lateinit var messages: LinearLayout
    private lateinit var candidates: LinearLayout
    private lateinit var summaries: LinearLayout
    private lateinit var editor: LinearLayout
    private lateinit var content: EditText
    private lateinit var expiry: EditText
    private lateinit var permanent: CheckBox
    private lateinit var shared: CheckBox
    private lateinit var cloudAllowed: CheckBox
    private lateinit var types: RadioGroup
    private lateinit var conflicts: LinearLayout
    private lateinit var confirm: Button
    private lateinit var selected: Button
    private lateinit var local: Button
    private lateinit var cloud: Button
    private lateinit var cancel: Button
    private lateinit var summary: Button
    private var rendering = false
    private var messageRender: List<Any?>? = null
    private var candidateRender: List<Any?>? = null
    private var conflictRender: List<Any?>? = null
    private var summaryRender: List<Any?>? = null
    private var sourceDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        body = column()
        val padding = (20 * resources.displayMetrics.density).toInt()
        body.setPadding(padding, padding, padding, padding)
        val scroll = ScrollView(this).apply { addView(body) }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        setContentView(scroll)
        ViewCompat.requestApplyInsets(scroll)
        button(body, R.string.memory_close) { finish() }
        title = text(body, R.string.curation_title)
        text(body, R.string.curation_policy)
        enabled = check(body, R.string.curation_enable) {
            if (!rendering) viewModel.configure(it, cloudConsent.isChecked)
        }
        text(body, R.string.curation_cloud_notice)
        cloudConsent = check(body, R.string.curation_cloud_consent) {
            if (!rendering) viewModel.configure(enabled.isChecked, it)
        }
        status = text(body, R.string.memory_loading)
        button(body, R.string.memory_retry) { viewModel.reload() }
        button(body, R.string.memory_error_dismiss) { viewModel.dismissError() }
        text(body, R.string.curation_messages)
        messages = column().also(body::addView)
        selected = button(body, R.string.curation_selected) { viewModel.proposeSelected() }
        local = button(body, R.string.curation_local) { viewModel.extractLocal() }
        local.isVisible = BuildConfig.DEBUG
        cloud = button(body, R.string.curation_cloud) {
            AlertDialog.Builder(this).setMessage(R.string.curation_cloud_notice)
                .setNegativeButton(R.string.memory_cancel, null)
                .setPositiveButton(R.string.curation_cloud) { _, _ -> viewModel.extractCloud() }.show()
        }
        cancel = button(body, R.string.curation_cancel_extraction) { viewModel.cancelExtraction() }
        text(body, R.string.curation_candidates)
        candidates = column().also(body::addView)
        createEditor()
        summary = button(body, R.string.curation_summarize) { viewModel.summarize() }
        text(body, R.string.curation_summaries)
        summaries = column().also(body::addView)
        viewModel.initialize(intent.getStringExtra(MemoryActivity.EXTRA_PERSONA_ID),
            intent.getStringExtra(MemoryActivity.EXTRA_PERSONA_NAME).orEmpty(),
            intent.getStringExtra(MemoryActivity.EXTRA_SOURCE_MESSAGE_ID))
        render(viewModel.uiState.value)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.uiState.collect(::render) }
        }
    }

    private fun createEditor() {
        editor = column().also(body::addView)
        text(editor, R.string.curation_editor)
        content = input(editor, R.string.memory_content).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            doAfterTextChanged { captureDraft() }
        }
        types = RadioGroup(this).also(editor::addView)
        MemoryType.entries.forEach { type ->
            types.addView(RadioButton(this).apply {
                id = View.generateViewId()
                tag = type
                setText(typeLabel(type))
                isSaveEnabled = false
            })
        }
        types.setOnCheckedChangeListener { _, _ -> captureDraft() }
        shared = check(editor, R.string.memory_shared_option) { captureDraft() }
        cloudAllowed = check(editor, R.string.memory_cloud_option) { captureDraft() }
        text(editor, R.string.curation_expiry_hint)
        expiry = input(editor, R.string.curation_expiry).apply { doAfterTextChanged { captureDraft() } }
        permanent = check(editor, R.string.curation_permanent) { checked ->
            if (!rendering) {
                rendering = true
                expiry.setText(if (checked) "" else formatMemoryExpiry(System.currentTimeMillis() + 86_400_000))
                rendering = false
                captureDraft()
            }
        }
        text(editor, R.string.curation_conflicts)
        conflicts = column().also(editor::addView)
        button(editor, R.string.curation_retry_conflicts) {
            viewModel.uiState.value.draft?.candidateId?.let(viewModel::selectCandidate)
        }
        confirm = button(editor, R.string.curation_confirm) { viewModel.confirm() }
        button(editor, R.string.memory_cancel) { viewModel.closeDraft() }
    }

    private fun captureDraft() {
        if (rendering) return
        val type = types.findViewById<RadioButton>(types.checkedRadioButtonId)?.tag as? MemoryType ?: return
        viewModel.updateDraft(content.text.toString(), type, shared.isChecked, cloudAllowed.isChecked,
            if (permanent.isChecked) "" else expiry.text.toString())
    }

    private fun render(state: MemoryCurationUiState) {
        rendering = true
        try {
            val available = !state.loading && state.operation == null && !state.personaId.isNullOrBlank()
            val active = available && state.preferences.enabled
            title.text = getString(R.string.curation_persona_title, state.personaName)
            enabled.isChecked = state.preferences.enabled
            enabled.isEnabled = available
            cloudConsent.isChecked = state.preferences.cloudExtractionAllowed
            cloudConsent.isEnabled = active
            status.text = when {
                state.error != null -> getString(when (state.error) {
                    CurationError.INVALID_PERSONA -> R.string.memory_error_invalid_persona
                    CurationError.LOAD -> R.string.curation_error_load
                    CurationError.OPERATION -> R.string.curation_error_operation
                    CurationError.INVALID_DRAFT -> R.string.curation_error_draft
                    CurationError.SOURCE -> R.string.memory_error_source
                    CurationError.NO_SUMMARY -> R.string.curation_no_summary
                })
                state.operation != null || state.loading -> getString(R.string.curation_busy)
                else -> getString(R.string.curation_ready)
            }
            val source = state.messages.firstOrNull { it.id == state.selectedMessageId }
            selected.isEnabled = active && source != null
            local.isEnabled = selected.isEnabled
            cloud.isEnabled = selected.isEnabled && state.preferences.cloudExtractionAllowed && source?.localOnly == false
            cancel.isVisible = state.operation == CurationOperation.EXTRACTING
            summary.isEnabled = active
            renderMessages(state, available)
            renderCandidates(state, available, active)
            renderEditor(state, active)
            renderSummaries(state, available)
            renderSources(state)
        } finally {
            rendering = false
        }
    }

    private fun renderMessages(state: MemoryCurationUiState, available: Boolean) {
        val key = listOf(state.messages, state.selectedMessageId, available)
        if (messageRender == key) return
        messageRender = key
        messages.removeAllViews()
        if (state.messages.isEmpty()) text(messages, R.string.curation_no_messages)
        val group = RadioGroup(this).also(messages::addView)
        state.messages.forEach { message ->
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = getString(R.string.curation_message, message.id, message.content)
                isChecked = message.id == state.selectedMessageId
                isEnabled = available
                isSaveEnabled = false
                setOnClickListener { viewModel.selectMessage(message.id) }
            })
        }
    }

    private fun renderCandidates(state: MemoryCurationUiState, available: Boolean, active: Boolean) {
        val key = listOf(state.candidates, available, active)
        if (candidateRender == key) return
        candidateRender = key
        candidates.removeAllViews()
        if (state.candidates.isEmpty()) text(candidates, R.string.curation_empty)
        state.candidates.forEach { candidate ->
            text(candidates, R.string.curation_candidate).text = getString(R.string.curation_candidate,
                getString(typeLabel(candidate.type)), candidate.content,
                getString(if (candidate.extractorVersion == com.example.persona.core.memory.MemoryExtraction.VERSION)
                    R.string.curation_method_rule else R.string.curation_method_model))
            button(candidates, R.string.curation_edit) { viewModel.selectCandidate(candidate.id) }.isEnabled = active
            button(candidates, R.string.curation_source) { viewModel.showCandidateSource(candidate.id) }.isEnabled = available
            button(candidates, R.string.curation_ignore) { viewModel.ignore(candidate.id) }.isEnabled = available
        }
    }

    private fun renderEditor(state: MemoryCurationUiState, active: Boolean) {
        val draft = state.draft
        editor.isVisible = draft != null
        if (draft == null) return
        setEnabled(editor, active)
        if (content.text.toString() != draft.content) content.setText(draft.content)
        for (index in 0 until types.childCount) {
            val radio = types.getChildAt(index) as RadioButton
            if (radio.tag == draft.type) types.check(radio.id)
        }
        shared.isChecked = draft.shared
        cloudAllowed.isChecked = draft.cloudAllowed
        permanent.isChecked = draft.expiryInput.isEmpty()
        if (expiry.text.toString() != draft.expiryInput) expiry.setText(draft.expiryInput)
        expiry.isEnabled = active && !permanent.isChecked
        content.error = if (draft.content.isBlank() || draft.content.length > 400)
            getString(R.string.memory_content_invalid) else null
        expiry.error = if (!draft.valid && draft.content.isNotBlank() && draft.content.length <= 400)
            getString(R.string.curation_error_expiry) else null
        confirm.isEnabled = active && draft.valid && draft.resolutionChosen && state.conflictsLoaded
        val key = listOf(state.possibleConflicts, draft.replaceId, draft.expectedRevision, draft.resolutionChosen, active)
        if (conflictRender == key) return
        conflictRender = key
        conflicts.removeAllViews()
        val group = RadioGroup(this).also(conflicts::addView)
        group.addView(RadioButton(this).apply {
            id = View.generateViewId()
            setText(R.string.curation_create_new)
            isChecked = draft.resolutionChosen && draft.replaceId == null
            isEnabled = active
            setOnClickListener { viewModel.chooseNew() }
        })
        state.possibleConflicts.forEach { memory ->
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                tag = memory.id
                text = getString(R.string.curation_replace, memory.id, memory.revision, memory.content)
                isChecked = draft.replaceId == memory.id
                isEnabled = active
                setOnClickListener { viewModel.chooseReplacement(memory.id) }
            })
        }
    }

    private fun renderSummaries(state: MemoryCurationUiState, available: Boolean) {
        val key = listOf(state.summaries, available)
        if (summaryRender == key) return
        summaryRender = key
        summaries.removeAllViews()
        if (state.summaries.isEmpty()) text(summaries, R.string.curation_no_summary)
        state.summaries.forEach { memory ->
            text(summaries, R.string.curation_summaries).text = memory.content
            button(summaries, R.string.curation_all_sources) { viewModel.showSummarySources(memory.id) }.isEnabled = available
        }
    }

    private fun renderSources(state: MemoryCurationUiState) {
        if (state.sources == null) {
            sourceDialog?.dismiss()
            sourceDialog = null
            return
        }
        val message = state.sources.joinToString("\n\n") {
            getString(R.string.curation_message, it.messageId, it.content ?: getString(R.string.memory_source_unavailable))
        }.ifEmpty { getString(R.string.memory_source_unavailable) }
        if (sourceDialog == null) {
            sourceDialog = AlertDialog.Builder(this).setTitle(R.string.memory_source_title).setMessage(message)
                .setPositiveButton(R.string.memory_source_close) { _, _ -> viewModel.closeSources() }
                .setOnCancelListener { viewModel.closeSources() }.show().also {
                    it.findViewById<TextView>(android.R.id.message)?.setTextIsSelectable(true)
                }
        } else sourceDialog?.setMessage(message)
    }

    private fun typeLabel(type: MemoryType) = when (type) {
        MemoryType.PREFERENCE -> R.string.memory_type_preference
        MemoryType.FACT -> R.string.memory_type_fact
        MemoryType.GOAL -> R.string.memory_type_goal
        MemoryType.RELATIONSHIP -> R.string.memory_type_relationship
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun text(parent: LinearLayout, resource: Int) = TextView(this).apply {
        setText(resource)
        setTextIsSelectable(true)
        parent.addView(this)
    }
    private fun button(parent: LinearLayout, resource: Int, action: () -> Unit) = Button(this).apply {
        setText(resource)
        setOnClickListener { action() }
        parent.addView(this)
    }
    private fun check(parent: LinearLayout, resource: Int, action: (Boolean) -> Unit) = CheckBox(this).apply {
        setText(resource)
        isSaveEnabled = false
        setOnCheckedChangeListener { _, checked -> action(checked) }
        parent.addView(this)
    }
    private fun input(parent: LinearLayout, resource: Int) = EditText(this).apply {
        setHint(resource)
        isSaveEnabled = false
        parent.addView(this)
    }
    private fun setEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is android.view.ViewGroup) {
            for (index in 0 until view.childCount) setEnabled(view.getChildAt(index), enabled)
        }
    }

    override fun onDestroy() {
        sourceDialog?.dismiss()
        super.onDestroy()
    }
}
