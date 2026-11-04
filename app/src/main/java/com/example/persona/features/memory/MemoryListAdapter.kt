package com.example.persona.features.memory

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.persona.R
import com.example.persona.domain.model.Memory
import com.example.persona.domain.model.MemoryType

internal fun MemoryType.labelResource(): Int = when (this) {
    MemoryType.PREFERENCE -> R.string.memory_type_preference
    MemoryType.FACT -> R.string.memory_type_fact
    MemoryType.GOAL -> R.string.memory_type_goal
    MemoryType.RELATIONSHIP -> R.string.memory_type_relationship
}

internal class MemoryListAdapter(
    private val onEdit: (String) -> Unit,
    private val onDelete: (String) -> Unit,
    private val onSource: (String) -> Unit
) : ListAdapter<Memory, MemoryListAdapter.Holder>(object : DiffUtil.ItemCallback<Memory>() {
    override fun areItemsTheSame(oldItem: Memory, newItem: Memory) = oldItem.id == newItem.id &&
        oldItem.ownerId == newItem.ownerId
    override fun areContentsTheSame(oldItem: Memory, newItem: Memory) = oldItem == newItem
}) {
    var actionsEnabled = true
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
            val padding = (12 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(ContextCompat.getColor(context, R.color.card_background))
        }
    )

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(private val container: LinearLayout) : RecyclerView.ViewHolder(container) {
        private val metadata = TextView(container.context)
        private val content = TextView(container.context).apply {
            setTextIsSelectable(true)
            textSize = 16f
        }
        private val source = Button(container.context)
        private val manualSource = TextView(container.context).apply { setText(R.string.memory_manual_source) }
        private val edit = Button(container.context).apply { setText(R.string.memory_edit) }
        private val delete = Button(container.context).apply { setText(R.string.memory_delete) }

        init {
            listOf(metadata, content, source, manualSource).forEach {
                container.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            val actions = LinearLayout(container.context).apply { orientation = LinearLayout.HORIZONTAL }
            listOf(edit, delete).forEach {
                actions.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            container.addView(actions)
            listOf(metadata, content, manualSource).forEach {
                it.setTextColor(ContextCompat.getColor(container.context, R.color.text_primary))
            }
        }

        fun bind(memory: Memory) {
            val context = container.context
            metadata.text = context.getString(R.string.memory_metadata,
                context.getString(memory.type.labelResource()),
                context.getString(if (memory.personaId == null) R.string.memory_scope_shared else R.string.memory_scope_current),
                context.getString(if (memory.cloudAllowed) R.string.memory_cloud_yes else R.string.memory_cloud_no),
                memory.revision)
            content.text = memory.content
            val messageId = memory.sourceMessageId
            source.visibility = if (messageId == null) View.GONE else View.VISIBLE
            manualSource.visibility = if (messageId == null) View.VISIBLE else View.GONE
            source.text = messageId?.let { context.getString(R.string.memory_source_button, it) }.orEmpty()
            source.setOnClickListener { messageId?.let(onSource) }
            edit.setOnClickListener { onEdit(memory.id) }
            delete.setOnClickListener { onDelete(memory.id) }
            listOf(source, edit, delete).forEach { it.isEnabled = actionsEnabled }
        }
    }
}
