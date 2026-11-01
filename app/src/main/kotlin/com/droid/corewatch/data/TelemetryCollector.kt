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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.hardware.usb.UsbManager
import android.location.LocationManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TrafficStats
import android.net.wifi.ScanResult
import android.net.wifi.WifiAvailableChannel
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.SystemClock
import android.os.StatFs
import android.os.storage.StorageManager
import android.telephony.TelephonyManager
import android.util.DisplayMetrics
import android.view.InputDevice
import androidx.core.content.ContextCompat
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

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
    private val usbSysfsRoot: File = File("/sys/bus/usb/devices"),
    val vrrAndFrameTracker: VrrAndFrameWindowTracker = VrrAndFrameWindowTracker(),
) {
    private val totalNetRateTracker = InterfaceRateTracker()
    private val wifiRateTracker = InterfaceRateTracker()
    private val ethernetRateTracker = InterfaceRateTracker()
    @Volatile
    private var latestWifiNetwork: Network? = null
    @Volatile
    private var latestWifiTransportInfo: WifiInfo? = null
    private val latestEthernetNetworks: MutableSet<Network> = ConcurrentHashMap.newKeySet()
    private var networkCallbacksRegistered: Boolean = false
    private var networkInterfaceAccessible: Boolean? = null
    private val isEmulatorBuild: Boolean by lazy {
        val hw = (Build.HARDWARE ?: "").lowercase(java.util.Locale.US)
        val fp = (Build.FINGERPRINT ?: "").lowercase(java.util.Locale.US)
        val prod = (Build.PRODUCT ?: "").lowercase(java.util.Locale.US)
        hw.contains("ranchu") || hw.contains("goldfish") || hw.contains("cuttlefish") ||
            fp.contains("generic") || prod.contains("sdk_gphone") || prod.contains("emulator")
    }
    private var lastLegacyWifiPollElapsedMs: Long = 0L
    private var cachedLegacyWifiInfo: WifiInfo? = null
    private var wifiScanCallbackRegistered: Boolean = false
    @Volatile
    private var wifiScanResultsDirty: Boolean = true
    private var lastWifiCapSampleElapsedMs: Long = 0L
    private var cachedWifiCapabilities: WifiRadioCapabilities = WifiRadioCapabilities()
    private var lastWifiScanSampleElapsedMs: Long = 0L
    private var cachedWifiScanSnapshot: WifiScanTelemetrySnapshot = WifiScanTelemetrySnapshot()
    @Volatile
    private var lastWifiDumpsysSampleElapsedMs: Long = 0L
    @Volatile
    private var wifiDumpsysRefreshInFlight: Boolean = false
    @Volatile
    private var cachedDumpsysWifiCountry: Pair<String, String>? = null
    @Volatile
    private var latestBeaconCountry80211d: String? = null
    @Volatile
    private var a2dpProxy: BluetoothA2dp? = null
    private var a2dpProxyRequested: Boolean = false
    @Volatile
    private var lastBtStackSampleElapsedMs: Long = 0L
    @Volatile
    private var btStackRefreshInFlight: Boolean = false
    @Volatile
    private var cachedBtDumpsysText: String = ""
    @Volatile
    private var cachedBtLogcatText: String = ""
    @Volatile
    private var cachedAudioDropStats: BluetoothAudioDropStats = BluetoothAudioDropStats()
    private val btStackExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "corewatch-bt-stack").apply { isDaemon = true }
    }
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
    private val usbHotplugTracker = UsbHotplugTracker()
    private var usbListenersRegistered: Boolean = false
    @Volatile
    private var usbTopologyDirty: Boolean = true
    private var lastUsbSampleElapsedMs: Long = 0L
    private var cachedUsbSnapshot: UsbPeripheralTelemetrySnapshot = UsbPeripheralTelemetrySnapshot()
    @Volatile
    private var lastUsbDumpsysSampleElapsedMs: Long = 0L
    @Volatile
    private var usbDumpsysRefreshInFlight: Boolean = false
    @Volatile
    private var cachedUsbDumpsysText: String = ""
    @Volatile
    var lastTrimLevelLabel: String = "Normal"

    fun resetWindowCounters() {
        vrrAndFrameTracker.reset()
        usbHotplugTracker.reset()
        invalidateWifiCaches()
        invalidateUsbCaches()
    }

    fun invalidateWifiCaches() {
        lastWifiCapSampleElapsedMs = 0L
        lastWifiScanSampleElapsedMs = 0L
        wifiScanResultsDirty = true
    }

    fun invalidateUsbCaches() {
        lastUsbSampleElapsedMs = 0L
        lastUsbDumpsysSampleElapsedMs = 0L
        usbTopologyDirty = true
    }

    @Suppress("DEPRECATION")
    fun triggerActiveWifiScan(): Pair<Boolean, String> {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return false to "Wi-Fi service unavailable on this device"
        val wifiOn = runCatching { wm.isWifiEnabled }.getOrDefault(false)
        if (!wifiOn) {
            return false to "Turn on Wi-Fi to scan surrounding access points"
        }
        val hasLocPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasLocPerm) {
            return false to "Grant Location permission to read surrounding Wi-Fi scan results"
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val locEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { lm?.isLocationEnabled ?: true }.getOrDefault(true)
        } else {
            true
        }
        if (!locEnabled) {
            return false to "Enable Location Services in Android Settings to unlock Wi-Fi scan results"
        }
        wifiScanResultsDirty = true
        lastWifiScanSampleElapsedMs = 0L
        val started = runCatching { wm.startScan() }.getOrDefault(false)
        return if (started) {
            true to "Active Wi-Fi scan triggered across supported bands"
        } else {
            false to "Refreshed cached AP beacons (Android limits foreground apps to 4 active scans / 2 min)"
        }
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
        val netBundle = readConnectivityBundle(nowElapsedMs)
        val bluetooth = readBluetooth(nowElapsedMs)
        val usbPeripherals = readUsbPeripherals(nowElapsedMs)

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
            network = netBundle.totalThroughput,
            wifi = netBundle.wifi,
            ethernet = netBundle.ethernet,
            activeDefaultRouteSummary = netBundle.activeDefaultRouteSummary,
            bluetooth = bluetooth,
            usbPeripherals = usbPeripherals,
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

    private data class ConnectivityBundle(
        val totalThroughput: NetworkThroughput,
        val wifi: WifiTelemetrySnapshot,
        val ethernet: EthernetTelemetrySnapshot,
        val activeDefaultRouteSummary: String,
    )

    private data class LinkMetadata(
        val interfaceName: String,
        val mtu: Int,
        val ipStackLabel: String,
        val dnsSummaryLabel: String,
        val wakeOnLanSupported: Boolean,
    )

    private data class EvaluatedNetwork(
        val network: Network,
        val caps: NetworkCapabilities,
        val linkProps: LinkProperties?,
    )

    private fun ensureNetworkCallbacksRegistered(cm: ConnectivityManager?) {
        if (networkCallbacksRegistered || cm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        networkCallbacksRegistered = true
        runCatching {
            val wifiReq = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            cm.registerNetworkCallback(
                wifiReq,
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        latestWifiNetwork = network
                    }

                    override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                        latestWifiNetwork = network
                        latestWifiTransportInfo = networkCapabilities.transportInfo as? WifiInfo
                    }

                    override fun onLost(network: Network) {
                        if (latestWifiNetwork == network) {
                            latestWifiNetwork = null
                            latestWifiTransportInfo = null
                        }
                    }
                },
            )
        }
        runCatching {
            val ethReq = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build()
            cm.registerNetworkCallback(
                ethReq,
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        latestEthernetNetworks.add(network)
                    }

                    override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                        latestEthernetNetworks.add(network)
                    }

                    override fun onLost(network: Network) {
                        latestEthernetNetworks.remove(network)
                    }
                },
            )
        }
    }

    private fun sampleInterfaceThroughput(
        tracker: InterfaceRateTracker,
        iface: String,
        isConnected: Boolean,
        nowElapsedMs: Long,
    ): NetworkThroughput {
        if (!isConnected || iface.isBlank() || iface == "None" || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            tracker.reset()
            return NetworkThroughput()
        }
        val rx = runCatching { TrafficStats.getRxBytes(iface) }.getOrDefault(-1L)
        val tx = runCatching { TrafficStats.getTxBytes(iface) }.getOrDefault(-1L)
        val rxPkts = runCatching { TrafficStats.getRxPackets(iface) }.getOrDefault(-1L)
        val txPkts = runCatching { TrafficStats.getTxPackets(iface) }.getOrDefault(-1L)
        return tracker.sample(
            ifaceKey = iface,
            rawRxBytes = rx,
            rawTxBytes = tx,
            rawRxPackets = rxPkts,
            rawTxPackets = txPkts,
            nowElapsedMs = nowElapsedMs,
        )
    }

    private fun inspectLinkMetadata(
        lp: LinkProperties?,
        fallbackIface: String,
        isConnected: Boolean,
    ): LinkMetadata {
        val iface = lp?.interfaceName?.takeIf { it.isNotBlank() } ?: fallbackIface
        val lpMtu = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { lp?.mtu ?: 0 }.getOrDefault(0)
        } else {
            0
        }
        val mtu = when {
            lpMtu > 0 -> lpMtu
            isConnected && iface.isNotBlank() && iface != "None" && networkInterfaceAccessible != false -> {
                val niMtu = try {
                    val m = NetworkInterface.getByName(iface)?.mtu ?: 0
                    networkInterfaceAccessible = true
                    m
                } catch (_: Throwable) {
                    networkInterfaceAccessible = false
                    0
                }
                if (niMtu > 0) niMtu else 1500
            }
            else -> 1500
        }
        val addrs = runCatching { lp?.linkAddresses.orEmpty() }.getOrDefault(emptyList())
        val hasIpv4 = addrs.any { it?.address is Inet4Address }
        val hasIpv6 = addrs.any { it?.address is Inet6Address }
        val isPrivateDns = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { lp?.isPrivateDnsActive == true }.getOrDefault(false)
        } else {
            false
        }
        val dnsCount = runCatching { lp?.dnsServers?.size ?: 0 }.getOrDefault(0)
        val wolSupported = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { lp?.isWakeOnLanSupported == true }.getOrDefault(false)
        } else {
            false
        }
        return LinkMetadata(
            interfaceName = iface,
            mtu = mtu,
            ipStackLabel = if (isConnected) WirelessTelemetryParsers.formatIpStackSummary(hasIpv4, hasIpv6) else "N/A",
            dnsSummaryLabel = WirelessTelemetryParsers.formatDnsSummary(isPrivateDns, dnsCount, isConnected),
            wakeOnLanSupported = wolSupported,
        )
    }

    private fun readWifiDoubleField(wifiInfo: WifiInfo?, getterName: String): Double {
        if (wifiInfo == null) return 0.0
        return runCatching {
            val method = wifiInfo.javaClass.getMethod(getterName)
            ((method.invoke(wifiInfo) as? Number)?.toDouble() ?: 0.0).coerceAtLeast(0.0)
        }.getOrDefault(0.0)
    }

    @Suppress("DEPRECATION")
    private fun readConnectivityBundle(nowElapsedMs: Long): ConnectivityBundle {
        val totalRx = runCatching { TrafficStats.getTotalRxBytes() }.getOrDefault(0L).coerceAtLeast(0L)
        val totalTx = runCatching { TrafficStats.getTotalTxBytes() }.getOrDefault(0L).coerceAtLeast(0L)
        val totalRxPkts = runCatching { TrafficStats.getTotalRxPackets() }.getOrDefault(0L).coerceAtLeast(0L)
        val totalTxPkts = runCatching { TrafficStats.getTotalTxPackets() }.getOrDefault(0L).coerceAtLeast(0L)
        val totalThroughput = totalNetRateTracker.sample(
            ifaceKey = "__total__",
            rawRxBytes = totalRx,
            rawTxBytes = totalTx,
            rawRxPackets = totalRxPkts,
            rawTxPackets = totalTxPkts,
            nowElapsedMs = nowElapsedMs,
        )

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ensureNetworkCallbacksRegistered(cm)
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val activeNet = runCatching { cm?.activeNetwork }.getOrNull()
        val activeCaps = runCatching { if (activeNet != null) cm?.getNetworkCapabilities(activeNet) else null }.getOrNull()
        val activeLp = runCatching { if (activeNet != null) cm?.getLinkProperties(activeNet) else null }.getOrNull()

        val candidateNetworks = LinkedHashSet<Network>().apply {
            if (activeNet != null) add(activeNet)
            latestWifiNetwork?.let { add(it) }
            addAll(latestEthernetNetworks)
            runCatching {
                cm?.allNetworks?.forEach { net ->
                    if (net != null) add(net)
                }
            }
        }

        val evaluated = ArrayList<EvaluatedNetwork>(candidateNetworks.size)
        for (net in candidateNetworks) {
            val c = runCatching { cm?.getNetworkCapabilities(net) }.getOrNull() ?: continue
            val lp = runCatching { cm?.getLinkProperties(net) }.getOrNull()
            evaluated += EvaluatedNetwork(net, c, lp)
        }

        val anyVpnActive = activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true ||
            evaluated.any { it.caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }

        // 1. Discover physical Wired Ethernet network (excluding AVF Linux VM tap / container / VPN bridges)
        val ethEval = evaluated
            .filter { item ->
                if (!item.caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return@filter false
                if (item.caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@filter false
                val iface = item.linkProps?.interfaceName?.trim().orEmpty()
                if (iface.isNotEmpty() && WirelessTelemetryParsers.isVirtualBridgeInterface(iface)) return@filter false
                val hasInternet = item.caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val hasAddresses = !item.linkProps?.linkAddresses.isNullOrEmpty()
                hasInternet || (iface.isNotEmpty() && hasAddresses)
            }
            .sortedWith(
                compareByDescending<EvaluatedNetwork> { it.network == activeNet }
                    .thenByDescending { it.caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
                    .thenByDescending { it.caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }
                    .thenByDescending {
                        maxOf(it.caps.linkDownstreamBandwidthKbps, it.caps.linkUpstreamBandwidthKbps)
                    },
            )
            .firstOrNull()

        // 2. Discover Wi-Fi network (even when secondary behind docked Ethernet)
        val wifiEval = evaluated
            .filter { item ->
                item.caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !item.caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
            .sortedWith(
                compareByDescending<EvaluatedNetwork> { it.network == activeNet }
                    .thenByDescending { it.caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }
                    .thenByDescending { it.caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) },
            )
            .firstOrNull()

        // 3. Build Wired Ethernet Telemetry Snapshot
        val isEthConnected = ethEval != null
        val isEthPrimary = isEthConnected && (
            ethEval.network == activeNet ||
                (activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true &&
                    !WirelessTelemetryParsers.isVirtualBridgeInterface(activeLp?.interfaceName.orEmpty()))
            )
        val ethCaps = ethEval?.caps
        val ethMeta = inspectLinkMetadata(
            lp = ethEval?.linkProps,
            fallbackIface = if (isEthConnected) "eth0" else "None",
            isConnected = isEthConnected,
        )
        val ethDownKbps = ethCaps?.linkDownstreamBandwidthKbps?.coerceAtLeast(0) ?: 0
        val ethUpKbps = ethCaps?.linkUpstreamBandwidthKbps?.coerceAtLeast(0) ?: 0
        val (ethSpeedMbps, ethTierLabel) = WirelessTelemetryParsers.formatEthernetSpeedTier(
            downKbps = ethDownKbps,
            upKbps = ethUpKbps,
            isConnected = isEthConnected,
        )
        val ethThroughput = sampleInterfaceThroughput(
            tracker = ethernetRateTracker,
            iface = ethMeta.interfaceName,
            isConnected = isEthConnected,
            nowElapsedMs = nowElapsedMs,
        )
        val ethernetSnapshot = EthernetTelemetrySnapshot(
            isConnected = isEthConnected,
            interfaceName = if (isEthConnected) ethMeta.interfaceName else "None",
            isPrimaryDefaultRoute = isEthPrimary,
            isValidated = ethCaps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            isMetered = ethCaps != null && !ethCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            mtu = ethMeta.mtu,
            negotiatedSpeedMbps = ethSpeedMbps,
            downstreamBandwidthKbps = ethDownKbps,
            upstreamBandwidthKbps = ethUpKbps,
            linkSpeedTierLabel = ethTierLabel,
            ipStackLabel = ethMeta.ipStackLabel,
            dnsSummaryLabel = ethMeta.dnsSummaryLabel,
            wakeOnLanSupported = ethMeta.wakeOnLanSupported,
            isEmulatorVirtualNic = isEmulatorBuild,
            throughput = ethThroughput,
        )

        // 4. Build Wi-Fi Radio & Link Telemetry Snapshot
        val wifiCaps = wifiEval?.caps
        val wifiInfo: WifiInfo? = runCatching {
            val fromWifiEval = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) wifiCaps?.transportInfo as? WifiInfo else null)
                ?.takeIf { it.rssi > -120 }
            val fromActive = if (fromWifiEval == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                (activeCaps?.transportInfo as? WifiInfo)?.takeIf { it.rssi > -120 }
            } else {
                null
            }
            val fromTrackedNet = if (fromWifiEval == null && fromActive == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val tracked = latestWifiNetwork
                ((if (tracked != null) cm?.getNetworkCapabilities(tracked)?.transportInfo as? WifiInfo else null)
                    ?: latestWifiTransportInfo)?.takeIf { it.rssi > -120 }
            } else {
                null
            }
            fromWifiEval ?: fromActive ?: fromTrackedNet ?: run {
                val elapsed = nowElapsedMs - lastLegacyWifiPollElapsedMs
                if (cachedLegacyWifiInfo == null || lastLegacyWifiPollElapsedMs == 0L || elapsed >= 2_500L || elapsed < 0L) {
                    lastLegacyWifiPollElapsedMs = nowElapsedMs
                    cachedLegacyWifiInfo = wm?.connectionInfo
                }
                cachedLegacyWifiInfo
            }
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

        val txLost = readWifiDoubleField(wifiInfo, "getLostTxPacketsPerSecond")
        val txRetry = readWifiDoubleField(wifiInfo, "getRetriedTxPacketsPerSecond")
        val txGood = readWifiDoubleField(wifiInfo, "getSuccessfulTxPacketsPerSecond")
        val rxGood = readWifiDoubleField(wifiInfo, "getSuccessfulRxPacketsPerSecond")

        val isWifiConnected = wifiEval != null || (rssi > -120 && freq > 0) ||
            (activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true)
        val wifiMeta = inspectLinkMetadata(
            lp = wifiEval?.linkProps ?: if (activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) activeLp else null,
            fallbackIface = "wlan0",
            isConnected = isWifiConnected,
        )
        val cleanWifiIface = if (wifiMeta.interfaceName.startsWith("wlan") || wifiMeta.interfaceName.startsWith("wl")) {
            wifiMeta.interfaceName
        } else {
            "wlan0"
        }
        val isWifiPrimary = isWifiConnected && !isEthPrimary && (
            (wifiEval != null && wifiEval.network == activeNet) ||
                activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            )
        val wifiRouteRole = when {
            !isWifiConnected -> "Disconnected"
            isWifiPrimary -> "Primary Default Route"
            isEthPrimary -> "Standby (Ethernet Primary)"
            else -> "Secondary Link"
        }
        val wifiValidated = (wifiCaps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) ||
            (isWifiPrimary && activeCaps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) ||
            (isWifiConnected && wifiCaps == null)
        val wifiMetered = wifiCaps != null && !wifiCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        val wifiDownKbps = wifiCaps?.linkDownstreamBandwidthKbps?.coerceAtLeast(0) ?: 0
        val wifiUpKbps = wifiCaps?.linkUpstreamBandwidthKbps?.coerceAtLeast(0) ?: 0
        val wifiThroughput = sampleInterfaceThroughput(
            tracker = wifiRateTracker,
            iface = cleanWifiIface,
            isConnected = isWifiConnected,
            nowElapsedMs = nowElapsedMs,
        )

        ensureWifiScanCallbackRegistered(wm)
        val surroundingScan = readSurroundingWifiScan(
            wm = wm,
            wifiInfo = wifiInfo,
            isWifiConnected = isWifiConnected,
            nowElapsedMs = nowElapsedMs,
        )
        val wifiCapabilities = readWifiRadioCapabilities(
            wm = wm,
            wifiInfo = wifiInfo,
            activeStdCode = stdCode,
            activeFreqMhz = freq,
            nowElapsedMs = nowElapsedMs,
        )

        val wifiSnapshot = WifiTelemetrySnapshot(
            isConnected = isWifiConnected,
            transportLabel = if (isWifiConnected) "Wi-Fi" else "Disconnected",
            interfaceName = if (isWifiConnected) cleanWifiIface else "wlan0",
            isPrimaryDefaultRoute = isWifiPrimary,
            routeRoleLabel = wifiRouteRole,
            mtu = wifiMeta.mtu,
            isValidated = wifiValidated,
            isMetered = wifiMetered,
            ipStackLabel = wifiMeta.ipStackLabel,
            dnsSummaryLabel = wifiMeta.dnsSummaryLabel,
            wifiStandardCode = stdCode,
            wifiStandardLabel = if (isWifiConnected) WirelessTelemetryParsers.formatWifiStandard(stdCode) else "Disconnected",
            frequencyMhz = freq,
            bandAndChannelLabel = if (isWifiConnected) WirelessTelemetryParsers.formatBandAndChannel(freq) else "N/A",
            rssiDbm = if (isWifiConnected) rssi else -127,
            signalQualityPercent = if (isWifiConnected) WirelessTelemetryParsers.computeRssiQualityPercent(rssi) else 0,
            signalQualityLabel = if (isWifiConnected) WirelessTelemetryParsers.formatRssiQualityLabel(rssi) else "No Signal",
            txLinkSpeedMbps = if (isWifiConnected) txMbps else 0,
            rxLinkSpeedMbps = if (isWifiConnected) rxMbps else 0,
            linkSpeedMbps = if (isWifiConnected) linkMbps else 0,
            downstreamBandwidthKbps = wifiDownKbps,
            upstreamBandwidthKbps = wifiUpKbps,
            securityLabel = if (isWifiConnected) WirelessTelemetryParsers.formatWifiSecurityType(secType) else "N/A",
            mloLinkCount = if (isWifiConnected) mloLinks else 0,
            mloSummary = if (isWifiConnected && mloLinks > 1) "MLO Active ($mloLinks Links)" else "Single-Link",
            txLostPktsPerSec = if (isWifiConnected) txLost else 0.0,
            txRetriesPerSec = if (isWifiConnected) txRetry else 0.0,
            txSuccessPktsPerSec = if (isWifiConnected) txGood else 0.0,
            rxSuccessPktsPerSec = if (isWifiConnected) rxGood else 0.0,
            throughput = wifiThroughput,
            capabilities = wifiCapabilities,
            surroundingScan = surroundingScan,
        )

        val vpnSuffix = if (anyVpnActive) " + VPN" else ""
        val activeDefaultRouteSummary = when {
            isEthPrimary -> "Ethernet (${ethernetSnapshot.interfaceName})$vpnSuffix"
            isWifiPrimary -> "Wi-Fi (${wifiSnapshot.interfaceName})$vpnSuffix"
            activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Cellular$vpnSuffix"
            isEthConnected -> "Ethernet (${ethernetSnapshot.interfaceName})$vpnSuffix"
            isWifiConnected -> "Wi-Fi (${wifiSnapshot.interfaceName})$vpnSuffix"
            anyVpnActive -> "VPN"
            else -> "Disconnected"
        }

        return ConnectivityBundle(
            totalThroughput = totalThroughput,
            wifi = wifiSnapshot,
            ethernet = ethernetSnapshot,
            activeDefaultRouteSummary = activeDefaultRouteSummary,
        )
    }

    private fun ensureWifiScanCallbackRegistered(wm: WifiManager?) {
        if (wifiScanCallbackRegistered || wm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        wifiScanCallbackRegistered = true
        runCatching {
            wm.registerScanResultsCallback(
                { runnable -> runnable.run() },
                object : WifiManager.ScanResultsCallback() {
                    override fun onScanResultsAvailable() {
                        wifiScanResultsDirty = true
                    }
                },
            )
        }
    }

    private fun scheduleWifiCountryDumpsysIfPermitted(nowElapsedMs: Long) {
        val hasDump = ContextCompat.checkSelfPermission(context, Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED
        if (!hasDump || wifiDumpsysRefreshInFlight) return
        val dt = nowElapsedMs - lastWifiDumpsysSampleElapsedMs
        if (lastWifiDumpsysSampleElapsedMs != 0L && dt in 0L until 15_000L) return
        wifiDumpsysRefreshInFlight = true
        lastWifiDumpsysSampleElapsedMs = nowElapsedMs
        runCatching {
            btStackExecutor.execute {
                try {
                    val proc = ProcessBuilder("/system/bin/dumpsys", "wifi")
                        .redirectErrorStream(true)
                        .start()
                    val sb = StringBuilder(4_096)
                    proc.inputStream.bufferedReader().use { reader ->
                        var linesRead = 0
                        while (linesRead < 4_000) {
                            val line = reader.readLine() ?: break
                            linesRead++
                            if (line.contains("CountryCode", ignoreCase = true)) {
                                sb.appendLine(line)
                                if (line.contains("mDriverCountryCode:")) break
                            }
                        }
                    }
                    runCatching { proc.destroy() }
                    WirelessTelemetryParsers.parseWifiDumpsysCountryCode(sb.toString())?.let {
                        cachedDumpsysWifiCountry = it
                    }
                } catch (_: Throwable) {
                    // Ignore process failures on restricted sandboxes
                } finally {
                    wifiDumpsysRefreshInFlight = false
                }
            }
        }.onFailure {
            wifiDumpsysRefreshInFlight = false
        }
    }

    private fun resolveRegulatoryRegion(
        wm: WifiManager?,
        hasLocationPerm: Boolean,
        nowElapsedMs: Long,
    ): Pair<String, String> {
        scheduleWifiCountryDumpsysIfPermitted(nowElapsedMs)
        cachedDumpsysWifiCountry?.let { return it }

        if (wm != null && hasLocationPerm) {
            val sysCountry = runCatching {
                val method = wm.javaClass.getMethod("getCountryCode")
                (method.invoke(wm) as? String)?.trim()?.uppercase(java.util.Locale.US)
            }.getOrNull()
            if (WirelessTelemetryParsers.isValidRegulatoryCountryCode(sysCountry)) {
                return sysCountry!! to "WifiManager RegDomain"
            }
        }

        latestBeaconCountry80211d?.takeIf { WirelessTelemetryParsers.isValidRegulatoryCountryCode(it) }?.let {
            return it to "802.11d AP Beacon"
        }

        val tm = runCatching { context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager }.getOrNull()
        val netIso = runCatching { tm?.networkCountryIso?.trim()?.uppercase(java.util.Locale.US) }.getOrNull()
        if (WirelessTelemetryParsers.isValidRegulatoryCountryCode(netIso)) {
            return netIso!! to "Telephony Network"
        }
        val simIso = runCatching { tm?.simCountryIso?.trim()?.uppercase(java.util.Locale.US) }.getOrNull()
        if (WirelessTelemetryParsers.isValidRegulatoryCountryCode(simIso)) {
            return simIso!! to "SIM Country"
        }

        val localeIso = java.util.Locale.getDefault().country.trim().uppercase(java.util.Locale.US)
        if (WirelessTelemetryParsers.isValidRegulatoryCountryCode(localeIso)) {
            return localeIso to "System Locale (Fallback)"
        }
        return "Unknown" to "Unavailable"
    }

    @Suppress("MissingPermission", "DEPRECATION")
    private fun readWifiRadioCapabilities(
        wm: WifiManager?,
        wifiInfo: WifiInfo?,
        activeStdCode: Int,
        activeFreqMhz: Int,
        nowElapsedMs: Long,
    ): WifiRadioCapabilities {
        if (wm == null) return WifiRadioCapabilities()

        val hasNearbyPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val hasLocationPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        val maxTxPhy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { wifiInfo?.maxSupportedTxLinkSpeedMbps?.coerceAtLeast(0) ?: 0 }.getOrDefault(0)
        } else {
            0
        }
        val maxRxPhy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { wifiInfo?.maxSupportedRxLinkSpeedMbps?.coerceAtLeast(0) ?: 0 }.getOrDefault(0)
        } else {
            0
        }

        val dt = nowElapsedMs - lastWifiCapSampleElapsedMs
        if (cachedWifiCapabilities.isCapabilitiesQueried &&
            lastWifiCapSampleElapsedMs != 0L &&
            dt in 0L until 5_000L &&
            hasNearbyPerm == cachedWifiCapabilities.hasNearbyWifiPermission
        ) {
            val (cc, ccSrc) = resolveRegulatoryRegion(wm, hasLocationPerm, nowElapsedMs)
            return cachedWifiCapabilities.copy(
                maxTxLinkSpeedMbps = maxTxPhy,
                maxRxLinkSpeedMbps = maxRxPhy,
                countryCode = cc,
                countryCodeSource = ccSrc,
            )
        }
        lastWifiCapSampleElapsedMs = nowElapsedMs

        val sup24 = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { wm.is24GHzBandSupported }.getOrDefault(true)
        } else {
            true
        } || (activeFreqMhz in 2401..2495)
        val sup5 = runCatching { wm.is5GHzBandSupported }.getOrDefault(false) || (activeFreqMhz in 5150..5895)
        val sup6 = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { wm.is6GHzBandSupported }.getOrDefault(false)
        } else {
            false
        } || (activeFreqMhz in 5925..7125)
        val sup60 = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { wm.is60GHzBandSupported }.getOrDefault(false)
        } else {
            false
        }

        val sup11abg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { wm.isWifiStandardSupported(ScanResult.WIFI_STANDARD_LEGACY) }.getOrDefault(true)
        } else {
            true
        } || activeStdCode >= 1
        val sup11n = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { wm.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11N) }.getOrDefault(true)
        } else {
            true
        } || activeStdCode >= 4
        val sup11ac = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { wm.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11AC) }.getOrDefault(sup5)
        } else {
            sup5
        } || activeStdCode in listOf(5, 6, 8)
        val sup11ax = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { wm.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11AX) }.getOrDefault(false)
        } else {
            false
        } || activeStdCode in listOf(6, 8) || sup6
        val sup11ad = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { wm.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11AD) }.getOrDefault(false)
        } else {
            false
        } || activeStdCode == 7
        val sup11be = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { wm.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11BE) }.getOrDefault(false)
        } else {
            false
        } || activeStdCode == 8

        val supSae = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { wm.isWpa3SaeSupported }.getOrDefault(false)
        } else {
            false
        }
        val supSaeH2e = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { wm.isWpa3SaeH2eSupported }.getOrDefault(false)
        } else {
            false
        }
        val supSaePk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { wm.isWpa3SaePublicKeySupported }.getOrDefault(false)
        } else {
            false
        }
        val supOwe = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { wm.isEnhancedOpenSupported }.getOrDefault(false)
        } else {
            false
        }
        val supSuiteB = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { wm.isWpa3SuiteBSupported }.getOrDefault(false)
        } else {
            false
        }
        val supTls13 = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching { wm.isTlsV13Supported }.getOrDefault(false)
        } else {
            false
        }
        val supTofu = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { wm.isTrustOnFirstUseSupported }.getOrDefault(false)
        } else {
            false
        }
        val supDpp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { wm.isEasyConnectSupported }.getOrDefault(false)
        } else {
            false
        }
        val supWpa2 = if (Build.VERSION.SDK_INT >= 35) {
            runCatching { wm.isWpaPersonalSupported }.getOrDefault(true)
        } else {
            true
        }

        val supDbs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching { wm.isDualBandSimultaneousSupported }.getOrDefault(false)
        } else {
            false
        }
        val supT2lm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching { wm.isTidToLinkMappingNegotiationSupported }.getOrDefault(false)
        } else {
            false
        }
        val supMbb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { wm.isMakeBeforeBreakWifiSwitchingSupported }.getOrDefault(false)
        } else {
            false
        }
        val supStaAp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { wm.isStaApConcurrencySupported }.getOrDefault(false)
        } else {
            false
        }
        val scanThrottle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { wm.isScanThrottleEnabled }.getOrDefault(true)
        } else {
            true
        }

        var channelQueryOk = false
        var allowed24: List<Pair<Int, Int>> = emptyList()
        var usable24: List<Int> = emptyList()
        var allowed5: List<Pair<Int, Int>> = emptyList()
        var usable5: List<Int> = emptyList()
        var allowed6: List<Pair<Int, Int>> = emptyList()
        var usable6: List<Int> = emptyList()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && (hasNearbyPerm || hasLocationPerm)) {
            val opSta = WifiAvailableChannel.OP_MODE_STA
            fun extractFreqWidths(list: List<WifiAvailableChannel>): List<Pair<Int, Int>> = list.map { ch ->
                val w = if (Build.VERSION.SDK_INT >= 36) {
                    runCatching { ch.channelWidth }.getOrDefault(0)
                } else {
                    0
                }
                ch.frequencyMhz to w
            }

            if (sup24) {
                runCatching {
                    allowed24 = extractFreqWidths(wm.getAllowedChannels(1, opSta))
                    usable24 = wm.getUsableChannels(1, opSta).map { it.frequencyMhz }
                    channelQueryOk = true
                }
            }
            if (sup5) {
                // WifiManager.getAllowedChannels requires WIFI_BAND_5_GHZ_WITH_DFS = 6 (passing 2 throws IllegalArgumentException)
                runCatching {
                    val rawAllowed = wm.getAllowedChannels(6, opSta)
                    val rawUsable = wm.getUsableChannels(6, opSta)
                    allowed5 = extractFreqWidths(rawAllowed)
                    usable5 = rawUsable.map { it.frequencyMhz }
                    channelQueryOk = true
                }.onFailure {
                    runCatching {
                        allowed5 = extractFreqWidths(wm.getAllowedChannels(2, opSta))
                        usable5 = wm.getUsableChannels(2, opSta).map { it.frequencyMhz }
                        channelQueryOk = true
                    }
                }
            }
            if (sup6) {
                runCatching {
                    allowed6 = extractFreqWidths(wm.getAllowedChannels(8, opSta))
                    usable6 = wm.getUsableChannels(8, opSta).map { it.frequencyMhz }
                    channelQueryOk = true
                }
            }
        }

        val summary24 = WirelessTelemetryParsers.summarizeAllowedBandChannels(
            bandGhz = 2,
            allowedFreqsWidths = allowed24,
            usableFreqs = usable24,
            isBandSupported = sup24,
            supports11be = sup11be,
            supports11axOrAc = sup11ax || sup11ac,
            supports11n = sup11n,
        )
        val summary5 = WirelessTelemetryParsers.summarizeAllowedBandChannels(
            bandGhz = 5,
            allowedFreqsWidths = allowed5,
            usableFreqs = usable5,
            isBandSupported = sup5,
            supports11be = sup11be,
            supports11axOrAc = sup11ax || sup11ac,
            supports11n = sup11n,
        )
        val summary6 = WirelessTelemetryParsers.summarizeAllowedBandChannels(
            bandGhz = 6,
            allowedFreqsWidths = allowed6,
            usableFreqs = usable6,
            isBandSupported = sup6,
            supports11be = sup11be,
            supports11axOrAc = sup11ax || sup11ac,
            supports11n = sup11n,
        )

        val (cc, ccSrc) = resolveRegulatoryRegion(wm, hasLocationPerm, nowElapsedMs)

        return WifiRadioCapabilities(
            isCapabilitiesQueried = true,
            hasNearbyWifiPermission = hasNearbyPerm,
            channelQuerySucceeded = channelQueryOk,
            supports11beWifi7 = sup11be,
            supports11axWifi6 = sup11ax,
            supports11acWifi5 = sup11ac,
            supports11nWifi4 = sup11n,
            supports11abgLegacy = sup11abg,
            supports11adWigig = sup11ad,
            supports24Ghz = sup24,
            supports5Ghz = sup5,
            supports6Ghz = sup6,
            supports60Ghz = sup60,
            maxTxLinkSpeedMbps = maxTxPhy,
            maxRxLinkSpeedMbps = maxRxPhy,
            supportsWpa3Sae = supSae,
            supportsSaeH2e = supSaeH2e,
            supportsSaePk = supSaePk,
            supportsOwe = supOwe,
            supportsWpa3SuiteB192 = supSuiteB,
            supportsTlsV13 = supTls13,
            supportsTrustOnFirstUse = supTofu,
            supportsEasyConnectDpp = supDpp,
            supportsWpa2Personal = supWpa2,
            supportsDualBandSimultaneous = supDbs,
            supportsMloTidToLinkMapping = supT2lm,
            supportsMakeBeforeBreak = supMbb,
            supportsStaApConcurrency = supStaAp,
            isScanThrottleEnabled = scanThrottle,
            countryCode = cc,
            countryCodeSource = ccSrc,
            band24Ghz = summary24,
            band5Ghz = summary5,
            band6Ghz = summary6,
        ).also { cachedWifiCapabilities = it }
    }

    @Suppress("MissingPermission", "DEPRECATION")
    private fun readSurroundingWifiScan(
        wm: WifiManager?,
        wifiInfo: WifiInfo?,
        isWifiConnected: Boolean,
        nowElapsedMs: Long,
    ): WifiScanTelemetrySnapshot {
        val wifiEnabled = runCatching { wm?.isWifiEnabled ?: false }.getOrDefault(false) || isWifiConnected
        val hasScanPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val locEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { lm?.isLocationEnabled ?: true }.getOrDefault(true)
        } else {
            true
        }

        if (wm == null || !wifiEnabled || !hasScanPerm || !locEnabled) {
            return WifiScanTelemetrySnapshot(
                hasScanLocationPermission = hasScanPerm,
                isLocationEnabled = locEnabled,
                isWifiEnabled = wifiEnabled,
            ).also { cachedWifiScanSnapshot = it }
        }

        val dt = nowElapsedMs - lastWifiScanSampleElapsedMs
        if (!wifiScanResultsDirty &&
            lastWifiScanSampleElapsedMs != 0L &&
            dt in 0L until 4_000L &&
            cachedWifiScanSnapshot.hasScanLocationPermission == hasScanPerm &&
            cachedWifiScanSnapshot.isLocationEnabled == locEnabled
        ) {
            return cachedWifiScanSnapshot
        }
        wifiScanResultsDirty = false
        lastWifiScanSampleElapsedMs = nowElapsedMs

        val rawResults: List<ScanResult> = runCatching { wm.scanResults.orEmpty() }.getOrDefault(emptyList())
        val activeBssid = runCatching { wifiInfo?.bssid?.trim()?.lowercase(java.util.Locale.US) }.getOrNull()
            ?.takeIf { it.isNotEmpty() && it != "02:00:00:00:00:00" && it != "00:00:00:00:00:00" }

        val refElapsedUs = maxOf(nowElapsedMs * 1_000L, SystemClock.elapsedRealtime() * 1_000L)
        var c24 = 0
        var c5 = 0
        var c6 = 0
        var minAgeSec = Float.MAX_VALUE
        var bestBeaconCountry: String? = null

        val parsedAps = ArrayList<SurroundingWifiAp>(rawResults.size.coerceAtMost(80))
        val sortedRaw = rawResults.sortedWith(
            compareByDescending<ScanResult> { sr ->
                val b = sr.BSSID?.trim()?.lowercase(java.util.Locale.US)
                isWifiConnected && activeBssid != null && b == activeBssid
            }.thenByDescending { it.level },
        )

        for (sr in sortedRaw) {
            val freq = sr.frequency
            val band = WirelessTelemetryParsers.bandGhzForFrequency(freq)
            when (band) {
                2 -> c24++
                5 -> c5++
                6 -> c6++
            }
            if (parsedAps.size >= 80) continue

            val rawSsidStr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                runCatching { sr.wifiSsid?.toString() }.getOrNull() ?: sr.SSID
            } else {
                sr.SSID
            }
            val (ssidLabel, isHidden) = WirelessTelemetryParsers.sanitizeSsidDisplay(rawSsidStr)
            val cleanBssid = sr.BSSID?.trim()?.lowercase(java.util.Locale.US)?.ifBlank { "00:00:00:00:00:00" }
                ?: "00:00:00:00:00:00"
            val isConnectedAp = isWifiConnected && activeBssid != null && cleanBssid == activeBssid
            val (bwMhz, bwLabel) = WirelessTelemetryParsers.formatChannelBandwidth(sr.channelWidth)
            val stdCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching { sr.wifiStandard }.getOrDefault(0)
            } else {
                0
            }
            val secTypes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                runCatching { sr.securityTypes }.getOrDefault(IntArray(0))
            } else {
                IntArray(0)
            }
            val (secSummary, baseBadges) = WirelessTelemetryParsers.formatScanCapabilitiesSecurity(
                capabilitiesRaw = sr.capabilities.orEmpty(),
                securityTypes = secTypes,
            )

            val ies = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching {
                    val rawIes = sr.informationElements.orEmpty()
                    val pairs = ArrayList<Pair<Int, ByteArray>>(rawIes.size)
                    for (ie in rawIes) {
                        val buf = ie.bytes.duplicate()
                        val len = buf.remaining().coerceIn(0, 255)
                        val arr = ByteArray(len)
                        buf.get(arr)
                        pairs += ie.id to arr
                    }
                    WirelessTelemetryParsers.parse80211InformationElements(pairs)
                }.getOrDefault(ParsedBeaconIes())
            } else {
                ParsedBeaconIes()
            }

            if (ies.countryCode80211d != null && (isConnectedAp || bestBeaconCountry == null)) {
                bestBeaconCountry = ies.countryCode80211d
            }

            val mloCount = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                runCatching { sr.affiliatedMloLinks.size }.getOrDefault(0)
            } else {
                0
            }
            val hasTwt = if (Build.VERSION.SDK_INT >= 35) {
                runCatching { sr.isTwtResponder }.getOrDefault(false)
            } else {
                false
            }
            val has11az = if (Build.VERSION.SDK_INT >= 35) {
                runCatching { sr.is80211azNtbResponder }.getOrDefault(false)
            } else {
                false
            }
            val has11mc = runCatching { sr.is80211mcResponder }.getOrDefault(false)
            val isPasspoint = runCatching { sr.isPasspointNetwork }.getOrDefault(false)

            val badges = LinkedHashSet<String>(baseBadges.size + 6).apply {
                addAll(baseBadges)
                if (ies.fastTransition11r) add("11r")
                if (ies.neighborReport11k) add("11k")
                if (ies.bssTransition11v) add("11v")
                if (mloCount > 1) add("MLO (${mloCount}L)")
                if (hasTwt) add("TWT")
                if (has11az) add("11az")
                else if (has11mc) add("11mc")
                if (isPasspoint) add("Passpoint")
            }.toList()

            val ageSec = if (sr.timestamp > 0L && refElapsedUs >= sr.timestamp) {
                ((refElapsedUs - sr.timestamp) / 1_000_000f).coerceIn(0f, 3_600f)
            } else {
                0f
            }
            if (ageSec < minAgeSec) minAgeSec = ageSec

            parsedAps += SurroundingWifiAp(
                ssid = ssidLabel,
                isHiddenSsid = isHidden,
                bssid = cleanBssid,
                maskedBssid = WirelessTelemetryParsers.maskBssid(cleanBssid),
                isConnectedAp = isConnectedAp,
                bandGhz = band,
                bandLabel = WirelessTelemetryParsers.bandLabelForFrequency(freq),
                primaryFreqMhz = freq,
                centerFreq0Mhz = sr.centerFreq0.coerceAtLeast(0),
                channelNumber = WirelessTelemetryParsers.frequencyMhzToChannel(freq),
                channelWidthMhz = bwMhz,
                channelWidthLabel = bwLabel,
                isDfsChannel = WirelessTelemetryParsers.is5GhzDfsChannel(freq),
                isPscChannel = WirelessTelemetryParsers.is6GhzPscChannel(freq),
                wifiStandardCode = stdCode,
                wifiStandardLabel = WirelessTelemetryParsers.formatWifiStandardForBand(stdCode, freq),
                securitySummary = secSummary,
                capabilityBadges = badges,
                rssiDbm = sr.level,
                signalPercent = WirelessTelemetryParsers.computeRssiQualityPercent(sr.level),
                mloAffiliatedLinkCount = mloCount,
                countryCode80211d = ies.countryCode80211d,
                qbssStationCount = ies.qbssStationCount,
                qbssChannelUtilizationPct = ies.qbssChannelUtilizationPct,
                ageSeconds = ageSec,
            )
        }

        if (bestBeaconCountry != null) {
            latestBeaconCountry80211d = bestBeaconCountry
        }

        return WifiScanTelemetrySnapshot(
            hasScanLocationPermission = hasScanPerm,
            isLocationEnabled = locEnabled,
            isWifiEnabled = wifiEnabled,
            totalApCount = rawResults.size,
            count24Ghz = c24,
            count5Ghz = c5,
            count6Ghz = c6,
            lastScanAgeSeconds = if (minAgeSec < Float.MAX_VALUE) minAgeSec else -1f,
            accessPoints = parsedAps,
        ).also { cachedWifiScanSnapshot = it }
    }

    @Suppress("MissingPermission")
    private fun readBluetooth(nowElapsedMs: Long): BluetoothTelemetrySnapshot {
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

        // Refresh stack-level A2DP / HFP / LE Audio frame drop & underflow counters asynchronously off the UI thread
        val isScoOn = isVoiceMode && scoOutput != null
        val isLeAudioActive = bleOutput != null && (btOutput == bleOutput)
        val hasDumpPerm = ContextCompat.checkSelfPermission(
            context,
            "android.permission.DUMP",
        ) == PackageManager.PERMISSION_GRANTED
        val hasReadLogs = ContextCompat.checkSelfPermission(
            context,
            "android.permission.READ_LOGS",
        ) == PackageManager.PERMISSION_GRANTED

        if (hasDumpPerm || hasReadLogs) {
            val elapsedSinceLast = nowElapsedMs - lastBtStackSampleElapsedMs
            if (!btStackRefreshInFlight && (lastBtStackSampleElapsedMs == 0L || elapsedSinceLast >= 3_000L || elapsedSinceLast < 0L)) {
                btStackRefreshInFlight = true
                lastBtStackSampleElapsedMs = nowElapsedMs
                btStackExecutor.execute {
                    try {
                        val dumpOut = if (hasDumpPerm) {
                            runCatching {
                                val proc = ProcessBuilder("dumpsys", "bluetooth_manager")
                                    .redirectErrorStream(true)
                                    .start()
                                val text = proc.inputStream.bufferedReader().use { it.readText() }
                                proc.waitFor()
                                text
                            }.getOrDefault("")
                        } else {
                            ""
                        }
                        val logOut = if (!hasDumpPerm && hasReadLogs) {
                            runCatching {
                                val proc = ProcessBuilder(
                                    "logcat",
                                    "-d",
                                    "-s",
                                    "A2dpService:D",
                                    "bluetooth-a2dp-aidl:I",
                                    "A2dpStateMachine:D",
                                    "btif_a2dp_source:V",
                                    "bta_ag_sco:V",
                                    "le_audio:V",
                                )
                                    .redirectErrorStream(true)
                                    .start()
                                val text = proc.inputStream.bufferedReader().use { it.readText() }
                                proc.waitFor()
                                text
                            }.getOrDefault("")
                        } else {
                            ""
                        }
                        cachedBtDumpsysText = dumpOut
                        cachedBtLogcatText = logOut
                        cachedAudioDropStats = WirelessTelemetryParsers.parseBluetoothAudioDropStats(
                            dumpsysText = dumpOut,
                            logcatText = logOut,
                            isScoOn = isScoOn,
                            isLeAudioActive = isLeAudioActive,
                        )
                    } finally {
                        btStackRefreshInFlight = false
                    }
                }
            }
        } else {
            cachedAudioDropStats = BluetoothAudioDropStats()
        }

        // Fallback: if A2DP is active and getCodecStatus is gated by BLUETOOTH_PRIVILEGED, inspect dumpsys + logcat
        if (btOutput?.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP && codecName == "A2DP Audio Stream") {
            val combinedSource = if (cachedBtDumpsysText.isNotEmpty() || cachedBtLogcatText.isNotEmpty()) {
                "$cachedBtDumpsysText\n$cachedBtLogcatText"
            } else {
                ""
            }
            val cacheKey = "$routeName|$audioModeLabel|${combinedSource.length}"
            val logCodec = if (cacheKey == cachedLogcatCodecKey && cachedLogcatCodecSnapshot != null) {
                cachedLogcatCodecSnapshot
            } else if (combinedSource.isNotBlank()) {
                val parsed = WirelessTelemetryParsers.parseA2dpLogcatCodec(combinedSource, routeName, audioModeLabel)
                cachedLogcatCodecKey = cacheKey
                cachedLogcatCodecSnapshot = parsed
                parsed
            } else {
                null
            }
            if (logCodec != null) {
                codecName = logCodec.activeCodecName
                sampleRateStr = logCodec.sampleRateLabel
                bitsStr = logCodec.bitsPerSampleLabel
                channelStr = logCodec.channelModeLabel
                qualityStr = logCodec.bitrateOrQualityLabel
                selectableSummary = logCodec.selectableCodecsSummary
            } else {
                val aacPrio = readSystemProperty("bluetooth.a2dp.source.aac_priority.config")
                val ldacPrio = readSystemProperty("bluetooth.a2dp.source.ldac_priority.config")
                val aptxPrio = readSystemProperty("bluetooth.a2dp.source.aptx_priority.config")
                if (aacPrio == "-1" && ldacPrio == "-1" && aptxPrio == "-1") {
                    codecName = "SBC"
                    sampleRateStr = "44.1 kHz"
                    bitsStr = "16-bit"
                    channelStr = "Joint Stereo"
                    qualityStr = "243–328 kbps (Bitpool 2–37)"
                }
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
            audioDrops = cachedAudioDropStats,
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

    private fun ensureUsbListenersRegistered() {
        if (usbListenersRegistered) return
        usbListenersRegistered = true
        val appCtx = context.applicationContext

        runCatching {
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            ContextCompat.registerReceiver(
                appCtx,
                object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context?, intent: Intent?) {
                        usbTopologyDirty = true
                    }
                },
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }

        runCatching {
            val im = appCtx.getSystemService(Context.INPUT_SERVICE) as? InputManager
            im?.registerInputDeviceListener(
                object : InputManager.InputDeviceListener {
                    override fun onInputDeviceAdded(deviceId: Int) {
                        usbTopologyDirty = true
                    }

                    override fun onInputDeviceRemoved(deviceId: Int) {
                        usbTopologyDirty = true
                    }

                    override fun onInputDeviceChanged(deviceId: Int) {
                        usbTopologyDirty = true
                    }
                },
                null,
            )
        }

        runCatching {
            val audioMgr = appCtx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioMgr?.registerAudioDeviceCallback(
                object : AudioDeviceCallback() {
                    override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                        usbTopologyDirty = true
                    }

                    override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                        usbTopologyDirty = true
                    }
                },
                null,
            )
        }
    }

    private fun scheduleUsbDumpsysIfPermitted(hasDump: Boolean, nowElapsedMs: Long) {
        if (!hasDump) {
            cachedUsbDumpsysText = ""
            return
        }
        if (usbDumpsysRefreshInFlight) return
        val dt = nowElapsedMs - lastUsbDumpsysSampleElapsedMs
        if (!usbTopologyDirty && lastUsbDumpsysSampleElapsedMs != 0L && dt in 0L until 5_000L && cachedUsbDumpsysText.isNotEmpty()) {
            return
        }
        usbDumpsysRefreshInFlight = true
        lastUsbDumpsysSampleElapsedMs = nowElapsedMs
        runCatching {
            btStackExecutor.execute {
                try {
                    val proc = ProcessBuilder("/system/bin/dumpsys", "usb")
                        .redirectErrorStream(true)
                        .start()
                    val sb = StringBuilder(16_384)
                    proc.inputStream.bufferedReader().use { reader ->
                        var linesRead = 0
                        var insidePortManager = false
                        var braceDepth = 0
                        while (linesRead < 3_500 && sb.length < 131_072) {
                            val line = reader.readLine() ?: break
                            linesRead++
                            val trimmed = line.trim()
                            if (!insidePortManager) {
                                if (trimmed.startsWith("port_manager={")) {
                                    insidePortManager = true
                                    braceDepth = line.count { it == '{' } - line.count { it == '}' }
                                    sb.appendLine(line)
                                    if (braceDepth <= 0) break
                                }
                            } else {
                                sb.appendLine(line)
                                braceDepth += line.count { it == '{' } - line.count { it == '}' }
                                if (braceDepth <= 0) break
                            }
                        }
                    }
                    runCatching { proc.destroy() }
                    cachedUsbDumpsysText = sb.toString()
                } catch (_: Throwable) {
                    // Ignore process sandbox failures
                } finally {
                    usbDumpsysRefreshInFlight = false
                }
            }
        }.onFailure {
            usbDumpsysRefreshInFlight = false
        }
    }

    private fun readUsbPeripherals(nowElapsedMs: Long): UsbPeripheralTelemetrySnapshot {
        ensureUsbListenersRegistered()
        val hasDump = ContextCompat.checkSelfPermission(context, Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED
        scheduleUsbDumpsysIfPermitted(hasDump, nowElapsedMs)

        val dt = nowElapsedMs - lastUsbSampleElapsedMs
        if (!usbTopologyDirty &&
            lastUsbSampleElapsedMs != 0L &&
            dt in 0L until 2_500L &&
            cachedUsbSnapshot.hasDumpPermission == hasDump &&
            (cachedUsbSnapshot.hasTypeCPorts || cachedUsbDumpsysText.isEmpty())
        ) {
            return cachedUsbSnapshot
        }
        lastUsbSampleElapsedMs = nowElapsedMs
        usbTopologyDirty = false

        val rawSysfs = UsbPeripheralParsers.readSysfsUsbDirectory(usbSysfsRoot)
        val correlated = collectCorrelatedUsbCapabilities(rawSysfs)
        val typeCPorts = if (hasDump && cachedUsbDumpsysText.isNotEmpty()) {
            UsbPeripheralParsers.parseUsbDumpsysPorts(cachedUsbDumpsysText, nowElapsedMs)
        } else {
            emptyList()
        }

        val prelim = UsbPeripheralParsers.buildUsbTopologySnapshot(
            rawDevices = rawSysfs,
            correlatedByVidPid = correlated,
            hasDumpPermission = hasDump,
            typeCPorts = typeCPorts,
        )
        val events = usbHotplugTracker.recordSample(prelim.nodes, nowElapsedMs)
        val snap = prelim.copy(hotplugEvents = events)
        cachedUsbSnapshot = snap
        return snap
    }

    private fun collectCorrelatedUsbCapabilities(
        rawSysfs: List<UsbPeripheralParsers.RawSysfsUsbDevice>,
    ): Map<Pair<Int, Int>, UsbCorrelatedInfo> {
        val map = HashMap<Pair<Int, Int>, UsbCorrelatedInfo>()

        // 1. UsbManager host devices (endpoint & configuration counts)
        runCatching {
            val usbMgr = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            val devMap = usbMgr?.deviceList.orEmpty()
            for (uDev in devMap.values) {
                val key = (uDev.vendorId and 0xFFFF) to (uDev.productId and 0xFFFF)
                var epTotal = 0
                for (i in 0 until uDev.interfaceCount) {
                    epTotal += runCatching { uDev.getInterface(i).endpointCount }.getOrDefault(0)
                }
                val prev = map[key] ?: UsbCorrelatedInfo()
                map[key] = prev.copy(
                    usbManagerEndpointTotal = maxOf(prev.usbManagerEndpointTotal, epTotal),
                    usbManagerConfigCount = maxOf(prev.usbManagerConfigCount, uDev.configurationCount),
                    isPresentInUsbManager = true,
                )
            }
        }

        // 2. InputManager external USB HID devices (keyboard type, pointer/gamepad/stylus sources, API 31+ battery %)
        runCatching {
            val im = context.getSystemService(Context.INPUT_SERVICE) as? InputManager
            val ids = im?.inputDeviceIds ?: IntArray(0)
            for (id in ids) {
                val dev = im?.getInputDevice(id) ?: continue
                if (dev.isVirtual) continue
                val vid = dev.vendorId and 0xFFFF
                val pid = dev.productId and 0xFFFF
                if (vid == 0 && pid == 0) continue
                val isExt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    runCatching { dev.isExternal }.getOrDefault(true)
                } else {
                    true
                }
                val srcLabels = ArrayList<String>(4)
                val sources = dev.sources
                if ((sources and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD &&
                    dev.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
                ) {
                    srcLabels += "Alphabetic Keyboard"
                }
                if ((sources and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) {
                    srcLabels += "Mouse"
                }
                if ((sources and InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD) {
                    srcLabels += "Touchpad"
                }
                if ((sources and InputDevice.SOURCE_STYLUS) == InputDevice.SOURCE_STYLUS) {
                    srcLabels += "Stylus"
                }
                if ((sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                    (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                ) {
                    srcLabels += "Gamepad / Joystick"
                }
                if (srcLabels.isEmpty()) continue

                val battPct = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    runCatching {
                        val bs = dev.batteryState
                        if (bs.isPresent && bs.capacity in 0f..1f) (bs.capacity * 100f).toInt() else -1
                    }.getOrDefault(-1)
                } else {
                    -1
                }
                val key = vid to pid
                val prev = map[key] ?: UsbCorrelatedInfo()
                val mergedSrc = listOfNotNull(prev.inputSourcesSummary, srcLabels.joinToString(" · "))
                    .flatMap { it.split(" · ") }
                    .distinct()
                    .joinToString(" · ")
                map[key] = prev.copy(
                    inputSourcesSummary = mergedSrc,
                    inputBatteryPercent = if (battPct in 0..100) battPct else prev.inputBatteryPercent,
                    isExternalInputDevice = prev.isExternalInputDevice || isExt,
                )
            }
        }

        // 3. AudioManager USB DAC / Headset sample rates, bit depths & channels
        runCatching {
            val audioMgr = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val usbAudioDevices = audioMgr?.getDevices(
                AudioManager.GET_DEVICES_INPUTS or AudioManager.GET_DEVICES_OUTPUTS,
            ).orEmpty().filter { info ->
                info.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    info.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    info.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
            }
            if (usbAudioDevices.isNotEmpty()) {
                val audioCandidates = rawSysfs.filter { r ->
                    r.interfaces.any { it.classCode == 0x01 || it.driverName.equals("snd-usb-audio", ignoreCase = true) }
                }
                for (aInfo in usbAudioDevices) {
                    val prodName = aInfo.productName?.toString()?.trim().orEmpty()
                    val matchedRaw = audioCandidates.firstOrNull { r ->
                        prodName.isNotEmpty() && (
                            r.product.contains(prodName, ignoreCase = true) ||
                                prodName.contains(r.product.trim(), ignoreCase = true)
                            )
                    } ?: audioCandidates.singleOrNull()
                    if (matchedRaw != null) {
                        val maxHz = aInfo.sampleRates.maxOrNull() ?: 48_000
                        val maxCh = aInfo.channelCounts.maxOrNull() ?: 2
                        val bitLabel = when {
                            aInfo.encodings.contains(AudioFormat.ENCODING_PCM_32BIT) ||
                                aInfo.encodings.contains(AudioFormat.ENCODING_PCM_FLOAT) -> "32-bit"
                            aInfo.encodings.contains(AudioFormat.ENCODING_PCM_24BIT_PACKED) -> "24-bit"
                            else -> "16-bit"
                        }
                        val typeStr = if (aInfo.type == AudioDeviceInfo.TYPE_USB_HEADSET) "USB Headset" else "USB Audio DAC"
                        val summary = String.format(Locale.US, "%s · Up to %.1f kHz · %s · %dch", typeStr, maxHz / 1000f, bitLabel, maxCh)
                        val key = (matchedRaw.vendorId and 0xFFFF) to (matchedRaw.productId and 0xFFFF)
                        val prev = map[key] ?: UsbCorrelatedInfo()
                        map[key] = prev.copy(audioCapabilitiesSummary = summary)
                    }
                }
            }
        }

        // 4. CameraManager external UVC cameras
        runCatching {
            val camMgr = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            val extCamIds = camMgr?.cameraIdList.orEmpty().filter { id ->
                val chars = runCatching { camMgr?.getCameraCharacteristics(id) }.getOrNull()
                chars?.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_EXTERNAL
            }
            if (extCamIds.isNotEmpty()) {
                val uvcNodes = rawSysfs.filter { r ->
                    r.removable != "fixed" && r.interfaces.any { it.classCode == 0x0e || it.driverName == "uvcvideo" }
                }
                for (uvc in uvcNodes) {
                    val key = (uvc.vendorId and 0xFFFF) to (uvc.productId and 0xFFFF)
                    val prev = map[key] ?: UsbCorrelatedInfo()
                    map[key] = prev.copy(cameraSummary = "External UVC Camera (${extCamIds.size} stream(s) registered)")
                }
            }
        }

        // 5. StorageManager removable USB volumes
        runCatching {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            val removableVols = sm?.storageVolumes.orEmpty().filter { it.isRemovable }
            if (removableVols.isNotEmpty()) {
                val storageNodes = rawSysfs.filter { r ->
                    r.interfaces.any { it.classCode == 0x08 || it.driverName in setOf("usb-storage", "uas") }
                }
                val volDesc = removableVols.joinToString(", ") { v ->
                    val desc = SysfsParsers.sanitizeSensorName(v.getDescription(context), "USB Drive")
                    "$desc (${v.state})"
                }
                for (st in storageNodes) {
                    val key = (st.vendorId and 0xFFFF) to (st.productId and 0xFFFF)
                    val prev = map[key] ?: UsbCorrelatedInfo()
                    map[key] = prev.copy(storageVolumeSummary = volDesc)
                }
            }
        }

        return map
    }

    private fun readSystemProperty(key: String): String? {
        return runCatching {
            val cls = Class.forName("android.os.SystemProperties")
            val getMethod = cls.getMethod("get", String::class.java)
            (getMethod.invoke(null, key) as? String)?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull()
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
