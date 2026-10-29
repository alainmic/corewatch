// Copyright 2026 The Corewatch Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.corewatch.data

enum class CaffeineDuration(val label: String, val durationMs: Long?) {
    Off("Off", null),
    Min30("30m", 30L * 60_000L),
    Hour1("1h", 60L * 60_000L),
    Indefinite("Indefinite", Long.MAX_VALUE),
}

data class CaffeineConfig(
    val duration: CaffeineDuration = CaffeineDuration.Off,
    val activatedAtElapsedMs: Long = 0L,
    val autoOnAcCharging: Boolean = false,
    val autoOnExternalDisplay: Boolean = false,
    val lowBatteryCutoffPercent: Int = DEFAULT_LOW_BATTERY_CUTOFF,
) {
    companion object {
        const val DEFAULT_LOW_BATTERY_CUTOFF = 15
    }
}

data class CaffeineEvaluation(
    val isActive: Boolean,
    val activeReason: String,
    val remainingMinutes: Int?,
    val cutoffTriggered: Boolean,
)

/**
 * Pure rule evaluator for the "Caffeine" Keep-Awake controller.
 * Enforces the automatic `< 15%` low-battery safety cutoff whenever discharging.
 */
object CaffeinePolicy {
    fun evaluate(
        config: CaffeineConfig,
        batteryPercent: Int,
        isCharging: Boolean,
        externalDisplayCount: Int,
        nowElapsedMs: Long,
    ): CaffeineEvaluation {
        val safeBattery = batteryPercent.coerceIn(0, 100)
        val cutoff = config.lowBatteryCutoffPercent.coerceIn(5, 50)

        val manualRemainingMs: Long? = when (config.duration) {
            CaffeineDuration.Off -> null
            CaffeineDuration.Indefinite -> Long.MAX_VALUE
            CaffeineDuration.Min30, CaffeineDuration.Hour1 -> {
                val dur = config.duration.durationMs ?: 0L
                val elapsed = (nowElapsedMs - config.activatedAtElapsedMs).coerceAtLeast(0L)
                (dur - elapsed).takeIf { it > 0L }
            }
        }

        val wantsManual = manualRemainingMs != null
        val wantsAc = config.autoOnAcCharging && isCharging
        val wantsExtDisplay = config.autoOnExternalDisplay && externalDisplayCount > 0

        if (!wantsManual && !wantsAc && !wantsExtDisplay) {
            val expired = config.duration != CaffeineDuration.Off
            return CaffeineEvaluation(
                isActive = false,
                activeReason = if (expired) "Timer completed" else "Off",
                remainingMinutes = null,
                cutoffTriggered = false,
            )
        }

        // Safety guard: never hold wake lock on a discharging battery below the safety cutoff.
        if (!isCharging && safeBattery < cutoff) {
            return CaffeineEvaluation(
                isActive = false,
                activeReason = "Paused (Battery < $cutoff%)",
                remainingMinutes = null,
                cutoffTriggered = true,
            )
        }

        val remMin = if (manualRemainingMs != null && manualRemainingMs != Long.MAX_VALUE) {
            ((manualRemainingMs + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
        } else {
            null
        }

        val reason = when {
            wantsManual && remMin != null -> "Active (${remMin}m left)"
            wantsManual -> "Active (Indefinite)"
            wantsAc && wantsExtDisplay -> "Auto (AC + External Display)"
            wantsAc -> "Auto (On AC Power)"
            else -> "Auto (External Display)"
        }

        return CaffeineEvaluation(
            isActive = true,
            activeReason = reason,
            remainingMinutes = remMin,
            cutoffTriggered = false,
        )
    }

    /** Cycles between Off -> Indefinite -> 30m -> 1h -> Off for quick caption-bar/tile toggles. */
    fun toggleQuick(current: CaffeineConfig, nowElapsedMs: Long): CaffeineConfig =
        if (current.duration == CaffeineDuration.Off) {
            current.copy(duration = CaffeineDuration.Indefinite, activatedAtElapsedMs = nowElapsedMs)
        } else {
            current.copy(duration = CaffeineDuration.Off, activatedAtElapsedMs = 0L)
        }
}
