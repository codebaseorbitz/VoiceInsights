package com.example.voiceinsights

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log

import android.os.Build

/**
 * Listens for phone call state changes.
 * When a call ends (IDLE after OFFHOOK), triggers a call recording scan
 * with a brief delay to let Samsung finish writing the recording file.
 */
class PhoneCallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PhoneCallReceiver"
        private var wasInCall = false
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        Log.d(TAG, "Phone state: $state")

        when (state) {
            TelephonyManager.EXTRA_STATE_OFFHOOK, TelephonyManager.EXTRA_STATE_RINGING -> {
                if (!wasInCall) {
                    wasInCall = true
                    Log.d(TAG, "Call started/ringing - pausing recording")
                    val pauseIntent = Intent(context, RecordingService::class.java).apply {
                        action = "PAUSE_FOR_CALL"
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(pauseIntent)
                    } else {
                        context.startService(pauseIntent)
                    }
                }
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                if (wasInCall) {
                    wasInCall = false
                    Log.d(TAG, "Call ended - resuming recording and triggering scan")

                    val resumeIntent = Intent(context, RecordingService::class.java).apply {
                        action = "RESUME_AFTER_CALL"
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(resumeIntent)
                    } else {
                        context.startService(resumeIntent)
                    }

                    // Samsung can take 15-30+ seconds to write call recordings to disk.
                    // Retry scan at 15s, 30s, and 60s to reliably catch the file.
                    val handler = android.os.Handler(android.os.Looper.getMainLooper())
                    longArrayOf(15_000, 30_000, 60_000).forEach { delayMs ->
                        handler.postDelayed({
                            CallRecordingScanWorker.scanNow(context)
                        }, delayMs)
                    }
                }
            }
        }
    }
}
