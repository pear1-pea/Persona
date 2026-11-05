package com.example.persona.features.memory

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.persona.R
import com.example.persona.databinding.ActivityMemoryBinding
import com.example.persona.domain.model.Memory
import com.example.persona.domain.model.MemoryType
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MemoryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMemoryBinding
    private val viewModel: MemoryViewModel by viewModels()
    private lateinit var adapter: MemoryListAdapter
    private var rendering = false
    private var deleteDialog: AlertDialog? = null
    private var deleteTarget: Memory? = null
    private var sourceDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMemoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        adapter = MemoryListAdapter(viewModel::editMemory, viewModel::requestDelete, viewModel::showSource)
        binding.memoryList.layoutManager = LinearLayoutManager(this)
        binding.memoryList.adapter = adapter
        viewModel.initialize(intent.getStringExtra(EXTRA_PERSONA_ID),
            intent.getStringExtra(EXTRA_PERSONA_NAME).orEmpty(),
            intent.getStringExtra(EXTRA_SOURCE_MESSAGE_ID))

        binding.btnClose.setOnClickListener { finish() }
        binding.btnInstructions.setOnClickListener {
            binding.instructions.isVisible = !binding.instructions.isVisible
            binding.btnInstructions.setText(if (binding.instructions.isVisible)
                R.string.memory_instructions_close else R.string.memory_instructions)
        }
        binding.btnAdd.setOnClickListener { viewModel.newMemory() }
        binding.btnCuration.setOnClickListener {
            startActivity(Intent(this, MemoryCurationActivity::class.java).apply {
                putExtra(EXTRA_PERSONA_ID, intent.getStringExtra(EXTRA_PERSONA_ID))
                putExtra(EXTRA_PERSONA_NAME, intent.getStringExtra(EXTRA_PERSONA_NAME))
                putExtra(EXTRA_SOURCE_MESSAGE_ID, intent.getStringExtra(EXTRA_SOURCE_MESSAGE_ID))
            })
        }
        binding.btnSave.setOnClickListener { viewModel.save() }
        binding.btnCancel.setOnClickListener { viewModel.cancelDraft() }
        binding.btnRetry.setOnClickListener { viewModel.retryLoading() }
        binding.btnDismissError.setOnClickListener { viewModel.acknowledgeError() }
        binding.btnDraftSource.setOnClickListener {
            viewModel.uiState.value.draft?.sourceMessageId?.let(viewModel::showSource)
        }
        binding.etContent.doAfterTextChanged { captureDraft() }
        binding.checkShared.setOnCheckedChangeListener { _, _ -> captureDraft() }
        binding.checkCloud.setOnCheckedChangeListener { _, _ -> captureDraft() }
        binding.typeGroup.setOnCheckedChangeListener { _, _ -> captureDraft() }
        binding.etExpiry.doAfterTextChanged { captureDraft(expiryEdited = true) }
        binding.checkPermanent.setOnCheckedChangeListener { _, checked ->
            if (!rendering) {
                rendering = true
                binding.etExpiry.setText(if (checked) "" else
                    formatMemoryExpiry(System.currentTimeMillis() + 86_400_000))
                rendering = false
                captureDraft(expiryEdited = true)
            }
        }
        render(viewModel.uiState.value)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect(::render)
            }
        }
    }

    private fun captureDraft(expiryEdited: Boolean = false) {
        if (rendering) return
        val draft = viewModel.uiState.value.draft ?: return
        val type = when (binding.typeGroup.checkedRadioButtonId) {
            R.id.typeFact -> MemoryType.FACT
            R.id.typeGoal -> MemoryType.GOAL
            R.id.typeRelationship -> MemoryType.RELATIONSHIP
            else -> MemoryType.PREFERENCE
        }
        viewModel.updateDraft(draft.copy(content = binding.etContent.text.toString(), type = type,
            shared = binding.checkShared.isChecked, cloudAllowed = binding.checkCloud.isChecked,
            expiryInput = if (expiryEdited) {
                if (binding.checkPermanent.isChecked) "" else binding.etExpiry.text.toString()
            } else draft.expiryInput))
    }

    private fun render(state: MemoryUiState) {
        rendering = true
        try {
            binding.tvTitle.text = getString(R.string.memory_title, state.personaName)
            val busy = state.operation != null
            val available = !busy && !state.personaId.isNullOrBlank()
            binding.progress.isVisible = busy || state.loading
            binding.tvOperation.isVisible = busy || state.loading
            binding.tvOperation.setText(when (state.operation) {
                MemoryOperation.SAVING -> R.string.memory_saving
                MemoryOperation.DELETING -> R.string.memory_deleting
                null -> R.string.memory_loading
            })
            binding.btnAdd.isEnabled = available && state.draft == null && state.pendingDelete == null
            binding.btnCuration.isEnabled = available
            binding.editor.isVisible = state.draft != null
            val draft = state.draft
            val content = draft?.content.orEmpty()
            if (binding.etContent.text.toString() != content) binding.etContent.setText(content)
            binding.tvEditorTitle.text = if (draft?.id == null) getString(R.string.memory_new) else
                getString(R.string.memory_edit_title, draft.revision)
            binding.typeGroup.check(when (draft?.type) {
                MemoryType.FACT -> R.id.typeFact
                MemoryType.GOAL -> R.id.typeGoal
                MemoryType.RELATIONSHIP -> R.id.typeRelationship
                else -> R.id.typePreference
            })
            binding.checkShared.isChecked = draft?.shared == true
            binding.checkCloud.isChecked = draft?.cloudAllowed == true
            val expiryInput = draft?.expiryInput ?: formatMemoryExpiry(draft?.expiresAt)
            binding.checkPermanent.isChecked = expiryInput.isEmpty()
            if (binding.etExpiry.text.toString() != expiryInput) binding.etExpiry.setText(expiryInput)
            binding.etExpiry.isEnabled = available && !binding.checkPermanent.isChecked
            binding.checkPermanent.isEnabled = available
            binding.etExpiry.error = if (draft != null && !runCatching {
                parseMemoryExpiry(expiryInput)?.let { it > System.currentTimeMillis() } ?: true
            }.getOrDefault(false)) getString(R.string.curation_error_expiry) else null
            binding.tvCount.text = getString(R.string.memory_content_count, content.length)
            binding.contentInput.error = if (draft != null && !draft.valid)
                getString(R.string.memory_content_invalid) else null
            binding.etContent.isEnabled = available
            binding.checkShared.isEnabled = available
            binding.checkCloud.isEnabled = available
            for (index in 0 until binding.typeGroup.childCount) {
                binding.typeGroup.getChildAt(index).isEnabled = available
            }
            binding.btnSave.isEnabled = available && draft?.valid == true
            binding.btnCancel.isEnabled = available
            binding.btnDraftSource.isEnabled = available
            binding.btnDraftSource.isVisible = draft?.sourceMessageId != null
            binding.tvManualSource.isVisible = draft?.sourceMessageId == null
            binding.btnDraftSource.text = draft?.sourceMessageId?.let {
                getString(R.string.memory_source_button, it)
            }.orEmpty()
            adapter.actionsEnabled = available && draft == null && state.pendingDelete == null
            adapter.submitList(state.memories.takeIf { it.isNotEmpty() })
            binding.tvEmpty.isVisible = state.memories.isEmpty() && !state.loading
            renderError(state, available)
            renderDelete(state)
            renderSource(state.source)
        } finally {
            rendering = false
        }
    }

    private fun renderError(state: MemoryUiState, available: Boolean) {
        binding.errorPanel.isVisible = state.error != null
        binding.btnRetry.isVisible = state.error == MemoryError.LOAD_FAILED
        binding.btnRetry.isEnabled = available
        state.error?.let {
            binding.tvError.setText(when (it) {
                MemoryError.INVALID_PERSONA -> R.string.memory_error_invalid_persona
                MemoryError.INVALID_CONTENT -> R.string.memory_content_invalid
                MemoryError.LOAD_FAILED -> R.string.memory_error_load
                MemoryError.SAVE_FAILED -> R.string.memory_error_save
                MemoryError.DELETE_FAILED -> R.string.memory_error_delete
                MemoryError.SOURCE_FAILED -> R.string.memory_error_source
            })
        }
    }

    private fun renderDelete(state: MemoryUiState) {
        val target = state.pendingDelete
        if (target != deleteTarget) {
            deleteDialog?.dismiss()
            deleteDialog = null
            deleteTarget = target
        }
        if (target == null) return
        if (deleteDialog == null) {
            deleteDialog = AlertDialog.Builder(this)
                .setTitle(R.string.memory_delete_title)
                .setMessage(getString(R.string.memory_delete_confirmation, target.content, target.revision))
                .setNegativeButton(R.string.memory_cancel) { _, _ -> viewModel.cancelDelete() }
                .setPositiveButton(R.string.memory_delete, null)
                .setOnCancelListener { viewModel.cancelDelete() }
                .create().also { dialog ->
                    dialog.show()
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        viewModel.confirmDelete(target)
                    }
                }
        }
        val busy = state.operation != null
        deleteDialog?.apply {
            setCancelable(!busy)
            setCanceledOnTouchOutside(!busy)
            getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = !busy
            getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = !busy
        }
    }

    private fun renderSource(source: MemorySource?) {
        if (source == null) {
            sourceDialog?.dismiss()
            sourceDialog = null
            return
        }
        val context = when {
            source.loading -> getString(R.string.memory_source_loading)
            source.content != null -> source.content
            else -> getString(R.string.memory_source_unavailable)
        }
        val message = getString(R.string.memory_source_context, source.messageId, context)
        if (sourceDialog == null) {
            sourceDialog = AlertDialog.Builder(this)
                .setTitle(R.string.memory_source_title)
                .setMessage(message)
                .setPositiveButton(R.string.memory_source_close) { _, _ -> viewModel.closeSource() }
                .setOnCancelListener { viewModel.closeSource() }
                .show().also {
                    it.findViewById<TextView>(android.R.id.message)?.setTextIsSelectable(true)
                }
        } else {
            sourceDialog?.setMessage(message)
        }
    }

    override fun onDestroy() {
        deleteDialog?.dismiss()
        sourceDialog?.dismiss()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PERSONA_ID = "PERSONA_ID"
        const val EXTRA_PERSONA_NAME = "PERSONA_NAME"
        const val EXTRA_SOURCE_MESSAGE_ID = "SOURCE_MESSAGE_ID"
    }
}
