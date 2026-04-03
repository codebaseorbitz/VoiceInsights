package com.example.voiceinsights

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File

class CallRecordingScanner(private val context: Context) {

    companion object {
        private const val TAG = "CallRecordingScanner"
        private const val PREF_NAME = "call_recording_scanner_prefs"
        private const val KEY_IMPORTED_FILES = "imported_files"
    }

    // Known call recording directories by OEM (non-existent paths are safely skipped)
    private val scanPaths = listOf(
        // Samsung
        File(Environment.getExternalStorageDirectory(), "Call"),
        File(Environment.getExternalStorageDirectory(), "Recordings/Call"),
        File(Environment.getExternalStorageDirectory(), "Sounds/Call"),
        // OnePlus / OxygenOS
        File(Environment.getExternalStorageDirectory(), "Recordings/Call Recordings"),
        File(Environment.getExternalStorageDirectory(), "Record/Call"),
        File(Environment.getExternalStorageDirectory(), "Music/Recordings/Call Recordings"),
    )

    // Supported audio extensions
    private val audioExtensions = setOf("m4a", "mp3", "amr", "aac", "wav", "3gp", "ogg")

    /**
     * Scan all known directories for new call recordings.
     * Returns the count of newly imported files.
     */
    fun scanAndImport(): Int {
        val importedSet = getImportedFileSet().toMutableSet()
        val audioDir = File(context.filesDir, "audio_chunks").apply { mkdirs() }
        var newCount = 0

        for (scanDir in scanPaths) {
            if (!scanDir.exists() || !scanDir.isDirectory) {
                Log.d(TAG, "Directory not found, skipping: ${scanDir.absolutePath}")
                continue
            }

            Log.d(TAG, "Scanning: ${scanDir.absolutePath}")

            val files = scanDir.listFiles { file ->
                file.isFile && file.extension.lowercase() in audioExtensions
            } ?: continue

            for (file in files) {
                val fileKey = "${file.absolutePath}|${file.length()}|${file.lastModified()}"

                if (fileKey in importedSet) continue

                try {
                    val destFile = File(audioDir, "call_${file.name}")
                    file.copyTo(destFile, overwrite = true)
                    importedSet.add(fileKey)
                    newCount++
                    Log.d(TAG, "Imported: ${file.name} → ${destFile.name}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to import ${file.name}: ${e.message}")
                }
            }
        }

        if (newCount > 0) {
            saveImportedFileSet(importedSet)
            Log.d(TAG, "Imported $newCount new call recording(s)")
        } else {
            Log.d(TAG, "No new call recordings found")
        }

        return newCount
    }

    /**
     * Get a list of directories that exist and have recordings.
     */
    fun getAvailablePaths(): List<String> {
        return scanPaths.filter { it.exists() && it.isDirectory }
            .map { it.absolutePath }
    }

    private fun getImportedFileSet(): Set<String> {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_IMPORTED_FILES, emptySet()) ?: emptySet()
    }

    private fun saveImportedFileSet(set: Set<String>) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_IMPORTED_FILES, set)
            .apply()
    }
}
