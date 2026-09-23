package dev.piotrprus.audiocapture.sample

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {

    // The library never asks for permission itself; the app does, here at launch.
    private val requestMicrophone = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestMicrophone.launch(Manifest.permission.RECORD_AUDIO)
        setContent { App(recordingsDir = filesDir.absolutePath) }
    }
}
