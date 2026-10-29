// Copyright 2026 The Corewatch Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.corewatch.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp

internal data class SparklinePoint(val x: Float, val y: Float)

/**
 * Computes normalized `(x, y)` canvas coordinates for [samples] within `[0..widthPx] x [0..heightPx]`.
 */
internal fun computeSparklinePoints(
    samples: List<Float>,
    widthPx: Float,
    heightPx: Float,
    strokeLinePx: Float,
    minFloor: Float = 0f,
    maxCeiling: Float? = null,
): List<SparklinePoint> {
    if (widthPx <= 2f || heightPx <= 2f || samples.isEmpty()) return emptyList()
    val rawMax = maxCeiling ?: samples.maxOrNull()?.coerceAtLeast(minFloor + 1f) ?: 1f
    val span = (rawMax - minFloor).takeIf { it > 0.0001f } ?: 1f
    val count = samples.size
    if (count == 1) {
        val norm = ((samples[0] - minFloor) / span).coerceIn(0f, 1f)
        val y = heightPx - (norm * (heightPx - strokeLinePx * 2f)) - strokeLinePx
        return listOf(SparklinePoint(0f, y), SparklinePoint(widthPx, y))
    }
    val stepX = widthPx / (count - 1).toFloat()
    return List(count) { i ->
        val norm = ((samples[i] - minFloor) / span).coerceIn(0f, 1f)
        val x = i * stepX
        val y = heightPx - (norm * (heightPx - strokeLinePx * 2f)) - strokeLinePx
        SparklinePoint(x, y)
    }
}

/**
 * Lightweight Compose `Canvas` sparkline that draws directly with primitive line segments
 * (`drawLine`) rather than allocating a `Path` object on every 1Hz/2Hz sample tick.
 */
@Composable
fun SparklineCanvas(
    samples: List<Float>,
    lineColor: Color,
    gridColor: Color,
    modifier: Modifier = Modifier,
    minFloor: Float = 0f,
    maxCeiling: Float? = null,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 2f || h <= 2f) return@Canvas

        // Subtle horizontal reference grid lines (25%, 50%, 75%)
        val strokeGrid = 1.dp.toPx()
        for (frac in listOf(0.25f, 0.5f, 0.75f)) {
            val y = h * frac
            drawLine(
                color = gridColor,
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = strokeGrid,
            )
        }

        val strokeLine = 2.dp.toPx()
        val points = computeSparklinePoints(
            samples = samples,
            widthPx = w,
            heightPx = h,
            strokeLinePx = strokeLine,
            minFloor = minFloor,
            maxCeiling = maxCeiling,
        )
        if (points.isEmpty()) return@Canvas

        if (samples.size == 1) {
            drawLine(
                color = lineColor,
                start = Offset(points[0].x, points[0].y),
                end = Offset(points[1].x, points[1].y),
                strokeWidth = strokeLine,
                cap = StrokeCap.Round,
            )
            return@Canvas
        }

        val fillAlphaColor = lineColor.copy(alpha = 0.16f)
        val stepX = (w / (points.size - 1).toFloat()).coerceAtLeast(1.5f)
        for (i in points.indices) {
            val pt = points[i]
            drawLine(
                color = fillAlphaColor,
                start = Offset(pt.x, pt.y),
                end = Offset(pt.x, h),
                strokeWidth = stepX,
            )
            if (i > 0) {
                val prev = points[i - 1]
                drawLine(
                    color = lineColor,
                    start = Offset(prev.x, prev.y),
                    end = Offset(pt.x, pt.y),
                    strokeWidth = strokeLine,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}
