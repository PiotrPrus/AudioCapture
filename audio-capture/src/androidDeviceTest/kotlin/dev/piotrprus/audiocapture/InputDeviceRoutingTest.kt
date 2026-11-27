package dev.piotrprus.audiocapture

import android.Manifest
import android.media.AudioManager
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records a few seconds from every microphone the phone lists, including the active Bluetooth
 * headset, and checks the system really routed to it. Results go to logcat under [TAG].
 *
 * Connect the headsets to test before running. Speak while it runs to see non-zero levels.
 */
@RunWith(AndroidJUnit4::class)
class InputDeviceRoutingTest {

    @get:Rule
    val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val capture = AudioCapture(context)
    private val audioManager = context.getSystemService(AudioManager::class.java)

    @Test
    fun recordsFromEveryListedDevice() = ActivityScenario.launch(ForegroundActivity::class.java).use {
        runBlocking {
            val devices = capture.inputDevices()
            devices.forEach { Log.i(TAG, "listed: $it") }
            assertTrue(devices.isNotEmpty(), "No input devices listed")

            val failures = mutableListOf<String>()
            for (device in devices) {
                val result = runCatching { recordFrom(device) }
                result.onSuccess { (routed, peakDbfs, communication) ->
                    Log.i(TAG, "${device.name} [${device.type}]: routed=${routed?.name} [${routed?.type}] peak=${"%.1f".format(peakDbfs)} dBFS communicationDevice=$communication")
                    if (routed?.stableKey != device.stableKey) {
                        failures += "${device.name}: recorded from ${routed?.name} (${routed?.stableKey}) instead of ${device.stableKey}"
                    }
                }.onFailure { error ->
                    Log.e(TAG, "${device.name} [${device.type}]: failed", error)
                    failures += "${device.name}: ${error.message}"
                }
                if (android.os.Build.VERSION.SDK_INT >= 31 && device.type == InputDeviceType.Bluetooth) {
                    val left = audioManager.communicationDevice
                    Log.i(TAG, "after stop, communicationDevice=${left?.productName} type=${left?.type}")
                    if (left?.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                        failures += "${device.name}: communication device not cleared after stop"
                    }
                }
            }
            if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
        }
    }

    /** Diagnostic: everything the system reports, to compare with [AudioCapture.inputDevices]. */
    @Test
    fun logsSystemAudioDevices() {
        audioManager.getDevices(AudioManager.GET_DEVICES_ALL).forEach {
            Log.i(TAG, "system device: ${it.productName} type=${it.type} source=${it.isSource} sink=${it.isSink} address=${it.address} id=${it.id}")
        }
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            audioManager.availableCommunicationDevices.forEach {
                Log.i(TAG, "communication device: ${it.productName} type=${it.type} address=${it.address} id=${it.id}")
            }
        }
    }

    @Test
    fun deviceFlowEmitsCurrentList() = runBlocking {
        val first = withTimeoutOrNull(2.seconds) { capture.inputDevicesFlow().first() }
        assertEquals(capture.inputDevices(), first)
    }

    @Test
    fun missingDeviceFailsToStart() = runBlocking {
        val ghost = InputDevice(id = "-1", name = "Ghost", type = InputDeviceType.Bluetooth, stableKey = "7:00:00:00:00:00:00")
        val error = runCatching { capture.start(CaptureConfig(device = ghost)) }.exceptionOrNull()
        assertTrue(error is AudioCaptureException, "Expected AudioCaptureException, got $error")
        if (android.os.Build.VERSION.SDK_INT >= 31) assertNull(audioManager.communicationDevice?.takeIf { it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO })
    }

    private suspend fun recordFrom(device: InputDevice): Triple<InputDevice?, Float, String?> {
        val session = capture.start(CaptureConfig(device = device))
        try {
            var peak = 0f
            withTimeoutOrNull(RECORD_SECONDS.seconds) {
                session.chunks.collect { peak = maxOf(peak, it.level().peak) }
            }
            val communication = if (android.os.Build.VERSION.SDK_INT >= 31) {
                audioManager.communicationDevice?.let { "${it.productName} type=${it.type}" }
            } else {
                null
            }
            return Triple(session.inputDevice, AudioLevel(peak, 0f).peakDbfs, communication)
        } finally {
            session.cancel()
        }
    }

    private companion object {
        const val TAG = "AudioCaptureTest"
        const val RECORD_SECONDS = 3
    }
}
