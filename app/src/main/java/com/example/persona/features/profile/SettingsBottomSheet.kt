package com.example.persona.features.profile

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.example.persona.core.auth.AuthManager
import com.example.persona.core.util.SettingsManager
import com.example.persona.databinding.DialogSettingsBinding
import com.example.persona.features.auth.AuthActivity
import com.example.persona.features.model.ModelManagementActivity
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class SettingsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: DialogSettingsBinding? = null
    private val binding get() = _binding!!

    @Inject
    lateinit var settingsManager: SettingsManager

    @Inject
    lateinit var authManager: AuthManager

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
            val intent = Intent(requireActivity(), AuthActivity::class.java)
            startActivity(intent)
            requireActivity().finish()
        }

        binding.btnModelManager.setOnClickListener {
            startActivity(Intent(requireContext(), ModelManagementActivity::class.java))
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "SettingsBottomSheet"
    }
}
