package com.example.persona.features.chat

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.persona.core.util.MarkdownHelper
import com.example.persona.core.util.SimpleDiffCallback
import com.example.persona.databinding.ItemMessageReceivedBinding
import com.example.persona.databinding.ItemMessageSentBinding
import com.example.persona.domain.model.Message
import com.example.persona.domain.model.MessageStatus

class ChatAdapter(
    private val markdownHelper: MarkdownHelper,
    private val onSaveMemory: (Message) -> Unit = {}
) : PagingDataAdapter<Message, RecyclerView.ViewHolder>(MessageDiffCallback) {

    private var streamingMessage: StreamingMessage? = null

    fun updateStreamingMessage(message: StreamingMessage?) {
        val affectedIds = setOfNotNull(streamingMessage?.messageId, message?.messageId)
        streamingMessage = message
        snapshot().items.forEachIndexed { index, item ->
            if (item.id in affectedIds) notifyItemChanged(index)
        }
    }

    private val typeSent = 1
    private val typeReceived = 2

    override fun getItemViewType(position: Int): Int {
        val message = getItem(position)
        return if (message?.isFromUser == true) typeSent else typeReceived
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == typeSent) {
            val binding = ItemMessageSentBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            SentViewHolder(binding)
        } else {
            val binding = ItemMessageReceivedBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            ReceivedViewHolder(binding)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = getItem(position)
        if (message != null) {
            bindMessage(holder, message)
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.isNotEmpty()) {
            val message = getItem(position)
            if (message != null) {
                bindMessage(holder, message)
            }
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    private fun bindMessage(holder: RecyclerView.ViewHolder, message: Message) {
        holder.itemView.setOnLongClickListener {
            if (message.isFromUser && message.status == MessageStatus.NORMAL) {
                onSaveMemory(message)
                true
            } else false
        }
        val displayed = streamingMessage?.takeIf { it.messageId == message.id }?.let {
            message.copy(content = it.content, status = it.status)
        } ?: message
        if (holder is SentViewHolder) {
            holder.binding.tvContent.text = displayed.content
        } else if (holder is ReceivedViewHolder) {
            if (displayed.status == MessageStatus.GENERATING) {
                holder.binding.tvContent.text = displayed.content
                holder.binding.tvContent.linksClickable = false
                holder.binding.tvContent.movementMethod = null
            } else {
                markdownHelper.setMarkdown(holder.binding.tvContent, displayed.content)
            }
        }
    }

    class SentViewHolder(val binding: ItemMessageSentBinding) : RecyclerView.ViewHolder(binding.root)
    class ReceivedViewHolder(val binding: ItemMessageReceivedBinding) : RecyclerView.ViewHolder(binding.root)

    companion object {
        private val MessageDiffCallback = SimpleDiffCallback<Message>(
            areItemsSame = { oldItem, newItem ->
                oldItem.id == newItem.id
            },
            areContentsSame = { oldItem, newItem ->
                oldItem.content == newItem.content &&
                        oldItem.isFromUser == newItem.isFromUser &&
                        oldItem.status == newItem.status
            },
            payloadProvider = { oldItem, newItem ->
                if (oldItem.content != newItem.content) {
                    "CONTENT_UPDATE"
                } else null
            }
        )
    }
}
