package com.govorun.lite.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import com.govorun.lite.R
import com.govorun.lite.model.GigaAmModel
import com.govorun.lite.stats.StatsStore
import com.govorun.lite.transcriber.OfflineTranscriber
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

    private lateinit var recordButton: MaterialButton
    private lateinit var hint: TextView
    private lateinit var themedContext: Context
    private val sessionText = StringBuilder()

    override fun onCreateInputView(): View {
        // InputMethodService does not inherit the Activity theme. Wrapping the
        // context is required for MaterialButton's theme enforcement and also
        // lets Material resolve the device's Monet/dynamic colors.
        themedContext = DynamicColors.wrapContextIfAvailable(this)
        val root = LinearLayout(themedContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(16))
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

        hint = TextView(themedContext).apply {
            text = getString(R.string.ime_hint)
            textSize = 18f
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(hint, LinearLayout.LayoutParams(-1, -2))

        // Fixed order: clear, whole-word backspace, start/stop, character
        // backspace, newline.
        val controls = LinearLayout(themedContext).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 5f
        }
        controls.addView(makeIconButton(R.drawable.ic_clear_all_24, R.string.ime_remove_session) {
            removeSessionText()
        }, weightedButtonParams())
        controls.addView(makeIconButton(R.drawable.ic_backspace_word_24, R.string.ime_backspace_word) {
            deletePreviousWord()
        }, weightedButtonParams())
        recordButton = makeIconButton(R.drawable.ic_mic_24, R.string.ime_start) {
            if (recording) stopRecording() else startRecording()
        }
        controls.addView(recordButton, weightedButtonParams())
        controls.addView(makeIconButton(R.drawable.ic_backspace_24, R.string.ime_backspace) {
            deletePreviousCodePoint()
        }, weightedButtonParams())
        controls.addView(makeIconButton(R.drawable.ic_keyboard_return_24, R.string.ime_newline) {
            commitInserted("\n")
        }, weightedButtonParams())
        root.addView(controls, LinearLayout.LayoutParams(-1, dp(56)))

        // Keep the entire system hide/switcher touch target below our content.
        // Android 17 can place those controls over the bottom of an IME view.
        root.setOnApplyWindowInsetsListener { view, insets ->
            val nav = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            val ime = insets.getInsets(WindowInsets.Type.ime()).bottom
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, dp(88) + maxOf(nav, ime))
            insets
        }
        root.requestApplyInsets()
        return root
    }

    private fun makeIconButton(iconRes: Int, descriptionRes: Int, action: () -> Unit): MaterialButton =
        MaterialButton(themedContext).apply {
            contentDescription = getString(descriptionRes)
            icon = ContextCompat.getDrawable(themedContext, iconRes)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            iconPadding = 0
            text = ""
            minWidth = 0
            minHeight = 0
            insetTop = 4
            insetBottom = 4
            setPadding(0, 0, 0, 0)
            setOnClickListener { action() }
        }

    private fun weightedButtonParams() = LinearLayout.LayoutParams(0, dp(56), 1f)

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (::recordButton.isInitialized) {
            recordButton.icon = ContextCompat.getDrawable(
                themedContext,
                if (recording) R.drawable.ic_stop_24 else R.drawable.ic_mic_24,
            )
            recordButton.contentDescription = getString(
                if (recording) R.string.ime_stop else R.string.ime_start,
            )
        }
    }

    private fun startRecording() {
        if (recording) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            hint.text = getString(R.string.ime_mic_permission)
            return
        }
        if (!GigaAmModel.isInstalled(this)) {
            hint.text = getString(R.string.ime_model_not_ready)
            return
        }
        sessionText.clear()
        recording = true
        updateRecordButton()
        hint.text = getString(R.string.ime_listening)
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
                hint.text = getString(R.string.ime_hint)
            },
            useVad = true,
        )
    }

    private fun stopRecording() {
        if (!recording) return
        hint.text = getString(R.string.ime_processing)
        recorder?.stop()
    }

    private fun updateRecordButton() {
        if (!::recordButton.isInitialized) return
        recordButton.icon = ContextCompat.getDrawable(
            themedContext,
            if (recording) R.drawable.ic_stop_24 else R.drawable.ic_mic_24,
        )
        recordButton.contentDescription = getString(
            if (recording) R.string.ime_stop else R.string.ime_start,
        )
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
