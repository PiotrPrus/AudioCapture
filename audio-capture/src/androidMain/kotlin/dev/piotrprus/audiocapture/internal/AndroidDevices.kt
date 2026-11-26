package dev.piotrprus.audiocapture.internal

import android.media.AudioDeviceInfo
import android.media.AudioManager
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
    val address = address.orEmpty()
    val product = productName?.toString().orEmpty()
    // Phones list each built-in microphone separately ("bottom", "back"); the address tells them apart.
    val name = if (inputType == InputDeviceType.BuiltIn && address.isNotBlank()) "$product ($address)" else product
    // The id changes on every reconnect. The address does not: for Bluetooth it is the device's
    // Bluetooth address, which is what tells two connected headsets apart.
    val stableKey = "$type:" + address.ifBlank { product }
    return InputDevice(id = id.toString(), name = name, type = inputType, stableKey = stableKey)
}

internal val AudioDeviceInfo.isBluetoothInput: Boolean
    get() = type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
        (Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_BLE_HEADSET)

/** The connected input for [wanted]: the same connection if it is still there, else the same physical device. */
internal fun AudioManager.findInput(wanted: InputDevice): AudioDeviceInfo? {
    val inputs = getDevices(AudioManager.GET_DEVICES_INPUTS)
    return inputs.firstOrNull { it.id.toString() == wanted.id }
        ?: inputs.firstOrNull { it.toInputDevice()?.stableKey == wanted.stableKey }
}
