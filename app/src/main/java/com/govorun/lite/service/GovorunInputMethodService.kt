package com.govorun.lite.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.inputmethodservice.InputMethodService
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.govorun.lite.R
import com.govorun.lite.model.GigaAmModel
import com.govorun.lite.stats.StatsStore
import com.govorun.lite.transcriber.OfflineTranscriber
import com.govorun.lite.util.Prefs
import com.govorun.lite.transcriber.VadRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Voice-first auxiliary IME with dictation and lightweight editing controls. */
class GovorunInputMethodService : InputMethodService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var recorder: VadRecorder? = null
    private var recording = false
    private val touchHandler = Handler(Looper.getMainLooper())
    private var walkieLongPressTriggered = false
    private var walkieLocked = false
    private var walkieDownRawY = 0f
    private val walkieStartRunnable = Runnable {
        if (!recording && walkieLongPressTriggered) startRecording()
    }

    private lateinit var recordButton: MaterialButton
    private lateinit var themedContext: Context
    private var currentEditorInfo: EditorInfo? = null
    private val sessionText = StringBuilder()

    override fun onCreateInputView(): View {
        // InputMethodService does not inherit the Activity theme. Wrapping the
        // context is required for MaterialButton's theme enforcement and also
        // lets Material resolve the device's Monet/dynamic colors.
        themedContext = DynamicColors.wrapContextIfAvailable(
            ContextThemeWrapper(this, R.style.Theme_GovorunIme)
        )
        val root = LinearLayout(themedContext).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(MaterialColors.getColor(
                themedContext,
                com.google.android.material.R.attr.colorSurface,
                Color.TRANSPARENT,
            ))
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }

        val topRow = LinearLayout(themedContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.START
        }
        val exitButton = makeIconButton(
            R.drawable.ic_arrow_back_24,
            R.string.ime_exit,
        ) { exitToPreviousInputMethod() }
        topRow.addView(exitButton, LinearLayout.LayoutParams(dp(56), dp(48)))
        root.addView(topRow, LinearLayout.LayoutParams(-1, -2))

        // Fixed order: clear, whole-word backspace, start/stop, character
        // backspace, newline.
        val controls = LinearLayout(themedContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            // Four flexible edit buttons plus one fixed circular record button.
            weightSum = 4f
        }
        controls.addView(makeIconButton(R.drawable.ic_clear_all_24, R.string.ime_remove_session) {
            removeSessionText()
        }, weightedButtonParams())
        controls.addView(makeIconButton(R.drawable.ic_backspace_word_24, R.string.ime_backspace_word) {
            deletePreviousWord()
        }, weightedButtonParams())
        recordButton = makeRecordButton()
        configureRecordTouch()
        controls.addView(recordButton, recordButtonParams())
        controls.addView(makeIconButton(R.drawable.ic_backspace_24, R.string.ime_backspace) {
            deletePreviousCodePoint()
        }, weightedButtonParams())
        val enterButton = makeIconButton(R.drawable.ic_keyboard_return_24, R.string.ime_newline) {
            if (!performEditorActionIfSupported()) commitInserted("\n")
        }
        // Long-press is intentionally different from a tap: it always inserts
        // a literal newline, even for search/go/done editor actions.
        enterButton.setOnLongClickListener {
            commitInserted("\n")
            true
        }
        controls.addView(enterButton, weightedButtonParams())
        root.addView(controls, LinearLayout.LayoutParams(-1, dp(64)))

        // Keep the entire system hide/switcher touch target below our content.
        // Android 17 can place those controls over the bottom of an IME view.
        root.setOnApplyWindowInsetsListener { view, insets ->
            val nav = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            val ime = insets.getInsets(WindowInsets.Type.ime()).bottom
            // The inset is exactly the system-owned touch area. Do not add
            // an extra fixed spacer: it makes the voice panel unnecessarily tall.
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, maxOf(nav, ime) + dp(12))
            insets
        }
        root.requestApplyInsets()
        return root
    }

    private fun makeIconButton(iconRes: Int, descriptionRes: Int, action: () -> Unit): MaterialButton =
        MaterialButton(themedContext).apply {
            contentDescription = getString(descriptionRes)
            tooltipText = getString(descriptionRes)
            icon = ContextCompat.getDrawable(themedContext, iconRes)
            iconTint = ColorStateList.valueOf(onSurfaceVariantColor())
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            iconPadding = 0
            iconSize = dp(28)
            gravity = android.view.Gravity.CENTER
            text = ""
            minWidth = 0
            minHeight = 0
            insetTop = 0
            insetBottom = 0
            elevation = 0f
            strokeWidth = 0
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            setPadding(0, 0, 0, 0)
            setOnClickListener { action() }
        }

    private fun makeRecordButton(): MaterialButton =
        makeIconButton(R.drawable.ic_mic_24, R.string.ime_start) {
            if (recording) stopRecording() else startRecording()
        }.apply {
            iconSize = dp(32)
            cornerRadius = dp(32)
            backgroundTintList = ColorStateList.valueOf(primaryColor())
            iconTint = ColorStateList.valueOf(onPrimaryColor())
            insetTop = dp(4)
            insetBottom = dp(4)
        }

    private fun configureRecordTouch() {
        recordButton.setOnTouchListener { _, event ->
            if (!Prefs.isImeWalkieTalkieEnabled(this)) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    walkieLongPressTriggered = !recording
                    walkieLocked = false
                    walkieDownRawY = event.rawY
                    if (walkieLongPressTriggered) {
                        touchHandler.postDelayed(
                            walkieStartRunnable,
                            ViewConfiguration.getLongPressTimeout().toLong(),
                        )
                    }
                    recordButton.isPressed = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (walkieLongPressTriggered && !walkieLocked &&
                        walkieDownRawY - event.rawY >= dp(48)
                    ) {
                        // Slide upward to lock recording after release.
                        walkieLocked = true
                        recordButton.isPressed = false
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    touchHandler.removeCallbacks(walkieStartRunnable)
                    recordButton.isPressed = false
                    if (walkieLongPressTriggered) {
                        if (!walkieLocked && recording) stopRecording()
                    } else if (recording) {
                        // A tap always stops an active recording, including an
                        // automatically started or previously locked session.
                        stopRecording()
                    }
                    walkieLongPressTriggered = false
                    true
                }
                else -> true
            }
        }
    }

    private fun weightedButtonParams() = LinearLayout.LayoutParams(0, dp(56), 1f).apply {
        marginStart = dp(4)
        marginEnd = dp(4)
    }

    private fun recordButtonParams() = LinearLayout.LayoutParams(dp(64), dp(64)).apply {
        marginStart = dp(4)
        marginEnd = dp(4)
    }

    private fun primaryColor(): Int = MaterialColors.getColor(
        themedContext, com.google.android.material.R.attr.colorPrimary, Color.rgb(70, 90, 150)
    )

    private fun errorColor(): Int = MaterialColors.getColor(
        themedContext, com.google.android.material.R.attr.colorError, Color.rgb(186, 26, 26)
    )

    private fun onSurfaceVariantColor(): Int = MaterialColors.getColor(
        themedContext, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.DKGRAY
    )

    private fun onPrimaryColor(): Int = MaterialColors.getColor(
        themedContext, com.google.android.material.R.attr.colorOnPrimary, Color.WHITE
    )

    private fun onErrorColor(): Int = MaterialColors.getColor(
        themedContext, com.google.android.material.R.attr.colorOnError, Color.WHITE
    )

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        currentEditorInfo = info
        if (::recordButton.isInitialized) {
            updateRecordButton()
            if (Prefs.isImeAutoStartEnabled(this) && !recording) {
                recordButton.post { if (!recording) startRecording() }
            }
        }
    }

    private fun startRecording() {
        if (recording) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            recordButton.tooltipText = getString(R.string.ime_mic_permission)
            return
        }
        if (!GigaAmModel.isInstalled(this)) {
            recordButton.tooltipText = getString(R.string.ime_model_not_ready)
            return
        }
        sessionText.clear()
        recording = true
        updateRecordButton()
        recordButton.tooltipText = getString(R.string.ime_listening)
        val currentRecorder = VadRecorder(this)
        recorder = currentRecorder
        currentRecorder.start(
            scope = scope,
            transcriberProvider = { OfflineTranscriber.getInstance(this@GovorunInputMethodService) },
            onSegment = { text ->
                if (text.isNotBlank()) {
                    commitInserted(text)
                    StatsStore.addWords(applicationContext, StatsStore.countWords(text))
                }
            },
            onDone = {
                recording = false
                recorder = null
                updateRecordButton()
                recordButton.tooltipText = getString(R.string.ime_start)
            },
            useVad = true,
        )
    }

    private fun stopRecording() {
        if (!recording) return
        recordButton.tooltipText = getString(R.string.ime_processing)
        recorder?.stop()
    }

    private fun updateRecordButton() {
        if (!::recordButton.isInitialized) return
        recordButton.icon = ContextCompat.getDrawable(
            themedContext,
            if (recording) R.drawable.ic_stop_24 else R.drawable.ic_mic_24,
        )
        recordButton.backgroundTintList = ColorStateList.valueOf(
            if (recording) errorColor() else primaryColor()
        )
        recordButton.iconTint = ColorStateList.valueOf(
            if (recording) onErrorColor() else onPrimaryColor()
        )
        val label = if (recording) R.string.ime_stop else R.string.ime_start
        recordButton.contentDescription = getString(label)
        recordButton.tooltipText = getString(label)
    }

    private fun performEditorActionIfSupported(): Boolean {
        val info = currentEditorInfo ?: return false
        if ((info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return false
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        if (action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED) return false
        return currentInputConnection?.performEditorAction(action) == true
    }

    private fun commitInserted(text: String) {
        if (text.isEmpty()) return
        currentInputConnection?.commitText(text, 1)
        sessionText.append(text)
    }

    private fun deletePreviousCodePoint() {
        val connection = currentInputConnection ?: return
        if (connection.getTextBeforeCursor(1, 0)?.isNotEmpty() == true) {
            connection.deleteSurroundingTextInCodePoints(1, 0)
        }
    }

    /** Attached punctuation stays part of the preceding word/token. */
    private fun deletePreviousWord() {
        val connection = currentInputConnection ?: return
        val before = connection.getTextBeforeCursor(512, 0)?.toString() ?: return
        if (before.isEmpty()) return
        var end = before.length
        while (end > 0 && before[end - 1].isWhitespace()) end--
        var start = end
        while (start > 0 && !before[start - 1].isWhitespace()) start--
        if (start == end) return
        val token = before.substring(start)
        connection.deleteSurroundingTextInCodePoints(token.codePointCount(0, token.length), 0)
    }

    private fun removeSessionText() {
        val connection = currentInputConnection ?: return
        val text = sessionText.toString()
        if (text.isEmpty()) return
        val before = connection.getTextBeforeCursor(text.length + 8, 0)?.toString() ?: return
        if (!before.endsWith(text)) return
        connection.deleteSurroundingTextInCodePoints(text.codePointCount(0, text.length), 0)
        sessionText.clear()
    }

    private fun exitToPreviousInputMethod() {
        stopRecording()
        if (!switchToPreviousInputMethod()) requestHideSelf(0)
    }

    override fun onFinishInput() {
        stopRecording()
        super.onFinishInput()
    }

    override fun onDestroy() {
        recorder?.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
