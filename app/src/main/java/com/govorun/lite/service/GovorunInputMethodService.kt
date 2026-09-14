package com.govorun.lite.service

import android.Manifest
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.govorun.lite.R
import com.govorun.lite.model.GigaAmModel
import com.govorun.lite.stats.StatsStore
import com.govorun.lite.transcriber.OfflineTranscriber
import com.govorun.lite.transcriber.VadRecorder
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Minimal voice-input IME. It deliberately only exposes a microphone button;
 * the user's normal keyboard remains available through the system keyboard
 * switcher. Recognized text is committed to the current InputConnection.
 */
class GovorunInputMethodService : InputMethodService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var recorder: VadRecorder? = null
    private var recording = false
    private lateinit var button: MaterialButton
    private lateinit var hint: TextView

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }
        hint = TextView(this).apply {
            text = getString(R.string.ime_hint)
            setPadding(0, 0, 0, 8)
        }
        button = MaterialButton(this).apply {
            text = getString(R.string.ime_start)
            setOnClickListener { if (recording) stopRecording() else startRecording() }
        }
        root.addView(hint, LinearLayout.LayoutParams(-1, -2))
        root.addView(button, LinearLayout.LayoutParams(-1, -2))
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (::button.isInitialized) {
            button.text = getString(if (recording) R.string.ime_stop else R.string.ime_start)
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
        recording = true
        button.text = getString(R.string.ime_stop)
        hint.text = getString(R.string.ime_listening)
        val currentRecorder = VadRecorder(this)
        recorder = currentRecorder
        currentRecorder.start(
            scope = scope,
            transcriberProvider = { OfflineTranscriber.getInstance(this@GovorunInputMethodService) },
            onSegment = { text ->
                val connection = currentInputConnection
                if (connection != null && text.isNotBlank()) {
                    connection.commitText(text, 1)
                    StatsStore.addWords(applicationContext, StatsStore.countWords(text))
                }
            },
            onDone = {
                recording = false
                recorder = null
                button.text = getString(R.string.ime_start)
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

    override fun onFinishInput() {
        stopRecording()
        super.onFinishInput()
    }

    override fun onDestroy() {
        recorder?.stop()
        scope.cancel()
        super.onDestroy()
    }
}
