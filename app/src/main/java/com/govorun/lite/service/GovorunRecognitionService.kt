package com.govorun.lite.service

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log
import com.govorun.lite.model.GigaAmModel
import com.govorun.lite.transcriber.OfflineTranscriber
import com.govorun.lite.transcriber.VadRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Exposes the same offline recognizer through Android's speech-recognition API. */
class GovorunRecognitionService : RecognitionService() {
    private var recorder: VadRecorder? = null
    private var callback: Callback? = null
    private var recognized = StringBuilder()
    private var scope: CoroutineScope? = null

    override fun onStartListening(intent: Intent?, listener: Callback) {
        stopCurrent()
        callback = listener
        recognized = StringBuilder()
        if (!GigaAmModel.isInstalled(this)) {
            listener.error(SpeechRecognizer.ERROR_SERVER)
            return
        }
        listener.readyForSpeech(Bundle())
        listener.beginningOfSpeech()
        val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = sessionScope
        val currentRecorder = VadRecorder(this)
        recorder = currentRecorder
        currentRecorder.start(
            scope = sessionScope,
            transcriberProvider = { OfflineTranscriber.getInstance(this@GovorunRecognitionService) },
            onSegment = { text ->
                if (text.isNotBlank()) {
                    if (recognized.isNotEmpty()) recognized.append(' ')
                    recognized.append(text)
                    callback?.partialResults(resultBundle(recognized.toString()))
                }
            },
            onDone = {
                val text = recognized.toString().trim()
                callback?.results(resultBundle(text))
                callback?.endOfSpeech()
                clearSession()
            },
            useVad = true,
        )
    }

    override fun onStopListening(listener: Callback) {
        recorder?.stop()
    }

    override fun onCancel(listener: Callback) {
        stopCurrent()
    }

    private fun resultBundle(text: String): Bundle = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
    }

    private fun stopCurrent() {
        recorder?.stop()
        scope?.cancel()
        recorder = null
        scope = null
    }

    private fun clearSession() {
        recorder = null
        scope?.cancel()
        scope = null
        callback = null
    }

    override fun onDestroy() {
        stopCurrent()
        super.onDestroy()
    }
}
