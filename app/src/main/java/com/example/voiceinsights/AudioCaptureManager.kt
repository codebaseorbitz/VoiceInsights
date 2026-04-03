package com.example.voiceinsights

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import java.io.File

class AudioCaptureManager(private val context: Context) {
    private var mediaRecorder: MediaRecorder? = null
    @Volatile
    private var isRecording = false
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val chunkDurationMs = 10 * 60 * 1000L 

    private fun createMediaRecorder(): MediaRecorder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
    }

    fun startCapture() {
        if (isRecording) return
        isRecording = true
        
        recordingJob = scope.launch {
            // Flush any leftover .m4a files from a previous session (e.g., post-call resume)
            renameRecordingFiles()
            val audioDir = File(context.filesDir, "audio_chunks")
            val leftoverFiles = audioDir.listFiles { f -> f.name.endsWith(".m4a") }
            if (!leftoverFiles.isNullOrEmpty()) {
                Log.d("AudioCapture", "Found ${leftoverFiles.size} leftover file(s) — triggering upload")
                DriveUploadWorker.enqueue(context)
            }

            try {
                while (isRecording && isActive) {
                    val timestamp = System.currentTimeMillis()
                    audioDir.mkdirs()
                    // Use .recording extension while in progress
                    val recordingFile = File(audioDir, "chunk_$timestamp.m4a.recording")
                    val finalFile = File(audioDir, "chunk_$timestamp.m4a")

                    mediaRecorder = createMediaRecorder().apply {
                        setAudioSource(MediaRecorder.AudioSource.MIC)
                        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                        setAudioChannels(1)
                        setAudioEncodingBitRate(64000)
                        setAudioSamplingRate(44100)
                        setOutputFile(recordingFile.absolutePath)
                        prepare()
                        start()
                    }

                    Log.d("AudioCapture", "Recording chunk: ${recordingFile.name}")
                    
                    delay(chunkDurationMs)
                    
                    // Chunk complete — stop recorder, rename to .m4a, then upload
                    stopCurrentRecorder()
                    recordingFile.renameTo(finalFile)
                    if (finalFile.length() == 0L) {
                        Log.w("AudioCapture", "Empty finalized file ${finalFile.name} detected — deleting.")
                        finalFile.delete()
                    } else {
                        Log.d("AudioCapture", "Finalized: ${finalFile.name} (${finalFile.length()} bytes)")
                    }
                    DriveUploadWorker.enqueue(context)
                }
            } catch (e: CancellationException) {
                // Stopped early — save partial chunk
                Log.d("AudioCapture", "Cancelled — saving partial chunk")
                stopCurrentRecorder()
                // Rename any .recording files to .m4a so they get uploaded
                renameRecordingFiles()
            } catch (e: Exception) {
                Log.e("AudioCapture", "Error: ${e.message}")
                stopCurrentRecorder()
                renameRecordingFiles()
            }
        }
    }

    private fun stopCurrentRecorder() {
        try {
            mediaRecorder?.apply {
                stop()
                reset()
                release()
            }
        } catch (e: Exception) {
            Log.e("AudioCapture", "Error stopping recorder: ${e.message}")
            try { mediaRecorder?.release() } catch (_: Exception) {}
        } finally {
            mediaRecorder = null
        }
    }

    /** Rename all .recording files to .m4a so they become uploadable */
    private fun renameRecordingFiles() {
        val audioDir = File(context.filesDir, "audio_chunks")
        audioDir.listFiles { f -> f.name.endsWith(".recording") }?.forEach { file ->
            if (file.length() == 0L) {
                Log.w("AudioCapture", "Empty partial file ${file.name} detected — deleting.")
                file.delete()
            } else {
                val finalName = file.name.removeSuffix(".recording")
                val finalFile = File(file.parent, finalName)
                file.renameTo(finalFile)
                Log.d("AudioCapture", "Renamed partial: ${finalFile.name} (${finalFile.length()} bytes)")
            }
        }
    }

    fun stopCapture() {
        if (!isRecording) return
        isRecording = false
        val jobToJoin = recordingJob
        recordingJob = null
        
        // Wait for the recording coroutine to fully complete (including rename)
        // before enqueueing upload — eliminates the race condition
        scope.launch {
            jobToJoin?.cancelAndJoin()
            DriveUploadWorker.enqueue(context)
        }
        Log.d("AudioCapture", "Stopped ambient audio capture")
    }
}
