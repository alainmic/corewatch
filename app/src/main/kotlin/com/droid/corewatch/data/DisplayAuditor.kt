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
) {
    val activeResolutionLabel: String
        get() = "${activeWidth}×${activeHeight}"

    val activeRefreshLabel: String
        get() = String.format(Locale.US, "%.0f Hz", activeRefreshHz)

    val scalePercent: Int
        get() = ((logicalDpi.toFloat() / 160f) * 100f).roundToInt().coerceIn(50, 400)
}

/**
 * Pure auditor for internal laptop panels and external HDMI / USB-C DisplayPort Alt Mode monitors.
 * Flags 30Hz dock negotiation bottlenecks when high-resolution external monitors run below 50Hz.
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
    ): DisplaySpec {
        val isBuiltIn = displayId == 0
        val cleanName = SysfsParsers.sanitizeSensorName(
            rawName,
            if (isBuiltIn) "Built-in Googlebook Display" else "External Monitor #$displayId",
        )
        val connectionLabel = if (isBuiltIn) "Internal eDP Panel" else "External (USB-C DP Alt Mode / HDMI)"
        val safeWidth = activeWidth.coerceIn(320, 16_384)
        val safeHeight = activeHeight.coerceIn(240, 16_384)
        val safeHz = if (activeRefreshHz.isFinite() && activeRefreshHz > 0f) activeRefreshHz.coerceIn(1f, 480f) else 60f

        val normalizedModes = if (modes.isEmpty()) {
            listOf(DisplayModeSpec(activeModeId, safeWidth, safeHeight, safeHz, isActive = true))
        } else {
            modes.sortedWith(
                compareByDescending<DisplayModeSpec> { it.width.toLong() * it.height.toLong() }
                    .thenByDescending { it.refreshRateHz },
            )
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
        )
    }
}
