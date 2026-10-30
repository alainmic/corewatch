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
    val network: NetworkThroughput = NetworkThroughput(),
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
                "${d.name} (${d.activeResolutionLabel} @ ${d.activeRefreshLabel}, ${d.scalePercent}% scale$warn)"
            }
        }

        return buildString {
            appendLine("### Corewatch Hardware & Thermal Diagnostic Snapshot")
            appendLine()
            appendLine("| Metric | Value |")
            appendLine("| :--- | :--- |")
            appendLine("| **Device / Board** | `$model` (`$board`, `$abi`) |")
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
                "| **Memory & zram Swap** | RAM: `${formatGiB(snapshot.memory.usedBytes)} / ${formatGiB(snapshot.memory.totalBytes)}` (`${ramPct}%`) · Swap: `${formatGiB(snapshot.memory.swapUsedBytes)} / ${formatGiB(snapshot.memory.swapTotalBytes)}` (`${swapPct}%`) |",
            )
            appendLine("| **Displays** | $displaySummary |")
            appendLine("| **Caffeine Keep-Awake** | `${SysfsParsers.sanitizeSensorName(snapshot.caffeineEvaluation.activeReason, "Off")}` |")
        }.trimEnd()
    }
}
