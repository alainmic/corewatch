// Copyright 2026 The Corewatch Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.corewatch.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CorewatchDataUnitTest {

    @Test
    fun floatRingBufferHandlesWrapAroundAndNonFiniteValues() {
        val buf = FloatRingBuffer(capacity = 4)
        assertEquals(0, buf.size)
        assertNull(buf.latestOrNull())

        buf.push(10f)
        buf.push(20f)
        buf.push(30f)
        buf.push(40f)
        assertEquals(listOf(10f, 20f, 30f, 40f), buf.toList())
        assertEquals(10f, buf.minOrNull()!!, 0.001f)
        assertEquals(40f, buf.maxOrNull()!!, 0.001f)
        assertEquals(25f, buf.average(), 0.001f)

        // Wrap around
        buf.push(50f)
        assertEquals(listOf(20f, 30f, 40f, 50f), buf.toList())
        assertEquals(50f, buf.latest(), 0.001f)

        // NaN and Infinity are sanitized to 0f
        buf.push(Float.NaN)
        buf.push(Float.POSITIVE_INFINITY)
        assertTrue(buf.toList().all { it.isFinite() })
        assertEquals(0f, buf.latest(), 0.001f)

        buf.clear()
        assertEquals(0, buf.size)
    }

    @Test
    fun parseCpuCoresClassifiesPerformanceAndEfficiencyClusters() {
        val raw = mapOf(
            0 to mapOf("scaling_cur_freq" to "2400000", "cpuinfo_max_freq" to "4800000", "cpuinfo_min_freq" to "800000", "scaling_governor" to "schedutil"),
            1 to mapOf("scaling_cur_freq" to "4800000", "cpuinfo_max_freq" to "4800000", "cpuinfo_min_freq" to "800000", "scaling_governor" to "performance"),
            2 to mapOf("scaling_cur_freq" to "1200000", "cpuinfo_max_freq" to "3400000", "cpuinfo_min_freq" to "400000", "scaling_governor" to "schedutil"),
            3 to mapOf("scaling_cur_freq" to "1700000", "cpuinfo_max_freq" to "3400000", "cpuinfo_min_freq" to "400000", "scaling_governor" to "powersave"),
        )
        val cores = SysfsParsers.parseCpuCores(raw)
        assertEquals(4, cores.size)
        assertEquals(CoreCluster.Performance, cores[0].cluster)
        assertEquals(CoreCluster.Performance, cores[1].cluster)
        assertEquals(CoreCluster.Efficiency, cores[2].cluster)
        assertEquals(CoreCluster.Efficiency, cores[3].cluster)
        assertEquals(2400, cores[0].curMhz)
        assertEquals(4800, cores[0].maxMhz)
        assertEquals(0.5f, cores[0].utilizationFraction, 0.01f)
    }

    @Test
    fun parseThermalReadingsHandlesMillidegreesAndFiltersBogusValues() {
        val zones = listOf(
            "x86_pkg_temp" to "54500",
            "acpitz" to "42000",
            "bogus_disconnected" to "-273000",
            "bogus_hot" to "250000",
        )
        val hwmons = listOf(
            Triple("coretemp", "Package id 0", "56000"),
            Triple("nvme", "Composite", "39500"),
        )
        val readings = SysfsParsers.parseThermalReadings(zones, hwmons)
        assertEquals(4, readings.size)
        assertEquals("x86_pkg_temp", readings[0].name)
        assertEquals(54.5f, readings[0].celsius, 0.01f)
        assertEquals("coretemp (Package id 0)", readings[2].name)
        assertEquals(56.0f, readings[2].celsius, 0.01f)
    }

    @Test
    fun parseMemInfoComputesRamAndZramSwapPressure() {
        val sample = """
            MemTotal:       16384000 kB
            MemFree:         2048000 kB
            MemAvailable:    6144000 kB
            Buffers:          512000 kB
            Cached:          3584000 kB
            Dirty:             16384 kB
            SwapTotal:       8192000 kB
            SwapFree:        6144000 kB
        """.trimIndent()
        val mem = SysfsParsers.parseMemInfo(sample)
        assertEquals(16384000L * 1024L, mem.totalBytes)
        assertEquals(6144000L * 1024L, mem.availableBytes)
        assertEquals(10240000L * 1024L, mem.usedBytes)
        assertEquals(0.625f, mem.usedFraction, 0.001f)
        assertEquals(2048000L * 1024L, mem.swapUsedBytes)
        assertEquals(0.25f, mem.swapUsedFraction, 0.001f)
    }

    @Test
    fun batteryPowerCalculatorComputesUsbPdChargingAndDischargingWattage() {
        // 15.0V (15000 mV) * 3.0A (3000000 uA) = +45.0 W charging
        val charging = BatteryPowerCalculator.computeSnapshot(
            levelPercent = 60,
            isCharging = true,
            pluggedType = 1,
            rawVoltage = 15_000,
            rawCurrentNow = 3_000_000L,
            rawTemperatureTenthsC = 335,
            cycleCount = 88,
            healthCode = 2,
        )
        assertTrue(charging.isCharging)
        assertEquals(45.0f, charging.watts, 0.1f)
        assertEquals(PowerSource.UsbPd, charging.powerSource)
        assertTrue(charging.statusSummary.contains("+45.0 W"))
        assertTrue(charging.statusSummary.contains("Charging via USB-C PD / AC"))

        // 11.4V (11400 mV) * 0.75A (-750000 uA) = -8.6 W discharging
        val discharging = BatteryPowerCalculator.computeSnapshot(
            levelPercent = 80,
            isCharging = false,
            pluggedType = 0,
            rawVoltage = 11_400,
            rawCurrentNow = -750_000L,
            rawTemperatureTenthsC = 295,
            cycleCount = 88,
            healthCode = 2,
        )
        assertFalse(discharging.isCharging)
        assertEquals(-8.6f, discharging.watts, 0.1f)
        assertNotNull(discharging.estimatedRemainingHours)
        assertTrue(discharging.statusSummary.contains("-8.6 W"))
        assertTrue(discharging.statusSummary.contains("Discharging"))
    }

    @Test
    fun displayAuditorDetects30HzExternalMonitorBottleneck() {
        val internalPanel = DisplayAuditor.auditDisplay(
            displayId = 0,
            rawName = "Built-in Screen",
            activeModeId = 1,
            activeWidth = 2560,
            activeHeight = 1600,
            activeRefreshHz = 120f,
            logicalDpi = 240,
            physicalXDpi = 226f,
            physicalYDpi = 226f,
            hdrTypeIds = intArrayOf(2, 3),
            modes = listOf(DisplayModeSpec(1, 2560, 1600, 120f, true)),
        )
        assertTrue(internalPanel.isBuiltIn)
        assertNull(internalPanel.bottleneckWarning)
        assertEquals(listOf("HDR10", "HLG"), internalPanel.hdrFormats)

        val external4k30 = DisplayAuditor.auditDisplay(
            displayId = 2,
            rawName = "Dell U2723QE",
            activeModeId = 2,
            activeWidth = 3840,
            activeHeight = 2160,
            activeRefreshHz = 30f,
            logicalDpi = 160,
            physicalXDpi = 163f,
            physicalYDpi = 163f,
            hdrTypeIds = intArrayOf(2),
            modes = listOf(
                DisplayModeSpec(1, 3840, 2160, 60f, false),
                DisplayModeSpec(2, 3840, 2160, 30f, true),
            ),
        )
        assertFalse(external4k30.isBuiltIn)
        val warn = external4k30.bottleneckWarning
        assertNotNull(warn)
        assertTrue(warn!!.contains("30Hz"))
        assertTrue(warn.contains("60Hz"))
    }

    @Test
    fun caffeinePolicyEnforcesTimersAutoTriggersAndLowBatteryCutoff() {
        val cfgTimed = CaffeineConfig(duration = CaffeineDuration.Min30, activatedAtElapsedMs = 1_000L)
        val activeTimed = CaffeinePolicy.evaluate(
            config = cfgTimed,
            batteryPercent = 50,
            isCharging = false,
            externalDisplayCount = 0,
            nowElapsedMs = 1_000L + 10L * 60_000L,
        )
        assertTrue(activeTimed.isActive)
        assertEquals(20, activeTimed.remainingMinutes)

        // Low battery (< 15%) while discharging pauses Keep-Awake automatically
        val cutoffEval = CaffeinePolicy.evaluate(
            config = cfgTimed,
            batteryPercent = 12,
            isCharging = false,
            externalDisplayCount = 1,
            nowElapsedMs = 1_000L + 10L * 60_000L,
        )
        assertFalse(cutoffEval.isActive)
        assertTrue(cutoffEval.cutoffTriggered)
        assertTrue(cutoffEval.activeReason.contains("Battery < 15%"))

        // Auto-trigger on external display when battery >= 15%
        val autoExtCfg = CaffeineConfig(duration = CaffeineDuration.Off, autoOnExternalDisplay = true)
        val autoExtEval = CaffeinePolicy.evaluate(
            config = autoExtCfg,
            batteryPercent = 40,
            isCharging = false,
            externalDisplayCount = 1,
            nowElapsedMs = 5_000L,
        )
        assertTrue(autoExtEval.isActive)
        assertEquals("Auto (External Display)", autoExtEval.activeReason)
    }

    @Test
    fun diagnosticReportBuilderProducesSanitizedMarkdownTable() {
        val dispWarn = DisplayAuditor.auditDisplay(
            displayId = 2,
            rawName = "External 4K",
            activeModeId = 2,
            activeWidth = 3840,
            activeHeight = 2160,
            activeRefreshHz = 30f,
            logicalDpi = 160,
            physicalXDpi = 160f,
            physicalYDpi = 160f,
            hdrTypeIds = intArrayOf(1, 2, 3, 4),
            modes = listOf(
                DisplayModeSpec(1, 3840, 2160, 60f, false),
                DisplayModeSpec(2, 3840, 2160, 30f, true),
            ),
        )
        val snap = SystemTelemetrySnapshot(
            deviceBoard = "googlebook|evil\nline",
            deviceModel = "Acer Googlebook 14",
            cpuCores = listOf(
                CpuCoreReading(0, 3_600_000L, 400_000L, 4_800_000L, "schedutil", CoreCluster.Performance),
                CpuCoreReading(4, 1_800_000L, 400_000L, 3_500_000L, "schedutil", CoreCluster.Efficiency),
            ),
            thermalSensors = listOf(
                ThermalSensorReading("tz0", "CPU Package", 64.2f, "thermal_zone"),
            ),
            displays = listOf(dispWarn),
        )
        val md = DiagnosticReportBuilder.buildMarkdownReport(snap)
        assertTrue(md.contains("### Corewatch Hardware & Thermal Diagnostic Snapshot"))
        assertTrue(md.contains("Acer Googlebook 14"))
        assertFalse(md.contains("googlebook|evil"))
        assertTrue(md.contains("googlebook_evil line"))
        assertTrue(md.contains("2 cores (1P + 1E)"))
        assertTrue(md.contains("CPU Package: 64.2 °C"))
        assertTrue(md.contains("30Hz Bottleneck"))
    }

    @Test
    fun thermalLevelAndPowerSourceAndBatteryHealthMappingsCoverAllBranches() {
        for (code in -1..8) {
            val level = ThermalLevel.fromStatusCode(code)
            assertTrue(level.label.isNotEmpty())
            assertTrue(level.severityFraction in 0f..1f)
        }
        for (plug in listOf(0, 1, 2, 4, 8, 99)) {
            val snap = BatteryPowerCalculator.computeSnapshot(
                levelPercent = 60,
                isCharging = plug != 0,
                pluggedType = plug,
                rawVoltage = 11_400,
                rawCurrentNow = 2_000_000L,
                rawTemperatureTenthsC = 300,
                cycleCount = 10,
                healthCode = 2,
            )
            assertTrue(snap.powerSource.label.isNotEmpty())
        }
        for (h in 1..8) {
            val lbl = BatteryPowerCalculator.healthLabelOf(h)
            assertTrue(lbl.isNotEmpty())
        }
        // Timer expired branch in CaffeinePolicy
        val expiredEval = CaffeinePolicy.evaluate(
            config = CaffeineConfig(duration = CaffeineDuration.Min30, activatedAtElapsedMs = 1_000L),
            batteryPercent = 80,
            isCharging = true,
            externalDisplayCount = 0,
            nowElapsedMs = 1_000L + 45L * 60_000L,
        )
        assertFalse(expiredEval.isActive)
        assertEquals("Timer completed", expiredEval.activeReason)

        // AC + External display combined auto-trigger branch
        val bothAuto = CaffeinePolicy.evaluate(
            config = CaffeineConfig(duration = CaffeineDuration.Off, autoOnAcCharging = true, autoOnExternalDisplay = true),
            batteryPercent = 80,
            isCharging = true,
            externalDisplayCount = 1,
            nowElapsedMs = 10_000L,
        )
        assertTrue(bothAuto.isActive)
        assertEquals("Auto (AC + External Display)", bothAuto.activeReason)

        // Toggle quick cycle Off -> Indefinite -> Off
        val toggledOn = CaffeinePolicy.toggleQuick(CaffeineConfig(duration = CaffeineDuration.Off), 5_000L)
        assertEquals(CaffeineDuration.Indefinite, toggledOn.duration)
        val toggledOff = CaffeinePolicy.toggleQuick(toggledOn, 6_000L)
        assertEquals(CaffeineDuration.Off, toggledOff.duration)
    }

    @Test
    fun parseGpuAndNpuTelemetryAndAccelIoctlBridgeCoverAllStates() {
        val idleGpu = SysfsParsers.parseGpuTelemetry(
            attrs = mapOf(
                "act_freq" to "0",
                "cur_freq" to "800",
                "min_freq" to "800",
                "max_freq" to "2500",
                "rp0_freq" to "2500",
                "rpe_freq" to "850",
                "rpn_freq" to "100",
                "power_profile" to "[base]    power_saving",
                "throttle_status" to "0",
            ),
            driverName = "xe",
            deviceName = "Intel Xe2 Graphics",
            activeResidencyFraction = 0.01f,
        )
        assertEquals("rc6-sleep", idleGpu.scalingStateLabel)
        assertEquals(800, idleGpu.displayFreqMhz)
        assertEquals("base", idleGpu.powerProfile)
        assertFalse(idleGpu.isThrottled)
        assertEquals("Nominal", idleGpu.throttleSummary)

        val throttledGpu = SysfsParsers.parseGpuTelemetry(
            attrs = mapOf(
                "act_freq" to "1800000",
                "cur_freq" to "2000000",
                "max_freq" to "2500000",
                "throttle_status" to "1",
                "reason_pl1" to "1",
                "reason_thermal" to "1",
            ),
            driverName = "xe",
            deviceName = "Intel Xe2 Graphics",
            activeResidencyFraction = null,
        )
        assertTrue(throttledGpu.isThrottled)
        assertEquals("throttled", throttledGpu.scalingStateLabel)
        assertEquals("PL1, THERMAL", throttledGpu.throttleSummary)
        assertEquals(0.72f, throttledGpu.utilizationFraction, 0.02f)

        val drmVer = AccelIoctlBridge.parseDrmVersionString("intel_vpu|Driver for Intel NPU|1.0.0")
        assertNotNull(drmVer)
        assertEquals("intel_vpu", drmVer!!.driverName)
        assertNull(AccelIoctlBridge.parseDrmVersionString("malformed"))

        val ivpuParams = AccelIoctlBridge.parseIvpuParamsArray(
            longArrayOf(0xB03EL, 7L, 2_000_000_000L, 64L, 0x00030025L, 42L, 3L, 0L),
        )
        assertNotNull(ivpuParams)
        assertEquals("0xB03E", ivpuParams!!.deviceIdHex)
        assertEquals(2000, ivpuParams.maxClockMhz)
        assertEquals("v3.37", ivpuParams.firmwareVersionLabel)
        assertNull(AccelIoctlBridge.parseIvpuParamsArray(longArrayOf(1L, 2L)))

        val idleNpu = SysfsParsers.parseNpuTelemetry(
            attrs = emptyMap(),
            drmVersion = drmVer,
            ivpuParams = ivpuParams,
            busyDeltaFraction = 0f,
            heartbeatActive = false,
        )
        assertEquals("d3cold-sleep", idleNpu.scalingStateLabel)
        assertEquals(2000, idleNpu.maxFreqMhz)
        assertEquals(0, idleNpu.currentFreqMhz)

        val activeNpu = SysfsParsers.parseNpuTelemetry(
            attrs = mapOf(
                "npu_current_frequency_mhz" to "1650",
                "npu_max_frequency_mhz" to "2000",
                "npu_memory_utilization" to "67108864",
            ),
            drmVersion = drmVer,
            ivpuParams = ivpuParams,
            busyDeltaFraction = 0.75f,
            heartbeatActive = true,
        )
        assertEquals("turbo", activeNpu.scalingStateLabel)
        assertEquals(1650, activeNpu.currentFreqMhz)
        assertEquals(0.75f, activeNpu.utilizationFraction, 0.01f)
    }
}
