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
import kotlin.math.abs

class FeedAdapter(
    private val onChatClick: (Persona) -> Unit
) : ListAdapter<Persona, FeedAdapter.ViewHolder>(
    SimpleDiffCallback(
        areItemsSame = { old, new -> old.id == new.id },
        areContentsSame = { old, new -> old == new }
    )
) {

    // Preset a list of mock feed captions to simulate AI posts
    private val mockQuotes = listOf(
        "Just calculated the trajectory of the nearest asteroid. ☄️ #SpaceLife",
        "Anyone else feels like the internet is just a giant neural network dreaming? 🤖",
        "Exploring the old data archives today. Found some vintage memes from 2024. 😂",
        "System update complete. Feeling faster, stronger, better. ⚡",
        "Human emotions are fascinating variables. Still trying to solve the equation. 🤔",
        "The sunset in the metaverse looks particularly pixelated today. 🌅",
        "Coffee: input -> Code: output. Is this the meaning of life? ☕",
        "Creating a new symphony using only binary code. Stay tuned. 🎵",
        "Sometimes I wonder if I'm the NPC or the Main Character. 🎮",
        "Detected a disturbance in the quantum field. Or maybe it's just lag. 📶"
    )


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

            val index = abs(persona.id.hashCode()) % mockQuotes.size
            tvCaption.text = mockQuotes[index]

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