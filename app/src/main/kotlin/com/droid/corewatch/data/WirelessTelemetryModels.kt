// Copyright 2026 The Corewatch Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.corewatch.data

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Allowed vs. currently usable 20 MHz primary channels for a single Wi-Fi frequency band
 * (`2.4 GHz`, `5 GHz`, or `6 GHz`) in the device's active regulatory domain.
 */
data class BandChannelSummary(
    val bandLabel: String = "N/A",
    val isHardwareSupported: Boolean = false,
    val allowedCount: Int = 0,
    val usableCount: Int = 0,
    val maxBandwidthMhz: Int = 0,
    val channelRangeSummary: String = "N/A",
    val dfsChannelCount: Int = 0,
    val pscChannelCount: Int = 0,
    val subBandNote: String = "",
) {
    val countBadge: String
        get() = when {
            !isHardwareSupported -> "Unsupported"
            allowedCount == 0 -> "0 Allowed (Reg-Blocked)"
            usableCount == allowedCount -> "$usableCount / $allowedCount Usable"
            else -> "$usableCount Usable ($allowedCount Reg)"
        }
}

/**
 * Hardware Wi-Fi radio capabilities, supported IEEE 802.11 generations, security features,
 * concurrency modes, active ISO/802.11d regulatory region, and per-band allowed channel matrix.
 */
data class WifiRadioCapabilities(
    val isCapabilitiesQueried: Boolean = false,
    val hasNearbyWifiPermission: Boolean = false,
    val channelQuerySucceeded: Boolean = false,
    // Supported IEEE 802.11 Generations
    val supports11beWifi7: Boolean = false,
    val supports11axWifi6: Boolean = false,
    val supports11acWifi5: Boolean = false,
    val supports11nWifi4: Boolean = false,
    val supports11abgLegacy: Boolean = false,
    val supports11adWigig: Boolean = false,
    // Supported Radio Frequency Bands
    val supports24Ghz: Boolean = false,
    val supports5Ghz: Boolean = false,
    val supports6Ghz: Boolean = false,
    val supports60Ghz: Boolean = false,
    // Max Supported PHY Link Speed on Current Association (API 30+)
    val maxTxLinkSpeedMbps: Int = 0,
    val maxRxLinkSpeedMbps: Int = 0,
    // Security & Authentication Capabilities
    val supportsWpa3Sae: Boolean = false,
    val supportsSaeH2e: Boolean = false,
    val supportsSaePk: Boolean = false,
    val supportsOwe: Boolean = false,
    val supportsWpa3SuiteB192: Boolean = false,
    val supportsTlsV13: Boolean = false,
    val supportsTrustOnFirstUse: Boolean = false,
    val supportsEasyConnectDpp: Boolean = false,
    val supportsWpa2Personal: Boolean = false,
    // Radio Concurrency & Roaming Capabilities
    val supportsDualBandSimultaneous: Boolean = false,
    val supportsMloTidToLinkMapping: Boolean = false,
    val supportsMakeBeforeBreak: Boolean = false,
    val supportsStaApConcurrency: Boolean = false,
    val isScanThrottleEnabled: Boolean = true,
    // Active Regulatory Domain / Country Code
    val countryCode: String = "Unknown",
    val countryCodeSource: String = "Unavailable",
    // Per-Band Allowed & Usable Channel Summaries (API 34+ via NEARBY_WIFI_DEVICES neverForLocation)
    val band24Ghz: BandChannelSummary = BandChannelSummary(bandLabel = "2.4 GHz"),
    val band5Ghz: BandChannelSummary = BandChannelSummary(bandLabel = "5 GHz"),
    val band6Ghz: BandChannelSummary = BandChannelSummary(bandLabel = "6 GHz"),
) {
    val maxGenerationLabel: String
        get() = when {
            supports11beWifi7 -> "Wi-Fi 7 (802.11be)"
            supports11axWifi6 && supports6Ghz -> "Wi-Fi 6E (802.11ax)"
            supports11axWifi6 -> "Wi-Fi 6 (802.11ax)"
            supports11acWifi5 -> "Wi-Fi 5 (802.11ac)"
            supports11nWifi4 -> "Wi-Fi 4 (802.11n)"
            supports11abgLegacy -> "Legacy (802.11a/b/g)"
            else -> "Unknown"
        }

    val supportedGenerationsSummary: String
        get() {
            val gens = ArrayList<String>(6)
            if (supports11beWifi7) gens += "Wi-Fi 7 (11be)"
            if (supports11axWifi6) gens += if (supports6Ghz) "Wi-Fi 6E/6 (11ax)" else "Wi-Fi 6 (11ax)"
            if (supports11acWifi5) gens += "Wi-Fi 5 (11ac)"
            if (supports11nWifi4) gens += "Wi-Fi 4 (11n)"
            if (supports11abgLegacy) gens += "11a/b/g"
            if (supports11adWigig) gens += "WiGig (11ad)"
            return if (gens.isEmpty()) "Unknown" else gens.joinToString(" · ")
        }

    val supportedBandsSummary: String
        get() {
            val bands = ArrayList<String>(4)
            if (supports24Ghz) bands += "2.4 GHz"
            if (supports5Ghz) bands += "5 GHz"
            if (supports6Ghz) bands += "6 GHz"
            if (supports60Ghz) bands += "60 GHz"
            val tier = when {
                supports24Ghz && supports5Ghz && supports6Ghz -> " (Tri-Band)"
                supports24Ghz && supports5Ghz -> " (Dual-Band)"
                else -> ""
            }
            return if (bands.isEmpty()) "Unknown" else bands.joinToString(" · ") + tier
        }

    val maxPhySpeedSummary: String
        get() = when {
            maxTxLinkSpeedMbps > 0 && maxRxLinkSpeedMbps > 0 ->
                String.format(Locale.US, "TX %,d Mbps · RX %,d Mbps", maxTxLinkSpeedMbps, maxRxLinkSpeedMbps)
            maxTxLinkSpeedMbps > 0 -> String.format(Locale.US, "TX %,d Mbps", maxTxLinkSpeedMbps)
            maxRxLinkSpeedMbps > 0 -> String.format(Locale.US, "RX %,d Mbps", maxRxLinkSpeedMbps)
            else -> "Connect to AP to query"
        }

    val securityCapabilitiesSummary: String
        get() {
            val sec = ArrayList<String>(9)
            if (supportsWpa3Sae) sec += "WPA3-SAE"
            if (supportsSaeH2e) sec += "SAE-H2E"
            if (supportsSaePk) sec += "SAE-PK"
            if (supportsOwe) sec += "OWE"
            if (supportsWpa3SuiteB192) sec += "WPA3-Ent 192-bit"
            if (supportsTlsV13) sec += "EAP-TLS 1.3"
            if (supportsTrustOnFirstUse) sec += "TOFU"
            if (supportsEasyConnectDpp) sec += "DPP"
            if (supportsWpa2Personal) sec += "WPA2"
            return if (sec.isEmpty()) "Standard WPA2/WPA3" else sec.joinToString(" · ")
        }

    val concurrencySummary: String
        get() {
            val feats = ArrayList<String>(5)
            if (supportsDualBandSimultaneous) feats += "DBS"
            if (supportsMloTidToLinkMapping) feats += "MLO T2LM"
            if (supportsMakeBeforeBreak) feats += "Make-Before-Break"
            if (supportsStaApConcurrency) feats += "STA+AP"
            feats += if (isScanThrottleEnabled) "Scan Throttle: On" else "Scan Throttle: Off"
            return feats.joinToString(" · ")
        }

    val sixGhzRegulatoryWarning: String?
        get() {
            if (!supports6Ghz || !channelQuerySucceeded) return null
            val cc = countryCode.trim().uppercase(Locale.US)
            if (band6Ghz.allowedCount == 0) {
                return when {
                    cc.isEmpty() || cc == "00" || cc == "UNKNOWN" ->
                        "6 GHz Hardware Present, 0 Channels Allowed — Regulatory domain is unresolved ('$countryCode' world safe-mode). Driver disables 6 GHz until an 802.11d country beacon, SIM, or country override sets a valid ISO region."
                    cc == "CA" ->
                        "6 GHz Hardware Present, 0 Channels Allowed in CA (Canada) — Kernel wireless-regdb / Intel LAR (Location Aware Regulatory) or firmware LPI table is blocking 6 GHz (5955–7115 MHz) in region 'CA'."
                    else ->
                        "6 GHz Hardware Present, 0 Channels Allowed in Region '$cc' — Current regulatory domain or firmware LAR policy blocks 6 GHz operation ($countryCodeSource)."
                }
            }
            if (band6Ghz.usableCount == 0) {
                return "6 GHz Allowed (${band6Ghz.allowedCount} ch in $countryCode) but 0 Usable — Temporarily unavailable due to radio concurrency, cellular coex, or indoor LPI/no-IR restriction."
            }
            return null
        }
}

enum class WifiApBandFilter(val label: String) {
    ALL("All Bands"),
    BAND_24_GHZ("2.4 GHz"),
    BAND_5_GHZ("5 GHz"),
    BAND_6_GHZ("6 GHz"),
}

enum class WifiApSortOrder(val label: String) {
    SIGNAL_DBM("Sort: Signal (dBm)"),
    BAND_CHANNEL("Sort: Band & Ch"),
    SSID_NAME("Sort: SSID");

    fun next(): WifiApSortOrder = when (this) {
        SIGNAL_DBM -> BAND_CHANNEL
        BAND_CHANNEL -> SSID_NAME
        SSID_NAME -> SIGNAL_DBM
    }
}

data class ParsedBeaconIes(
    val countryCode80211d: String? = null,
    val countryEnvLabel: String = "",
    val qbssStationCount: Int? = null,
    val qbssChannelUtilizationPct: Int? = null,
    val fastTransition11r: Boolean = false,
    val neighborReport11k: Boolean = false,
    val bssTransition11v: Boolean = false,
)

/**
 * Verbose surrounding Wi-Fi Access Point beacon/probe entry parsed from `WifiManager.getScanResults()`.
 */
data class SurroundingWifiAp(
    val ssid: String,
    val isHiddenSsid: Boolean = false,
    val bssid: String,
    val maskedBssid: String,
    val isConnectedAp: Boolean = false,
    val bandGhz: Int,
    val bandLabel: String,
    val primaryFreqMhz: Int,
    val centerFreq0Mhz: Int = 0,
    val channelNumber: Int,
    val channelWidthMhz: Int,
    val channelWidthLabel: String,
    val isDfsChannel: Boolean = false,
    val isPscChannel: Boolean = false,
    val wifiStandardCode: Int = 0,
    val wifiStandardLabel: String,
    val securitySummary: String,
    val capabilityBadges: List<String> = emptyList(),
    val rssiDbm: Int,
    val signalPercent: Int,
    val mloAffiliatedLinkCount: Int = 0,
    val countryCode80211d: String? = null,
    val qbssStationCount: Int? = null,
    val qbssChannelUtilizationPct: Int? = null,
    val ageSeconds: Float = 0f,
) {
    val channelAndFreqSummary: String
        get() {
            val tag = when {
                isPscChannel -> " [PSC]"
                isDfsChannel -> " [DFS]"
                else -> ""
            }
            val centerPart = if (centerFreq0Mhz > 0 && centerFreq0Mhz != primaryFreqMhz && channelWidthMhz > 20) {
                " (ctr $centerFreq0Mhz)"
            } else {
                ""
            }
            return "$bandLabel · Ch $channelNumber$tag · $primaryFreqMhz MHz$centerPart · $channelWidthLabel"
        }

    val bssLoadSummary: String?
        get() = if (qbssStationCount != null && qbssChannelUtilizationPct != null) {
            "BSS Load: ${qbssChannelUtilizationPct}% (${qbssStationCount} STA)"
        } else {
            null
        }
}

/**
 * Snapshot of surrounding Wi-Fi Access Point scan results and OS scan permission/location state.
 */
data class WifiScanTelemetrySnapshot(
    val hasScanLocationPermission: Boolean = false,
    val isLocationEnabled: Boolean = true,
    val isWifiEnabled: Boolean = true,
    val totalApCount: Int = 0,
    val count24Ghz: Int = 0,
    val count5Ghz: Int = 0,
    val count6Ghz: Int = 0,
    val lastScanAgeSeconds: Float = -1f,
    val accessPoints: List<SurroundingWifiAp> = emptyList(),
) {
    val permissionStateSummary: String
        get() = when {
            !isWifiEnabled -> "Wi-Fi Adapter Off"
            !hasScanLocationPermission -> "Location Permission Needed"
            !isLocationEnabled -> "Location Services Off"
            else -> "$totalApCount APs (${count24Ghz}×2.4G · ${count5Ghz}×5G · ${count6Ghz}×6G)"
        }
}

/**
 * Live Wi-Fi radio, PHY link speed, MAC frame counter, per-interface (`wlan*`) throughput,
 * device radio/regulatory capabilities, and surrounding AP scan telemetry.
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
    val capabilities: WifiRadioCapabilities = WifiRadioCapabilities(),
    val surroundingScan: WifiScanTelemetrySnapshot = WifiScanTelemetrySnapshot(),
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

    fun bandGhzForFrequency(freqMhz: Int): Int = when (freqMhz) {
        in 2401..2495 -> 2
        in 5150..5895 -> 5
        in 5925..7125 -> 6
        in 57000..71000 -> 60
        else -> 0
    }

    fun bandLabelForFrequency(freqMhz: Int): String = when (bandGhzForFrequency(freqMhz)) {
        2 -> "2.4 GHz"
        5 -> "5 GHz"
        6 -> "6 GHz"
        60 -> "60 GHz"
        else -> if (freqMhz > 0) "$freqMhz MHz" else "Unknown"
    }

    fun frequencyMhzToChannel(freqMhz: Int): Int = when {
        freqMhz == 2484 -> 14
        freqMhz in 2412..2472 && (freqMhz - 2407) % 5 == 0 -> (freqMhz - 2407) / 5
        freqMhz in 2412..2472 -> (freqMhz - 2407) / 5
        freqMhz in 5150..5895 -> (freqMhz - 5000) / 5
        freqMhz in 5955..7115 -> (freqMhz - 5950) / 5
        freqMhz in 58320..69120 -> (freqMhz - 56160) / 2160
        else -> 0
    }

    fun isPrimary20MhzChannel(bandGhz: Int, channelNum: Int): Boolean = when (bandGhz) {
        2 -> channelNum in 1..14
        5 -> (channelNum in 32..144 && channelNum % 4 == 0) ||
            (channelNum in 149..177 && (channelNum - 149) % 4 == 0)
        6 -> channelNum in 1..233 && (channelNum - 1) % 4 == 0
        else -> channelNum > 0
    }

    /** Returns true if [freqMhz] lies in 5 GHz radar-avoidance DFS sub-bands (U-NII-2A / U-NII-2C, Ch 52–144). */
    fun is5GhzDfsChannel(freqMhz: Int): Boolean = freqMhz in 5250..5730

    /** Returns true if [freqMhz] is an IEEE 802.11ax 6 GHz Preferred Scanning Channel (Ch 5, 21, 37, ..., 229). */
    fun is6GhzPscChannel(freqMhz: Int): Boolean {
        if (freqMhz !in 5955..7115 || (freqMhz - 5950) % 5 != 0) return false
        val ch = (freqMhz - 5950) / 5
        return ch >= 5 && (ch - 5) % 16 == 0
    }

    fun formatChannelBandwidth(channelWidthCode: Int): Pair<Int, String> = when (channelWidthCode) {
        0 -> 20 to "20 MHz"
        1 -> 40 to "40 MHz"
        2 -> 80 to "80 MHz"
        3 -> 160 to "160 MHz"
        4 -> 160 to "80+80 MHz"
        5 -> 320 to "320 MHz"
        else -> 20 to "20 MHz"
    }

    fun formatWifiStandardForBand(standardCode: Int, freqMhz: Int): String = when (standardCode) {
        8 -> "Wi-Fi 7 (11be)"
        7 -> "WiGig (11ad)"
        6 -> if (freqMhz >= 5925) "Wi-Fi 6E (11ax)" else "Wi-Fi 6 (11ax)"
        5 -> "Wi-Fi 5 (11ac)"
        4 -> "Wi-Fi 4 (11n)"
        1 -> "Legacy (11a/b/g)"
        else -> "Wi-Fi"
    }

    fun sanitizeSsidDisplay(rawSsid: String?): Pair<String, Boolean> {
        if (rawSsid == null) return "<Hidden SSID>" to true
        var text = rawSsid.trim()
        if (text.length >= 2 && text.startsWith('"') && text.endsWith('"')) {
            text = text.substring(1, text.length - 1).trim()
        }
        val cleaned = buildString(text.length.coerceAtMost(32)) {
            for (ch in text) {
                if (length >= 32) break
                if (ch >= ' ' && ch != '\u007f') append(ch)
            }
        }.trim()
        return if (cleaned.isEmpty() || cleaned.equals("<unknown ssid>", ignoreCase = true)) {
            "<Hidden SSID>" to true
        } else {
            cleaned to false
        }
    }

    private val MAC_OCTET_REGEX = Regex("^[0-9a-f]{2}$")

    /**
     * Masks octets 4 and 5 of a 6-octet BSSID (`aa:bb:cc:••:••:ff`), preserving the vendor OUI
     * (`aa:bb:cc`) and radio VAP suffix (`ff`) while preventing passive location fingerprinting.
     */
    fun maskBssid(rawBssid: String?): String {
        if (rawBssid.isNullOrBlank()) return "••:••:••:••:••:••"
        val parts = rawBssid.trim().lowercase(Locale.US).split(':', '-')
        if (parts.size == 6 && parts.all { MAC_OCTET_REGEX.matches(it) }) {
            return "${parts[0]}:${parts[1]}:${parts[2]}:••:••:${parts[5]}"
        }
        return "••:••:••:••:••:••"
    }

    /**
     * Compresses per-band `WifiManager.getAllowedChannels` / `getUsableChannels` frequencies into
     * IEEE channel ranges, DFS / TDWR / PSC sub-band annotations, and maximum regulatory bandwidth.
     */
    fun summarizeAllowedBandChannels(
        bandGhz: Int,
        allowedFreqsWidths: List<Pair<Int, Int>>,
        usableFreqs: List<Int>,
        isBandSupported: Boolean,
        supports11be: Boolean = false,
        supports11axOrAc: Boolean = true,
        supports11n: Boolean = true,
    ): BandChannelSummary {
        val bandLabel = when (bandGhz) {
            2 -> "2.4 GHz"
            5 -> "5 GHz"
            6 -> "6 GHz"
            else -> "${bandGhz} GHz"
        }
        if (!isBandSupported) {
            return BandChannelSummary(
                bandLabel = bandLabel,
                isHardwareSupported = false,
                channelRangeSummary = "Hardware Unsupported",
            )
        }

        val hasExplicit20Mhz = allowedFreqsWidths.any { it.first > 0 && it.second <= 0 }
        val filteredPrimary = allowedFreqsWidths
            .asSequence()
            .filter { (freq, widthCode) ->
                freq > 0 &&
                    bandGhzForFrequency(freq) == bandGhz &&
                    (!hasExplicit20Mhz || widthCode <= 0)
            }
            .map { (freq, _) -> freq to frequencyMhzToChannel(freq) }
            .filter { (_, ch) -> isPrimary20MhzChannel(bandGhz, ch) }
            .distinctBy { it.second }
            .sortedBy { it.second }
            .toList()

        val primaryList = if (filteredPrimary.isNotEmpty()) {
            filteredPrimary
        } else {
            allowedFreqsWidths
                .asSequence()
                .filter { (freq, _) -> freq > 0 && bandGhzForFrequency(freq) == bandGhz }
                .map { (freq, _) -> freq to frequencyMhzToChannel(freq) }
                .filter { (_, ch) -> ch > 0 }
                .distinctBy { it.second }
                .sortedBy { it.second }
                .toList()
        }

        if (primaryList.isEmpty()) {
            return BandChannelSummary(
                bandLabel = bandLabel,
                isHardwareSupported = true,
                allowedCount = 0,
                usableCount = 0,
                maxBandwidthMhz = 0,
                channelRangeSummary = "Blocked in Region",
                subBandNote = if (bandGhz == 6) "0 allowed 6 GHz channels" else "0 allowed channels",
            )
        }

        val allowedChannelSet = primaryList.map { it.second }.toHashSet()
        val usableCount = usableFreqs
            .asSequence()
            .filter { freq -> freq > 0 && bandGhzForFrequency(freq) == bandGhz }
            .map { frequencyMhzToChannel(it) }
            .filter { it in allowedChannelSet }
            .distinct()
            .count()
            .coerceAtMost(primaryList.size)

        val step = if (bandGhz == 2) 1 else 4
        val ranges = ArrayList<String>(4)
        var runStart = primaryList[0].second
        var runPrev = runStart
        var curRunLen = 1
        var maxContiguousRun = 1
        for (i in 1 until primaryList.size) {
            val ch = primaryList[i].second
            if (ch == runPrev + step) {
                runPrev = ch
                curRunLen++
                if (curRunLen > maxContiguousRun) maxContiguousRun = curRunLen
            } else {
                ranges += if (runStart == runPrev) "$runStart" else "$runStart–$runPrev"
                runStart = ch
                runPrev = ch
                curRunLen = 1
            }
        }
        ranges += if (runStart == runPrev) "$runStart" else "$runStart–$runPrev"
        val rangeSummary = "Ch " + ranges.joinToString(", ")

        val explicitMaxWidth = allowedFreqsWidths.maxOfOrNull { (_, w) ->
            formatChannelBandwidth(w).first
        } ?: 20
        val inferredMaxWidth = when (bandGhz) {
            2 -> if (primaryList.size >= 5 && supports11n) 40 else 20
            5 -> when {
                maxContiguousRun >= 8 && supports11axOrAc -> 160
                maxContiguousRun >= 4 && supports11axOrAc -> 80
                maxContiguousRun >= 2 && supports11n -> 40
                else -> 20
            }
            6 -> when {
                maxContiguousRun >= 16 && supports11be -> 320
                maxContiguousRun >= 8 -> 160
                maxContiguousRun >= 4 -> 80
                maxContiguousRun >= 2 -> 40
                else -> 20
            }
            else -> 20
        }
        val maxBwMhz = maxOf(explicitMaxWidth, inferredMaxWidth)

        val dfsCount = if (bandGhz == 5) primaryList.count { is5GhzDfsChannel(it.first) } else 0
        val pscCount = if (bandGhz == 6) primaryList.count { is6GhzPscChannel(it.first) } else 0
        val maxFreq = primaryList.maxOf { it.first }

        val subNote = when (bandGhz) {
            2 -> {
                val maxCh = primaryList.last().second
                when {
                    maxCh >= 14 -> "Ch 1–14 (JP)"
                    maxCh >= 13 -> "ETSI / World (Ch 1–13)"
                    else -> "FCC / ISED (Ch 1–$maxCh)"
                }
            }
            5 -> {
                val tdwrExcluded = 116 in allowedChannelSet && 132 in allowedChannelSet && 120 !in allowedChannelSet
                when {
                    dfsCount > 0 && tdwrExcluded -> "$dfsCount DFS ch · TDWR 120–128 excl."
                    dfsCount > 0 -> "$dfsCount DFS ch (U-NII-2A/2C)"
                    else -> "Non-DFS only (U-NII-1/3)"
                }
            }
            6 -> {
                if (maxFreq <= 6425) {
                    "U-NII-5 Only (Lower 500 MHz · $pscCount PSC)"
                } else {
                    "U-NII-5..8 Full 1200 MHz ($pscCount PSC)"
                }
            }
            else -> ""
        }

        return BandChannelSummary(
            bandLabel = bandLabel,
            isHardwareSupported = true,
            allowedCount = primaryList.size,
            usableCount = usableCount,
            maxBandwidthMhz = maxBwMhz,
            channelRangeSummary = rangeSummary,
            dfsChannelCount = dfsCount,
            pscChannelCount = pscCount,
            subBandNote = subNote,
        )
    }

    private val DUMPSYS_COUNTRY_PATTERNS: List<Pair<Regex, String>> = listOf(
        Regex("\\bmDriverCountryCode:\\s*([A-Za-z0-9]{2})\\b") to "Driver RegDomain (dumpsys)",
        Regex("\\bmOverrideCountryCode:\\s*([A-Za-z0-9]{2})\\b") to "Override RegDomain (dumpsys)",
        Regex("\\bmTelephonyCountryCode:\\s*([A-Za-z0-9]{2})\\b") to "Telephony RegDomain (dumpsys)",
        Regex("\\bDefaultCountryCode\\(config store\\):\\s*([A-Za-z0-9]{2})\\b") to "Config Store (dumpsys)",
        Regex("\\bDefaultCountryCode\\(system property\\):\\s*([A-Za-z0-9]{2})\\b") to "System Property (dumpsys)",
    )

    fun isValidRegulatoryCountryCode(raw: String?): Boolean {
        if (raw == null || raw.length != 2) return false
        if (raw == "00") return true
        return raw[0].isLetter() && raw[1].isLetter() &&
            raw[0].code in 65..122 && raw[1].code in 65..122
    }

    /**
     * Extracts the active Wi-Fi regulatory country code and provenance from `dumpsys wifi` (`WifiCountryCode`).
     */
    fun parseWifiDumpsysCountryCode(dumpsysText: String): Pair<String, String>? {
        if (dumpsysText.isBlank() || dumpsysText.contains("Permission Denial", ignoreCase = true)) return null
        val matches = HashMap<Int, Pair<String, String>>(5)
        for (line in dumpsysText.lineSequence()) {
            for ((idx, entry) in DUMPSYS_COUNTRY_PATTERNS.withIndex()) {
                if (matches.containsKey(idx)) continue
                val m = entry.first.find(line) ?: continue
                val code = m.groupValues[1].uppercase(Locale.US)
                if (isValidRegulatoryCountryCode(code)) {
                    matches[idx] = code to entry.second
                    if (idx == 0) return matches[0]
                }
            }
        }
        for (idx in DUMPSYS_COUNTRY_PATTERNS.indices) {
            matches[idx]?.let { return it }
        }
        return null
    }

    /**
     * Parses IEEE 802.11 Beacon / Probe Response Information Elements (`ScanResult.InformationElement`):
     * - `EID 7` (`802.11d` Country code + Indoor/Outdoor environment)
     * - `EID 11` (`802.11e` QBSS Load station count & channel utilization %)
     * - `EID 54` (`802.11r` Mobility Domain Fast BSS Transition)
     * - `EID 70` (`802.11k` Radio Resource Measurement Neighbor Report)
     * - `EID 127` (`802.11v` Extended Capabilities bit 19 BSS Transition Management)
     */
    fun parse80211InformationElements(elements: List<Pair<Int, ByteArray>>): ParsedBeaconIes {
        var country: String? = null
        var envLabel = ""
        var staCount: Int? = null
        var chanUtilPct: Int? = null
        var has11r = false
        var has11k = false
        var has11v = false

        for ((eid, bytes) in elements) {
            when (eid) {
                7 -> if (country == null && bytes.size >= 3) {
                    val c0 = (bytes[0].toInt() and 0xFF).toChar()
                    val c1 = (bytes[1].toInt() and 0xFF).toChar()
                    val cand = "$c0$c1".uppercase(Locale.US)
                    if (isValidRegulatoryCountryCode(cand)) {
                        country = cand
                        val envByte = (bytes[2].toInt() and 0xFF).toChar()
                        envLabel = when (envByte) {
                            'I' -> "Indoor"
                            'O' -> "Outdoor"
                            'X' -> "Non-Country"
                            else -> ""
                        }
                    }
                }
                11 -> if (staCount == null && bytes.size >= 5) {
                    val stations = (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
                    val utilRaw = bytes[2].toInt() and 0xFF
                    staCount = stations.coerceIn(0, 65_535)
                    chanUtilPct = ((utilRaw * 100 + 127) / 255).coerceIn(0, 100)
                }
                54 -> if (bytes.size >= 2) {
                    has11r = true
                }
                70 -> if (bytes.isNotEmpty()) {
                    // Bit 1 of byte 0 is Neighbor Report capability; presence of RRM IE also signals 802.11k
                    has11k = true
                }
                127 -> if (bytes.size >= 3 && (bytes[2].toInt() and 0x08) != 0) {
                    // Bit 19 (octet 2, bit 3) = BSS Transition
                    has11v = true
                }
            }
        }
        return ParsedBeaconIes(
            countryCode80211d = country,
            countryEnvLabel = envLabel,
            qbssStationCount = staCount,
            qbssChannelUtilizationPct = chanUtilPct,
            fastTransition11r = has11r,
            neighborReport11k = has11k,
            bssTransition11v = has11v,
        )
    }

    /**
     * Parses `ScanResult.capabilities` and `ScanResult.getSecurityTypes()` into a primary security
     * label and supplementary AKM / management-frame badges (`SAE-H2E`, `PMF-Req`, `PMF`, `11r`, `WPS`).
     */
    fun formatScanCapabilitiesSecurity(
        capabilitiesRaw: String,
        securityTypes: IntArray = IntArray(0),
    ): Pair<String, List<String>> {
        val caps = capabilitiesRaw.uppercase(Locale.US)
        val hasSae = caps.contains("SAE") || securityTypes.contains(4)
        val hasPsk = caps.contains("PSK") || securityTypes.contains(2)
        val hasOwe = caps.contains("OWE") || securityTypes.contains(6)
        val hasSuiteB = caps.contains("SUITE_B_192") || caps.contains("SUITE-B-192") || securityTypes.contains(5)
        val hasEap = caps.contains("EAP") || securityTypes.contains(3) || securityTypes.contains(9)
        val hasWep = caps.contains("WEP") || securityTypes.contains(1)
        val hasWapi = caps.contains("WAPI") || securityTypes.contains(7) || securityTypes.contains(8)

        val primary = when {
            hasSuiteB -> "WPA3-Ent 192-bit"
            hasSae && hasPsk -> "WPA2/WPA3-SAE"
            hasSae -> "WPA3-SAE"
            hasEap && (caps.contains("WPA3") || caps.contains("RSN-EAP") && securityTypes.contains(9)) -> "WPA3-Enterprise"
            hasEap -> "WPA2/WPA3-Ent"
            hasOwe && caps.contains("OWE_TRANSITION") -> "Open / OWE"
            hasOwe -> "OWE (Enhanced Open)"
            hasPsk && caps.contains("WPA2") -> "WPA2-PSK"
            hasPsk -> "WPA/WPA2-PSK"
            hasWapi -> "WAPI"
            hasWep -> "WEP"
            else -> "Open"
        }

        val badges = ArrayList<String>(5)
        if (caps.contains("SAE_EXT_KEY") || caps.contains("SAE-H2E")) {
            badges += "SAE-H2E"
        }
        if (caps.contains("SAE-PK") || caps.contains("SAE_PK")) {
            badges += "SAE-PK"
        }
        when {
            caps.contains("MFPR") -> badges += "PMF-Req"
            caps.contains("MFPC") -> badges += "PMF"
        }
        if (caps.contains("FT/") || caps.contains("-FT-") || caps.contains("-FT]")) {
            badges += "11r"
        }
        if (caps.contains("[WPS]")) {
            badges += "WPS"
        }
        return primary to badges
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
