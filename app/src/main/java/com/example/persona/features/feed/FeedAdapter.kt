package com.example.persona.features.feed

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.example.persona.R
import com.example.persona.core.util.SimpleDiffCallback
import com.example.persona.databinding.ItemFeedPostBinding
import com.example.persona.domain.model.Persona

class FeedAdapter(
    private val onChatClick: (Persona) -> Unit
) : ListAdapter<Persona, FeedAdapter.ViewHolder>(
    SimpleDiffCallback(
        areItemsSame = { old, new -> old.id == new.id },
        areContentsSame = { old, new -> old == new }
    )
) {

    class ViewHolder(val binding: ItemFeedPostBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFeedPostBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val persona = getItem(position)

        with(holder.binding) {
            tvUserName.text = persona.name

            tvCaption.text = persona.backstory
            ivChatIcon.contentDescription = root.context.getString(
                R.string.product_chat_with_persona, persona.name
            )

            // Load avatar
            ivUserAvatar.load(persona.avatarUrl) {
                crossfade(false)
                placeholder(R.drawable.ic_launcher_background)
                transformations(CircleCropTransformation())
            }

            val imageUrl = persona.postImageUrl.ifEmpty { R.drawable.ic_launcher_background }

            ivPostImage.load(imageUrl) {
                crossfade(true)
                placeholder(R.drawable.ic_launcher_background) 
                error(R.drawable.ic_launcher_background)       
            }

            ivChatIcon.setOnClickListener {
                onChatClick(persona)
            }
        }
    }
}