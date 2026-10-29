// Copyright 2026 The Corewatch Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.corewatch.data

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

enum class PowerSource(val label: String) {
    UsbPd("USB-C PD / AC"),
    UsbPort("USB Port"),
    Wireless("Wireless"),
    Discharging("Battery"),
}

data class BatterySnapshot(
    val levelPercent: Int,
    val isCharging: Boolean,
    val powerSource: PowerSource,
    val voltageMv: Int,
    val currentMa: Float,
    /** Signed power flow in Watts (`+W` when charging, `-W` when discharging). */
    val watts: Float,
    val temperatureCelsius: Float,
    val cycleCount: Int,
    val healthLabel: String,
    val estimatedRemainingHours: Float?,
    val statusSummary: String,
) {
    val absWatts: Float get() = abs(watts)
}

/**
 * Pure calculator for battery voltage (`mV`), current (`µA` / `mA`), wattage (`W = V × I`),
 * charger classification, and remaining charge/discharge time estimates.
 */
object BatteryPowerCalculator {
    const val MAX_REASONABLE_WATTS = 240f

    fun computeSnapshot(
        levelPercent: Int,
        isCharging: Boolean,
        pluggedType: Int,
        rawVoltage: Int,
        rawCurrentNow: Long,
        rawTemperatureTenthsC: Int,
        cycleCount: Int,
        healthCode: Int,
        energyCounterNwh: Long = Long.MIN_VALUE,
        chargeCounterUah: Int = 0,
    ): BatterySnapshot {
        val clampedLevel = levelPercent.coerceIn(0, 100)
        // Android BatteryManager.EXTRA_VOLTAGE is typically mV (e.g. 11400 for 3S laptop packs or 4200 for 1S),
        // though some drivers report µV (> 100_000) or V (< 50).
        val voltageMv = when {
            rawVoltage <= 0 -> 11_400
            rawVoltage < 50 -> rawVoltage * 1_000
            rawVoltage > 100_000 -> rawVoltage / 1_000
            else -> rawVoltage.coerceIn(2_500, 30_000)
        }

        // BATTERY_PROPERTY_CURRENT_NOW is specified in µA, but some HALs report mA directly.
        val absRawCurrent = abs(rawCurrentNow.toDouble())
        val currentMaMagnitude = when {
            rawCurrentNow == Long.MIN_VALUE || absRawCurrent == 0.0 -> 0f
            absRawCurrent >= 25_000.0 -> (absRawCurrent / 1_000.0).toFloat()
            else -> absRawCurrent.toFloat()
        }.coerceIn(0f, 20_000f)

        val rawWatts = (voltageMv.toDouble() / 1_000.0) * (currentMaMagnitude.toDouble() / 1_000.0)
        val clampedAbsWatts = rawWatts.toFloat().coerceIn(0f, MAX_REASONABLE_WATTS)
        val signedWatts = if (clampedAbsWatts < 0.05f) {
            0f
        } else if (isCharging) {
            ((clampedAbsWatts * 10f).roundToInt() / 10f)
        } else {
            -((clampedAbsWatts * 10f).roundToInt() / 10f)
        }

        val signedCurrentMa = if (isCharging) currentMaMagnitude else -currentMaMagnitude
        val tempC = if (rawTemperatureTenthsC in -400..1500) {
            rawTemperatureTenthsC / 10f
        } else {
            25.0f
        }

        val source = when {
            !isCharging -> PowerSource.Discharging
            pluggedType == 1 -> PowerSource.UsbPd // BATTERY_PLUGGED_AC
            pluggedType == 2 -> if (clampedAbsWatts >= 15f) PowerSource.UsbPd else PowerSource.UsbPort // BATTERY_PLUGGED_USB
            pluggedType == 4 -> PowerSource.Wireless
            else -> PowerSource.UsbPd
        }

        val estHours = estimateRemainingHours(
            levelPercent = clampedLevel,
            isCharging = isCharging,
            absWatts = abs(signedWatts),
            voltageMv = voltageMv,
            energyCounterNwh = energyCounterNwh,
            chargeCounterUah = chargeCounterUah,
        )

        val formattedWatts = String.format(Locale.US, "%+.1f W", signedWatts)
        val statusSummary = when {
            isCharging && clampedLevel >= 100 -> "Full (${source.label})"
            isCharging -> {
                val timePart = estHours?.let { " — Est. ${formatHours(it)} to full" }.orEmpty()
                "$formattedWatts (Charging via ${source.label}$timePart)"
            }
            else -> {
                val timePart = estHours?.let { " — Est. ${formatHours(it)} remaining" }.orEmpty()
                "$formattedWatts (Discharging$timePart)"
            }
        }

        return BatterySnapshot(
            levelPercent = clampedLevel,
            isCharging = isCharging,
            powerSource = source,
            voltageMv = voltageMv,
            currentMa = signedCurrentMa,
            watts = signedWatts,
            temperatureCelsius = tempC,
            cycleCount = cycleCount.coerceAtLeast(0),
            healthLabel = healthLabelOf(healthCode),
            estimatedRemainingHours = estHours,
            statusSummary = statusSummary,
        )
    }

    private fun estimateRemainingHours(
        levelPercent: Int,
        isCharging: Boolean,
        absWatts: Float,
        voltageMv: Int,
        energyCounterNwh: Long,
        chargeCounterUah: Int,
    ): Float? {
        if (absWatts < 0.5f) return null
        val currentWh: Float = when {
            energyCounterNwh > 0L && energyCounterNwh != Long.MIN_VALUE ->
                (energyCounterNwh.toDouble() / 1_000_000_000.0).toFloat()
            chargeCounterUah > 0 ->
                ((chargeCounterUah.toDouble() / 1_000_000.0) * (voltageMv.toDouble() / 1_000.0)).toFloat()
            else -> (55f * (levelPercent / 100f)) // Typical 55 Wh Googlebook pack fallback
        }.coerceIn(0f, 150f)

        val hours = if (isCharging) {
            if (levelPercent <= 0 || levelPercent >= 100) return null
            val fullWh = (currentWh / (levelPercent / 100f)).coerceAtMost(150f)
            (fullWh - currentWh).coerceAtLeast(0f) / absWatts
        } else {
            currentWh / absWatts
        }
        return hours.takeIf { it.isFinite() && it in 0.05f..72f }
    }

    fun formatHours(hours: Float): String {
        if (!hours.isFinite() || hours <= 0f) return "0m"
        val totalMin = (hours * 60f).roundToInt().coerceIn(1, 72 * 60)
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    fun healthLabelOf(code: Int): String = when (code) {
        2 -> "Good"
        3 -> "Overheat"
        4 -> "Dead"
        5 -> "Overvoltage"
        6 -> "Unspecified Failure"
        7 -> "Cold"
        else -> "Good"
    }
}
