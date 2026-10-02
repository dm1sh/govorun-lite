package ru.dm1sh.rechka.ui.onboarding

import androidx.fragment.app.Fragment
import ru.dm1sh.rechka.ui.OnboardingActivity

/**
 * Base contract for every step fragment. A step reports its completion state
 * via [setStepComplete]; the hosting activity reads that flag to enable or
 * disable the «Далее» button.
 *
 * Concrete fragments should call [setStepComplete] from their own lifecycle
 * (e.g. after a permission is granted, after a download finishes, etc.).
 * For trivially-complete screens like Welcome, just call it once in onCreate.
 */
abstract class OnboardingStepFragment : Fragment() {

    var isStepComplete: Boolean = false
        private set

    protected fun setStepComplete(complete: Boolean) {
        if (isStepComplete == complete) return
        isStepComplete = complete
        (activity as? OnboardingActivity)?.onStepStateChanged()
    }

    /**
     * Called when this step becomes the visible page. Useful for re-checking
     * permissions or connectivity that may have changed while another app
     * was in the foreground.
     */
    open fun onStepFocused() = Unit
}
