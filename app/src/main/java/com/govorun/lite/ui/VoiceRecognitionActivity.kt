package com.govorun.lite.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.Gravity
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

/** Handles apps/keyboards that use ACTION_RECOGNIZE_SPEECH instead of an IME. */
class VoiceRecognitionActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var recorder: VadRecorder? = null
    private lateinit var status: TextView
    private lateinit var button: Button
    private var transcript = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(40, 32, 40, 32)
        }
        status = TextView(this).apply {
            text = getString(R.string.voice_recognition_listening)
            textSize = 18f
            gravity = Gravity.CENTER
        }
        button = Button(this).apply {
            text = getString(R.string.voice_recognition_stop)
            setOnClickListener { stopListening() }
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))
        root.addView(button, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            !GigaAmModel.isInstalled(this)) {
            finishWithError()
        } else {
            startListening()
        }
    }

    private fun startListening() {
        transcript = StringBuilder()
        val currentRecorder = VadRecorder(this)
        recorder = currentRecorder
        currentRecorder.start(
            scope = scope,
            transcriberProvider = { OfflineTranscriber.getInstance(this@VoiceRecognitionActivity) },
            onSegment = { text ->
                if (text.isNotBlank()) {
                    if (transcript.isNotEmpty()) transcript.append(' ')
                    transcript.append(text)
                }
            },
            onDone = {
                val result = transcript.toString().trim()
                if (result.isBlank()) finishWithError() else finishWithResult(result)
            },
            useVad = true,
        )
    }

    private fun stopListening() {
        status.text = getString(R.string.voice_recognition_processing)
        button.isEnabled = false
        recorder?.stop()
    }

    private fun finishWithResult(text: String) {
        StatsStore.addWords(applicationContext, StatsStore.countWords(text))
        setResult(RESULT_OK, Intent().apply {
            putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf(text))
        })
        finish()
    }

    private fun finishWithError() {
        setResult(RESULT_CANCELED)
        finish()
    }

    override fun onDestroy() {
        recorder?.stop()
        scope.cancel()
        super.onDestroy()
    }
}
