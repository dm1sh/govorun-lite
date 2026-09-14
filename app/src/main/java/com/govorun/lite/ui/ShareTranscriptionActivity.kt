package com.govorun.lite.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.govorun.lite.R
import com.govorun.lite.model.GigaAmModel
import com.govorun.lite.stats.StatsStore
import com.govorun.lite.transcriber.AudioFileDecoder
import com.govorun.lite.transcriber.OfflineTranscriber
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Receives an audio file from Android's share sheet and transcribes it offline. */
class ShareTranscriptionActivity : AppCompatActivity() {
    companion object {
        private const val KEY_STATS_RECORDED = "share_stats_recorded"
    }

    private lateinit var status: MaterialTextView
    private lateinit var result: MaterialTextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var copy: MaterialButton
    private lateinit var shareAgain: MaterialButton
    private var transcript = ""
    private var statsRecorded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_share_transcription)
        statsRecorded = savedInstanceState?.getBoolean(KEY_STATS_RECORDED, false) ?: false
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.shareToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        status = findViewById(R.id.shareStatus)
        result = findViewById(R.id.shareResult)
        progress = findViewById(R.id.shareProgress)
        copy = findViewById(R.id.shareCopy)
        shareAgain = findViewById(R.id.shareAgain)
        copy.setOnClickListener { copyTranscript() }
        shareAgain.setOnClickListener { shareTranscript() }

        val uri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
        if (uri == null) {
            showError(getString(R.string.share_transcription_no_file))
            return
        }
        findViewById<MaterialTextView>(R.id.shareFileName).text =
            uri.lastPathSegment ?: getString(R.string.share_transcription_audio_file)
        transcribe(uri)
    }

    private fun transcribe(uri: android.net.Uri) {
        lifecycleScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    // Application startup normally extracts this in parallel; doing
                    // it here as well makes a share received immediately after install safe.
                    GigaAmModel.ensureInstalled(this@ShareTranscriptionActivity)
                    val pcm = AudioFileDecoder.decode(this@ShareTranscriptionActivity, uri)
                    if (pcm.isEmpty()) return@withContext ""
                    val transcriber = OfflineTranscriber.getInstance(this@ShareTranscriptionActivity)
                    transcriber.startAudio()
                    val bytes = ByteArray(pcm.size * 2)
                    for (i in pcm.indices) {
                        bytes[i * 2] = (pcm[i].toInt() and 0xff).toByte()
                        bytes[i * 2 + 1] = (pcm[i].toInt() shr 8).toByte()
                    }
                    // Keep memory use bounded for long files while using the same
                    // streaming API as microphone dictation.
                    var offset = 0
                    val chunk = 64 * 1024
                    while (offset < bytes.size) {
                        val end = minOf(offset + chunk, bytes.size)
                        transcriber.sendAudioChunk(bytes.copyOfRange(offset, end))
                        offset = end
                    }
                    transcriber.stopAudioAndGetTranscript()
                }
                transcript = text
                progress.visibility = View.GONE
                if (text.isBlank()) {
                    showError(getString(R.string.share_transcription_empty))
                } else {
                    if (!statsRecorded) {
                        StatsStore.addWords(applicationContext, StatsStore.countWords(text))
                        statsRecorded = true
                    }
                    status.text = getString(R.string.share_transcription_done)
                    result.text = text
                    result.visibility = View.VISIBLE
                    copy.visibility = View.VISIBLE
                    shareAgain.visibility = View.VISIBLE
                }
            } catch (t: Throwable) {
                showError(getString(R.string.share_transcription_failed, t.message ?: ""))
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_STATS_RECORDED, statsRecorded)
        super.onSaveInstanceState(outState)
    }

    private fun showError(message: String) {
        progress.visibility = View.GONE
        status.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun copyTranscript() {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(
            ClipData.newPlainText(getString(R.string.share_transcription_title), transcript)
        )
        Toast.makeText(this, R.string.share_transcription_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareTranscript() {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, transcript)
        }, getString(R.string.share_transcription_share_chooser)))
    }
}
