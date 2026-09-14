package com.govorun.lite.service

import android.Manifest
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.govorun.lite.R
import com.govorun.lite.model.GigaAmModel
import com.govorun.lite.stats.StatsStore
import com.govorun.lite.transcriber.OfflineTranscriber
import com.govorun.lite.transcriber.VadRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Voice-first auxiliary IME. It also provides the editing controls normally
 * available on a keyboard, so users can correct or remove the last dictation
 * without switching back to their regular keyboard.
 */
class GovorunInputMethodService : InputMethodService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var recorder: VadRecorder? = null
    private var recording = false

    private lateinit var recordButton: Button
    private lateinit var hint: TextView
    private lateinit var removeSessionButton: Button

    // Exact text committed during the current recognition session. It is reset
    // when the user presses "Начать диктовку". This lets removeSessionText()
    // safely remove only that session, provided the cursor is still at its end.
    private val sessionText = StringBuilder()

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }
        hint = TextView(this).apply {
            text = getString(R.string.ime_hint)
            setPadding(0, 0, 0, dp(8))
        }
        recordButton = Button(this).apply {
            text = getString(R.string.ime_start)
            setOnClickListener { if (recording) stopRecording() else startRecording() }
        }
        root.addView(hint, LinearLayout.LayoutParams(-1, -2))
        root.addView(recordButton, LinearLayout.LayoutParams(-1, -2))

        val editRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 5f
        }
        val backspace = makeControlButton(R.string.ime_backspace, "⌫") {
            deletePreviousCodePoint()
        }
        val wordBackspace = makeControlButton(R.string.ime_backspace_word, "⌫ слово") {
            deletePreviousWord()
        }
        val newline = makeControlButton(R.string.ime_newline, "↵") {
            commitInserted("\n")
        }
        removeSessionButton = makeControlButton(
            R.string.ime_remove_session,
            "Очистить",
        ) { removeSessionText() }
        val exit = makeControlButton(R.string.ime_exit, "Клавиатура") {
            exitToPreviousInputMethod()
        }
        listOf(backspace, wordBackspace, newline, removeSessionButton, exit).forEach {
            editRow.addView(it, LinearLayout.LayoutParams(0, dp(52), 1f))
        }
        root.addView(editRow, LinearLayout.LayoutParams(-1, -2))

        // The IME window shares the bottom edge with Android's gesture/navigation
        // area. Android also places the hide-IME and keyboard-switcher controls
        // there. Reserve a deliberately generous area so the complete pressable
        // area of those system controls stays below our content, rather than
        // sitting on top of the editing buttons.
        root.setOnApplyWindowInsetsListener { view, insets ->
            val navigationBottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            val imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom
            val bottom = maxOf(navigationBottom, imeBottom)
            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                dp(72) + bottom,
            )
            insets
        }
        root.requestApplyInsets()
        return root
    }

    private fun makeControlButton(labelRes: Int, fallbackText: String, action: () -> Unit): Button =
        Button(this).apply {
            contentDescription = getString(labelRes)
            text = fallbackText
            minWidth = 0
            minHeight = 0
            setPadding(dp(2), 0, dp(2), 0)
            setOnClickListener { action() }
        }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (::recordButton.isInitialized) {
            recordButton.text = getString(if (recording) R.string.ime_stop else R.string.ime_start)
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
        // A new tap starts a new removable session.
        sessionText.clear()
        recording = true
        recordButton.text = getString(R.string.ime_stop)
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
                recordButton.text = getString(R.string.ime_start)
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

    /** Commits text and remembers exactly what was inserted for session undo. */
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

    /**
     * Deletes the preceding whitespace-delimited token. Punctuation is not
     * stripped separately: it remains part of the token, so "слово," is
     * removed as one unit, including the comma.
     */
    private fun deletePreviousWord() {
        val connection = currentInputConnection ?: return
        val before = connection.getTextBeforeCursor(512, 0)?.toString() ?: return
        if (before.isEmpty()) return
        var end = before.length
        while (end > 0 && before[end - 1].isWhitespace()) end--
        var start = end
        while (start > 0 && !before[start - 1].isWhitespace()) start--
        if (start == end) return
        val codePoints = before.substring(start, before.length).codePointCount(0, before.length - start)
        connection.deleteSurroundingTextInCodePoints(codePoints, 0)
    }

    private fun removeSessionText() {
        val connection = currentInputConnection ?: return
        val text = sessionText.toString()
        if (text.isEmpty()) return
        val before = connection.getTextBeforeCursor(text.length + 8, 0)?.toString() ?: return
        // Do not delete unrelated text if the user moved the cursor or edited
        // the field after dictation. Verify the exact session suffix first.
        if (!before.endsWith(text)) return
        connection.deleteSurroundingTextInCodePoints(text.codePointCount(0, text.length), 0)
        sessionText.clear()
    }

    private fun exitToPreviousInputMethod() {
        stopRecording()
        // Auxiliary voice subtypes are intended to return to the IME that was
        // active before them. This is the same framework path used by the
        // standard Android voice keyboard.
        if (!switchToPreviousInputMethod()) {
            requestHideSelf(0)
        }
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

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
