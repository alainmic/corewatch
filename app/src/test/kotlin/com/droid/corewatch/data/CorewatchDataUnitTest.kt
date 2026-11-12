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
        assertEquals(0, idleGpu.actualFreqMhz)
        assertTrue(idleGpu.actualFreqAvailable)
        assertEquals("Idle (RC6)", idleGpu.headlineFreqLabel)
        assertEquals("1%", idleGpu.headlineBusyLabel)
        assertEquals(800, idleGpu.requestedFreqMhz)
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
        assertEquals("1800 MHz", throttledGpu.headlineFreqLabel)
        assertNull(throttledGpu.utilizationFraction)
        assertEquals("—", throttledGpu.headlineBusyLabel)

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
        assertTrue(idleNpu.isPresent)
        assertEquals("d3cold-sleep", idleNpu.scalingStateLabel)
        assertEquals(2000, idleNpu.maxFreqMhz)
        assertEquals(0, idleNpu.currentFreqMhz)

        val missingNpu = SysfsParsers.parseNpuTelemetry(
            attrs = emptyMap(),
            drmVersion = null,
            ivpuParams = null,
            busyDeltaFraction = null,
            heartbeatActive = false,
        )
        assertFalse(missingNpu.isPresent)
        assertEquals("not-present", missingNpu.scalingStateLabel)
        assertEquals("none", missingNpu.driverName)
        assertEquals("No NPU Present on SoC", missingNpu.deviceName)
        assertEquals(0, missingNpu.maxFreqMhz)
        assertEquals(0, missingNpu.currentFreqMhz)
        assertEquals(0, missingNpu.maxContexts)
        assertEquals(0f, missingNpu.utilizationFraction, 0.001f)
        val missingNpuReport = DiagnosticReportBuilder.buildMarkdownReport(SystemTelemetrySnapshot(npu = missingNpu))
        assertTrue(missingNpuReport.contains("No NPU Present on SoC"))

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

        // Without DUMP permission, frame metrics stay zeroed and locked (never reading self app-window metrics).
        val lockedSnap = tracker.snapshotFrames(2_500L, defaultHz = 120, hasDumpPermission = false)
        assertEquals(0, lockedSnap.evaluatedFrames5m)
        assertEquals(0, lockedSnap.jankyFrames5m)
        assertEquals(0, lockedSnap.droppedFrames5m)
        assertEquals("SYSTEM-WIDE (LOCKED)", lockedSnap.scopeBadgeLabel)
        assertEquals("[120] Hz", lockedSnap.deadlineRangeLabel)
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
    fun frameJankTrackerZeroedWhenDumpPermissionLockedOrInitializing() {
        val tracker = VrrAndFrameWindowTracker(windowHorizonMs = 300_000L)
        val lockedSnap = tracker.snapshotFrames(nowElapsedMs = 1_000L, defaultHz = 60, hasDumpPermission = false)
        assertFalse(lockedSnap.hasDumpPermission)
        assertFalse(lockedSnap.isSystemWide)
        assertEquals("SYSTEM-WIDE (LOCKED)", lockedSnap.scopeBadgeLabel)
        assertEquals(0, lockedSnap.evaluatedFrames5m)
        assertEquals(0, lockedSnap.expectedFrames5m)
        assertEquals(0, lockedSnap.droppedFrames5m)
        assertEquals(0, lockedSnap.jankyFrames5m)
        assertEquals(0f, lockedSnap.droppedPercent5m, 0.001f)
        assertEquals(0f, lockedSnap.jankyPercent5m, 0.001f)
        assertEquals(0f, lockedSnap.latestIntervalJankPercent, 0.001f)
        assertEquals("00:00 / 05:00", lockedSnap.windowElapsedLabel)
        assertEquals("[60] Hz", lockedSnap.deadlineRangeLabel)

        val initSnap = tracker.snapshotFrames(nowElapsedMs = 1_200L, defaultHz = 120, hasDumpPermission = true)
        assertTrue(initSnap.hasDumpPermission)
        assertFalse(initSnap.isSystemWide)
        assertEquals("SYSTEM-WIDE (INITIALIZING)", initSnap.scopeBadgeLabel)
        assertEquals(0, initSnap.evaluatedFrames5m)
        assertEquals(0f, initSnap.droppedPercent5m, 0.001f)
        assertEquals(0f, initSnap.jankyPercent5m, 0.001f)
        assertEquals("[120] Hz", initSnap.deadlineRangeLabel)
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

            UID: 1010279
            Package: com.droid.corewatch
            Version: 4
            Stats since: 1793664000025ns
            Stats end: 1793715546093ns
            Total frames rendered: 100
            Janky frames: 20 (20.00%)
            Number Slow UI thread: 20
            Number Frame deadline missed: 20
            HISTOGRAM: 5ms=80 28ms=20
            GPU HISTOGRAM: 2ms=100
        """.trimIndent()

        val parsedT0 = GraphicsStatsParsers.parseDumpsysGraphicsStats(dumpT0)
        assertEquals(5, parsedT0.size)
        assertTrue(parsedT0[3].cpuHistogramMsCounts.isEmpty())
        assertTrue(parsedT0[3].gpuHistogramMsCounts.isEmpty())

        val tracker = VrrAndFrameWindowTracker(windowHorizonMs = 300_000L)

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
        // - Self package (com.droid.corewatch): +250 frames, +60 janky with 300ms stalls -> MUST be excluded!
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

            UID: 1010279
            Package: com.droid.corewatch
            Version: 4
            Stats since: 1793664000025ns
            Stats end: 1793717546093ns
            Total frames rendered: 350
            Janky frames: 80 (22.86%)
            Number Slow UI thread: 80
            Number Frame deadline missed: 80
            HISTOGRAM: 5ms=270 28ms=75 300ms=5
            GPU HISTOGRAM: 2ms=350
        """.trimIndent()

        val parsedT1 = GraphicsStatsParsers.parseDumpsysGraphicsStats(dumpT1)
        tracker.recordGraphicsStatsSample(nowElapsedMs = 3_000L, records = parsedT1, effectiveHz = 120f)
        val snapT1 = tracker.snapshotFrames(nowElapsedMs = 3_000L, defaultHz = 120, hasDumpPermission = true)
        assertEquals(250, snapT1.evaluatedFrames5m) // 120 + 30 + 60 + 40 (com.droid.corewatch excluded!)
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

        // 5. Locked state (hasDumpPermission = false): zeros out metrics instead of reading Corewatch's own window
        val lockedSnap = tracker.snapshotFrames(nowElapsedMs = 3_000L, defaultHz = 120, hasDumpPermission = false)
        assertFalse(lockedSnap.isSystemWideGraphicsStats)
        assertFalse(lockedSnap.isSystemWide)
        assertEquals("SYSTEM-WIDE (LOCKED)", lockedSnap.scopeBadgeLabel)
        assertEquals(0, lockedSnap.evaluatedFrames5m)
        assertEquals(0, lockedSnap.droppedFrames5m)
        assertEquals(0, lockedSnap.jankyFrames5m)
        assertEquals(0f, lockedSnap.droppedPercent5m, 0.001f)
        assertEquals(0f, lockedSnap.jankyPercent5m, 0.001f)
        val lockedReport = DiagnosticReportBuilder.buildMarkdownReport(SystemTelemetrySnapshot(frameJank = lockedSnap))
        assertTrue(lockedReport.contains("System-Wide GraphicsStats Locked"))

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

    @Test
    fun graphicsStatsHandlesConsecutiveMultiVsyncFreezesGpuThresholdsAndResurrectedStreams() {
        val tracker = VrrAndFrameWindowTracker(windowHorizonMs = 300_000L)
        val probeKey = GraphicsStatsKey(1010270, "com.example.probe", 1L, 1_793_664_000_014L)
        // Baseline t=0 with statsEndNs = 1_793_720_270_000
        tracker.recordGraphicsStatsSample(
            nowElapsedMs = 1_000L,
            records = listOf(
                PackageGraphicsStatsRecord(
                    key = probeKey,
                    statsEndNs = 1_793_720_270_000L,
                    totalFrames = 100L,
                    jankyFrames = 0L,
                    cpuHistogramMsCounts = mapOf(5 to 100L),
                    gpuHistogramMsCounts = mapOf(2 to 100L),
                ),
            ),
            effectiveHz = 120f,
        )

        // Case A: Pure CPU 28ms UI-thread jank (30/120 frames) where GPU completes in 5ms (< 8.33ms 120Hz budget)
        // Must report Long CPU = 30 and Long GPU = 0 (no false-positive 5ms GPU overrun).
        tracker.recordGraphicsStatsSample(
            nowElapsedMs = 3_000L,
            records = listOf(
                PackageGraphicsStatsRecord(
                    key = probeKey,
                    statsEndNs = 1_793_720_272_000L,
                    totalFrames = 220L,
                    jankyFrames = 30L,
                    slowUiThread = 30L,
                    frameDeadlineMissed = 30L,
                    cpuHistogramMsCounts = mapOf(5 to 190L, 28 to 30L),
                    gpuHistogramMsCounts = mapOf(2 to 215L, 5 to 5L),
                ),
            ),
            effectiveHz = 120f,
        )
        val cpuJankSnap = tracker.snapshotFrames(nowElapsedMs = 3_000L, defaultHz = 120, hasDumpPermission = true)
        assertEquals(120, cpuJankSnap.evaluatedFrames5m)
        assertEquals(30, cpuJankSnap.jankyFrames5m)
        assertEquals(90, cpuJankSnap.droppedFrames5m) // 30 * ceil((28 - 8.333)/8.333) = 30 * 3
        assertEquals(30, cpuJankSnap.longCpuJank5m)
        assertEquals(0, cpuJankSnap.longGpuJank5m)

        // Case B: Consecutive severe 150..300ms UI freezes (20 frames, HWUI rolling average marks 10 janky)
        // Must NOT inflate pkgPeriodMs to 250ms; all 20 >=85ms freezes count missed 120Hz VSync slots (19*35 + 1*17 = 682).
        tracker.reset()
        tracker.recordGraphicsStatsSample(
            nowElapsedMs = 10_000L,
            records = listOf(PackageGraphicsStatsRecord(key = probeKey, statsEndNs = 1_793_720_280_000L, totalFrames = 220L, jankyFrames = 30L)),
            effectiveHz = 120f,
        )
        tracker.recordGraphicsStatsSample(
            nowElapsedMs = 13_000L,
            records = listOf(
                PackageGraphicsStatsRecord(
                    key = probeKey,
                    statsEndNs = 1_793_720_283_000L,
                    totalFrames = 240L,
                    jankyFrames = 40L,
                    slowUiThread = 10L,
                    frameDeadlineMissed = 10L,
                    cpuHistogramMsCounts = mapOf(300 to 19L, 150 to 1L),
                    gpuHistogramMsCounts = mapOf(3 to 20L),
                ),
            ),
            effectiveHz = 120f,
        )
        val freezeSnap = tracker.snapshotFrames(nowElapsedMs = 13_000L, defaultHz = 120, hasDumpPermission = true)
        assertEquals(20, freezeSnap.evaluatedFrames5m)
        assertEquals(10, freezeSnap.jankyFrames5m)
        assertEquals(682, freezeSnap.droppedFrames5m)
        assertTrue(freezeSnap.droppedPercent5m > 95f)
        assertEquals(10, freezeSnap.longCpuJank5m)
        assertEquals(0, freezeSnap.longGpuJank5m)

        // Case C: Heavy GPU shader burst where RenderThread issueDrawCommands blocks on GPU fence (slowDraw=40, gpu=40, slowUi=3)
        // Plus a resurrected pre-reset UID stream (statsSinceNs < lastDumpWallNs) that must NOT leak historical frames.
        tracker.reset()
        tracker.recordGraphicsStatsSample(
            nowElapsedMs = 20_000L,
            records = listOf(PackageGraphicsStatsRecord(key = probeKey, statsEndNs = 1_793_720_300_000L, totalFrames = 240L, jankyFrames = 40L)),
            effectiveHz = 120f,
        )
        val resurrectedKey = GraphicsStatsKey(1010271, "com.example.reinstalled", 1L, 1_793_720_150_000L) // < 1_793_720_300_000L!
        tracker.recordGraphicsStatsSample(
            nowElapsedMs = 23_000L,
            records = listOf(
                PackageGraphicsStatsRecord(
                    key = probeKey,
                    statsEndNs = 1_793_720_303_000L,
                    totalFrames = 305L,
                    jankyFrames = 80L,
                    slowUiThread = 3L,
                    slowIssueDrawCommands = 40L,
                    frameDeadlineMissed = 40L,
                    cpuHistogramMsCounts = mapOf(6 to 25L, 200 to 40L),
                    gpuHistogramMsCounts = mapOf(2 to 25L, 4950 to 40L),
                ),
                PackageGraphicsStatsRecord(
                    key = resurrectedKey,
                    statsEndNs = 1_793_720_303_000L,
                    totalFrames = 585L,
                    jankyFrames = 121L,
                ),
            ),
            effectiveHz = 120f,
        )
        val gpuSnap = tracker.snapshotFrames(nowElapsedMs = 23_000L, defaultHz = 120, hasDumpPermission = true)
        assertEquals(65, gpuSnap.evaluatedFrames5m) // 585 resurrected frames NOT leaked!
        assertEquals(40, gpuSnap.jankyFrames5m)
        assertEquals(40, gpuSnap.longGpuJank5m)
        assertEquals(3, gpuSnap.longCpuJank5m)
        assertEquals(1, gpuSnap.activeAppCount5m)
    }

    @Test
    fun gpuHeadlineActFreqNoResidencyThrottleReasonsVsyncUnavailableAndMtuFallback() {
        // Issue #1 & #2: act_freq = 0 with no residency -> "Idle (RC6)", null utilization, "rc6-sleep"
        val idleNoResidency = SysfsParsers.parseGpuTelemetry(
            attrs = mapOf(
                "act_freq" to "0",
                "cur_freq" to "900",
                "min_freq" to "850",
                "rpe_freq" to "900",
                "max_freq" to "2500",
            ),
            activeResidencyFraction = null,
        )
        assertTrue(idleNoResidency.actualFreqAvailable)
        assertEquals(0, idleNoResidency.actualFreqMhz)
        assertEquals(900, idleNoResidency.requestedFreqMhz)
        assertEquals(850, idleNoResidency.minFreqMhz)
        assertEquals("Idle (RC6)", idleNoResidency.headlineFreqLabel)
        assertNull(idleNoResidency.utilizationFraction)
        assertEquals("—", idleNoResidency.headlineBusyLabel)
        assertEquals("rc6-sleep", idleNoResidency.scalingStateLabel)

        // Issue #1 & #2: act_freq > 0 (300 / 1200 MHz) with no residency -> must NOT fake 25% busy or "balanced"
        val activeNoResidency = SysfsParsers.parseGpuTelemetry(
            attrs = mapOf(
                "act_freq" to "300",
                "cur_freq" to "300",
                "min_freq" to "300",
                "max_freq" to "1200",
            ),
            activeResidencyFraction = null,
            avgActualFreqMhz = 136,
        )
        assertTrue(activeNoResidency.actualFreqAvailable)
        assertEquals(300, activeNoResidency.actualFreqMhz)
        assertEquals(136, activeNoResidency.avgActualFreqMhz)
        assertEquals("300 MHz", activeNoResidency.headlineFreqLabel)
        assertNull(activeNoResidency.utilizationFraction)
        assertEquals("—", activeNoResidency.headlineBusyLabel)
        assertEquals("active", activeNoResidency.scalingStateLabel)

        // Issue #1: missing act_freq -> actualFreqAvailable = false, headline "—", state "unknown" without residency
        val missingActFreq = SysfsParsers.parseGpuTelemetry(
            attrs = mapOf(
                "cur_freq" to "850",
                "min_freq" to "850",
                "max_freq" to "2500",
            ),
            activeResidencyFraction = null,
        )
        assertFalse(missingActFreq.actualFreqAvailable)
        assertEquals(0, missingActFreq.actualFreqMhz)
        assertEquals("—", missingActFreq.headlineFreqLabel)
        assertNull(missingActFreq.utilizationFraction)
        assertEquals("unknown", missingActFreq.scalingStateLabel)

        // Issue #4: reason_vr_tdc + unknown reason_* suffix fallback
        val vrTdcThrottled = SysfsParsers.parseGpuTelemetry(
            attrs = mapOf(
                "act_freq" to "950",
                "cur_freq" to "1200",
                "max_freq" to "2500",
                "reason_vr_tdc" to "1",
                "reason_vr_edc" to "0",
                "reason_pbm_limit" to "1",
            ),
            activeResidencyFraction = 0.50f,
        )
        assertTrue(vrTdcThrottled.isThrottled)
        assertEquals(listOf("PBM_LIMIT", "VR_TDC"), vrTdcThrottled.throttleReasons)
        assertEquals("PBM_LIMIT, VR_TDC", vrTdcThrottled.throttleSummary)

        // Issue #5: Display.Mode.getVsyncRate() unavailable -> liveCadenceSummary labels Mode Hz + VSync unavailable
        val noVsyncDisplay = DisplayAuditor.auditDisplay(
            displayId = 0,
            rawName = "Built-in Screen",
            activeModeId = 1,
            activeWidth = 2560,
            activeHeight = 1600,
            activeRefreshHz = 120f,
            logicalDpi = 240,
            physicalXDpi = 227f,
            physicalYDpi = 227f,
            hdrTypeIds = intArrayOf(),
            modes = listOf(DisplayModeSpec(1, 2560, 1600, 120f, isActive = true)),
            renderFrameRateHz = 60f,
            vsyncRateHz = null,
            supportedRefreshRatesHz = floatArrayOf(60f, 120f),
            vrrCapable = true,
            androidArrAvailable = false,
        )
        assertNull(noVsyncDisplay.vsyncRateHz)
        assertFalse(noVsyncDisplay.androidArrAvailable)
        assertEquals("VRR Capable", noVsyncDisplay.vrrStatusBadge)
        assertEquals("Render 60 Hz · Mode 120 Hz (VSync rate unavailable)", noVsyncDisplay.liveCadenceSummary)

        // Issue #6 & DiagnosticReportBuilder: unknown MTU renders "—", GPU idle/unavailable & NPU unreadable counters format cleanly
        val unknownMtuWifi = WifiTelemetrySnapshot(isConnected = true, mtu = 0)
        val unknownMtuEth = EthernetTelemetrySnapshot(isConnected = true, interfaceName = "eth0", mtu = 0)
        assertEquals("—", unknownMtuWifi.mtuLabel)
        assertEquals("—", unknownMtuEth.mtuLabel)
        assertEquals("9000", EthernetTelemetrySnapshot(isConnected = true, mtu = 9000).mtuLabel)

        val lockedCountersNpu = SysfsParsers.parseNpuTelemetry(
            attrs = emptyMap(),
            drmVersion = DrmVersionInfo("intel_vpu", "Intel NPU", "1.0.0"),
            ivpuParams = AccelIoctlBridge.parseIvpuParamsArray(
                longArrayOf(0xB03EL, 7L, 2_000_000_000L, 64L, 0x00030025L, 0L, 3L, 0L),
            ),
            sysfsCountersReadable = false,
        )
        assertTrue(lockedCountersNpu.isPresent)
        assertFalse(lockedCountersNpu.sysfsCountersReadable)

        val md = DiagnosticReportBuilder.buildMarkdownReport(
            SystemTelemetrySnapshot(
                gpu = activeNoResidency,
                npu = lockedCountersNpu,
                ethernet = unknownMtuEth,
                displays = listOf(noVsyncDisplay),
            ),
        )
        assertTrue(md.contains("`—` (busy % unavailable)"))
        assertTrue(md.contains("`300 MHz`, avg `136 MHz`, requested `300 MHz`, floor `300 MHz`"))
        assertTrue(md.contains("`NPU counters not readable`"))
        assertTrue(md.contains("MTU `—`"))

        // Interval-aware GPU operating clock on 120 Hz desktop (inter-frame RC6 micro-sleep with act_freq = 0, residency = 3.5%)
        val interFrameGpu = SysfsParsers.parseGpuTelemetry(
            attrs = mapOf(
                "act_freq" to "0",
                "cur_freq" to "700",
                "min_freq" to "650",
                "rpe_freq" to "900",
                "max_freq" to "2500",
            ),
            activeResidencyFraction = 0.035f,
        )
        assertEquals(0, interFrameGpu.actualFreqMhz)
        assertEquals(700, interFrameGpu.operatingFreqMhz)
        assertEquals("700 MHz", interFrameGpu.headlineFreqLabel)
        assertEquals("4%", interFrameGpu.headlineBusyLabel)
        assertEquals("powersave", interFrameGpu.scalingStateLabel)

        // /dev/accel0 METRIC_STREAMER 11-element IvpuParamInfo (zero-wakeup D3cold idle + active DPU/NCE clock scaling)
        assertEquals(0, IvpuParamInfo.normalizeRawClockToMhz(0L))
        assertEquals(950, IvpuParamInfo.normalizeRawClockToMhz(950_000L))
        assertEquals(543, IvpuParamInfo.normalizeRawClockToMhz(542_857L))
        assertEquals(2000, IvpuParamInfo.normalizeRawClockToMhz(2_000_000_000L))
        assertEquals(1400, IvpuParamInfo.normalizeRawClockToMhz(1400L))

        val idleIoctlNpu = SysfsParsers.parseNpuTelemetry(
            attrs = emptyMap(),
            drmVersion = DrmVersionInfo("intel_vpu", "Intel NPU", "1.0.0"),
            ivpuParams = AccelIoctlBridge.parseIvpuParamsArray(
                longArrayOf(0xB03EL, 0L, 2_000_000_000L, 64L, 0x0003000AL, 0L, 3L, 0L, 0L, 0L, 1L),
            ),
            sysfsCountersReadable = false,
        )
        assertTrue(idleIoctlNpu.hasLiveClock)
        assertEquals(0, idleIoctlNpu.currentFreqMhz)
        assertEquals("0% · Idle (D3cold)", idleIoctlNpu.headlineLabel)
        assertEquals("d3cold-sleep", idleIoctlNpu.scalingStateLabel)

        val activeIoctlNpu = SysfsParsers.parseNpuTelemetry(
            attrs = emptyMap(),
            drmVersion = DrmVersionInfo("intel_vpu", "Intel NPU", "1.0.0"),
            ivpuParams = AccelIoctlBridge.parseIvpuParamsArray(
                longArrayOf(0xB03EL, 0L, 2_000_000_000L, 64L, 0x0003000AL, 44L, 3L, 2L, 950_000L, 542_857L, 1L),
            ),
            sysfsCountersReadable = false,
        )
        assertTrue(activeIoctlNpu.hasLiveClock)
        assertEquals(950, activeIoctlNpu.currentFreqMhz)
        assertEquals(543, activeIoctlNpu.nceFreqMhz)
        assertEquals("Active · 950 / 2000 MHz", activeIoctlNpu.headlineLabel)
        assertEquals("active", activeIoctlNpu.scalingStateLabel)

        val mdIdleIoctl = DiagnosticReportBuilder.buildMarkdownReport(SystemTelemetrySnapshot(npu = idleIoctlNpu))
        assertTrue(mdIdleIoctl.contains("`0%` idle (`0 / 2000 MHz` D3cold"))
        val mdActiveIoctl = DiagnosticReportBuilder.buildMarkdownReport(SystemTelemetrySnapshot(gpu = interFrameGpu, npu = activeIoctlNpu))
        assertTrue(mdActiveIoctl.contains("`4%` active (`700 MHz`"))
        assertTrue(mdActiveIoctl.contains("`Active` (`950 / 2000 MHz`, NCE `543 MHz`"))
    }
}
