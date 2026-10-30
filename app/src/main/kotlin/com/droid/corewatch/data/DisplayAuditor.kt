// Copyright 2026 The Corewatch Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.corewatch.data

import java.util.Locale
import kotlin.math.roundToInt

data class DisplayModeSpec(
    val modeId: Int,
    val width: Int,
    val height: Int,
    val refreshRateHz: Float,
    val isActive: Boolean,
) {
    val label: String
        get() = "${width}×${height} @ ${refreshRateHz.roundToInt()}Hz"
}

data class DisplaySpec(
    val displayId: Int,
    val name: String,
    val isBuiltIn: Boolean,
    val connectionLabel: String,
    val activeWidth: Int,
    val activeHeight: Int,
    val activeRefreshHz: Float,
    val logicalDpi: Int,
    val physicalXDpi: Float,
    val physicalYDpi: Float,
    val hdrFormats: List<String>,
    val supportedModes: List<DisplayModeSpec>,
    val bottleneckWarning: String?,
    val renderFrameRateHz: Float = activeRefreshHz,
    val vsyncRateHz: Float = activeRefreshHz,
    val supportedRefreshStepsHz: List<Float> = listOf(activeRefreshHz),
    val vrrCapable: Boolean = false,
    val vrrKernelEnabled: Boolean = false,
    val hasAndroidArr: Boolean = false,
    val maxBpc: Int = 8,
    val panelModel: String = "",
    val drmConnectorName: String = "",
) {
    val activeResolutionLabel: String
        get() = "${activeWidth}×${activeHeight}"

    val activeRefreshLabel: String
        get() = String.format(Locale.US, "%.0f Hz", activeRefreshHz)

    val scalePercent: Int
        get() = ((logicalDpi.toFloat() / 160f) * 100f).roundToInt().coerceIn(50, 400)

    val minRefreshHz: Float
        get() = supportedRefreshStepsHz.minOrNull()
            ?: supportedModes.minOfOrNull { it.refreshRateHz }
            ?: activeRefreshHz

    val maxRefreshHz: Float
        get() = supportedRefreshStepsHz.maxOrNull()
            ?: supportedModes.maxOfOrNull { it.refreshRateHz }
            ?: activeRefreshHz

    val isVrrSupported: Boolean
        get() = vrrCapable || hasAndroidArr || (maxRefreshHz - minRefreshHz >= 10f && supportedRefreshStepsHz.size >= 2)

    val vrrStatusBadge: String
        get() = when {
            vrrKernelEnabled || hasAndroidArr -> "VRR Active"
            isVrrSupported && kotlin.math.abs(renderFrameRateHz - vsyncRateHz) >= 1f ->
                "Dynamic Cadence (${renderFrameRateHz.roundToInt()}/${vsyncRateHz.roundToInt()} Hz)"
            isVrrSupported -> "VRR Capable"
            else -> "Fixed Refresh"
        }

    val vrrRangeSummary: String
        get() {
            val distinctSteps = supportedRefreshStepsHz.map { it.roundToInt() }.distinct().sorted()
            return if (distinctSteps.size >= 2) {
                "${distinctSteps.first()}–${distinctSteps.last()} Hz (${distinctSteps.joinToString(", ")} Hz)"
            } else if (isVrrSupported) {
                "${minRefreshHz.roundToInt()}–${maxRefreshHz.roundToInt()} Hz"
            } else {
                "Fixed ${activeRefreshHz.roundToInt()} Hz"
            }
        }

    val liveCadenceSummary: String
        get() = String.format(Locale.US, "Render %.0f Hz · VSync %.0f Hz", renderFrameRateHz, vsyncRateHz)
}

/**
 * Pure auditor for internal laptop panels and external HDMI / USB-C DisplayPort Alt Mode monitors.
 * Flags 30Hz dock negotiation bottlenecks when high-resolution external monitors run below 50Hz,
 * and audits Variable Refresh Rate (VRR / eDP Adaptive-Sync / Android ARR) capabilities and live cadence.
 */
object DisplayAuditor {
    fun auditDisplay(
        displayId: Int,
        rawName: String?,
        activeModeId: Int,
        activeWidth: Int,
        activeHeight: Int,
        activeRefreshHz: Float,
        logicalDpi: Int,
        physicalXDpi: Float,
        physicalYDpi: Float,
        hdrTypeIds: IntArray,
        modes: List<DisplayModeSpec>,
        renderFrameRateHz: Float = activeRefreshHz,
        vsyncRateHz: Float = activeRefreshHz,
        supportedRefreshRatesHz: FloatArray = floatArrayOf(),
        vrrCapable: Boolean = false,
        vrrKernelEnabled: Boolean = false,
        hasAndroidArr: Boolean = false,
        maxBpc: Int = 8,
        panelModel: String? = null,
        drmConnectorName: String? = null,
    ): DisplaySpec {
        val isBuiltIn = displayId == 0
        val cleanName = SysfsParsers.sanitizeSensorName(
            rawName,
            if (isBuiltIn) "Built-in Googlebook Display" else "External Monitor #$displayId",
        )
        val cleanConnector = SysfsParsers.sanitizeSensorName(drmConnectorName, "")
        val connectionLabel = when {
            isBuiltIn && cleanConnector.isNotEmpty() -> "Internal Panel ($cleanConnector)"
            isBuiltIn -> "Internal eDP Panel"
            cleanConnector.isNotEmpty() -> "External ($cleanConnector)"
            else -> "External (USB-C DP Alt Mode / HDMI)"
        }
        val safeWidth = activeWidth.coerceIn(320, 16_384)
        val safeHeight = activeHeight.coerceIn(240, 16_384)
        val safeHz = if (activeRefreshHz.isFinite() && activeRefreshHz > 0f) activeRefreshHz.coerceIn(1f, 480f) else 60f
        val safeRenderHz = if (renderFrameRateHz.isFinite() && renderFrameRateHz > 0f) renderFrameRateHz.coerceIn(1f, 480f) else safeHz
        val safeVsyncHz = if (vsyncRateHz.isFinite() && vsyncRateHz > 0f) vsyncRateHz.coerceIn(1f, 480f) else safeHz

        val normalizedModes = if (modes.isEmpty()) {
            listOf(DisplayModeSpec(activeModeId, safeWidth, safeHeight, safeHz, isActive = true))
        } else {
            modes.sortedWith(
                compareByDescending<DisplayModeSpec> { it.width.toLong() * it.height.toLong() }
                    .thenByDescending { it.refreshRateHz },
            )
        }

        val refreshSteps = if (supportedRefreshRatesHz.isNotEmpty()) {
            supportedRefreshRatesHz
                .filter { it.isFinite() && it in 1f..480f }
                .map { (it * 10f).roundToInt() / 10f }
                .distinct()
                .sorted()
        } else {
            normalizedModes
                .filter { it.width == safeWidth && it.height == safeHeight }
                .map { (it.refreshRateHz * 10f).roundToInt() / 10f }
                .distinct()
                .sorted()
                .ifEmpty { listOf(safeHz) }
        }

        val maxSupportedHzAtActiveRes = normalizedModes
            .filter { it.width == safeWidth && it.height == safeHeight }
            .maxOfOrNull { it.refreshRateHz } ?: safeHz

        val maxSupportedHzAnyRes = normalizedModes.maxOfOrNull { it.refreshRateHz } ?: safeHz

        val bottleneckWarning = when {
            !isBuiltIn && safeHz < 50f && maxSupportedHzAtActiveRes >= 59f ->
                "Running at ${safeHz.roundToInt()}Hz — this monitor supports ${maxSupportedHzAtActiveRes.roundToInt()}Hz at ${safeWidth}×${safeHeight}. Check Display Settings or USB-C dock lane allocation."
            !isBuiltIn && safeHz < 50f && (safeWidth >= 2560 || safeHeight >= 1440) ->
                "30Hz USB-C / HDMI Dock Bottleneck detected (${safeWidth}×${safeHeight} @ ${safeHz.roundToInt()}Hz). A 2-lane USB-C hub with USB 3.0 data often caps 4K to 30Hz."
            !isBuiltIn && safeHz < 50f && maxSupportedHzAnyRes >= 59f ->
                "Low refresh rate (${safeHz.roundToInt()}Hz); panel supports up to ${maxSupportedHzAnyRes.roundToInt()}Hz."
            else -> null
        }

        val hdrLabels = hdrTypeIds.distinct().sorted().mapNotNull { id ->
            when (id) {
                1 -> "Dolby Vision"
                2 -> "HDR10"
                3 -> "HLG"
                4 -> "HDR10+"
                else -> null
            }
        }

        val inferredVrrCapable = vrrCapable || hasAndroidArr || refreshSteps.size >= 2

        return DisplaySpec(
            displayId = displayId,
            name = cleanName,
            isBuiltIn = isBuiltIn,
            connectionLabel = connectionLabel,
            activeWidth = safeWidth,
            activeHeight = safeHeight,
            activeRefreshHz = safeHz,
            logicalDpi = logicalDpi.coerceIn(72, 640),
            physicalXDpi = if (physicalXDpi.isFinite() && physicalXDpi > 0f) physicalXDpi else logicalDpi.toFloat(),
            physicalYDpi = if (physicalYDpi.isFinite() && physicalYDpi > 0f) physicalYDpi else logicalDpi.toFloat(),
            hdrFormats = hdrLabels,
            supportedModes = normalizedModes,
            bottleneckWarning = bottleneckWarning,
            renderFrameRateHz = safeRenderHz,
            vsyncRateHz = safeVsyncHz,
            supportedRefreshStepsHz = refreshSteps,
            vrrCapable = inferredVrrCapable,
            vrrKernelEnabled = vrrKernelEnabled,
            hasAndroidArr = hasAndroidArr,
            maxBpc = maxBpc.coerceIn(6, 16),
            panelModel = SysfsParsers.sanitizeSensorName(panelModel, ""),
            drmConnectorName = cleanConnector,
        )
    }
}
