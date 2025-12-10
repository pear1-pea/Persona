package com.example.persona.features.auth

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.example.persona.R
import com.example.persona.databinding.FragmentSignUpBinding
import com.example.persona.features.profile.SettingsBottomSheet.Companion.TAG
import com.google.android.material.textfield.TextInputLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
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
    private var currentVerificationId: String? = null

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
        observeEvents()
        setupCodeInputListener() // 添加这行
    }

    private fun setupMode() {
        if (isPhoneLogin) {
            binding.tvTitle.text = "手机号登录"
            binding.tilEmail.hint = "手机号"
            binding.etEmail.inputType = InputType.TYPE_CLASS_PHONE
            binding.btnLogin.visibility = View.VISIBLE
            binding.btnVerifyCode.visibility = View.VISIBLE
            binding.btnVerifyCode.text = "获取验证码"
            binding.tilPassword.hint = "验证码"
            binding.etPassword.inputType = InputType.TYPE_CLASS_NUMBER
            binding.tilPassword.endIconMode = TextInputLayout.END_ICON_NONE

            binding.tilConfirmPassword.visibility = View.GONE
            binding.btnSignUp.visibility = View.GONE
            binding.tvSignInPrompt.visibility = View.GONE

            binding.btnLogin.setOnClickListener { verifyCode() }
            binding.btnVerifyCode.setOnClickListener { sendVerificationCode() }
        } else {
            binding.tvTitle.text = "创建新账号"

            binding.tilEmail.hint = "邮箱地址"
            binding.etEmail.inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            binding.btnLogin.visibility = View.GONE
            binding.btnVerifyCode.visibility = View.GONE
            binding.tilPassword.hint = "密码"
            binding.etPassword.inputType = InputType.TYPE_TEXT_VARIATION_PASSWORD
            binding.tilPassword.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            binding.tilConfirmPassword.visibility = View.VISIBLE
            binding.btnSignUp.visibility = View.VISIBLE
            binding.tvSignInPrompt.visibility = View.VISIBLE

            binding.btnSignUp.text = "注册"
            binding.btnSignUp.setOnClickListener { handleSignUp() }

            binding.tvSignInPrompt.setOnClickListener {
                findNavController().popBackStack()
            }
        }
    }

    private fun sendVerificationCode() {
        Log.d("SignUpFragment", "sendVerificationCode called")
        if (isSendingCode) return
        val phone = binding.etEmail.text.toString().trim()
        if (phone.isEmpty()) {
            Toast.makeText(context, "请输入手机号", Toast.LENGTH_SHORT).show()
            return
        }

        startCountdown()
        Log.d("SignUpFragment", "Starting phone number verification for: $phone")

        // 开始发送验证码
        val activity = requireActivity()
        viewModel.startPhoneNumberVerification(phone, activity)
    }

    private fun verifyCode() {
        Log.d(TAG, "verifyCode called")
        val code = binding.etPassword.text.toString().trim()

        if (code.length != 6 || !code.matches(Regex("\\d{6}"))) {
            binding.tilPassword.error = "请输入6位数字验证码"
            return
        }
        binding.tilPassword.error = null

        val verificationId = currentVerificationId ?: viewModel.getCurrentVerificationId()
        Log.d(TAG, "Current verificationId: $verificationId")
        Log.d(TAG, "Code to verify: $code")

        if (verificationId.isNullOrEmpty()) {
            Log.e(TAG, "Verification ID is null or empty")
            Toast.makeText(context, "会话已过期，请重新获取验证码", Toast.LENGTH_SHORT).show()
            binding.btnLogin.isEnabled = true
            binding.btnLogin.text = "登入"
            return
        }

        try {
            binding.btnLogin.isEnabled = false
            binding.btnLogin.text = "验证中..."

            viewModel.verifyPhoneNumberCode(verificationId, code)

        } catch (e: Exception) {
            Log.e(TAG, "Error during verification: ${e.message}", e)
            binding.btnLogin.isEnabled = true
            binding.btnLogin.text = "登入"
            Toast.makeText(context, "验证时发生错误: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun startCountdown() {
        isSendingCode = true
        countdownSeconds = 60

        binding.btnVerifyCode.isEnabled = false
        binding.btnVerifyCode.text = "${countdownSeconds}s 后重试"

        lifecycleScope.launch {
            while (countdownSeconds > 0 && isSendingCode) {
                delay(1000)
                countdownSeconds--
                binding.btnVerifyCode.text = "${countdownSeconds}s 后重试"
            }

            if (isSendingCode) {
                binding.btnVerifyCode.isEnabled = true
                binding.btnVerifyCode.text = "重新发送"
                isSendingCode = false
            }
        }
    }

    private fun handleSignUp() {
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString()
        val confirmPassword = binding.etConfirmPassword.text.toString()

        if (email.isEmpty() || password.isEmpty() || confirmPassword.isEmpty()) {
            Toast.makeText(context, "所有字段都不能为空", Toast.LENGTH_SHORT).show()
            return
        }

        if (password != confirmPassword) {
            Toast.makeText(context, "两次输入的密码不一致", Toast.LENGTH_SHORT).show()
            return
        }

        viewModel.signUp(email, password)
    }

    private fun observeEvents() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.phoneAuthEvents.collect { event ->
                when (event) {
                    is PhoneAuthEvent.CodeSent -> {
                        Log.d(TAG, "Code sent: ${event.verificationId}")
                        currentVerificationId = event.verificationId
                        viewModel.setCurrentVerificationId(event.verificationId)
                        switchToVerificationMode(event.verificationId)
                    }
                    is PhoneAuthEvent.VerificationCompleted -> {
                        Log.d(TAG, "Verification completed")
                        navigateToFeed()
                    }
                    is PhoneAuthEvent.VerificationFailed -> {
                        Log.e(TAG, "Verification failed: ${event.message}")
                        isSendingCode = false
                        binding.btnVerifyCode.isEnabled = true
                        binding.btnVerifyCode.text = "获取验证码"
                        Toast.makeText(context, "发送失败: ${event.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.signInSuccess.collect {
                Log.d("SignUpFragment", "Sign in success, navigating to feed")
                binding.btnLogin.isEnabled = true
                binding.btnLogin.text = "登入"
                navigateToFeed()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.errorEvents.collect { errorMessage ->
                binding.btnLogin.isEnabled = true
                binding.btnLogin.text = "登入"
                Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun switchToVerificationMode(verificationId: String) {
        this.currentVerificationId = verificationId
        viewModel.setCurrentVerificationId(verificationId)

        binding.apply {
            btnVerifyCode.text = "重新发送"
            btnVerifyCode.isEnabled = true
            tilPassword.helperText = "验证码已发送，请查收短信"
        }

        binding.btnLogin.isEnabled = true

        Log.d("SignUpFragment", "Switched to verification mode with id: $verificationId")
    }

    private fun navigateToFeed() {
        findNavController().navigate(R.id.action_signUpFragment_to_feedFragment)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
    private fun setupCodeInputListener() {
        binding.etPassword.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                updateLoginButtonState()
            }
        })
    }

    private fun updateLoginButtonState() {
        val code = binding.etPassword.text.toString().trim()
        binding.btnLogin.isEnabled = code.length == 6
    }
}