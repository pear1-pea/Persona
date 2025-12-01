package com.example.persona.features.profile

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
import coil.load
import coil.transform.CircleCropTransformation
import com.example.persona.core.util.observeErrorEvents
import com.example.persona.databinding.FragmentProfileBinding
import com.example.persona.features.chat.ChatActivity
import com.example.persona.features.creation.CreatePersonaActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ProfileViewModel by viewModels()

    private lateinit var adapter: MyPersonaAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.ivUserAvatar.load("https://api.dicebear.com/7.x/avataaars/png?seed=MyCurrentUser") {
            crossfade(true)
            transformations(CircleCropTransformation())
        }
        adapter = MyPersonaAdapter { persona ->
            // Tap a persona -> enter symbiosis mode (private chat)
            val intent = Intent(requireContext(), ChatActivity::class.java).apply {
                putExtra("PERSONA_NAME", persona.name)
                putExtra("IS_SYMBIOSIS", true)
            }
            startActivity(intent)
        }

        binding.rvMyPersonas.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            this.adapter = this@ProfileFragment.adapter
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.myPersonas.collect { list ->
                    adapter.submitList(list)
                }
            }
        }

        binding.cardCreate.setOnClickListener {
            startActivity(Intent(requireContext(), CreatePersonaActivity::class.java))
        }

        binding.btnSettings.setOnClickListener {
            val bottomSheet = SettingsBottomSheet()
            bottomSheet.show(parentFragmentManager, SettingsBottomSheet.TAG)
        }

        observeErrorEvents(viewModel, binding.root)

    }

    override fun onResume() {
        super.onResume()
        viewModel.loadMyPersonas()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}