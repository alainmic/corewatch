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

        // 1Hz VRR low-cadence frame where SurfaceFlinger FrameTimeline allocates a 60 Hz (16.67ms) deadline
        // and RC6/PSR wakeup completes in 12.5ms -> smooth (within FrameMetrics.DEADLINE).
        tracker.recordFrameMetric(
            nowElapsedMs = 3_200L,
            totalDurationNs = 12_500_000L,
            deadlineNs = 16_666_666L,
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

    @Test
    fun wifiRegulatoryCapabilitiesCanada6GhzWarningChannelSummariesAndBeaconIeParsersWorkCorrectly() {
        // 1. 2.4 GHz FCC/ISED (Ch 1–11) vs ETSI (Ch 1–13) vs JP (Ch 1–14)
        val freqs24Ca = (1..11).map { (2407 + it * 5) to 0 }
        val sum24Ca = WirelessTelemetryParsers.summarizeAllowedBandChannels(
            bandGhz = 2,
            allowedFreqsWidths = freqs24Ca,
            usableFreqs = freqs24Ca.map { it.first },
            isBandSupported = true,
        )
        assertEquals(11, sum24Ca.allowedCount)
        assertEquals(11, sum24Ca.usableCount)
        assertEquals(40, sum24Ca.maxBandwidthMhz)
        assertEquals("Ch 1–11", sum24Ca.channelRangeSummary)
        assertEquals("11 / 11 Usable", sum24Ca.countBadge)
        assertTrue(sum24Ca.subBandNote.contains("FCC / ISED"))

        val freqs24Eu = (1..13).map { (2407 + it * 5) to 0 }
        val sum24Eu = WirelessTelemetryParsers.summarizeAllowedBandChannels(
            bandGhz = 2,
            allowedFreqsWidths = freqs24Eu,
            usableFreqs = freqs24Eu.map { it.first },
            isBandSupported = true,
        )
        assertEquals("Ch 1–13", sum24Eu.channelRangeSummary)
        assertTrue(sum24Eu.subBandNote.contains("ETSI"))

        // 2. 5 GHz Canada ISED with DFS and TDWR (Ch 120–128) exclusion
        val ch5Ca = listOf(36, 40, 44, 48, 52, 56, 60, 64, 100, 104, 108, 112, 116, 132, 136, 140, 144, 149, 153, 157, 161, 165)
        val freqs5Ca = ch5Ca.map { (5000 + it * 5) to 0 }
        val sum5Ca = WirelessTelemetryParsers.summarizeAllowedBandChannels(
            bandGhz = 5,
            allowedFreqsWidths = freqs5Ca,
            usableFreqs = freqs5Ca.take(18).map { it.first },
            isBandSupported = true,
            supports11be = true,
        )
        assertEquals(22, sum5Ca.allowedCount)
        assertEquals(18, sum5Ca.usableCount)
        assertEquals("18 Usable (22 Reg)", sum5Ca.countBadge)
        assertEquals(160, sum5Ca.maxBandwidthMhz)
        assertEquals("Ch 36–64, 100–116, 132–144, 149–165", sum5Ca.channelRangeSummary)
        assertEquals(13, sum5Ca.dfsChannelCount)
        assertTrue(sum5Ca.subBandNote.contains("TDWR 120–128 excl."))

        // 3. 6 GHz US/ISED Full 1200 MHz (59 channels, 15 PSC, 320 MHz on Wi-Fi 7) vs EU U-NII-5 Only (24 channels, 6 PSC)
        val freqs6Full = (0 until 59).map { idx -> (5955 + idx * 20) to 0 }
        val sum6FullWifi7 = WirelessTelemetryParsers.summarizeAllowedBandChannels(
            bandGhz = 6,
            allowedFreqsWidths = freqs6Full,
            usableFreqs = freqs6Full.map { it.first },
            isBandSupported = true,
            supports11be = true,
        )
        assertEquals(59, sum6FullWifi7.allowedCount)
        assertEquals(59, sum6FullWifi7.usableCount)
        assertEquals(15, sum6FullWifi7.pscChannelCount)
        assertEquals(320, sum6FullWifi7.maxBandwidthMhz)
        assertEquals("Ch 1–233", sum6FullWifi7.channelRangeSummary)
        assertTrue(sum6FullWifi7.subBandNote.contains("U-NII-5..8 Full 1200 MHz (15 PSC)"))

        val freqs6Eu = (0 until 24).map { idx -> (5955 + idx * 20) to 0 }
        val sum6EuWifi6e = WirelessTelemetryParsers.summarizeAllowedBandChannels(
            bandGhz = 6,
            allowedFreqsWidths = freqs6Eu,
            usableFreqs = freqs6Eu.map { it.first },
            isBandSupported = true,
            supports11be = false,
        )
        assertEquals(24, sum6EuWifi6e.allowedCount)
        assertEquals(6, sum6EuWifi6e.pscChannelCount)
        assertEquals(160, sum6EuWifi6e.maxBandwidthMhz)
        assertEquals("Ch 1–93", sum6EuWifi6e.channelRangeSummary)
        assertTrue(sum6EuWifi6e.subBandNote.contains("U-NII-5 Only"))

        // 4. Exact Canada ('CA') & World ('00') 6 GHz blocked diagnosis on Wi-Fi 7 hardware
        val sum6Blocked = WirelessTelemetryParsers.summarizeAllowedBandChannels(
            bandGhz = 6,
            allowedFreqsWidths = emptyList(),
            usableFreqs = emptyList(),
            isBandSupported = true,
            supports11be = true,
        )
        assertEquals("0 Allowed (Reg-Blocked)", sum6Blocked.countBadge)
        assertEquals("Blocked in Region", sum6Blocked.channelRangeSummary)

        val caBlockedCaps = WifiRadioCapabilities(
            isCapabilitiesQueried = true,
            hasNearbyWifiPermission = true,
            channelQuerySucceeded = true,
            supports11beWifi7 = true,
            supports11axWifi6 = true,
            supports11acWifi5 = true,
            supports11nWifi4 = true,
            supports11abgLegacy = true,
            supports24Ghz = true,
            supports5Ghz = true,
            supports6Ghz = true,
            maxTxLinkSpeedMbps = 2882,
            maxRxLinkSpeedMbps = 2882,
            supportsWpa3Sae = true,
            supportsSaeH2e = true,
            supportsOwe = true,
            supportsDualBandSimultaneous = true,
            supportsMloTidToLinkMapping = true,
            countryCode = "CA",
            countryCodeSource = "Driver RegDomain (dumpsys)",
            band24Ghz = sum24Ca,
            band5Ghz = sum5Ca,
            band6Ghz = sum6Blocked,
        )
        assertEquals("Wi-Fi 7 (802.11be)", caBlockedCaps.maxGenerationLabel)
        assertTrue(caBlockedCaps.supportedBandsSummary.contains("Tri-Band"))
        assertEquals("TX 2,882 Mbps · RX 2,882 Mbps", caBlockedCaps.maxPhySpeedSummary)
        assertNotNull(caBlockedCaps.sixGhzRegulatoryWarning)
        assertTrue(caBlockedCaps.sixGhzRegulatoryWarning!!.contains("CA (Canada)"))
        assertTrue(caBlockedCaps.sixGhzRegulatoryWarning!!.contains("Intel LAR"))

        val worldBlockedCaps = caBlockedCaps.copy(countryCode = "00")
        assertTrue(worldBlockedCaps.sixGhzRegulatoryWarning!!.contains("world safe-mode"))

        val concurrencyMutedCaps = caBlockedCaps.copy(
            band6Ghz = sum6FullWifi7.copy(usableCount = 0),
        )
        assertTrue(concurrencyMutedCaps.sixGhzRegulatoryWarning!!.contains("59 ch in CA"))

        // 5. Dumpsys WifiCountryCode provenance parser
        val dumpsysWifiSample = """
            WifiCountryCode:
              mTelephonyCountryCode: US
              DefaultCountryCode(system property): 00
              mDriverCountryCode: CA
        """.trimIndent()
        assertEquals(
            "CA" to "Driver RegDomain (dumpsys)",
            WirelessTelemetryParsers.parseWifiDumpsysCountryCode(dumpsysWifiSample),
        )
        assertNull(WirelessTelemetryParsers.parseWifiDumpsysCountryCode("Permission Denial: can't dump WifiService"))

        // 6. 802.11d Country IE + QBSS Load + 11r/11k/11v Beacon IE parser & BSSID privacy masking
        val parsedIes = WirelessTelemetryParsers.parse80211InformationElements(
            listOf(
                7 to byteArrayOf('C'.code.toByte(), 'A'.code.toByte(), 'I'.code.toByte(), 1, 11, 30),
                11 to byteArrayOf(12, 0, 64, 0, 0), // 12 stations, 64/255 = 25% channel utilization
                54 to byteArrayOf(0x12, 0x34, 0x00),
                70 to byteArrayOf(0x02, 0x00, 0x00, 0x00, 0x00),
                127 to byteArrayOf(0x00, 0x00, 0x08, 0x00),
            ),
        )
        assertEquals("CA", parsedIes.countryCode80211d)
        assertEquals("Indoor", parsedIes.countryEnvLabel)
        assertEquals(12, parsedIes.qbssStationCount)
        assertEquals(25, parsedIes.qbssChannelUtilizationPct)
        assertTrue(parsedIes.fastTransition11r)
        assertTrue(parsedIes.neighborReport11k)
        assertTrue(parsedIes.bssTransition11v)

        assertEquals("02:11:22:••:••:06", WirelessTelemetryParsers.maskBssid("02:11:22:33:44:06"))
        assertEquals("••:••:••:••:••:••", WirelessTelemetryParsers.maskBssid("invalid"))
        assertEquals("Wi-Fi 6E (11ax)", WirelessTelemetryParsers.formatWifiStandardForBand(6, 6135))
        assertEquals("Wi-Fi 6 (11ax)", WirelessTelemetryParsers.formatWifiStandardForBand(6, 5180))
        assertEquals(320 to "320 MHz", WirelessTelemetryParsers.formatChannelBandwidth(5))

        val (secLabel, secBadges) = WirelessTelemetryParsers.formatScanCapabilitiesSecurity(
            "[WPA2-PSK-CCMP][RSN-SAE+SAE_EXT_KEY-CCMP][MFPR][FT/SAE][ESS]",
            intArrayOf(2, 4),
        )
        assertEquals("WPA2/WPA3-SAE", secLabel)
        assertTrue(secBadges.containsAll(listOf("SAE-H2E", "PMF-Req", "11r")))

        // 7. DiagnosticReportBuilder exports Wi-Fi Radio & RegDomain but NEVER surrounding SSIDs or BSSIDs
        val reportWithAps = DiagnosticReportBuilder.buildMarkdownReport(
            SystemTelemetrySnapshot(
                wifi = WifiTelemetrySnapshot(
                    isConnected = true,
                    capabilities = caBlockedCaps,
                    surroundingScan = WifiScanTelemetrySnapshot(
                        hasScanLocationPermission = true,
                        isLocationEnabled = true,
                        totalApCount = 1,
                        count6Ghz = 1,
                        accessPoints = listOf(
                            SurroundingWifiAp(
                                ssid = "PrivateHomeCorpSSID",
                                bssid = "02:aa:bb:cc:dd:ee",
                                maskedBssid = "02:aa:bb:••:••:ee",
                                bandGhz = 6,
                                bandLabel = "6 GHz",
                                primaryFreqMhz = 6135,
                                channelNumber = 37,
                                channelWidthMhz = 320,
                                channelWidthLabel = "320 MHz",
                                isPscChannel = true,
                                wifiStandardCode = 8,
                                wifiStandardLabel = "Wi-Fi 7 (11be)",
                                securitySummary = "WPA3-SAE",
                                rssiDbm = -48,
                                signalPercent = 95,
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertTrue(reportWithAps.contains("| **Wi-Fi Radio & RegDomain** |"))
        assertTrue(reportWithAps.contains("⚠️ 6 GHz RegDomain Blocked"))
        assertFalse(reportWithAps.contains("PrivateHomeCorpSSID"))
        assertFalse(reportWithAps.contains("02:aa:bb"))
    }

    @Test
    fun usbPeripheralParsersReconstructMultiTierDockTreeDetectBottlenecksAndParseTypeCDumpsys() {
        val tempDir = java.nio.file.Files.createTempDirectory("corewatch_usb_sysfs_test").toFile()
        try {
            fun writeUsbDev(
                name: String,
                vid: String,
                pid: String,
                speed: String,
                version: String,
                devClass: String = "00",
                removable: String = "removable",
                maxPower: String = "0mA",
                bmAttr: String = "e0",
                txLanes: String = "1",
                rxLanes: String = "1",
                mfg: String = "",
                product: String = "",
            ) {
                val d = java.io.File(tempDir, name).apply { mkdirs() }
                java.io.File(d, "idVendor").writeText("$vid\n")
                java.io.File(d, "idProduct").writeText("$pid\n")
                java.io.File(d, "speed").writeText("$speed\n")
                java.io.File(d, "version").writeText(" $version\n")
                java.io.File(d, "bDeviceClass").writeText("$devClass\n")
                java.io.File(d, "removable").writeText("$removable\n")
                java.io.File(d, "bMaxPower").writeText("$maxPower\n")
                java.io.File(d, "bmAttributes").writeText("$bmAttr\n")
                java.io.File(d, "tx_lanes").writeText("$txLanes\n")
                java.io.File(d, "rx_lanes").writeText("$rxLanes\n")
                if (mfg.isNotEmpty()) java.io.File(d, "manufacturer").writeText("$mfg\n")
                if (product.isNotEmpty()) java.io.File(d, "product").writeText("$product\n")
            }

            fun writeUsbIface(
                devName: String,
                ifaceIdx: Int,
                cls: String,
                sub: String = "00",
                pro: String = "00",
                eps: String = "01",
                ifaceStr: String = "",
            ) {
                val d = java.io.File(tempDir, "$devName:1.$ifaceIdx").apply { mkdirs() }
                java.io.File(d, "bInterfaceClass").writeText("$cls\n")
                java.io.File(d, "bInterfaceSubClass").writeText("$sub\n")
                java.io.File(d, "bInterfaceProtocol").writeText("$pro\n")
                java.io.File(d, "bNumEndpoints").writeText("$eps\n")
                if (ifaceStr.isNotEmpty()) java.io.File(d, "interface").writeText("$ifaceStr\n")
            }

            // Root xHCI buses
            writeUsbDev("usb1", "1d6b", "0002", "480", "2.00", devClass = "09", product = "xHCI Host Controller")
            writeUsbDev("usb2", "1d6b", "0003", "20000", "3.10", devClass = "09", txLanes = "2", rxLanes = "2", product = "xHCI Host Controller")

            // External TBT4 dock SuperSpeed subtree (2-4 removable -> 2-4.4 fixed internal dock hub -> 2-4.4.1 fixed 2.5G NIC)
            writeUsbDev("2-4", "8087", "0b40", "10000", "3.20", devClass = "09", removable = "removable", product = "USB3.0 Hub")
            writeUsbIface("2-4", 0, "09")
            writeUsbDev("2-4.4", "0bda", "0481", "5000", "3.20", devClass = "09", removable = "fixed", product = "USB3.2 Hub")
            writeUsbIface("2-4.4", 0, "09")
            writeUsbDev("2-4.4.1", "0bda", "8156", "5000", "3.20", removable = "fixed", maxPower = "256mA", bmAttr = "a0", mfg = "Realtek", product = "USB 10/100/1G/2.5G LAN")
            writeUsbIface("2-4.4.1", 0, "ff", "ff", "00", eps = "03")

            // Companion USB 2.0 hub that is BUS-POWERED (bmAttr=80) and over-subscribed (300mA + 300mA = 600mA > 500mA)
            writeUsbDev("1-1", "2109", "2817", "480", "2.10", devClass = "09", removable = "removable", bmAttr = "80", product = "USB2.0 Hub")
            writeUsbIface("1-1", 0, "09")
            // Child 1: SuperSpeed NVMe SSD downgraded to 480 Mbps on USB 2.0 cable/hub
            writeUsbDev("1-1.1", "0781", "5583", "480", "3.20", removable = "removable", maxPower = "300mA", bmAttr = "80", product = "Extreme Portable SSD")
            writeUsbIface("1-1.1", 0, "08", "06", "62", eps = "04")
            // Child 2: Standalone USB Billboard fallback device (Class 0x11)
            writeUsbDev("1-1.2", "413c", "b0ff", "480", "2.01", devClass = "11", removable = "removable", maxPower = "300mA", bmAttr = "80", product = "USB-C Alt-Mode Adapter")
            writeUsbIface("1-1.2", 0, "11")

            // Composite Dell Dock MCU (HID 0x03 + Passive Billboard 0x11) -> must be Healthy Dock, NOT BillboardFallback!
            writeUsbDev("1-2", "413c", "b0a2", "480", "2.01", removable = "removable", maxPower = "100mA", bmAttr = "c0", mfg = "Dell", product = "Dell Pro TBT4 Smart SD25TB4")
            writeUsbIface("1-2", 0, "03", "00", "00", eps = "01", ifaceStr = "Dock HID")
            writeUsbIface("1-2", 1, "11", "00", "00", eps = "00", ifaceStr = "Billboard")

            // CH341 Serial UART with empty manufacturer (resolved via UsbVendorLookup 0x1a86 -> QinHeng Electronics (WCH))
            writeUsbDev("1-3", "1a86", "7523", "12", "1.10", devClass = "ff", removable = "removable", maxPower = "98mA", bmAttr = "80", product = "USB Serial")
            writeUsbIface("1-3", 0, "ff", "01", "02", eps = "03")

            // Internal fixed motherboard webcam & FPMCU -> must be marked isInternal = true
            writeUsbDev("1-5", "04f2", "b890", "480", "2.01", devClass = "ef", removable = "fixed", maxPower = "500mA", bmAttr = "80", mfg = "Chicony", product = "Integrated 5M Camera")
            writeUsbIface("1-5", 0, "0e", "01", "00")
            writeUsbDev("1-6", "18d1", "5401", "12", "2.00", devClass = "00", removable = "fixed", maxPower = "500mA", bmAttr = "a0", mfg = "Google", product = "FPMCU")
            writeUsbIface("1-6", 0, "ff", "5a", "00")

            val rawSysfs = UsbPeripheralParsers.readSysfsUsbDirectory(tempDir)
            assertTrue(rawSysfs.size >= 11)

            val dumpsysSample = """
                USB Manager State:
                  port_manager={
                    is_simulation_active=false
                    usb_ports=[
                      {
                        port={
                          id=port0
                          supported_modes=dual
                        }
                        status={
                          connected=true
                          current_mode=dfp
                          power_role=sink
                          data_role=host
                          role_combinations=[
                            {
                              power_role=source
                              data_role=device
                            }
                          ]
                          usb_data_status=enabled
                          is_power_transfer_limited=false
                          usb_power_brick_status=connected
                          compliance_warning_status=[]
                          displayport_alt_mode_status=DisplayPortAltModeInfo{partnerSink=detected, cable=passive, numLanes=4, hotPlugDetect=true, linkTrainingStatus=success}
                        }
                        connected_at_millis=10000
                      }
                      {
                        port={
                          id=port1
                          supported_modes=dual
                        }
                        status={
                          connected=true
                          current_mode=ufp
                          power_role=sink
                          data_role=device
                          usb_data_status=disabled-contaminant
                          is_power_transfer_limited=true
                          compliance_warning_status=[unreliable-cable, debug-accessory]
                        }
                        connected_at_millis=65000
                      }
                    ]
                  }
            """.trimIndent()

            val ports = UsbPeripheralParsers.parseUsbDumpsysPorts(dumpsysSample, nowElapsedMs = 75_000L)
            assertEquals(2, ports.size)
            assertEquals("port0", ports[0].portId)
            // Verify role_combinations=[source/device] did NOT overwrite status's Sink/Host!
            assertEquals("Sink", ports[0].powerRole)
            assertEquals("Host", ports[0].dataRole)
            assertEquals("Host · Sink (PD In)", ports[0].roleBadgeLabel)
            assertEquals("DP Alt-Mode (4-Lane) · Link: Success", ports[0].displayPortAltModeSummary)
            assertEquals("1m 5s", ports[0].connectedDurationLabel)
            assertFalse(ports[0].hasWarning)

            assertTrue(ports[1].hasWarning)
            assertTrue(ports[1].isPowerTransferLimited)
            assertEquals(listOf("unreliable-cable", "debug-accessory"), ports[1].complianceWarnings)

            val snap = UsbPeripheralParsers.buildUsbTopologySnapshot(
                rawDevices = rawSysfs,
                hasDumpPermission = true,
                typeCPorts = ports,
            )
            val byId = snap.nodes.associateBy { it.id }

            // Root-port removable inheritance: 2-4.4.1 (Realtek 2.5G LAN inside external dock) is external!
            val nic = byId.getValue("2-4.4.1")
            assertFalse("Soldered NIC inside external dock must inherit external visibility", nic.isInternal)
            assertEquals(UsbNodeCategory.NetworkAdapter, nic.category)
            assertEquals(UsbHealthStatus.Healthy, nic.healthStatus)
            assertEquals("5 Gbps", nic.speedBadgeLabel)

            // Fixed motherboard devices are internal
            assertTrue(byId.getValue("1-5").isInternal)
            assertEquals(UsbNodeCategory.VideoWebcam, byId.getValue("1-5").category)
            assertTrue(byId.getValue("1-6").isInternal)
            assertEquals(UsbNodeCategory.SecurityBiometric, byId.getValue("1-6").category)
            assertEquals(2, snap.internalDeviceCount)

            // Bottleneck, Billboard & Hub Power Budget warnings
            assertEquals(UsbHealthStatus.PowerBudgetWarning, byId.getValue("1-1").healthStatus)
            assertEquals(600, byId.getValue("1-1").downstreamRequestedMa)
            assertEquals(UsbHealthStatus.SpeedBottleneck, byId.getValue("1-1.1").healthStatus)
            assertEquals(UsbHealthStatus.BillboardFallback, byId.getValue("1-1.2").healthStatus)

            // Composite Dell Dock MCU is Healthy Dock with passiveNote
            val dockMcu = byId.getValue("1-2")
            assertEquals(UsbNodeCategory.Dock, dockMcu.category)
            assertEquals(UsbHealthStatus.Healthy, dockMcu.healthStatus)
            assertNotNull(dockMcu.passiveNote)

            // QinHeng WCH vendor lookup fallback when sysfs manufacturer is blank
            val serial = byId.getValue("1-3")
            assertEquals(UsbNodeCategory.SerialAdapter, serial.category)
            assertEquals("QinHeng Electronics (WCH)", serial.vendorName)

            // Hotplug tracker records baseline warnings, then detach & attach transitions
            val tracker = UsbHotplugTracker(capacity = 8)
            val initialEvents = tracker.recordSample(snap.nodes, nowElapsedMs = 1_000L, wallClockMs = 1_700_000_000_000L)
            assertEquals(3, initialEvents.size) // PowerBudgetWarning + SpeedBottleneck + BillboardFallback

            val afterDetachNodes = snap.nodes.filterNot { it.id == "1-3" }
            val afterDetachEvents = tracker.recordSample(afterDetachNodes, nowElapsedMs = 2_000L, wallClockMs = 1_700_000_001_000L)
            assertEquals(UsbHotplugAction.Detached, afterDetachEvents.first().action)
            assertEquals("1-3", afterDetachEvents.first().nodeId)

            val afterReattachEvents = tracker.recordSample(snap.nodes, nowElapsedMs = 3_000L, wallClockMs = 1_700_000_002_000L)
            assertEquals(UsbHotplugAction.Attached, afterReattachEvents.first().action)
            assertEquals("1-3", afterReattachEvents.first().nodeId)

            tracker.reset()
            assertTrue(tracker.recordSample(emptyList(), nowElapsedMs = 4_000L).isEmpty())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun frameJankTrackerZeroAndSmoothStreamReportsZeroPercentDroppedAndJanky() {
        val tracker = VrrAndFrameWindowTracker(windowHorizonMs = 300_000L)
        val emptySnap = tracker.snapshotFrames(nowElapsedMs = 1_000L, defaultHz = 120)
        assertEquals(0, emptySnap.evaluatedFrames5m)
        assertEquals(0, emptySnap.expectedFrames5m)
        assertEquals(0, emptySnap.droppedFrames5m)
        assertEquals(0, emptySnap.jankyFrames5m)
        assertEquals(0f, emptySnap.droppedPercent5m, 0.001f)
        assertEquals(0f, emptySnap.jankyPercent5m, 0.001f)
        assertEquals(0f, emptySnap.latestIntervalJankPercent, 0.001f)
        assertEquals("00:00 / 05:00", emptySnap.windowElapsedLabel)
        assertEquals("[120] Hz", emptySnap.deadlineRangeLabel)

        // 120 smooth 120 Hz frames (6.0ms <= 8.33ms) + 60 smooth 60 Hz VRR frames (12.0ms <= 16.67ms)
        repeat(120) { i ->
            val vsyncNs = (i + 1L) * 8_333_333L
            tracker.recordFrameMetric(
                nowElapsedMs = 1_000L + (i * 8L),
                totalDurationNs = 6_000_000L,
                deadlineNs = 8_333_333L,
                gpuDurationNs = 2_000_000L,
                intendedVsyncNs = vsyncNs,
                vsyncNs = vsyncNs,
                cpuStagesDurationNs = 3_800_000L,
                swapBuffersDurationNs = 200_000L,
            )
        }
        repeat(60) { i ->
            val vsyncNs = 1_000_000_000L + (i + 1L) * 16_666_667L
            tracker.recordFrameMetric(
                nowElapsedMs = 2_000L + (i * 16L),
                totalDurationNs = 12_000_000L,
                deadlineNs = 16_666_667L,
                gpuDurationNs = 3_000_000L,
                intendedVsyncNs = vsyncNs,
                vsyncNs = vsyncNs,
                cpuStagesDurationNs = 8_500_000L,
                swapBuffersDurationNs = 500_000L,
            )
        }

        val smoothSnap = tracker.snapshotFrames(nowElapsedMs = 3_000L, defaultHz = 120)
        assertEquals(180, smoothSnap.evaluatedFrames5m)
        assertEquals(180, smoothSnap.expectedFrames5m)
        assertEquals(0, smoothSnap.droppedFrames5m)
        assertEquals(0, smoothSnap.jankyFrames5m)
        assertEquals(0f, smoothSnap.droppedPercent5m, 0.001f)
        assertEquals(0f, smoothSnap.jankyPercent5m, 0.001f)
        assertEquals(0f, smoothSnap.latestIntervalJankPercent, 0.001f)
        assertEquals("[60, 120] Hz", smoothSnap.deadlineRangeLabel)
    }

    @Test
    fun frameJankTrackerSustainedSubRefreshRatesReflectExactDroppedAndJankyPercentages() {
        val deadline60HzNs = 16_666_667L

        // Case 1: 30 FPS on 60 Hz (30 frames each taking 25ms -> 1 missed VSync per frame = 30 dropped / 60 expected = 50.00%)
        val halfRateTracker = VrrAndFrameWindowTracker()
        repeat(30) { i ->
            val vsyncNs = (i + 1L) * 33_333_334L
            halfRateTracker.recordFrameMetric(
                nowElapsedMs = 1_000L + (i * 33L),
                totalDurationNs = 25_000_000L,
                deadlineNs = deadline60HzNs,
                gpuDurationNs = 4_000_000L,
                intendedVsyncNs = vsyncNs,
                vsyncNs = vsyncNs,
                cpuStagesDurationNs = 20_500_000L,
                swapBuffersDurationNs = 500_000L,
            )
        }
        val halfRateSnap = halfRateTracker.snapshotFrames(nowElapsedMs = 2_000L, defaultHz = 60)
        assertEquals(30, halfRateSnap.evaluatedFrames5m)
        assertEquals(30, halfRateSnap.jankyFrames5m)
        assertEquals(30, halfRateSnap.droppedFrames5m)
        assertEquals(60, halfRateSnap.expectedFrames5m)
        assertEquals(100.0f, halfRateSnap.jankyPercent5m, 0.01f)
        assertEquals(50.0f, halfRateSnap.droppedPercent5m, 0.01f)

        // Case 2: 45 FPS on 60 Hz (30 smooth 10ms frames + 15 late 25ms frames -> 15/45 = 33.33% janky, 15/60 = 25.00% dropped)
        val threeQuarterTracker = VrrAndFrameWindowTracker()
        repeat(30) { i ->
            val vsyncNs = (i + 1L) * deadline60HzNs
            threeQuarterTracker.recordFrameMetric(
                nowElapsedMs = 1_000L + (i * 16L),
                totalDurationNs = 10_000_000L,
                deadlineNs = deadline60HzNs,
                gpuDurationNs = 2_000_000L,
                intendedVsyncNs = vsyncNs,
                vsyncNs = vsyncNs,
                cpuStagesDurationNs = 7_500_000L,
            )
        }
        repeat(15) { i ->
            val vsyncNs = 600_000_000L + (i + 1L) * 33_333_334L
            threeQuarterTracker.recordFrameMetric(
                nowElapsedMs = 1_500L + (i * 33L),
                totalDurationNs = 25_000_000L,
                deadlineNs = deadline60HzNs,
                gpuDurationNs = 3_000_000L,
                intendedVsyncNs = vsyncNs,
                vsyncNs = vsyncNs,
                cpuStagesDurationNs = 21_500_000L,
            )
        }
        val threeQuarterSnap = threeQuarterTracker.snapshotFrames(nowElapsedMs = 2_000L, defaultHz = 60)
        assertEquals(45, threeQuarterSnap.evaluatedFrames5m)
        assertEquals(15, threeQuarterSnap.jankyFrames5m)
        assertEquals(15, threeQuarterSnap.droppedFrames5m)
        assertEquals(60, threeQuarterSnap.expectedFrames5m)
        assertEquals(33.33f, threeQuarterSnap.jankyPercent5m, 0.02f)
        assertEquals(25.00f, threeQuarterSnap.droppedPercent5m, 0.01f)

        // Case 3: 15 FPS on 60 Hz (15 frames each taking 55ms -> 3 missed VSyncs per frame = 45 dropped / 60 expected = 75.00%)
        val quarterRateTracker = VrrAndFrameWindowTracker()
        repeat(15) { i ->
            val vsyncNs = (i + 1L) * 66_666_668L
            quarterRateTracker.recordFrameMetric(
                nowElapsedMs = 1_000L + (i * 66L),
                totalDurationNs = 55_000_000L,
                deadlineNs = deadline60HzNs,
                gpuDurationNs = 5_000_000L,
                intendedVsyncNs = vsyncNs,
                vsyncNs = vsyncNs,
                cpuStagesDurationNs = 49_000_000L,
            )
        }
        val quarterRateSnap = quarterRateTracker.snapshotFrames(nowElapsedMs = 2_000L, defaultHz = 60)
        assertEquals(15, quarterRateSnap.evaluatedFrames5m)
        assertEquals(15, quarterRateSnap.jankyFrames5m)
        assertEquals(45, quarterRateSnap.droppedFrames5m)
        assertEquals(60, quarterRateSnap.expectedFrames5m)
        assertEquals(100.0f, quarterRateSnap.jankyPercent5m, 0.01f)
        assertEquals(75.0f, quarterRateSnap.droppedPercent5m, 0.01f)
    }

    @Test
    fun frameJankTrackerMultiVsyncFreezeDistinguishesDroppedSlotRatioFromJankyFrameRatio() {
        val tracker = VrrAndFrameWindowTracker()
        val deadline60HzNs = 16_666_667L

        // 30 smooth frames (0.5s) followed by 1 catastrophic 500ms UI freeze (overrun = 483.33ms -> 29 missed VSyncs)
        repeat(30) { i ->
            val vsyncNs = (i + 1L) * deadline60HzNs
            tracker.recordFrameMetric(
                nowElapsedMs = 1_000L + (i * 16L),
                totalDurationNs = 7_000_000L,
                deadlineNs = deadline60HzNs,
                gpuDurationNs = 2_000_000L,
                intendedVsyncNs = vsyncNs,
                vsyncNs = vsyncNs,
                cpuStagesDurationNs = 4_800_000L,
            )
        }
        tracker.recordFrameMetric(
            nowElapsedMs = 1_980L,
            totalDurationNs = 500_000_000L,
            deadlineNs = deadline60HzNs,
            gpuDurationNs = 3_000_000L,
            intendedVsyncNs = 600_000_000L,
            vsyncNs = 1_080_000_000L,
            cpuStagesDurationNs = 496_000_000L,
        )

        val snap = tracker.snapshotFrames(nowElapsedMs = 2_000L, defaultHz = 60)
        assertEquals(31, snap.evaluatedFrames5m)
        assertEquals(1, snap.jankyFrames5m)
        assertEquals(29, snap.droppedFrames5m)
        assertEquals(60, snap.expectedFrames5m)
        assertEquals(1, snap.sfMissedFrames5m)
        assertEquals(1, snap.longCpuJank5m)
        // 1 of 31 rendered frames missed deadline (3.23%), while 29 of 60 expected VSyncs were dropped (48.33%)
        assertEquals(3.23f, snap.jankyPercent5m, 0.02f)
        assertEquals(48.33f, snap.droppedPercent5m, 0.02f)
    }

    @Test
    fun frameJankTrackerHonorsPerFrameVrrDeadlineOverStaleFallbackHzAndExemptsFirstDraw() {
        val tracker = VrrAndFrameWindowTracker()

        // First-draw cold layout inflate (150ms) is exempt from display jank/drop evaluation, while recording droppedReports.
        tracker.recordFrameMetric(
            nowElapsedMs = 1_000L,
            totalDurationNs = 150_000_000L,
            deadlineNs = 8_333_333L,
            gpuDurationNs = 12_000_000L,
            intendedVsyncNs = 100_000_000L,
            vsyncNs = 200_000_000L,
            isFirstDrawFrame = true,
            droppedReportCount = 2,
        )
        val afterFirstDraw = tracker.snapshotFrames(nowElapsedMs = 1_100L, defaultHz = 120)
        assertEquals(0, afterFirstDraw.evaluatedFrames5m)
        assertEquals(0, afterFirstDraw.jankyFrames5m)
        assertEquals(0, afterFirstDraw.droppedFrames5m)
        assertEquals(2, afterFirstDraw.droppedReports5m)

        // VRR panel ramps from 20 Hz idle to 120 Hz (deadline = 8.33ms), while 1Hz Display sample fallbackHz is still stale at 20 Hz.
        // A 14ms frame MUST be flagged as janky against its real 8.33ms FrameMetrics.DEADLINE.
        tracker.recordFrameMetric(
            nowElapsedMs = 1_200L,
            totalDurationNs = 14_000_000L,
            deadlineNs = 8_333_333L,
            gpuDurationNs = 2_000_000L,
            intendedVsyncNs = 300_000_000L,
            vsyncNs = 300_000_000L,
            fallbackHz = 20f,
            cpuStagesDurationNs = 11_500_000L,
            droppedReportCount = 1,
        )
        val afterVrrRamp = tracker.snapshotFrames(nowElapsedMs = 1_300L, defaultHz = 120)
        assertEquals(1, afterVrrRamp.evaluatedFrames5m)
        assertEquals(1, afterVrrRamp.jankyFrames5m)
        assertEquals(1, afterVrrRamp.droppedFrames5m)
        assertEquals(3, afterVrrRamp.droppedReports5m)
        assertEquals(100.0f, afterVrrRamp.jankyPercent5m, 0.01f)
        assertEquals(50.0f, afterVrrRamp.droppedPercent5m, 0.01f)
        assertEquals("[120] Hz", afterVrrRamp.deadlineRangeLabel)

        // When deadlineNs is unavailable (-1L), fallbackHz (60 Hz -> 16.67ms) is used cleanly.
        tracker.recordFrameMetric(
            nowElapsedMs = 1_400L,
            totalDurationNs = 12_000_000L,
            deadlineNs = -1L,
            gpuDurationNs = 2_000_000L,
            intendedVsyncNs = 320_000_000L,
            vsyncNs = 320_000_000L,
            fallbackHz = 60f,
            cpuStagesDurationNs = 9_500_000L,
        )
        val afterFallback = tracker.snapshotFrames(nowElapsedMs = 1_500L, defaultHz = 120)
        assertEquals(2, afterFallback.evaluatedFrames5m)
        assertEquals(1, afterFallback.jankyFrames5m)
        assertEquals("[60, 120] Hz", afterFallback.deadlineRangeLabel)
    }

    @Test
    fun frameJankTrackerClassifiesLongCpuLongGpuBufferStuffingAndMissedVsyncAccurately() {
        val tracker = VrrAndFrameWindowTracker()
        val deadlineNs = 16_666_667L

        // 1. Long CPU + Choreographer Missed VSync (vsyncSlip = 17ms, cpu = 19ms, gpu = 2ms, swap = 0.2ms, total = 21.2ms)
        tracker.recordFrameMetric(
            nowElapsedMs = 1_000L,
            totalDurationNs = 21_200_000L,
            deadlineNs = deadlineNs,
            gpuDurationNs = 2_000_000L,
            intendedVsyncNs = 100_000_000L,
            vsyncNs = 117_000_000L,
            cpuStagesDurationNs = 19_000_000L,
            swapBuffersDurationNs = 200_000L,
        )
        // 2. Long GPU (vsyncSlip = 0, cpu = 3ms, gpu = 16ms, swap = 0.2ms, total = 19.2ms)
        tracker.recordFrameMetric(
            nowElapsedMs = 1_100L,
            totalDurationNs = 19_200_000L,
            deadlineNs = deadlineNs,
            gpuDurationNs = 16_000_000L,
            intendedVsyncNs = 200_000_000L,
            vsyncNs = 200_000_000L,
            cpuStagesDurationNs = 3_000_000L,
            swapBuffersDurationNs = 200_000L,
        )
        // 3. Buffer Stuffing via SWAP_BUFFERS_DURATION stall (vsyncSlip = 0, cpu = 3ms, gpu = 2ms, swap = 15ms, total = 20ms)
        tracker.recordFrameMetric(
            nowElapsedMs = 1_200L,
            totalDurationNs = 20_000_000L,
            deadlineNs = deadlineNs,
            gpuDurationNs = 2_000_000L,
            intendedVsyncNs = 300_000_000L,
            vsyncNs = 300_000_000L,
            cpuStagesDurationNs = 3_000_000L,
            swapBuffersDurationNs = 15_000_000L,
        )

        val snap = tracker.snapshotFrames(nowElapsedMs = 1_300L, defaultHz = 60)
        assertEquals(3, snap.evaluatedFrames5m)
        assertEquals(3, snap.jankyFrames5m)
        assertEquals(3, snap.droppedFrames5m)
        assertEquals(1, snap.longCpuJank5m)
        assertEquals(1, snap.longGpuJank5m)
        assertEquals(1, snap.bufferStuffing5m)
        assertEquals(1, snap.sfMissedFrames5m)
    }

    @Test
    fun frameJankTrackerRollingFiveMinutePruningAndIdleSparklineDecay() {
        val tracker = VrrAndFrameWindowTracker(windowHorizonMs = 300_000L)
        val deadlineNs = 16_666_667L

        // At t = 1,000ms: 2 smooth + 2 janky frames -> 50.0% interval jank
        repeat(2) { i ->
            tracker.recordFrameMetric(
                nowElapsedMs = 1_000L + (i * 16L),
                totalDurationNs = 8_000_000L,
                deadlineNs = deadlineNs,
                gpuDurationNs = 2_000_000L,
                intendedVsyncNs = 100_000_000L,
                vsyncNs = 100_000_000L,
                cpuStagesDurationNs = 5_500_000L,
            )
            tracker.recordFrameMetric(
                nowElapsedMs = 1_100L + (i * 33L),
                totalDurationNs = 24_000_000L,
                deadlineNs = deadlineNs,
                gpuDurationNs = 2_000_000L,
                intendedVsyncNs = 200_000_000L,
                vsyncNs = 200_000_000L,
                cpuStagesDurationNs = 21_500_000L,
            )
        }
        val activeSnap = tracker.snapshotFrames(nowElapsedMs = 1_500L, defaultHz = 60)
        assertEquals(50.0f, activeSnap.jankyPercent5m, 0.01f)
        assertEquals(50.0f, activeSnap.latestIntervalJankPercent, 0.01f)

        // At t = 5,000ms (screen static for 3.8s): 60s sparkline latestIntervalJankPercent decays to 0%,
        // while the 5-minute cumulative jank % remains 50.0%.
        val idleSnap = tracker.snapshotFrames(nowElapsedMs = 5_000L, defaultHz = 60)
        assertEquals(50.0f, idleSnap.jankyPercent5m, 0.01f)
        assertEquals(0.0f, idleSnap.latestIntervalJankPercent, 0.001f)
        assertEquals("00:04 / 05:00", idleSnap.windowElapsedLabel)

        // At t = 305,000ms (> 300s after t = 1,000ms bucket): old jank ages out completely.
        val expiredSnap = tracker.snapshotFrames(nowElapsedMs = 305_000L, defaultHz = 60)
        assertEquals(0, expiredSnap.evaluatedFrames5m)
        assertEquals(0, expiredSnap.jankyFrames5m)
        assertEquals(0, expiredSnap.droppedFrames5m)
        assertEquals(0.0f, expiredSnap.jankyPercent5m, 0.001f)
        assertEquals(0.0f, expiredSnap.droppedPercent5m, 0.001f)
        assertEquals("00:00 / 05:00", expiredSnap.windowElapsedLabel)

        // Recording a fresh smooth frame after expiration starts a fresh window duration (00:01 / 05:00, not 05:00 / 05:00).
        tracker.recordFrameMetric(
            nowElapsedMs = 306_000L,
            totalDurationNs = 8_000_000L,
            deadlineNs = deadlineNs,
            gpuDurationNs = 2_000_000L,
            intendedVsyncNs = 500_000_000L,
            vsyncNs = 500_000_000L,
            cpuStagesDurationNs = 5_500_000L,
        )
        val freshWindowSnap = tracker.snapshotFrames(nowElapsedMs = 307_200L, defaultHz = 60)
        assertEquals(1, freshWindowSnap.evaluatedFrames5m)
        assertEquals(0f, freshWindowSnap.jankyPercent5m, 0.001f)
        assertEquals("00:01 / 05:00", freshWindowSnap.windowElapsedLabel)
    }

    @Test
    fun sixGhzDiagnosticsDistinguishFirmwareRegDomainBlockOsConfigDisableAndGenuineHardwareLack() {
        val sum24 = WirelessTelemetryParsers.summarizeAllowedBandChannels(
            bandGhz = 2,
            allowedFreqsWidths = (1..11).map { (2407 + it * 5) to 0 },
            usableFreqs = (1..11).map { 2407 + it * 5 },
            isBandSupported = true,
        )
        val sum5 = WirelessTelemetryParsers.summarizeAllowedBandChannels(
            bandGhz = 5,
            allowedFreqsWidths = listOf(5180 to 0, 5200 to 0, 5745 to 0, 5765 to 0),
            usableFreqs = listOf(5180, 5200, 5745, 5765),
            isBandSupported = true,
            supports11be = true,
        )

        // 1. Live Wi-Fi 7 (802.11be · iwlwifi) laptop in Canada ('CA') where WifiManager.is6GHzBandSupported == false
        //    because firmware LAR / wireless-regdb exposes 0 channels (and STA RRO config_wifi6ghzSupport=false).
        val dumpsysCaRaw = """
            ActiveModeWarden:
              SupportedChannelListIn24g[2412, 2437, 2462] SupportedChannelListIn5g[5180, 5745] SupportedChannelListIn6g[] mCountryCodeFromDriverCA
            WifiModule: iwlwifi 0000:00:14.3
            WifiCountryCode:
              mTelephonyCountryCode: null
              mDriverCountryCode: CA
            WifiResourceCache:
              Resource Name: config_wifi6ghzSupport, value: false
              Resource Name: config_wifiSoftap6ghzSupported, value: true
        """.trimIndent()
        val parsedMeta = WirelessTelemetryParsers.parseWifiDumpsysRegMetadata(dumpsysCaRaw)
        assertEquals("CA" to "Driver RegDomain (dumpsys)", parsedMeta.countryAndSource)
        assertTrue(parsedMeta.driver6GhzChannelListSeen)
        assertTrue(parsedMeta.driver6GhzChannelListEmpty)
        assertEquals(false, parsedMeta.wifiResConfig6GhzSta)
        assertEquals(true, parsedMeta.wifiResConfig6GhzSoftAp)
        assertTrue(parsedMeta.mentionsIwlwifi)

        val sum6CaFwBlocked = WirelessTelemetryParsers.evaluateSixGhzBandSummary(
            allowedFreqsWidths = emptyList(),
            usableFreqs = emptyList(),
            osReports6GhzSupported = false,
            supports11be = true,
            countryCode = "CA",
            wifiKernelDriver = "iwlwifi",
            wifiResConfig6GhzSta = false,
            wifiResConfig6GhzSoftAp = true,
            driver6GhzChannelListEmpty = true,
        )
        assertTrue(sum6CaFwBlocked.isHardwareSupported)
        assertEquals("Firmware Blocked (CA)", sum6CaFwBlocked.countBadge)
        assertEquals("Firmware / RegDomain Blocked (CA)", sum6CaFwBlocked.channelRangeSummary)
        assertTrue(sum6CaFwBlocked.subBandNote.contains("11be · iwlwifi"))
        assertTrue(sum6CaFwBlocked.subBandNote.contains("0 ch in CA"))
        assertTrue(sum6CaFwBlocked.subBandNote.contains("STA RRO off"))

        val caWifi7Caps = WifiRadioCapabilities(
            isCapabilitiesQueried = true,
            hasNearbyWifiPermission = true,
            channelQuerySucceeded = true,
            supports11beWifi7 = true,
            supports11axWifi6 = true,
            supports24Ghz = true,
            supports5Ghz = true,
            supports6Ghz = false,
            wifiKernelDriver = "iwlwifi",
            wifiResConfig6GhzSta = false,
            wifiResConfig6GhzSoftAp = true,
            driver6GhzChannelListEmpty = true,
            countryCode = "CA",
            countryCodeSource = "Driver RegDomain (dumpsys)",
            band24Ghz = sum24,
            band5Ghz = sum5,
            band6Ghz = sum6CaFwBlocked,
        )
        assertTrue(caWifi7Caps.is6GhzHardwareCapable)
        assertEquals(SixGhzSupportStatus.FIRMWARE_OR_REGDOMAIN_BLOCKED, caWifi7Caps.sixGhzStatus)
        assertEquals("2.4 GHz · 5 GHz · 6 GHz (HW Capable · Blocked in CA)", caWifi7Caps.supportedBandsSummary)
        assertNotNull(caWifi7Caps.sixGhzRegulatoryWarning)
        assertTrue(caWifi7Caps.sixGhzRegulatoryWarning!!.contains("6 GHz Hardware Capable"))
        assertTrue(caWifi7Caps.sixGhzRegulatoryWarning!!.contains("CA (Canada)"))
        assertTrue(caWifi7Caps.sixGhzRegulatoryWarning!!.contains("Intel LAR"))
        assertTrue(caWifi7Caps.sixGhzRegulatoryWarning!!.contains("config_wifi6ghzSupport=false"))

        val caReport = DiagnosticReportBuilder.buildMarkdownReport(
            SystemTelemetrySnapshot(wifi = WifiTelemetrySnapshot(isConnected = true, capabilities = caWifi7Caps)),
        )
        assertTrue(caReport.contains("6G `Blocked(CA)`"))
        assertTrue(caReport.contains("⚠️ 6 GHz RegDomain Blocked"))

        // 2. Wi-Fi 6E (802.11ax + RRO config_wifi6ghzSupport=true, no 11be) in unresolved World Safe-Mode ('00')
        val sum6World = WirelessTelemetryParsers.evaluateSixGhzBandSummary(
            allowedFreqsWidths = emptyList(),
            usableFreqs = emptyList(),
            osReports6GhzSupported = false,
            supports11be = false,
            countryCode = "00",
            wifiKernelDriver = "mt7921e",
            wifiResConfig6GhzSta = true,
        )
        assertTrue(sum6World.isHardwareSupported)
        assertEquals("Reg-Blocked ('00')", sum6World.countBadge)
        assertEquals("Blocked (World Domain '00')", sum6World.channelRangeSummary)
        assertTrue(sum6World.subBandNote.contains("6E RRO · mt7921e"))

        val worldWifi6eCaps = WifiRadioCapabilities(
            isCapabilitiesQueried = true,
            hasNearbyWifiPermission = true,
            channelQuerySucceeded = true,
            supports11beWifi7 = false,
            supports11axWifi6 = true,
            supports24Ghz = true,
            supports5Ghz = true,
            supports6Ghz = false,
            wifiKernelDriver = "mt7921e",
            wifiResConfig6GhzSta = true,
            countryCode = "00",
            countryCodeSource = "Driver RegDomain (dumpsys)",
            band24Ghz = sum24,
            band5Ghz = sum5,
            band6Ghz = sum6World,
        )
        assertTrue(worldWifi6eCaps.is6GhzHardwareCapable)
        assertEquals("Wi-Fi 6E (802.11ax)", worldWifi6eCaps.maxGenerationLabel)
        assertEquals(SixGhzSupportStatus.FIRMWARE_OR_REGDOMAIN_BLOCKED, worldWifi6eCaps.sixGhzStatus)
        assertTrue(worldWifi6eCaps.sixGhzRegulatoryWarning!!.contains("world safe-mode"))

        // 3. OS RRO Config Disabled in a 6 GHz-open region ('US', driver channel list non-empty)
        val sum6OsDisabled = WirelessTelemetryParsers.evaluateSixGhzBandSummary(
            allowedFreqsWidths = emptyList(),
            usableFreqs = emptyList(),
            osReports6GhzSupported = false,
            supports11be = true,
            countryCode = "US",
            wifiKernelDriver = "iwlwifi",
            wifiResConfig6GhzSta = false,
            wifiResConfig6GhzSoftAp = true,
            driver6GhzChannelListEmpty = false,
        )
        assertTrue(sum6OsDisabled.isHardwareSupported)
        assertEquals("Disabled (OS RRO)", sum6OsDisabled.countBadge)
        assertEquals("Disabled by OS Wi-Fi Config", sum6OsDisabled.channelRangeSummary)

        val usOsDisabledCaps = caWifi7Caps.copy(
            countryCode = "US",
            driver6GhzChannelListEmpty = false,
            band6Ghz = sum6OsDisabled,
        )
        assertEquals(SixGhzSupportStatus.OS_CONFIG_DISABLED, usOsDisabledCaps.sixGhzStatus)
        assertEquals("2.4 GHz · 5 GHz · 6 GHz (HW Capable · Disabled by OS Config)", usOsDisabledCaps.supportedBandsSummary)
        assertTrue(usOsDisabledCaps.sixGhzRegulatoryWarning!!.contains("Disabled by OS Config"))
        val usOsReport = DiagnosticReportBuilder.buildMarkdownReport(
            SystemTelemetrySnapshot(wifi = WifiTelemetrySnapshot(isConnected = true, capabilities = usOsDisabledCaps)),
        )
        assertTrue(usOsReport.contains("6G `Disabled(OS)`"))
        assertTrue(usOsReport.contains("⚠️ 6 GHz Disabled by OS Config"))

        // 4. Genuine Dual-Band 2.4/5 GHz-Only Hardware (Wi-Fi 6 802.11ax without 6 GHz PHY) in Canada ('CA')
        val sum6NoHw = WirelessTelemetryParsers.evaluateSixGhzBandSummary(
            allowedFreqsWidths = emptyList(),
            usableFreqs = emptyList(),
            osReports6GhzSupported = false,
            supports11be = false,
            countryCode = "CA",
            wifiKernelDriver = "iwlwifi",
            wifiResConfig6GhzSta = false,
            wifiResConfig6GhzSoftAp = false,
        )
        assertFalse(sum6NoHw.isHardwareSupported)
        assertEquals("Hardware Unsupported", sum6NoHw.countBadge)
        assertEquals("Hardware Unsupported", sum6NoHw.channelRangeSummary)
        assertEquals("2.4 / 5 GHz radio only (no 6 GHz PHY)", sum6NoHw.subBandNote)

        val dualBandOnlyCaps = WifiRadioCapabilities(
            isCapabilitiesQueried = true,
            hasNearbyWifiPermission = true,
            channelQuerySucceeded = true,
            supports11beWifi7 = false,
            supports11axWifi6 = true,
            supports24Ghz = true,
            supports5Ghz = true,
            supports6Ghz = false,
            wifiKernelDriver = "iwlwifi",
            wifiResConfig6GhzSta = false,
            wifiResConfig6GhzSoftAp = false,
            countryCode = "CA",
            countryCodeSource = "Driver RegDomain (dumpsys)",
            band24Ghz = sum24,
            band5Ghz = sum5,
            band6Ghz = sum6NoHw,
        )
        assertFalse(dualBandOnlyCaps.is6GhzHardwareCapable)
        assertEquals(SixGhzSupportStatus.HARDWARE_UNSUPPORTED, dualBandOnlyCaps.sixGhzStatus)
        assertEquals("Wi-Fi 6 (802.11ax)", dualBandOnlyCaps.maxGenerationLabel)
        assertEquals("2.4 GHz · 5 GHz (Dual-Band)", dualBandOnlyCaps.supportedBandsSummary)
        assertNull(dualBandOnlyCaps.sixGhzRegulatoryWarning)

        val dualBandReport = DiagnosticReportBuilder.buildMarkdownReport(
            SystemTelemetrySnapshot(wifi = WifiTelemetrySnapshot(isConnected = true, capabilities = dualBandOnlyCaps)),
        )
        assertTrue(dualBandReport.contains("6G `Unsupported`"))
        assertFalse(dualBandReport.contains("⚠️ 6 GHz"))
    }

    @Test
    fun systemWideGraphicsStatsParserAndTrackerBaselineDeltasVrrAndFallbackWorkAccurately() {
        // 1. Permission Denial / blank safety
        assertTrue(GraphicsStatsParsers.parseDumpsysGraphicsStats("").isEmpty())
        assertTrue(
            GraphicsStatsParsers.parseDumpsysGraphicsStats(
                "Permission Denial: can't dump graphicsstats from from pid=1234, uid=10269",
            ).isEmpty(),
        )

        // 2. Parse multi-UID (HSUM), multi-day rotated buckets, and zero-frame (-nan%) blocks
        val dumpT0 = """
            UID: 10204
            Package: com.android.systemui
            Version: 37
            Stats since: 1793664000014ns
            Stats end: 1793715546093ns
            Total frames rendered: 192607
            Janky frames: 20 (0.01%)
            50th percentile: 5ms
            90th percentile: 5ms
            95th percentile: 5ms
            99th percentile: 5ms
            Number Missed Vsync: 2
            Number High input latency: 1473
            Number Slow UI thread: 9
            Number Slow bitmap uploads: 0
            Number Slow issue draw commands: 7
            Number Frame deadline missed: 20
            HISTOGRAM: 5ms=191662 6ms=583 7ms=150 8ms=192 20ms=10 53ms=10 4950ms=0
            50th gpu percentile: 1ms
            90th gpu percentile: 2ms
            95th gpu percentile: 2ms
            99th gpu percentile: 3ms
            GPU HISTOGRAM: 1ms=112524 2ms=80083 4950ms=0

            UID: 1010204
            Package: com.android.systemui
            Version: 37
            Stats since: 1793664000015ns
            Stats end: 1793715546093ns
            Total frames rendered: 317
            Janky frames: 0 (0.00%)
            Number Missed Vsync: 0
            Number High input latency: 317
            Number Slow UI thread: 0
            Number Slow bitmap uploads: 0
            Number Slow issue draw commands: 0
            Number Frame deadline missed: 0
            HISTOGRAM: 5ms=311 6ms=6 4950ms=0
            GPU HISTOGRAM: 1ms=317 4950ms=0

            UID: 1010180
            Package: com.android.chrome
            Version: 6778200
            Stats since: 1793664000020ns
            Stats end: 1793715546093ns
            Total frames rendered: 50000
            Janky frames: 120 (0.24%)
            Number Missed Vsync: 10
            Number High input latency: 200
            Number Slow UI thread: 50
            Number Slow bitmap uploads: 5
            Number Slow issue draw commands: 40
            Number Frame deadline missed: 110
            HISTOGRAM: 6ms=49880 18ms=120 4950ms=0
            GPU HISTOGRAM: 2ms=49950 12ms=50 4950ms=0

            UID: 1001000
            Package: com.android.settings
            Version: 37
            Stats since: 1793664000016ns
            Stats end: 1793715546093ns
            Total frames rendered: 0
            Janky frames: 0 (-nan%)
            50th gpu percentile: 4950ms
            HISTOGRAM: 5ms=0 4950ms=0
            GPU HISTOGRAM: 1ms=0 4950ms=0
        """.trimIndent()

        val parsedT0 = GraphicsStatsParsers.parseDumpsysGraphicsStats(dumpT0)
        assertEquals(4, parsedT0.size)
        assertTrue(parsedT0[3].cpuHistogramMsCounts.isEmpty())
        assertTrue(parsedT0[3].gpuHistogramMsCounts.isEmpty())

        val tracker = VrrAndFrameWindowTracker(windowHorizonMs = 300_000L)
        // Also record 1 app-window FrameMetrics frame so we can verify fallback vs system-wide separation
        tracker.recordFrameMetric(
            nowElapsedMs = 900L,
            totalDurationNs = 6_000_000L,
            deadlineNs = 8_333_333L,
            gpuDurationNs = 1_500_000L,
            intendedVsyncNs = 10_000_000L,
            vsyncNs = 10_000_000L,
        )

        // 3. First GraphicsStats sample at t=1000ms establishes baseline with 0 historical leak!
        tracker.recordGraphicsStatsSample(nowElapsedMs = 1_000L, records = parsedT0, effectiveHz = 120f)
        val snapBaseline = tracker.snapshotFrames(nowElapsedMs = 1_000L, defaultHz = 120, hasDumpPermission = true)
        assertTrue(snapBaseline.isSystemWideGraphicsStats)
        assertTrue(snapBaseline.isSystemWide)
        assertEquals("SYSTEM-WIDE (GRAPHICSSTATS)", snapBaseline.scopeBadgeLabel)
        assertEquals(0, snapBaseline.evaluatedFrames5m)
        assertEquals(0, snapBaseline.droppedFrames5m)
        assertEquals(0, snapBaseline.jankyFrames5m)
        assertEquals(0, snapBaseline.activeAppCount5m)

        // 4. Second GraphicsStats sample at t=3000ms across 3 active apps (120 Hz panel -> 8.333ms period):
        // - SystemUI (UID 10204): +120 frames, +2 janky (1 at 20ms -> 2 missed slots, 1 at 53ms -> 6 missed slots = 8 dropped slots; +1 slow UI, +1 missed vsync, +2 deadline missed)
        // - SystemUI (UID 1010204): +30 smooth frames (same package name -> still 1 distinct app for systemui)
        // - Chrome (UID 1010180): +60 frames, +1 janky (1 at 18ms -> 2 missed slots, 0 slow CPU, 0 GPU overrun, +1 deadline missed -> +1 buffer stuffing!)
        // - Newly launched app (com.example.editor, statsSince >= baselineMax): 40 frames, 1 janky (1 at 26ms -> 3 missed slots, GPU 12ms=1 -> +1 long GPU)
        val dumpT1 = """
            UID: 10204
            Package: com.android.systemui
            Version: 37
            Stats since: 1793664000014ns
            Stats end: 1793717546093ns
            Total frames rendered: 192727
            Janky frames: 22 (0.01%)
            Number Missed Vsync: 3
            Number High input latency: 1473
            Number Slow UI thread: 10
            Number Slow bitmap uploads: 0
            Number Slow issue draw commands: 7
            Number Frame deadline missed: 22
            HISTOGRAM: 5ms=191780 6ms=583 7ms=150 8ms=192 20ms=11 53ms=11 4950ms=0
            GPU HISTOGRAM: 1ms=112644 2ms=80083 4950ms=0

            UID: 1010204
            Package: com.android.systemui
            Version: 37
            Stats since: 1793664000015ns
            Stats end: 1793717546093ns
            Total frames rendered: 347
            Janky frames: 0 (0.00%)
            Number Missed Vsync: 0
            Number High input latency: 317
            Number Slow UI thread: 0
            Number Slow bitmap uploads: 0
            Number Slow issue draw commands: 0
            Number Frame deadline missed: 0
            HISTOGRAM: 5ms=341 6ms=6 4950ms=0
            GPU HISTOGRAM: 1ms=347 4950ms=0

            UID: 1010180
            Package: com.android.chrome
            Version: 6778200
            Stats since: 1793664000020ns
            Stats end: 1793717546093ns
            Total frames rendered: 50060
            Janky frames: 121 (0.24%)
            Number Missed Vsync: 10
            Number High input latency: 200
            Number Slow UI thread: 50
            Number Slow bitmap uploads: 5
            Number Slow issue draw commands: 40
            Number Frame deadline missed: 111
            HISTOGRAM: 6ms=49939 18ms=121 4950ms=0
            GPU HISTOGRAM: 2ms=50010 12ms=50 4950ms=0

            UID: 1010350
            Package: com.example.editor
            Version: 1
            Stats since: 1793716000000ns
            Stats end: 1793717546093ns
            Total frames rendered: 40
            Janky frames: 1 (2.50%)
            Number Missed Vsync: 0
            Number High input latency: 0
            Number Slow UI thread: 0
            Number Slow bitmap uploads: 0
            Number Slow issue draw commands: 0
            Number Frame deadline missed: 1
            HISTOGRAM: 6ms=39 26ms=1
            GPU HISTOGRAM: 2ms=39 12ms=1
        """.trimIndent()

        val parsedT1 = GraphicsStatsParsers.parseDumpsysGraphicsStats(dumpT1)
        tracker.recordGraphicsStatsSample(nowElapsedMs = 3_000L, records = parsedT1, effectiveHz = 120f)
        val snapT1 = tracker.snapshotFrames(nowElapsedMs = 3_000L, defaultHz = 120, hasDumpPermission = true)
        assertEquals(250, snapT1.evaluatedFrames5m) // 120 + 30 + 60 + 40
        assertEquals(4, snapT1.jankyFrames5m) // 2 + 0 + 1 + 1
        assertEquals(13, snapT1.droppedFrames5m) // 8 + 0 + 2 + 3
        assertEquals(3, snapT1.activeAppCount5m) // systemui, chrome, editor
        assertEquals(1, snapT1.longCpuJank5m)
        assertEquals(1, snapT1.longGpuJank5m)
        assertEquals(1, snapT1.bufferStuffing5m)
        assertEquals(1, snapT1.sfMissedFrames5m)
        assertEquals("00:02 / 05:00", snapT1.windowElapsedLabel)
        assertTrue(snapT1.windowSummaryLabel.contains("250 evaluated frames (3 active apps)"))

        // Verify Markdown export includes system-wide scope and app count WITHOUT leaking any package names
        val report = DiagnosticReportBuilder.buildMarkdownReport(SystemTelemetrySnapshot(frameJank = snapT1))
        assertTrue(report.contains("`System-Wide GraphicsStats, 3 apps`"))
        assertFalse(report.contains("com.android.chrome"))
        assertFalse(report.contains("com.example.editor"))

        // 5. Automatic fallback to APP WINDOW (FRAMEMETRICS) when DUMP permission is not granted
        val fallbackSnap = tracker.snapshotFrames(nowElapsedMs = 3_000L, defaultHz = 120, hasDumpPermission = false)
        assertFalse(fallbackSnap.isSystemWideGraphicsStats)
        assertFalse(fallbackSnap.isSystemWide)
        assertEquals("APP WINDOW (FRAMEMETRICS)", fallbackSnap.scopeBadgeLabel)
        assertEquals(1, fallbackSnap.evaluatedFrames5m)

        // 6. VRR 60Hz budget inference on a 120Hz panel: 59 smooth 12ms frames + 1 janky 20ms frame -> 1 missed 60Hz VSync (not 2)
        val vrrTracker = VrrAndFrameWindowTracker(windowHorizonMs = 300_000L)
        val vrrKey = GraphicsStatsKey(1010500, "com.example.video60", 1L, 1000L)
        vrrTracker.recordGraphicsStatsSample(
            nowElapsedMs = 1_000L,
            records = listOf(PackageGraphicsStatsRecord(key = vrrKey, totalFrames = 100L, jankyFrames = 0L, cpuHistogramMsCounts = mapOf(12 to 100L))),
            effectiveHz = 120f,
        )
        // Smooth 60Hz frames (12..15ms, 0 janky) -> 0 dropped
        vrrTracker.recordGraphicsStatsSample(
            nowElapsedMs = 2_000L,
            records = listOf(PackageGraphicsStatsRecord(key = vrrKey, totalFrames = 160L, jankyFrames = 0L, cpuHistogramMsCounts = mapOf(12 to 130L, 15 to 30L))),
            effectiveHz = 120f,
        )
        val smoothVrr = vrrTracker.snapshotFrames(nowElapsedMs = 2_000L, defaultHz = 120, hasDumpPermission = true)
        assertEquals(60, smoothVrr.evaluatedFrames5m)
        assertEquals(0, smoothVrr.jankyFrames5m)
        assertEquals(0, smoothVrr.droppedFrames5m)

        // Now 59 smooth 12ms frames + 1 late 20ms frame -> infers 60Hz (16.67ms) budget -> 1 missed VSync
        vrrTracker.recordGraphicsStatsSample(
            nowElapsedMs = 3_000L,
            records = listOf(
                PackageGraphicsStatsRecord(
                    key = vrrKey,
                    totalFrames = 220L,
                    jankyFrames = 1L,
                    frameDeadlineMissed = 1L,
                    slowUiThread = 1L,
                    cpuHistogramMsCounts = mapOf(12 to 189L, 15 to 30L, 20 to 1L),
                ),
            ),
            effectiveHz = 120f,
        )
        val oneLate60Hz = vrrTracker.snapshotFrames(nowElapsedMs = 3_000L, defaultHz = 120, hasDumpPermission = true)
        assertEquals(120, oneLate60Hz.evaluatedFrames5m)
        assertEquals(1, oneLate60Hz.jankyFrames5m)
        assertEquals(1, oneLate60Hz.droppedFrames5m)

        // 7. Rolling 5-minute expiration (> 300_000ms) prunes expired slices cleanly
        val expired = vrrTracker.snapshotFrames(nowElapsedMs = 305_000L, defaultHz = 120, hasDumpPermission = true)
        assertEquals(0, expired.evaluatedFrames5m)
        assertEquals(0, expired.droppedFrames5m)
        assertEquals(0, expired.activeAppCount5m)
        assertEquals("05:00 / 05:00", expired.windowElapsedLabel)
    }
}
