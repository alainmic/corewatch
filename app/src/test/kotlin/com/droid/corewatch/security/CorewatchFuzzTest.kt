// Copyright 2026 The Corewatch Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.corewatch.security

import com.droid.corewatch.data.BatteryPowerCalculator
import com.droid.corewatch.data.CaffeineConfig
import com.droid.corewatch.data.CaffeineDuration
import com.droid.corewatch.data.CaffeinePolicy
import com.droid.corewatch.data.DiagnosticReportBuilder
import com.droid.corewatch.data.DisplayAuditor
import com.droid.corewatch.data.DisplayModeSpec
import com.droid.corewatch.data.FloatRingBuffer
import com.droid.corewatch.data.SysfsParsers
import com.droid.corewatch.data.SystemTelemetrySnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Property-based fuzz suite for Corewatch's sysfs/procfs parsers, battery wattage calculator,
 * display bottleneck auditor, Caffeine safety rules, and Markdown diagnostic exporter.
 */
class CorewatchFuzzTest {

    private val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_ -:/.\t\n\r|<>`!@#$%^&*()[]{}0\u0000\u0007\u001b"

    private fun randomStr(r: Random, maxLen: Int = 48): String {
        val len = r.nextInt(maxLen + 1)
        return buildString(len) {
            repeat(len) { append(alphabet[r.nextInt(alphabet.length)]) }
        }
    }

    @Test
    fun fuzzParseMemInfo() {
        val r = Random(0x50554C53L)
        val keys = listOf("MemTotal", "MemFree", "MemAvailable", "Buffers", "Cached", "Dirty", "SwapTotal", "SwapFree", "BogusKey")
        repeat(30_000) {
            val lines = (0..r.nextInt(10)).joinToString("\n") {
                if (r.nextInt(4) == 0) {
                    randomStr(r, 40)
                } else {
                    val k = keys[r.nextInt(keys.size)]
                    val v = listOf("-999", "0", "16384000", "999999999999999999999", randomStr(r, 12))[r.nextInt(5)]
                    "$k: $v kB"
                }
            }
            val snap = SysfsParsers.parseMemInfo(lines)
            assertTrue(snap.totalBytes >= 0L)
            assertTrue(snap.availableBytes in 0L..snap.totalBytes)
            assertTrue(snap.usedBytes in 0L..snap.totalBytes)
            assertTrue(snap.usedFraction in 0f..1f && snap.usedFraction.isFinite())
            assertTrue(snap.swapUsedBytes in 0L..snap.swapTotalBytes)
            assertTrue(snap.swapUsedFraction in 0f..1f && snap.swapUsedFraction.isFinite())
        }
    }

    @Test
    fun fuzzParseCpuCores() {
        val r = Random(0x43505546L)
        repeat(20_000) {
            val map = HashMap<Int, Map<String, String>>()
            repeat(r.nextInt(8)) {
                val coreIdx = r.nextInt(-2, 70)
                val attrs = mapOf(
                    "scaling_cur_freq" to listOf("1800000", "-50", "0", "99999999999", randomStr(r, 10))[r.nextInt(5)],
                    "cpuinfo_max_freq" to listOf("4800000", "3400000", "0", "-1", randomStr(r, 10))[r.nextInt(5)],
                    "cpuinfo_min_freq" to listOf("400000", "800000", "9000000", randomStr(r, 10))[r.nextInt(4)],
                    "scaling_governor" to randomStr(r, 30),
                )
                map[coreIdx] = attrs
            }
            val cores = SysfsParsers.parseCpuCores(map)
            for (c in cores) {
                assertTrue(c.coreIndex in 0..255)
                assertTrue(c.maxKhz in 1L..SysfsParsers.MAX_FREQ_KHZ)
                assertTrue(c.curKhz in 0L..c.maxKhz)
                assertTrue(c.minKhz in 0L..c.maxKhz)
                assertTrue(c.utilizationFraction in 0f..1f && c.utilizationFraction.isFinite())
                assertFalse(c.governor.contains('\n') || c.governor.contains('|'))
            }
        }
    }

    @Test
    fun fuzzParseThermalReadings() {
        val r = Random(0x5448524DL)
        repeat(25_000) {
            val zones = List(r.nextInt(5)) {
                randomStr(r, 24) to listOf("45000", "-500000", "200000", "NaN", "Infinity", "38.5", randomStr(r, 12))[r.nextInt(7)]
            }
            val hwmons = List(r.nextInt(4)) {
                Triple(randomStr(r, 20), randomStr(r, 20), listOf("52000", "-999999", "41.2", randomStr(r, 10))[r.nextInt(4)])
            }
            val list = SysfsParsers.parseThermalReadings(zones, hwmons)
            for (reading in list) {
                assertTrue(reading.celsius.isFinite())
                assertTrue(reading.celsius in SysfsParsers.MIN_VALID_CELSIUS..SysfsParsers.MAX_VALID_CELSIUS)
                assertFalse(reading.name.contains('|') || reading.name.contains('\n') || reading.name.contains('\r'))
            }
        }
    }

    @Test
    fun fuzzBatteryPowerCalculator() {
        val r = Random(0x42415454L)
        repeat(25_000) {
            val snap = BatteryPowerCalculator.computeSnapshot(
                levelPercent = r.nextInt(-100, 250),
                isCharging = r.nextBoolean(),
                pluggedType = r.nextInt(-2, 8),
                rawVoltage = listOf(0, -500, 12, 4_200, 11_800, 15_000_000, Int.MAX_VALUE, Int.MIN_VALUE, r.nextInt())[r.nextInt(9)],
                rawCurrentNow = listOf(0L, -850_000L, 3_200_000L, Long.MIN_VALUE, Long.MAX_VALUE, r.nextLong())[r.nextInt(6)],
                rawTemperatureTenthsC = r.nextInt(-1000, 3000),
                cycleCount = r.nextInt(-50, 5000),
                healthCode = r.nextInt(-2, 12),
                energyCounterNwh = listOf(Long.MIN_VALUE, 0L, 45_000_000_000L, Long.MAX_VALUE, r.nextLong())[r.nextInt(5)],
                chargeCounterUah = r.nextInt(-100, 10_000_000),
            )
            assertTrue(snap.levelPercent in 0..100)
            assertTrue(snap.watts.isFinite() && snap.absWatts <= BatteryPowerCalculator.MAX_REASONABLE_WATTS)
            assertTrue(snap.temperatureCelsius.isFinite())
            assertTrue(snap.cycleCount >= 0)
            snap.estimatedRemainingHours?.let { h ->
                assertTrue(h.isFinite() && h in 0.05f..72f)
            }
        }
    }

    @Test
    fun fuzzCaffeinePolicyAndRingBuffer() {
        val r = Random(0x43414646L)
        val ring = FloatRingBuffer(60)
        repeat(15_000) {
            ring.push(if (r.nextInt(10) == 0) Float.NaN else r.nextFloat() * 1000f - 500f)
            assertTrue(ring.average().isFinite())
            val cfg = CaffeineConfig(
                duration = CaffeineDuration.entries[r.nextInt(CaffeineDuration.entries.size)],
                activatedAtElapsedMs = r.nextLong(),
                autoOnAcCharging = r.nextBoolean(),
                autoOnExternalDisplay = r.nextBoolean(),
                lowBatteryCutoffPercent = r.nextInt(-20, 120),
            )
            val batt = r.nextInt(-10, 120)
            val charging = r.nextBoolean()
            val eval = CaffeinePolicy.evaluate(
                config = cfg,
                batteryPercent = batt,
                isCharging = charging,
                externalDisplayCount = r.nextInt(-2, 6),
                nowElapsedMs = r.nextLong(),
            )
            val effectiveCutoff = cfg.lowBatteryCutoffPercent.coerceIn(5, 50)
            if (!charging && batt.coerceIn(0, 100) < effectiveCutoff) {
                assertFalse("Discharging below cutoff must never stay active", eval.isActive)
            }
        }
    }

    @Test
    fun fuzzDisplayAuditorAndDiagnosticMarkdownEscaping() {
        val r = Random(0x44495350L)
        repeat(10_000) {
            val disp = DisplayAuditor.auditDisplay(
                displayId = r.nextInt(0, 5),
                rawName = randomStr(r, 40),
                activeModeId = r.nextInt(1, 5),
                activeWidth = r.nextInt(-100, 10000),
                activeHeight = r.nextInt(-100, 10000),
                activeRefreshHz = if (r.nextInt(8) == 0) Float.NaN else r.nextFloat() * 240f,
                logicalDpi = r.nextInt(-50, 800),
                physicalXDpi = r.nextFloat() * 400f,
                physicalYDpi = r.nextFloat() * 400f,
                hdrTypeIds = IntArray(r.nextInt(5)) { r.nextInt(-1, 7) },
                modes = listOf(DisplayModeSpec(1, 3840, 2160, 60f, false)),
            )
            val snap = SystemTelemetrySnapshot(
                deviceBoard = randomStr(r, 30),
                deviceModel = randomStr(r, 30),
                osVersion = randomStr(r, 30),
                cpuAbi = randomStr(r, 20),
                displays = listOf(disp),
            )
            val md = DiagnosticReportBuilder.buildMarkdownReport(snap)
            assertTrue(md.startsWith("### Corewatch Hardware & Thermal Diagnostic Snapshot"))
            // Every table row must have balanced pipe delimiters
            val tableLines = md.lines().filter { it.startsWith("|") }
            assertEqualsCount(10, tableLines.size)
            for (line in tableLines) {
                assertEqualsCount(3, line.count { it == '|' })
            }
        }
    }

    private fun assertEqualsCount(expected: Int, actual: Int) {
        org.junit.Assert.assertEquals(expected, actual)
    }
}
