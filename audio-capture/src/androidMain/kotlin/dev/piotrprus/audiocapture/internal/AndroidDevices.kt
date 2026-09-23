package dev.piotrprus.audiocapture.internal

import android.media.AudioDeviceInfo
import android.os.Build
import dev.piotrprus.audiocapture.InputDevice
import dev.piotrprus.audiocapture.InputDeviceType

/**
 * `AudioDeviceInfo.TYPE_ECHO_REFERENCE`, a system API missing from the public SDK. Pixels list it
 * as an input named after the phone; it carries playback, not the microphone.
 */
private const val TYPE_ECHO_REFERENCE = 28

/** `null` for inputs that are not microphones: the telephony uplink, tuners, loopback, echo reference. */
internal fun AudioDeviceInfo.toInputDevice(): InputDevice? {
    val inputType = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> InputDeviceType.BuiltIn
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> InputDeviceType.WiredHeadset
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> InputDeviceType.Bluetooth
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> InputDeviceType.Usb
        AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL -> InputDeviceType.LineIn
        AudioDeviceInfo.TYPE_TELEPHONY, AudioDeviceInfo.TYPE_REMOTE_SUBMIX,
        AudioDeviceInfo.TYPE_FM_TUNER, AudioDeviceInfo.TYPE_TV_TUNER, TYPE_ECHO_REFERENCE,
        -> return null
        else -> when {
            Build.VERSION.SDK_INT >= 26 && type == AudioDeviceInfo.TYPE_USB_HEADSET -> InputDeviceType.Usb
            Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_BLE_HEADSET -> InputDeviceType.Bluetooth
            else -> InputDeviceType.Other
        }
    }
    // Phones list each built-in microphone separately ("bottom", "back"); the address tells them apart.
    val address = address.orEmpty()
    val name = productName.toString() + if (address.isNotBlank()) " ($address)" else ""
    return InputDevice(id = id.toString(), name = name, type = inputType)
}
