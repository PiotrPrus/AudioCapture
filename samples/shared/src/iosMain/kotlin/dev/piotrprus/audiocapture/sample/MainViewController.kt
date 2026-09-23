package dev.piotrprus.audiocapture.sample

import androidx.compose.ui.window.ComposeUIViewController
import platform.Foundation.NSTemporaryDirectory

@Suppress("unused", "FunctionName")
fun MainViewController() = ComposeUIViewController { App(recordingsDir = NSTemporaryDirectory()) }
