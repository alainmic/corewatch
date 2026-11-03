// Copyright 2026 The Corewatch Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.corewatch.data

import java.util.Locale
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
 * Rolling 5-minute VRR-aware frame pacing & jank snapshot collected from
 * `Window.OnFrameMetricsAvailableListener` (`FrameMetrics.DEADLINE`) or `SurfaceFlinger --timestats`.
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
    val droppedReports5m: Int = 0,
) {
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

    val deadlineRangeLabel: String
        get() = if (activeDeadlineHzMin == activeDeadlineHzMax) {
            "[$activeDeadlineHzMax] Hz"
        } else {
            "[$activeDeadlineHzMin, $activeDeadlineHzMax] Hz"
        }

    val scopeBadgeLabel: String
        get() = if (isSystemWideTimeStats) "SYSTEM-WIDE (SF TIMESTATS)" else "APP WINDOW (FRAMEMETRICS)"
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

    private val lock = Any()
    private val vrrSlices = ArrayDeque<VrrSlice>()
    private var lastSampleElapsedMs: Long = -1L
    private var lastObservedHz: Int = -1

    private val frameBuckets = ArrayDeque<FrameBucket>()
    private var lastFrameElapsedMs: Long = -1L

    fun reset() {
        synchronized(lock) {
            vrrSlices.clear()
            lastSampleElapsedMs = -1L
            lastObservedHz = -1
            frameBuckets.clear()
            lastFrameElapsedMs = -1L
        }
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

    fun snapshotFrames(nowElapsedMs: Long, defaultHz: Int = 120): FrameJankSnapshot {
        val safeNowMs = nowElapsedMs.coerceAtLeast(0L)
        synchronized(lock) {
            pruneFramesLocked(safeNowMs)
            if (frameBuckets.isEmpty()) {
                return FrameJankSnapshot(
                    activeDeadlineHzMin = defaultHz,
                    activeDeadlineHzMax = defaultHz,
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
                droppedReports5m = droppedReports.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            )
        }
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

    companion object {
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
