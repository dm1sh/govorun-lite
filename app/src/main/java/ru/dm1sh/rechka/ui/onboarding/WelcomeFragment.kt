package ru.dm1sh.rechka.ui.onboarding

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.checkbox.MaterialCheckBox
import ru.dm1sh.rechka.R

class WelcomeFragment : OnboardingStepFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_onboarding_welcome, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val overlay = view.findViewById<MaterialCheckBox>(R.id.onbModeOverlay)
        val keyboard = view.findViewById<MaterialCheckBox>(R.id.onbModeKeyboard)
        overlay.isChecked = prefs.getBoolean(KEY_OVERLAY, false)
        keyboard.isChecked = prefs.getBoolean(KEY_KEYBOARD, false)
        view.findViewById<MaterialCheckBox>(R.id.onbModeFiles).apply {
            isChecked = true
            isEnabled = false
        }
        overlay.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_OVERLAY, checked).apply()
        }
        keyboard.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_KEYBOARD, checked).apply()
        }
        setStepComplete(true)
    }

    companion object {
        private const val PREFS = "rechka_lite_prefs"
        private const val KEY_OVERLAY = "onboarding_mode_overlay"
        private const val KEY_KEYBOARD = "onboarding_mode_keyboard"

        fun isOverlaySelected(context: Context): Boolean = context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_OVERLAY, false)

        fun isKeyboardSelected(context: Context): Boolean = context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_KEYBOARD, false)
    }
}
