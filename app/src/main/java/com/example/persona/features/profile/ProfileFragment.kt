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
import com.example.persona.R
import com.example.persona.core.auth.AuthManager
import com.example.persona.core.util.observeErrorEvents
import com.example.persona.databinding.FragmentProfileBinding
import com.example.persona.features.chat.ChatActivity
import com.example.persona.features.creation.CreatePersonaActivity
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    @Inject
    lateinit var authManager: AuthManager

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

        val currentUser = FirebaseAuth.getInstance().currentUser
        binding.tvUserName.text = currentUser?.displayName ?: ""

        val imageUrl = currentUser?.photoUrl
        val fallbackUrl = "https://api.dicebear.com/7.x/avataaars/png?seed=MyCurrentUser"

        binding.ivUserAvatar.load(imageUrl ?: fallbackUrl) {
            crossfade(true)
            placeholder(R.drawable.ic_launcher_background)
            error(R.drawable.ic_launcher_background)
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