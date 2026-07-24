package com.example.persona.features.feed

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.persona.core.util.observeErrorEvents
import com.example.persona.databinding.FragmentFeedBinding
import com.example.persona.features.chat.ChatActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class FeedFragment : Fragment() {

    private var _binding: FragmentFeedBinding? = null
    private val binding get() = _binding!!

    private val viewModel: FeedViewModel by viewModels()

    private lateinit var adapter: FeedAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFeedBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize adapter
        adapter = FeedAdapter { persona ->
            val intent = Intent(requireContext(), ChatActivity::class.java).apply {
                putExtra("PERSONA_ID", persona.id)
                putExtra("PERSONA_NAME", persona.name)
                putExtra("IS_SYMBIOSIS", false)
            }
            startActivity(intent)
        }

        binding.rvFeed.apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = this@FeedFragment.adapter
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { list ->
                    adapter.submitList(list)
                }
            }
        }

        observeErrorEvents(viewModel, binding.root)

    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}