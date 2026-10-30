// Copyright 2026 The Corewatch Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.corewatch.data

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Live Wi-Fi radio, PHY link speed, MAC frame counter, and per-interface (`wlan*`) throughput telemetry.
 * Collected using install-time `ACCESS_NETWORK_STATE` and `ACCESS_WIFI_STATE` permissions
 * without requiring `INTERNET` or `ACCESS_FINE_LOCATION`.
 */
data class WifiTelemetrySnapshot(
    val isConnected: Boolean = false,
    val transportLabel: String = "Disconnected",
    val interfaceName: String = "wlan0",
    val isPrimaryDefaultRoute: Boolean = false,
    val routeRoleLabel: String = "Disconnected",
    val mtu: Int = 1500,
    val isValidated: Boolean = false,
    val isMetered: Boolean = false,
    val ipStackLabel: String = "N/A",
    val dnsSummaryLabel: String = "N/A",
    val wifiStandardCode: Int = 0,
    val wifiStandardLabel: String = "Disconnected",
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
    val throughput: NetworkThroughput = NetworkThroughput(),
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
 * Physical Wired Ethernet (`eth*` / `en*` / USB-C RJ45 dock) link negotiation, routing role,
 * IP/DNS stack, Wake-on-LAN capability, and per-interface `TrafficStats` throughput telemetry.
 * Virtual VM/container bridges (such as `avf_tap*`, `veth*`, `docker*`, and `tun*`) are excluded.
 */
data class EthernetTelemetrySnapshot(
    val isConnected: Boolean = false,
    val interfaceName: String = "None",
    val isPrimaryDefaultRoute: Boolean = false,
    val isValidated: Boolean = false,
    val isMetered: Boolean = false,
    val mtu: Int = 1500,
    val negotiatedSpeedMbps: Int = 0,
    val downstreamBandwidthKbps: Int = 0,
    val upstreamBandwidthKbps: Int = 0,
    val linkSpeedTierLabel: String = "Undocked",
    val ipStackLabel: String = "N/A",
    val dnsSummaryLabel: String = "N/A",
    val wakeOnLanSupported: Boolean = false,
    val isEmulatorVirtualNic: Boolean = false,
    val throughput: NetworkThroughput = NetworkThroughput(),
) {
    val routeRoleLabel: String
        get() = when {
            !isConnected -> "Undocked"
            isPrimaryDefaultRoute -> "Primary Default Route"
            else -> "Secondary Link"
        }

    val linkBarFraction: Float
        get() = when {
            !isConnected -> 0f
            negotiatedSpeedMbps >= 5_000 -> 1.0f
            negotiatedSpeedMbps >= 2_500 -> 0.86f
            negotiatedSpeedMbps >= 1_000 -> 0.68f
            negotiatedSpeedMbps > 0 -> 0.28f
            else -> 0.60f
        }

    val negotiatedSpeedSummary: String
        get() = when {
            !isConnected -> "No Cable / Dock"
            negotiatedSpeedMbps >= 1_000 && negotiatedSpeedMbps % 1_000 == 0 ->
                "${negotiatedSpeedMbps / 1_000} Gbps (${String.format(Locale.US, "%,d", negotiatedSpeedMbps)} Mbps)"
            negotiatedSpeedMbps >= 1_000 ->
                String.format(Locale.US, "%.1f Gbps (%,d Mbps)", negotiatedSpeedMbps / 1_000.0, negotiatedSpeedMbps)
            negotiatedSpeedMbps > 0 -> "$negotiatedSpeedMbps Mbps"
            else -> "Link Up"
        }

    val dockBottleneckWarning: String?
        get() = if (isConnected && !isEmulatorVirtualNic && negotiatedSpeedMbps in 1..100) {
            "100 Mbps link ceiling (${negotiatedSpeedMbps} Mbps) — check USB 2.0 hub or cable if expecting 1 GbE / 2.5 GbE"
        } else {
            null
        }
}

/**
 * Stateful, hotplug-safe rate calculator for total or per-interface (`wlan0`, `eth1`)
 * byte and packet counters read from `TrafficStats`.
 */
class InterfaceRateTracker {
    private var lastIfaceKey: String = ""
    private var lastSampleElapsedMs: Long = 0L
    private var lastRxBytes: Long = -1L
    private var lastTxBytes: Long = -1L
    private var lastRxPackets: Long = -1L
    private var lastTxPackets: Long = -1L

    fun reset() {
        lastIfaceKey = ""
        lastSampleElapsedMs = 0L
        lastRxBytes = -1L
        lastTxBytes = -1L
        lastRxPackets = -1L
        lastTxPackets = -1L
    }

    fun sample(
        ifaceKey: String,
        rawRxBytes: Long,
        rawTxBytes: Long,
        rawRxPackets: Long,
        rawTxPackets: Long,
        nowElapsedMs: Long,
    ): NetworkThroughput {
        if (ifaceKey.isBlank() || rawRxBytes < 0L || rawTxBytes < 0L) {
            reset()
            return NetworkThroughput()
        }
        val cleanRxPkts = rawRxPackets.coerceAtLeast(0L)
        val cleanTxPkts = rawTxPackets.coerceAtLeast(0L)
        val dtMs = nowElapsedMs - lastSampleElapsedMs
        val sameIface = ifaceKey == lastIfaceKey
        val monotonicBytes = lastRxBytes >= 0L && lastTxBytes >= 0L &&
            rawRxBytes >= lastRxBytes && rawTxBytes >= lastTxBytes

        var rxMbps = 0f
        var txMbps = 0f
        var rxPps = 0f
        var txPps = 0f
        if (sameIface && monotonicBytes && dtMs in 100L..30_000L) {
            val dRx = rawRxBytes - lastRxBytes
            val dTx = rawTxBytes - lastTxBytes
            rxMbps = ((dRx * 8.0) / (dtMs * 1_000.0)).toFloat().coerceIn(0f, 40_000f)
            txMbps = ((dTx * 8.0) / (dtMs * 1_000.0)).toFloat().coerceIn(0f, 40_000f)
            if (lastRxPackets >= 0L && lastTxPackets >= 0L &&
                cleanRxPkts >= lastRxPackets && cleanTxPkts >= lastTxPackets
            ) {
                val dRxPkt = cleanRxPkts - lastRxPackets
                val dTxPkt = cleanTxPkts - lastTxPackets
                rxPps = ((dRxPkt * 1_000.0) / dtMs.toDouble()).toFloat().coerceIn(0f, 5_000_000f)
                txPps = ((dTxPkt * 1_000.0) / dtMs.toDouble()).toFloat().coerceIn(0f, 5_000_000f)
            }
        }
        lastIfaceKey = ifaceKey
        lastSampleElapsedMs = nowElapsedMs
        lastRxBytes = rawRxBytes
        lastTxBytes = rawTxBytes
        lastRxPackets = cleanRxPkts
        lastTxPackets = cleanTxPkts
        return NetworkThroughput(
            rxMbps = rxMbps,
            txMbps = txMbps,
            totalRxBytes = rawRxBytes,
            totalTxBytes = rawTxBytes,
            rxPacketsPerSec = rxPps,
            txPacketsPerSec = txPps,
            totalRxPackets = cleanRxPkts,
            totalTxPackets = cleanTxPkts,
        )
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
 * Per-profile Bluetooth audio frame/packet drop, TxQueue dropout, and buffer underflow counters
 * across A2DP (Media), HFP / SCO (Hands-Free Voice), and LE Audio (CIS / BIS Isochronous).
 */
data class BluetoothAudioDropStats(
    val sourceLabel: String = "STANDARD API (GRANT DUMP/LOGS FOR STACK COUNTERS)",
    val hasStackCounters: Boolean = false,
    // A2DP (Advanced Audio Distribution Profile)
    val a2dpExpectedFrames: Long = 0L,
    val a2dpDroppedFrames: Long = 0L,
    val a2dpExpectedPackets: Long = 0L,
    val a2dpDroppedPackets: Long = 0L,
    val a2dpTxEnqueued: Long = 0L,
    val a2dpTxFlushed: Long = 0L,
    val a2dpTxDropped: Long = 0L,
    val a2dpTxDropouts: Long = 0L,
    val a2dpMaxDropped: Long = 0L,
    val a2dpUnderflows: Long = 0L,
    val a2dpUnderflowBytes: Long = 0L,
    val a2dpSinkDelayMs: Float = 0f,
    // HFP / SCO (Hands-Free Profile Synchronous Connection-Oriented)
    val hfpStateLabel: String = "Standby",
    val hfpSpeechCodecLabel: String = "mSBC / CVSD",
    val hfpScoActive: Boolean = false,
    val hfpDroppedPackets: Long = 0L,
    val hfpTriggerDrops: Long = 0L,
    val hfpUnderflows: Long = 0L,
    // LE Audio (Low Energy Isochronous Channels - CIS / BIS)
    val leAudioStreamState: String = "Standby",
    val leAudioUnderflows: Long = 0L,
    val leAudioUnderflowBytes: Long = 0L,
    val leIsoCreditsUnderflows: Long = 0L,
    val leIsoDroppedPackets: Long = 0L,
    val leIsoAvailableCredits: Int = 0,
    val leIsoBufferSizeBytes: Int = 0,
) {
    /** Total A2DP encoder dropped frames + TxQueue dropped packets + encoder dropped packets. */
    val a2dpTotalDrops: Long
        get() = a2dpDroppedFrames + a2dpTxDropped + a2dpDroppedPackets

    val a2dpDropRatePercent: Double
        get() {
            val denom = if (a2dpExpectedFrames > 0L) a2dpExpectedFrames else a2dpTxEnqueued
            return if (denom <= 0L) 0.0 else (a2dpTotalDrops * 100.0) / denom.toDouble()
        }

    val a2dpPrimaryCounterLabel: String
        get() = if (a2dpExpectedFrames > 0L || a2dpTxEnqueued > 0L || hasStackCounters) {
            String.format(
                Locale.US,
                "%,d enc drops · %,d TxQ drops (%.3f%%)",
                a2dpDroppedFrames,
                a2dpTxDropped,
                a2dpDropRatePercent,
            )
        } else {
            "0 dropped frames (Idle)"
        }

    val a2dpDetailSummary: String
        get() {
            val delayPart = if (a2dpSinkDelayMs > 0f) {
                String.format(Locale.US, " · Sink Delay %.0f ms", a2dpSinkDelayMs)
            } else {
                ""
            }
            return String.format(
                Locale.US,
                "Frames %,d · Pkts %,d · Dropouts %,d · Underflows %,d%s",
                a2dpExpectedFrames,
                a2dpTxEnqueued,
                a2dpTxDropouts,
                a2dpUnderflows,
                delayPart,
            )
        }

    val hfpPrimaryCounterLabel: String
        get() = String.format(
            Locale.US,
            "%,d pkt drops · %,d underflows",
            hfpDroppedPackets + hfpTriggerDrops,
            hfpUnderflows,
        )

    val hfpDetailSummary: String
        get() = "State: $hfpStateLabel · Codec: $hfpSpeechCodecLabel"

    val leAudioPrimaryCounterLabel: String
        get() = String.format(
            Locale.US,
            "%,d underflows · %,d ISO drops",
            leAudioUnderflows + leIsoCreditsUnderflows,
            leIsoDroppedPackets,
        )

    val leAudioDetailSummary: String
        get() = if (leIsoAvailableCredits > 0 && leIsoBufferSizeBytes > 0) {
            "State: $leAudioStreamState · ISO Credits: $leIsoAvailableCredits × ${leIsoBufferSizeBytes} B"
        } else {
            "State: $leAudioStreamState · Underflow Bytes: $leAudioUnderflowBytes B"
        }
}

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
    val audioDrops: BluetoothAudioDropStats = BluetoothAudioDropStats(),
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
 * Pure, deterministic parsers and formatters for Wi-Fi 7/6E/5 PHY parameters, Wired Ethernet
 * link speed tiers, IP/DNS summaries, and Bluetooth codecs.
 */
object WirelessTelemetryParsers {

    private val VIRTUAL_IFACE_PREFIXES = listOf(
        "avf_",
        "veth",
        "docker",
        "virbr",
        "br-",
        "tap",
        "tun",
        "wg",
        "ipsec",
        "clat",
        "rmnet",
        "ccmni",
        "dummy",
        "sit",
        "p2p",
        "aware",
        "ap",
        "wlan",
        "swlan",
    )

    /**
     * Returns true if [ifaceName] is a virtual bridge, Linux VM tap (`avf_tap*`), VPN tunnel,
     * cellular, or Wi-Fi interface that must not be classified as physical Wired Ethernet.
     */
    fun isVirtualBridgeInterface(ifaceName: String): Boolean {
        val clean = ifaceName.trim().lowercase(Locale.US)
        if (clean.isEmpty() || clean == "lo" || clean == "none") return true
        return VIRTUAL_IFACE_PREFIXES.any { clean.startsWith(it) }
    }

    /**
     * Maps `NetworkCapabilities` link bandwidth (`Kbps`) reported by Android's `EthernetTracker`
     * into negotiated `Mbps` and an IEEE 802.3 speed tier badge.
     */
    fun formatEthernetSpeedTier(
        downKbps: Int,
        upKbps: Int,
        isConnected: Boolean = true,
    ): Pair<Int, String> {
        if (!isConnected) return 0 to "Undocked"
        val maxKbps = maxOf(downKbps, upKbps).coerceAtLeast(0)
        val mbps = maxKbps / 1_000
        val label = when {
            mbps >= 10_000 -> "10 GbE (10GBASE-T)"
            mbps >= 5_000 -> "5 GbE (5GBASE-T)"
            mbps >= 2_500 -> "2.5 GbE (2500BASE-T)"
            mbps >= 1_000 -> "1 GbE (1000BASE-T)"
            mbps >= 100 -> if (mbps == 100) "100 Mbps (100BASE-TX)" else "$mbps Mbps"
            mbps >= 10 -> if (mbps == 10) "10 Mbps (10BASE-T)" else "$mbps Mbps"
            mbps > 0 -> "$mbps Mbps"
            else -> "Connected"
        }
        return mbps to label
    }

    fun formatIpStackSummary(hasIpv4: Boolean, hasIpv6: Boolean): String = when {
        hasIpv4 && hasIpv6 -> "Dual-Stack (IPv4 + IPv6)"
        hasIpv4 -> "IPv4"
        hasIpv6 -> "IPv6"
        else -> "N/A"
    }

    fun formatDnsSummary(
        isPrivateDnsActive: Boolean,
        dnsServerCount: Int,
        isConnected: Boolean = true,
    ): String {
        if (!isConnected) return "N/A"
        val mode = if (isPrivateDnsActive) "Private DNS (DoT)" else "Standard DNS"
        return when {
            dnsServerCount <= 0 -> mode
            dnsServerCount == 1 -> "$mode · 1 Server"
            else -> "$mode · $dnsServerCount Servers"
        }
    }

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
        "mCodecConfig:\\s*\\{codecName:([A-Za-z0-9_\\-]+),mCodecType:(\\d+),mCodecPriority:\\d+,mSampleRate:0x[0-9a-fA-F]+\\((\\d+)\\),mBitsPerSample:0x[0-9a-fA-F]+\\((\\d+)\\),mChannelMode:0x[0-9a-fA-F]+\\(([A-Za-z0-9_|]+)\\),mCodecSpecific1:(-?\\d+)",
    )
    private val SELECTABLE_CAP_REGEX = Regex("mCodecsSelectableCapabilities:\\s*\\[(.*?)\\]", RegexOption.DOT_MATCHES_ALL)
    private val CODEC_NAME_FIELD_REGEX = Regex("codecName:([A-Za-z0-9_\\-]+)")
    private val EFFECTIVE_MTU_REGEX = Regex("Effective MTU:\\s*(\\d+)")
    private val SBC_BITPOOL_REGEX = Regex("SBC Bitpool \\(min/max\\)\\s*:\\s*(\\d+)\\s*/\\s*(\\d+)")

    // Dumpsys bluetooth_manager A2DP / HFP / LE Audio counter regexes
    private val ENQUEUE_DEQUEUE_REGEX = Regex("Counts \\(enqueue/dequeue/readbuf\\)\\s*:\\s*(\\d+)\\s*/\\s*(\\d+)\\s*/\\s*(\\d+)")
    private val FLUSH_DROP_DROPOUT_REGEX = Regex("Counts \\(flushed/dropped/dropouts\\)\\s*:\\s*(\\d+)\\s*/\\s*(\\d+)\\s*/\\s*(\\d+)")
    private val MAX_DROPPED_REGEX = Regex("Counts \\(max dropped\\)\\s*:\\s*(\\d+)")
    private val UNDERFLOW_COUNTS_REGEX = Regex("Counts \\(underflow\\)\\s*:\\s*(\\d+)")
    private val UNDERFLOW_BYTES_REGEX = Regex("Bytes \\(underflow\\)\\s*:\\s*(\\d+)")
    private val CREDITS_UNDERFLOW_REGEX = Regex("Credits underflow \\(count\\)\\s*:\\s*(\\d+)")
    private val PKT_EXPECTED_DROPPED_REGEX = Regex("Packet counts \\(expected/dropped\\)\\s*:\\s*(\\d+)\\s*/\\s*(\\d+)")
    private val FRAMES_EXPECTED_DROPPED_REGEX = Regex("Frames counts \\(expected/dropped\\)\\s*:\\s*(\\d+)\\s*/\\s*(\\d+)")
    private val DELAY_REPORTING_REGEX = Regex("Delay Reporting:\\s*(\\d+)\\s*\\(in 1/10 milliseconds\\)")
    private val AUDIO_DELAY_EVENT_REGEX = Regex("AUDIO_DELAY_REPORTED:\\s*arg1=(\\d+)")
    private val HFP_STATE_REGEX = Regex("mCurrentState:\\s*([A-Za-z0-9_]+)")
    private val HFP_SCO_ON_REGEX = Regex("AudioManager\\.isBluetoothScoOn\\(\\):\\s*(true|false)")
    private val HFP_WBS_EVENT_REGEX = Regex("EVENT_TYPE_WBS\\[\\d+\\],\\s*valInt=(\\d+)")
    private val HFP_SWB_EVENT_REGEX = Regex("EVENT_TYPE_SWB\\[\\d+\\],\\s*valInt=(\\d+)")
    private val HFP_TRIGGER_DROP_REGEX = Regex("Trigger dropped counts\\s*:\\s*(\\d+)")
    private val HFP_PKT_DROP_REGEX = Regex("Packet dropped counts\\s*:\\s*(\\d+)")
    private val LE_STREAM_STATE_REGEX = Regex("Stream state:\\s*([A-Za-z0-9_]+)")
    private val ISO_CREDITS_REGEX = Regex("Available credits:\\s*(\\d+)")
    private val ISO_BUFFER_SIZE_REGEX = Regex("Controller buffer size:\\s*(\\d+)")

    // Logcat fallback counter regexes
    private val LOG_A2DP_ENCODED_DROP_REGEX = Regex("num_dropped_encoded_frames=(\\d+)")
    private val LOG_SCO_UNRECEIVED_REGEX = Regex("rx_unreceived_packets[:=]\\s*(\\d+)")
    private val LOG_ISO_FLUSHED_REGEX = Regex("tx_flushed_packets[:=]\\s*(\\d+)")

    /**
     * Parses `bluetooth-a2dp-aidl`, `A2dpService`, and `dumpsys bluetooth_manager` lines when
     * `BluetoothA2dp.getCodecStatus` is gated by `BLUETOOTH_PRIVILEGED` on Android 15/16.
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
        var effectiveMtu = 0
        var sbcBitpoolMax = 0

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
            EFFECTIVE_MTU_REGEX.find(line)?.let { m ->
                effectiveMtu = m.groupValues[1].toIntOrNull() ?: effectiveMtu
            }
            SBC_BITPOOL_REGEX.find(line)?.let { m ->
                sbcBitpoolMax = m.groupValues[2].toIntOrNull() ?: sbcBitpoolMax
            }
        }
        if (selectableSummary == null) {
            SELECTABLE_CAP_REGEX.find(logText)?.let { m ->
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
        if (!bitrateLabel.contains("MTU") && effectiveMtu > 0) {
            bitrateLabel = if (resolvedCodec == "SBC" && sbcBitpoolMax > 0) {
                // SBC 44.1 kHz Joint Stereo bitpool 37 = 243 kbps; bitpool 53 = 328 kbps
                val kbps = if (sbcBitpoolMax <= 37) 243 else 328
                "$kbps kbps (Bitpool $sbcBitpoolMax · MTU $effectiveMtu B)"
            } else {
                "$bitrateLabel (MTU $effectiveMtu B)"
            }
        }
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
     * Parses A2DP, HFP/SCO, and LE Audio frame/packet drop and underflow counters from
     * `dumpsys bluetooth_manager` (when `DUMP` permission is granted) and/or `logcat`
     * (when `READ_LOGS` permission is granted).
     */
    fun parseBluetoothAudioDropStats(
        dumpsysText: String,
        logcatText: String,
        isScoOn: Boolean = false,
        isLeAudioActive: Boolean = false,
    ): BluetoothAudioDropStats {
        var a2dpExpectedFrames = 0L
        var a2dpDroppedFrames = 0L
        var a2dpExpectedPackets = 0L
        var a2dpDroppedPackets = 0L
        var a2dpTxEnqueued = 0L
        var a2dpTxFlushed = 0L
        var a2dpTxDropped = 0L
        var a2dpTxDropouts = 0L
        var a2dpMaxDropped = 0L
        var a2dpUnderflows = 0L
        var a2dpUnderflowBytes = 0L
        var a2dpSinkDelayMs = 0f

        var hfpState = if (isScoOn) "SCO Active" else "Standby"
        var hfpScoActive = isScoOn
        var hfpWbs = false
        var hfpSwb = false
        var hfpDroppedPkts = 0L
        var hfpTriggerDrops = 0L
        var hfpUnderflows = 0L

        var leStreamState = if (isLeAudioActive) "Streaming" else "Standby"
        var leUnderflows = 0L
        var leUnderflowBytes = 0L
        var leCreditsUnderflows = 0L
        var leIsoDrops = 0L
        var leCredits = 0
        var leBufferBytes = 0

        var hasDumpsys = false
        if (dumpsysText.isNotBlank() && !dumpsysText.contains("Permission Denial", ignoreCase = true)) {
            var section = "NONE"
            for (rawLine in dumpsysText.lineSequence()) {
                val line = rawLine.trim()
                when {
                    line.startsWith("Profile: HeadsetService") ||
                        line.startsWith("Bluetooth Handsfree Service") ||
                        line.startsWith("Bluetooth HF Client BTA Statistics") -> {
                        section = "HFP"
                        hasDumpsys = true
                    }
                    line.startsWith("Profile: LeAudioService") ||
                        line.startsWith("LeAudio Manager:") ||
                        line.startsWith("LE AudioHalClient:") ||
                        line.startsWith("ISO Manager:") -> {
                        section = "LE_AUDIO"
                        hasDumpsys = true
                    }
                    line.startsWith("Profile: A2dpService") ||
                        line.startsWith("A2DP State:") ||
                        line.startsWith("A2dpStateMachine:") -> {
                        section = "A2DP"
                        hasDumpsys = true
                    }
                    line.startsWith("Profile: ") -> {
                        section = "OTHER"
                    }
                }

                when (section) {
                    "A2DP" -> {
                        ENQUEUE_DEQUEUE_REGEX.find(line)?.let { m ->
                            a2dpTxEnqueued = m.groupValues[1].toLongOrNull() ?: a2dpTxEnqueued
                        }
                        FLUSH_DROP_DROPOUT_REGEX.find(line)?.let { m ->
                            a2dpTxFlushed = m.groupValues[1].toLongOrNull() ?: a2dpTxFlushed
                            a2dpTxDropped = m.groupValues[2].toLongOrNull() ?: a2dpTxDropped
                            a2dpTxDropouts = m.groupValues[3].toLongOrNull() ?: a2dpTxDropouts
                        }
                        MAX_DROPPED_REGEX.find(line)?.let { m ->
                            a2dpMaxDropped = m.groupValues[1].toLongOrNull() ?: a2dpMaxDropped
                        }
                        UNDERFLOW_COUNTS_REGEX.find(line)?.let { m ->
                            a2dpUnderflows = m.groupValues[1].toLongOrNull() ?: a2dpUnderflows
                        }
                        UNDERFLOW_BYTES_REGEX.find(line)?.let { m ->
                            a2dpUnderflowBytes = m.groupValues[1].toLongOrNull() ?: a2dpUnderflowBytes
                        }
                        PKT_EXPECTED_DROPPED_REGEX.find(line)?.let { m ->
                            a2dpExpectedPackets = m.groupValues[1].toLongOrNull() ?: a2dpExpectedPackets
                            a2dpDroppedPackets = m.groupValues[2].toLongOrNull() ?: a2dpDroppedPackets
                        }
                        FRAMES_EXPECTED_DROPPED_REGEX.find(line)?.let { m ->
                            a2dpExpectedFrames = m.groupValues[1].toLongOrNull() ?: a2dpExpectedFrames
                            a2dpDroppedFrames = m.groupValues[2].toLongOrNull() ?: a2dpDroppedFrames
                        }
                        DELAY_REPORTING_REGEX.find(line)?.let { m ->
                            val tenthsMs = m.groupValues[1].toFloatOrNull() ?: 0f
                            if (tenthsMs > 0f) a2dpSinkDelayMs = tenthsMs / 10f
                        }
                        AUDIO_DELAY_EVENT_REGEX.find(line)?.let { m ->
                            val tenthsMs = m.groupValues[1].toFloatOrNull() ?: 0f
                            if (tenthsMs > 0f && a2dpSinkDelayMs == 0f) a2dpSinkDelayMs = tenthsMs / 10f
                        }
                    }
                    "HFP" -> {
                        HFP_STATE_REGEX.find(line)?.let { m ->
                            val st = m.groupValues[1]
                            if (st.equals("AudioOn", ignoreCase = true)) {
                                hfpState = "SCO Audio Active"
                                hfpScoActive = true
                            } else if (st.equals("Connected", ignoreCase = true) && !hfpScoActive) {
                                hfpState = "Connected (SCO Standby)"
                            }
                        }
                        HFP_SCO_ON_REGEX.find(line)?.let { m ->
                            if (m.groupValues[1].equals("true", ignoreCase = true)) {
                                hfpScoActive = true
                                hfpState = "SCO Audio Active"
                            }
                        }
                        HFP_WBS_EVENT_REGEX.find(line)?.let { m ->
                            if ((m.groupValues[1].toIntOrNull() ?: 0) > 0) hfpWbs = true
                        }
                        HFP_SWB_EVENT_REGEX.find(line)?.let { m ->
                            if ((m.groupValues[1].toIntOrNull() ?: 0) > 0) hfpSwb = true
                        }
                        HFP_TRIGGER_DROP_REGEX.find(line)?.let { m ->
                            hfpTriggerDrops = m.groupValues[1].toLongOrNull() ?: hfpTriggerDrops
                        }
                        HFP_PKT_DROP_REGEX.find(line)?.let { m ->
                            hfpDroppedPkts = m.groupValues[1].toLongOrNull() ?: hfpDroppedPkts
                        }
                        UNDERFLOW_COUNTS_REGEX.find(line)?.let { m ->
                            hfpUnderflows = m.groupValues[1].toLongOrNull() ?: hfpUnderflows
                        }
                    }
                    "LE_AUDIO" -> {
                        LE_STREAM_STATE_REGEX.find(line)?.let { m ->
                            val rawSt = m.groupValues[1]
                            leStreamState = when (rawSt.uppercase(Locale.US)) {
                                "DISABLED" -> if (isLeAudioActive) "Streaming" else "Standby"
                                "STREAMING", "ENABLED", "STARTING" -> "Streaming (CIS/BIS)"
                                else -> rawSt
                            }
                        }
                        UNDERFLOW_COUNTS_REGEX.find(line)?.let { m ->
                            leUnderflows += m.groupValues[1].toLongOrNull() ?: 0L
                        }
                        UNDERFLOW_BYTES_REGEX.find(line)?.let { m ->
                            leUnderflowBytes += m.groupValues[1].toLongOrNull() ?: 0L
                        }
                        CREDITS_UNDERFLOW_REGEX.find(line)?.let { m ->
                            leCreditsUnderflows += m.groupValues[1].toLongOrNull() ?: 0L
                        }
                        ISO_CREDITS_REGEX.find(line)?.let { m ->
                            leCredits = m.groupValues[1].toIntOrNull() ?: leCredits
                        }
                        ISO_BUFFER_SIZE_REGEX.find(line)?.let { m ->
                            leBufferBytes = m.groupValues[1].toIntOrNull() ?: leBufferBytes
                        }
                    }
                }
            }
        }

        // Parse logcat fallback events when logcatText is provided
        var hasLogcat = false
        if (logcatText.isNotBlank()) {
            hasLogcat = true
            var logA2dpPktDrops = 0L
            var logA2dpEncDrops = 0L
            var logA2dpUnderflows = 0L
            var logHfpDrops = 0L
            var logLeUnderflows = 0L
            var logLeIsoDrops = 0L
            for (line in logcatText.lineSequence()) {
                if (line.contains("Dropped media packet", ignoreCase = true) ||
                    line.contains("Dropped oldest frame", ignoreCase = true)
                ) {
                    logA2dpPktDrops++
                }
                LOG_A2DP_ENCODED_DROP_REGEX.find(line)?.let { m ->
                    val n = m.groupValues[1].toLongOrNull() ?: 0L
                    if (n > logA2dpEncDrops) logA2dpEncDrops = n
                }
                if (line.contains("btif_a2dp_source", ignoreCase = true) &&
                    line.contains("underflow", ignoreCase = true)
                ) {
                    logA2dpUnderflows++
                }
                LOG_SCO_UNRECEIVED_REGEX.find(line)?.let { m ->
                    val n = m.groupValues[1].toLongOrNull() ?: 0L
                    if (n > logHfpDrops) logHfpDrops = n
                }
                if ((line.contains("bta_ag_sco", ignoreCase = true) || line.contains("bt_sco", ignoreCase = true)) &&
                    line.contains("drop", ignoreCase = true)
                ) {
                    logHfpDrops++
                }
                if ((line.contains("le_audio", ignoreCase = true) || line.contains("AudioHalClient", ignoreCase = true)) &&
                    line.contains("underflow", ignoreCase = true)
                ) {
                    logLeUnderflows++
                }
                LOG_ISO_FLUSHED_REGEX.find(line)?.let { m ->
                    val n = m.groupValues[1].toLongOrNull() ?: 0L
                    if (n > logLeIsoDrops) logLeIsoDrops = n
                }
            }
            if (!hasDumpsys) {
                a2dpTxDropped = logA2dpPktDrops
                a2dpDroppedFrames = logA2dpEncDrops
                a2dpUnderflows = logA2dpUnderflows
                hfpDroppedPkts = logHfpDrops
                leUnderflows = logLeUnderflows
                leIsoDrops = logLeIsoDrops
            } else {
                if (logLeIsoDrops > leIsoDrops) leIsoDrops = logLeIsoDrops
            }
        }

        val hfpCodec = when {
            hfpSwb -> "SWB (LC3 32 kHz)"
            hfpWbs -> "WBS (mSBC 16 kHz)"
            else -> "CVSD / mSBC"
        }

        val sourceLabel = when {
            hasDumpsys -> "BT STACK DUMPSYS (LIVE)"
            hasLogcat -> "BT LOGCAT MONITOR"
            else -> "STANDARD API (GRANT DUMP/LOGS FOR STACK COUNTERS)"
        }

        return BluetoothAudioDropStats(
            sourceLabel = sourceLabel,
            hasStackCounters = hasDumpsys || hasLogcat,
            a2dpExpectedFrames = a2dpExpectedFrames,
            a2dpDroppedFrames = a2dpDroppedFrames,
            a2dpExpectedPackets = a2dpExpectedPackets,
            a2dpDroppedPackets = a2dpDroppedPackets,
            a2dpTxEnqueued = a2dpTxEnqueued,
            a2dpTxFlushed = a2dpTxFlushed,
            a2dpTxDropped = a2dpTxDropped,
            a2dpTxDropouts = a2dpTxDropouts,
            a2dpMaxDropped = a2dpMaxDropped,
            a2dpUnderflows = a2dpUnderflows,
            a2dpUnderflowBytes = a2dpUnderflowBytes,
            a2dpSinkDelayMs = a2dpSinkDelayMs,
            hfpStateLabel = hfpState,
            hfpSpeechCodecLabel = hfpCodec,
            hfpScoActive = hfpScoActive,
            hfpDroppedPackets = hfpDroppedPkts,
            hfpTriggerDrops = hfpTriggerDrops,
            hfpUnderflows = hfpUnderflows,
            leAudioStreamState = leStreamState,
            leAudioUnderflows = leUnderflows,
            leAudioUnderflowBytes = leUnderflowBytes,
            leIsoCreditsUnderflows = leCreditsUnderflows,
            leIsoDroppedPackets = leIsoDrops,
            leIsoAvailableCredits = leCredits,
            leIsoBufferSizeBytes = leBufferBytes,
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
