package com.example.persona.core.util

import androidx.recyclerview.widget.DiffUtil


class SimpleDiffCallback<T : Any>(
    private val areItemsSame: (T, T) -> Boolean,
    private val areContentsSame: (T, T) -> Boolean,
    private val payloadProvider: ((T, T) -> Any?)? = null
) : DiffUtil.ItemCallback<T>() {

    override fun areItemsTheSame(oldItem: T, newItem: T): Boolean {
        return areItemsSame(oldItem, newItem)
    }

    override fun areContentsTheSame(oldItem: T, newItem: T): Boolean {
        return areContentsSame(oldItem, newItem)
    }

    override fun getChangePayload(oldItem: T, newItem: T): Any? {
        return payloadProvider?.invoke(oldItem, newItem)
    }
}
