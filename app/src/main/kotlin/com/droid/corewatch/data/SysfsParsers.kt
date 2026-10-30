// Copyright 2026 The Corewatch Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.corewatch.data

import kotlin.math.roundToInt

enum class CoreCluster(val label: String) {
    Performance("P-Core"),
    Efficiency("E-Core"),
    Uniform("Core"),
}

data class CpuCoreReading(
    val coreIndex: Int,
    val curKhz: Long,
    val minKhz: Long,
    val maxKhz: Long,
    val governor: String,
    val cluster: CoreCluster,
    val activeResidencyFraction: Float? = null,
) {
    val curMhz: Int get() = (curKhz / 1_000L).toInt().coerceAtLeast(0)
    val maxMhz: Int get() = (maxKhz / 1_000L).toInt().coerceAtLeast(0)
    val minMhz: Int get() = (minKhz / 1_000L).toInt().coerceAtLeast(0)

    val frequencyFraction: Float
        get() {
            if (maxKhz <= 0L) return 0f
            return (curKhz.toFloat() / maxKhz.toFloat()).coerceIn(0f, 1f)
        }

    val utilizationFraction: Float
        get() = activeResidencyFraction?.coerceIn(0f, 1f) ?: frequencyFraction

    val scalingStateLabel: String
        get() = when {
            governor.equals("powersave", ignoreCase = true) -> when {
                utilizationFraction >= 0.62f -> "turbo"
                utilizationFraction >= 0.25f -> "balanced"
                else -> "powersave"
            }
            else -> governor
        }
}

enum class ThermalLevel(val statusCode: Int, val label: String, val severityFraction: Float) {
    NONE(0, "Nominal", 0.12f),
    LIGHT(1, "Light Throttling", 0.32f),
    MODERATE(2, "Moderate Throttling", 0.55f),
    SEVERE(3, "Severe Throttling", 0.78f),
    CRITICAL(4, "Critical Thermal", 0.92f),
    EMERGENCY(5, "Emergency Thermal", 1.0f),
    SHUTDOWN(6, "Thermal Shutdown", 1.0f);

    companion object {
        fun fromStatusCode(code: Int): ThermalLevel =
            entries.firstOrNull { it.statusCode == code } ?: if (code > 6) SHUTDOWN else NONE
    }
}

data class ThermalSensorReading(
    val id: String,
    val name: String,
    val celsius: Float,
    val source: String,
) {
    val fahrenheit: Float get() = celsius * 9f / 5f + 32f
}

data class MemorySnapshot(
    val totalBytes: Long,
    val availableBytes: Long,
    val freeBytes: Long,
    val cachedBytes: Long,
    val buffersBytes: Long,
    val dirtyBytes: Long,
    val swapTotalBytes: Long,
    val swapFreeBytes: Long,
    val amsThresholdBytes: Long = 216L shl 20,
    val isLowMemory: Boolean = false,
) {
    val usedBytes: Long get() = (totalBytes - availableBytes).coerceAtLeast(0L)
    val usedFraction: Float
        get() = if (totalBytes <= 0L) 0f else (usedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)

    val swapUsedBytes: Long get() = (swapTotalBytes - swapFreeBytes).coerceAtLeast(0L)
    val swapUsedFraction: Float
        get() = if (swapTotalBytes <= 0L) 0f else (swapUsedBytes.toDouble() / swapTotalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
}

data class GpuTelemetrySnapshot(
    val driverName: String = "xe",
    val deviceName: String = "Intel Xe2 Graphics",
    val actualFreqMhz: Int = 0,
    val requestedFreqMhz: Int = 800,
    val minFreqMhz: Int = 100,
    val efficientFreqMhz: Int = 850,
    val maxFreqMhz: Int = 2500,
    val activeResidencyFraction: Float? = null,
    val powerProfile: String = "power_saving",
    val isThrottled: Boolean = false,
    val throttleReasons: List<String> = emptyList(),
    val mediaEngineFreqMhz: Int? = null,
) {
    /**
     * Operating GPU frequency in MHz (`actualFreqMhz` when an EU slice is mid-frame, or GuC SLPC
     * `requestedFreqMhz` / `cur_freq` when sampled during an inter-frame RC6 micro-sleep window).
     */
    val displayFreqMhz: Int
        get() = if (actualFreqMhz > 0) actualFreqMhz else requestedFreqMhz

    val utilizationFraction: Float
        get() {
            if (activeResidencyFraction != null) return activeResidencyFraction.coerceIn(0f, 1f)
            if (maxFreqMhz <= 0) return 0f
            return (actualFreqMhz.toFloat() / maxFreqMhz.toFloat()).coerceIn(0f, 1f)
        }

    val scalingStateLabel: String
        get() = when {
            isThrottled -> "throttled"
            actualFreqMhz == 0 && utilizationFraction < 0.05f -> "rc6-sleep"
            utilizationFraction >= 0.62f -> "turbo"
            utilizationFraction >= 0.25f -> "balanced"
            else -> "powersave"
        }

    val throttleSummary: String
        get() = if (!isThrottled || throttleReasons.isEmpty()) "Nominal" else throttleReasons.joinToString(", ")
}

data class NpuTelemetrySnapshot(
    val driverName: String = "intel_vpu",
    val deviceName: String = "Intel NPU",
    val currentFreqMhz: Int = 0,
    val maxFreqMhz: Int = 2000,
    val activeUtilizationFraction: Float = 0f,
    val engineHeartbeat: Long = 0L,
    val activeContexts: Int = 0,
    val maxContexts: Int = 64,
    val firmwareVersion: String = "v3.0",
    val memoryUsedBytes: Long = 0L,
) {
    val utilizationFraction: Float
        get() = activeUtilizationFraction.coerceIn(0f, 1f)

    val scalingStateLabel: String
        get() = when {
            utilizationFraction >= 0.62f -> "turbo"
            utilizationFraction >= 0.15f -> "active"
            currentFreqMhz > 0 && utilizationFraction > 0.01f -> "balanced"
            else -> "d3cold-sleep"
        }
}

/**
 * Pure, exception-safe parsers for `/sys/devices/system/cpu/cpufreq`, `/sys/class/thermal`,
 * `/sys/class/hwmon`, and `/proc/meminfo`.
 */
object SysfsParsers {
    const val MIN_VALID_CELSIUS = -40f
    const val MAX_VALID_CELSIUS = 150f
    const val MAX_FREQ_KHZ = 10_000_000L // 10 GHz ceiling guard

    /**
     * Parses per-core cpufreq attributes (`scaling_cur_freq`, `scaling_max_freq`, `cpuinfo_max_freq`,
     * `cpuinfo_min_freq`, `scaling_governor`) and classifies hybrid P-Core vs E-Core topologies.
     */
    fun parseCpuCores(rawCores: Map<Int, Map<String, String>>): List<CpuCoreReading> {
        if (rawCores.isEmpty()) return emptyList()
        val parsed = rawCores.entries
            .filter { it.key in 0..255 }
            .sortedBy { it.key }
            .mapNotNull { (idx, attrs) ->
                val maxKhz = parseBoundedKhz(attrs["cpuinfo_max_freq"])
                    ?: parseBoundedKhz(attrs["scaling_max_freq"])
                    ?: parseBoundedKhz(attrs["scaling_cur_freq"])
                    ?: return@mapNotNull null
                val minKhz = (parseBoundedKhz(attrs["cpuinfo_min_freq"])
                    ?: parseBoundedKhz(attrs["scaling_min_freq"]) ?: 0L).coerceAtMost(maxKhz)
                val curKhz = (parseBoundedKhz(attrs["scaling_cur_freq"])
                    ?: parseBoundedKhz(attrs["cpuinfo_cur_freq"]) ?: minKhz).coerceIn(0L, maxKhz)
                val gov = sanitizeSensorName(attrs["scaling_governor"].orEmpty(), "schedutil")
                CpuCoreReading(
                    coreIndex = idx,
                    curKhz = curKhz,
                    minKhz = minKhz,
                    maxKhz = maxKhz,
                    governor = gov,
                    cluster = CoreCluster.Uniform,
                )
            }
        if (parsed.isEmpty()) return emptyList()
        val distinctMax = parsed.map { it.maxKhz }.distinct().sorted()
        if (distinctMax.size <= 1) return parsed
        val highestMax = distinctMax.last()
        val threshold = (highestMax * 85L) / 100L
        return parsed.map { core ->
            val cluster = if (core.maxKhz >= threshold) CoreCluster.Performance else CoreCluster.Efficiency
            core.copy(cluster = cluster)
        }
    }

    private fun parseBoundedKhz(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val v = raw.trim().toLongOrNull() ?: return null
        if (v <= 0L || v > MAX_FREQ_KHZ) return null
        return v
    }

    /**
     * Converts a kernel temperature string (millidegrees e.g. `48500`, decicelsius `485`, or degrees `48.5`)
     * into degrees Celsius, returning `null` for disconnected/bogus readings outside `[-40, 150] °C`.
     */
    fun parseThermalValueCelsius(raw: String?): Float? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        val num = trimmed.toDoubleOrNull() ?: return null
        if (!num.isFinite()) return null
        val celsius = when {
            kotlin.math.abs(num) >= 1_000.0 -> (num / 1_000.0).toFloat()
            kotlin.math.abs(num) > 150.0 -> (num / 10.0).toFloat()
            else -> num.toFloat()
        }
        if (!celsius.isFinite() || celsius < MIN_VALID_CELSIUS || celsius > MAX_VALID_CELSIUS) return null
        return ((celsius * 10f).roundToInt() / 10f)
    }

    fun sanitizeSensorName(raw: String?, fallback: String = "sensor"): String {
        if (raw.isNullOrBlank()) return fallback
        val cleaned = buildString(raw.length.coerceAtMost(64)) {
            for (ch in raw.trim()) {
                if (length >= 64) break
                when {
                    ch < ' ' || ch == '\u007f' -> append(' ')
                    ch == '|' || ch == '`' || ch == '<' || ch == '>' -> append('_')
                    else -> append(ch)
                }
            }
        }.trim().replace(WHITESPACE_REGEX, " ")
        return cleaned.ifEmpty { fallback }
    }

    /**
     * Parses `/sys/class/thermal/thermal_zone*` (`type` to `temp`) and `/sys/class/hwmon/hwmon*`
     * (`chipName`, `label`, `temp_input`) entries into sorted, deduplicated [ThermalSensorReading]s.
     */
    fun parseThermalReadings(
        thermalZones: List<Pair<String, String>>,
        hwmonSensors: List<Triple<String, String, String>> = emptyList(),
    ): List<ThermalSensorReading> {
        val out = ArrayList<ThermalSensorReading>()
        thermalZones.forEachIndexed { idx, (rawType, rawTemp) ->
            val tempC = parseThermalValueCelsius(rawTemp) ?: return@forEachIndexed
            val name = sanitizeSensorName(rawType, "thermal_zone$idx")
            out += ThermalSensorReading(
                id = "tz:$idx:$name",
                name = name,
                celsius = tempC,
                source = "thermal_zone",
            )
        }
        hwmonSensors.forEachIndexed { idx, (chip, label, rawTemp) ->
            val tempC = parseThermalValueCelsius(rawTemp) ?: return@forEachIndexed
            val cleanChip = sanitizeSensorName(chip, "hwmon$idx")
            val cleanLabel = sanitizeSensorName(label, "")
            val fullName = if (cleanLabel.isNotEmpty() && !cleanLabel.equals(cleanChip, ignoreCase = true)) {
                "$cleanChip ($cleanLabel)"
            } else {
                cleanChip
            }
            out += ThermalSensorReading(
                id = "hwmon:$idx:$fullName",
                name = fullName,
                celsius = tempC,
                source = "hwmon",
            )
        }
        return out
    }

    /**
     * Parses `/proc/meminfo` text into [MemorySnapshot]. Missing or corrupt fields default safely to `0L`
     * and `availableBytes` never exceeds `totalBytes`.
     */
    fun parseMemInfo(text: String): MemorySnapshot {
        val valuesKb = HashMap<String, Long>()
        for (line in text.lineSequence().take(256)) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim()
            val rest = line.substring(colon + 1).trim()
            val numToken = rest.substringBefore(' ').trim()
            val kb = numToken.toLongOrNull() ?: continue
            if (kb >= 0L) {
                valuesKb[key] = kb.coerceAtMost(Long.MAX_VALUE / 1024L)
            }
        }
        fun bytesOf(key: String): Long = (valuesKb[key] ?: 0L) * 1024L

        val total = bytesOf("MemTotal")
        val free = bytesOf("MemFree").coerceAtMost(total)
        val buffers = bytesOf("Buffers").coerceAtMost(total)
        val cached = bytesOf("Cached").coerceAtMost(total)
        val rawAvailable = valuesKb["MemAvailable"]?.let { it * 1024L }
            ?: (free + buffers + cached).coerceAtMost(total)
        val available = rawAvailable.coerceIn(0L, total)
        val dirty = bytesOf("Dirty").coerceAtMost(total)
        val swapTotal = bytesOf("SwapTotal")
        val swapFree = bytesOf("SwapFree").coerceIn(0L, swapTotal)

        return MemorySnapshot(
            totalBytes = total,
            availableBytes = available,
            freeBytes = free,
            cachedBytes = cached,
            buffersBytes = buffers,
            dirtyBytes = dirty,
            swapTotalBytes = swapTotal,
            swapFreeBytes = swapFree,
        )
    }

    /**
     * Parses GPU frequency, RC6 idle residency, power profile, and throttle flags from Linux DRM
     * (`xe` / `i915` `/sys/class/drm/card0/device/tile0/gt0/...`) or `/sys/class/devfreq`.
     */
    fun parseGpuTelemetry(
        attrs: Map<String, String>,
        driverName: String = "xe",
        deviceName: String = "Intel Xe2 Graphics",
        activeResidencyFraction: Float? = null,
    ): GpuTelemetrySnapshot {
        val rawMax = parseFreqToMhz(attrs["max_freq"])
            ?: parseFreqToMhz(attrs["rp0_freq"])
            ?: 2500
        val maxMhz = rawMax.coerceIn(100, 10_000)
        val minMhz = (parseFreqToMhz(attrs["rpn_freq"])
            ?: parseFreqToMhz(attrs["min_freq"])
            ?: 100).coerceIn(0, maxMhz)
        val effMhz = (parseFreqToMhz(attrs["rpe_freq"])
            ?: ((minMhz + maxMhz) / 2)).coerceIn(minMhz, maxMhz)
        val reqMhz = (parseFreqToMhz(attrs["cur_freq"]) ?: minMhz).coerceIn(0, maxMhz)
        val actMhz = (parseFreqToMhz(attrs["act_freq"]) ?: reqMhz).coerceIn(0, maxMhz)

        val profileRaw = attrs["power_profile"].orEmpty()
        val selectedProfile = PROFILE_BRACKET_REGEX.find(profileRaw)?.groupValues?.getOrNull(1)
            ?: profileRaw.trim().split(WHITESPACE_REGEX).lastOrNull()?.takeIf { it.isNotBlank() }
            ?: "power_saving"

        val reasons = ArrayList<String>()
        val reasonKeys = listOf(
            "reason_pl1" to "PL1",
            "reason_pl2" to "PL2",
            "reason_pl4" to "PL4",
            "reason_prochot" to "PROCHOT",
            "reason_ratl" to "RATL",
            "reason_thermal" to "THERMAL",
            "reason_vr_edc" to "VR_EDC",
            "reason_vr_thermalert" to "VR_THERMALERT",
        )
        for ((k, label) in reasonKeys) {
            if (attrs[k]?.trim() == "1") {
                reasons += label
            }
        }
        val statusThrottled = attrs["throttle_status"]?.trim() == "1" || reasons.isNotEmpty()
        val mediaMhz = parseFreqToMhz(attrs["media_freq"])?.coerceIn(0, 10_000)

        return GpuTelemetrySnapshot(
            driverName = sanitizeSensorName(driverName, "gpu"),
            deviceName = sanitizeSensorName(deviceName, "Integrated GPU"),
            actualFreqMhz = actMhz,
            requestedFreqMhz = reqMhz,
            minFreqMhz = minMhz,
            efficientFreqMhz = effMhz,
            maxFreqMhz = maxMhz,
            activeResidencyFraction = activeResidencyFraction?.coerceIn(0f, 1f),
            powerProfile = sanitizeSensorName(selectedProfile, "power_saving"),
            isThrottled = statusThrottled,
            throttleReasons = reasons,
            mediaEngineFreqMhz = mediaMhz,
        )
    }

    /**
     * Parses `/proc/cpuinfo` text to extract the human-readable processor marketing name
     * (e.g. `Intel Core Ultra 7 355` or `Snapdragon X Elite`).
     */
    fun parseCpuModelName(procCpuInfoText: String, fallbackArch: String = "x86_64"): String {
        for (line in procCpuInfoText.lineSequence().take(128)) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim().lowercase()
            if (key == "model name" || key == "hardware" || key == "processor") {
                val rawVal = line.substring(colon + 1).trim()
                if (rawVal.isEmpty() || rawVal.all { it.isDigit() }) continue
                val cleaned = rawVal
                    .replace("(R)", "", ignoreCase = true)
                    .replace("(TM)", "", ignoreCase = true)
                    .replace("CPU", "", ignoreCase = true)
                    .replace(WHITESPACE_REGEX, " ")
                    .trim()
                if (cleaned.length >= 3) {
                    return sanitizeSensorName(cleaned, fallbackArch)
                }
            }
        }
        return sanitizeSensorName(fallbackArch, "CPU")
    }

    /**
     * Parses `/proc/pressure/memory` PSI text into `(someAvg10, fullAvg10)` percentages (`0..100`).
     */
    fun parsePsiMemoryAvg10(psiText: String): Pair<Float, Float> {
        var someAvg10 = 0f
        var fullAvg10 = 0f
        for (line in psiText.lineSequence().take(16)) {
            val trimmed = line.trim()
            val match = PSI_AVG10_REGEX.find(trimmed) ?: continue
            val valPct = match.groupValues.getOrNull(1)?.toFloatOrNull()?.coerceIn(0f, 100f) ?: continue
            if (trimmed.startsWith("some ")) {
                someAvg10 = valPct
            } else if (trimmed.startsWith("full ")) {
                fullAvg10 = valPct
            }
        }
        return someAvg10 to fullAvg10
    }

    /**
     * Parses Qualcomm Adreno KGSL `gpubusy` (`"<busy_cycles> <total_cycles>"`) or `gpu_busy_percentage` (`"42 %"`).
     */
    fun parseQualcommKgslGpuBusy(rawBusy: String?): Float? {
        if (rawBusy.isNullOrBlank()) return null
        val tokens = rawBusy.trim().replace("%", "").split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
        if (tokens.size >= 2) {
            val busy = tokens[0].toDoubleOrNull()
            val total = tokens[1].toDoubleOrNull()
            if (busy != null && total != null && total > 0.0 && busy >= 0.0) {
                return (busy / total).toFloat().coerceIn(0f, 1f)
            }
        } else if (tokens.size == 1) {
            val pct = tokens[0].toFloatOrNull()
            if (pct != null && pct.isFinite()) {
                return (pct / 100f).coerceIn(0f, 1f)
            }
        }
        return null
    }

    /**
     * Parses NPU telemetry from `/sys/class/accel/accel0/device` attributes and/or `DRM_IOCTL_IVPU_GET_PARAM`
     * parameters on `/dev/accel0`.
     */
    fun parseNpuTelemetry(
        attrs: Map<String, String>,
        drmVersion: DrmVersionInfo? = null,
        ivpuParams: IvpuParamInfo? = null,
        busyDeltaFraction: Float? = null,
        heartbeatActive: Boolean = false,
    ): NpuTelemetrySnapshot {
        val driver = sanitizeSensorName(drmVersion?.driverName ?: attrs["driver"] ?: "intel_vpu", "npu")
        val baseDesc = when {
            ivpuParams != null && ivpuParams.deviceId > 0 ->
                "Intel NPU (${ivpuParams.deviceIdHex} · Rev ${ivpuParams.revision})"
            drmVersion != null && drmVersion.description.isNotBlank() ->
                drmVersion.description
            else -> "Neural Processing Unit"
        }
        val maxMhz = (parseFreqToMhz(attrs["npu_max_frequency_mhz"])
            ?: parseFreqToMhz(attrs["hw_max_freq"])
            ?: ivpuParams?.maxClockMhz?.takeIf { it > 0 }
            ?: 2000).coerceIn(100, 10_000)

        val sysfsCurMhz = parseFreqToMhz(attrs["npu_current_frequency_mhz"])
            ?: parseFreqToMhz(attrs["current_freq"])

        val activeCtx = ivpuParams?.activeContexts?.coerceAtLeast(0) ?: 0
        val contextLoad = ((activeCtx.toFloat() * 0.24f) + if (heartbeatActive) 0.20f else 0f).coerceIn(0f, 1f)

        val activeFrac = when {
            busyDeltaFraction != null -> busyDeltaFraction.coerceIn(0f, 1f)
            contextLoad > 0f -> contextLoad
            heartbeatActive -> 0.55f
            sysfsCurMhz != null && sysfsCurMhz > 0 -> (sysfsCurMhz.toFloat() / maxMhz.toFloat()).coerceIn(0f, 1f)
            else -> 0f
        }

        val curMhz = when {
            sysfsCurMhz != null -> sysfsCurMhz.coerceIn(0, maxMhz)
            activeFrac > 0.01f -> (600 + ((maxMhz - 600) * activeFrac).roundToInt()).coerceIn(200, maxMhz)
            else -> 0
        }

        val memBytes = attrs["npu_memory_utilization"]?.trim()?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        val fwLabel = ivpuParams?.firmwareVersionLabel ?: drmVersion?.version?.let { "v$it" } ?: "v1.0"
        val contexts = ivpuParams?.maxContexts?.takeIf { it > 0 } ?: 64
        val heartbeat = ivpuParams?.engineHeartbeat ?: 0L

        return NpuTelemetrySnapshot(
            driverName = driver,
            deviceName = sanitizeSensorName(baseDesc, "Intel NPU"),
            currentFreqMhz = curMhz,
            maxFreqMhz = maxMhz,
            activeUtilizationFraction = activeFrac,
            engineHeartbeat = heartbeat,
            activeContexts = activeCtx,
            maxContexts = contexts,
            firmwareVersion = sanitizeSensorName(fwLabel, "v1.0"),
            memoryUsedBytes = memBytes,
        )
    }

    private fun parseFreqToMhz(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val v = raw.trim().toLongOrNull() ?: return null
        if (v < 0L) return null
        return when {
            v >= 100_000_000L -> (v / 1_000_000L).toInt() // Hz -> MHz
            v >= 100_000L -> (v / 1_000L).toInt() // kHz -> MHz
            else -> v.toInt() // Already MHz
        }
    }

    private val WHITESPACE_REGEX = Regex("\\s+")
    private val PROFILE_BRACKET_REGEX = Regex("\\[([^\\]]+)]")
    private val PSI_AVG10_REGEX = Regex("avg10=([0-9]+(?:\\.[0-9]+)?)")
}
