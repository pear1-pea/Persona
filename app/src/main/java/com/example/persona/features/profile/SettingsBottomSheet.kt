package com.example.persona.features.profile

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.example.persona.core.auth.AuthManager
import com.example.persona.core.util.SettingsManager
import com.example.persona.databinding.DialogSettingsBinding
import com.example.persona.features.model.ModelManagementActivity
import com.example.persona.domain.repository.ChatRepository
import com.example.persona.data.repository.RoomPersonaRepository
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SettingsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: DialogSettingsBinding? = null
    private val binding get() = _binding!!

    @Inject
    lateinit var settingsManager: SettingsManager

    @Inject
    lateinit var authManager: AuthManager

    @Inject
    lateinit var chatRepository: ChatRepository

    @Inject
    lateinit var roomPersonaRepository: RoomPersonaRepository

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Read current settings and check the corresponding RadioButton
        when (settingsManager.getThemeMode()) {
            SettingsManager.THEME_LIGHT -> binding.rbLight.isChecked = true
            SettingsManager.THEME_DARK -> binding.rbDark.isChecked = true
            SettingsManager.THEME_SYSTEM -> binding.rbSystem.isChecked = true
        }

        // Listen for selection changes
        binding.radioGroupTheme.setOnCheckedChangeListener { _, checkedId ->
            val selectedMode = when (checkedId) {
                binding.rbLight.id -> SettingsManager.THEME_LIGHT
                binding.rbDark.id -> SettingsManager.THEME_DARK
                else -> SettingsManager.THEME_SYSTEM
            }

            view.postDelayed({
                if (!isAdded) return@postDelayed

                dismiss()

                settingsManager.saveThemeMode(selectedMode)

            }, 300)
        }

        binding.btnLogout.setOnClickListener {
            authManager.logout()
            dismiss()
        }

        binding.btnModelManager.setOnClickListener {
            startActivity(Intent(requireContext(), ModelManagementActivity::class.java))
            dismiss()
        }

        binding.btnChangePassword.setOnClickListener { showChangePasswordDialog() }
        binding.btnSessions.setOnClickListener { showSessionsDialog() }
        binding.btnDeleteAccount.setOnClickListener { showDeleteAccountDialog() }
    }

    private fun showChangePasswordDialog() {
        val current = EditText(requireContext()).apply {
            hint = "当前密码"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val next = EditText(requireContext()).apply {
            hint = "新密码（至少 8 位）"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 8, 48, 0)
            addView(current)
            addView(next)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("修改密码")
            .setView(content)
            .setNegativeButton("取消", null)
            .setPositiveButton("确认") { _, _ ->
                val currentPassword = current.text.toString()
                val newPassword = next.text.toString()
                if (newPassword.length < 8) {
                    Toast.makeText(requireContext(), "新密码至少需要 8 位", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    runCatching { authManager.changePassword(currentPassword, newPassword) }
                        .onSuccess { Toast.makeText(requireContext(), "密码已修改", Toast.LENGTH_SHORT).show() }
                        .onFailure { Toast.makeText(requireContext(), "当前密码错误或网络异常", Toast.LENGTH_LONG).show() }
                }
            }
            .show()
    }

    private fun showSessionsDialog() {
        lifecycleScope.launch {
            runCatching { authManager.getSessions() }
                .onSuccess { sessions ->
                    val message = sessions.joinToString("\n") { session ->
                        val device = session.userAgent?.take(36) ?: "未知设备"
                        val prefix = if (session.isCurrent) "当前设备" else "其他设备"
                        "$prefix · $device · ${session.lastUsedAt.take(10)}"
                    }.ifBlank { "没有有效 session" }
                    AlertDialog.Builder(requireContext())
                        .setTitle("登录设备")
                        .setMessage(message)
                        .setNegativeButton("关闭", null)
                        .setPositiveButton("退出其他设备") { _, _ ->
                            lifecycleScope.launch {
                                runCatching { authManager.revokeOtherSessions() }
                                    .onSuccess { Toast.makeText(requireContext(), "其他设备已退出", Toast.LENGTH_SHORT).show() }
                                    .onFailure { Toast.makeText(requireContext(), "操作失败", Toast.LENGTH_SHORT).show() }
                            }
                        }
                        .show()
                }
                .onFailure { Toast.makeText(requireContext(), "无法读取登录设备", Toast.LENGTH_LONG).show() }
        }
    }

    private fun showDeleteAccountDialog() {
        val password = EditText(requireContext()).apply {
            hint = "当前密码"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(requireContext())
            .setTitle("注销账号")
            .setMessage("账号和你创建的 Persona 将被永久删除。请输入当前密码确认。")
            .setView(password)
            .setNegativeButton("取消", null)
            .setPositiveButton("确认注销") { _, _ ->
                val currentPassword = password.text.toString()
                val ownerId = authManager.currentUserId
                if (currentPassword.isBlank() || ownerId == null) {
                    Toast.makeText(requireContext(), "请输入当前密码", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    runCatching {
                        authManager.deleteAccount(currentPassword)
                        chatRepository.deleteMessagesForOwner(ownerId)
                        roomPersonaRepository.deletePersonasByCreator(ownerId)
                    }
                        .onSuccess {
                            dismiss()
                        }
                        .onFailure {
                            Toast.makeText(
                                requireContext(),
                                "注销失败，请检查密码或网络",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                }
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "SettingsBottomSheet"
    }
}
