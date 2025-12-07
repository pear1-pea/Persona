package com.example.persona.features.profile

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.example.persona.R
import com.example.persona.core.util.SimpleDiffCallback
import com.example.persona.databinding.ItemMyPersonaBinding
import com.example.persona.domain.model.Persona

// Adapter fills Persona data into the RecyclerView
class MyPersonaAdapter(
    private val onClick: (Persona) -> Unit
) : ListAdapter<Persona, MyPersonaAdapter.ViewHolder>(
    SimpleDiffCallback(
        areItemsSame = { old, new -> old.id == new.id },
        areContentsSame = { old, new -> old == new }
    )
) {

    class ViewHolder(val binding: ItemMyPersonaBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemMyPersonaBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)

        with(holder.binding) {
            tvName.text = item.name

            ivAvatar.load(item.avatarUrl) {
                crossfade(true)
                placeholder(R.drawable.ic_launcher_background)
                transformations(CircleCropTransformation())
            }

            root.setOnClickListener {
                onClick(item) 
            }
        }
    }

}