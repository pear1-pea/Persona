package com.example.persona.features.creation

import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.persona.R
import com.example.persona.databinding.ActivityCreatePersonaBinding
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class CreatePersonaActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCreatePersonaBinding
    private val viewModel: CreatePersonaViewModel by viewModels()
    private var rendering = false
    private var renderedDraft: CreationDraft? = null
    private val defaultTraits by lazy { resources.getStringArray(R.array.creation_default_traits).toList() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCreatePersonaBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // SavedStateHandle owns these fields, including dynamically generated chips.
        binding.etName.isSaveEnabled = false
        binding.etBackstory.isSaveEnabled = false
        binding.switchPublic.isSaveEnabled = false
        binding.chipGroupTraits.isSaveEnabled = false
        viewModel.initializeDraft(initialDraft())
        render(viewModel.uiState.value)
        binding.etName.doAfterTextChanged { captureDraft() }
        binding.etBackstory.doAfterTextChanged { captureDraft() }
        binding.switchPublic.setOnCheckedChangeListener { _, _ -> captureDraft() }

        binding.btnAiGenerate.setOnClickListener {
            captureDraft()
            val draft = viewModel.uiState.value.draft ?: return@setOnClickListener
            viewModel.generateAI(draft.story + " " + draft.name)
            val keyboard = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            keyboard.hideSoftInputFromWindow(binding.root.windowToken, 0)
        }
        binding.btnCreate.setOnClickListener {
            captureDraft()
            val draft = viewModel.uiState.value.draft ?: return@setOnClickListener
            viewModel.savePersona(draft.editingId, draft.name, draft.story, draft.traits, draft.isPublic)
        }
        binding.btnDeletePersona.setOnClickListener {
            val id = viewModel.uiState.value.draft?.editingId ?: return@setOnClickListener
            AlertDialog.Builder(this)
                .setTitle(R.string.creation_delete)
                .setMessage(R.string.creation_delete_confirmation)
                .setNegativeButton(R.string.creation_cancel, null)
                .setPositiveButton(R.string.creation_delete) { _, _ -> viewModel.deletePersona(id) }
                .show()
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    render(state)
                    if (state.completed) {
                        setResult(RESULT_OK)
                        finish()
                    }
                    state.error?.let { error ->
                        val message = when (error) {
                            CreationError.INVALID_PROFILE -> R.string.creation_invalid_profile
                            CreationError.GENERATION_FAILED -> R.string.creation_generation_failed
                            CreationError.GENERATION_TIMEOUT -> R.string.creation_generation_timeout
                            CreationError.SAVE_FAILED -> R.string.creation_save_failed
                            CreationError.DELETE_FAILED -> R.string.creation_delete_failed
                        }
                        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
                        viewModel.acknowledgeError()
                    }
                }
            }
        }
    }

    private fun initialDraft(): CreationDraft {
        val id = intent.getStringExtra(EXTRA_PERSONA_ID)
        val traits = if (id == null) emptyList() else
            intent.getStringArrayListExtra(EXTRA_PERSONA_TRAITS).orEmpty()
        return CreationDraft(
            editingId = id,
            name = if (id == null) "" else intent.getStringExtra(EXTRA_PERSONA_NAME).orEmpty(),
            story = if (id == null) "" else intent.getStringExtra(EXTRA_PERSONA_BACKSTORY).orEmpty(),
            traitOptions = if (id == null) defaultTraits else traits,
            traits = traits,
            isPublic = intent.getBooleanExtra(EXTRA_PERSONA_PUBLIC, true)
        )
    }

    private fun captureDraft() {
        if (rendering) return
        val draft = viewModel.uiState.value.draft ?: return
        val chips = (0 until binding.chipGroupTraits.childCount).map {
            binding.chipGroupTraits.getChildAt(it) as Chip
        }
        viewModel.updateDraft(draft.copy(
            name = binding.etName.text.toString(),
            story = binding.etBackstory.text.toString(),
            traitOptions = chips.map { it.text.toString() },
            traits = chips.filter { it.isChecked }.map { it.text.toString() },
            isPublic = binding.switchPublic.isChecked
        ))
    }

    private fun render(state: CreationUiState) {
        rendering = true
        try {
            state.draft?.let { draft ->
                if (binding.etName.text.toString() != draft.name) binding.etName.setText(draft.name)
                if (binding.etBackstory.text.toString() != draft.story) binding.etBackstory.setText(draft.story)
                binding.switchPublic.isChecked = draft.isPublic
                if (renderedDraft?.traitOptions != draft.traitOptions || renderedDraft?.traits != draft.traits) {
                    binding.chipGroupTraits.removeAllViews()
                    val options = draft.traitOptions.ifEmpty { defaultTraits }
                    options.forEach { trait ->
                        binding.chipGroupTraits.addView(createChip(trait, trait in draft.traits))
                    }
                }
                renderedDraft = draft
                binding.btnDeletePersona.visibility = if (draft.editingId == null) View.GONE else View.VISIBLE
                binding.btnCreate.setText(when {
                    state.operation == CreationOperation.SAVING -> R.string.creation_saving
                    draft.editingId != null -> R.string.creation_save_changes
                    else -> R.string.creation_create
                })
            }
            val busy = state.operation != null
            val enabled = !busy && !state.completed
            binding.etName.isEnabled = enabled
            binding.etBackstory.isEnabled = enabled
            binding.switchPublic.isEnabled = enabled
            binding.chipGroupTraits.isEnabled = enabled
            for (index in 0 until binding.chipGroupTraits.childCount) {
                binding.chipGroupTraits.getChildAt(index).isEnabled = enabled
            }
            binding.btnAiGenerate.isEnabled = enabled
            binding.btnCreate.isEnabled = enabled
            binding.btnDeletePersona.isEnabled = enabled
            binding.progressBarHorizontal.isIndeterminate = true
            binding.progressBarHorizontal.visibility = if (busy) View.VISIBLE else View.GONE
            binding.btnAiGenerate.setText(if (state.operation == CreationOperation.GENERATING)
                R.string.creation_generating else R.string.creation_generate)
            binding.btnDeletePersona.setText(if (state.operation == CreationOperation.DELETING)
                R.string.creation_deleting else R.string.creation_delete)
        } finally {
            rendering = false
        }
    }

    private fun createChip(label: String, selected: Boolean): Chip = Chip(this).apply {
        id = View.generateViewId()
        isSaveEnabled = false
        text = label
        isCheckable = true
        isChecked = selected
        setChipBackgroundColorResource(R.color.selector_chip_background)
        setTextColor(ContextCompat.getColorStateList(context, R.color.selector_chip_text))
        chipStrokeWidth = 0f
        checkedIconTint = ContextCompat.getColorStateList(context, R.color.white)
        setOnCheckedChangeListener { _, _ -> captureDraft() }
    }

    companion object {
        const val EXTRA_PERSONA_ID = "PERSONA_EDIT_ID"
        const val EXTRA_PERSONA_NAME = "PERSONA_EDIT_NAME"
        const val EXTRA_PERSONA_BACKSTORY = "PERSONA_EDIT_BACKSTORY"
        const val EXTRA_PERSONA_TRAITS = "PERSONA_EDIT_TRAITS"
        const val EXTRA_PERSONA_PUBLIC = "PERSONA_EDIT_PUBLIC"
    }
}
