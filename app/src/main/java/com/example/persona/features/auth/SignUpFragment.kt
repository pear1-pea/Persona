
package com.example.persona.features.auth

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
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
import androidx.navigation.fragment.navArgs
import com.example.persona.R
import com.example.persona.databinding.FragmentSignUpBinding
import com.google.android.material.textfield.TextInputLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SignUpFragment : Fragment() {

    private var _binding: FragmentSignUpBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AuthViewModel by viewModels()
    private val args: SignUpFragmentArgs by navArgs()

    private val isPhoneLogin: Boolean get() = args.isPhoneLogin
    private var isSendingCode = false
    private var countdownSeconds = 60
    private var currentPhoneNumber: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSignUpBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupMode()
        setupCodeInputListener()
        observeEvents()
    }

    private fun setupMode() {
        if (isPhoneLogin) {
            binding.tvTitle.text = "???????"
            binding.tilEmail.hint = "???"
            binding.etEmail.inputType = InputType.TYPE_CLASS_PHONE
            binding.tilPassword.hint = "???"
            binding.etPassword.inputType = InputType.TYPE_CLASS_NUMBER
            binding.tilPassword.endIconMode = TextInputLayout.END_ICON_NONE

            binding.tilConfirmPassword.visibility = View.GONE
            binding.btnSignUp.visibility = View.GONE
            binding.btnVerifyCode.visibility = View.VISIBLE
            binding.btnVerifyCode.text = "?????"
            binding.btnLogin.visibility = View.VISIBLE
            binding.btnLogin.text = "??"
            binding.btnLogin.isEnabled = false
            binding.tvSignInPrompt.visibility = View.VISIBLE
            binding.tvSignInPrompt.text = "??????"

            binding.btnVerifyCode.setOnClickListener { sendVerificationCode() }
            binding.btnLogin.setOnClickListener { verifyCode() }
            binding.tvSignInPrompt.setOnClickListener { findNavController().popBackStack() }
        } else {
            binding.tvTitle.text = "?????"
            binding.tilEmail.hint = "????"
            binding.etEmail.inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            binding.tilPassword.hint = "??"
            binding.etPassword.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            binding.tilPassword.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE

            binding.tilConfirmPassword.visibility = View.VISIBLE
            binding.btnSignUp.visibility = View.VISIBLE
            binding.btnSignUp.text = "??"
            binding.btnVerifyCode.visibility = View.GONE
            binding.btnLogin.visibility = View.GONE
            binding.tvSignInPrompt.visibility = View.VISIBLE
            binding.tvSignInPrompt.text = "?????????"

            binding.btnSignUp.setOnClickListener { handleSignUp() }
            binding.tvSignInPrompt.setOnClickListener { findNavController().popBackStack() }
        }
    }

    private fun sendVerificationCode() {
        if (isSendingCode) return

        val phone = binding.etEmail.text.toString().trim()
        if (phone.isBlank()) {
            Toast.makeText(context, "??????", Toast.LENGTH_SHORT).show()
            return
        }

        currentPhoneNumber = phone
        startCountdown()
        viewModel.startPhoneNumberVerification(phone)
    }

    private fun verifyCode() {
        val code = binding.etPassword.text.toString().trim()
        val phone = currentPhoneNumber ?: viewModel.getCurrentPhoneNumber() ?: binding.etEmail.text.toString().trim()

        if (phone.isBlank()) {
            Toast.makeText(context, "???????", Toast.LENGTH_SHORT).show()
            return
        }

        if (!code.matches(Regex("\\d{6}"))) {
            binding.tilPassword.error = "??? 6 ??????"
            return
        }
        binding.tilPassword.error = null

        binding.btnLogin.isEnabled = false
        binding.btnLogin.text = "???..."
        viewModel.verifyPhoneNumberCode(phone, code)
    }

    @SuppressLint("SetTextI18n")
    private fun startCountdown() {
        isSendingCode = true
        countdownSeconds = 60
        binding.btnVerifyCode.isEnabled = false
        binding.btnVerifyCode.text = "${countdownSeconds}s ???"

        lifecycleScope.launch {
            while (countdownSeconds > 0 && isSendingCode) {
                delay(1000)
                countdownSeconds--
                binding.btnVerifyCode.text = "${countdownSeconds}s ???"
            }

            if (isSendingCode) {
                resetVerifyButton()
            }
        }
    }

    private fun handleSignUp() {
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString()
        val confirmPassword = binding.etConfirmPassword.text.toString()

        if (email.isBlank() || password.isBlank() || confirmPassword.isBlank()) {
            Toast.makeText(context, "?????????", Toast.LENGTH_SHORT).show()
            return
        }

        if (password.length < 6) {
            Toast.makeText(context, "?????? 6 ?", Toast.LENGTH_SHORT).show()
            return
        }

        if (password != confirmPassword) {
            Toast.makeText(context, "??????????", Toast.LENGTH_SHORT).show()
            return
        }

        viewModel.signUp(email, password)
    }

    private fun observeEvents() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.phoneAuthEvents.collect { event ->
                    when (event) {
                        is PhoneAuthEvent.CodeSent -> {
                            currentPhoneNumber = event.phoneNumber
                            binding.tilPassword.helperText = "????????????"
                            binding.btnLogin.isEnabled = binding.etPassword.text?.length == 6
                        }
                        is PhoneAuthEvent.VerificationFailed -> {
                            resetVerifyButton()
                            Toast.makeText(context, "?????${event.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.signInSuccess.collect {
                    binding.btnLogin.isEnabled = true
                    binding.btnLogin.text = "??"
                    navigateToFeed()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.errorEvents.collect { errorMessage ->
                    binding.btnLogin.isEnabled = true
                    binding.btnLogin.text = "??"
                    Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun resetVerifyButton() {
        isSendingCode = false
        binding.btnVerifyCode.isEnabled = true
        binding.btnVerifyCode.text = "????"
    }

    private fun navigateToFeed() {
        findNavController().navigate(R.id.action_signUpFragment_to_feedFragment)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        isSendingCode = false
        _binding = null
    }

    private fun setupCodeInputListener() {
        binding.etPassword.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                if (isPhoneLogin) {
                    binding.btnLogin.isEnabled = s?.length == 6
                }
            }
        })
    }
}
