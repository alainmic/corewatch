// Copyright 2026 The Corewatch Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.corewatch.data

import java.util.Locale

data class DrmVersionInfo(
    val driverName: String,
    val description: String,
    val version: String,
)

data class DrmConnectorVrrInfo(
    val connectorName: String,
    val vrrCapable: Boolean,
    val vrrKernelEnabled: Boolean,
    val maxBpc: Int,
    val physicalSizeMm: String,
)

data class IvpuParamInfo(
    val deviceId: Int,
    val revision: Int,
    val maxClockHz: Long,
    val maxContexts: Int,
    val fwApiVersionRaw: Long,
    val engineHeartbeat: Long,
    val tileConfig: Long,
    val activeContexts: Int = 0,
) {
    val maxClockMhz: Int get() = (maxClockHz / 1_000_000L).toInt().coerceAtLeast(0)

    val deviceIdHex: String
        get() = String.format(Locale.US, "0x%04X", deviceId and 0xFFFF)

    val firmwareVersionLabel: String
        get() {
            if (fwApiVersionRaw <= 0L) return "N/A"
            val major = (fwApiVersionRaw ushr 16) and 0xFFFFL
            val minor = fwApiVersionRaw and 0xFFFFL
            return "v$major.$minor"
        }
}

/**
 * Unprivileged DRM/Accel ioctl bridge (`DRM_IOCTL_VERSION` on `/dev/dri/renderD128` and `/dev/accel0`,
 * plus read-only `DRM_IOCTL_IVPU_GET_PARAM` on `/dev/accel0` for Intel Lunar Lake / Meteor Lake NPUs).
 */
object AccelIoctlBridge {
    internal var testDrmVersionProvider: ((String) -> String?)? = null
    internal var testIvpuParamsProvider: ((String, Boolean) -> LongArray?)? = null
    internal var testDrmConnectorsProvider: ((String) -> String?)? = null

    private val nativeLoaded: Boolean by lazy {
        runCatching {
            System.loadLibrary("corewatch_accel")
            true
        }.getOrDefault(false)
    }

    @JvmStatic
    private external fun nativeQueryDrmVersion(path: String): String?

    @JvmStatic
    private external fun nativeQueryIvpuParams(path: String, heartbeatOnly: Boolean): LongArray?

    @JvmStatic
    private external fun nativeQueryDrmConnectors(path: String): String?

    fun queryDrmConnectors(path: String = "/dev/dri/card0"): List<DrmConnectorVrrInfo> {
        val raw = try {
            val override = testDrmConnectorsProvider
            if (override != null) {
                override(path)
            } else if (nativeLoaded) {
                nativeQueryDrmConnectors(path)
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        } ?: return emptyList()

        return parseDrmConnectorString(raw)
    }

    fun parseDrmConnectorString(raw: String): List<DrmConnectorVrrInfo> {
        if (raw.isBlank()) return emptyList()
        return raw.split(';').mapNotNull { entry ->
            val parts = entry.trim().split('|')
            if (parts.size < 5) return@mapNotNull null
            val name = SysfsParsers.sanitizeSensorName(parts[0], "")
            if (name.isEmpty()) return@mapNotNull null
            val vrrCapable = parts[1].trim() == "1"
            val vrrEnabled = parts[2].trim() == "1"
            val maxBpc = parts[3].trim().toIntOrNull()?.coerceIn(6, 16) ?: 8
            val sizeMm = SysfsParsers.sanitizeSensorName(parts[4], "")
            DrmConnectorVrrInfo(
                connectorName = name,
                vrrCapable = vrrCapable,
                vrrKernelEnabled = vrrEnabled,
                maxBpc = maxBpc,
                physicalSizeMm = sizeMm,
            )
        }
    }

    fun queryDrmVersion(path: String): DrmVersionInfo? {
        val raw = try {
            val override = testDrmVersionProvider
            if (override != null) {
                override(path)
            } else if (nativeLoaded) {
                nativeQueryDrmVersion(path)
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        } ?: return null

        return parseDrmVersionString(raw)
    }

    fun parseDrmVersionString(raw: String): DrmVersionInfo? {
        val parts = raw.split('|')
        if (parts.size < 3) return null
        val name = SysfsParsers.sanitizeSensorName(parts[0], "")
        if (name.isEmpty()) return null
        val desc = SysfsParsers.sanitizeSensorName(parts[1], name)
        val ver = SysfsParsers.sanitizeSensorName(parts[2], "1.0.0")
        return DrmVersionInfo(driverName = name, description = desc, version = ver)
    }

    fun queryIvpuParams(path: String, heartbeatOnly: Boolean = false): IvpuParamInfo? {
        val raw = try {
            val override = testIvpuParamsProvider
            if (override != null) {
                override(path, heartbeatOnly)
            } else if (nativeLoaded) {
                nativeQueryIvpuParams(path, heartbeatOnly)
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        } ?: return null

        return parseIvpuParamsArray(raw)
    }

    fun parseIvpuParamsArray(raw: LongArray): IvpuParamInfo? {
        if (raw.size < 7) return null
        return IvpuParamInfo(
            deviceId = raw[0].toInt(),
            revision = raw[1].toInt(),
            maxClockHz = raw[2].coerceAtLeast(0L),
            maxContexts = raw[3].toInt().coerceAtLeast(0),
            fwApiVersionRaw = raw[4].coerceAtLeast(0L),
            engineHeartbeat = raw[5].coerceAtLeast(0L),
            tileConfig = raw[6].coerceAtLeast(0L),
            activeContexts = raw.getOrNull(7)?.toInt()?.coerceAtLeast(0) ?: 0,
        )
    }
}
