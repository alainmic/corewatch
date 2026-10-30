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

        val drmConnectors = AccelIoctlBridge.parseDrmConnectorString("eDP-1|1|0|12|300x190;DP-1|1|1|10|600x340")
        assertEquals(2, drmConnectors.size)
        assertEquals("eDP-1", drmConnectors[0].connectorName)
        assertTrue(drmConnectors[0].vrrCapable)
        assertFalse(drmConnectors[0].vrrKernelEnabled)
        assertEquals(12, drmConnectors[0].maxBpc)
        assertEquals("300x190", drmConnectors[0].physicalSizeMm)
        assertTrue(drmConnectors[1].vrrKernelEnabled)

        val vrrDisplay = DisplayAuditor.auditDisplay(
            displayId = 0,
            rawName = "Built-in Screen",
            activeModeId = 1,
            activeWidth = 2880,
            activeHeight = 1800,
            activeRefreshHz = 120f,
            logicalDpi = 240,
            physicalXDpi = 242f,
            physicalYDpi = 240f,
            hdrTypeIds = intArrayOf(2),
            modes = listOf(DisplayModeSpec(1, 2880, 1800, 120f, isActive = true)),
            renderFrameRateHz = 60f,
            vsyncRateHz = 120f,
            supportedRefreshRatesHz = floatArrayOf(120f, 60f, 40f, 30f, 24f, 20f),
            vrrCapable = true,
            vrrKernelEnabled = false,
            hasAndroidArr = false,
            maxBpc = 12,
            panelModel = "BOE NB140B91-ML1",
            drmConnectorName = "eDP-1",
        )
        assertTrue(vrrDisplay.isVrrSupported)
        assertEquals("Dynamic Cadence (60/120 Hz)", vrrDisplay.vrrStatusBadge)
        assertEquals("20–120 Hz (20, 24, 30, 40, 60, 120 Hz)", vrrDisplay.vrrRangeSummary)
        assertEquals("Render 60 Hz · VSync 120 Hz", vrrDisplay.liveCadenceSummary)
        assertEquals(12, vrrDisplay.maxBpc)
        assertEquals("BOE NB140B91-ML1", vrrDisplay.panelModel)

        val cpuModel = SysfsParsers.parseCpuModelName(
            "processor\t: 0\nmodel name\t: Intel(R) Core(TM) Ultra 7 355\n",
            "x86_64",
        )
        assertEquals("Intel Core Ultra 7 355", cpuModel)

        val (somePsi, fullPsi) = SysfsParsers.parsePsiMemoryAvg10(
            "some avg10=1.25 avg60=0.40 avg300=0.10 total=12345\nfull avg10=0.50 avg60=0.10 avg300=0.00 total=2345\n",
        )
        assertEquals(1.25f, somePsi, 0.01f)
        assertEquals(0.50f, fullPsi, 0.01f)

        assertEquals(0.42f, SysfsParsers.parseQualcommKgslGpuBusy("420 1000")!!, 0.01f)
        assertEquals(0.65f, SysfsParsers.parseQualcommKgslGpuBusy("65 %")!!, 0.01f)

        val tracker = VrrAndFrameWindowTracker(windowHorizonMs = 300_000L)
        val steps = listOf(20f, 24f, 30f, 40f, 60f, 120f)
        tracker.recordDisplayRefreshSample(1_000L, 120f, steps)
        val (buckets, switches) = tracker.recordDisplayRefreshSample(2_000L, 60f, steps)
        assertEquals(2, buckets.size)
        assertEquals(1, switches)
        assertEquals(120, buckets[0].hz)
        assertEquals(60, buckets[1].hz)

        // Frame metrics: 1 smooth frame (6ms vs 8.33ms deadline) + 1 janky/dropped frame (20ms vs 8.33ms deadline)
        tracker.recordFrameMetric(
            nowElapsedMs = 2_000L,
            totalDurationNs = 6_000_000L,
            deadlineNs = 8_333_333L,
            gpuDurationNs = 2_000_000L,
            intendedVsyncNs = 100_000_000L,
            vsyncNs = 100_000_000L,
        )
        tracker.recordFrameMetric(
            nowElapsedMs = 2_200L,
            totalDurationNs = 20_000_000L,
            deadlineNs = 16_666_666L,
            gpuDurationNs = 3_000_000L,
            intendedVsyncNs = 120_000_000L,
            vsyncNs = 140_000_000L,
        )
        val frameSnap = tracker.snapshotFrames(2_500L, defaultHz = 120)
        assertEquals(2, frameSnap.evaluatedFrames5m)
        assertEquals(1, frameSnap.jankyFrames5m)
        assertEquals(1, frameSnap.droppedFrames5m)
        assertEquals(1, frameSnap.longCpuJank5m)
        assertEquals("[60, 120] Hz", frameSnap.deadlineRangeLabel)

        // Isolated 1Hz telemetry wakeup frame (1,000ms after previous frame, 12.5ms RC6/PSR wakeup, 0ms VSync slip)
        // should NOT be flagged as jank.
        tracker.recordFrameMetric(
            nowElapsedMs = 3_200L,
            totalDurationNs = 12_500_000L,
            deadlineNs = 8_333_333L,
            gpuDurationNs = 8_000_000L,
            intendedVsyncNs = 1_140_000_000L,
            vsyncNs = 1_140_000_000L,
            cpuStagesDurationNs = 4_500_000L,
        )
        val afterIdleWakeup = tracker.snapshotFrames(3_500L, defaultHz = 120)
        assertEquals(3, afterIdleWakeup.evaluatedFrames5m)
        assertEquals(1, afterIdleWakeup.jankyFrames5m)
    }

    @Test
    fun wirelessTelemetryParsersFormatWifi7BandChannelRssiAndBluetoothCodecs() {
        assertEquals("Wi-Fi 7 (802.11be)", WirelessTelemetryParsers.formatWifiStandard(8))
        assertEquals("Wi-Fi 6/6E (802.11ax)", WirelessTelemetryParsers.formatWifiStandard(6))
        assertEquals("Wi-Fi 5 (802.11ac)", WirelessTelemetryParsers.formatWifiStandard(5))

        assertEquals("5 GHz · Ch 149 (5745 MHz)", WirelessTelemetryParsers.formatBandAndChannel(5745))
        assertEquals("6 GHz · Ch 33 (6115 MHz)", WirelessTelemetryParsers.formatBandAndChannel(6115))
        assertEquals("2.4 GHz · Ch 6 (2437 MHz)", WirelessTelemetryParsers.formatBandAndChannel(2437))

        assertEquals(100, WirelessTelemetryParsers.computeRssiQualityPercent(-42))
        assertEquals(45, WirelessTelemetryParsers.computeRssiQualityPercent(-75))
        assertEquals(0, WirelessTelemetryParsers.computeRssiQualityPercent(-127))
        assertEquals("WPA3-SAE", WirelessTelemetryParsers.formatWifiSecurityType(4))

        assertEquals("SBC", WirelessTelemetryParsers.formatBluetoothCodecType(0))
        assertEquals("AAC", WirelessTelemetryParsers.formatBluetoothCodecType(1))
        assertEquals("LDAC", WirelessTelemetryParsers.formatBluetoothCodecType(4))
        assertEquals("LC3 (LE Audio)", WirelessTelemetryParsers.formatBluetoothCodecType(5))
        assertEquals("Opus", WirelessTelemetryParsers.formatBluetoothCodecType(6))
        assertEquals("48.0 kHz", WirelessTelemetryParsers.formatBluetoothSampleRate(0x2))
        assertEquals("24-bit", WirelessTelemetryParsers.formatBluetoothBitsPerSample(0x2))
        assertEquals("Stereo", WirelessTelemetryParsers.formatBluetoothChannelMode(0x2))
        assertEquals("990 kbps (High Quality)", WirelessTelemetryParsers.formatBluetoothBitrateQuality(4, 1000L))
        assertEquals("Mouse / Pointing", WirelessTelemetryParsers.formatPeripheralCategory(0x0500, 0x0580, "MX Master 3 B"))
        assertEquals("Audio / Headphones", WirelessTelemetryParsers.formatPeripheralCategory(0x0400, 0x0404, "Pixel Buds Pro 2"))
        assertEquals("Pixel Buds Pro 2", WirelessTelemetryParsers.sanitizePeripheralDisplayName("User's Pixel Buds Pro 2"))
        assertEquals("Wireless Headphones (LE Audio)", WirelessTelemetryParsers.sanitizePeripheralDisplayName("User's Earbuds PVT"))

        val parsedLogCodec = WirelessTelemetryParsers.parseA2dpLogcatCodec(
            logText = """
                I bluetooth-a2dp-aidl: IsCodecOffloadingEnabled: software codec=CodecConfiguration{codecType: SBC, encodedAudioBitrate: 243000, peerMtu: 663, isScmstEnabled: false, config: CodecSpecific{sbcConfig: SbcConfiguration{sampleRateHz: 44100, channelMode: JOINT_STEREO, blockLength: 16, numSubbands: 8, allocMethod: ALLOC_MD_L, bitsPerSample: 16, minBitpool: 2, maxBitpool: 37}}}
                D A2dpService: broadcastCodecConfig: {mCodecConfig:{codecName:SBC,mCodecType:0,mCodecPriority:1001,mSampleRate:0x1(44100),mBitsPerSample:0x1(16),mChannelMode:0x2(STEREO),mCodecSpecific1:0},mCodecsSelectableCapabilities:[{codecName:SBC},{codecName:AAC}]}
            """.trimIndent(),
            activeRouteLabel = "Bluetooth A2DP (Pixel Buds Pro 2)",
            communicationModeLabel = "Normal (Media)",
        )
        assertNotNull(parsedLogCodec)
        assertEquals("SBC", parsedLogCodec!!.activeCodecName)
        assertEquals("44.1 kHz", parsedLogCodec.sampleRateLabel)
        assertEquals("16-bit", parsedLogCodec.bitsPerSampleLabel)
        assertEquals("Joint Stereo", parsedLogCodec.channelModeLabel)
        assertEquals("243 kbps (MTU 663 B)", parsedLogCodec.bitrateOrQualityLabel)
        assertEquals("SBC, AAC", parsedLogCodec.selectableCodecsSummary)

        val dumpsysDropStats = WirelessTelemetryParsers.parseBluetoothAudioDropStats(
            dumpsysText = """
                Profile: HeadsetService
                  mCurrentState: Connected
                  AudioManager.isBluetoothScoOn(): false
                  EVENT_TYPE_WBS[17], valInt=1
                  Trigger dropped counts : 2
                  Packet dropped counts : 3
                Profile: LeAudioService
                  LE AudioHalClient:
                    Stream state: DISABLED
                    Counts (underflow) : 1
                    Bytes (underflow) : 480
                    Credits underflow (count) : 2
                  ISO Manager:
                    Available credits: 8
                    Controller buffer size: 248
                Profile: A2dpService
                  A2DP State:
                    Counts (enqueue/dequeue/readbuf) : 42570 / 42548 / 84906
                    Counts (flushed/dropped/dropouts) : 0 / 22 / 1
                    Counts (max dropped) : 22
                    Counts (underflow) : 0
                    Bytes (underflow) : 0
                    Packet counts (expected/dropped) : 42570 / 0
                    Frames counts (expected/dropped) : 293334 / 4
                    Delay Reporting: 2800 (in 1/10 milliseconds)
            """.trimIndent(),
            logcatText = "",
            isScoOn = false,
            isLeAudioActive = false,
        )
        assertEquals("BT STACK DUMPSYS (LIVE)", dumpsysDropStats.sourceLabel)
        assertEquals(293_334L, dumpsysDropStats.a2dpExpectedFrames)
        assertEquals(4L, dumpsysDropStats.a2dpDroppedFrames)
        assertEquals(22L, dumpsysDropStats.a2dpTxDropped)
        assertEquals(1L, dumpsysDropStats.a2dpTxDropouts)
        assertEquals(280.0f, dumpsysDropStats.a2dpSinkDelayMs, 0.01f)
        assertEquals("Connected (SCO Standby)", dumpsysDropStats.hfpStateLabel)
        assertEquals("WBS (mSBC 16 kHz)", dumpsysDropStats.hfpSpeechCodecLabel)
        assertEquals("5 pkt drops · 0 underflows", dumpsysDropStats.hfpPrimaryCounterLabel)
        assertEquals("3 underflows · 0 ISO drops", dumpsysDropStats.leAudioPrimaryCounterLabel)
        assertEquals(8, dumpsysDropStats.leIsoAvailableCredits)
        assertEquals(248, dumpsysDropStats.leIsoBufferSizeBytes)
    }

    @Test
    fun ethernetSpeedTiersVirtualBridgeFilterRateTrackerAndSplitReportWorkCorrectly() {
        // 1. Virtual VM / bridge / tunnel exclusion vs physical wired NIC acceptance
        assertTrue(WirelessTelemetryParsers.isVirtualBridgeInterface(""))
        assertTrue(WirelessTelemetryParsers.isVirtualBridgeInterface("None"))
        assertTrue(WirelessTelemetryParsers.isVirtualBridgeInterface("lo"))
        assertTrue(WirelessTelemetryParsers.isVirtualBridgeInterface("avf_tap_2052"))
        assertTrue(WirelessTelemetryParsers.isVirtualBridgeInterface("veth42a"))
        assertTrue(WirelessTelemetryParsers.isVirtualBridgeInterface("docker0"))
        assertTrue(WirelessTelemetryParsers.isVirtualBridgeInterface("virbr0"))
        assertTrue(WirelessTelemetryParsers.isVirtualBridgeInterface("tun0"))
        assertTrue(WirelessTelemetryParsers.isVirtualBridgeInterface("wlan0"))
        assertFalse(WirelessTelemetryParsers.isVirtualBridgeInterface("eth0"))
        assertFalse(WirelessTelemetryParsers.isVirtualBridgeInterface("eth1"))
        assertFalse(WirelessTelemetryParsers.isVirtualBridgeInterface("enx00e04c680001"))
        assertFalse(WirelessTelemetryParsers.isVirtualBridgeInterface("usb0"))

        // 2. Negotiated IEEE 802.3 speed tier badges
        assertEquals(0 to "Undocked", WirelessTelemetryParsers.formatEthernetSpeedTier(2_500_000, 2_500_000, isConnected = false))
        assertEquals(10_000 to "10 GbE (10GBASE-T)", WirelessTelemetryParsers.formatEthernetSpeedTier(10_000_000, 10_000_000))
        assertEquals(5_000 to "5 GbE (5GBASE-T)", WirelessTelemetryParsers.formatEthernetSpeedTier(5_000_000, 5_000_000))
        assertEquals(2_500 to "2.5 GbE (2500BASE-T)", WirelessTelemetryParsers.formatEthernetSpeedTier(2_500_000, 2_500_000))
        assertEquals(1_000 to "1 GbE (1000BASE-T)", WirelessTelemetryParsers.formatEthernetSpeedTier(1_000_000, 1_000_000))
        assertEquals(100 to "100 Mbps (100BASE-TX)", WirelessTelemetryParsers.formatEthernetSpeedTier(100_000, 100_000))
        assertEquals(10 to "10 Mbps (10BASE-T)", WirelessTelemetryParsers.formatEthernetSpeedTier(10_000, 10_000))
        assertEquals(0 to "Connected", WirelessTelemetryParsers.formatEthernetSpeedTier(0, 0, isConnected = true))

        // 3. IP stack & DNS summaries (anonymized without raw IP addresses)
        assertEquals("Dual-Stack (IPv4 + IPv6)", WirelessTelemetryParsers.formatIpStackSummary(true, true))
        assertEquals("IPv4", WirelessTelemetryParsers.formatIpStackSummary(true, false))
        assertEquals("IPv6", WirelessTelemetryParsers.formatIpStackSummary(false, true))
        assertEquals("N/A", WirelessTelemetryParsers.formatIpStackSummary(false, false))
        assertEquals("Private DNS (DoT) · 2 Servers", WirelessTelemetryParsers.formatDnsSummary(true, 2, isConnected = true))
        assertEquals("Standard DNS · 1 Server", WirelessTelemetryParsers.formatDnsSummary(false, 1, isConnected = true))
        assertEquals("N/A", WirelessTelemetryParsers.formatDnsSummary(true, 2, isConnected = false))

        // 4. EthernetTelemetrySnapshot computed helpers & 100BASE-TX bottleneck detection
        val dock25Gbe = EthernetTelemetrySnapshot(
            isConnected = true,
            interfaceName = "eth1",
            isPrimaryDefaultRoute = true,
            isValidated = true,
            negotiatedSpeedMbps = 2_500,
            linkSpeedTierLabel = "2.5 GbE (2500BASE-T)",
            ipStackLabel = "Dual-Stack (IPv4 + IPv6)",
            dnsSummaryLabel = "Private DNS (DoT) · 2 Servers",
            throughput = NetworkThroughput(rxMbps = 185.4f, txMbps = 42.1f),
        )
        assertEquals("Primary Default Route", dock25Gbe.routeRoleLabel)
        assertEquals("2.5 Gbps (2,500 Mbps)", dock25Gbe.negotiatedSpeedSummary)
        assertNull(dock25Gbe.dockBottleneckWarning)
        assertTrue(dock25Gbe.linkBarFraction > 0.8f)

        val dock100M = dock25Gbe.copy(
            negotiatedSpeedMbps = 100,
            linkSpeedTierLabel = "100 Mbps (100BASE-TX)",
            isEmulatorVirtualNic = false,
        )
        assertNotNull(dock100M.dockBottleneckWarning)
        assertEquals("100 Mbps", dock100M.negotiatedSpeedSummary)
        assertEquals(0.28f, dock100M.linkBarFraction, 0.01f)
        assertNull(dock100M.copy(isEmulatorVirtualNic = true).dockBottleneckWarning)

        val dock5Gbe = dock25Gbe.copy(negotiatedSpeedMbps = 5_000, isPrimaryDefaultRoute = false)
        assertEquals("Secondary Link", dock5Gbe.routeRoleLabel)
        assertEquals("5 Gbps (5,000 Mbps)", dock5Gbe.negotiatedSpeedSummary)
        assertEquals(1.0f, dock5Gbe.linkBarFraction, 0.01f)

        val dock1Gbe = dock25Gbe.copy(negotiatedSpeedMbps = 1_000)
        assertEquals("1 Gbps (1,000 Mbps)", dock1Gbe.negotiatedSpeedSummary)
        assertEquals(0.68f, dock1Gbe.linkBarFraction, 0.01f)

        val dockUnknownSpeed = dock25Gbe.copy(negotiatedSpeedMbps = 0)
        assertEquals("Link Up", dockUnknownSpeed.negotiatedSpeedSummary)
        assertEquals(0.60f, dockUnknownSpeed.linkBarFraction, 0.01f)

        val undocked = EthernetTelemetrySnapshot()
        assertEquals("Undocked", undocked.routeRoleLabel)
        assertEquals("No Cable / Dock", undocked.negotiatedSpeedSummary)
        assertEquals(0f, undocked.linkBarFraction, 0.01f)

        // Logcat fallback & peripheral category mappings
        val logcatDrops = WirelessTelemetryParsers.parseBluetoothAudioDropStats(
            dumpsysText = "",
            logcatText = """
                btif_a2dp_source: Dropped media packet
                btif_a2dp_source: audio underflow
                bta_ag_sco: packet drop detected
                le_audio: AudioHalClient stream underflow
            """.trimIndent(),
            isScoOn = true,
            isLeAudioActive = true,
        )
        assertEquals("BT LOGCAT MONITOR", logcatDrops.sourceLabel)
        assertTrue(logcatDrops.hasStackCounters)
        assertEquals(1L, logcatDrops.a2dpTxDropped)
        assertEquals(1L, logcatDrops.a2dpUnderflows)
        assertEquals(1L, logcatDrops.hfpDroppedPackets)
        assertEquals(1L, logcatDrops.leAudioUnderflows)

        assertEquals("Keyboard", WirelessTelemetryParsers.formatPeripheralCategory(0x0500, 0x0540, "Keychron K2"))
        assertEquals("Combo Keyboard/Mouse", WirelessTelemetryParsers.formatPeripheralCategory(0x0500, 0x05C0, "Combo Receiver"))
        assertEquals("Gamepad", WirelessTelemetryParsers.formatPeripheralCategory(0x0500, 0x0508, "Wireless Controller"))
        assertEquals("HID Peripheral", WirelessTelemetryParsers.formatPeripheralCategory(0x0500, 0x0500, "Digitizer"))
        assertEquals("Computer", WirelessTelemetryParsers.formatPeripheralCategory(0x0100, 0x010C, "Laptop"))
        assertEquals("Phone", WirelessTelemetryParsers.formatPeripheralCategory(0x0200, 0x020C, "Handset"))
        assertEquals("Wearable", WirelessTelemetryParsers.formatPeripheralCategory(0x0700, 0x0704, "Smart Watch"))
        assertEquals("Peripheral", WirelessTelemetryParsers.formatPeripheralCategory(0x1F00, 0x1F00, "Generic Dongle"))

        // 5. InterfaceRateTracker per-interface rate math, hotplug interface swap, and UNSUPPORTED (-1L)
        val rateTracker = InterfaceRateTracker()
        val firstSample = rateTracker.sample("eth1", 1_000_000L, 500_000L, 1_000L, 500L, nowElapsedMs = 1_000L)
        assertEquals(0f, firstSample.rxMbps, 0.001f)
        // +12,500,000 bytes in 1,000 ms = 100.0 Mbps; +1,000 pkts in 1,000 ms = 1,000 pkt/s
        val secondSample = rateTracker.sample("eth1", 13_500_000L, 3_000_000L, 2_000L, 750L, nowElapsedMs = 2_000L)
        assertEquals(100.0f, secondSample.rxMbps, 0.05f)
        assertEquals(20.0f, secondSample.txMbps, 0.05f)
        assertEquals(1_000f, secondSample.rxPacketsPerSec, 0.5f)
        assertEquals(250f, secondSample.txPacketsPerSec, 0.5f)
        // Hotplug interface switch (eth1 -> eth2) resets delta baseline without bogus spike
        val swappedSample = rateTracker.sample("eth2", 50_000_000L, 10_000_000L, 5_000L, 900L, nowElapsedMs = 3_000L)
        assertEquals(0f, swappedSample.rxMbps, 0.001f)
        // TrafficStats.UNSUPPORTED (-1L) returns zeroed throughput
        val unsupportedSample = rateTracker.sample("eth2", -1L, -1L, -1L, -1L, nowElapsedMs = 4_000L)
        assertEquals(0L, unsupportedSample.totalRxBytes)

        // 6. DiagnosticReportBuilder emits distinct Wi-Fi Radio & Link and Wired Ethernet rows
        val dockedReport = DiagnosticReportBuilder.buildMarkdownReport(
            SystemTelemetrySnapshot(
                wifi = WifiTelemetrySnapshot(
                    isConnected = true,
                    transportLabel = "Wi-Fi",
                    interfaceName = "wlan0",
                    isPrimaryDefaultRoute = false,
                    routeRoleLabel = "Standby (Ethernet Primary)",
                    wifiStandardCode = 8,
                    wifiStandardLabel = "Wi-Fi 7 (802.11be)",
                    bandAndChannelLabel = "5 GHz · Ch 149 (5745 MHz)",
                    rssiDbm = -35,
                    signalQualityPercent = 100,
                    signalQualityLabel = "Excellent",
                    isValidated = true,
                    txLinkSpeedMbps = 1296,
                    rxLinkSpeedMbps = 1080,
                ),
                ethernet = dock25Gbe,
                activeDefaultRouteSummary = "Ethernet (eth1)",
            ),
        )
        assertTrue(dockedReport.contains("| **Wi-Fi Radio & Link** |"))
        assertTrue(dockedReport.contains("Standby (Ethernet Primary)"))
        assertTrue(dockedReport.contains("| **Wired Ethernet** | `eth1` — `2.5 GbE (2500BASE-T)`"))
    }
}
