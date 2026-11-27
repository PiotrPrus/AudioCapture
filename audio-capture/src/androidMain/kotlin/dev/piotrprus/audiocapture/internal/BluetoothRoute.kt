package dev.piotrprus.audiocapture.internal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.annotation.RequiresApi
import dev.piotrprus.audiocapture.AudioCaptureException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Switches a Bluetooth headset to its call profile so its microphone can record, and switches it
 * back on [release].
 *
 * Android only records from a Bluetooth microphone while that headset is the communication device.
 * From API 31 the app names the headset with `setCommunicationDevice`, matched by address. Below
 * API 31 `startBluetoothSco` connects whichever headset is active. Either way Android only offers
 * the headset that is the system's active device for calls (seen on a Pixel 10 Pro, Android 16:
 * a connected Pixel Watch was in neither the input list nor `availableCommunicationDevices`).
 */
internal class BluetoothRoute(context: Context, private val audioManager: AudioManager) {

    private val appContext = context.applicationContext
    private var communicationDevice: AudioDeviceInfo? = null
    private var scoStarted = false

    /**
     * Routes to [input], a Bluetooth input device. Blocks until the headset is connected, up to
     * a few seconds below API 31.
     *
     * @throws AudioCaptureException when the system refuses or the headset does not connect.
     */
    fun acquire(input: AudioDeviceInfo) {
        if (Build.VERSION.SDK_INT >= 31) acquireCommunicationDevice(input) else acquireSco()
    }

    /** Puts the previous routing back. Safe to call more than once. */
    fun release() {
        if (Build.VERSION.SDK_INT >= 31) {
            val ours = communicationDevice ?: return
            communicationDevice = null
            // Another app may have chosen its own device since; leave that alone.
            if (audioManager.communicationDevice?.id == ours.id) audioManager.clearCommunicationDevice()
        } else if (scoStarted) {
            scoStarted = false
            @Suppress("DEPRECATION")
            audioManager.stopBluetoothSco()
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = false
        }
    }

    @RequiresApi(31)
    private fun acquireCommunicationDevice(input: AudioDeviceInfo) {
        // The communication devices are the headsets' outputs; the input and output of one headset
        // share its Bluetooth address.
        val device = audioManager.availableCommunicationDevices
            .firstOrNull { it.type == input.type && it.address == input.address }
            ?: throw AudioCaptureException("${input.productName} cannot be used as a microphone")
        if (!audioManager.setCommunicationDevice(device)) {
            throw AudioCaptureException("The system refused to route audio to ${input.productName}")
        }
        communicationDevice = device
    }

    // Below API 31 only: system broadcasts need no receiver export flag there.
    @Suppress("DEPRECATION", "UnspecifiedRegisterReceiverFlag")
    private fun acquireSco() {
        if (!audioManager.isBluetoothScoAvailableOffCall) {
            throw AudioCaptureException("This device cannot record from Bluetooth headsets")
        }
        val connected = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR)
                if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) connected.countDown()
            }
        }
        val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        appContext.registerReceiver(receiver, filter)
        try {
            audioManager.startBluetoothSco()
            scoStarted = true
            audioManager.isBluetoothScoOn = true
            if (!connected.await(SCO_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                release()
                throw AudioCaptureException("The Bluetooth headset did not connect its microphone")
            }
        } finally {
            appContext.unregisterReceiver(receiver)
        }
    }

    private companion object {
        const val SCO_TIMEOUT_MS = 4_000L
    }
}
