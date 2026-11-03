// Copyright 2026 The Corewatch Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.corewatch.data

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Single refresh-rate bucket in the rolling 5-minute VRR time-in-state residency window.
 */
data class VrrResidencyBucket(
    val hz: Int,
    val durationMs: Long,
    val fraction: Float,
) {
    val seconds: Float
        get() = durationMs.coerceAtLeast(0L) / 1_000f

    val summaryLabel: String
        get() = String.format(Locale.US, "%.1f%% (%.1fs)", (fraction * 100f).coerceIn(0f, 100f), seconds)
}

/**
 * Rolling 5-minute VRR-aware frame pacing & jank snapshot collected system-wide from
 * `dumpsys graphicsstats` (when `android.permission.DUMP` is granted) or per-window from
 * `Window.OnFrameMetricsAvailableListener` (`FrameMetrics.DEADLINE`) as an unprivileged fallback.
 */
data class FrameJankSnapshot(
    val evaluatedFrames5m: Int = 0,
    val droppedFrames5m: Int = 0,
    val jankyFrames5m: Int = 0,
    val longCpuJank5m: Int = 0,
    val longGpuJank5m: Int = 0,
    val bufferStuffing5m: Int = 0,
    val sfMissedFrames5m: Int = 0,
    val windowDurationMs: Long = 0L,
    val activeDeadlineHzMin: Int = 60,
    val activeDeadlineHzMax: Int = 120,
    val latestIntervalJankPercent: Float = 0f,
    val isSystemWideTimeStats: Boolean = false,
    val isSystemWideGraphicsStats: Boolean = false,
    val hasDumpPermission: Boolean = false,
    val activeAppCount5m: Int = 0,
    val droppedReports5m: Int = 0,
) {
    val isSystemWide: Boolean
        get() = isSystemWideGraphicsStats || isSystemWideTimeStats

    /**
     * Total expected frame presentation slots in the 5-minute window:
     * rendered frames (`evaluatedFrames5m`) plus display VSync frame slots skipped while stalling (`droppedFrames5m`).
     */
    val expectedFrames5m: Int
        get() = (evaluatedFrames5m.coerceAtLeast(0).toLong() + droppedFrames5m.coerceAtLeast(0).toLong())
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

    /**
     * Percentage of expected VSync frame slots that were dropped (`dropped / (rendered + dropped)`).
     * For example, sustaining 30 FPS on a 60 Hz cadence yields 30 rendered + 30 dropped = 50.00% dropped frames.
     */
    val droppedPercent5m: Float
        get() = if (evaluatedFrames5m <= 0 || droppedFrames5m <= 0) {
            0f
        } else {
            val denom = evaluatedFrames5m.toDouble() + droppedFrames5m.toDouble()
            ((droppedFrames5m.toDouble() * 100.0) / denom).toFloat().coerceIn(0f, 100f)
        }

    /**
     * Percentage of rendered frames whose `FrameMetrics.TOTAL_DURATION` exceeded `FrameMetrics.DEADLINE`
     * (`janky / rendered`).
     */
    val jankyPercent5m: Float
        get() = if (evaluatedFrames5m <= 0 || jankyFrames5m <= 0) {
            0f
        } else {
            ((jankyFrames5m.coerceAtMost(evaluatedFrames5m).toDouble() * 100.0) / evaluatedFrames5m.toDouble())
                .toFloat()
                .coerceIn(0f, 100f)
        }

    val windowElapsedLabel: String
        get() {
            val totalSec = (windowDurationMs.coerceIn(0L, 300_000L) / 1_000L).toInt()
            val mm = totalSec / 60
            val ss = totalSec % 60
            return String.format(Locale.US, "%02d:%02d / 05:00", mm, ss)
        }

    val windowSummaryLabel: String
        get() = if (isSystemWideGraphicsStats && activeAppCount5m > 0) {
            val appWord = if (activeAppCount5m == 1) "app" else "apps"
            "Window: $windowElapsedLabel · $evaluatedFrames5m evaluated frames ($activeAppCount5m active $appWord)"
        } else {
            "Window: $windowElapsedLabel · $evaluatedFrames5m evaluated frames"
        }

    val deadlineRangeLabel: String
        get() = if (activeDeadlineHzMin == activeDeadlineHzMax) {
            "[$activeDeadlineHzMax] Hz"
        } else {
            "[$activeDeadlineHzMin, $activeDeadlineHzMax] Hz"
        }

    val scopeBadgeLabel: String
        get() = when {
            isSystemWideGraphicsStats -> "SYSTEM-WIDE (GRAPHICSSTATS)"
            isSystemWideTimeStats -> "SYSTEM-WIDE (SF TIMESTATS)"
            else -> "APP WINDOW (FRAMEMETRICS)"
        }
}

/**
 * Unique stream key for a monotonic `GraphicsStatsService` ashmem/disk stats block.
 * On multi-user (HSUM) Android Desktop, the same package (e.g. `com.android.systemui`) can run
 * under multiple UIDs (`10204` vs `1010204`) and have historical + current `Stats since:` buckets.
 */
data class GraphicsStatsKey(
    val uid: Int,
    val packageName: String,
    val version: Long,
    val statsSinceNs: Long,
)

/**
 * Parsed cumulative HWUI `GraphicsStatsService` counters and sparse non-zero duration histograms
 * for a single `(UID, Package, Version, Stats since)` block in `dumpsys graphicsstats`.
 */
data class PackageGraphicsStatsRecord(
    val key: GraphicsStatsKey,
    val statsEndNs: Long = 0L,
    val totalFrames: Long = 0L,
    val jankyFrames: Long = 0L,
    val missedVsync: Long = 0L,
    val highInputLatency: Long = 0L,
    val slowUiThread: Long = 0L,
    val slowBitmapUploads: Long = 0L,
    val slowIssueDrawCommands: Long = 0L,
    val frameDeadlineMissed: Long = 0L,
    val cpuHistogramMsCounts: Map<Int, Long> = emptyMap(),
    val gpuHistogramMsCounts: Map<Int, Long> = emptyMap(),
)

/**
 * Pure, zero-regex parser for `dumpsys graphicsstats` text output.
 */
object GraphicsStatsParsers {

    fun parseDumpsysGraphicsStats(rawText: String): List<PackageGraphicsStatsRecord> {
        if (rawText.isBlank()) return emptyList()
        if (rawText.contains("Permission Denial:", ignoreCase = true) ||
            rawText.contains("SecurityException", ignoreCase = true)
        ) {
            return emptyList()
        }

        val mergedByKey = LinkedHashMap<GraphicsStatsKey, PackageGraphicsStatsRecord>()

        var hasUid = false
        var hasPkg = false
        var hasTotal = false
        var uid = 0
        var pkg = ""
        var version = 0L
        var statsSinceNs = 0L
        var statsEndNs = 0L
        var totalFrames = 0L
        var jankyFrames = 0L
        var missedVsync = 0L
        var highInputLatency = 0L
        var slowUiThread = 0L
        var slowBitmapUploads = 0L
        var slowIssueDrawCommands = 0L
        var frameDeadlineMissed = 0L
        var cpuHist: Map<Int, Long> = emptyMap()
        var gpuHist: Map<Int, Long> = emptyMap()

        fun flushCurrent() {
            if (hasPkg && hasTotal && pkg.isNotEmpty()) {
                val safeTotal = totalFrames.coerceAtLeast(0L)
                val safeJanky = jankyFrames.coerceIn(0L, safeTotal)
                val key = GraphicsStatsKey(
                    uid = uid,
                    packageName = pkg,
                    version = version,
                    statsSinceNs = statsSinceNs,
                )
                val rec = PackageGraphicsStatsRecord(
                    key = key,
                    statsEndNs = statsEndNs.coerceAtLeast(0L),
                    totalFrames = safeTotal,
                    jankyFrames = safeJanky,
                    missedVsync = missedVsync.coerceAtLeast(0L),
                    highInputLatency = highInputLatency.coerceAtLeast(0L),
                    slowUiThread = slowUiThread.coerceAtLeast(0L),
                    slowBitmapUploads = slowBitmapUploads.coerceAtLeast(0L),
                    slowIssueDrawCommands = slowIssueDrawCommands.coerceAtLeast(0L),
                    frameDeadlineMissed = frameDeadlineMissed.coerceAtLeast(0L),
                    cpuHistogramMsCounts = cpuHist,
                    gpuHistogramMsCounts = gpuHist,
                )
                val existing = mergedByKey[key]
                mergedByKey[key] = if (existing == null) rec else mergeRecords(existing, rec)
            }
            hasUid = false
            hasPkg = false
            hasTotal = false
            uid = 0
            pkg = ""
            version = 0L
            statsSinceNs = 0L
            statsEndNs = 0L
            totalFrames = 0L
            jankyFrames = 0L
            missedVsync = 0L
            highInputLatency = 0L
            slowUiThread = 0L
            slowBitmapUploads = 0L
            slowIssueDrawCommands = 0L
            frameDeadlineMissed = 0L
            cpuHist = emptyMap()
            gpuHist = emptyMap()
        }

        for (rawLine in rawText.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) {
                if (hasPkg) flushCurrent()
                continue
            }
            when {
                line.startsWith("UID:") -> {
                    if (hasUid || hasPkg) flushCurrent()
                    uid = line.substring(4).trim().toIntOrNull() ?: 0
                    hasUid = true
                }
                line.startsWith("Package:") -> {
                    if (hasPkg) flushCurrent()
                    pkg = SysfsParsers.sanitizeSensorName(line.substring(8).trim(), "")
                    hasPkg = pkg.isNotEmpty()
                }
                line.startsWith("Version:") -> {
                    version = parseLeadingLong(line, 8)
                }
                line.startsWith("Stats since:") -> {
                    statsSinceNs = parseLeadingLong(line, 12)
                }
                line.startsWith("Stats end:") -> {
                    statsEndNs = parseLeadingLong(line, 10)
                }
                line.startsWith("Total frames rendered:") -> {
                    totalFrames = parseLeadingLong(line, 22)
                    hasTotal = true
                }
                line.startsWith("Janky frames:") -> {
                    jankyFrames = parseLeadingLong(line, 13)
                }
                line.startsWith("Number Missed Vsync:") -> {
                    missedVsync = parseLeadingLong(line, 20)
                }
                line.startsWith("Number High input latency:") -> {
                    highInputLatency = parseLeadingLong(line, 26)
                }
                line.startsWith("Number Slow UI thread:") -> {
                    slowUiThread = parseLeadingLong(line, 22)
                }
                line.startsWith("Number Slow bitmap uploads:") -> {
                    slowBitmapUploads = parseLeadingLong(line, 27)
                }
                line.startsWith("Number Slow issue draw commands:") -> {
                    slowIssueDrawCommands = parseLeadingLong(line, 32)
                }
                line.startsWith("Number Frame deadline missed:") -> {
                    frameDeadlineMissed = parseLeadingLong(line, 29)
                }
                line.startsWith("HISTOGRAM:") -> {
                    cpuHist = parseHistogramNonZero(line, 10)
                }
                line.startsWith("GPU HISTOGRAM:") -> {
                    gpuHist = parseHistogramNonZero(line, 14)
                }
            }
        }
        if (hasPkg) flushCurrent()
        return mergedByKey.values.toList()
    }

    internal fun mergeRecords(
        a: PackageGraphicsStatsRecord,
        b: PackageGraphicsStatsRecord,
    ): PackageGraphicsStatsRecord {
        val safeTotal = saturatingAdd(a.totalFrames, b.totalFrames)
        return PackageGraphicsStatsRecord(
            key = a.key,
            statsEndNs = maxOf(a.statsEndNs, b.statsEndNs),
            totalFrames = safeTotal,
            jankyFrames = saturatingAdd(a.jankyFrames, b.jankyFrames).coerceAtMost(safeTotal),
            missedVsync = saturatingAdd(a.missedVsync, b.missedVsync),
            highInputLatency = saturatingAdd(a.highInputLatency, b.highInputLatency),
            slowUiThread = saturatingAdd(a.slowUiThread, b.slowUiThread),
            slowBitmapUploads = saturatingAdd(a.slowBitmapUploads, b.slowBitmapUploads),
            slowIssueDrawCommands = saturatingAdd(a.slowIssueDrawCommands, b.slowIssueDrawCommands),
            frameDeadlineMissed = saturatingAdd(a.frameDeadlineMissed, b.frameDeadlineMissed),
            cpuHistogramMsCounts = mergeHistograms(a.cpuHistogramMsCounts, b.cpuHistogramMsCounts),
            gpuHistogramMsCounts = mergeHistograms(a.gpuHistogramMsCounts, b.gpuHistogramMsCounts),
        )
    }

    private fun mergeHistograms(a: Map<Int, Long>, b: Map<Int, Long>): Map<Int, Long> {
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        val out = HashMap<Int, Long>(a.size + b.size)
        out.putAll(a)
        for ((k, v) in b) {
            out[k] = saturatingAdd(out[k] ?: 0L, v)
        }
        return out
    }

    internal fun saturatingAdd(x: Long, y: Long): Long {
        if (x <= 0L) return y.coerceAtLeast(0L)
        if (y <= 0L) return x
        return if (x > Long.MAX_VALUE - y) Long.MAX_VALUE else x + y
    }

    private fun parseLeadingLong(line: String, startIdx: Int): Long {
        var i = startIdx
        val len = line.length
        while (i < len && line[i] <= ' ') i++
        if (i >= len || line[i] !in '0'..'9') return 0L
        var acc = 0L
        while (i < len && line[i] in '0'..'9') {
            val d = (line[i] - '0').toLong()
            acc = if (acc > (Long.MAX_VALUE - d) / 10L) Long.MAX_VALUE else acc * 10L + d
            i++
        }
        return acc
    }

    internal fun parseHistogramNonZero(line: String, startIdx: Int): Map<Int, Long> {
        var i = startIdx
        val len = line.length
        var map: HashMap<Int, Long>? = null
        while (i < len) {
            while (i < len && line[i] <= ' ') i++
            if (i >= len) break
            var ms = 0
            var hasMs = false
            while (i < len && line[i] in '0'..'9') {
                ms = (ms * 10 + (line[i] - '0')).coerceAtMost(60_000)
                hasMs = true
                i++
            }
            if (!hasMs || i + 2 >= len || line[i] != 'm' || line[i + 1] != 's' || line[i + 2] != '=') {
                while (i < len && line[i] > ' ') i++
                continue
            }
            i += 3
            var count = 0L
            var hasCount = false
            while (i < len && line[i] in '0'..'9') {
                val d = (line[i] - '0').toLong()
                count = if (count > (Long.MAX_VALUE - d) / 10L) Long.MAX_VALUE else count * 10L + d
                hasCount = true
                i++
            }
            while (i < len && line[i] > ' ') i++
            if (hasCount && count > 0L && ms > 0) {
                val m = map ?: HashMap<Int, Long>(16).also { map = it }
                m[ms] = saturatingAdd(m[ms] ?: 0L, count)
            }
        }
        return map ?: emptyMap()
    }
}

/**
 * Single process kill record captured from `ApplicationExitInfo.REASON_LOW_MEMORY` or LMKD event logs.
 */
data class LmkdKillEvent(
    val timestampMs: Long,
    val processName: String,
    val pid: Int,
    val pssKb: Long,
    val reason: String,
) {
    val formattedPssMb: String
        get() = if (pssKb > 0L) String.format(Locale.US, "%.1f MiB", pssKb / 1024.0) else "N/A"
}

/**
 * Rolling 5-minute LMKD (Low Memory Killer Daemon) & Linux PSI memory pressure telemetry.
 */
data class LmkdTelemetrySnapshot(
    val killCount5m: Int = 0,
    val recentKills5m: List<LmkdKillEvent> = emptyList(),
    val trimLevelLabel: String = "Normal",
    val psiMemorySomeAvg10: Float = 0f,
    val psiMemoryFullAvg10: Float = 0f,
    val vmstatOomKills: Long = 0L,
    val isSystemWide: Boolean = false,
) {
    val scopeBadgeLabel: String
        get() = if (isSystemWide) "SYSTEM-WIDE LMKD" else "AMS EXIT-INFO + PSI"
}

/**
 * Thread-safe rolling 5-minute (`300_000 ms`) window tracker for:
 * 1. VRR refresh-rate time-in-state residency buckets and refresh-rate switch counts.
 * 2. VRR-aware per-frame deadline evaluation (`FrameMetrics.TOTAL_DURATION` vs `FrameMetrics.DEADLINE`).
 */
class VrrAndFrameWindowTracker(
    private val windowHorizonMs: Long = 300_000L,
) {
    private data class VrrSlice(
        val timestampMs: Long,
        val hz: Int,
        val durationMs: Long,
        val switched: Boolean,
    )

    private data class FrameBucket(
        val timestampMs: Long,
        var firstFrameMs: Long = timestampMs,
        var evaluated: Int = 0,
        var dropped: Int = 0,
        var janky: Int = 0,
        var longCpu: Int = 0,
        var longGpu: Int = 0,
        var bufferStuffing: Int = 0,
        var missedVsync: Int = 0,
        var droppedReports: Int = 0,
        var minDeadlineHz: Int = Int.MAX_VALUE,
        var maxDeadlineHz: Int = 0,
    )

    private data class SysFrameBucket(
        val timestampMs: Long,
        var firstFrameMs: Long = timestampMs,
        var evaluated: Long = 0L,
        var dropped: Long = 0L,
        var janky: Long = 0L,
        var longCpu: Long = 0L,
        var longGpu: Long = 0L,
        var bufferStuffing: Long = 0L,
        var missedVsync: Long = 0L,
        var minDeadlineHz: Int = Int.MAX_VALUE,
        var maxDeadlineHz: Int = 0,
        val activePackages: MutableSet<String> = HashSet(),
    )

    private val lock = Any()
    private val vrrSlices = ArrayDeque<VrrSlice>()
    private var lastSampleElapsedMs: Long = -1L
    private var lastObservedHz: Int = -1

    private val frameBuckets = ArrayDeque<FrameBucket>()
    private var lastFrameElapsedMs: Long = -1L

    private val sysFrameBuckets = ArrayDeque<SysFrameBucket>()
    private val prevGraphicsStatsByKey = HashMap<GraphicsStatsKey, PackageGraphicsStatsRecord>()
    private var graphicsStatsHasBaseline: Boolean = false
    private var graphicsStatsBaselineElapsedMs: Long = -1L
    private var graphicsStatsMaxSinceNs: Long = 0L
    private var graphicsStatsLastDumpWallNs: Long = 0L
    private var lastSysFrameElapsedMs: Long = -1L

    fun reset() {
        synchronized(lock) {
            vrrSlices.clear()
            lastSampleElapsedMs = -1L
            lastObservedHz = -1
            frameBuckets.clear()
            lastFrameElapsedMs = -1L
            clearGraphicsStatsLocked()
        }
    }

    fun clearGraphicsStatsState() {
        synchronized(lock) {
            clearGraphicsStatsLocked()
        }
    }

    private fun clearGraphicsStatsLocked() {
        sysFrameBuckets.clear()
        prevGraphicsStatsByKey.clear()
        graphicsStatsHasBaseline = false
        graphicsStatsBaselineElapsedMs = -1L
        graphicsStatsMaxSinceNs = 0L
        graphicsStatsLastDumpWallNs = 0L
        lastSysFrameElapsedMs = -1L
    }

    /**
     * Records a display refresh rate sample at `nowElapsedMs` and returns the rolling 5-minute
     * time-in-state residency buckets (sorted descending by Hz) and the 5-minute VRR switch count.
     */
    fun recordDisplayRefreshSample(
        nowElapsedMs: Long,
        effectiveHz: Float,
        supportedStepsHz: List<Float>,
    ): Pair<List<VrrResidencyBucket>, Int> {
        val snappedHz = snapToSupportedStep(effectiveHz, supportedStepsHz)
        synchronized(lock) {
            val dtMs = if (lastSampleElapsedMs > 0L && nowElapsedMs > lastSampleElapsedMs) {
                (nowElapsedMs - lastSampleElapsedMs).coerceAtMost(10_000L)
            } else {
                250L
            }
            val switched = lastObservedHz > 0 && lastObservedHz != snappedHz
            vrrSlices.addLast(
                VrrSlice(
                    timestampMs = nowElapsedMs,
                    hz = snappedHz,
                    durationMs = dtMs,
                    switched = switched,
                ),
            )
            lastSampleElapsedMs = nowElapsedMs
            lastObservedHz = snappedHz
            pruneVrrLocked(nowElapsedMs)
            return summarizeVrrLocked()
        }
    }

    /**
     * Records a single rendered frame's timing in nanoseconds from `Window.OnFrameMetricsAvailableListener`.
     */
    fun recordFrameMetric(
        nowElapsedMs: Long,
        totalDurationNs: Long,
        deadlineNs: Long,
        gpuDurationNs: Long,
        intendedVsyncNs: Long,
        vsyncNs: Long,
        fallbackHz: Float = 120f,
        cpuStagesDurationNs: Long = -1L,
        swapBuffersDurationNs: Long = -1L,
        isFirstDrawFrame: Boolean = false,
        droppedReportCount: Int = 0,
    ) {
        val safeDroppedReports = droppedReportCount.coerceAtLeast(0)
        if (isFirstDrawFrame || totalDurationNs <= 0L) {
            if (safeDroppedReports > 0) {
                val safeNowMs = nowElapsedMs.coerceAtLeast(0L)
                val bucketKeyMs = (safeNowMs / 1_000L) * 1_000L
                synchronized(lock) {
                    val bucket = getOrCreateBucketLocked(bucketKeyMs, safeNowMs)
                    bucket.droppedReports = (bucket.droppedReports.toLong() + safeDroppedReports.toLong())
                        .coerceAtMost(Int.MAX_VALUE.toLong())
                        .toInt()
                    pruneFramesLocked(safeNowMs)
                }
            }
            return
        }
        val safeNowMs = nowElapsedMs.coerceAtLeast(0L)
        val hz = if (fallbackHz.isFinite() && fallbackHz >= 10f) fallbackHz.coerceAtMost(480f) else 120f
        val fallbackDeadlineNs = (1_000_000_000.0 / hz.toDouble()).toLong().coerceAtLeast(1_000_000L)
        // Per Android FrameMetrics.DEADLINE (API 31+), DEADLINE already encodes the exact per-frame
        // compositor budget allocated by SurfaceFlinger's FrameTimeline for the window's active display
        // and VRR cadence. Only fall back to Display refresh rate when DEADLINE is unavailable/invalid.
        val evalDeadlineNs = if (deadlineNs in 1_000_000L..250_000_000L) {
            deadlineNs
        } else {
            fallbackDeadlineNs
        }
        val deadlineHz = (1_000_000_000.0 / evalDeadlineNs.toDouble()).roundToInt().coerceIn(4, 480)
        val vsyncSlipNs = if (intendedVsyncNs > 0L && vsyncNs > intendedVsyncNs) {
            vsyncNs - intendedVsyncNs
        } else {
            0L
        }
        val safeGpuNs = gpuDurationNs.coerceAtLeast(0L)
        val safeSwapNs = swapBuffersDurationNs.coerceAtLeast(0L)
        val cpuNs = if (cpuStagesDurationNs >= 0L) {
            maxOf(cpuStagesDurationNs, vsyncSlipNs)
        } else {
            maxOf((totalDurationNs - safeGpuNs - safeSwapNs).coerceAtLeast(0L), vsyncSlipNs)
        }

        val isJanky = totalDurationNs > evalDeadlineNs
        val overrunNs = (totalDurationNs - evalDeadlineNs).coerceAtLeast(0L)
        val maxDroppedPerFrame = (5_000_000_000L / evalDeadlineNs).coerceIn(1L, 600L)
        val overrunDroppedSlots = if (overrunNs > 0L) {
            (((overrunNs - 1L) / evalDeadlineNs) + 1L).coerceIn(1L, maxDroppedPerFrame).toInt()
        } else {
            0
        }
        val vsyncSlipSlots = if (isJanky && vsyncSlipNs >= evalDeadlineNs) {
            (vsyncSlipNs / evalDeadlineNs).coerceIn(1L, maxDroppedPerFrame).toInt()
        } else {
            0
        }
        val droppedSlots = maxOf(overrunDroppedSlots, vsyncSlipSlots)

        val missedVsyncStart = isJanky && vsyncSlipNs >= minOf(evalDeadlineNs / 2L, 4_000_000L)
        val unattributedStallNs = if (cpuStagesDurationNs >= 0L) {
            (totalDurationNs - cpuNs - safeGpuNs).coerceAtLeast(0L)
        } else {
            safeSwapNs
        }
        val isStuffing = isJanky && (
            safeSwapNs > (evalDeadlineNs / 3L) ||
                (safeSwapNs > 0L && safeSwapNs >= cpuNs && safeSwapNs >= safeGpuNs) ||
                unattributedStallNs > (evalDeadlineNs / 2L)
            )
        val isLongGpu = isJanky && (safeGpuNs > (evalDeadlineNs / 2L) || (!isStuffing && safeGpuNs > cpuNs))
        val isLongCpu = isJanky && (cpuNs > (evalDeadlineNs / 2L) || (!isStuffing && cpuNs >= safeGpuNs))

        val bucketKeyMs = (safeNowMs / 1_000L) * 1_000L
        synchronized(lock) {
            val bucket = getOrCreateBucketLocked(bucketKeyMs, safeNowMs)
            if (safeNowMs < bucket.firstFrameMs) {
                bucket.firstFrameMs = safeNowMs
            }
            bucket.evaluated += 1
            bucket.dropped += droppedSlots
            if (isJanky) bucket.janky += 1
            if (isLongCpu) bucket.longCpu += 1
            if (isLongGpu) bucket.longGpu += 1
            if (isStuffing) bucket.bufferStuffing += 1
            if (missedVsyncStart) bucket.missedVsync += 1
            if (safeDroppedReports > 0) {
                bucket.droppedReports = (bucket.droppedReports.toLong() + safeDroppedReports.toLong())
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
            }
            if (deadlineHz < bucket.minDeadlineHz) bucket.minDeadlineHz = deadlineHz
            if (deadlineHz > bucket.maxDeadlineHz) bucket.maxDeadlineHz = deadlineHz
            if (safeNowMs > lastFrameElapsedMs) {
                lastFrameElapsedMs = safeNowMs
            }
            pruneFramesLocked(safeNowMs)
        }
    }

    private fun getOrCreateBucketLocked(bucketKeyMs: Long, nowElapsedMs: Long): FrameBucket {
        val existing = frameBuckets.lastOrNull { it.timestampMs == bucketKeyMs }
        if (existing != null) return existing
        val created = FrameBucket(timestampMs = bucketKeyMs, firstFrameMs = nowElapsedMs)
        if (frameBuckets.isEmpty() || bucketKeyMs >= frameBuckets.last().timestampMs) {
            frameBuckets.addLast(created)
        } else {
            val insertIdx = frameBuckets.indexOfFirst { it.timestampMs > bucketKeyMs }
            if (insertIdx < 0) frameBuckets.addLast(created) else frameBuckets.add(insertIdx, created)
        }
        return created
    }

    private fun getOrCreateSysBucketLocked(bucketKeyMs: Long, nowElapsedMs: Long): SysFrameBucket {
        val existing = sysFrameBuckets.lastOrNull { it.timestampMs == bucketKeyMs }
        if (existing != null) return existing
        val created = SysFrameBucket(timestampMs = bucketKeyMs, firstFrameMs = nowElapsedMs)
        if (sysFrameBuckets.isEmpty() || bucketKeyMs >= sysFrameBuckets.last().timestampMs) {
            sysFrameBuckets.addLast(created)
        } else {
            val insertIdx = sysFrameBuckets.indexOfFirst { it.timestampMs > bucketKeyMs }
            if (insertIdx < 0) sysFrameBuckets.addLast(created) else sysFrameBuckets.add(insertIdx, created)
        }
        return created
    }

    /**
     * Records a system-wide `dumpsys graphicsstats` snapshot across all UIDs and packages.
     *
     * The first non-empty call after launch or [reset] establishes the `t=0` baseline without
     * leaking historical pre-session frames into the rolling 5-minute window. Subsequent calls
     * accumulate positive per-stream deltas and histogram-derived missed VSync slots.
     */
    fun recordGraphicsStatsSample(
        nowElapsedMs: Long,
        records: List<PackageGraphicsStatsRecord>,
        effectiveHz: Float = 120f,
    ) {
        if (records.isEmpty()) return
        val safeNowMs = nowElapsedMs.coerceAtLeast(0L)
        val cleanHz = if (effectiveHz.isFinite() && effectiveHz >= 10f) {
            effectiveHz.coerceAtMost(480f)
        } else {
            120f
        }
        val deadlineHz = cleanHz.roundToInt().coerceIn(10, 480)
        val displayPeriodMs = 1000.0 / cleanHz.toDouble()

        val merged = LinkedHashMap<GraphicsStatsKey, PackageGraphicsStatsRecord>(records.size)
        for (r in records) {
            if (r.key.packageName.isBlank()) continue
            val prevInDump = merged[r.key]
            merged[r.key] = if (prevInDump == null) r else GraphicsStatsParsers.mergeRecords(prevInDump, r)
        }
        if (merged.isEmpty()) return

        synchronized(lock) {
            if (!graphicsStatsHasBaseline) {
                prevGraphicsStatsByKey.clear()
                var maxSince = 0L
                var maxWall = 0L
                for ((k, rec) in merged) {
                    prevGraphicsStatsByKey[k] = rec
                    if (k.statsSinceNs > maxSince) maxSince = k.statsSinceNs
                    val recWall = maxOf(k.statsSinceNs, rec.statsEndNs)
                    if (recWall > maxWall) maxWall = recWall
                }
                graphicsStatsMaxSinceNs = maxSince
                graphicsStatsLastDumpWallNs = maxWall
                graphicsStatsBaselineElapsedMs = safeNowMs
                graphicsStatsHasBaseline = true
                pruneSysFramesLocked(safeNowMs)
                return
            }

            if (safeNowMs < graphicsStatsBaselineElapsedMs) {
                graphicsStatsBaselineElapsedMs = safeNowMs
            }

            val bucketKeyMs = (safeNowMs / 1_000L) * 1_000L
            val minLiveNewSinceNs = if (graphicsStatsLastDumpWallNs > 0L) {
                graphicsStatsLastDumpWallNs
            } else {
                graphicsStatsMaxSinceNs
            }
            var nextDumpMaxWallNs = graphicsStatsLastDumpWallNs
            var sliceEval = 0L
            var sliceDropped = 0L
            var sliceJanky = 0L
            var sliceLongCpu = 0L
            var sliceLongGpu = 0L
            var sliceStuffing = 0L
            var sliceMissedVsync = 0L
            var activePkgs: HashSet<String>? = null

            for ((key, cur) in merged) {
                val curWall = maxOf(key.statsSinceNs, cur.statsEndNs)
                if (curWall > nextDumpMaxWallNs) nextDumpMaxWallNs = curWall
                val existingPrev = prevGraphicsStatsByKey[key]
                val effectivePrev: PackageGraphicsStatsRecord = if (existingPrev != null) {
                    existingPrev
                } else {
                    val isLiveNewStream = cur.totalFrames in 1L..MAX_NEW_STREAM_INITIAL_FRAMES &&
                        (minLiveNewSinceNs == 0L || key.statsSinceNs >= minLiveNewSinceNs)
                    if (isLiveNewStream) {
                        PackageGraphicsStatsRecord(key = key)
                    } else {
                        prevGraphicsStatsByKey[key] = cur
                        if (key.statsSinceNs > graphicsStatsMaxSinceNs) {
                            graphicsStatsMaxSinceNs = key.statsSinceNs
                        }
                        continue
                    }
                }

                prevGraphicsStatsByKey[key] = cur
                if (key.statsSinceNs > graphicsStatsMaxSinceNs) {
                    graphicsStatsMaxSinceNs = key.statsSinceNs
                }

                val dEval = cur.totalFrames - effectivePrev.totalFrames
                if (dEval <= 0L) continue

                val dJanky = (cur.jankyFrames - effectivePrev.jankyFrames).coerceIn(0L, dEval)
                val dMissedVsync = (cur.missedVsync - effectivePrev.missedVsync).coerceIn(0L, dJanky)
                val dSlowUi = (cur.slowUiThread - effectivePrev.slowUiThread).coerceAtLeast(0L)
                val dSlowBitmap = (cur.slowBitmapUploads - effectivePrev.slowBitmapUploads).coerceAtLeast(0L)
                val dSlowDraw = (cur.slowIssueDrawCommands - effectivePrev.slowIssueDrawCommands).coerceAtLeast(0L)
                val dDeadlineMissed = (cur.frameDeadlineMissed - effectivePrev.frameDeadlineMissed).coerceIn(0L, dJanky)

                val (dDropped, dLongGpu) = if (dJanky == 0L) {
                    0L to 0L
                } else {
                    computeDroppedSlotsAndGpuOverrun(
                        prev = effectivePrev,
                        cur = cur,
                        dEval = dEval,
                        dJanky = dJanky,
                        displayPeriodMs = displayPeriodMs,
                    )
                }
                // When GPU command execution overruns VSync (`dLongGpu > 0`), RenderThread's
                // `issueDrawCommands` also blocks on GPU fence backpressure; subtract `dLongGpu`
                // so GPU-bound stalls are not double-attributed as CPU jank unless UI/bitmap stalled too.
                val dCpuOnlySlowDraw = (dSlowDraw - dLongGpu).coerceAtLeast(0L)
                val dLongCpu = if (dJanky == 0L) {
                    0L
                } else {
                    GraphicsStatsParsers.saturatingAdd(
                        GraphicsStatsParsers.saturatingAdd(dSlowUi, dSlowBitmap),
                        dCpuOnlySlowDraw,
                    ).coerceAtMost(dJanky)
                }
                val dStuffing = if (dJanky == 0L) {
                    0L
                } else {
                    (dDeadlineMissed - dLongCpu - dLongGpu - dMissedVsync).coerceIn(0L, dJanky)
                }

                sliceEval = GraphicsStatsParsers.saturatingAdd(sliceEval, dEval)
                sliceDropped = GraphicsStatsParsers.saturatingAdd(sliceDropped, dDropped)
                sliceJanky = GraphicsStatsParsers.saturatingAdd(sliceJanky, dJanky)
                sliceLongCpu = GraphicsStatsParsers.saturatingAdd(sliceLongCpu, dLongCpu)
                sliceLongGpu = GraphicsStatsParsers.saturatingAdd(sliceLongGpu, dLongGpu)
                sliceStuffing = GraphicsStatsParsers.saturatingAdd(sliceStuffing, dStuffing)
                sliceMissedVsync = GraphicsStatsParsers.saturatingAdd(sliceMissedVsync, dMissedVsync)
                val pkgs = activePkgs ?: HashSet<String>(8).also { activePkgs = it }
                pkgs.add(key.packageName)
            }
            graphicsStatsLastDumpWallNs = nextDumpMaxWallNs

            if (sliceEval > 0L) {
                val bucket = getOrCreateSysBucketLocked(bucketKeyMs, safeNowMs)
                if (safeNowMs < bucket.firstFrameMs) {
                    bucket.firstFrameMs = safeNowMs
                }
                bucket.evaluated = GraphicsStatsParsers.saturatingAdd(bucket.evaluated, sliceEval)
                bucket.dropped = GraphicsStatsParsers.saturatingAdd(bucket.dropped, sliceDropped)
                bucket.janky = GraphicsStatsParsers.saturatingAdd(bucket.janky, sliceJanky)
                bucket.longCpu = GraphicsStatsParsers.saturatingAdd(bucket.longCpu, sliceLongCpu)
                bucket.longGpu = GraphicsStatsParsers.saturatingAdd(bucket.longGpu, sliceLongGpu)
                bucket.bufferStuffing = GraphicsStatsParsers.saturatingAdd(bucket.bufferStuffing, sliceStuffing)
                bucket.missedVsync = GraphicsStatsParsers.saturatingAdd(bucket.missedVsync, sliceMissedVsync)
                activePkgs?.let { bucket.activePackages.addAll(it) }
                if (deadlineHz < bucket.minDeadlineHz) bucket.minDeadlineHz = deadlineHz
                if (deadlineHz > bucket.maxDeadlineHz) bucket.maxDeadlineHz = deadlineHz
                if (safeNowMs > lastSysFrameElapsedMs) {
                    lastSysFrameElapsedMs = safeNowMs
                }
            }
            pruneSysFramesLocked(safeNowMs)
        }
    }

    private fun computeDroppedSlotsAndGpuOverrun(
        prev: PackageGraphicsStatsRecord,
        cur: PackageGraphicsStatsRecord,
        dEval: Long,
        dJanky: Long,
        displayPeriodMs: Double,
    ): Pair<Long, Long> {
        val cpuDeltasDesc = ArrayList<Pair<Int, Long>>()
        for ((ms, curCount) in cur.cpuHistogramMsCounts) {
            val prevCount = prev.cpuHistogramMsCounts[ms] ?: 0L
            val diff = curCount - prevCount
            if (diff > 0L && ms > 0) {
                cpuDeltasDesc += ms to diff
            }
        }
        cpuDeltasDesc.sortByDescending { it.first }

        var remJanky = dJanky
        var remUnflaggedFreezeBudget = (dEval - dJanky).coerceAtLeast(0L)
        var maxSmoothMs = 0
        val takenStallBuckets = ArrayList<Pair<Int, Long>>(cpuDeltasDesc.size.coerceAtMost(32))
        for ((ms, diff) in cpuDeltasDesc) {
            val takeJanky = if (remJanky > 0L) minOf(diff, remJanky) else 0L
            remJanky -= takeJanky
            val leftover = diff - takeJanky
            // When consecutive frames all freeze (e.g. 150..300ms UI stalls), HWUI's rolling
            // cadence heuristic suppresses `jankyFrames` on roughly half of the frozen frames;
            // still count missed VSync slots for unambiguous >= 85ms multi-VSync stalls up to `dEval`.
            val takeSevereFreeze = if (leftover > 0L && ms >= SEVERE_FREEZE_HISTOGRAM_MS && remUnflaggedFreezeBudget > 0L) {
                minOf(leftover, remUnflaggedFreezeBudget)
            } else {
                0L
            }
            remUnflaggedFreezeBudget -= takeSevereFreeze
            val totalTaken = takeJanky + takeSevereFreeze
            if (totalTaken > 0L) {
                takenStallBuckets += ms to totalTaken
            }
            val smoothLeftover = leftover - takeSevereFreeze
            // Only infer lower VRR / FrameRateOverride budgets (60/40/30 Hz) from genuinely smooth
            // frames (<= 34ms), never from multi-VSync freezes where HWUI suppressed consecutive jank.
            if (smoothLeftover > 0L && ms <= 34 && ms > maxSmoothMs) {
                maxSmoothMs = ms
            }
        }

        val pkgPeriodMs = when {
            maxSmoothMs <= 0 -> displayPeriodMs
            maxSmoothMs.toDouble() <= displayPeriodMs + 1.0 -> displayPeriodMs
            maxSmoothMs <= 17 -> maxOf(displayPeriodMs, 1000.0 / 60.0)
            maxSmoothMs <= 26 -> maxOf(displayPeriodMs, 1000.0 / 40.0)
            else -> maxOf(displayPeriodMs, 1000.0 / 30.0)
        }.coerceIn(2.0, 50.0)

        val maxDroppedPerFrame = (5000.0 / pkgPeriodMs).toLong().coerceIn(1L, 600L)
        var droppedSlots = 0L
        for ((ms, count) in takenStallBuckets) {
            val overrunMs = ms.toDouble() - pkgPeriodMs
            val slotsPerFrame = if (overrunMs > 0.01) {
                ceil(overrunMs / pkgPeriodMs).toLong().coerceIn(1L, maxDroppedPerFrame)
            } else {
                1L
            }
            val addSlots = if (count > Long.MAX_VALUE / slotsPerFrame) Long.MAX_VALUE else count * slotsPerFrame
            droppedSlots = GraphicsStatsParsers.saturatingAdd(droppedSlots, addSlots)
        }
        if (remJanky > 0L) {
            droppedSlots = GraphicsStatsParsers.saturatingAdd(droppedSlots, remJanky)
        }

        var gpuOverrun = 0L
        val gpuThresholdMs = pkgPeriodMs
        for ((ms, curCount) in cur.gpuHistogramMsCounts) {
            if (ms.toDouble() > gpuThresholdMs) {
                val prevCount = prev.gpuHistogramMsCounts[ms] ?: 0L
                val diff = curCount - prevCount
                if (diff > 0L) {
                    gpuOverrun = GraphicsStatsParsers.saturatingAdd(gpuOverrun, diff)
                }
            }
        }
        return droppedSlots.coerceAtLeast(dJanky) to gpuOverrun.coerceIn(0L, dJanky)
    }

    fun snapshotFrames(
        nowElapsedMs: Long,
        defaultHz: Int = 120,
        hasDumpPermission: Boolean = false,
    ): FrameJankSnapshot {
        val safeNowMs = nowElapsedMs.coerceAtLeast(0L)
        synchronized(lock) {
            if (hasDumpPermission && graphicsStatsHasBaseline) {
                return snapshotSystemWideGraphicsStatsLocked(safeNowMs, defaultHz)
            }
            pruneFramesLocked(safeNowMs)
            if (frameBuckets.isEmpty()) {
                return FrameJankSnapshot(
                    activeDeadlineHzMin = defaultHz,
                    activeDeadlineHzMax = defaultHz,
                    hasDumpPermission = hasDumpPermission,
                )
            }
            var eval = 0L
            var dropped = 0L
            var janky = 0L
            var longCpu = 0L
            var longGpu = 0L
            var stuffing = 0L
            var missedVsync = 0L
            var droppedReports = 0L
            var minHz = Int.MAX_VALUE
            var maxHz = 0
            var oldestEvaluatedMs = -1L
            for (b in frameBuckets) {
                eval += b.evaluated.toLong()
                dropped += b.dropped.toLong()
                janky += b.janky.toLong()
                longCpu += b.longCpu.toLong()
                longGpu += b.longGpu.toLong()
                stuffing += b.bufferStuffing.toLong()
                missedVsync += b.missedVsync.toLong()
                droppedReports += b.droppedReports.toLong()
                if (b.evaluated > 0) {
                    if (oldestEvaluatedMs < 0L || b.firstFrameMs < oldestEvaluatedMs) {
                        oldestEvaluatedMs = b.firstFrameMs
                    }
                    if (b.minDeadlineHz < minHz) minHz = b.minDeadlineHz
                    if (b.maxDeadlineHz > maxHz) maxHz = b.maxDeadlineHz
                }
            }
            val latest = frameBuckets.lastOrNull { it.evaluated > 0 }
            val isLatestActive = latest != null &&
                lastFrameElapsedMs >= 0L &&
                safeNowMs >= lastFrameElapsedMs &&
                (safeNowMs - lastFrameElapsedMs) <= 1_500L
            val latestJankPct = if (isLatestActive && latest.evaluated > 0) {
                ((latest.janky.toDouble() * 100.0) / latest.evaluated.toDouble()).toFloat().coerceIn(0f, 100f)
            } else {
                0f
            }
            val winDur = if (oldestEvaluatedMs >= 0L && safeNowMs >= oldestEvaluatedMs) {
                (safeNowMs - oldestEvaluatedMs).coerceAtMost(windowHorizonMs)
            } else {
                0L
            }
            return FrameJankSnapshot(
                evaluatedFrames5m = eval.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                droppedFrames5m = dropped.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                jankyFrames5m = janky.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                longCpuJank5m = longCpu.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                longGpuJank5m = longGpu.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                bufferStuffing5m = stuffing.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                sfMissedFrames5m = missedVsync.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                windowDurationMs = winDur,
                activeDeadlineHzMin = if (minHz == Int.MAX_VALUE) defaultHz else minHz,
                activeDeadlineHzMax = if (maxHz == 0) defaultHz else maxHz,
                latestIntervalJankPercent = latestJankPct,
                isSystemWideTimeStats = false,
                isSystemWideGraphicsStats = false,
                hasDumpPermission = hasDumpPermission,
                droppedReports5m = droppedReports.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            )
        }
    }

    private fun snapshotSystemWideGraphicsStatsLocked(safeNowMs: Long, defaultHz: Int): FrameJankSnapshot {
        pruneSysFramesLocked(safeNowMs)
        pruneVrrLocked(safeNowMs)

        var eval = 0L
        var dropped = 0L
        var janky = 0L
        var longCpu = 0L
        var longGpu = 0L
        var stuffing = 0L
        var missedVsync = 0L
        var minHz = Int.MAX_VALUE
        var maxHz = 0
        val activeApps = HashSet<String>()

        for (b in sysFrameBuckets) {
            eval = GraphicsStatsParsers.saturatingAdd(eval, b.evaluated)
            dropped = GraphicsStatsParsers.saturatingAdd(dropped, b.dropped)
            janky = GraphicsStatsParsers.saturatingAdd(janky, b.janky)
            longCpu = GraphicsStatsParsers.saturatingAdd(longCpu, b.longCpu)
            longGpu = GraphicsStatsParsers.saturatingAdd(longGpu, b.longGpu)
            stuffing = GraphicsStatsParsers.saturatingAdd(stuffing, b.bufferStuffing)
            missedVsync = GraphicsStatsParsers.saturatingAdd(missedVsync, b.missedVsync)
            if (b.evaluated > 0L) {
                activeApps.addAll(b.activePackages)
                if (b.minDeadlineHz < minHz) minHz = b.minDeadlineHz
                if (b.maxDeadlineHz > maxHz) maxHz = b.maxDeadlineHz
            }
        }
        for (s in vrrSlices) {
            if (s.timestampMs >= graphicsStatsBaselineElapsedMs) {
                if (s.hz < minHz) minHz = s.hz
                if (s.hz > maxHz) maxHz = s.hz
            }
        }

        val latest = sysFrameBuckets.lastOrNull { it.evaluated > 0L }
        val isLatestActive = latest != null &&
            lastSysFrameElapsedMs >= 0L &&
            safeNowMs >= lastSysFrameElapsedMs &&
            (safeNowMs - lastSysFrameElapsedMs) <= 3_500L
        val latestJankPct = if (isLatestActive && latest.evaluated > 0L) {
            ((latest.janky.coerceAtMost(latest.evaluated).toDouble() * 100.0) / latest.evaluated.toDouble())
                .toFloat()
                .coerceIn(0f, 100f)
        } else {
            0f
        }

        val winDur = if (graphicsStatsBaselineElapsedMs >= 0L && safeNowMs >= graphicsStatsBaselineElapsedMs) {
            (safeNowMs - graphicsStatsBaselineElapsedMs).coerceAtMost(windowHorizonMs)
        } else {
            0L
        }

        val evalInt = eval.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val jankyInt = janky.coerceAtMost(eval).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

        return FrameJankSnapshot(
            evaluatedFrames5m = evalInt,
            droppedFrames5m = dropped.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            jankyFrames5m = jankyInt,
            longCpuJank5m = longCpu.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            longGpuJank5m = longGpu.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            bufferStuffing5m = stuffing.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            sfMissedFrames5m = missedVsync.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            windowDurationMs = winDur,
            activeDeadlineHzMin = if (minHz == Int.MAX_VALUE) defaultHz else minHz,
            activeDeadlineHzMax = if (maxHz == 0) defaultHz else maxHz,
            latestIntervalJankPercent = latestJankPct,
            isSystemWideTimeStats = false,
            isSystemWideGraphicsStats = true,
            hasDumpPermission = true,
            activeAppCount5m = activeApps.size,
            droppedReports5m = 0,
        )
    }

    private fun pruneVrrLocked(nowElapsedMs: Long) {
        val cutoff = nowElapsedMs - windowHorizonMs
        while (vrrSlices.isNotEmpty() && vrrSlices.first().timestampMs < cutoff) {
            vrrSlices.removeFirst()
        }
    }

    private fun summarizeVrrLocked(): Pair<List<VrrResidencyBucket>, Int> {
        if (vrrSlices.isEmpty()) return emptyList<VrrResidencyBucket>() to 0
        val totalsByHz = HashMap<Int, Long>()
        var totalMs = 0L
        var switches = 0
        for (s in vrrSlices) {
            totalsByHz[s.hz] = (totalsByHz[s.hz] ?: 0L) + s.durationMs
            totalMs += s.durationMs
            if (s.switched) switches += 1
        }
        val denom = totalMs.coerceAtLeast(1L).toDouble()
        val buckets = totalsByHz.entries
            .sortedByDescending { it.key }
            .map { (hz, ms) ->
                VrrResidencyBucket(
                    hz = hz,
                    durationMs = ms,
                    fraction = (ms.toDouble() / denom).toFloat().coerceIn(0f, 1f),
                )
            }
        return buckets to switches
    }

    private fun pruneFramesLocked(nowElapsedMs: Long) {
        val cutoff = nowElapsedMs - windowHorizonMs
        while (frameBuckets.isNotEmpty() && frameBuckets.first().timestampMs < cutoff) {
            frameBuckets.removeFirst()
        }
    }

    private fun pruneSysFramesLocked(nowElapsedMs: Long) {
        val cutoff = nowElapsedMs - windowHorizonMs
        while (sysFrameBuckets.isNotEmpty() && sysFrameBuckets.first().timestampMs < cutoff) {
            sysFrameBuckets.removeFirst()
        }
    }

    companion object {
        private const val MAX_NEW_STREAM_INITIAL_FRAMES = 2_400L
        private const val SEVERE_FREEZE_HISTOGRAM_MS = 85

        fun snapToSupportedStep(rawHz: Float, supportedStepsHz: List<Float>): Int {
            val clean = if (rawHz.isFinite() && rawHz > 0f) rawHz else 60f
            if (supportedStepsHz.isEmpty()) return clean.roundToInt().coerceIn(1, 480)
            val nearest = supportedStepsHz.minByOrNull { kotlin.math.abs(it - clean) } ?: clean
            return if (kotlin.math.abs(nearest - clean) <= 4f) {
                nearest.roundToInt().coerceIn(1, 480)
            } else {
                clean.roundToInt().coerceIn(1, 480)
            }
        }
    }
}
