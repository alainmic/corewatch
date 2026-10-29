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
) {
    val curMhz: Int get() = (curKhz / 1_000L).toInt().coerceAtLeast(0)
    val maxMhz: Int get() = (maxKhz / 1_000L).toInt().coerceAtLeast(0)
    val minMhz: Int get() = (minKhz / 1_000L).toInt().coerceAtLeast(0)

    val utilizationFraction: Float
        get() {
            if (maxKhz <= 0L) return 0f
            return (curKhz.toFloat() / maxKhz.toFloat()).coerceIn(0f, 1f)
        }

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
) {
    val usedBytes: Long get() = (totalBytes - availableBytes).coerceAtLeast(0L)
    val usedFraction: Float
        get() = if (totalBytes <= 0L) 0f else (usedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)

    val swapUsedBytes: Long get() = (swapTotalBytes - swapFreeBytes).coerceAtLeast(0L)
    val swapUsedFraction: Float
        get() = if (swapTotalBytes <= 0L) 0f else (swapUsedBytes.toDouble() / swapTotalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
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

    private val WHITESPACE_REGEX = Regex("\\s+")
}
