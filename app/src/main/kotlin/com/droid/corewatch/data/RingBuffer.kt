// Copyright 2026 The Corewatch Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.corewatch.data

/**
 * Fixed-capacity primitive circular buffer for real-time telemetry time series.
 *
 * Updates in O(1) with zero heap allocations on the sampling loop, and sanitizes
 * non-finite values (`NaN`, `Infinity`) so corrupt kernel sensor reads never break
 * chart scaling or session statistics.
 */
class FloatRingBuffer(val capacity: Int = DEFAULT_CAPACITY) {
    init {
        require(capacity in 2..MAX_CAPACITY) { "Capacity must be in 2..$MAX_CAPACITY" }
    }

    private val data = FloatArray(capacity)
    private var head = 0
    var size: Int = 0
        private set

    fun push(rawValue: Float) {
        val safe = if (rawValue.isFinite()) rawValue else 0f
        data[head] = safe
        head = (head + 1) % capacity
        if (size < capacity) size++
    }

    /** Oldest sample is at index `0`, newest sample is at index `size - 1`. */
    operator fun get(index: Int): Float {
        if (index !in 0 until size) throw IndexOutOfBoundsException("Index $index out of bounds for size $size")
        val start = if (size < capacity) 0 else head
        return data[(start + index) % capacity]
    }

    fun latestOrNull(): Float? = if (size == 0) null else get(size - 1)

    fun latest(default: Float = 0f): Float = latestOrNull() ?: default

    fun minOrNull(): Float? {
        if (size == 0) return null
        var m = Float.POSITIVE_INFINITY
        for (i in 0 until size) {
            val v = get(i)
            if (v < m) m = v
        }
        return m
    }

    fun maxOrNull(): Float? {
        if (size == 0) return null
        var m = Float.NEGATIVE_INFINITY
        for (i in 0 until size) {
            val v = get(i)
            if (v > m) m = v
        }
        return m
    }

    fun average(): Float {
        if (size == 0) return 0f
        var sum = 0.0
        for (i in 0 until size) {
            sum += get(i).toDouble()
        }
        return (sum / size).toFloat()
    }

    fun toList(): List<Float> = List(size) { get(it) }

    fun clear() {
        head = 0
        size = 0
        data.fill(0f)
    }

    companion object {
        const val DEFAULT_CAPACITY = 60
        const val MAX_CAPACITY = 3_600
    }
}
