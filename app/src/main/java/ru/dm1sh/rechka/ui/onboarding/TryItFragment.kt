package ru.dm1sh.rechka.ui.onboarding

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
import com.google.android.material.textview.MaterialTextView
import ru.dm1sh.rechka.R
import ru.dm1sh.rechka.model.GigaAmModel
import ru.dm1sh.rechka.overlay.BubbleView
import ru.dm1sh.rechka.stats.StatsStore
import ru.dm1sh.rechka.transcriber.OfflineTranscriber
import ru.dm1sh.rechka.transcriber.VadRecorder
import ru.dm1sh.rechka.util.Haptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * «Попробуйте» step — combined just-in-time mic permission + live demo.
 * The big mascot button is the only path forward: tap it → if RECORD_AUDIO
 * isn't granted, the system dialog appears → on grant, recording starts
 * immediately. Speaking produces text in the result card with a blinking
 * cursor.
 *
 * Step is marked complete the moment mic permission is granted (recording
 * itself is optional — Далее unblocks as soon as we have the mic).
 */
class TryItFragment : OnboardingStepFragment() {

    private lateinit var bubble: BubbleView
    private lateinit var hint: MaterialTextView
    private lateinit var grantHint: MaterialTextView
    private lateinit var resultScroll: ScrollView
    private lateinit var resultText: MaterialTextView
    private lateinit var doneLabel: MaterialTextView
    private lateinit var openSettingsLink: MaterialTextView

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            setStepComplete(true)
            refreshHint()
            startRecording()
        } else {
            val permanentlyDenied = !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
            openSettingsLink.visibility = if (permanentlyDenied) View.VISIBLE else View.GONE
            hint.setText(R.string.onb_try_denied)
        }
    }

    private var recorder: VadRecorder? = null
    @Volatile private var isRecording = false

    companion object {
        // Same threshold as LiteAccessibilityService — tap is anything
        // shorter, hold is anything longer (provided the finger doesn't
        // move past the touch slop in the meantime).
        private const val HOLD_DELAY_MS = 250L
    }
    private var accumulated = StringBuilder()
    private var recordStartMs: Long = 0L
    // Resets every time the view is (re)created, i.e. every fresh visit to
    // this step — swipe away + back, leave and relaunch, rotation. Within
    // one visit the halo still stops the moment the first word is
    // recognised (see appendSegment), which is the original bug fix.
    private var hasCompletedDemo = false

    private val cursorChar = "▏"
    private var cursorVisible = true
    private val cursorHandler = Handler(Looper.getMainLooper())
    private val cursorBlink = object : Runnable {
        override fun run() {
            cursorVisible = !cursorVisible
            renderResult()
            cursorHandler.postDelayed(this, 530L)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_onboarding_try_it, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bubble = view.findViewById(R.id.tryBubble)
        hint = view.findViewById(R.id.tryHint)
        grantHint = view.findViewById(R.id.tryGrantHint)
        resultScroll = view.findViewById(R.id.tryResultScroll)
        resultText = view.findViewById(R.id.tryResult)
        doneLabel = view.findViewById(R.id.tryDoneLabel)
        openSettingsLink = view.findViewById(R.id.tryOpenSettings)

        // Each fresh view = a fresh visit to this step. FragmentStateAdapter
        // keeps the fragment instance alive across swipes so a field-level
        // flag would persist and the halo would never come back; resetting
        // here guarantees the "tap me" cue reappears every time.
        hasCompletedDemo = false
        accumulated = StringBuilder()
        doneLabel.visibility = View.GONE

        setStepComplete(hasMicPermission())

        // Touch listener mirroring LiteAccessibilityService: tap-to-toggle
         // OR hold-to-talk OR horizontal-swipe-passthrough. Demo bubble has
         // no drag-to-position (it's anchored to the layout), so we don't
         // touch the bubble's location — movement just blocks tap activation.
        bubble.setOnTouchListener(object : View.OnTouchListener {
            private val touchSlopPx = 10f * resources.displayMetrics.density
            private val holdMovementSlopPx = 5f * resources.displayMetrics.density
            private val holdHandler = Handler(Looper.getMainLooper())
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var lastTouchX = 0f
            private var lastTouchY = 0f
            private var moved = false
            private var holdStarted = false
            private val holdRunnable = Runnable {
                if (moved || holdStarted || !bubble.isEnabled || !hasMicPermission()) return@Runnable
                // Tighter check than the touch-slop drag threshold —
                // catches slow swipes that haven't yet tripped `moved` but
                // are clearly in motion. Without this, slow gestures
                // briefly start recording before being cancelled.
                val movedX = Math.abs(lastTouchX - initialTouchX)
                val movedY = Math.abs(lastTouchY - initialTouchY)
                if (movedX > holdMovementSlopPx || movedY > holdMovementSlopPx) return@Runnable
                holdStarted = true
                // Hold-to-talk: raw PCM mode so the whole utterance reaches
                // GigaAM as one block instead of being split on pauses.
                startRecording(useVad = false)
            }

            @SuppressLint("ClickableViewAccessibility")
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                if (!bubble.isEnabled) return false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        lastTouchX = event.rawX
                        lastTouchY = event.rawY
                        moved = false
                        holdStarted = false
                        holdHandler.postDelayed(holdRunnable, HOLD_DELAY_MS)
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        lastTouchX = event.rawX
                        lastTouchY = event.rawY
                        // Once hold-recording started the gesture is locked —
                        // ignore movement, only release (UP) stops it.
                        if (holdStarted) return true
                        if (!moved) {
                            val dx = event.rawX - initialTouchX
                            val dy = event.rawY - initialTouchY
                            if (Math.abs(dx) > touchSlopPx || Math.abs(dy) > touchSlopPx) {
                                moved = true
                                holdHandler.removeCallbacks(holdRunnable)
                            }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        holdHandler.removeCallbacks(holdRunnable)
                        if (moved) return true
                        if (holdStarted) {
                            stopRecording()
                            return true
                        }
                        // Quick tap: same path as the original click handler —
                        // requests permission if missing, else toggles.
                        onMicTapped()
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        // System-initiated abort (finger left view bounds,
                        // parent intercepted, etc.) — clean up without
                        // triggering the tap path.
                        holdHandler.removeCallbacks(holdRunnable)
                        if (holdStarted) stopRecording()
                        return true
                    }
                }
                return false
            }
        })
        openSettingsLink.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", requireContext().packageName, null)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
        refreshModelState()
        renderResult()
        startCursorBlink()
        // Halo runs only until the user has proven to themselves that the
        // bubble works — the first recognised segment disables it (see
        // appendSegment). This avoids the old bug where the halo kept
        // pulsing forever after mic permission was granted and the test
        // had clearly succeeded.
        if (!hasCompletedDemo) bubble.setIdlePulse(true)
    }

    override fun onResume() {
        super.onResume()
        // Re-check after the user comes back from app settings.
        if (hasMicPermission()) {
            setStepComplete(true)
            openSettingsLink.visibility = View.GONE
            refreshHint()
        }
    }

    override fun onStepFocused() {
        refreshModelState()
    }

    private fun hasMicPermission(): Boolean = ContextCompat.checkSelfPermission(
        requireContext(), Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    override fun onPause() {
        super.onPause()
        // Releasing the mic is mandatory when the user swipes away; otherwise
        // AudioRecord stays open and other apps will fail to grab the mic.
        if (isRecording) stopRecording()
    }

    override fun onDestroyView() {
        stopCursorBlink()
        super.onDestroyView()
    }

    private fun refreshModelState() {
        val ready = GigaAmModel.isInstalled(requireContext())
        bubble.isEnabled = ready
        if (!ready) {
            hint.setText(R.string.onb_try_preparing)
            viewLifecycleOwner.lifecycleScope.launch {
                while (!GigaAmModel.isInstalled(requireContext())) delay(500L)
                if (!isAdded) return@launch
                bubble.isEnabled = true
                if (!isRecording) refreshHint()
            }
        } else {
            if (!isRecording) refreshHint()
        }
    }

    private fun refreshHint() {
        // Top hint stays focused on what to do RIGHT NOW — success feedback
        // lives in doneLabel below the result card, so this line never has
        // to compete with the "Отлично!" message.
        val res = when {
            isRecording -> R.string.onb_try_tap_to_stop
            hasMicPermission() -> R.string.onb_try_tap_to_start
            else -> R.string.onb_try_tap_to_grant
        }
        hint.setText(res)
        // Sub-hint about which permission option to pick — visible only
        // before the user has granted.
        grantHint.visibility = if (!isRecording && !hasMicPermission()) View.VISIBLE else View.GONE
    }

    private fun onMicTapped() {
        if (!hasMicPermission()) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (isRecording) stopRecording() else startRecording()
    }

    @SuppressLint("MissingPermission")
    private fun startRecording(useVad: Boolean = true) {
        if (isRecording) return
        isRecording = true
        accumulated = StringBuilder()
        recordStartMs = android.os.SystemClock.elapsedRealtime()
        bubble.setRecording(true)
        hint.setText(R.string.onb_try_tap_to_stop)
        renderResult()
        Haptics.longPress(requireContext())

        val ctx = requireContext().applicationContext
        val rec = VadRecorder(ctx).also { recorder = it }
        rec.start(
            scope = viewLifecycleOwner.lifecycleScope,
            transcriberProvider = {
                withContext(Dispatchers.IO) { OfflineTranscriber.getInstance(ctx) }
            },
            onSegment = { text -> appendSegment(text) },
            useVad = useVad,
        )
    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        recorder?.stop()
        recorder = null
        bubble.setRecording(false)
        refreshHint()
        Haptics.doubleTap(requireContext())
        // Contribute the recording duration to the dashboard "minutes" counter.
        // Word counts are added per-segment in appendSegment as text arrives.
        if (recordStartMs > 0L) {
            val seconds = (android.os.SystemClock.elapsedRealtime() - recordStartMs) / 1000L
            StatsStore.addSeconds(requireContext().applicationContext, seconds)
            recordStartMs = 0L
        }
    }

    private suspend fun appendSegment(text: String) {
        if (text.isBlank()) return
        val trimmed = text.trim()
        withContext(Dispatchers.Main) {
            val isFirstSegment = accumulated.isEmpty()
            if (!isFirstSegment) accumulated.append(' ')
            accumulated.append(trimmed)
            renderResult()
            if (isFirstSegment) {
                // Persistent success confirmation below the result card — once
                // the user has proven the demo works, the «Далее» prompt sticks
                // around for the rest of this visit so it's visible whether
                // they stop and start another recording or not.
                doneLabel.visibility = View.VISIBLE
                if (!hasCompletedDemo) {
                    hasCompletedDemo = true
                    bubble.setIdlePulse(false)
                }
            }
            // Feed the dashboard counter straight from the onboarding demo —
            // that way the main screen's "Ваша статистика" card already shows
            // real numbers the moment the user lands on it.
            val words = StatsStore.countWords(trimmed)
            StatsStore.addWords(requireContext().applicationContext, words)
        }
    }

    private fun startCursorBlink() {
        cursorHandler.removeCallbacks(cursorBlink)
        cursorVisible = true
        renderResult()
        cursorHandler.postDelayed(cursorBlink, 530L)
    }

    private fun stopCursorBlink() {
        cursorHandler.removeCallbacks(cursorBlink)
    }

    private fun renderResult() {
        if (view == null) return
        val empty = accumulated.isEmpty()
        val base = if (empty) getString(R.string.onb_try_result_placeholder) else accumulated.toString()
        val textColorAttr = if (empty) {
            com.google.android.material.R.attr.colorOnSurfaceVariant
        } else {
            com.google.android.material.R.attr.colorOnSurface
        }
        val textColor = MaterialColors.getColor(requireView(), textColorAttr)
        val cursorColor = if (cursorVisible) {
            MaterialColors.getColor(requireView(), com.google.android.material.R.attr.colorPrimary)
        } else {
            Color.TRANSPARENT
        }
        val full = base + cursorChar
        val sp = SpannableString(full)
        sp.setSpan(ForegroundColorSpan(textColor), 0, base.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        sp.setSpan(ForegroundColorSpan(cursorColor), full.length - 1, full.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        resultText.text = sp
        // Card height is fixed; auto-scroll inside it so new dictation lines
        // remain visible without pushing the bird button down the screen.
        if (!empty) resultScroll.post { resultScroll.fullScroll(View.FOCUS_DOWN) }
    }
}
