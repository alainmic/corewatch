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
) {
    val droppedPercent5m: Float
        get() = if (evaluatedFrames5m <= 0) 0f else ((droppedFrames5m * 100f) / evaluatedFrames5m.toFloat()).coerceIn(0f, 100f)

    val jankyPercent5m: Float
        get() = if (evaluatedFrames5m <= 0) 0f else ((jankyFrames5m * 100f) / evaluatedFrames5m.toFloat()).coerceIn(0f, 100f)

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
        var evaluated: Int = 0,
        var dropped: Int = 0,
        var janky: Int = 0,
        var longCpu: Int = 0,
        var longGpu: Int = 0,
        var bufferStuffing: Int = 0,
        var minDeadlineHz: Int = Int.MAX_VALUE,
        var maxDeadlineHz: Int = 0,
    )

    private val lock = Any()
    private val vrrSlices = ArrayDeque<VrrSlice>()
    private var lastSampleElapsedMs: Long = -1L
    private var lastObservedHz: Int = -1

    private val frameBuckets = ArrayDeque<FrameBucket>()
    private var firstFrameElapsedMs: Long = -1L
    private var lastFrameVsyncNs: Long = -1L

    fun reset() {
        synchronized(lock) {
            vrrSlices.clear()
            lastSampleElapsedMs = -1L
            lastObservedHz = -1
            frameBuckets.clear()
            firstFrameElapsedMs = -1L
            lastFrameVsyncNs = -1L
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
    ) {
        if (totalDurationNs <= 0L) return
        val hz = if (fallbackHz.isFinite() && fallbackHz >= 10f) fallbackHz else 120f
        val renderPeriodNs = (1_000_000_000.0 / hz.toDouble()).toLong()
        val rawDeadlineNs = if (deadlineNs in 1_000_000L..100_000_000L) {
            deadlineNs
        } else {
            renderPeriodNs
        }
        // When Android ARR/VRR paces rendering at a lower step (e.g., Render 60 Hz inside a 120 Hz VSync mode),
        // use the effective VRR render interval so 60 Hz-paced frames are not penalized against an 8.33 ms VSync tick.
        val safeDeadlineNs = maxOf(rawDeadlineNs, renderPeriodNs)
        val deadlineHz = (1_000_000_000.0 / safeDeadlineNs.toDouble()).roundToInt().coerceIn(20, 480)
        val vsyncSlipNs = (vsyncNs - intendedVsyncNs).coerceAtLeast(0L)
        val safeGpuNs = gpuDurationNs.coerceAtLeast(0L)
        val cpuNs = if (cpuStagesDurationNs >= 0L) {
            cpuStagesDurationNs
        } else {
            (totalDurationNs - safeGpuNs).coerceAtLeast(0L)
        }
        val activeRenderNs = if (cpuStagesDurationNs >= 0L) {
            maxOf(cpuNs + safeGpuNs, vsyncSlipNs)
        } else {
            totalDurationNs
        }
        val isIdleWakeupFrame = synchronized(lock) {
            val prevVsync = lastFrameVsyncNs
            if (vsyncNs > 0L) {
                lastFrameVsyncNs = vsyncNs
            }
            (prevVsync <= 0L || (vsyncNs - prevVsync) >= 250_000_000L) && vsyncSlipNs < safeDeadlineNs
        }
        // Isolated 1Hz telemetry ticks (>= 250 ms after the previous frame with no VSync slip)
        // wake the CPU from powersave, GPU from RC6, and eDP panel from PSR low-refresh (30 Hz / 33.3 ms).
        val evalDeadlineNs = if (isIdleWakeupFrame) {
            maxOf(safeDeadlineNs, 33_333_333L)
        } else {
            safeDeadlineNs
        }
        val isJanky = totalDurationNs > evalDeadlineNs && activeRenderNs > evalDeadlineNs
        val isDropped = isJanky && (vsyncSlipNs > safeDeadlineNs || activeRenderNs > (evalDeadlineNs * 2L))
        val isLongCpu = isJanky && cpuNs > (evalDeadlineNs * 65L / 100L)
        val isLongGpu = isJanky && safeGpuNs > (evalDeadlineNs * 50L / 100L)
        val isStuffing = isJanky && !isLongCpu && !isLongGpu

        val bucketKeyMs = (nowElapsedMs / 1_000L) * 1_000L
        synchronized(lock) {
            if (firstFrameElapsedMs < 0L) {
                firstFrameElapsedMs = nowElapsedMs
            }
            val tail = frameBuckets.lastOrNull()
            val bucket = if (tail != null && tail.timestampMs == bucketKeyMs) {
                tail
            } else {
                FrameBucket(timestampMs = bucketKeyMs).also { frameBuckets.addLast(it) }
            }
            bucket.evaluated += 1
            if (isDropped) bucket.dropped += 1
            if (isJanky) bucket.janky += 1
            if (isLongCpu) bucket.longCpu += 1
            if (isLongGpu) bucket.longGpu += 1
            if (isStuffing) bucket.bufferStuffing += 1
            if (deadlineHz < bucket.minDeadlineHz) bucket.minDeadlineHz = deadlineHz
            if (deadlineHz > bucket.maxDeadlineHz) bucket.maxDeadlineHz = deadlineHz
            pruneFramesLocked(nowElapsedMs)
        }
    }

    fun snapshotFrames(nowElapsedMs: Long, defaultHz: Int = 120): FrameJankSnapshot {
        synchronized(lock) {
            pruneFramesLocked(nowElapsedMs)
            if (frameBuckets.isEmpty()) {
                return FrameJankSnapshot(
                    activeDeadlineHzMin = defaultHz,
                    activeDeadlineHzMax = defaultHz,
                )
            }
            var eval = 0
            var dropped = 0
            var janky = 0
            var longCpu = 0
            var longGpu = 0
            var stuffing = 0
            var minHz = Int.MAX_VALUE
            var maxHz = 0
            for (b in frameBuckets) {
                eval += b.evaluated
                dropped += b.dropped
                janky += b.janky
                longCpu += b.longCpu
                longGpu += b.longGpu
                stuffing += b.bufferStuffing
                if (b.minDeadlineHz < minHz) minHz = b.minDeadlineHz
                if (b.maxDeadlineHz > maxHz) maxHz = b.maxDeadlineHz
            }
            val latest = frameBuckets.last()
            val latestJankPct = if (latest.evaluated > 0) {
                ((latest.janky * 100f) / latest.evaluated.toFloat()).coerceIn(0f, 100f)
            } else {
                0f
            }
            val winDur = if (firstFrameElapsedMs > 0L && nowElapsedMs >= firstFrameElapsedMs) {
                (nowElapsedMs - firstFrameElapsedMs).coerceAtMost(windowHorizonMs)
            } else {
                0L
            }
            return FrameJankSnapshot(
                evaluatedFrames5m = eval,
                droppedFrames5m = dropped,
                jankyFrames5m = janky,
                longCpuJank5m = longCpu,
                longGpuJank5m = longGpu,
                bufferStuffing5m = stuffing,
                sfMissedFrames5m = dropped,
                windowDurationMs = winDur,
                activeDeadlineHzMin = if (minHz == Int.MAX_VALUE) defaultHz else minHz,
                activeDeadlineHzMax = if (maxHz == 0) defaultHz else maxHz,
                latestIntervalJankPercent = latestJankPct,
                isSystemWideTimeStats = false,
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
