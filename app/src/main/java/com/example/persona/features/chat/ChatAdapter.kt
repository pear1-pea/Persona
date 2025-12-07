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

class ChatAdapter(
    private val markdownHelper: MarkdownHelper
) : PagingDataAdapter<Message, RecyclerView.ViewHolder>(MessageDiffCallback) {

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
        if (holder is SentViewHolder) {
            holder.binding.tvContent.text = message.content
        } else if (holder is ReceivedViewHolder) {
            markdownHelper.setMarkdown(holder.binding.tvContent, message.content)
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
                        oldItem.isFromUser == newItem.isFromUser
            },
            payloadProvider = { oldItem, newItem ->
                if (oldItem.content != newItem.content) {
                    "CONTENT_UPDATE"
                } else null
            }
        )
    }
}