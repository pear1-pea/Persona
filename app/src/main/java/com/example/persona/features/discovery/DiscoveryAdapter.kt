package com.example.persona.features.discovery

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.persona.R
import com.example.persona.core.util.SimpleDiffCallback
import com.example.persona.databinding.ItemDiscoveryCardBinding
import com.example.persona.domain.model.Persona

class DiscoveryAdapter(private val onClick: (Persona) -> Unit) :
    ListAdapter<Persona, DiscoveryAdapter.ViewHolder>(
        SimpleDiffCallback(
            areItemsSame = { old, new -> old.id == new.id },
            areContentsSame = { old, new -> old == new }
        )
    ){

    class ViewHolder(val binding: ItemDiscoveryCardBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDiscoveryCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.binding.tvName.text = item.name
        holder.binding.tvIntro.text = item.backstory
        holder.binding.ivPersonaImage.load(item.avatarUrl) {
            crossfade(true)
            placeholder(R.drawable.ic_launcher_background)
        }
        holder.itemView.setOnClickListener { onClick(item) }
    }

}