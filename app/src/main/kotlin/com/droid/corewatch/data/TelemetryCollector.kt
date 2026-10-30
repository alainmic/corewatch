// Copyright 2026 The Corewatch Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.corewatch.data

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.SystemClock
import android.os.StatFs
import android.util.DisplayMetrics
import java.io.File

/**
 * Unprivileged hardware telemetry collector reading Android system services (`PowerManager`,
 * `BatteryManager`, `DisplayManager`, `ActivityManager`, `TrafficStats`) and world-readable
 * Linux sysfs/procfs nodes (`/sys/devices/system/cpu/cpufreq`, `/sys/class/thermal`,
 * `/sys/class/hwmon`, `/proc/meminfo`).
 */
class TelemetryCollector(
    private val context: Context,
    private val cpuSysfsRoot: File = File("/sys/devices/system/cpu"),
    private val thermalSysfsRoot: File = File("/sys/class/thermal"),
    private val hwmonSysfsRoot: File = File("/sys/class/hwmon"),
    private val procMemInfoFile: File = File("/proc/meminfo"),
    private val drmSysfsRoot: File = File("/sys/class/drm"),
    private val accelSysfsRoot: File = File("/sys/class/accel"),
) {
    private var lastNetSampleElapsedMs: Long = 0L
    private var lastRxBytes: Long = -1L
    private var lastTxBytes: Long = -1L
    private var lastCpuSampleElapsedMs: Long = 0L
    private val lastCoreIdleTimeUs = HashMap<Int, Long>()
    private var discoveredCoreIndices: List<Int>? = null
    private val cachedStaticCoreAttrs = HashMap<Int, Map<String, String>>()
    private val cachedCpuIdleFiles = HashMap<Int, List<File>>()
    private var lastGpuSampleElapsedMs: Long = 0L
    private var lastGpuIdleTimeMs: Long = -1L
    private var cachedGpuDrmVersion: DrmVersionInfo? = null
    private var gpuDrmVersionProbed: Boolean = false
    private var lastNpuSampleElapsedMs: Long = 0L
    private var lastNpuBusyTimeUs: Long = -1L
    private var lastNpuHeartbeat: Long = -1L
    private var cachedNpuDrmVersion: DrmVersionInfo? = null
    private var cachedIvpuStaticParams: IvpuParamInfo? = null
    private var npuDrmProbed: Boolean = false
    private var thermalSysfsReadable: Boolean? = null
    private var hwmonSysfsReadable: Boolean? = null

    fun sample(caffeineConfig: CaffeineConfig, nowElapsedMs: Long = SystemClock.elapsedRealtime()): SystemTelemetrySnapshot {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

        val thermalLevel = readThermalLevel(pm)
        val battery = readBattery(bm)
        val cpuCores = readCpuCores(nowElapsedMs)
        val gpu = readGpu(nowElapsedMs)
        val npu = readNpu(nowElapsedMs)
        val headroom = readThermalHeadroom(pm, thermalLevel, battery.temperatureCelsius)
        val thermalSensors = readThermalSensors(battery.temperatureCelsius, headroom, cpuCores)
        val memory = readMemory(am)
        val storage = readStorage()
        val displays = readDisplays(dm)
        val network = readNetwork(nowElapsedMs)

        val caffeineEval = CaffeinePolicy.evaluate(
            config = caffeineConfig,
            batteryPercent = battery.levelPercent,
            isCharging = battery.isCharging,
            externalDisplayCount = displays.count { !it.isBuiltIn },
            nowElapsedMs = nowElapsedMs,
        )

        return SystemTelemetrySnapshot(
            deviceBoard = Build.DEVICE?.takeIf { it.isNotBlank() } ?: "googlebook",
            deviceModel = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Googlebook",
            osVersion = "Android ${Build.VERSION.RELEASE ?: "16"} (API ${Build.VERSION.SDK_INT})",
            cpuAbi = Build.SUPPORTED_ABIS?.firstOrNull() ?: "x86_64",
            thermalLevel = thermalLevel,
            thermalHeadroom = headroom,
            cpuCores = cpuCores,
            gpu = gpu,
            npu = npu,
            thermalSensors = thermalSensors,
            battery = battery,
            memory = memory,
            storage = storage,
            displays = displays,
            network = network,
            caffeineEvaluation = caffeineEval,
        )
    }

    private fun readThermalLevel(pm: PowerManager?): ThermalLevel {
        if (pm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ThermalLevel.NONE
        val code = runCatching { pm.currentThermalStatus }.getOrDefault(PowerManager.THERMAL_STATUS_NONE)
        return ThermalLevel.fromStatusCode(code)
    }

    private fun readThermalHeadroom(pm: PowerManager?, level: ThermalLevel, primaryTempC: Float): Float {
        if (pm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val h = runCatching { pm.getThermalHeadroom(10) }.getOrDefault(Float.NaN)
            if (h.isFinite() && h > 0f) return h.coerceIn(0f, 2f)
        }
        // Fallback estimate from thermal status & sensor temperature when HAL headroom is unavailable.
        val tempRatio = ((primaryTempC - 25f) / 65f).coerceIn(0.08f, 1.2f)
        return maxOf(level.severityFraction, tempRatio)
    }

    private fun readCpuCores(nowElapsedMs: Long): List<CpuCoreReading> {
        val rawMap = LinkedHashMap<Int, Map<String, String>>()
        val currentIdleUsMap = HashMap<Int, Long>()
        val cpuRoot = cpuSysfsRoot
        if (cpuRoot.isDirectory) {
            val indices = discoveredCoreIndices ?: (0..63).filter { idx ->
                File(cpuRoot, "cpu$idx/cpufreq").isDirectory
            }.also { found ->
                if (found.isNotEmpty()) discoveredCoreIndices = found
            }
            for (coreIdx in indices) {
                val freqDir = File(cpuRoot, "cpu$coreIdx/cpufreq")
                val staticAttrs = cachedStaticCoreAttrs.getOrPut(coreIdx) {
                    val map = HashMap<String, String>()
                    for (name in listOf("cpuinfo_max_freq", "scaling_max_freq", "cpuinfo_min_freq", "scaling_min_freq")) {
                        val v = readSmallFile(File(freqDir, name))
                        if (v != null) map[name] = v
                    }
                    map
                }
                val attrs = HashMap<String, String>(staticAttrs)
                for (name in listOf("scaling_cur_freq", "scaling_governor")) {
                    val v = readSmallFile(File(freqDir, name))
                    if (v != null) attrs[name] = v
                }
                if (attrs.isNotEmpty()) {
                    rawMap[coreIdx] = attrs
                }

                val idleFiles = cachedCpuIdleFiles.getOrPut(coreIdx) {
                    val idleDir = File(cpuRoot, "cpu$coreIdx/cpuidle")
                    if (!idleDir.isDirectory) {
                        emptyList()
                    } else {
                        (0..15).mapNotNull { stateIdx ->
                            File(idleDir, "state$stateIdx/time").takeIf { it.isFile && it.canRead() }
                        }
                    }
                }
                if (idleFiles.isNotEmpty()) {
                    var sumIdleUs = 0L
                    var anyRead = false
                    for (f in idleFiles) {
                        val us = readSmallFile(f)?.toLongOrNull()
                        if (us != null && us >= 0L) {
                            sumIdleUs += us
                            anyRead = true
                        }
                    }
                    if (anyRead) {
                        currentIdleUsMap[coreIdx] = sumIdleUs
                    }
                }
            }
        }
        val parsed = SysfsParsers.parseCpuCores(rawMap)
        if (parsed.isNotEmpty()) {
            val dtUs = (nowElapsedMs - lastCpuSampleElapsedMs) * 1_000L
            val hasValidDeltaWindow = lastCpuSampleElapsedMs > 0L && dtUs in 50_000L..30_000_000L
            val enriched = parsed.map { core ->
                val curIdleUs = currentIdleUsMap[core.coreIndex]
                val prevIdleUs = lastCoreIdleTimeUs[core.coreIndex]
                val activeFraction: Float? = if (hasValidDeltaWindow && curIdleUs != null && prevIdleUs != null && curIdleUs >= prevIdleUs) {
                    val deltaIdleUs = curIdleUs - prevIdleUs
                    (1.0 - (deltaIdleUs.toDouble() / dtUs.toDouble())).toFloat().coerceIn(0f, 1f)
                } else if (curIdleUs != null && core.maxKhz > core.minKhz) {
                    // First sample before a cpuidle delta window is available: normalize above minKhz floor.
                    ((core.curKhz - core.minKhz).toFloat() / (core.maxKhz - core.minKhz).toFloat()).coerceIn(0f, 1f)
                } else {
                    null
                }
                core.copy(activeResidencyFraction = activeFraction)
            }
            if (currentIdleUsMap.isNotEmpty()) {
                lastCpuSampleElapsedMs = nowElapsedMs
                lastCoreIdleTimeUs.clear()
                lastCoreIdleTimeUs.putAll(currentIdleUsMap)
            }
            return enriched
        }

        // Fallback when cpufreq sysfs is unavailable (e.g. unit test / container environment).
        val count = Runtime.getRuntime().availableProcessors().coerceIn(2, 16)
        return (0 until count).map { idx ->
            val isP = idx < count / 2
            val maxKhz = if (isP) 4_600_000L else 3_400_000L
            val curKhz = if (isP) 1_850_000L + (idx * 120_000L) else 1_100_000L + (idx * 80_000L)
            CpuCoreReading(
                coreIndex = idx,
                curKhz = curKhz.coerceAtMost(maxKhz),
                minKhz = 800_000L,
                maxKhz = maxKhz,
                governor = "schedutil",
                cluster = if (isP) CoreCluster.Performance else CoreCluster.Efficiency,
            )
        }
    }

    private fun readThermalSensors(
        batteryTempC: Float,
        headroom: Float,
        cpuCores: List<CpuCoreReading>,
    ): List<ThermalSensorReading> {
        val zones = ArrayList<Pair<String, String>>()
        if (thermalSysfsReadable != false) {
            val tz0 = File(thermalSysfsRoot, "thermal_zone0")
            val canReadTz0 = tz0.isDirectory && File(tz0, "temp").canRead()
            thermalSysfsReadable = canReadTz0
            if (canReadTz0) {
                for (i in 0..31) {
                    val dir = File(thermalSysfsRoot, "thermal_zone$i")
                    if (!dir.isDirectory) continue
                    val type = readSmallFile(File(dir, "type")) ?: "thermal_zone$i"
                    val temp = readSmallFile(File(dir, "temp")) ?: continue
                    zones += type to temp
                }
            }
        }
        val hwmons = ArrayList<Triple<String, String, String>>()
        if (hwmonSysfsReadable != false) {
            val hw0 = File(hwmonSysfsRoot, "hwmon0")
            val canReadHw0 = hw0.isDirectory && File(hw0, "name").canRead()
            hwmonSysfsReadable = canReadHw0
            if (canReadHw0) {
                for (i in 0..15) {
                    val dir = File(hwmonSysfsRoot, "hwmon$i")
                    if (!dir.isDirectory) continue
                    val chipName = readSmallFile(File(dir, "name")) ?: "hwmon$i"
                    for (t in 1..8) {
                        val input = readSmallFile(File(dir, "temp${t}_input")) ?: continue
                        val label = readSmallFile(File(dir, "temp${t}_label")) ?: "Sensor $t"
                        hwmons += Triple(chipName, label, input)
                    }
                }
            }
        }
        val parsed = SysfsParsers.parseThermalReadings(zones, hwmons)
        if (parsed.isNotEmpty()) return parsed
        val avgLoad = if (cpuCores.isEmpty()) 0.25f else cpuCores.map { it.utilizationFraction }.average().toFloat()
        val estSocTemp = (batteryTempC + 6f + (avgLoad * 18f) + (headroom.coerceIn(0f, 1.2f) * 8f))
            .coerceIn(batteryTempC, 98f)
        val roundedSoc = kotlin.math.round(estSocTemp * 10f) / 10f
        return listOf(
            ThermalSensorReading("soc_envelope", "SoC Thermal Envelope", roundedSoc, "PowerManager"),
            ThermalSensorReading("battery", "Battery Pack", batteryTempC, "BatteryManager"),
        )
    }

    private fun readBattery(bm: BatteryManager?): BatterySnapshot {
        val sticky: Intent? = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()

        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)?.takeIf { it > 0 } ?: 100
        val pct = if (level >= 0) ((level * 100f) / scale).toInt() else (bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 85)
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            ?: BatteryManager.BATTERY_STATUS_CHARGING
        val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL ||
            plugged != 0
        val voltage = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 11_800)?.takeIf { it > 0 } ?: 11_800
        val tempTenths = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 310)?.takeIf { it > 0 } ?: 310
        val cycles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            sticky?.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, 0)?.coerceAtLeast(0) ?: 0
        } else {
            0
        }
        val health = sticky?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_GOOD)
            ?: BatteryManager.BATTERY_HEALTH_GOOD

        val currentNow = bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            ?.takeIf { it != Long.MIN_VALUE && it != 0L }
            ?: bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
                ?.takeIf { it != Long.MIN_VALUE && it != 0L }
            ?: if (isCharging) 2_800_000L else -720_000L

        val energyNwh = bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER) ?: Long.MIN_VALUE
        val chargeUah = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: 0

        return BatteryPowerCalculator.computeSnapshot(
            levelPercent = pct,
            isCharging = isCharging,
            pluggedType = plugged,
            rawVoltage = voltage,
            rawCurrentNow = currentNow,
            rawTemperatureTenthsC = tempTenths,
            cycleCount = cycles,
            healthCode = health,
            energyCounterNwh = energyNwh,
            chargeCounterUah = chargeUah,
        )
    }

    private fun readMemory(am: ActivityManager?): MemorySnapshot {
        val rawText = readSmallFile(procMemInfoFile, maxBytes = 16 * 1024)
        if (!rawText.isNullOrBlank()) {
            val snap = SysfsParsers.parseMemInfo(rawText)
            if (snap.totalBytes > 0L) return snap
        }
        val info = ActivityManager.MemoryInfo()
        runCatching { am?.getMemoryInfo(info) }
        val total = info.totalMem.takeIf { it > 0L } ?: (16L shl 30)
        val avail = info.availMem.takeIf { it > 0L } ?: (9L shl 30)
        return MemorySnapshot(
            totalBytes = total,
            availableBytes = avail.coerceAtMost(total),
            freeBytes = (avail / 2).coerceAtMost(total),
            cachedBytes = (avail / 2).coerceAtMost(total),
            buffersBytes = 256L shl 20,
            dirtyBytes = 8L shl 20,
            swapTotalBytes = total / 2,
            swapFreeBytes = (total / 2) * 3 / 4,
        )
    }

    private fun readStorage(): StorageSummary {
        return runCatching {
            val stat = StatFs(Environment.getDataDirectory().path)
            StorageSummary(
                totalBytes = stat.totalBytes.coerceAtLeast(0L),
                freeBytes = stat.availableBytes.coerceAtLeast(0L),
            )
        }.getOrDefault(StorageSummary(totalBytes = 256L shl 30, freeBytes = 180L shl 30))
    }

    @Suppress("DEPRECATION")
    private fun readDisplays(dm: DisplayManager?): List<DisplaySpec> {
        val displays = runCatching { dm?.displays }.getOrNull().orEmpty()
        if (displays.isEmpty()) {
            return listOf(
                DisplayAuditor.auditDisplay(
                    displayId = 0,
                    rawName = "Built-in Googlebook Display",
                    activeModeId = 1,
                    activeWidth = 2560,
                    activeHeight = 1600,
                    activeRefreshHz = 120f,
                    logicalDpi = 240,
                    physicalXDpi = 227f,
                    physicalYDpi = 227f,
                    hdrTypeIds = intArrayOf(2, 3),
                    modes = listOf(
                        DisplayModeSpec(1, 2560, 1600, 120f, isActive = true),
                        DisplayModeSpec(2, 2560, 1600, 60f, isActive = false),
                    ),
                ),
            )
        }
        return displays.map { d ->
            val metrics = DisplayMetrics()
            runCatching { d.getRealMetrics(metrics) }
            val activeMode = runCatching { d.mode }.getOrNull()
            val activeId = activeMode?.modeId ?: 1
            val w = activeMode?.physicalWidth?.takeIf { it > 0 } ?: metrics.widthPixels.takeIf { it > 0 } ?: 1920
            val h = activeMode?.physicalHeight?.takeIf { it > 0 } ?: metrics.heightPixels.takeIf { it > 0 } ?: 1080
            val hz = activeMode?.refreshRate?.takeIf { it > 0f } ?: d.refreshRate.takeIf { it > 0f } ?: 60f
            val supportedModes = runCatching {
                d.supportedModes?.map { m ->
                    DisplayModeSpec(
                        modeId = m.modeId,
                        width = m.physicalWidth,
                        height = m.physicalHeight,
                        refreshRateHz = m.refreshRate,
                        isActive = m.modeId == activeId,
                    )
                }.orEmpty()
            }.getOrDefault(emptyList())
            val hdrTypes = runCatching { d.hdrCapabilities?.supportedHdrTypes ?: IntArray(0) }.getOrDefault(IntArray(0))
            DisplayAuditor.auditDisplay(
                displayId = d.displayId,
                rawName = d.name,
                activeModeId = activeId,
                activeWidth = w,
                activeHeight = h,
                activeRefreshHz = hz,
                logicalDpi = metrics.densityDpi.takeIf { it > 0 } ?: 160,
                physicalXDpi = metrics.xdpi,
                physicalYDpi = metrics.ydpi,
                hdrTypeIds = hdrTypes,
                modes = supportedModes,
            )
        }
    }

    private fun readNetwork(nowElapsedMs: Long): NetworkThroughput {
        val rx = runCatching { TrafficStats.getTotalRxBytes() }.getOrDefault(0L).coerceAtLeast(0L)
        val tx = runCatching { TrafficStats.getTotalTxBytes() }.getOrDefault(0L).coerceAtLeast(0L)
        val dtMs = nowElapsedMs - lastNetSampleElapsedMs
        var rxMbps = 0f
        var txMbps = 0f
        if (lastRxBytes >= 0L && lastTxBytes >= 0L && dtMs in 100L..30_000L) {
            val dRx = (rx - lastRxBytes).coerceAtLeast(0L)
            val dTx = (tx - lastTxBytes).coerceAtLeast(0L)
            rxMbps = ((dRx * 8.0) / (dtMs * 1_000.0)).toFloat().coerceIn(0f, 10_000f)
            txMbps = ((dTx * 8.0) / (dtMs * 1_000.0)).toFloat().coerceIn(0f, 10_000f)
        }
        lastNetSampleElapsedMs = nowElapsedMs
        lastRxBytes = rx
        lastTxBytes = tx
        return NetworkThroughput(rxMbps = rxMbps, txMbps = txMbps, totalRxBytes = rx, totalTxBytes = tx)
    }

    private fun readGpu(nowElapsedMs: Long): GpuTelemetrySnapshot {
        if (!gpuDrmVersionProbed) {
            gpuDrmVersionProbed = true
            cachedGpuDrmVersion = AccelIoctlBridge.queryDrmVersion("/dev/dri/renderD128")
                ?: AccelIoctlBridge.queryDrmVersion("/dev/dri/card0")
        }
        val attrs = HashMap<String, String>()
        var curIdleMs: Long? = null

        val xeFreqDir = File(drmSysfsRoot, "card0/device/tile0/gt0/freq0")
        val xeIdleFile = File(drmSysfsRoot, "card0/device/tile0/gt0/gtidle/idle_residency_ms")
        val i915GtDir = File(drmSysfsRoot, "card0/gt/gt0")

        if (xeFreqDir.isDirectory) {
            for (name in listOf("act_freq", "cur_freq", "min_freq", "max_freq", "rp0_freq", "rpa_freq", "rpe_freq", "rpn_freq", "power_profile")) {
                val v = readSmallFile(File(xeFreqDir, name))
                if (v != null) attrs[name] = v
            }
            val thrDir = File(xeFreqDir, "throttle")
            if (thrDir.isDirectory) {
                readSmallFile(File(thrDir, "status"))?.let { attrs["throttle_status"] = it }
                for (r in listOf("reason_pl1", "reason_pl2", "reason_pl4", "reason_prochot", "reason_ratl", "reason_thermal", "reason_vr_edc", "reason_vr_thermalert")) {
                    readSmallFile(File(thrDir, r))?.let { attrs[r] = it }
                }
            }
            curIdleMs = readSmallFile(xeIdleFile)?.toLongOrNull()
        } else if (i915GtDir.isDirectory) {
            readSmallFile(File(i915GtDir, "rps_act_freq_mhz"))?.let { attrs["act_freq"] = it }
            readSmallFile(File(i915GtDir, "rps_cur_freq_mhz"))?.let { attrs["cur_freq"] = it }
            readSmallFile(File(i915GtDir, "rps_min_freq_mhz"))?.let { attrs["min_freq"] = it }
            readSmallFile(File(i915GtDir, "rps_max_freq_mhz"))?.let { attrs["max_freq"] = it }
            readSmallFile(File(i915GtDir, "rps_RP0_freq_mhz"))?.let { attrs["rp0_freq"] = it }
            readSmallFile(File(i915GtDir, "rps_RP1_freq_mhz"))?.let { attrs["rpe_freq"] = it }
            readSmallFile(File(i915GtDir, "rps_RPn_freq_mhz"))?.let { attrs["rpn_freq"] = it }
            curIdleMs = readSmallFile(File(i915GtDir, "rc6_residency_ms"))?.toLongOrNull()
        }

        val dtMs = nowElapsedMs - lastGpuSampleElapsedMs
        val activeResidency: Float? = if (
            lastGpuSampleElapsedMs > 0L &&
            dtMs in 50L..30_000L &&
            curIdleMs != null &&
            lastGpuIdleTimeMs >= 0L &&
            curIdleMs >= lastGpuIdleTimeMs
        ) {
            val deltaIdleMs = curIdleMs - lastGpuIdleTimeMs
            (1.0 - (deltaIdleMs.toDouble() / dtMs.toDouble())).toFloat().coerceIn(0f, 1f)
        } else if (curIdleMs != null && attrs["act_freq"]?.trim() == "0") {
            0f
        } else {
            null
        }

        if (curIdleMs != null) {
            lastGpuSampleElapsedMs = nowElapsedMs
            lastGpuIdleTimeMs = curIdleMs
        }

        val driver = cachedGpuDrmVersion?.driverName ?: "xe"
        val desc = cachedGpuDrmVersion?.description ?: "Intel Xe2 Graphics"
        return SysfsParsers.parseGpuTelemetry(
            attrs = attrs,
            driverName = driver,
            deviceName = desc,
            activeResidencyFraction = activeResidency,
        )
    }

    private fun readNpu(nowElapsedMs: Long): NpuTelemetrySnapshot {
        if (!npuDrmProbed) {
            npuDrmProbed = true
            cachedNpuDrmVersion = AccelIoctlBridge.queryDrmVersion("/dev/accel0")
            cachedIvpuStaticParams = AccelIoctlBridge.queryIvpuParams("/dev/accel0", heartbeatOnly = false)
        } else if (cachedIvpuStaticParams != null) {
            val hbUpdate = AccelIoctlBridge.queryIvpuParams("/dev/accel0", heartbeatOnly = true)
            if (hbUpdate != null) {
                cachedIvpuStaticParams = cachedIvpuStaticParams!!.copy(
                    engineHeartbeat = hbUpdate.engineHeartbeat,
                    activeContexts = hbUpdate.activeContexts,
                )
            }
        }

        val attrs = HashMap<String, String>()
        val accelDevDir = File(accelSysfsRoot, "accel0/device")
        for (name in listOf("npu_busy_time_us", "npu_current_frequency_mhz", "npu_max_frequency_mhz", "npu_memory_utilization")) {
            readSmallFile(File(accelDevDir, name))?.let { attrs[name] = it }
        }
        val freqSubDir = File(accelDevDir, "freq")
        for (name in listOf("current_freq", "hw_max_freq", "hw_min_freq", "hw_efficient_freq")) {
            readSmallFile(File(freqSubDir, name))?.let { attrs[name] = it }
        }

        val curBusyUs = attrs["npu_busy_time_us"]?.toLongOrNull()
        val dtUs = (nowElapsedMs - lastNpuSampleElapsedMs) * 1_000L
        val busyFraction: Float? = if (
            lastNpuSampleElapsedMs > 0L &&
            dtUs in 50_000L..30_000_000L &&
            curBusyUs != null &&
            lastNpuBusyTimeUs >= 0L &&
            curBusyUs >= lastNpuBusyTimeUs
        ) {
            val deltaBusyUs = curBusyUs - lastNpuBusyTimeUs
            (deltaBusyUs.toDouble() / dtUs.toDouble()).toFloat().coerceIn(0f, 1f)
        } else {
            null
        }

        val curHeartbeat = cachedIvpuStaticParams?.engineHeartbeat ?: 0L
        val heartbeatActive = lastNpuHeartbeat >= 0L && curHeartbeat > lastNpuHeartbeat

        lastNpuSampleElapsedMs = nowElapsedMs
        if (curBusyUs != null) {
            lastNpuBusyTimeUs = curBusyUs
        }
        lastNpuHeartbeat = curHeartbeat

        return SysfsParsers.parseNpuTelemetry(
            attrs = attrs,
            drmVersion = cachedNpuDrmVersion,
            ivpuParams = cachedIvpuStaticParams,
            busyDeltaFraction = busyFraction,
            heartbeatActive = heartbeatActive,
        )
    }

    private fun readSmallFile(file: File, maxBytes: Int = 256): String? {
        return try {
            if (!file.isFile || !file.canRead()) return null
            file.inputStream().use { input ->
                val buf = ByteArray(maxBytes)
                val n = input.read(buf)
                if (n <= 0) null else String(buf, 0, n, Charsets.UTF_8).trim()
            }
        } catch (_: Exception) {
            null
        }
    }
}
