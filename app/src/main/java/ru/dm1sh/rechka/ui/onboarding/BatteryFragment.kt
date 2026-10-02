package ru.dm1sh.rechka.ui.onboarding

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import ru.dm1sh.rechka.R
import ru.dm1sh.rechka.util.AccessibilityHelper
import ru.dm1sh.rechka.util.AppLog

/**
 * Step 5: ask the system not to kill the accessibility service in the
 * background. This step is non-blocking — the user can just hit «Далее»
 * without doing anything. We still show a button that takes them to the
 * exemption settings if they want to be thorough.
 *
 * Uses ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS with the package URI when
 * available; falls back to the generic optimization settings list.
 */
class BatteryFragment : OnboardingStepFragment() {

    private lateinit var statusText: MaterialTextView
    private lateinit var openButton: MaterialButton
    private lateinit var hintText: MaterialTextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_onboarding_battery, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        statusText = view.findViewById(R.id.batteryStatus)
        openButton = view.findViewById(R.id.batteryOpen)
        hintText = view.findViewById(R.id.batteryHint)

        openButton.setOnClickListener { onOpenClicked() }

        // Non-blocking: Далее enabled from the start.
        setStepComplete(true)
    }

    private fun onOpenClicked() {
        val ctx = requireContext()
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val ignoring = pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true
        AppLog.log(ctx, "Battery: click, ignoring=$ignoring")

        // If we're not yet in the doze whitelist, the OS shows the per-app
        // "Разрешить игнорировать оптимизацию?" dialog. If we're already in
        // the whitelist, that intent silently no-ops on some ROMs — go
        // straight to the optimization-settings list so the user can verify
        // or change the state manually.
        if (!ignoring) {
            val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(request)
                AppLog.log(ctx, "Battery: launched REQUEST_IGNORE_BATTERY_OPTIMIZATIONS")
                return
            } catch (e: ActivityNotFoundException) {
                AppLog.log(ctx, "Battery: REQUEST intent not found: ${e.message}")
            }
        }

        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(fallback)
            AppLog.log(ctx, "Battery: launched IGNORE_BATTERY_OPTIMIZATION_SETTINGS")
        } catch (e: ActivityNotFoundException) {
            AppLog.log(ctx, "Battery: fallback intent not found: ${e.message}")
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
        val context = requireContext()
        if (!WelcomeFragment.isOverlaySelected(context)) {
            requireView().visibility = View.GONE
            setStepComplete(true)
            return
        }
        requireView().visibility = View.VISIBLE
        if (!AccessibilityHelper.isLiteServiceEnabled(context)) {
            statusText.setText(R.string.onb_battery_not_needed)
            openButton.visibility = View.GONE
            hintText.visibility = View.GONE
            return
        }

        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val ignoring = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        if (ignoring) {
            statusText.setText(R.string.onb_battery_body_done)
            openButton.visibility = View.GONE
            hintText.visibility = View.GONE
        } else {
            statusText.setText(R.string.onb_battery_body)
            openButton.setText(R.string.onb_battery_open)
            openButton.visibility = View.VISIBLE
            hintText.visibility = View.VISIBLE
        }
    }
}
