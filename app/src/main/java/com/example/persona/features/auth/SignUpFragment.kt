package com.example.persona.features.auth

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.example.persona.databinding.FragmentSignUpBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

@AndroidEntryPoint
class SignUpFragment : Fragment() {
    private var _binding: FragmentSignUpBinding? = null
    private val binding get() = _binding!!
    private val viewModel: AuthViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentSignUpBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.btnSignUp.setOnClickListener { signUp() }
        binding.tvSignInPrompt.setOnClickListener { findNavController().popBackStack() }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.errorEvents.collect { message ->
                    Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.isSubmitting.collect { submitting ->
                    binding.btnSignUp.isEnabled = !submitting
                    binding.btnSignUp.text = if (submitting) "注册中..." else "注册"
                }
            }
        }
    }

    private fun signUp() {
        val email = binding.etEmail.text?.toString()?.trim().orEmpty()
        val password = binding.etPassword.text?.toString().orEmpty()
        val confirmation = binding.etConfirmPassword.text?.toString().orEmpty()
        when {
            email.isBlank() || password.isBlank() || confirmation.isBlank() ->
                Toast.makeText(requireContext(), "请填写完整信息", Toast.LENGTH_SHORT).show()
            password.length < 8 ->
                Toast.makeText(requireContext(), "密码至少需要 8 位", Toast.LENGTH_SHORT).show()
            password != confirmation ->
                Toast.makeText(requireContext(), "两次输入的密码不一致", Toast.LENGTH_SHORT).show()
            else -> viewModel.signUp(email, password)
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
