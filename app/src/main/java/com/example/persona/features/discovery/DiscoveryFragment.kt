package com.example.persona.features.discovery

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.example.persona.R
import com.example.persona.core.util.observeErrorEvents
import com.example.persona.databinding.FragmentDiscoveryBinding
import com.example.persona.features.chat.ChatActivity
import com.google.android.material.chip.Chip
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class DiscoveryFragment : Fragment() {

    private var _binding: FragmentDiscoveryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DiscoveryViewModel by viewModels()
    private lateinit var adapter: DiscoveryAdapter

    private val categories = listOf("All", "Sci-Fi", "Fantasy", "Realistic", "Anime")

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDiscoveryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupFilterChips()
        observeData()
        setupSearchView()
        observeErrorEvents(viewModel, binding.root)
    }

    private fun setupSearchView() {
        binding.svDiscovery.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {

            override fun onQueryTextSubmit(query: String?): Boolean {
                binding.svDiscovery.clearFocus() 
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                viewModel.searchPersonas(newText ?: "")
                return true
            }
        })
    }

    private fun setupRecyclerView() {
        adapter = DiscoveryAdapter { persona ->
            val intent = Intent(requireContext(), ChatActivity::class.java).apply {
                putExtra("PERSONA_ID", persona.id)
                putExtra("PERSONA_NAME", persona.name)
                putExtra("IS_SYMBIOSIS", false)
            }
            startActivity(intent)
        }

        binding.rvDiscovery.apply {
            layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
            this.adapter = this@DiscoveryFragment.adapter
        }
    }

    private fun setupFilterChips() {
        val chipGroup = binding.chipGroupFilter
        chipGroup.removeAllViews() 

        for (category in categories) {
            val chip = Chip(requireContext()).apply {
                text = category
                isCheckable = true
                isClickable = true


                setChipBackgroundColorResource(R.color.selector_filter_chip_bg)

                setTextColor(ContextCompat.getColorStateList(context, R.color.selector_filter_chip_text))

                chipStrokeWidth = 0f

                if (category == "All") {
                    isChecked = true
                }
            }

            chip.setOnClickListener {
                viewModel.filterByCategory(category)
            }

            chipGroup.addView(chip)
        }
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { list ->
                    adapter.submitList(list)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}