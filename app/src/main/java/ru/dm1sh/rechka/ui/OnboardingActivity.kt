package ru.dm1sh.rechka.ui

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import com.google.android.material.progressindicator.LinearProgressIndicator
import ru.dm1sh.rechka.R
import ru.dm1sh.rechka.ui.onboarding.OnboardingPagerAdapter
import ru.dm1sh.rechka.ui.onboarding.OnboardingStep
import ru.dm1sh.rechka.ui.onboarding.OnboardingStepFragment
import ru.dm1sh.rechka.ui.onboarding.WelcomeFragment
import ru.dm1sh.rechka.util.AccessibilityHelper

/**
 * Host for the onboarding wizard. ViewPager2 with user-swipe disabled — the
 * only way to advance is through the «Далее» button, which is enabled once
 * the current step reports completion.
 *
 * Persists the last reached step in SharedPreferences so a user who leaves
 * mid-flow resumes at the same page. On completion, sets the ONBOARDING_DONE
 * flag and routes to MainActivity.
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var pager: ViewPager2
    private lateinit var progress: LinearProgressIndicator
    private lateinit var nextButton: MaterialButton
    private lateinit var backButton: MaterialButton
    private lateinit var bottomBar: android.view.View
    private lateinit var prefs: SharedPreferences
    private lateinit var pagerAdapter: OnboardingPagerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ONBOARDING_DONE, false)) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_onboarding)

        pager = findViewById(R.id.onboardingPager)
        progress = findViewById(R.id.onboardingProgress)
        nextButton = findViewById(R.id.onboardingNext)
        backButton = findViewById(R.id.onboardingBack)
        bottomBar = findViewById(R.id.onboardingBottomBar)

        ViewCompat.setOnApplyWindowInsetsListener(bottomBar) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(bottom = bars.bottom)
            insets
        }

        pagerAdapter = OnboardingPagerAdapter(this)
        pager.adapter = pagerAdapter
        pager.isUserInputEnabled = false
        progress.max = pagerAdapter.itemCount

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                progress.setProgressCompat(position + 1, true)
                persistStep(pagerAdapter.stepAt(position))
                refreshNextButton()
                // Fragments created lazily — nudge the new one once it's attached.
                currentStepFragment()?.onStepFocused()
            }
        })

        nextButton.setOnClickListener {
            val next = findNextRequiredStep(pager.currentItem, 1)
            if (next >= 0) pager.currentItem = next else finishOnboarding()
        }

        backButton.setOnClickListener { goBackOneStep() }

        // System back button steps through pages instead of leaving the wizard.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!goBackOneStep()) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        val loadedStep = loadResumeStep()
        val resumeAt = if (isStepRequired(loadedStep)) loadedStep else {
            val next = findNextRequiredStep(loadedStep.ordinal, 1)
            if (next >= 0) pagerAdapter.stepAt(next) else OnboardingStep.WELCOME
        }
        pager.setCurrentItem(resumeAt.ordinal, false)
        progress.setProgressCompat(resumeAt.ordinal + 1, false)
        refreshNextButton()
    }

    private fun goBackOneStep(): Boolean {
        val previous = findNextRequiredStep(pager.currentItem, -1)
        if (previous >= 0) {
            pager.currentItem = previous
            return true
        }
        return false
    }

    private fun findNextRequiredStep(from: Int, direction: Int): Int {
        var index = from + direction
        while (index in 0 until pagerAdapter.itemCount) {
            if (isStepRequired(pagerAdapter.stepAt(index))) return index
            index += direction
        }
        return -1
    }

    private fun isStepRequired(step: OnboardingStep): Boolean {
        return when (step) {
            OnboardingStep.WELCOME -> true
            OnboardingStep.MODEL -> true
            OnboardingStep.TRY_IT -> WelcomeFragment.isOverlaySelected(this) ||
                WelcomeFragment.isKeyboardSelected(this)
            OnboardingStep.ACCESSIBILITY -> WelcomeFragment.isOverlaySelected(this)
            OnboardingStep.KEYBOARD -> WelcomeFragment.isKeyboardSelected(this)
            OnboardingStep.BATTERY -> WelcomeFragment.isOverlaySelected(this) &&
                AccessibilityHelper.isLiteServiceEnabled(this)
        }
    }

    fun onStepStateChanged() {
        refreshNextButton()
    }

    private fun refreshNextButton() {
        val isLast = pager.currentItem == pagerAdapter.itemCount - 1
        nextButton.setText(if (isLast) R.string.action_done else R.string.action_next)
        nextButton.isEnabled = currentStepFragment()?.isStepComplete == true
        backButton.visibility = if (pager.currentItem > 0) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun currentStepFragment(): OnboardingStepFragment? {
        return supportFragmentManager.findFragmentByTag("f${pager.currentItem}") as? OnboardingStepFragment
    }

    private fun loadResumeStep(): OnboardingStep {
        val name = prefs.getString(KEY_STEP, null) ?: return OnboardingStep.WELCOME
        return runCatching { OnboardingStep.valueOf(name) }.getOrDefault(OnboardingStep.WELCOME)
    }

    private fun persistStep(step: OnboardingStep) {
        prefs.edit().putString(KEY_STEP, step.name).apply()
    }

    private fun finishOnboarding() {
        prefs.edit()
            .putBoolean(KEY_ONBOARDING_DONE, true)
            .remove(KEY_STEP)
            .apply()
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_JUST_FINISHED, true)
        startActivity(intent)
        finish()
    }

    companion object {
        private const val PREFS_NAME = "rechka_lite_prefs"
        private const val KEY_STEP = "onboarding_step"
        private const val KEY_ONBOARDING_DONE = "onboarding_done"
    }
}
