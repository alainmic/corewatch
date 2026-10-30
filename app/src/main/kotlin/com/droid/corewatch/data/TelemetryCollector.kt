// Copyright 2026 The Corewatch Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.corewatch.data

import android.Manifest
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.SystemClock
import android.os.StatFs
import android.util.DisplayMetrics
import androidx.core.content.ContextCompat
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
    private val procCpuInfoFile: File = File("/proc/cpuinfo"),
    private val procPsiMemoryFile: File = File("/proc/pressure/memory"),
    val vrrAndFrameTracker: VrrAndFrameWindowTracker = VrrAndFrameWindowTracker(),
) {
    private var lastNetSampleElapsedMs: Long = 0L
    private var lastRxBytes: Long = -1L
    private var lastTxBytes: Long = -1L
    private var lastRxPackets: Long = -1L
    private var lastTxPackets: Long = -1L
    @Volatile
    private var a2dpProxy: BluetoothA2dp? = null
    private var a2dpProxyRequested: Boolean = false
    private var cachedLogcatCodecKey: String = ""
    private var cachedLogcatCodecSnapshot: BluetoothAudioCodecSnapshot? = null
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
    private var cachedCpuModelName: String? = null
    @Volatile
    var lastTrimLevelLabel: String = "Normal"

    fun resetWindowCounters() {
        vrrAndFrameTracker.reset()
    }

    fun sample(caffeineConfig: CaffeineConfig, nowElapsedMs: Long = SystemClock.elapsedRealtime()): SystemTelemetrySnapshot {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

        val abi = Build.SUPPORTED_ABIS?.firstOrNull() ?: "x86_64"
        val cpuModel = cachedCpuModelName ?: run {
            val rawCpuInfo = readSmallFile(procCpuInfoFile, maxBytes = 8 * 1024).orEmpty()
            SysfsParsers.parseCpuModelName(rawCpuInfo, abi).also { cachedCpuModelName = it }
        }
        val thermalLevel = readThermalLevel(pm)
        val battery = readBattery(bm)
        val cpuCores = readCpuCores(nowElapsedMs)
        val gpu = readGpu(nowElapsedMs)
        val npu = readNpu(nowElapsedMs)
        val headroom = readThermalHeadroom(pm, thermalLevel, battery.temperatureCelsius)
        val thermalSensors = readThermalSensors(battery.temperatureCelsius, headroom, cpuCores)
        val memory = readMemory(am)
        val storage = readStorage()
        val displays = readDisplays(dm, nowElapsedMs)
        val primaryHz = displays.firstOrNull()?.activeRefreshHz?.toInt()?.coerceIn(30, 480) ?: 120
        val frameJank = vrrAndFrameTracker.snapshotFrames(nowElapsedMs, defaultHz = primaryHz)
        val lmkd = readLmkd(am)
        val network = readNetwork(nowElapsedMs)
        val wifi = readWifi()
        val bluetooth = readBluetooth()

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
            cpuAbi = abi,
            cpuModelName = cpuModel,
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
            frameJank = frameJank,
            lmkd = lmkd,
            network = network,
            wifi = wifi,
            bluetooth = bluetooth,
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
        val info = ActivityManager.MemoryInfo()
        runCatching { am?.getMemoryInfo(info) }
        val threshold = info.threshold.takeIf { it > 0L } ?: (216L shl 20)
        val lowMem = info.lowMemory

        val rawText = readSmallFile(procMemInfoFile, maxBytes = 16 * 1024)
        if (!rawText.isNullOrBlank()) {
            val snap = SysfsParsers.parseMemInfo(rawText)
            if (snap.totalBytes > 0L) {
                return snap.copy(
                    amsThresholdBytes = threshold,
                    isLowMemory = lowMem,
                )
            }
        }
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
            amsThresholdBytes = threshold,
            isLowMemory = lowMem,
        )
    }

    private fun readLmkd(am: ActivityManager?): LmkdTelemetrySnapshot {
        val kills = ArrayList<LmkdKillEvent>()
        val cutoffWallMs = System.currentTimeMillis() - 300_000L
        if (am != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val exits = am.getHistoricalProcessExitReasons(null, 0, 32)
                for (ex in exits) {
                    if (ex.timestamp < cutoffWallMs) continue
                    val isLowMemKill = ex.reason == ApplicationExitInfo.REASON_LOW_MEMORY ||
                        ex.reason == ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE
                    if (isLowMemKill) {
                        val reasonStr = if (ex.reason == ApplicationExitInfo.REASON_LOW_MEMORY) "LMKD Low Memory" else "Excessive Resource"
                        kills += LmkdKillEvent(
                            timestampMs = ex.timestamp,
                            processName = SysfsParsers.sanitizeSensorName(ex.processName, "process"),
                            pid = ex.pid,
                            pssKb = ex.pss.coerceAtLeast(0L),
                            reason = reasonStr,
                        )
                    }
                }
            }
        }
        val psiText = readSmallFile(procPsiMemoryFile, maxBytes = 1024).orEmpty()
        val (someAvg10, fullAvg10) = SysfsParsers.parsePsiMemoryAvg10(psiText)

        return LmkdTelemetrySnapshot(
            killCount5m = kills.size,
            recentKills5m = kills.take(6),
            trimLevelLabel = lastTrimLevelLabel,
            psiMemorySomeAvg10 = someAvg10,
            psiMemoryFullAvg10 = fullAvg10,
            vmstatOomKills = 0L,
            isSystemWide = false,
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
    private fun readDisplays(dm: DisplayManager?, nowElapsedMs: Long): List<DisplaySpec> {
        val drmConnectors = runCatching { AccelIoctlBridge.queryDrmConnectors("/dev/dri/card0") }.getOrDefault(emptyList())
        val displays = runCatching { dm?.displays }.getOrNull().orEmpty()
        if (displays.isEmpty()) {
            val eDpConn = drmConnectors.firstOrNull()
            val defaultSteps = listOf(20f, 24f, 30f, 40f, 60f, 120f)
            val (buckets, switches) = vrrAndFrameTracker.recordDisplayRefreshSample(nowElapsedMs, 120f, defaultSteps)
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
                    renderFrameRateHz = 120f,
                    vsyncRateHz = 120f,
                    supportedRefreshRatesHz = floatArrayOf(20f, 24f, 30f, 40f, 60f, 120f),
                    vrrCapable = eDpConn?.vrrCapable ?: true,
                    vrrKernelEnabled = eDpConn?.vrrKernelEnabled ?: false,
                    hasAndroidArr = false,
                    maxBpc = eDpConn?.maxBpc ?: 10,
                    drmConnectorName = eDpConn?.connectorName ?: "eDP-1",
                    vrrResidencyBuckets = buckets,
                    vrrSwitchCount5m = switches,
                ),
            )
        }
        return displays.mapIndexed { idx, d ->
            val metrics = DisplayMetrics()
            runCatching { d.getRealMetrics(metrics) }
            val activeMode = runCatching { d.mode }.getOrNull()
            val activeId = activeMode?.modeId ?: 1
            val w = activeMode?.physicalWidth?.takeIf { it > 0 } ?: metrics.widthPixels.takeIf { it > 0 } ?: 1920
            val h = activeMode?.physicalHeight?.takeIf { it > 0 } ?: metrics.heightPixels.takeIf { it > 0 } ?: 1080
            val modeHz = activeMode?.refreshRate?.takeIf { it > 0f } ?: d.refreshRate.takeIf { it > 0f } ?: 60f
            val renderHz = runCatching { d.refreshRate.takeIf { it > 0f } }.getOrNull() ?: modeHz
            val vsyncHz = runCatching {
                if (activeMode != null) {
                    val m = activeMode.javaClass.getMethod("getVsyncRate")
                    (m.invoke(activeMode) as? Float)?.takeIf { it > 0f }
                } else {
                    null
                }
            }.getOrNull() ?: modeHz
            val supportedRefreshSteps = runCatching { d.supportedRefreshRates }.getOrNull() ?: FloatArray(0)
            val hasArr = runCatching {
                val method = d.javaClass.getMethod("hasArrSupport")
                (method.invoke(d) as? Boolean) ?: false
            }.getOrDefault(false)
            val panelModel = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val info = d.deviceProductInfo
                    if (info != null) {
                        listOfNotNull(
                            info.manufacturerPnpId.takeIf { it.isNotBlank() },
                            info.productId.takeIf { it.isNotBlank() },
                            info.name?.takeIf { it.isNotBlank() },
                        ).joinToString(" ").trim()
                    } else {
                        ""
                    }
                } else {
                    ""
                }
            }.getOrDefault("")
            val drmConn = if (d.displayId == 0) {
                drmConnectors.firstOrNull { it.connectorName.startsWith("eDP", ignoreCase = true) }
                    ?: drmConnectors.getOrNull(idx)
            } else {
                drmConnectors.filterNot { it.connectorName.startsWith("eDP", ignoreCase = true) }
                    .getOrNull((idx - 1).coerceAtLeast(0))
                    ?: drmConnectors.getOrNull(idx)
            }
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
            val (buckets, switches) = if (idx == 0) {
                vrrAndFrameTracker.recordDisplayRefreshSample(
                    nowElapsedMs = nowElapsedMs,
                    effectiveHz = renderHz,
                    supportedStepsHz = supportedRefreshSteps.toList(),
                )
            } else {
                emptyList<VrrResidencyBucket>() to 0
            }
            DisplayAuditor.auditDisplay(
                displayId = d.displayId,
                rawName = d.name,
                activeModeId = activeId,
                activeWidth = w,
                activeHeight = h,
                activeRefreshHz = modeHz,
                logicalDpi = metrics.densityDpi.takeIf { it > 0 } ?: 160,
                physicalXDpi = metrics.xdpi,
                physicalYDpi = metrics.ydpi,
                hdrTypeIds = hdrTypes,
                modes = supportedModes,
                renderFrameRateHz = renderHz,
                vsyncRateHz = vsyncHz,
                supportedRefreshRatesHz = supportedRefreshSteps,
                vrrCapable = drmConn?.vrrCapable ?: false,
                vrrKernelEnabled = drmConn?.vrrKernelEnabled ?: false,
                hasAndroidArr = hasArr,
                maxBpc = drmConn?.maxBpc ?: 8,
                panelModel = panelModel,
                drmConnectorName = drmConn?.connectorName,
                vrrResidencyBuckets = buckets,
                vrrSwitchCount5m = switches,
            )
        }
    }

    private fun readNetwork(nowElapsedMs: Long): NetworkThroughput {
        val rx = runCatching { TrafficStats.getTotalRxBytes() }.getOrDefault(0L).coerceAtLeast(0L)
        val tx = runCatching { TrafficStats.getTotalTxBytes() }.getOrDefault(0L).coerceAtLeast(0L)
        val rxPkts = runCatching { TrafficStats.getTotalRxPackets() }.getOrDefault(0L).coerceAtLeast(0L)
        val txPkts = runCatching { TrafficStats.getTotalTxPackets() }.getOrDefault(0L).coerceAtLeast(0L)
        val dtMs = nowElapsedMs - lastNetSampleElapsedMs
        var rxMbps = 0f
        var txMbps = 0f
        var rxPps = 0f
        var txPps = 0f
        if (lastRxBytes >= 0L && lastTxBytes >= 0L && dtMs in 100L..30_000L) {
            val dRx = (rx - lastRxBytes).coerceAtLeast(0L)
            val dTx = (tx - lastTxBytes).coerceAtLeast(0L)
            rxMbps = ((dRx * 8.0) / (dtMs * 1_000.0)).toFloat().coerceIn(0f, 10_000f)
            txMbps = ((dTx * 8.0) / (dtMs * 1_000.0)).toFloat().coerceIn(0f, 10_000f)
            if (lastRxPackets >= 0L && lastTxPackets >= 0L) {
                val dRxPkt = (rxPkts - lastRxPackets).coerceAtLeast(0L)
                val dTxPkt = (txPkts - lastTxPackets).coerceAtLeast(0L)
                rxPps = ((dRxPkt * 1_000.0) / dtMs.toDouble()).toFloat().coerceIn(0f, 1_000_000f)
                txPps = ((dTxPkt * 1_000.0) / dtMs.toDouble()).toFloat().coerceIn(0f, 1_000_000f)
            }
        }
        lastNetSampleElapsedMs = nowElapsedMs
        lastRxBytes = rx
        lastTxBytes = tx
        lastRxPackets = rxPkts
        lastTxPackets = txPkts
        return NetworkThroughput(
            rxMbps = rxMbps,
            txMbps = txMbps,
            totalRxBytes = rx,
            totalTxBytes = tx,
            rxPacketsPerSec = rxPps,
            txPacketsPerSec = txPps,
            totalRxPackets = rxPkts,
            totalTxPackets = txPkts,
        )
    }

    @Suppress("DEPRECATION")
    private fun readWifi(): WifiTelemetrySnapshot {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val activeNet = runCatching { cm?.activeNetwork }.getOrNull()
        val caps = runCatching { if (activeNet != null) cm?.getNetworkCapabilities(activeNet) else null }.getOrNull()
        val linkProps = runCatching { if (activeNet != null) cm?.getLinkProperties(activeNet) else null }.getOrNull()

        val transports = ArrayList<String>(3)
        if (caps != null) {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transports += "Wi-Fi"
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) transports += "Ethernet"
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) transports += "VPN"
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transports += "Cellular"
        }
        val iface = linkProps?.interfaceName?.takeIf { it.isNotBlank() } ?: "wlan0"
        val mtu = runCatching { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) linkProps?.mtu ?: 1500 else 1500 }
            .getOrDefault(1500).let { if (it > 0) it else 1500 }
        val isValidated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val isMetered = caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        val downKbps = caps?.linkDownstreamBandwidthKbps?.coerceAtLeast(0) ?: 0
        val upKbps = caps?.linkUpstreamBandwidthKbps?.coerceAtLeast(0) ?: 0

        val wifiInfo: WifiInfo? = runCatching {
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) caps?.transportInfo as? WifiInfo else null)
                ?: wm?.connectionInfo
        }.getOrNull()

        val rssi = wifiInfo?.rssi ?: -127
        val freq = wifiInfo?.frequency?.coerceAtLeast(0) ?: 0
        val linkMbps = wifiInfo?.linkSpeed?.coerceAtLeast(0) ?: 0
        val txMbps = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) wifiInfo?.txLinkSpeedMbps?.coerceAtLeast(0) ?: linkMbps else linkMbps
        }.getOrDefault(linkMbps)
        val rxMbps = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) wifiInfo?.rxLinkSpeedMbps?.coerceAtLeast(0) ?: linkMbps else linkMbps
        }.getOrDefault(linkMbps)
        val stdCode = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) wifiInfo?.wifiStandard ?: 0 else 0
        }.getOrDefault(0)
        val secType = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) wifiInfo?.currentSecurityType ?: -1 else -1
        }.getOrDefault(-1)
        val mloLinks = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && wifiInfo != null) {
                wifiInfo.affiliatedMloLinks.size
            } else {
                0
            }
        }.getOrDefault(0)

        fun readWifiDoubleField(getterName: String): Double {
            if (wifiInfo == null) return 0.0
            return runCatching {
                val m = wifiInfo.javaClass.getMethod(getterName)
                ((m.invoke(wifiInfo) as? Number)?.toDouble() ?: 0.0).coerceAtLeast(0.0)
            }.getOrDefault(0.0)
        }

        val txLost = readWifiDoubleField("getLostTxPacketsPerSecond")
        val txRetry = readWifiDoubleField("getRetriedTxPacketsPerSecond")
        val txGood = readWifiDoubleField("getSuccessfulTxPacketsPerSecond")
        val rxGood = readWifiDoubleField("getSuccessfulRxPacketsPerSecond")

        val isWifiConnected = (rssi > -120 && freq > 0) || transports.contains("Wi-Fi")
        if (isWifiConnected && !transports.contains("Wi-Fi")) {
            transports.add(0, "Wi-Fi")
        }
        val effectiveIface = if (isWifiConnected && iface != "wlan0" && !iface.startsWith("wlan")) {
            "wlan0 + $iface"
        } else {
            iface
        }
        val isAnyConnected = isWifiConnected || transports.isNotEmpty()
        val transportLabel = when {
            transports.isNotEmpty() -> transports.joinToString(" + ")
            isWifiConnected -> "Wi-Fi"
            else -> "Disconnected"
        }

        return WifiTelemetrySnapshot(
            isConnected = isAnyConnected,
            transportLabel = transportLabel,
            interfaceName = effectiveIface,
            mtu = mtu,
            isValidated = isValidated || isWifiConnected,
            isMetered = isMetered,
            wifiStandardCode = stdCode,
            wifiStandardLabel = if (isWifiConnected) WirelessTelemetryParsers.formatWifiStandard(stdCode) else transportLabel,
            frequencyMhz = freq,
            bandAndChannelLabel = WirelessTelemetryParsers.formatBandAndChannel(freq),
            rssiDbm = rssi,
            signalQualityPercent = WirelessTelemetryParsers.computeRssiQualityPercent(rssi),
            signalQualityLabel = WirelessTelemetryParsers.formatRssiQualityLabel(rssi),
            txLinkSpeedMbps = txMbps,
            rxLinkSpeedMbps = rxMbps,
            linkSpeedMbps = linkMbps,
            downstreamBandwidthKbps = downKbps,
            upstreamBandwidthKbps = upKbps,
            securityLabel = if (isWifiConnected) WirelessTelemetryParsers.formatWifiSecurityType(secType) else "N/A",
            mloLinkCount = mloLinks,
            mloSummary = if (mloLinks > 1) "MLO Active ($mloLinks Links)" else "Single-Link",
            txLostPktsPerSec = txLost,
            txRetriesPerSec = txRetry,
            txSuccessPktsPerSec = txGood,
            rxSuccessPktsPerSec = rxGood,
        )
    }

    @Suppress("MissingPermission")
    private fun readBluetooth(): BluetoothTelemetrySnapshot {
        val audioMgr = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val btMgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter? = btMgr?.adapter

        val hasPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        // 1. Zero-permission AudioManager output routing inspection
        val outputs = runCatching { audioMgr?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.toList().orEmpty() }.getOrDefault(emptyList())
        val isVoiceMode = audioMgr?.mode == AudioManager.MODE_IN_COMMUNICATION || audioMgr?.mode == AudioManager.MODE_IN_CALL
        val bleOutput = outputs.firstOrNull { d ->
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && (
                d.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    d.type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && d.type == AudioDeviceInfo.TYPE_BLE_BROADCAST)
                )
        }
        val a2dpOutput = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        val scoOutput = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        val btOutput = if (isVoiceMode) {
            bleOutput ?: scoOutput ?: a2dpOutput
        } else {
            a2dpOutput ?: bleOutput ?: scoOutput
        }
        val activeOutput = btOutput ?: outputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_HDMI
        } ?: outputs.firstOrNull()

        val cleanProdName = WirelessTelemetryParsers.sanitizePeripheralDisplayName(
            activeOutput?.productName?.toString() ?: "Internal",
        )
        val routeName = when (activeOutput?.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP ($cleanProdName)"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth HFP/SCO ($cleanProdName)"
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE Audio Unicast ($cleanProdName)"
            AudioDeviceInfo.TYPE_BLE_BROADCAST -> "BLE Auracast Broadcast ($cleanProdName)"
            AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_LINE_DIGITAL -> "DisplayPort / HDMI ($cleanProdName)"
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Audio ($cleanProdName)"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "3.5mm Wired Audio"
            else -> "Built-in Speakers ($cleanProdName)"
        }

        val audioModeLabel = when (audioMgr?.mode) {
            AudioManager.MODE_IN_COMMUNICATION -> "VoIP / Communication (SCO/BLE)"
            AudioManager.MODE_IN_CALL -> "Voice Call"
            AudioManager.MODE_RINGTONE -> "Ringtone"
            else -> "Normal (Media)"
        }

        val defaultRateHz = activeOutput?.sampleRates?.maxOrNull() ?: 48_000
        var codecName = when (btOutput?.type) {
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST -> "LC3 (LE Audio)"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "mSBC / CVSD (HFP Voice)"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "A2DP Audio Stream"
            else -> "None (No Active Bluetooth Audio)"
        }
        var sampleRateStr = String.format(java.util.Locale.US, "%.1f kHz", defaultRateHz / 1_000.0)
        var bitsStr = "24-bit"
        var channelStr = "Stereo"
        var qualityStr = if (btOutput != null) "Active Stream" else "Idle"
        var selectableSummary = "Connect Bluetooth headphones to inspect"

        if (adapter == null) {
            return BluetoothTelemetrySnapshot(
                isAdapterEnabled = false,
                hasConnectPermission = hasPerm,
                connectionStateLabel = "No Adapter",
                audioCodec = BluetoothAudioCodecSnapshot(
                    activeRouteLabel = routeName,
                    activeCodecName = codecName,
                    sampleRateLabel = sampleRateStr,
                    bitsPerSampleLabel = bitsStr,
                    channelModeLabel = channelStr,
                    bitrateOrQualityLabel = qualityStr,
                    selectableCodecsSummary = selectableSummary,
                    communicationModeLabel = audioModeLabel,
                    isBluetoothAudioActive = btOutput != null,
                ),
            )
        }

        val isEnabled = runCatching { adapter.isEnabled }.getOrDefault(false)
        val le2m = runCatching { adapter.isLe2MPhySupported }.getOrDefault(false)
        val leCoded = runCatching { adapter.isLeCodedPhySupported }.getOrDefault(false)
        val leAudio = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) adapter.isLeAudioSupported == 10 else false
        }.getOrDefault(false)
        val leBroadcast = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                adapter.isLeAudioBroadcastSourceSupported == 10 || adapter.isLeAudioBroadcastAssistantSupported == 10
            } else {
                false
            }
        }.getOrDefault(false)

        if (!hasPerm) {
            return BluetoothTelemetrySnapshot(
                isAdapterEnabled = isEnabled,
                hasConnectPermission = false,
                connectionStateLabel = if (isEnabled) "On (Grant Permission)" else "Off",
                le2MPhySupported = le2m,
                leCodedPhySupported = leCoded,
                leAudioSupported = leAudio,
                leAudioBroadcastSupported = leBroadcast,
                audioCodec = BluetoothAudioCodecSnapshot(
                    activeRouteLabel = routeName,
                    activeCodecName = codecName,
                    sampleRateLabel = sampleRateStr,
                    bitsPerSampleLabel = bitsStr,
                    channelModeLabel = channelStr,
                    bitrateOrQualityLabel = qualityStr,
                    selectableCodecsSummary = "Grant BLUETOOTH_CONNECT to inspect A2DP codecs",
                    communicationModeLabel = audioModeLabel,
                    isBluetoothAudioActive = btOutput != null,
                ),
            )
        }

        // Bind A2DP profile proxy lazily to inspect negotiated BluetoothCodecConfig
        if (!a2dpProxyRequested && isEnabled) {
            a2dpProxyRequested = true
            runCatching {
                adapter.getProfileProxy(
                    context.applicationContext,
                    object : BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                            if (profile == BluetoothProfile.A2DP) {
                                a2dpProxy = proxy as? BluetoothA2dp
                            }
                        }

                        override fun onServiceDisconnected(profile: Int) {
                            if (profile == BluetoothProfile.A2DP) {
                                a2dpProxy = null
                            }
                        }
                    },
                    BluetoothProfile.A2DP,
                )
            }
        }

        val maxAudio = runCatching {
            val m = adapter.javaClass.getMethod("getMaxConnectedAudioDevices")
            ((m.invoke(adapter) as? Number)?.toInt() ?: 1).coerceAtLeast(1)
        }.getOrDefault(1)

        // Inspect A2DP codec status if an A2DP proxy is available
        val proxy = a2dpProxy
        if (proxy != null) {
            runCatching {
                val activeDevice = proxy.connectedDevices.firstOrNull()
                val getCodecStatusMethod = proxy.javaClass.getMethod("getCodecStatus", BluetoothDevice::class.java)
                val statusObj = getCodecStatusMethod.invoke(proxy, activeDevice)
                if (statusObj != null) {
                    val cfgObj = statusObj.javaClass.getMethod("getCodecConfig").invoke(statusObj)
                    if (cfgObj != null) {
                        val cType = (cfgObj.javaClass.getMethod("getCodecType").invoke(cfgObj) as? Number)?.toInt() ?: -1
                        val sRate = (cfgObj.javaClass.getMethod("getSampleRate").invoke(cfgObj) as? Number)?.toInt() ?: 0
                        val bSample = (cfgObj.javaClass.getMethod("getBitsPerSample").invoke(cfgObj) as? Number)?.toInt() ?: 0
                        val chMode = (cfgObj.javaClass.getMethod("getChannelMode").invoke(cfgObj) as? Number)?.toInt() ?: 0
                        val spec1 = (cfgObj.javaClass.getMethod("getCodecSpecific1").invoke(cfgObj) as? Number)?.toLong() ?: 0L
                        if (cType >= 0 && (activeDevice != null || btOutput != null)) {
                            codecName = WirelessTelemetryParsers.formatBluetoothCodecType(cType)
                            sampleRateStr = WirelessTelemetryParsers.formatBluetoothSampleRate(sRate)
                            bitsStr = WirelessTelemetryParsers.formatBluetoothBitsPerSample(bSample)
                            channelStr = WirelessTelemetryParsers.formatBluetoothChannelMode(chMode)
                            qualityStr = WirelessTelemetryParsers.formatBluetoothBitrateQuality(cType, spec1)
                        }
                    }
                    val selectableList = statusObj.javaClass.getMethod("getCodecsSelectableCapabilities").invoke(statusObj) as? List<*>
                    if (!selectableList.isNullOrEmpty()) {
                        val names = selectableList.mapNotNull { cap ->
                            val t = (cap?.javaClass?.getMethod("getCodecType")?.invoke(cap) as? Number)?.toInt()
                            if (t != null && t >= 0) WirelessTelemetryParsers.formatBluetoothCodecType(t) else null
                        }.distinct()
                        if (names.isNotEmpty()) {
                            selectableSummary = names.joinToString(", ")
                        }
                    }
                }
            }
        }

        // Enumerate bonded + GATT connected peripherals
        val bondedSet = runCatching { adapter.bondedDevices.orEmpty() }.getOrDefault(emptySet())
        val gattConnected = runCatching { btMgr.getConnectedDevices(BluetoothProfile.GATT).toSet() }.getOrDefault(emptySet())
        val allDevices = LinkedHashSet<BluetoothDevice>().apply {
            addAll(gattConnected)
            addAll(bondedSet)
        }

        val peripherals = allDevices.mapNotNull { dev ->
            val rawName = runCatching { dev.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val cleanName = WirelessTelemetryParsers.sanitizePeripheralDisplayName(rawName)
            val isConn = gattConnected.contains(dev) || runCatching {
                (dev.javaClass.getMethod("isConnected").invoke(dev) as? Boolean) ?: false
            }.getOrDefault(false)
            val batt = runCatching {
                ((dev.javaClass.getMethod("getBatteryLevel").invoke(dev) as? Number)?.toInt() ?: -1)
            }.getOrDefault(-1)
            val dType = runCatching { dev.type }.getOrDefault(0)
            val btClass = runCatching { dev.bluetoothClass }.getOrNull()
            val majorClass = btClass?.majorDeviceClass ?: 0
            val fullClass = btClass?.deviceClass ?: 0
            val cat = WirelessTelemetryParsers.formatPeripheralCategory(majorClass, fullClass, cleanName)
            val profSummary = when {
                isConn && dType == 2 -> "Connected (BLE GATT / HID)"
                isConn && cat.contains("Audio") -> "Connected (A2DP / Audio)"
                isConn -> "Connected"
                else -> "Paired (Standby)"
            }
            BluetoothPeripheralReading(
                name = cleanName,
                transportTypeLabel = WirelessTelemetryParsers.formatBluetoothDeviceType(dType),
                categoryLabel = cat,
                isConnected = isConn,
                profilesSummary = profSummary,
                batteryPercent = batt,
            )
        }.sortedWith(compareByDescending<BluetoothPeripheralReading> { it.isConnected }.thenBy { it.name })

        // Fallback: if A2DP is active and getCodecStatus is gated by BLUETOOTH_PRIVILEGED, inspect cached A2dpService logcat
        if (btOutput?.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP && codecName == "A2DP Audio Stream") {
            val cacheKey = "$routeName|$audioModeLabel"
            val logCodec = if (cacheKey == cachedLogcatCodecKey && cachedLogcatCodecSnapshot != null) {
                cachedLogcatCodecSnapshot
            } else {
                val parsed = runCatching {
                    val proc = ProcessBuilder("logcat", "-d", "-t", "120", "-s", "A2dpService:D", "bluetooth-a2dp-aidl:I", "A2dpStateMachine:D")
                        .redirectErrorStream(true)
                        .start()
                    val text = proc.inputStream.bufferedReader().use { it.readText() }
                    proc.waitFor()
                    WirelessTelemetryParsers.parseA2dpLogcatCodec(text, routeName, audioModeLabel)
                }.getOrNull()
                if (parsed != null) {
                    cachedLogcatCodecKey = cacheKey
                    cachedLogcatCodecSnapshot = parsed
                }
                parsed
            }
            if (logCodec != null) {
                codecName = logCodec.activeCodecName
                sampleRateStr = logCodec.sampleRateLabel
                bitsStr = logCodec.bitsPerSampleLabel
                channelStr = logCodec.channelModeLabel
                qualityStr = logCodec.bitrateOrQualityLabel
                selectableSummary = logCodec.selectableCodecsSummary
            }
        }

        if (selectableSummary == "Connect Bluetooth headphones to inspect") {
            val activeAudio = peripherals.firstOrNull { it.isConnected && it.categoryLabel.contains("Audio") }
            val pairedAudio = peripherals.firstOrNull { it.categoryLabel.contains("Audio") }
            if (activeAudio != null) {
                selectableSummary = "SBC, AAC, Opus, LC3 (${activeAudio.name})"
            } else if (pairedAudio != null) {
                selectableSummary = "SBC, AAC, Opus, LC3 (Standby: ${pairedAudio.name})"
            }
        }

        val connectedCount = peripherals.count { it.isConnected }
        val stateLabel = when {
            !isEnabled -> "Off"
            connectedCount > 0 -> "Connected ($connectedCount active)"
            else -> "On (Standby)"
        }

        return BluetoothTelemetrySnapshot(
            isAdapterEnabled = isEnabled,
            hasConnectPermission = true,
            connectionStateLabel = stateLabel,
            connectedCount = connectedCount,
            bondedCount = bondedSet.size,
            le2MPhySupported = le2m,
            leCodedPhySupported = leCoded,
            leAudioSupported = leAudio,
            leAudioBroadcastSupported = leBroadcast,
            maxConnectedAudioDevices = maxAudio,
            audioCodec = BluetoothAudioCodecSnapshot(
                activeRouteLabel = routeName,
                activeCodecName = codecName,
                sampleRateLabel = sampleRateStr,
                bitsPerSampleLabel = bitsStr,
                channelModeLabel = channelStr,
                bitrateOrQualityLabel = qualityStr,
                selectableCodecsSummary = selectableSummary,
                communicationModeLabel = audioModeLabel,
                isBluetoothAudioActive = btOutput != null,
            ),
            peripherals = peripherals,
        )
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
        val xeMediaFreqDir = File(drmSysfsRoot, "card0/device/tile0/gt1/freq0")
        val xeIdleFile = File(drmSysfsRoot, "card0/device/tile0/gt0/gtidle/idle_residency_ms")
        val i915GtDir = File(drmSysfsRoot, "card0/gt/gt0")
        val kgslDir = File("/sys/class/kgsl/kgsl-3d0")
        var fallbackBusyFraction: Float? = null

        if (xeFreqDir.isDirectory) {
            for (name in listOf("act_freq", "cur_freq", "min_freq", "max_freq", "rp0_freq", "rpa_freq", "rpe_freq", "rpn_freq", "power_profile")) {
                val v = readSmallFile(File(xeFreqDir, name))
                if (v != null) attrs[name] = v
            }
            if (xeMediaFreqDir.isDirectory) {
                val mediaFreq = readSmallFile(File(xeMediaFreqDir, "act_freq"))?.takeIf { it != "0" }
                    ?: readSmallFile(File(xeMediaFreqDir, "cur_freq"))
                if (mediaFreq != null) attrs["media_freq"] = mediaFreq
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
        } else if (kgslDir.isDirectory) {
            readSmallFile(File(kgslDir, "gpuclk"))?.let {
                attrs["act_freq"] = it
                attrs["cur_freq"] = it
            }
            readSmallFile(File(kgslDir, "max_gpuclk"))?.let { attrs["max_freq"] = it }
            readSmallFile(File(kgslDir, "min_clock_mhz"))?.let { attrs["min_freq"] = it }
            fallbackBusyFraction = SysfsParsers.parseQualcommKgslGpuBusy(
                readSmallFile(File(kgslDir, "gpu_busy_percentage"))
                    ?: readSmallFile(File(kgslDir, "gpubusy")),
            )
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
        } else if (fallbackBusyFraction != null) {
            fallbackBusyFraction
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
