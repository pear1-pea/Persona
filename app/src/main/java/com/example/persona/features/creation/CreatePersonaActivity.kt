package com.example.persona.features.creation

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.persona.R
import com.example.persona.core.util.observeErrorEvents
import com.example.persona.databinding.ActivityCreatePersonaBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@AndroidEntryPoint
class CreatePersonaActivity : AppCompatActivity() {

    private var progressJob: Job? = null

    private lateinit var binding: ActivityCreatePersonaBinding
    private val viewModel: CreatePersonaViewModel by viewModels()

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCreatePersonaBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupChips() 

        binding.btnAiGenerate.setOnClickListener {
            val keywords = binding.etBackstory.text.toString() + " " + binding.etName.text.toString()

            viewModel.generateAI(keywords) 

            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(binding.root.windowToken, 0)

            android.widget.Toast.makeText(this, "Summoning AI spirits... 🪄", android.widget.Toast.LENGTH_SHORT).show()
        }

        binding.btnCreate.setOnClickListener {
            val name = binding.etName.text.toString()
            val story = binding.etBackstory.text.toString()
            val selectedTraits = binding.chipGroupTraits.checkedChipIds.map { id ->
                val chip = binding.chipGroupTraits.findViewById<com.google.android.material.chip.Chip>(id)
                chip.text.toString() 
            }

            if (name.isBlank() || story.isBlank() || selectedTraits.isEmpty()) {
                android.widget.Toast.makeText(this, "Please fill in all fields", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            viewModel.createPersona(name, story, selectedTraits)
        }

        // Listen for ViewModel events
        lifecycleScope.launch {
            viewModel.event.collect { event ->
                when (event) {
                    is CreationEvent.Loading -> {
                        startFakeProgress()
                        setUiEnabled(false)
                        binding.btnAiGenerate.text = "Generating..."
                    }
                    is CreationEvent.Generated -> {
                        stopFakeProgress(success = true)
                        setUiEnabled(true)
                        binding.btnAiGenerate.text = "✨ AI Auto-Generate"

                        binding.etName.setText(event.name)
                        binding.etBackstory.setText(event.story)
                        binding.chipGroupTraits.removeAllViews()

                        // Add generated trait chips (selected)
                        event.traits.forEach { trait ->
                            val chip = createChip(trait, isSelected = true)
                            binding.chipGroupTraits.addView(chip)
                        }
                    }
                    is CreationEvent.Success -> {
                        android.widget.Toast.makeText(this@CreatePersonaActivity, "Persona Created!", android.widget.Toast.LENGTH_SHORT).show()
                        finish()
                    }

                    is CreationEvent.Error -> {
                        stopFakeProgress(success = false)
                        setUiEnabled(true)
                        binding.btnAiGenerate.text = "✨ AI Auto-Generate"
                    }
                }
            }
        }
        observeErrorEvents(viewModel, binding.root)
    }

    
    private fun createChip(label: String, isSelected: Boolean = false): com.google.android.material.chip.Chip {
        return com.google.android.material.chip.Chip(this).apply {
            text = label
            isCheckable = true
            isChecked = isSelected

            setChipBackgroundColorResource(R.color.selector_chip_background)
            setTextColor(androidx.core.content.ContextCompat.getColorStateList(context, R.color.selector_chip_text))

            // Visual tweaks
            chipStrokeWidth = 0f
            checkedIconTint = androidx.core.content.ContextCompat.getColorStateList(context, R.color.white)
        }
    }

    private fun setupChips() {
        val defaultTraits = listOf("Witty", "Mysterious", "Kind", "Brave", "Romantic", "Stoic", "Analytical")

        binding.chipGroupTraits.removeAllViews()

        for (trait in defaultTraits) {
            val chip = createChip(trait, isSelected = false)
            binding.chipGroupTraits.addView(chip)
        }
    }

    private fun setUiEnabled(enabled: Boolean) {
        binding.etName.isEnabled = enabled
        binding.etBackstory.isEnabled = enabled
        binding.chipGroupTraits.isEnabled = enabled
        binding.btnAiGenerate.isEnabled = enabled // 按钮本身在 event.Loading 中会被特殊处理
        binding.btnCreate.isEnabled = enabled
    }

    override fun onDestroy() {
        super.onDestroy()
        progressJob?.cancel()
    }

    private fun startFakeProgress() {
        progressJob?.cancel()

        binding.progressBarHorizontal.visibility = View.VISIBLE
        binding.progressBarHorizontal.progress = 0

        progressJob = lifecycleScope.launch {
            val maxFakeProgress = 98
            val fakeDurationMs = 10000L
            val interval = 50L

            val totalSteps = fakeDurationMs.toFloat() / interval.toFloat()
            val stepFloat = maxFakeProgress.toFloat() / totalSteps

            while (binding.progressBarHorizontal.progress < maxFakeProgress && isActive) {
                delay(interval)
                var increment = stepFloat.toInt().coerceAtLeast(1)
                val remaining = maxFakeProgress - binding.progressBarHorizontal.progress
                if (increment > remaining) {
                    increment = remaining
                }
                binding.progressBarHorizontal.progress += increment
            }
        }
    }

    private fun stopFakeProgress(success: Boolean) {
        progressJob?.cancel()
        if (success) {
            binding.progressBarHorizontal.progress = 100
            binding.progressBarHorizontal.postDelayed({
                binding.progressBarHorizontal.visibility = View.GONE
            }, 300)
        } else {
            binding.progressBarHorizontal.visibility = View.GONE
            binding.progressBarHorizontal.progress = 0
        }
    }
}