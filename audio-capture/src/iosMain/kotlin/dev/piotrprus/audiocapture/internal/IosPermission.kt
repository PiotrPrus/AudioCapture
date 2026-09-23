package dev.piotrprus.audiocapture.internal

import dev.piotrprus.audiocapture.MicPermission
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cValue
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFAudio.AVAudioApplication
import platform.AVFAudio.AVAudioApplicationRecordPermissionDenied
import platform.AVFAudio.AVAudioApplicationRecordPermissionGranted
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionRecordPermissionDenied
import platform.AVFAudio.AVAudioSessionRecordPermissionGranted
import platform.Foundation.NSOperatingSystemVersion
import platform.Foundation.NSProcessInfo
import kotlin.coroutines.resume

/** Microphone permission through `AVAudioApplication` on iOS 17+, `AVAudioSession` before. */
@OptIn(ExperimentalForeignApi::class)
internal object IosPermission {

    private val modernApi: Boolean = isAtLeast(17)

    fun current(): MicPermission = if (modernApi) {
        when (AVAudioApplication.sharedInstance.recordPermission) {
            AVAudioApplicationRecordPermissionGranted -> MicPermission.Granted
            AVAudioApplicationRecordPermissionDenied -> MicPermission.Denied
            else -> MicPermission.NotDetermined
        }
    } else {
        when (AVAudioSession.sharedInstance().recordPermission) {
            AVAudioSessionRecordPermissionGranted -> MicPermission.Granted
            AVAudioSessionRecordPermissionDenied -> MicPermission.Denied
            else -> MicPermission.NotDetermined
        }
    }

    /** Shows the system prompt if the user was never asked; otherwise returns the current answer. */
    suspend fun request(): MicPermission {
        val now = current()
        if (now != MicPermission.NotDetermined) return now
        return suspendCancellableCoroutine { continuation ->
            val handler: (Boolean) -> Unit = { granted ->
                continuation.resume(if (granted) MicPermission.Granted else MicPermission.Denied)
            }
            if (modernApi) {
                AVAudioApplication.requestRecordPermissionWithCompletionHandler(handler)
            } else {
                AVAudioSession.sharedInstance().requestRecordPermission(handler)
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun isAtLeast(major: Long): Boolean = NSProcessInfo.processInfo.isOperatingSystemAtLeastVersion(
    cValue<NSOperatingSystemVersion> {
        majorVersion = major
        minorVersion = 0
        patchVersion = 0
    },
)
