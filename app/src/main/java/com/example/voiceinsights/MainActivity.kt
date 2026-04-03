package com.example.voiceinsights

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import com.example.voiceinsights.ui.theme.VoiceInsightsTheme

class MainActivity : ComponentActivity() {

    private val TAG = "MainActivity"
    
    val isSignedIn = mutableStateOf(false)
    val signedInEmail = mutableStateOf<String?>(null)

    private lateinit var signInLauncher: ActivityResultLauncher<android.content.Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Register sign-in result handler
        signInLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val account = GoogleDriveAuth.handleSignInResult(result.data)
            if (account != null) {
                isSignedIn.value = true
                signedInEmail.value = account.email
                Log.d(TAG, "Signed in as: ${account.email}")
            } else {
                Log.e(TAG, "Sign-in result: no account (resultCode=${result.resultCode})")
            }
        }

        // Check if already signed in
        isSignedIn.value = GoogleDriveAuth.isSignedIn(this)
        signedInEmail.value = GoogleDriveAuth.getSignedInEmail(this)
        Log.d(TAG, "Initial auth state: signedIn=${isSignedIn.value}, email=${signedInEmail.value}")

        // Schedule periodic call recording scan
        CallRecordingScanWorker.schedule(this)

        setContent {
            VoiceInsightsTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        MainScreen(
                            isSignedIn = isSignedIn.value,
                            signedInEmail = signedInEmail.value,
                            onSignIn = { startGoogleSignIn() }
                        )
                    }
                }
            }
        }
    }

    private fun startGoogleSignIn() {
        Log.d(TAG, "Starting Google Sign-In...")
        val signInIntent = GoogleDriveAuth.getSignInIntent(this)
        signInLauncher.launch(signInIntent)
    }
}