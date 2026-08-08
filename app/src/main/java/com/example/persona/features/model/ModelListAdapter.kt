package com.example.persona.features.model

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.persona.R
import com.example.persona.databinding.ItemModelScanResultBinding

class ModelListAdapter(
    private val onSelect: (ModelUiItem) -> Unit,
    private val onDelete: (ModelUiItem) -> Unit,
    private val onDetails: (ModelUiItem) -> Unit
) : RecyclerView.Adapter<ModelListAdapter.ViewHolder>() {

    private val items = mutableListOf<ModelUiItem>()

    fun submitItems(newItems: List<ModelUiItem>) {
        if (items == newItems) return
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemModelScanResultBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(
        private val binding: ItemModelScanResultBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ModelUiItem) = with(binding) {
            tvModelName.text = item.name
            tvModelMeta.text = buildString {
                append("版本 ")
                append(item.version)
                append(" · ")
                append(item.backend)
                append(" · ")
                append(item.fileSize)
                if (item.minRamGb > 0) {
                    append(" · 建议 RAM ")
                    append(item.minRamGb)
                    append("GB")
                }
            }
            tvModelPath.text = item.modelDir
            tvModelStatus.text = item.statusLabel
            tvModelStatus.setTextColor(statusColor(item.statusKind))
            val noticeText = item.reason ?: item.riskText
            tvModelReason.text = noticeText.orEmpty()
            tvModelReason.visibility = if (noticeText.isNullOrBlank()) View.GONE else View.VISIBLE
            tvModelReason.setTextColor(
                if (item.reason != null) {
                    ContextCompat.getColor(root.context, android.R.color.holo_red_dark)
                } else {
                    ContextCompat.getColor(root.context, android.R.color.holo_orange_dark)
                }
            )
            btnSelect.text = if (item.isCurrent) "当前使用" else "设为当前"
            btnSelect.isEnabled = item.isReady && !item.isCurrent
            btnSelect.setOnClickListener { onSelect(item) }
            btnDelete.setOnClickListener { onDelete(item) }
            btnDetails.setOnClickListener { onDetails(item) }
        }

        private fun statusColor(kind: ModelStatusKind): Int {
            val context = binding.root.context
            return when (kind) {
                ModelStatusKind.Ready,
                ModelStatusKind.Current -> ContextCompat.getColor(context, R.color.accent_cyan)
                ModelStatusKind.Unsupported -> ContextCompat.getColor(context, android.R.color.holo_orange_dark)
                ModelStatusKind.NotInstalled,
                ModelStatusKind.Corrupted,
                ModelStatusKind.Failed -> ContextCompat.getColor(context, android.R.color.holo_red_dark)
            }
        }
    }
}
