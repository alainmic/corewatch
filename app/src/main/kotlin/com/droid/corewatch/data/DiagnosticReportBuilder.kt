// Copyright 2026 The Corewatch Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.corewatch.data

import java.util.Locale
import kotlin.math.roundToInt

data class NetworkThroughput(
    val rxMbps: Float = 0f,
    val txMbps: Float = 0f,
    val totalRxBytes: Long = 0L,
    val totalTxBytes: Long = 0L,
    val rxPacketsPerSec: Float = 0f,
    val txPacketsPerSec: Float = 0f,
    val totalRxPackets: Long = 0L,
    val totalTxPackets: Long = 0L,
)

data class StorageSummary(
    val totalBytes: Long = 0L,
    val freeBytes: Long = 0L,
) {
    val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0L)
    val usedFraction: Float
        get() = if (totalBytes <= 0L) 0f else (usedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
}

data class SystemTelemetrySnapshot(
    val deviceBoard: String = "googlebook",
    val deviceModel: String = "Googlebook",
    val osVersion: String = "Android 16",
    val cpuAbi: String = "x86_64",
    val cpuModelName: String = "Intel Core Ultra",
    val thermalLevel: ThermalLevel = ThermalLevel.NONE,
    val thermalHeadroom: Float = 0.25f,
    val cpuCores: List<CpuCoreReading> = emptyList(),
    val gpu: GpuTelemetrySnapshot = GpuTelemetrySnapshot(),
    val npu: NpuTelemetrySnapshot = NpuTelemetrySnapshot(),
    val thermalSensors: List<ThermalSensorReading> = emptyList(),
    val battery: BatterySnapshot = BatteryPowerCalculator.computeSnapshot(
        levelPercent = 85,
        isCharging = true,
        pluggedType = 1,
        rawVoltage = 11_800,
        rawCurrentNow = 3_800_000L,
        rawTemperatureTenthsC = 315,
        cycleCount = 42,
        healthCode = 2,
    ),
    val memory: MemorySnapshot = MemorySnapshot(
        totalBytes = 16L shl 30,
        availableBytes = 9L shl 30,
        freeBytes = 4L shl 30,
        cachedBytes = 4L shl 30,
        buffersBytes = 512L shl 20,
        dirtyBytes = 16L shl 20,
        swapTotalBytes = 8L shl 30,
        swapFreeBytes = 7L shl 30,
    ),
    val storage: StorageSummary = StorageSummary(totalBytes = 256L shl 30, freeBytes = 180L shl 30),
    val displays: List<DisplaySpec> = emptyList(),
    val frameJank: FrameJankSnapshot = FrameJankSnapshot(),
    val lmkd: LmkdTelemetrySnapshot = LmkdTelemetrySnapshot(),
    val network: NetworkThroughput = NetworkThroughput(),
    val wifi: WifiTelemetrySnapshot = WifiTelemetrySnapshot(),
    val ethernet: EthernetTelemetrySnapshot = EthernetTelemetrySnapshot(),
    val activeDefaultRouteSummary: String = "Disconnected",
    val bluetooth: BluetoothTelemetrySnapshot = BluetoothTelemetrySnapshot(),
    val usbPeripherals: UsbPeripheralTelemetrySnapshot = UsbPeripheralTelemetrySnapshot(),
    val caffeineEvaluation: CaffeineEvaluation = CaffeineEvaluation(false, "Off", null, false),
) {
    val avgCpuUtilizationFraction: Float
        get() = if (cpuCores.isEmpty()) 0f else cpuCores.map { it.utilizationFraction }.average().toFloat()

    val maxCpuFreqMhz: Int
        get() = cpuCores.maxOfOrNull { it.curMhz } ?: 0

    val primaryCpuTempCelsius: Float
        get() = thermalSensors.firstOrNull()?.celsius ?: battery.temperatureCelsius

    val externalDisplayCount: Int
        get() = displays.count { !it.isBuiltIn }
}

/**
 * Generates an anonymized, Markdown-safe hardware & thermal diagnostic report for `Ctrl+Shift+C`.
 */
object DiagnosticReportBuilder {
    fun formatGiB(bytes: Long): String =
        String.format(Locale.US, "%.1f GiB", bytes.coerceAtLeast(0L).toDouble() / (1L shl 30).toDouble())

    fun buildMarkdownReport(snapshot: SystemTelemetrySnapshot): String {
        val board = SysfsParsers.sanitizeSensorName(snapshot.deviceBoard, "unknown")
        val model = SysfsParsers.sanitizeSensorName(snapshot.deviceModel, "Googlebook")
        val os = SysfsParsers.sanitizeSensorName(snapshot.osVersion, "Android")
        val abi = SysfsParsers.sanitizeSensorName(snapshot.cpuAbi, "unknown")
        val cpuModel = SysfsParsers.sanitizeSensorName(snapshot.cpuModelName, abi)

        val pCores = snapshot.cpuCores.count { it.cluster == CoreCluster.Performance }
        val eCores = snapshot.cpuCores.count { it.cluster == CoreCluster.Efficiency }
        val coreTopology = if (pCores > 0 || eCores > 0) {
            "${snapshot.cpuCores.size} cores (${pCores}P + ${eCores}E)"
        } else {
            "${snapshot.cpuCores.size} cores"
        }
        val avgCpuPct = (snapshot.avgCpuUtilizationFraction * 100f).roundToInt()
        val headroomStr = if (snapshot.thermalHeadroom.isFinite() && snapshot.thermalHeadroom > 0f) {
            String.format(Locale.US, "%.2f", snapshot.thermalHeadroom)
        } else {
            "N/A"
        }
        val topSensor = snapshot.thermalSensors.maxByOrNull { it.celsius }
        val sensorSummary = if (topSensor != null) {
            String.format(Locale.US, "%s: %.1f °C", topSensor.name, topSensor.celsius)
        } else {
            String.format(Locale.US, "Battery: %.1f °C", snapshot.battery.temperatureCelsius)
        }

        val ramPct = (snapshot.memory.usedFraction * 100f).roundToInt()
        val swapPct = (snapshot.memory.swapUsedFraction * 100f).roundToInt()

        val displaySummary = if (snapshot.displays.isEmpty()) {
            "None reported"
        } else {
            snapshot.displays.joinToString("; ") { d ->
                val warn = if (d.bottleneckWarning != null) " ⚠️ 30Hz Bottleneck" else ""
                "${d.name} (${d.activeResolutionLabel} @ ${d.activeRefreshLabel}, ${d.vrrStatusBadge} [${d.vrrRangeSummary}], ${d.scalePercent}% scale$warn)"
            }
        }

        return buildString {
            appendLine("### Corewatch Hardware & Thermal Diagnostic Snapshot")
            appendLine()
            appendLine("| Metric | Value |")
            appendLine("| :--- | :--- |")
            appendLine("| **Device / Board** | `$model` (`$board`, `$abi` · `$cpuModel`) |")
            appendLine("| **OS Release** | `$os` |")
            appendLine("| **Thermal Status** | `${snapshot.thermalLevel.label}` (Headroom: `$headroomStr`, Peak: `$sensorSummary`) |")
            appendLine("| **CPU Scaling** | `$coreTopology` — Avg `${avgCpuPct}%`, Peak `${snapshot.maxCpuFreqMhz} MHz` |")
            appendLine(
                String.format(
                    Locale.US,
                    "| **GPU Accelerator** | `%s` (`%s`) — `%d%%` active (`%d / %d MHz`, `%s`, Throttle: `%s`) |",
                    SysfsParsers.sanitizeSensorName(snapshot.gpu.deviceName, "GPU"),
                    SysfsParsers.sanitizeSensorName(snapshot.gpu.driverName, "drm"),
                    (snapshot.gpu.utilizationFraction * 100f).roundToInt(),
                    snapshot.gpu.displayFreqMhz,
                    snapshot.gpu.maxFreqMhz,
                    snapshot.gpu.scalingStateLabel,
                    SysfsParsers.sanitizeSensorName(snapshot.gpu.throttleSummary, "Nominal"),
                ),
            )
            appendLine(
                String.format(
                    Locale.US,
                    "| **NPU Accelerator** | `%s` (`%s`, `%s`) — `%d%%` active (`%d / %d MHz`, `%s`, `%d / %d` contexts) |",
                    SysfsParsers.sanitizeSensorName(snapshot.npu.deviceName, "NPU"),
                    SysfsParsers.sanitizeSensorName(snapshot.npu.driverName, "accel"),
                    SysfsParsers.sanitizeSensorName(snapshot.npu.firmwareVersion, "v1.0"),
                    (snapshot.npu.utilizationFraction * 100f).roundToInt(),
                    snapshot.npu.currentFreqMhz,
                    snapshot.npu.maxFreqMhz,
                    snapshot.npu.scalingStateLabel,
                    snapshot.npu.activeContexts,
                    snapshot.npu.maxContexts,
                ),
            )
            appendLine(
                String.format(
                    Locale.US,
                    "| **Battery & Power** | `%d%%` — `%s` (`%d mV`, `%.1f °C`, `%d cycles`, `%s`) |",
                    snapshot.battery.levelPercent,
                    SysfsParsers.sanitizeSensorName(snapshot.battery.statusSummary, "N/A"),
                    snapshot.battery.voltageMv,
                    snapshot.battery.temperatureCelsius,
                    snapshot.battery.cycleCount,
                    SysfsParsers.sanitizeSensorName(snapshot.battery.healthLabel, "Good"),
                ),
            )
            appendLine(
                "| **Memory & zram Swap** | RAM: `${formatGiB(snapshot.memory.usedBytes)} / ${formatGiB(snapshot.memory.totalBytes)}` (`${ramPct}%`, AMS Threshold `${formatGiB(snapshot.memory.amsThresholdBytes)}`) · Swap: `${formatGiB(snapshot.memory.swapUsedBytes)} / ${formatGiB(snapshot.memory.swapTotalBytes)}` (`${swapPct}%`) · LMKD 5m: `${snapshot.lmkd.killCount5m} kills` |",
            )
            appendLine(
                String.format(
                    Locale.US,
                    "| **Frame Pacing (5m)** | `%d` frames (`%.2f%%` dropped, `%.2f%%` janky · Deadlines `%s`) |",
                    snapshot.frameJank.evaluatedFrames5m,
                    snapshot.frameJank.droppedPercent5m,
                    snapshot.frameJank.jankyPercent5m,
                    snapshot.frameJank.deadlineRangeLabel,
                ),
            )
            appendLine("| **Displays** | $displaySummary |")
            appendLine(
                String.format(
                    Locale.US,
                    "| **Wi-Fi Radio & Link** | `%s` (`%s` · `%s` · `%s` · `%d dBm` `%s` · PHY `%s` · RX `%.2f` / TX `%.2f Mbps`) |",
                    SysfsParsers.sanitizeSensorName(snapshot.wifi.interfaceName, "wlan0"),
                    SysfsParsers.sanitizeSensorName(snapshot.wifi.wifiStandardLabel, "Disconnected"),
                    SysfsParsers.sanitizeSensorName(snapshot.wifi.routeRoleLabel, "Disconnected"),
                    SysfsParsers.sanitizeSensorName(snapshot.wifi.bandAndChannelLabel, "N/A"),
                    snapshot.wifi.rssiDbm,
                    SysfsParsers.sanitizeSensorName(snapshot.wifi.linkHealthBadge, "N/A"),
                    SysfsParsers.sanitizeSensorName(snapshot.wifi.phySpeedSummary, "N/A"),
                    snapshot.wifi.throughput.rxMbps,
                    snapshot.wifi.throughput.txMbps,
                ),
            )
            val wifiCap = snapshot.wifi.capabilities
            val regChannelsSummary = if (wifiCap.channelQuerySucceeded) {
                "2.4G `${wifiCap.band24Ghz.usableCount}/${wifiCap.band24Ghz.allowedCount}` · 5G `${wifiCap.band5Ghz.usableCount}/${wifiCap.band5Ghz.allowedCount}` · 6G `${wifiCap.band6Ghz.usableCount}/${wifiCap.band6Ghz.allowedCount}`"
            } else {
                "Channels `Unqueried`"
            }
            val sixGhzWarnFlag = if (wifiCap.sixGhzRegulatoryWarning != null) " ⚠️ 6 GHz RegDomain Blocked" else ""
            appendLine(
                String.format(
                    Locale.US,
                    "| **Wi-Fi Radio & RegDomain** | Max `%s` (`%s` · Max PHY `%s` · Region `%s` [%s] · %s)%s |",
                    SysfsParsers.sanitizeSensorName(wifiCap.maxGenerationLabel, "Unknown"),
                    SysfsParsers.sanitizeSensorName(wifiCap.supportedBandsSummary, "Unknown"),
                    SysfsParsers.sanitizeSensorName(wifiCap.maxPhySpeedSummary, "N/A"),
                    SysfsParsers.sanitizeSensorName(wifiCap.countryCode, "Unknown"),
                    SysfsParsers.sanitizeSensorName(wifiCap.countryCodeSource, "Unavailable"),
                    regChannelsSummary,
                    sixGhzWarnFlag,
                ),
            )
            val ethSummary = if (snapshot.ethernet.isConnected) {
                val warnSuffix = if (snapshot.ethernet.dockBottleneckWarning != null) " ⚠️ 100BASE-TX Ceiling" else ""
                String.format(
                    Locale.US,
                    "`%s` — `%s` (`%s` · MTU `%d` · `%s` · `%s` · RX `%.2f` / TX `%.2f Mbps` · Total Net RX `%.2f` / TX `%.2f Mbps`)%s",
                    SysfsParsers.sanitizeSensorName(snapshot.ethernet.interfaceName, "eth0"),
                    SysfsParsers.sanitizeSensorName(snapshot.ethernet.linkSpeedTierLabel, "Connected"),
                    SysfsParsers.sanitizeSensorName(snapshot.ethernet.routeRoleLabel, "Primary Default Route"),
                    snapshot.ethernet.mtu,
                    SysfsParsers.sanitizeSensorName(snapshot.ethernet.ipStackLabel, "N/A"),
                    SysfsParsers.sanitizeSensorName(snapshot.ethernet.dnsSummaryLabel, "N/A"),
                    snapshot.ethernet.throughput.rxMbps,
                    snapshot.ethernet.throughput.txMbps,
                    snapshot.network.rxMbps,
                    snapshot.network.txMbps,
                    warnSuffix,
                )
            } else {
                String.format(
                    Locale.US,
                    "`Undocked / Disconnected` (Active Route: `%s` · Total Net RX `%.2f` / TX `%.2f Mbps`)",
                    SysfsParsers.sanitizeSensorName(snapshot.activeDefaultRouteSummary, "Disconnected"),
                    snapshot.network.rxMbps,
                    snapshot.network.txMbps,
                )
            }
            appendLine("| **Wired Ethernet** | $ethSummary |")
            val btDropsSummary = if (snapshot.bluetooth.audioDrops.hasStackCounters) {
                String.format(
                    Locale.US,
                    "A2DP `%s`, HFP `%s`, LE Audio `%s`",
                    SysfsParsers.sanitizeSensorName(snapshot.bluetooth.audioDrops.a2dpPrimaryCounterLabel, "0"),
                    SysfsParsers.sanitizeSensorName(snapshot.bluetooth.audioDrops.hfpPrimaryCounterLabel, "0"),
                    SysfsParsers.sanitizeSensorName(snapshot.bluetooth.audioDrops.leAudioPrimaryCounterLabel, "0"),
                )
            } else {
                "`Locked (grant android.permission.DUMP)`"
            }
            appendLine(
                String.format(
                    Locale.US,
                    "| **Bluetooth & Codec** | `%s` — Route: `%s` · Codec: `%s` (`%s`, `%s`, `%s`) · Drops (%s) · Connected: `%d` (`%d` paired) |",
                    SysfsParsers.sanitizeSensorName(snapshot.bluetooth.connectionStateLabel, "Off"),
                    SysfsParsers.sanitizeSensorName(snapshot.bluetooth.audioCodec.activeRouteLabel, "Speaker"),
                    SysfsParsers.sanitizeSensorName(snapshot.bluetooth.audioCodec.activeCodecName, "None"),
                    SysfsParsers.sanitizeSensorName(snapshot.bluetooth.audioCodec.sampleRateLabel, "48.0 kHz"),
                    SysfsParsers.sanitizeSensorName(snapshot.bluetooth.audioCodec.bitsPerSampleLabel, "24-bit"),
                    SysfsParsers.sanitizeSensorName(snapshot.bluetooth.audioCodec.bitrateOrQualityLabel, "Standard"),
                    btDropsSummary,
                    snapshot.bluetooth.connectedCount,
                    snapshot.bluetooth.bondedCount,
                ),
            )
            val usb = snapshot.usbPeripherals
            val typeCSummary = if (usb.hasTypeCPorts) {
                val activePorts = usb.typeCPorts.count { it.isConnected }
                "${activePorts}/${usb.typeCPorts.size} Type-C active"
            } else {
                "Type-C roles `Locked (grant DUMP)`"
            }
            val usbWarnSuffix = if (usb.warningCount > 0) " ⚠️ ${usb.warningCount} bottleneck/Alt-Mode warning(s)" else ""
            appendLine(
                String.format(
                    Locale.US,
                    "| **USB & Peripherals** | `%d` external peripheral(s) · `%d` hub/dock(s) · Peak Link `%s` (Host Max `%s`) · %s%s |",
                    usb.externalPeripheralCount,
                    usb.externalHubCount,
                    SysfsParsers.sanitizeSensorName(usb.maxExternalSpeedLabel, "No External USB"),
                    SysfsParsers.sanitizeSensorName(usb.maxControllerSpeedLabel, "N/A"),
                    typeCSummary,
                    usbWarnSuffix,
                ),
            )
            appendLine("| **Caffeine Keep-Awake** | `${SysfsParsers.sanitizeSensorName(snapshot.caffeineEvaluation.activeReason, "Off")}` |")
        }.trimEnd()
    }
}
