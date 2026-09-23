package dev.piotrprus.audiocapture.internal

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Asks for `RECORD_AUDIO` without the app handing over an Activity: a transparent
 * [PermissionActivity] is launched on top, shows the system dialog and reports back.
 */
internal object PermissionRequester {
    private val mutex = Mutex()
    private var shown: CompletableDeferred<Unit>? = null
    private var answer: CompletableDeferred<Boolean>? = null

    fun isGranted(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    suspend fun request(context: Context): Boolean = mutex.withLock {
        if (isGranted(context)) return true
        val appeared = CompletableDeferred<Unit>().also { shown = it }
        val result = CompletableDeferred<Boolean>().also { answer = it }
        context.startActivity(
            Intent(context, PermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
        )
        // Android blocks activity starts from the background; then nothing ever appears.
        if (withTimeoutOrNull(SHOW_TIMEOUT_MS) { appeared.await() } == null) {
            answer = null
            return false
        }
        result.await()
    }

    fun onShown() {
        shown?.complete(Unit)
    }

    fun onAnswer(granted: Boolean) {
        answer?.complete(granted)
        answer = null
    }

    private const val SHOW_TIMEOUT_MS = 3_000L
}

/** Invisible host for the permission dialog; finishes as soon as the user answers. */
internal class PermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PermissionRequester.onShown()
        if (savedInstanceState == null) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        PermissionRequester.onAnswer(grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        // Dismissed without an answer (back press, process recreation): treat as refused.
        if (isFinishing) PermissionRequester.onAnswer(PermissionRequester.isGranted(this))
        super.onDestroy()
    }

    private companion object {
        const val REQUEST_CODE = 0xAC
    }
}
