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
import com.droid.corewatch.data.EthernetTelemetrySnapshot
import com.droid.corewatch.data.FloatRingBuffer
import com.droid.corewatch.data.SysfsParsers
import com.droid.corewatch.data.SystemTelemetrySnapshot
import com.droid.corewatch.data.TypeCPortStatus
import com.droid.corewatch.data.UsbHotplugTracker
import com.droid.corewatch.data.UsbPeripheralParsers
import com.droid.corewatch.data.UsbPeripheralTelemetrySnapshot
import com.droid.corewatch.data.UsbVendorLookup
import com.droid.corewatch.data.WifiRadioCapabilities
import com.droid.corewatch.data.WifiTelemetrySnapshot
import com.droid.corewatch.data.WirelessTelemetryParsers
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
                wifi = WifiTelemetrySnapshot(
                    isConnected = r.nextBoolean(),
                    interfaceName = randomStr(r, 20),
                    routeRoleLabel = randomStr(r, 24),
                    wifiStandardLabel = randomStr(r, 24),
                    bandAndChannelLabel = randomStr(r, 24),
                    rssiDbm = r.nextInt(-130, 10),
                    capabilities = WifiRadioCapabilities(
                        isCapabilitiesQueried = r.nextBoolean(),
                        channelQuerySucceeded = r.nextBoolean(),
                        supports6Ghz = r.nextBoolean(),
                        countryCode = randomStr(r, 12),
                        countryCodeSource = randomStr(r, 24),
                    ),
                ),
                ethernet = EthernetTelemetrySnapshot(
                    isConnected = r.nextBoolean(),
                    interfaceName = randomStr(r, 20),
                    isPrimaryDefaultRoute = r.nextBoolean(),
                    mtu = r.nextInt(0, 10_000),
                    negotiatedSpeedMbps = r.nextInt(0, 10_000),
                    linkSpeedTierLabel = randomStr(r, 24),
                    ipStackLabel = randomStr(r, 24),
                    dnsSummaryLabel = randomStr(r, 24),
                ),
                activeDefaultRouteSummary = randomStr(r, 28),
                usbPeripherals = UsbPeripheralTelemetrySnapshot(
                    externalPeripheralCount = r.nextInt(0, 20),
                    externalHubCount = r.nextInt(0, 8),
                    warningCount = r.nextInt(0, 5),
                    maxExternalSpeedLabel = randomStr(r, 18),
                    maxControllerSpeedLabel = randomStr(r, 18),
                    hasTypeCPorts = r.nextBoolean(),
                    typeCPorts = if (r.nextBoolean()) listOf(TypeCPortStatus(portId = randomStr(r, 12), isConnected = r.nextBoolean())) else emptyList(),
                ),
            )
            val md = DiagnosticReportBuilder.buildMarkdownReport(snap)
            assertTrue(md.startsWith("### Corewatch Hardware & Thermal Diagnostic Snapshot"))
            // Every table row must have balanced pipe delimiters
            val tableLines = md.lines().filter { it.startsWith("|") }
            assertEqualsCount(18, tableLines.size)
            for (line in tableLines) {
                assertEqualsCount(3, line.count { it == '|' })
            }
        }
    }

    @Test
    fun fuzzUsbPeripheralTopologyAndDumpsysParsers() {
        val r = Random(0x55534236L)
        val tracker = UsbHotplugTracker(capacity = 16)
        repeat(15_000) { iter ->
            val rawDevs = List(r.nextInt(7)) { idx ->
                val idChoice = when (r.nextInt(5)) {
                    0 -> "usb${r.nextInt(1, 5)}"
                    1 -> "${r.nextInt(1, 5)}-${r.nextInt(1, 9)}"
                    2 -> "${r.nextInt(1, 5)}-${r.nextInt(1, 5)}.${r.nextInt(1, 5)}"
                    else -> randomStr(r, 14)
                } + "_$idx".takeIf { r.nextInt(6) == 0 }.orEmpty()
                val ifaces = List(r.nextInt(4)) { ifIdx ->
                    UsbPeripheralParsers.RawSysfsUsbInterface(
                        interfaceNumber = ifIdx,
                        classCode = r.nextInt(-5, 300),
                        subClassCode = r.nextInt(-2, 260),
                        protocolCode = r.nextInt(-2, 260),
                        endpointCount = r.nextInt(-4, 40),
                        driverName = randomStr(r, 20),
                        interfaceLabel = randomStr(r, 24),
                    )
                }
                UsbPeripheralParsers.RawSysfsUsbDevice(
                    id = idChoice,
                    canonicalPath = "/sys/devices/pci0000:00/0000:00:0d.0/" + randomStr(r, 24),
                    vendorId = r.nextInt(-10, 0x10005),
                    productId = r.nextInt(-10, 0x10005),
                    deviceClassCode = r.nextInt(-2, 260),
                    usbVersionBcd = listOf("3.20", "2.10", "1.10", "", randomStr(r, 10))[r.nextInt(5)],
                    speedMbps = listOf(0f, 1.5f, 12f, 480f, 5_000f, 10_000f, 20_000f, 40_000f, Float.NaN, -50f)[r.nextInt(10)],
                    txLanes = r.nextInt(-1, 6),
                    rxLanes = r.nextInt(-1, 6),
                    maxPowerMa = r.nextInt(-100, 2000),
                    bmAttributesHex = r.nextInt(0, 256),
                    removable = listOf("removable", "fixed", "unknown", randomStr(r, 12))[r.nextInt(4)],
                    manufacturer = randomStr(r, 24),
                    product = randomStr(r, 28),
                    interfaces = ifaces,
                )
            }
            val dumpsysRaw = if (r.nextInt(3) == 0) {
                "port_manager={\nusb_ports=[\n{\nport={\nid=${randomStr(r, 12)}\nsupported_modes=${randomStr(r, 10)}\n}\nstatus={\nconnected=${r.nextBoolean()}\npower_role=${randomStr(r, 10)}\ndata_role=${randomStr(r, 10)}\ncompliance_warning_status=[${randomStr(r, 18)}]\n}\n}\n]\n}"
            } else {
                randomStr(r, 96)
            }
            val ports = UsbPeripheralParsers.parseUsbDumpsysPorts(dumpsysRaw, nowElapsedMs = iter * 100L)
            val snap = UsbPeripheralParsers.buildUsbTopologySnapshot(
                rawDevices = rawDevs,
                hasDumpPermission = r.nextBoolean(),
                typeCPorts = ports,
            )
            for (n in snap.nodes) {
                assertFalse(n.productName.contains('|') || n.productName.contains('\n'))
                assertFalse(n.vendorName.contains('|') || n.vendorName.contains('\n'))
                assertTrue(n.vidPidHex.length == 9 && n.vidPidHex[4] == ':')
            }
            val evs = tracker.recordSample(snap.nodes, nowElapsedMs = iter * 100L)
            assertTrue(evs.size <= 16)
            val vendorResolved = UsbVendorLookup.resolveVendorName(r.nextInt(-5, 0xFFFF), randomStr(r, 20))
            assertTrue(vendorResolved.isNotBlank())
            assertFalse(vendorResolved.contains('|') || vendorResolved.contains('\n'))
        }
    }

    @Test
    fun fuzzWifiRegulatoryAndBeaconParsers() {
        val r = Random(0x57494649L)
        repeat(15_000) {
            val rawSsid = if (r.nextInt(8) == 0) null else randomStr(r, 64)
            val (ssid, hidden) = WirelessTelemetryParsers.sanitizeSsidDisplay(rawSsid)
            assertTrue(ssid.isNotEmpty() && ssid.length <= 32)
            if (hidden) assertEqualsCount("<Hidden SSID>".length, ssid.length)
            assertFalse(ssid.any { it < ' ' || it == '\u007f' })

            val masked = WirelessTelemetryParsers.maskBssid(if (r.nextInt(6) == 0) null else randomStr(r, 24))
            assertTrue(masked.contains("••"))

            val band = listOf(0, 2, 5, 6, 60, -1)[r.nextInt(6)]
            val allowed = List(r.nextInt(12)) {
                val freq = listOf(-100, 0, 2412, 2437, 5180, 5260, 5600, 5955, 5975, 7115, r.nextInt(-50, 80_000))[r.nextInt(11)]
                freq to r.nextInt(-2, 8)
            }
            val usable = List(r.nextInt(8)) { allowed.getOrNull(it)?.first ?: r.nextInt(-10, 10_000) }
            val summary = WirelessTelemetryParsers.summarizeAllowedBandChannels(
                bandGhz = band,
                allowedFreqsWidths = allowed,
                usableFreqs = usable,
                isBandSupported = r.nextBoolean(),
                supports11be = r.nextBoolean(),
            )
            assertTrue(summary.allowedCount >= 0)
            assertTrue(summary.usableCount in 0..summary.allowedCount)
            assertTrue(summary.maxBandwidthMhz in 0..320)

            val ies = List(r.nextInt(6)) {
                val eid = listOf(0, 7, 11, 54, 70, 127, 255, r.nextInt(-5, 300))[r.nextInt(8)]
                val bytes = ByteArray(r.nextInt(16)).also { r.nextBytes(it) }
                eid to bytes
            }
            val parsedIes = WirelessTelemetryParsers.parse80211InformationElements(ies)
            parsedIes.qbssChannelUtilizationPct?.let { pct -> assertTrue(pct in 0..100) }
            parsedIes.qbssStationCount?.let { stas -> assertTrue(stas in 0..65_535) }
            parsedIes.countryCode80211d?.let { cc -> assertTrue(WirelessTelemetryParsers.isValidRegulatoryCountryCode(cc)) }

            val dumpsys = randomStr(r, 80) + "\nmDriverCountryCode: " + randomStr(r, 5)
            WirelessTelemetryParsers.parseWifiDumpsysCountryCode(dumpsys)?.let { (cc, _) ->
                assertTrue(WirelessTelemetryParsers.isValidRegulatoryCountryCode(cc))
            }

            val (sec, badges) = WirelessTelemetryParsers.formatScanCapabilitiesSecurity(
                capabilitiesRaw = randomStr(r, 48),
                securityTypes = IntArray(r.nextInt(4)) { r.nextInt(-2, 12) },
            )
            assertTrue(sec.isNotBlank())
            assertTrue(badges.size <= 5)
        }
    }

    private fun assertEqualsCount(expected: Int, actual: Int) {
        org.junit.Assert.assertEquals(expected, actual)
    }
}
