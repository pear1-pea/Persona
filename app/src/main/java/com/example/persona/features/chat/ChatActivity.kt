package com.example.persona.features.chat

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import coil.transform.CircleCropTransformation
import com.example.persona.R
import com.example.persona.core.util.MarkdownHelper
import com.example.persona.core.util.observeErrorEvents
import com.example.persona.databinding.ActivityChatBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private val viewModel: ChatViewModel by viewModels()

    @Inject
    lateinit var markdownHelper: MarkdownHelper

    // Use lazy to initialize adapter
    private val adapter: ChatAdapter by lazy { ChatAdapter(markdownHelper) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        observeErrorEvents(viewModel, binding.root)

        val personaName = intent.getStringExtra("PERSONA_NAME") ?: "AI"
        val isSymbiosis = intent.getBooleanExtra("IS_SYMBIOSIS", false)

        binding.tvChatTitle.text = personaName

        viewModel.loadPersonaInfo(personaName)

        binding.rvChatMessages.layoutManager = LinearLayoutManager(this).apply {
            reverseLayout = true // reverse layout so newest messages appear at the bottom
//            stackFromEnd = true
        }
        binding.rvChatMessages.adapter = adapter

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Observe Paging message flow
                launch {
                    viewModel.messagesFlow.collectLatest { pagingData ->
                        adapter.submitData(pagingData)
                    }
                }
                // Observe Cloud/Edge mode 
                launch {
                    viewModel.isCloudMode.collect { isCloud ->
                        if (isCloud) {
                            binding.indicatorCard.setCardBackgroundColor(ContextCompat.getColor(this@ChatActivity, R.color.accent_cyan))
                            binding.tvModeLabel.text = "CLOUD"
                            binding.tvModeLabel.setTextColor(ContextCompat.getColor(this@ChatActivity, R.color.text_secondary))
                        } else {
                            binding.indicatorCard.setCardBackgroundColor(Color.parseColor("#4CAF50"))
                            binding.tvModeLabel.text = "EDGE"
                            binding.tvModeLabel.setTextColor(Color.parseColor("#4CAF50"))
                        }
                    }
                }

                // Observe persona and load avatar
                launch {
                    viewModel.currentPersona.collect { persona ->
                        if (persona != null) {
                            binding.ivChatAvatar.load(persona.avatarUrl) {
                                crossfade(true)
                                placeholder(R.drawable.ic_launcher_background)
                                transformations(CircleCropTransformation())
                            }
                        }
                    }
                }
            }
        }

        binding.btnSend.setOnClickListener {
            val text = binding.etMessage.text.toString()
            if (text.isNotEmpty()) {
                viewModel.sendMessage(text, personaName, isSymbiosis)
                binding.etMessage.setText("")
                binding.rvChatMessages.scrollToPosition(0)
            }
        }

        binding.etMessage.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard(v)
                true
            } else {
                false
            }
        }
    }

    private fun hideKeyboard(view: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
        view.clearFocus()
    }
}