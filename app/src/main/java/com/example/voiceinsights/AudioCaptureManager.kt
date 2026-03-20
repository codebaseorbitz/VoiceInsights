package com.example.voiceinsights

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream

class AudioCaptureManager(private val context: Context) {
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Whisper expects 16kHz, 16-bit, Mono audio
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    
    // Chunk duration: 5 minutes = 300,000 ms
    private val chunkDurationMs = 5 * 60 * 1000L 

    @SuppressLint("MissingPermission")
    fun startCapture() {
        if (isRecording) return
        
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("AudioCapture", "AudioRecord initialization failed!")
                return
            }

            audioRecord?.startRecording()
            isRecording = true
            
            recordingJob = scope.launch {
                writeAudioDataToChunks()
            }
            Log.d("AudioCapture", "Started continuous audio capture")
        } catch (e: Exception) {
            Log.e("AudioCapture", "Error starting capture: ${e.message}")
        }
    }

    private suspend fun writeAudioDataToChunks() {
        val audioData = ByteArray(bufferSize)
        var currentFile: File? = null
        var outputStream: FileOutputStream? = null
        var currentChunkStartTime = System.currentTimeMillis()

        try {
            while (isRecording) {
                // Initialize new chunk file every 5 minutes
                if (outputStream == null || (System.currentTimeMillis() - currentChunkStartTime) >= chunkDurationMs) {
                    outputStream?.apply {
                        flush()
                        close()
                    }
                    
                    if (currentFile != null) {
                        Log.d("AudioCapture", "Chunk completed: ${currentFile.absolutePath}")
                        // TODO: trigger transcription worker for currentFile
                    }

                    val timestamp = System.currentTimeMillis()
                    val audioDir = File(context.filesDir, "audio_chunks").apply { mkdirs() }
                    currentFile = File(audioDir, "chunk_$timestamp.pcm")
                    outputStream = FileOutputStream(currentFile)
                    currentChunkStartTime = timestamp
                    Log.d("AudioCapture", "Started new chunk: ${currentFile.name}")
                }

                val readSize = audioRecord?.read(audioData, 0, bufferSize) ?: 0
                if (readSize > 0) {
                    outputStream?.write(audioData, 0, readSize)
                }
            }
        } catch (e: Exception) {
            Log.e("AudioCapture", "Error writing audio data: ${e.message}")
        } finally {
            outputStream?.apply {
                flush()
                close()
            }
            Log.d("AudioCapture", "Finalized last audio chunk.")
        }
    }

    fun stopCapture() {
        isRecording = false
        recordingJob?.cancel()
        audioRecord?.apply {
            stop()
            release()
        }
        audioRecord = null
        Log.d("AudioCapture", "Stopped audio capture")
    }
}
