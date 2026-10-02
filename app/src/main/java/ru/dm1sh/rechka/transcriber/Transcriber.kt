package ru.dm1sh.rechka.transcriber

interface Transcriber {
    suspend fun connect()
    suspend fun startAudio(rate: Int = 16000, width: Int = 2, channels: Int = 1)
    suspend fun sendAudioChunk(pcmData: ByteArray, rate: Int = 16000, width: Int = 2, channels: Int = 1)
    suspend fun stopAudioAndGetTranscript(): String
    fun disconnect()
}
