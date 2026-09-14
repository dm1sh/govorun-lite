package com.govorun.lite.ui.onboarding

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import com.govorun.lite.R
import com.govorun.lite.service.GovorunInputMethodService

/** Optional setup for the auxiliary voice keyboard. */
class KeyboardFragment : OnboardingStepFragment() {
    private lateinit var status: MaterialTextView
    private lateinit var openButton: MaterialButton
    private lateinit var skipButton: MaterialButton
    private var skipped = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_onboarding_keyboard, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        status = view.findViewById(R.id.keyboardStatus)
        openButton = view.findViewById(R.id.keyboardOpen)
        skipButton = view.findViewById(R.id.keyboardSkip)
        openButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        skipButton.setOnClickListener {
            skipped = true
            setStepComplete(true)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    override fun onStepFocused() {
        refreshState()
    }

    private fun refreshState() {
        val enabled = isKeyboardEnabled(requireContext())
        status.setText(if (enabled) R.string.onb_keyboard_enabled else R.string.onb_keyboard_pending)
        openButton.visibility = if (enabled) View.GONE else View.VISIBLE
        skipButton.visibility = if (enabled || skipped) View.GONE else View.VISIBLE
        setStepComplete(enabled || skipped)
    }

    private fun isKeyboardEnabled(context: Context): Boolean {
        val imm = context.getSystemService(InputMethodManager::class.java) ?: return false
        val id = "${context.packageName}/${GovorunInputMethodService::class.java.name}"
        return imm.enabledInputMethodList.any { it.id == id }
    }
}
