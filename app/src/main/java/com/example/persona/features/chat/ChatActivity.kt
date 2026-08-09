package com.example.persona.features.chat

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.example.persona.R
import com.example.persona.core.ai.EngineState
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

    private val adapter: ChatAdapter by lazy { ChatAdapter(markdownHelper) }
    private lateinit var messagesLayoutManager: LinearLayoutManager
    private var shouldFollowLatestMessage = true
    private var didPositionInitialMessages = false

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        observeErrorEvents(viewModel, binding.root)

        val personaId = intent.getStringExtra("PERSONA_ID") ?: ""
        val personaName = intent.getStringExtra("PERSONA_NAME") ?: "AI"

        binding.tvChatTitle.text = personaName

        shouldFollowLatestMessage = true
        didPositionInitialMessages = false
        viewModel.loadPersonaInfo(personaId)

        messagesLayoutManager = LinearLayoutManager(this).apply {
            reverseLayout = false
            stackFromEnd = true
        }
        binding.rvChatMessages.layoutManager = messagesLayoutManager
        binding.rvChatMessages.itemAnimator = null
        binding.rvChatMessages.adapter = adapter
        binding.rvChatMessages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING ||
                    newState == RecyclerView.SCROLL_STATE_IDLE
                ) {
                    shouldFollowLatestMessage = isAtLatestMessage()
                }
            }
        })

        adapter.addOnPagesUpdatedListener {
            if (adapter.itemCount == 0) return@addOnPagesUpdatedListener
            if (!didPositionInitialMessages || shouldFollowLatestMessage) {
                didPositionInitialMessages = true
                scrollToLatestMessage()
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.messagesFlow.collectLatest { pagingData ->
                        adapter.submitData(pagingData)
                    }
                }

                launch {
                    viewModel.isCloudMode.collect { isCloud ->
                        binding.indicatorCard.setCardBackgroundColor(if (isCloud) ContextCompat.getColor(this@ChatActivity, R.color.accent_cyan) else "#4CAF50".toColorInt())
                        binding.tvModeLabel.text = if (isCloud) "CLOUD" else "LOCAL"
                        binding.tvModeLabel.setTextColor(if (isCloud) ContextCompat.getColor(this@ChatActivity, R.color.text_secondary) else "#4CAF50".toColorInt())
                    }
                }

                launch {
                    viewModel.localEngineState.collect { state ->
                        binding.tvChatStatus.text = when (state) {
                            EngineState.Idle -> "CLOUD READY"
                            EngineState.Initializing -> "LOCAL LOADING"
                            EngineState.Ready -> "LOCAL READY"
                            is EngineState.Error -> "CLOUD FALLBACK"
                        }
                        binding.tvChatStatus.setTextColor(
                            if (state == EngineState.Ready) "#4CAF50".toColorInt()
                            else ContextCompat.getColor(this@ChatActivity, R.color.accent_cyan)
                        )
                    }
                }

                launch {
                    viewModel.isGenerating.collect { isGenerating ->
                        binding.btnSend.setImageResource(
                            if (isGenerating) android.R.drawable.ic_menu_close_clear_cancel
                            else android.R.drawable.ic_menu_send
                        )
                        binding.btnSend.contentDescription = if (isGenerating) "停止生成" else "发送消息"
                    }
                }

                launch {
                    viewModel.currentPersona.collect { persona ->
                        if (persona != null) {
                            binding.ivChatAvatar.load(persona.avatarUrl) {
                                crossfade(false)
                                placeholder(R.drawable.ic_launcher_background)
                                transformations(CircleCropTransformation())
                            }
                        }
                    }
                }
            }
        }

        binding.btnSend.setOnClickListener {
            if (viewModel.isGenerating.value) {
                viewModel.stopGenerating()
                return@setOnClickListener
            }

            val text = binding.etMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                shouldFollowLatestMessage = true
                viewModel.sendMessage(text)
                binding.etMessage.setText("")
                scrollToLatestMessage()
            }
        }

        binding.btnClearConversation.setOnClickListener {
            confirmClearConversation()
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
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
        view.clearFocus()
    }

    private fun isAtLatestMessage(): Boolean {
        val lastVisiblePosition = messagesLayoutManager.findLastVisibleItemPosition()
        val latestPosition = adapter.itemCount - 1
        return lastVisiblePosition == RecyclerView.NO_POSITION ||
            latestPosition < 0 ||
            lastVisiblePosition >= latestPosition - 1
    }

    private fun scrollToLatestMessage() {
        if (adapter.itemCount > 0) {
            binding.rvChatMessages.scrollToPosition(adapter.itemCount - 1)
        }
    }

    private fun confirmClearConversation() {
        val personaName = viewModel.currentPersona.value?.name ?: binding.tvChatTitle.text.toString()
        AlertDialog.Builder(this)
            .setTitle("清空对话？")
            .setMessage("将删除与 $personaName 的本地聊天记录。这个操作不会删除 persona。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                viewModel.clearCurrentConversation()
                shouldFollowLatestMessage = true
                didPositionInitialMessages = false
            }
            .show()
    }

    override fun onDestroy() {
        viewModel.stopGenerating()
        super.onDestroy()
    }
}
