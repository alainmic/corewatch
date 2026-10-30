// Copyright 2026 The Corewatch Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.corewatch.data

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Live Wi-Fi radio, PHY link speed, and active network transport telemetry.
 * Collected using install-time `ACCESS_NETWORK_STATE` and `ACCESS_WIFI_STATE` permissions
 * without requiring `INTERNET` or `ACCESS_FINE_LOCATION`.
 */
data class WifiTelemetrySnapshot(
    val isConnected: Boolean = false,
    val transportLabel: String = "Disconnected",
    val interfaceName: String = "wlan0",
    val mtu: Int = 1500,
    val isValidated: Boolean = false,
    val isMetered: Boolean = false,
    val wifiStandardCode: Int = 0,
    val wifiStandardLabel: String = "N/A",
    val frequencyMhz: Int = 0,
    val bandAndChannelLabel: String = "N/A",
    val rssiDbm: Int = -127,
    val signalQualityPercent: Int = 0,
    val signalQualityLabel: String = "No Signal",
    val txLinkSpeedMbps: Int = 0,
    val rxLinkSpeedMbps: Int = 0,
    val linkSpeedMbps: Int = 0,
    val downstreamBandwidthKbps: Int = 0,
    val upstreamBandwidthKbps: Int = 0,
    val securityLabel: String = "N/A",
    val mloLinkCount: Int = 0,
    val mloSummary: String = "Single-Link",
    val txLostPktsPerSec: Double = 0.0,
    val txRetriesPerSec: Double = 0.0,
    val txSuccessPktsPerSec: Double = 0.0,
    val rxSuccessPktsPerSec: Double = 0.0,
) {
    val signalFraction: Float
        get() = (signalQualityPercent / 100f).coerceIn(0f, 1f)

    val phySpeedSummary: String
        get() = when {
            txLinkSpeedMbps > 0 && rxLinkSpeedMbps > 0 -> "TX $txLinkSpeedMbps Mbps · RX $rxLinkSpeedMbps Mbps"
            linkSpeedMbps > 0 -> "$linkSpeedMbps Mbps"
            else -> "N/A"
        }

    val linkHealthBadge: String
        get() = when {
            !isConnected -> "Disconnected"
            isValidated -> "$signalQualityLabel ($signalQualityPercent%)"
            else -> "Unvalidated Link"
        }
}

/**
 * Active Bluetooth / system audio routing and negotiated A2DP / LE Audio codec configuration.
 */
data class BluetoothAudioCodecSnapshot(
    val activeRouteLabel: String = "Built-in Speaker",
    val activeCodecName: String = "None (No Active Bluetooth Audio)",
    val sampleRateLabel: String = "48.0 kHz",
    val bitsPerSampleLabel: String = "24-bit",
    val channelModeLabel: String = "Stereo",
    val bitrateOrQualityLabel: String = "Standard",
    val selectableCodecsSummary: String = "None",
    val communicationModeLabel: String = "Normal (Media)",
    val isBluetoothAudioActive: Boolean = false,
)

/**
 * Connected or paired Bluetooth peripheral (HID Mouse/Keyboard, A2DP/LE Audio Headphones, etc.).
 */
data class BluetoothPeripheralReading(
    val name: String,
    val transportTypeLabel: String,
    val categoryLabel: String,
    val isConnected: Boolean,
    val profilesSummary: String,
    val batteryPercent: Int = -1,
) {
    val batteryLabel: String
        get() = if (batteryPercent in 0..100) "$batteryPercent%" else "N/A"
}

/**
 * Complete Bluetooth adapter, controller feature flags, audio codec, and peripheral snapshot.
 */
data class BluetoothTelemetrySnapshot(
    val isAdapterEnabled: Boolean = false,
    val hasConnectPermission: Boolean = false,
    val connectionStateLabel: String = "Off",
    val connectedCount: Int = 0,
    val bondedCount: Int = 0,
    val le2MPhySupported: Boolean = false,
    val leCodedPhySupported: Boolean = false,
    val leAudioSupported: Boolean = false,
    val leAudioBroadcastSupported: Boolean = false,
    val maxConnectedAudioDevices: Int = 1,
    val audioCodec: BluetoothAudioCodecSnapshot = BluetoothAudioCodecSnapshot(),
    val peripherals: List<BluetoothPeripheralReading> = emptyList(),
) {
    val controllerCapabilitiesSummary: String
        get() {
            val caps = ArrayList<String>(4)
            if (leAudioSupported) caps += "LE Audio (LC3)"
            if (leAudioBroadcastSupported) caps += "Auracast"
            if (le2MPhySupported) caps += "LE 2M PHY"
            if (leCodedPhySupported) caps += "LE Coded PHY"
            return if (caps.isEmpty()) "BR/EDR + BLE" else caps.joinToString(" · ")
        }
}

/**
 * Pure, deterministic parsers and formatters for Wi-Fi 7/6E/5 PHY parameters and Bluetooth codecs.
 */
object WirelessTelemetryParsers {

    fun formatWifiStandard(standardCode: Int): String = when (standardCode) {
        8 -> "Wi-Fi 7 (802.11be)"
        7 -> "WiGig (802.11ad)"
        6 -> "Wi-Fi 6/6E (802.11ax)"
        5 -> "Wi-Fi 5 (802.11ac)"
        4 -> "Wi-Fi 4 (802.11n)"
        1 -> "Legacy (802.11a/b/g)"
        else -> "Wi-Fi"
    }

    fun formatBandAndChannel(freqMhz: Int): String = when {
        freqMhz == 2484 -> "2.4 GHz · Ch 14 (2484 MHz)"
        freqMhz in 2412..2472 -> "2.4 GHz · Ch ${(freqMhz - 2407) / 5} ($freqMhz MHz)"
        freqMhz in 5170..5885 -> "5 GHz · Ch ${(freqMhz - 5000) / 5} ($freqMhz MHz)"
        freqMhz in 5955..7115 -> "6 GHz · Ch ${(freqMhz - 5950) / 5} ($freqMhz MHz)"
        freqMhz > 0 -> "$freqMhz MHz"
        else -> "N/A"
    }

    fun computeRssiQualityPercent(rssiDbm: Int): Int = when {
        rssiDbm <= -100 || rssiDbm >= 0 -> 0
        rssiDbm >= -45 -> 100
        else -> (((rssiDbm + 100) * 100f) / 55f).roundToInt().coerceIn(0, 100)
    }

    fun formatRssiQualityLabel(rssiDbm: Int): String = when {
        rssiDbm >= 0 || rssiDbm <= -100 -> "No Signal"
        rssiDbm >= -55 -> "Excellent"
        rssiDbm >= -67 -> "Good"
        rssiDbm >= -77 -> "Fair"
        else -> "Weak"
    }

    fun formatWifiSecurityType(securityType: Int): String = when (securityType) {
        0 -> "Open"
        1 -> "WEP"
        2 -> "WPA2-PSK"
        3 -> "WPA2/WPA3-Enterprise"
        4 -> "WPA3-SAE"
        5 -> "WPA3-Enterprise 192-bit"
        6 -> "Enhanced Open (OWE)"
        7 -> "WAPI-PSK"
        8 -> "WAPI-CERT"
        9 -> "WPA3-Enterprise"
        else -> "WPA2/WPA3"
    }

    fun formatBluetoothCodecType(codecType: Int): String = when (codecType) {
        0 -> "SBC"
        1 -> "AAC"
        2 -> "aptX"
        3 -> "aptX HD"
        4 -> "LDAC"
        5 -> "LC3 (LE Audio)"
        6 -> "Opus"
        7 -> "aptX Adaptive"
        else -> "Codec #$codecType"
    }

    fun formatBluetoothSampleRate(rateBitmask: Int): String = when {
        (rateBitmask and 32) != 0 -> "192.0 kHz"
        (rateBitmask and 16) != 0 -> "176.4 kHz"
        (rateBitmask and 8) != 0 -> "96.0 kHz"
        (rateBitmask and 4) != 0 -> "88.2 kHz"
        (rateBitmask and 2) != 0 -> "48.0 kHz"
        (rateBitmask and 1) != 0 -> "44.1 kHz"
        else -> "48.0 kHz"
    }

    fun formatBluetoothBitsPerSample(bitsBitmask: Int): String = when {
        (bitsBitmask and 4) != 0 -> "32-bit"
        (bitsBitmask and 2) != 0 -> "24-bit"
        (bitsBitmask and 1) != 0 -> "16-bit"
        else -> "16-bit"
    }

    fun formatBluetoothChannelMode(channelBitmask: Int): String = when {
        (channelBitmask and 2) != 0 -> "Stereo"
        (channelBitmask and 1) != 0 -> "Mono"
        else -> "Stereo"
    }

    fun formatBluetoothBitrateQuality(codecType: Int, codecSpecific1: Long): String = when (codecType) {
        4 -> when (codecSpecific1) {
            1000L -> "990 kbps (High Quality)"
            1001L -> "660 kbps (Standard)"
            1002L -> "330 kbps (Connection Priority)"
            else -> "Adaptive Bitrate (330–990 kbps)"
        }
        3 -> "576 kbps (24-bit HD)"
        2 -> "352 kbps (Low Latency)"
        1 -> "256 kbps VBR (AAC-LC)"
        0 -> "328 kbps (SBC High Quality)"
        5 -> "LE Audio Isochronous (LC3)"
        6 -> "Spatial / Adaptive (Opus)"
        else -> "Standard"
    }

    fun formatBluetoothDeviceType(deviceType: Int): String = when (deviceType) {
        1 -> "BR/EDR"
        2 -> "BLE"
        3 -> "Dual (BR/EDR + LE)"
        else -> "Bluetooth"
    }

    private val POSSESSIVE_PREFIX_REGEX = Regex("^[A-Za-z0-9_\\-]+['\u2019]s\\s+", RegexOption.IGNORE_CASE)
    private val PROTOTYPE_TAG_REGEX = Regex("\\b(EVT|DVT|PVT|PROTO)[0-9.]*\\b", RegexOption.IGNORE_CASE)
    private val AIDL_CODEC_REGEX = Regex(
        "CodecConfiguration\\{codecType:\\s*([A-Za-z0-9_]+),\\s*encodedAudioBitrate:\\s*(\\d+),\\s*peerMtu:\\s*(\\d+).*?sampleRateHz:\\s*(\\d+),\\s*channelMode:\\s*([A-Za-z0-9_]+).*?bitsPerSample:\\s*(\\d+)",
    )
    private val STATE_MACHINE_CODEC_REGEX = Regex(
        "mCodecConfig:\\{codecName:([A-Za-z0-9_\\-]+),mCodecType:(\\d+),mCodecPriority:\\d+,mSampleRate:0x[0-9a-fA-F]+\\((\\d+)\\),mBitsPerSample:0x[0-9a-fA-F]+\\((\\d+)\\),mChannelMode:0x[0-9a-fA-F]+\\(([A-Za-z0-9_|]+)\\),mCodecSpecific1:(-?\\d+)",
    )
    private val SELECTABLE_CAP_REGEX = Regex("mCodecsSelectableCapabilities:\\[(.*?)\\]")
    private val CODEC_NAME_FIELD_REGEX = Regex("codecName:([A-Za-z0-9_\\-]+)")

    /**
     * Parses `bluetooth-a2dp-aidl` and `A2dpService` logcat lines when `BluetoothA2dp.getCodecStatus`
     * is gated by `BLUETOOTH_PRIVILEGED` on Android 15/16.
     */
    fun parseA2dpLogcatCodec(
        logText: String,
        activeRouteLabel: String,
        communicationModeLabel: String,
    ): BluetoothAudioCodecSnapshot? {
        if (logText.isBlank()) return null
        var codecName: String? = null
        var sampleRateLabel = "44.1 kHz"
        var bitsLabel = "16-bit"
        var channelLabel = "Stereo"
        var bitrateLabel = "328 kbps (SBC)"
        var selectableSummary: String? = null

        for (line in logText.lineSequence()) {
            AIDL_CODEC_REGEX.find(line)?.let { m ->
                codecName = m.groupValues[1]
                val bitrateBps = m.groupValues[2].toLongOrNull() ?: 0L
                val mtu = m.groupValues[3].toIntOrNull() ?: 0
                val rateHz = m.groupValues[4].toIntOrNull() ?: 44_100
                val rawCh = m.groupValues[5]
                val bits = m.groupValues[6].toIntOrNull() ?: 16
                sampleRateLabel = String.format(Locale.US, "%.1f kHz", rateHz / 1_000.0)
                bitsLabel = "${bits}-bit"
                channelLabel = when (rawCh.uppercase(Locale.US)) {
                    "JOINT_STEREO" -> "Joint Stereo"
                    "STEREO" -> "Stereo"
                    "MONO" -> "Mono"
                    "DUAL_CHANNEL" -> "Dual Channel"
                    else -> rawCh
                }
                if (bitrateBps > 0L) {
                    val kbps = (bitrateBps / 1_000L).toInt()
                    bitrateLabel = if (mtu > 0) "$kbps kbps (MTU $mtu B)" else "$kbps kbps"
                }
            }
            STATE_MACHINE_CODEC_REGEX.find(line)?.let { m ->
                if (codecName == null) {
                    codecName = m.groupValues[1]
                }
                val cType = m.groupValues[2].toIntOrNull() ?: 0
                val rateHz = m.groupValues[3].toIntOrNull() ?: 44_100
                val bits = m.groupValues[4].toIntOrNull() ?: 16
                val rawCh = m.groupValues[5]
                val spec1 = m.groupValues[6].toLongOrNull() ?: 0L
                sampleRateLabel = String.format(Locale.US, "%.1f kHz", rateHz / 1_000.0)
                bitsLabel = "${bits}-bit"
                if (channelLabel == "Stereo" && rawCh.equals("MONO", ignoreCase = true)) {
                    channelLabel = "Mono"
                }
                if (!bitrateLabel.contains("MTU")) {
                    bitrateLabel = formatBluetoothBitrateQuality(cType, spec1)
                }
            }
            SELECTABLE_CAP_REGEX.find(line)?.let { m ->
                val names = CODEC_NAME_FIELD_REGEX.findAll(m.groupValues[1])
                    .map { it.groupValues[1] }
                    .distinct()
                    .toList()
                if (names.isNotEmpty()) {
                    selectableSummary = names.joinToString(", ")
                }
            }
        }
        val resolvedCodec = codecName ?: return null
        return BluetoothAudioCodecSnapshot(
            activeRouteLabel = activeRouteLabel,
            activeCodecName = resolvedCodec,
            sampleRateLabel = sampleRateLabel,
            bitsPerSampleLabel = bitsLabel,
            channelModeLabel = channelLabel,
            bitrateOrQualityLabel = bitrateLabel,
            selectableCodecsSummary = selectableSummary ?: resolvedCodec,
            communicationModeLabel = communicationModeLabel,
            isBluetoothAudioActive = true,
        )
    }

    /**
     * Strips personal possessive prefixes (e.g. "Alice's Pixel Buds Pro 2" -> "Pixel Buds Pro 2")
     * and prototype tags so screenshots and diagnostic views never leak personal names.
     */
    fun sanitizePeripheralDisplayName(rawName: String): String {
        val cleaned = SysfsParsers.sanitizeSensorName(rawName, "Bluetooth Device")
            .replace(POSSESSIVE_PREFIX_REGEX, "")
            .trim()
        if (PROTOTYPE_TAG_REGEX.containsMatchIn(cleaned)) {
            return "Wireless Headphones (LE Audio)"
        }
        return cleaned.ifBlank { "Bluetooth Device" }
    }

    fun formatPeripheralCategory(majorClass: Int, fullDeviceClass: Int, rawName: String): String {
        val lower = rawName.lowercase(Locale.US)
        return when {
            (fullDeviceClass and 0x1FFC) == 0x0580 || lower.contains("mouse") || lower.contains("mx master") || lower.contains("trackpad") ->
                "Mouse / Pointing"
            (fullDeviceClass and 0x1FFC) == 0x0540 || lower.contains("keyboard") || lower.contains("keychron") ->
                "Keyboard"
            (fullDeviceClass and 0x1FFC) == 0x05C0 ->
                "Combo Keyboard/Mouse"
            (fullDeviceClass and 0x1FFC) in listOf(0x0504, 0x0508) || lower.contains("controller") || lower.contains("gamepad") ->
                "Gamepad"
            majorClass == 0x0400 || lower.contains("buds") || lower.contains("headphone") || lower.contains("airpods") || lower.contains("xm5") || lower.contains("audio") ->
                "Audio / Headphones"
            majorClass == 0x0500 -> "HID Peripheral"
            majorClass == 0x0100 -> "Computer"
            majorClass == 0x0200 -> "Phone"
            majorClass == 0x0700 || lower.contains("watch") -> "Wearable"
            else -> "Peripheral"
        }
    }
}
