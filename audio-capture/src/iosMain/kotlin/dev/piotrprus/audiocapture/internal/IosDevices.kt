package dev.piotrprus.audiocapture.internal

import dev.piotrprus.audiocapture.InputDevice
import dev.piotrprus.audiocapture.InputDeviceType
import platform.AVFAudio.AVAudioSessionPortBluetoothHFP
import platform.AVFAudio.AVAudioSessionPortBluetoothLE
import platform.AVFAudio.AVAudioSessionPortBuiltInMic
import platform.AVFAudio.AVAudioSessionPortDescription
import platform.AVFAudio.AVAudioSessionPortHeadsetMic
import platform.AVFAudio.AVAudioSessionPortLineIn
import platform.AVFAudio.AVAudioSessionPortUSBAudio

internal fun AVAudioSessionPortDescription.toInputDevice(): InputDevice = InputDevice(
    id = UID,
    name = portName,
    type = when (portType) {
        AVAudioSessionPortBuiltInMic -> InputDeviceType.BuiltIn
        AVAudioSessionPortHeadsetMic -> InputDeviceType.WiredHeadset
        AVAudioSessionPortBluetoothHFP, AVAudioSessionPortBluetoothLE -> InputDeviceType.Bluetooth
        AVAudioSessionPortUSBAudio -> InputDeviceType.Usb
        AVAudioSessionPortLineIn -> InputDeviceType.LineIn
        else -> InputDeviceType.Other
    },
)
