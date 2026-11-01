// Copyright 2026 The Corewatch Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.corewatch.data

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

enum class UsbCategoryFilter(val label: String) {
    All("All"),
    HubsDocks("Hubs & Docks"),
    NetworkSerial("LAN & Serial"),
    InputHid("Input & HID"),
    AudioVideo("Audio & Video"),
    StorageSecurity("Storage & Security"),
    WarningsOnly("Warnings");
}

enum class UsbNodeCategory(
    val label: String,
    val shortBadge: String,
    val filterGroup: UsbCategoryFilter,
) {
    RootController("xHCI Host Controller", "xHCI", UsbCategoryFilter.HubsDocks),
    Dock("Dock / Alt-Mode Controller", "Dock", UsbCategoryFilter.HubsDocks),
    Hub("USB Hub", "Hub", UsbCategoryFilter.HubsDocks),
    NetworkAdapter("Ethernet / Network NIC", "LAN", UsbCategoryFilter.NetworkSerial),
    SerialAdapter("Serial / Debug UART", "UART", UsbCategoryFilter.NetworkSerial),
    WirelessController("Wireless / BT Controller", "Radio", UsbCategoryFilter.NetworkSerial),
    Keyboard("Keyboard", "Kbd", UsbCategoryFilter.InputHid),
    Pointer("Mouse / Trackpad", "Mouse", UsbCategoryFilter.InputHid),
    GameController("Game Controller", "Gamepad", UsbCategoryFilter.InputHid),
    HidControl("HID Control / MCU", "HID", UsbCategoryFilter.InputHid),
    Audio("USB Audio / DAC", "Audio", UsbCategoryFilter.AudioVideo),
    VideoWebcam("UVC Camera / Video", "Camera", UsbCategoryFilter.AudioVideo),
    Storage("Mass Storage / UASP", "Storage", UsbCategoryFilter.StorageSecurity),
    SecurityBiometric("Security Key / Biometric", "Security", UsbCategoryFilter.StorageSecurity),
    Billboard("USB Billboard Fallback", "Billboard", UsbCategoryFilter.HubsDocks),
    Other("USB Peripheral", "USB", UsbCategoryFilter.All);
}

enum class UsbHealthStatus(
    val badgeLabel: String,
    val isWarning: Boolean,
) {
    Healthy("Nominal", false),
    SpeedBottleneck("Speed Downgrade", true),
    BillboardFallback("Billboard Fallback", true),
    PowerBudgetWarning("Hub Power Limit", true);
}

data class UsbInterfaceSummary(
    val interfaceNumber: Int,
    val classCode: Int,
    val subClassCode: Int,
    val protocolCode: Int,
    val classLabel: String,
    val driverName: String,
    val interfaceLabel: String,
    val endpointCount: Int,
) {
    val classTripleHex: String
        get() = String.format(Locale.US, "%02x:%02x:%02x", classCode and 0xFF, subClassCode and 0xFF, protocolCode and 0xFF)
}

data class UsbCorrelatedInfo(
    val usbManagerEndpointTotal: Int = 0,
    val usbManagerConfigCount: Int = 0,
    val inputSourcesSummary: String? = null,
    val inputBatteryPercent: Int = -1,
    val audioCapabilitiesSummary: String? = null,
    val cameraSummary: String? = null,
    val storageVolumeSummary: String? = null,
    val isPresentInUsbManager: Boolean = false,
    val isExternalInputDevice: Boolean = false,
) {
    val hasAnyCorrelation: Boolean
        get() = isPresentInUsbManager ||
            inputSourcesSummary != null ||
            audioCapabilitiesSummary != null ||
            cameraSummary != null ||
            storageVolumeSummary != null
}

data class UsbTopologyNode(
    val id: String,
    val parentId: String?,
    val depth: Int,
    val busNumber: Int,
    val portChain: String,
    val pciControllerLabel: String,
    val vendorId: Int,
    val productId: Int,
    val vidPidHex: String,
    val productName: String,
    val vendorName: String,
    val usbVersionBcd: String,
    val deviceClassCode: Int,
    val negotiatedSpeedMbps: Float,
    val speedBadgeLabel: String,
    val speedTierDescription: String,
    val txLanes: Int,
    val rxLanes: Int,
    val lanesLabel: String,
    val maxPowerMa: Int,
    val isSelfPowered: Boolean,
    val downstreamRequestedMa: Int = 0,
    val isInternal: Boolean = false,
    val removableRaw: String = "unknown",
    val category: UsbNodeCategory = UsbNodeCategory.Other,
    val healthStatus: UsbHealthStatus = UsbHealthStatus.Healthy,
    val healthMessage: String? = null,
    val passiveNote: String? = null,
    val interfaces: List<UsbInterfaceSummary> = emptyList(),
    val correlatedInfo: UsbCorrelatedInfo = UsbCorrelatedInfo(),
    val companionNodeId: String? = null,
) {
    val isRootController: Boolean
        get() = category == UsbNodeCategory.RootController

    val isHubOrRoot: Boolean
        get() = category == UsbNodeCategory.RootController || category == UsbNodeCategory.Hub

    val powerSummaryLabel: String
        get() = if (isSelfPowered) {
            if (maxPowerMa > 0) "Self-Powered ($maxPowerMa mA aux)" else "Self-Powered (0 mA)"
        } else {
            "Bus-Powered ($maxPowerMa mA)"
        }

    val displayTitle: String
        get() = when {
            isRootController -> {
                val pciSuffix = if (pciControllerLabel.isNotBlank()) " [$pciControllerLabel]" else ""
                "xHCI Bus $busNumber · $speedBadgeLabel$pciSuffix"
            }
            productName.isNotBlank() -> productName
            else -> "${category.label} ($vidPidHex)"
        }
}

data class TypeCPortStatus(
    val portId: String,
    val supportedModes: String = "Dual-Role",
    val isConnected: Boolean = false,
    val currentMode: String = "None",
    val powerRole: String = "None",
    val dataRole: String = "None",
    val usbDataStatus: String = "Unknown",
    val isPowerTransferLimited: Boolean = false,
    val powerBrickStatus: String = "Unknown",
    val displayPortAltModeSummary: String? = null,
    val complianceWarnings: List<String> = emptyList(),
    val connectedDurationLabel: String? = null,
) {
    val hasWarning: Boolean
        get() = isConnected && (
            isPowerTransferLimited ||
                complianceWarnings.isNotEmpty() ||
                (usbDataStatus.isNotBlank() &&
                    !usbDataStatus.equals("Enabled", ignoreCase = true) &&
                    !usbDataStatus.equals("Unknown", ignoreCase = true))
            )

    val roleBadgeLabel: String
        get() = if (!isConnected) {
            "Disconnected"
        } else {
            val dRole = dataRole.ifBlank { currentMode }
            val pRole = when {
                powerRole.equals("Sink", ignoreCase = true) -> "Sink (PD In)"
                powerRole.equals("Source", ignoreCase = true) -> "Source (5V Out)"
                else -> powerRole
            }
            listOf(dRole, pRole).filter { it.isNotBlank() && !it.equals("None", ignoreCase = true) }
                .joinToString(" · ")
                .ifBlank { "Connected ($currentMode)" }
        }
}

enum class UsbHotplugAction(
    val badgeLabel: String,
    val isAlert: Boolean,
) {
    Attached("ATTACHED", false),
    Detached("DETACHED", false),
    BottleneckDetected("SPEED DOWNGRADE", true),
    BillboardAlert("BILLBOARD FALLBACK", true),
    PowerBudgetAlert("HUB POWER LIMIT", true);
}

data class UsbHotplugEvent(
    val timestampLabel: String,
    val elapsedRealtimeMs: Long,
    val nodeId: String,
    val deviceName: String,
    val vidPidHex: String,
    val speedBadgeLabel: String,
    val action: UsbHotplugAction,
    val detail: String,
)

data class UsbPeripheralTelemetrySnapshot(
    val nodes: List<UsbTopologyNode> = emptyList(),
    val externalPeripheralCount: Int = 0,
    val externalHubCount: Int = 0,
    val internalDeviceCount: Int = 0,
    val warningCount: Int = 0,
    val maxExternalSpeedMbps: Float = 0f,
    val maxExternalSpeedLabel: String = "No External USB",
    val maxControllerSpeedLabel: String = "N/A",
    val hasDumpPermission: Boolean = false,
    val hasTypeCPorts: Boolean = false,
    val typeCPorts: List<TypeCPortStatus> = emptyList(),
    val hotplugEvents: List<UsbHotplugEvent> = emptyList(),
)

/**
 * Clean-room curated lookup of well-known public USB-IF Vendor IDs across desktop silicon,
 * docks, hubs, networking, input peripherals, audio interfaces, cameras, storage, and security keys.
 */
object UsbVendorLookup {
    private val KNOWN_VENDORS: Map<Int, String> = mapOf(
        0x03eb to "Microchip / Atmel",
        0x03f0 to "HP",
        0x0403 to "FTDI",
        0x0409 to "NEC",
        0x0424 to "Microchip (SMSC)",
        0x043e to "LG Electronics",
        0x0451 to "Texas Instruments",
        0x045e to "Microsoft",
        0x046d to "Logitech",
        0x047f to "Poly / Plantronics",
        0x0483 to "STMicroelectronics",
        0x048d to "Integrated Technology Express (ITE)",
        0x04a9 to "Canon",
        0x04b4 to "Infineon / Cypress",
        0x04ca to "Lite-On",
        0x04d8 to "Microchip Technology",
        0x04e8 to "Samsung Electronics",
        0x04f2 to "Chicony Electronics",
        0x04f3 to "Elan Microelectronics",
        0x050d to "Belkin",
        0x054c to "Sony",
        0x0557 to "ATEN International",
        0x056a to "Wacom",
        0x057e to "Nintendo",
        0x058f to "Alcor Micro",
        0x05ac to "Apple",
        0x05c6 to "Qualcomm",
        0x05dc to "Lexar",
        0x05e3 to "Genesys Logic",
        0x067b to "Prolific Technology",
        0x06cb to "Synaptics",
        0x06f8 to "Guillemot / Thrustmaster",
        0x0764 to "CyberPower",
        0x0781 to "SanDisk / Western Digital",
        0x08bb to "Texas Instruments (Burr-Brown Audio)",
        0x090c to "Silicon Motion",
        0x0930 to "Toshiba / Kioxia",
        0x0951 to "Kingston Technology",
        0x0955 to "NVIDIA",
        0x0a12 to "Qualcomm CSR",
        0x0a5c to "Broadcom",
        0x0b05 to "ASUS",
        0x0b0e to "GN Audio (Jabra)",
        0x0b95 to "ASIX Electronics",
        0x0bc2 to "Seagate",
        0x0bda to "Realtek Semiconductor",
        0x0cf3 to "Qualcomm Atheros",
        0x0d8c to "C-Media Electronics",
        0x0e8d to "MediaTek",
        0x0fd9 to "Elgato",
        0x1038 to "SteelSeries",
        0x1050 to "Yubico",
        0x1058 to "Western Digital",
        0x10c4 to "Silicon Labs",
        0x1199 to "Sierra Wireless",
        0x1235 to "Focusrite",
        0x1395 to "Sennheiser / EPOS",
        0x13d3 to "IMC Networks",
        0x14cd to "Super Top",
        0x152d to "JMicron",
        0x1532 to "Razer",
        0x174c to "ASMedia Technology",
        0x17e9 to "DisplayLink (Synaptics)",
        0x17ef to "Lenovo",
        0x18d1 to "Google",
        0x1916 to "Anker",
        0x1a40 to "Terminus Technology",
        0x1a86 to "QinHeng Electronics (WCH)",
        0x1b1c to "Corsair",
        0x1bcf to "Sunplus Innovation",
        0x1d5c to "Fresco Logic",
        0x1d6b to "Linux Foundation (xHCI)",
        0x1fc9 to "NXP Semiconductors",
        0x2109 to "VIA Labs",
        0x2188 to "CalDigit",
        0x24ae to "Rapoo",
        0x2516 to "Cooler Master",
        0x258a to "Sino Wealth / Gaming HID",
        0x2717 to "Xiaomi",
        0x27c6 to "Goodix",
        0x28de to "Valve",
        0x291a to "Anker Innovations",
        0x2b89 to "Ugreen",
        0x31b2 to "Sonos",
        0x32ac to "Framework Computer",
        0x3434 to "Keychron",
        0x413c to "Dell",
        0x5986 to "Bison Electronics",
        0x8086 to "Intel",
        0x8087 to "Intel",
    )

    fun resolveVendorName(vendorId: Int, rawManufacturer: String?): String {
        val cleanedRaw = rawManufacturer
            ?.trim()
            ?.takeIf {
                it.isNotEmpty() &&
                    !it.equals("Generic", ignoreCase = true) &&
                    !it.equals("Unknown", ignoreCase = true) &&
                    !it.startsWith("Linux ", ignoreCase = true)
            }
            ?.let { SysfsParsers.sanitizeSensorName(it, "") }
            .orEmpty()
        val tableVendor = KNOWN_VENDORS[vendorId and 0xFFFF]
        return when {
            tableVendor != null && cleanedRaw.isEmpty() -> tableVendor
            tableVendor != null && cleanedRaw.length <= 4 -> tableVendor
            cleanedRaw.isNotEmpty() -> cleanedRaw
            tableVendor != null -> tableVendor
            vendorId > 0 -> String.format(Locale.US, "Vendor 0x%04x", vendorId and 0xFFFF)
            else -> "Unknown Vendor"
        }
    }
}

/**
 * Pure, deterministic parsers for `/sys/bus/usb/devices` topology trees, speed/power/Alt-Mode
 * bottleneck diagnostics, and `dumpsys usb` Type-C port state.
 */
object UsbPeripheralParsers {
    private val USB_BUS_NAME_REGEX = Regex("^usb(\\d+)$")
    private val USB_DEV_NAME_REGEX = Regex("^(\\d+)-(\\d+(?:\\.\\d+)*)$")
    private val USB_IFACE_NAME_REGEX = Regex("^([0-9]+-[0-9.]+):\\d+\\.(\\d+)$")
    private val PCI_BDF_REGEX = Regex("(?:0000:)?([0-9a-fA-F]{2}:[0-9a-fA-F]{2}\\.[0-7])")
    private val DP_ALT_LANES_REGEX = Regex("numLanes=(\\d+)")
    private val DP_ALT_PARTNER_REGEX = Regex("partnerSink=([A-Za-z0-9_-]+)")
    private val DP_ALT_TRAINING_REGEX = Regex("linkTrainingStatus=([A-Za-z0-9_-]+)")
    private val POWER_DIGITS_REGEX = Regex("(\\d+)")

    private val SUPER_SPEED_NIC_VID_PIDS: Set<Pair<Int, Int>> = setOf(
        (0x0bda to 0x8153), // Realtek RTL8153 1GbE
        (0x0bda to 0x8156), // Realtek RTL8156 2.5GbE
        (0x0bda to 0x8157), // Realtek RTL8157 5GbE
        (0x0b95 to 0x1790), // ASIX AX88179 1GbE
        (0x0b95 to 0x179a), // ASIX AX88179A 1GbE
        (0x17ef to 0x721e), // Lenovo USB-C 2.5GbE
        (0x2b89 to 0x8156), // Ugreen 2.5GbE
    )

    data class RawSysfsUsbInterface(
        val interfaceNumber: Int,
        val classCode: Int,
        val subClassCode: Int,
        val protocolCode: Int,
        val endpointCount: Int,
        val driverName: String,
        val interfaceLabel: String,
    )

    data class RawSysfsUsbDevice(
        val id: String,
        val canonicalPath: String,
        val vendorId: Int,
        val productId: Int,
        val deviceClassCode: Int,
        val usbVersionBcd: String,
        val speedMbps: Float,
        val txLanes: Int,
        val rxLanes: Int,
        val maxPowerMa: Int,
        val bmAttributesHex: Int,
        val removable: String,
        val manufacturer: String,
        val product: String,
        val interfaces: List<RawSysfsUsbInterface>,
    )

    fun readSysfsUsbDirectory(usbDevicesRoot: File): List<RawSysfsUsbDevice> {
        if (!usbDevicesRoot.isDirectory || !usbDevicesRoot.canRead()) return emptyList()
        val entries = runCatching { usbDevicesRoot.listFiles() }.getOrNull() ?: return emptyList()

        val interfacesByDeviceId = HashMap<String, MutableList<RawSysfsUsbInterface>>()
        val deviceDirs = ArrayList<File>()

        for (entry in entries.take(256)) {
            val name = entry.name
            val ifaceMatch = USB_IFACE_NAME_REGEX.matchEntire(name)
            if (ifaceMatch != null) {
                val devId = ifaceMatch.groupValues[1]
                if (devId.endsWith("-0")) continue // Root hub pseudo-interface (e.g. 2-0:1.0)
                val ifaceNum = ifaceMatch.groupValues[2].toIntOrNull() ?: 0
                val cls = readSmallAttr(entry, "bInterfaceClass")?.toIntOrNull(16) ?: 0
                val sub = readSmallAttr(entry, "bInterfaceSubClass")?.toIntOrNull(16) ?: 0
                val pro = readSmallAttr(entry, "bInterfaceProtocol")?.toIntOrNull(16) ?: 0
                val eps = readSmallAttr(entry, "bNumEndpoints")?.toIntOrNull(16)
                    ?: readSmallAttr(entry, "bNumEndpoints")?.toIntOrNull()
                    ?: 0
                val drv = runCatching {
                    val link = File(entry, "driver")
                    if (link.exists()) link.canonicalFile.name else ""
                }.getOrDefault("")
                val ifaceStr = readSmallAttr(entry, "interface").orEmpty()
                interfacesByDeviceId.getOrPut(devId) { ArrayList() }.add(
                    RawSysfsUsbInterface(
                        interfaceNumber = ifaceNum,
                        classCode = cls,
                        subClassCode = sub,
                        protocolCode = pro,
                        endpointCount = eps.coerceIn(0, 64),
                        driverName = drv,
                        interfaceLabel = ifaceStr,
                    ),
                )
            } else if (USB_BUS_NAME_REGEX.matches(name) || USB_DEV_NAME_REGEX.matches(name)) {
                deviceDirs.add(entry)
            }
        }

        val result = ArrayList<RawSysfsUsbDevice>(deviceDirs.size)
        for (dir in deviceDirs) {
            val id = dir.name
            val canon = runCatching { dir.canonicalPath }.getOrDefault(dir.path)
            val vid = readSmallAttr(dir, "idVendor")?.toIntOrNull(16) ?: 0
            val pid = readSmallAttr(dir, "idProduct")?.toIntOrNull(16) ?: 0
            val dCls = readSmallAttr(dir, "bDeviceClass")?.toIntOrNull(16) ?: 0
            val ver = readSmallAttr(dir, "version").orEmpty().trim().ifEmpty { "2.00" }
            val spd = readSmallAttr(dir, "speed")?.toFloatOrNull()?.takeIf { it.isFinite() && it >= 0f } ?: 0f
            val tx = readSmallAttr(dir, "tx_lanes")?.toIntOrNull()?.coerceIn(1, 4) ?: 1
            val rx = readSmallAttr(dir, "rx_lanes")?.toIntOrNull()?.coerceIn(1, 4) ?: 1
            val pwrRaw = readSmallAttr(dir, "bMaxPower").orEmpty()
            val maxMa = POWER_DIGITS_REGEX.find(pwrRaw)?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 5_000) ?: 0
            val bma = readSmallAttr(dir, "bmAttributes")?.toIntOrNull(16) ?: 0x80
            val rem = readSmallAttr(dir, "removable")?.lowercase(Locale.US)?.trim().orEmpty().ifEmpty { "unknown" }
            val mfg = readSmallAttr(dir, "manufacturer").orEmpty()
            val prd = readSmallAttr(dir, "product").orEmpty()
            val ifaces = interfacesByDeviceId[id]?.sortedBy { it.interfaceNumber }.orEmpty()
            result += RawSysfsUsbDevice(
                id = id,
                canonicalPath = canon,
                vendorId = vid,
                productId = pid,
                deviceClassCode = dCls,
                usbVersionBcd = ver,
                speedMbps = spd,
                txLanes = tx,
                rxLanes = rx,
                maxPowerMa = maxMa,
                bmAttributesHex = bma,
                removable = rem,
                manufacturer = mfg,
                product = prd,
                interfaces = ifaces,
            )
        }
        return result
    }

    fun buildUsbTopologySnapshot(
        rawDevices: List<RawSysfsUsbDevice>,
        correlatedByVidPid: Map<Pair<Int, Int>, UsbCorrelatedInfo> = emptyMap(),
        hasDumpPermission: Boolean = false,
        typeCPorts: List<TypeCPortStatus> = emptyList(),
        hotplugEvents: List<UsbHotplugEvent> = emptyList(),
    ): UsbPeripheralTelemetrySnapshot {
        if (rawDevices.isEmpty()) {
            val portWarnings = typeCPorts.count { it.hasWarning }
            return UsbPeripheralTelemetrySnapshot(
                hasDumpPermission = hasDumpPermission,
                hasTypeCPorts = typeCPorts.isNotEmpty(),
                typeCPorts = typeCPorts,
                warningCount = portWarnings,
                hotplugEvents = hotplugEvents,
            )
        }

        val rawById = rawDevices.associateBy { it.id }

        // 1. Determine root-port ancestor removable status so internal sub-hubs/NICs inside an external
        // USB-C/Thunderbolt dock inherit `isInternal = false` from their removable root port (e.g. `2-4` / `3-2`),
        // whereas laptop-motherboard fixed root ports (`3-3` webcam, `3-6` FPMCU) resolve to `isInternal = true`.
        fun isInternalSubtree(raw: RawSysfsUsbDevice): Boolean {
            if (USB_BUS_NAME_REGEX.matches(raw.id)) return false
            val rootPortId = extractRootPortAncestorId(raw.id) ?: return false
            val rootAncestor = rawById[rootPortId] ?: raw
            val corr = correlatedByVidPid[raw.vendorId to raw.productId]
            val rootCorr = correlatedByVidPid[rootAncestor.vendorId to rootAncestor.productId]
            if (corr?.isExternalInputDevice == true || rootCorr?.isExternalInputDevice == true) {
                return false
            }
            // Known internal Chromebook/Googlebook EC MCU or integrated user-facing camera on a fixed root port
            if (rootAncestor.removable == "fixed") {
                val isRootHubOrDock = rootAncestor.deviceClassCode == 0x09 ||
                    rootAncestor.interfaces.any { it.classCode == 0x09 } ||
                    rootAncestor.product.contains("Dock", ignoreCase = true)
                // External docks on removable Type-C ports report "removable" or "unknown"; fixed non-dock root ports are internal.
                if (!isRootHubOrDock) return true
                // Even if an internal root hub is marked fixed, check if any child has removable == "removable"
                val hasRemovableDescendant = rawDevices.any {
                    it.id.startsWith("$rootPortId.") && it.removable == "removable"
                }
                return !hasRemovableDescendant
            }
            return false
        }

        val anySuperSpeedActive = rawDevices.any { !USB_BUS_NAME_REGEX.matches(it.id) && it.speedMbps >= 5_000f }

        // 2. Compute downstream bus-powered current draw per parent hub ID
        val downstreamBusMaByParent = HashMap<String, Int>()
        for (raw in rawDevices) {
            val parentId = resolveParentNodeId(raw.id) ?: continue
            val selfPowered = (raw.bmAttributesHex and 0x40) != 0
            if (!selfPowered && raw.maxPowerMa > 0) {
                downstreamBusMaByParent[parentId] = (downstreamBusMaByParent[parentId] ?: 0) + raw.maxPowerMa
            }
        }

        // 3. Build preliminary UsbTopologyNode map
        val prelimById = LinkedHashMap<String, UsbTopologyNode>(rawDevices.size)
        for (raw in rawDevices) {
            val busMatch = USB_BUS_NAME_REGEX.matchEntire(raw.id)
            val devMatch = USB_DEV_NAME_REGEX.matchEntire(raw.id)
            val busNum = busMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: devMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: 0
            val parentId = resolveParentNodeId(raw.id)
            val depth = computeNodeDepth(raw.id)
            val pciLabel = extractPciBdf(raw.canonicalPath)
            val vidPidHex = String.format(Locale.US, "%04x:%04x", raw.vendorId and 0xFFFF, raw.productId and 0xFFFF)
            val cleanProduct = SysfsParsers.sanitizeSensorName(raw.product.trim(), "")
            val vendorName = UsbVendorLookup.resolveVendorName(raw.vendorId, raw.manufacturer)
            val (speedBadge, speedTierDesc) = formatUsbSpeed(raw.speedMbps)
            val lanesStr = if (raw.txLanes > 1 || raw.rxLanes > 1) {
                "${raw.txLanes}×${raw.rxLanes} Lanes"
            } else {
                "1×1 Lane"
            }
            val selfPowered = (raw.bmAttributesHex and 0x40) != 0 || busMatch != null
            val ifaces = raw.interfaces.map { i ->
                UsbInterfaceSummary(
                    interfaceNumber = i.interfaceNumber,
                    classCode = i.classCode,
                    subClassCode = i.subClassCode,
                    protocolCode = i.protocolCode,
                    classLabel = usbClassLabel(i.classCode, i.subClassCode, i.protocolCode),
                    driverName = SysfsParsers.sanitizeSensorName(i.driverName.ifBlank { "none" }, "none"),
                    interfaceLabel = SysfsParsers.sanitizeSensorName(i.interfaceLabel, ""),
                    endpointCount = i.endpointCount,
                )
            }
            val corr = correlatedByVidPid[raw.vendorId to raw.productId] ?: UsbCorrelatedInfo()
            val internal = isInternalSubtree(raw)
            val category = classifyNodeCategory(
                isRootBus = busMatch != null,
                raw = raw,
                cleanProduct = cleanProduct,
                correlated = corr,
            )
            val downstreamMa = downstreamBusMaByParent[raw.id] ?: 0
            val (health, healthMsg, passiveNote) = evaluateNodeHealth(
                raw = raw,
                category = category,
                cleanProduct = cleanProduct,
                isSelfPowered = selfPowered,
                downstreamRequestedMa = downstreamMa,
                anySuperSpeedActive = anySuperSpeedActive,
            )

            prelimById[raw.id] = UsbTopologyNode(
                id = raw.id,
                parentId = parentId,
                depth = depth,
                busNumber = busNum,
                portChain = if (busMatch != null) "Bus $busNum" else raw.id,
                pciControllerLabel = pciLabel,
                vendorId = raw.vendorId and 0xFFFF,
                productId = raw.productId and 0xFFFF,
                vidPidHex = vidPidHex,
                productName = cleanProduct.ifEmpty { category.label },
                vendorName = vendorName,
                usbVersionBcd = SysfsParsers.sanitizeSensorName(raw.usbVersionBcd, "2.00"),
                deviceClassCode = raw.deviceClassCode and 0xFF,
                negotiatedSpeedMbps = raw.speedMbps,
                speedBadgeLabel = speedBadge,
                speedTierDescription = speedTierDesc,
                txLanes = raw.txLanes,
                rxLanes = raw.rxLanes,
                lanesLabel = lanesStr,
                maxPowerMa = raw.maxPowerMa,
                isSelfPowered = selfPowered,
                downstreamRequestedMa = downstreamMa,
                isInternal = internal,
                removableRaw = SysfsParsers.sanitizeSensorName(raw.removable, "unknown"),
                category = category,
                healthStatus = health,
                healthMessage = healthMsg,
                passiveNote = passiveNote,
                interfaces = ifaces,
                correlatedInfo = corr,
            )
        }

        // 4. Pair companion xHCI root buses (same PCI BDF, USB 2.0 <-> USB 3.x) and companion USB2/USB3 dock hubs
        val companionMap = resolveCompanionPairs(prelimById.values.toList())
        val enrichedById = prelimById.mapValues { (id, node) ->
            val comp = companionMap[id]
            if (comp != null) node.copy(companionNodeId = comp) else node
        }

        // 5. Emit deterministic pre-order DFS sorted by numeric bus & port segments
        val childrenByParent = HashMap<String?, MutableList<UsbTopologyNode>>()
        for (node in enrichedById.values) {
            val effectiveParent = node.parentId?.takeIf { enrichedById.containsKey(it) }
            childrenByParent.getOrPut(effectiveParent) { ArrayList() }.add(node)
        }
        for (list in childrenByParent.values) {
            list.sortWith(USB_NODE_COMPARATOR)
        }

        val orderedNodes = ArrayList<UsbTopologyNode>(enrichedById.size)
        fun dfs(parentId: String?, depthOverride: Int) {
            val kids = childrenByParent[parentId] ?: return
            for (kid in kids) {
                orderedNodes += if (kid.depth == depthOverride) kid else kid.copy(depth = depthOverride)
                dfs(kid.id, depthOverride + 1)
            }
        }
        dfs(null, 0)

        val externalPeripherals = orderedNodes.filter {
            !it.isInternal && !it.isRootController && it.category != UsbNodeCategory.Hub
        }
        val externalHubs = orderedNodes.count {
            !it.isInternal && (it.category == UsbNodeCategory.Hub || it.category == UsbNodeCategory.Dock)
        }
        val internalDevices = orderedNodes.count { it.isInternal && !it.isRootController }
        val maxExtSpeed = orderedNodes
            .filter { !it.isInternal && !it.isRootController }
            .maxOfOrNull { it.negotiatedSpeedMbps } ?: 0f
        val maxCtrlSpeed = orderedNodes
            .filter { it.isRootController }
            .maxOfOrNull { it.negotiatedSpeedMbps } ?: 0f
        val nodeWarnings = orderedNodes.count { !it.isInternal && it.healthStatus.isWarning }
        val portWarnings = typeCPorts.count { it.hasWarning }

        return UsbPeripheralTelemetrySnapshot(
            nodes = orderedNodes,
            externalPeripheralCount = externalPeripherals.size,
            externalHubCount = externalHubs,
            internalDeviceCount = internalDevices,
            warningCount = nodeWarnings + portWarnings,
            maxExternalSpeedMbps = maxExtSpeed,
            maxExternalSpeedLabel = if (maxExtSpeed > 0f) formatUsbSpeed(maxExtSpeed).first else "No External USB",
            maxControllerSpeedLabel = if (maxCtrlSpeed > 0f) formatUsbSpeed(maxCtrlSpeed).first else "N/A",
            hasDumpPermission = hasDumpPermission,
            hasTypeCPorts = typeCPorts.isNotEmpty(),
            typeCPorts = typeCPorts,
            hotplugEvents = hotplugEvents,
        )
    }

    /**
     * Parses `dumpsys usb` (`port_manager={ usb_ports=[ ... ] }`) into structured [TypeCPortStatus] items.
     * Tracks bracket depth so `role_combinations=[ ... ]` never overwrites active `power_role` or `data_role`.
     */
    fun parseUsbDumpsysPorts(raw: String, nowElapsedMs: Long = 0L): List<TypeCPortStatus> {
        if (raw.isBlank()) return emptyList()
        val ports = ArrayList<TypeCPortStatus>()

        var inUsbPortsArray = false
        var usbPortsBracketDepth = 0
        var portEntryBraceDepth = 0
        var currentSection = ""

        var portId = ""
        var supportedModes = "dual"
        var connected = false
        var currentMode = "none"
        var powerRole = "none"
        var dataRole = "none"
        var usbDataStatus = "unknown"
        var powerLimited = false
        var powerBrick = "unknown"
        var dpAltSummary: String? = null
        var complianceList: List<String> = emptyList()
        var connectedAtMs = 0L

        fun resetPortFields() {
            portId = ""
            supportedModes = "dual"
            connected = false
            currentMode = "none"
            powerRole = "none"
            dataRole = "none"
            usbDataStatus = "unknown"
            powerLimited = false
            powerBrick = "unknown"
            dpAltSummary = null
            complianceList = emptyList()
            connectedAtMs = 0L
            currentSection = ""
        }

        fun flushPortIfValid() {
            if (portId.isNotBlank() && ports.size < 12) {
                val durationLabel = if (connected && connectedAtMs > 0L && nowElapsedMs > connectedAtMs) {
                    formatDurationSec(((nowElapsedMs - connectedAtMs) / 1_000L).coerceAtLeast(0L))
                } else {
                    null
                }
                ports += TypeCPortStatus(
                    portId = SysfsParsers.sanitizeSensorName(portId, "port"),
                    supportedModes = formatPortModeLabel(supportedModes),
                    isConnected = connected,
                    currentMode = formatRoleToken(currentMode),
                    powerRole = formatRoleToken(powerRole),
                    dataRole = formatRoleToken(dataRole),
                    usbDataStatus = formatRoleToken(usbDataStatus),
                    isPowerTransferLimited = powerLimited,
                    powerBrickStatus = formatRoleToken(powerBrick),
                    displayPortAltModeSummary = dpAltSummary,
                    complianceWarnings = complianceList,
                    connectedDurationLabel = durationLabel,
                )
            }
            resetPortFields()
        }

        for (rawLine in raw.lineSequence().take(2_500)) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            if (!inUsbPortsArray) {
                if (line.startsWith("usb_ports=[")) {
                    inUsbPortsArray = true
                    usbPortsBracketDepth = countChar(line, '[') - countChar(line, ']')
                    if (usbPortsBracketDepth <= 0) {
                        inUsbPortsArray = false
                    }
                }
                continue
            }

            // Inside usb_ports=[ ... ]
            val openBrackets = countChar(line, '[')
            val closeBrackets = countChar(line, ']')
            val openBraces = countChar(line, '{')
            val closeBraces = countChar(line, '}')

            val bracketDepthBefore = usbPortsBracketDepth
            val braceDepthBefore = portEntryBraceDepth

            if (braceDepthBefore == 0 && line == "{") {
                resetPortFields()
                portEntryBraceDepth = 1
                continue
            }

            if (braceDepthBefore == 1) {
                when {
                    line.startsWith("port={") -> currentSection = "port"
                    line.startsWith("status={") -> currentSection = "status"
                    line.startsWith("connected_at_millis=") -> {
                        connectedAtMs = line.substringAfter('=').trim().toLongOrNull() ?: 0L
                    }
                }
            } else if (braceDepthBefore == 2 && bracketDepthBefore == 1) {
                if (currentSection == "port") {
                    when {
                        line.startsWith("id=") -> portId = line.substringAfter('=').trim()
                        line.startsWith("supported_modes=") -> supportedModes = line.substringAfter('=').trim()
                    }
                } else if (currentSection == "status") {
                    when {
                        line.startsWith("connected=") -> connected = line.substringAfter('=').trim().equals("true", ignoreCase = true)
                        line.startsWith("current_mode=") -> currentMode = line.substringAfter('=').trim()
                        line.startsWith("power_role=") -> powerRole = line.substringAfter('=').trim()
                        line.startsWith("data_role=") -> dataRole = line.substringAfter('=').trim()
                        line.startsWith("usb_data_status=") -> usbDataStatus = line.substringAfter('=').trim()
                        line.startsWith("is_power_transfer_limited=") -> {
                            powerLimited = line.substringAfter('=').trim().equals("true", ignoreCase = true)
                        }
                        line.startsWith("usb_power_brick_status=") -> powerBrick = line.substringAfter('=').trim()
                        line.startsWith("compliance_warning_status=") -> {
                            val inside = line.substringAfter('[').substringBefore(']').trim()
                            if (inside.isNotEmpty()) {
                                complianceList = inside.split(',')
                                    .map { SysfsParsers.sanitizeSensorName(it.trim(), "") }
                                    .filter { it.isNotEmpty() }
                                    .take(6)
                            }
                        }
                        line.startsWith("displayport_alt_mode_status=") -> {
                            dpAltSummary = parseDpAltModeLine(line)
                        }
                    }
                }
            }

            portEntryBraceDepth = (portEntryBraceDepth + openBraces - closeBraces).coerceAtLeast(0)
            if (braceDepthBefore >= 2 && portEntryBraceDepth == 1) {
                currentSection = ""
            } else if (braceDepthBefore >= 1 && portEntryBraceDepth == 0) {
                flushPortIfValid()
            }

            usbPortsBracketDepth += (openBrackets - closeBrackets)
            if (usbPortsBracketDepth <= 0) {
                inUsbPortsArray = false
                break
            }
        }
        return ports
    }

    fun formatUsbSpeed(speedMbps: Float): Pair<String, String> {
        if (!speedMbps.isFinite() || speedMbps <= 0f) return "Unknown" to "Unknown Speed"
        return when {
            speedMbps >= 79_000f -> "80 Gbps" to "USB4 v2 (80 Gbps)"
            speedMbps >= 39_000f -> "40 Gbps" to "USB4 / Thunderbolt (40 Gbps)"
            speedMbps >= 19_000f -> "20 Gbps" to "USB 3.2 Gen 2×2 (20 Gbps)"
            speedMbps >= 9_500f -> "10 Gbps" to "USB 3.2 Gen 2 (10 Gbps)"
            speedMbps >= 4_500f -> "5 Gbps" to "USB 3.2 Gen 1 (5 Gbps)"
            speedMbps >= 400f -> "480 Mbps" to "USB 2.0 High-Speed (480 Mbps)"
            speedMbps >= 10f -> "12 Mbps" to "USB 1.1 Full-Speed (12 Mbps)"
            speedMbps >= 1f -> "1.5 Mbps" to "USB 1.0 Low-Speed (1.5 Mbps)"
            else -> String.format(Locale.US, "%.1f Mbps", speedMbps) to "Custom Link Speed"
        }
    }

    fun usbClassLabel(classCode: Int, subClass: Int = 0, protocol: Int = 0): String = when (classCode and 0xFF) {
        0x00 -> "Composite / Per-Interface"
        0x01 -> when (subClass) {
            0x01 -> "USB Audio Control"
            0x02 -> "USB Audio Streaming"
            0x03 -> "USB MIDI Streaming"
            else -> "USB Audio"
        }
        0x02 -> when (subClass) {
            0x06 -> "CDC Ethernet (ECM)"
            0x0d -> "CDC Network (NCM)"
            0x0e -> "CDC MBIM"
            0x02 -> "CDC Serial (ACM)"
            else -> "Communications (CDC)"
        }
        0x03 -> when (protocol) {
            0x01 -> "HID Boot Keyboard"
            0x02 -> "HID Boot Mouse"
            else -> "Human Interface (HID)"
        }
        0x05 -> "Physical Interface"
        0x06 -> "Still Imaging (PTP/MTP)"
        0x07 -> "Printer"
        0x08 -> if (protocol == 0x62) "Mass Storage (UASP)" else "Mass Storage (BOT)"
        0x09 -> when (protocol) {
            0x03 -> "SuperSpeed USB Hub"
            0x02 -> "USB 2.0 MTT Hub"
            0x01 -> "USB 2.0 STT Hub"
            else -> "USB Hub"
        }
        0x0a -> "CDC Data"
        0x0b -> "Smart Card / CCID"
        0x0d -> "Content Security"
        0x0e -> if (subClass == 0x01) "UVC Video Control" else "UVC Video Streaming"
        0x0f -> "Personal Healthcare"
        0x10 -> "Audio/Video (AV)"
        0x11 -> "USB Billboard (Alt-Mode)"
        0x12 -> "USB Type-C Bridge"
        0xdc -> "Diagnostic Device"
        0xe0 -> "Wireless / Bluetooth HCI"
        0xef -> "Miscellaneous (IAD)"
        0xfe -> if (subClass == 0x01) "Firmware Upgrade (DFU)" else "Application Specific"
        0xff -> "Vendor Specific"
        else -> String.format(Locale.US, "Class 0x%02x", classCode and 0xFF)
    }

    private fun classifyNodeCategory(
        isRootBus: Boolean,
        raw: RawSysfsUsbDevice,
        cleanProduct: String,
        correlated: UsbCorrelatedInfo,
    ): UsbNodeCategory {
        if (isRootBus) return UsbNodeCategory.RootController
        val dCls = raw.deviceClassCode and 0xFF
        val ifaces = raw.interfaces
        val prodLower = cleanProduct.lowercase(Locale.US)
        val drivers = ifaces.map { it.driverName.lowercase(Locale.US) }

        // 1. Standalone USB Billboard Fallback (failed Alt-Mode / TBT negotiation)
        val hasBillboardIface = ifaces.any { it.classCode == 0x11 }
        if (dCls == 0x11 || (ifaces.isNotEmpty() && ifaces.all { it.classCode == 0x11 })) {
            return UsbNodeCategory.Billboard
        }

        // 2. USB Hub
        if (dCls == 0x09 || ifaces.any { it.classCode == 0x09 }) {
            return UsbNodeCategory.Hub
        }

        // 3. Dock management MCU / DisplayPort MST controller / Composite Dock + Billboard
        if (hasBillboardIface ||
            prodLower.contains("dock") ||
            prodLower.contains("tbt4") ||
            prodLower.contains("thunderbolt") ||
            (prodLower.startsWith("vmm") && (raw.vendorId == 0x06cb || raw.vendorId == 0x413c || raw.manufacturer.contains("Synaptics", ignoreCase = true)))
        ) {
            return UsbNodeCategory.Dock
        }

        // 4. Network / Ethernet NICs (CDC-NCM/ECM or vendor Realtek r8152 / ASIX ax88179 / Aqantia)
        if (drivers.any { it in setOf("r8152", "r8153_ecm", "ax88179_178a", "asix", "cdc_ncm", "cdc_ether", "cdc_mbim", "aqc111", "lan78xx", "smsc95xx", "rtl8150") } ||
            ifaces.any { it.classCode == 0x02 && it.subClassCode in setOf(0x06, 0x0d, 0x0e) } ||
            prodLower.contains("10/100") ||
            prodLower.contains("ethernet") ||
            prodLower.contains("2.5g lan") ||
            prodLower.contains("1000 lan")
        ) {
            return UsbNodeCategory.NetworkAdapter
        }

        // 5. Serial / UART Debug Adapters (CH341, FTDI, CP210x, PL2303, CDC-ACM)
        if (drivers.any { it in setOf("ch341", "ch34x", "ftdi_sio", "cp210x", "pl2303", "cdc_acm", "option", "usbserial", "usbserial_generic") } ||
            ifaces.any { it.classCode == 0x02 && it.subClassCode == 0x02 } ||
            prodLower.contains("serial") ||
            prodLower.contains("uart")
        ) {
            return UsbNodeCategory.SerialAdapter
        }

        // 6. Security Keys & Biometric MCUs (FPMCU, YubiKey, CCID Smart Card)
        if (drivers.any { it.contains("cros-ec") || it.contains("fpmcu") } ||
            ifaces.any { it.classCode == 0x0b } ||
            raw.vendorId == 0x1050 ||
            prodLower.contains("fpmcu") ||
            prodLower.contains("fingerprint") ||
            prodLower.contains("yubikey") ||
            prodLower.contains("fido")
        ) {
            return UsbNodeCategory.SecurityBiometric
        }

        // 7. Wireless / Bluetooth HCI Controllers
        if (dCls == 0xe0 || ifaces.any { it.classCode == 0xe0 } || drivers.any { it == "btusb" }) {
            return UsbNodeCategory.WirelessController
        }

        // 8. UVC Video / Webcams
        if (ifaces.any { it.classCode == 0x0e } || drivers.any { it == "uvcvideo" } || correlated.cameraSummary != null) {
            return UsbNodeCategory.VideoWebcam
        }

        // 9. USB Audio / DAC / Headsets
        if (ifaces.any { it.classCode == 0x01 } || drivers.any { it == "snd-usb-audio" } || correlated.audioCapabilitiesSummary != null) {
            return UsbNodeCategory.Audio
        }

        // 10. Mass Storage / NVMe UASP Enclosures
        if (ifaces.any { it.classCode == 0x08 } || drivers.any { it in setOf("usb-storage", "uas") } || correlated.storageVolumeSummary != null) {
            return UsbNodeCategory.Storage
        }

        // 11. Input & HID Peripherals
        val corrInput = correlated.inputSourcesSummary?.lowercase(Locale.US).orEmpty()
        if (corrInput.contains("gamepad") || corrInput.contains("joystick") ||
            prodLower.contains("gamepad") || prodLower.contains("xbox") || prodLower.contains("dualsense") || prodLower.contains("dualshock")
        ) {
            return UsbNodeCategory.GameController
        }
        if (corrInput.contains("keyboard") || ifaces.any { it.classCode == 0x03 && it.protocolCode == 0x01 } || prodLower.contains("keyboard") || prodLower.contains("keychron")) {
            return UsbNodeCategory.Keyboard
        }
        if (corrInput.contains("mouse") || corrInput.contains("pointer") || corrInput.contains("touchpad") || corrInput.contains("stylus") ||
            ifaces.any { it.classCode == 0x03 && it.protocolCode == 0x02 } ||
            prodLower.contains("mouse") || prodLower.contains("trackpad") || prodLower.contains("trackball") || prodLower.contains("receiver")
        ) {
            return UsbNodeCategory.Pointer
        }
        if (ifaces.any { it.classCode == 0x03 }) {
            return UsbNodeCategory.HidControl
        }

        return UsbNodeCategory.Other
    }

    private fun evaluateNodeHealth(
        raw: RawSysfsUsbDevice,
        category: UsbNodeCategory,
        cleanProduct: String,
        isSelfPowered: Boolean,
        downstreamRequestedMa: Int,
        anySuperSpeedActive: Boolean,
    ): Triple<UsbHealthStatus, String?, String?> {
        // 1. Standalone USB Billboard fallback -> DP Alt-Mode / Thunderbolt / USB4 negotiation failed!
        if (category == UsbNodeCategory.Billboard) {
            return Triple(
                UsbHealthStatus.BillboardFallback,
                "USB Billboard Fallback Active (Class 0x11): DisplayPort Alt-Mode, Thunderbolt, or USB4 link negotiation failed. Verify port Alt-Mode support and use a certified SuperSpeed/USB4 cable.",
                null,
            )
        }

        // 2. Bus-powered hub over-subscription warning
        if (category == UsbNodeCategory.Hub && !isSelfPowered) {
            val budgetMa = if (raw.speedMbps >= 5_000f) 900 else 500
            if (downstreamRequestedMa > budgetMa) {
                return Triple(
                    UsbHealthStatus.PowerBudgetWarning,
                    "Bus-Powered Hub Over-Subscription: Downstream devices request $downstreamRequestedMa mA across a $budgetMa mA unpowered hub budget. Connect external PD power to prevent brownouts or disconnects.",
                    null,
                )
            }
        }

        // 3. SuperSpeed capability downgrade (bcdUSB >= 3.00 or known SuperSpeed GbE/2.5GbE running at <= 480 Mbps)
        val bcdFloat = raw.usbVersionBcd.trim().toFloatOrNull() ?: 2.0f
        val prodLower = cleanProduct.lowercase(Locale.US)
        val isKnownSuperSpeedNic = category == UsbNodeCategory.NetworkAdapter && (
            (raw.vendorId to raw.productId) in SUPER_SPEED_NIC_VID_PIDS ||
                prodLower.contains("1000") ||
                prodLower.contains("2.5g") ||
                prodLower.contains("5g")
            )
        if (category != UsbNodeCategory.RootController &&
            raw.speedMbps in 1f..480f &&
            (bcdFloat >= 3.0f || isKnownSuperSpeedNic)
        ) {
            val speedStr = formatUsbSpeed(raw.speedMbps).first
            return Triple(
                UsbHealthStatus.SpeedBottleneck,
                "SuperSpeed Downgrade: Device supports USB ${raw.usbVersionBcd.trim().ifEmpty { "3.x" }} (5+ Gbps) but negotiated $speedStr. Inspect for a USB 2.0 charge-only cable, a USB 2.0 hub port, or a 4-lane DisplayPort Alt-Mode pin split.",
                null,
            )
        }

        // 4. Composite Dock MCU with passive Billboard interface
        val hasPassiveBillboard = raw.interfaces.any { it.classCode == 0x11 }
        val passiveNote = if (hasPassiveBillboard) {
            if (anySuperSpeedActive) {
                "Dock exposes a passive USB Billboard (0x11) management descriptor alongside active SuperSpeed dock functions."
            } else {
                "Dock exposes a USB Billboard (0x11) descriptor and no SuperSpeed (5G+) links are currently active on the host."
            }
        } else {
            null
        }

        return Triple(UsbHealthStatus.Healthy, null, passiveNote)
    }

    private fun resolveCompanionPairs(nodes: List<UsbTopologyNode>): Map<String, String> {
        val pairs = HashMap<String, String>()

        // 1. Companion xHCI Root Buses sharing the same PCI BDF (e.g. usb1 @ 480M <-> usb2 @ 20G on 00:0d.0)
        val rootsByPci = nodes.filter { it.isRootController && it.pciControllerLabel.isNotBlank() }
            .groupBy { it.pciControllerLabel }
        for ((_, group) in rootsByPci) {
            val usb2Root = group.firstOrNull { it.negotiatedSpeedMbps in 1f..480f }
            val usb3Root = group.firstOrNull { it.negotiatedSpeedMbps >= 5_000f }
            if (usb2Root != null && usb3Root != null) {
                pairs[usb2Root.id] = usb3Root.id
                pairs[usb3Root.id] = usb2Root.id
            }
        }

        // 2. Companion USB 2.0 + USB 3.x External Hubs at the same tree depth from the same vendor
        val extHubs = nodes.filter { !it.isInternal && it.category == UsbNodeCategory.Hub }
        val hsHubs = extHubs.filter { it.negotiatedSpeedMbps in 1f..480f }
        val ssHubs = extHubs.filter { it.negotiatedSpeedMbps >= 5_000f }.toMutableList()
        for (hs in hsHubs) {
            val match = ssHubs.firstOrNull { ss ->
                ss.depth == hs.depth &&
                    ss.vendorId == hs.vendorId &&
                    (ss.productId == hs.productId || abs(ss.productId - hs.productId) in setOf(0x5000, 0x0100, 0x0001, 0x0300))
            }
            if (match != null) {
                ssHubs.remove(match)
                pairs[hs.id] = match.id
                pairs[match.id] = hs.id
            }
        }
        return pairs
    }

    private fun extractRootPortAncestorId(id: String): String? {
        val m = USB_DEV_NAME_REGEX.matchEntire(id) ?: return null
        val bus = m.groupValues[1]
        val firstPort = m.groupValues[2].substringBefore('.')
        return "$bus-$firstPort"
    }

    private fun resolveParentNodeId(id: String): String? {
        if (USB_BUS_NAME_REGEX.matches(id)) return null
        val m = USB_DEV_NAME_REGEX.matchEntire(id) ?: return null
        val bus = m.groupValues[1]
        val chain = m.groupValues[2]
        return if ('.' in chain) {
            "$bus-${chain.substringBeforeLast('.')}"
        } else {
            "usb$bus"
        }
    }

    private fun computeNodeDepth(id: String): Int {
        if (USB_BUS_NAME_REGEX.matches(id)) return 0
        val m = USB_DEV_NAME_REGEX.matchEntire(id) ?: return 1
        return 1 + m.groupValues[2].count { it == '.' }
    }

    private fun extractPciBdf(canonicalPath: String): String {
        val lastMatch = PCI_BDF_REGEX.findAll(canonicalPath).lastOrNull() ?: return ""
        return "PCI ${lastMatch.groupValues[1].lowercase(Locale.US)}"
    }

    private fun parseDpAltModeLine(line: String): String? {
        val partner = DP_ALT_PARTNER_REGEX.find(line)?.groupValues?.getOrNull(1)?.lowercase(Locale.US).orEmpty()
        val lanes = DP_ALT_LANES_REGEX.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val training = DP_ALT_TRAINING_REGEX.find(line)?.groupValues?.getOrNull(1)?.lowercase(Locale.US).orEmpty()
        if ((partner.isEmpty() || partner == "unknown" || partner == "not-connected") && lanes <= 0) {
            return null
        }
        val parts = ArrayList<String>(3)
        parts += if (lanes > 0) "DP Alt-Mode ($lanes-Lane)" else "DP Alt-Mode"
        if (training.isNotEmpty() && training != "unknown") {
            parts += "Link: ${formatRoleToken(training)}"
        }
        return parts.joinToString(" · ")
    }

    private fun formatPortModeLabel(raw: String): String = when (raw.lowercase(Locale.US)) {
        "dual", "drp" -> "Dual-Role (DRP)"
        "dfp" -> "Host-Only (DFP)"
        "ufp" -> "Device-Only (UFP)"
        else -> formatRoleToken(raw)
    }

    private fun formatRoleToken(raw: String): String {
        val clean = SysfsParsers.sanitizeSensorName(raw.trim(), "Unknown")
        return when (clean.lowercase(Locale.US)) {
            "dfp" -> "DFP"
            "ufp" -> "UFP"
            "host" -> "Host"
            "device" -> "Device"
            "source" -> "Source"
            "sink" -> "Sink"
            "enabled" -> "Enabled"
            "disabled" -> "Disabled"
            "unknown" -> "Unknown"
            "none", "" -> "None"
            else -> clean.replace('-', ' ').replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString()
            }
        }
    }

    private fun formatDurationSec(totalSec: Long): String {
        val hrs = totalSec / 3_600L
        val mins = (totalSec % 3_600L) / 60L
        val secs = totalSec % 60L
        return when {
            hrs > 0L -> "${hrs}h ${mins}m"
            mins > 0L -> "${mins}m ${secs}s"
            else -> "${secs}s"
        }
    }

    private fun countChar(s: String, ch: Char): Int {
        var count = 0
        for (c in s) if (c == ch) count++
        return count
    }

    private fun readSmallAttr(dir: File, name: String): String? {
        return try {
            val f = File(dir, name)
            if (!f.isFile || !f.canRead()) return null
            f.inputStream().use { input ->
                val buf = ByteArray(256)
                val n = input.read(buf)
                if (n <= 0) null else String(buf, 0, n, Charsets.UTF_8).trim()
            }
        } catch (_: Exception) {
            null
        }
    }

    private val USB_NODE_COMPARATOR = Comparator<UsbTopologyNode> { a, b ->
        val busCmp = a.busNumber.compareTo(b.busNumber)
        if (busCmp != 0) return@Comparator busCmp
        if (a.isRootController != b.isRootController) {
            return@Comparator if (a.isRootController) -1 else 1
        }
        val segsA = extractPortSegments(a.id)
        val segsB = extractPortSegments(b.id)
        val minLen = minOf(segsA.size, segsB.size)
        for (i in 0 until minLen) {
            val c = segsA[i].compareTo(segsB[i])
            if (c != 0) return@Comparator c
        }
        segsA.size.compareTo(segsB.size)
    }

    private fun extractPortSegments(id: String): List<Int> {
        val m = USB_DEV_NAME_REGEX.matchEntire(id) ?: return emptyList()
        return m.groupValues[2].split('.').map { it.toIntOrNull() ?: 0 }
    }
}

/**
 * Bounded, in-memory 64-event USB hotplug & bottleneck transition tracker.
 * Never persists USB identifiers or device history to disk, honoring Corewatch's strict privacy policy.
 */
class UsbHotplugTracker(private val capacity: Int = 64) {
    private data class TrackedNodeState(
        val id: String,
        val displayTitle: String,
        val vidPidHex: String,
        val speedBadgeLabel: String,
        val isInternal: Boolean,
        val healthStatus: UsbHealthStatus,
        val healthMessage: String?,
    )

    private var hasBaseline: Boolean = false
    private val previousNodes = LinkedHashMap<String, TrackedNodeState>()
    private val events = ArrayDeque<UsbHotplugEvent>(capacity)

    @Synchronized
    fun recordSample(
        nodes: List<UsbTopologyNode>,
        nowElapsedMs: Long,
        wallClockMs: Long = System.currentTimeMillis(),
    ): List<UsbHotplugEvent> {
        val currentMap = LinkedHashMap<String, TrackedNodeState>()
        for (node in nodes) {
            if (node.isRootController) continue
            currentMap[node.id] = TrackedNodeState(
                id = node.id,
                displayTitle = node.displayTitle,
                vidPidHex = node.vidPidHex,
                speedBadgeLabel = node.speedBadgeLabel,
                isInternal = node.isInternal,
                healthStatus = node.healthStatus,
                healthMessage = node.healthMessage,
            )
        }

        val tsLabel = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(wallClockMs))

        if (!hasBaseline) {
            hasBaseline = true
            previousNodes.clear()
            previousNodes.putAll(currentMap)
            // Surface active hardware warnings immediately even on initial launch so bottlenecks stand out in the log
            for (cur in currentMap.values) {
                if (!cur.isInternal && cur.healthStatus.isWarning) {
                    pushEvent(
                        UsbHotplugEvent(
                            timestampLabel = tsLabel,
                            elapsedRealtimeMs = nowElapsedMs,
                            nodeId = cur.id,
                            deviceName = cur.displayTitle,
                            vidPidHex = cur.vidPidHex,
                            speedBadgeLabel = cur.speedBadgeLabel,
                            action = warningActionFor(cur.healthStatus),
                            detail = cur.healthMessage ?: cur.healthStatus.badgeLabel,
                        ),
                    )
                }
            }
            return snapshotEvents()
        }

        // 1. Detect detached devices
        for ((id, prev) in previousNodes) {
            if (!currentMap.containsKey(id) && !prev.isInternal) {
                pushEvent(
                    UsbHotplugEvent(
                        timestampLabel = tsLabel,
                        elapsedRealtimeMs = nowElapsedMs,
                        nodeId = id,
                        deviceName = prev.displayTitle,
                        vidPidHex = prev.vidPidHex,
                        speedBadgeLabel = prev.speedBadgeLabel,
                        action = UsbHotplugAction.Detached,
                        detail = "Disconnected from port $id (${prev.vidPidHex})",
                    ),
                )
            }
        }

        // 2. Detect newly attached devices & new warning transitions
        for ((id, cur) in currentMap) {
            val prev = previousNodes[id]
            if (prev == null && !cur.isInternal) {
                pushEvent(
                    UsbHotplugEvent(
                        timestampLabel = tsLabel,
                        elapsedRealtimeMs = nowElapsedMs,
                        nodeId = id,
                        deviceName = cur.displayTitle,
                        vidPidHex = cur.vidPidHex,
                        speedBadgeLabel = cur.speedBadgeLabel,
                        action = UsbHotplugAction.Attached,
                        detail = "Negotiated ${cur.speedBadgeLabel} on port $id (${cur.vidPidHex})",
                    ),
                )
                if (cur.healthStatus.isWarning) {
                    pushEvent(
                        UsbHotplugEvent(
                            timestampLabel = tsLabel,
                            elapsedRealtimeMs = nowElapsedMs,
                            nodeId = id,
                            deviceName = cur.displayTitle,
                            vidPidHex = cur.vidPidHex,
                            speedBadgeLabel = cur.speedBadgeLabel,
                            action = warningActionFor(cur.healthStatus),
                            detail = cur.healthMessage ?: cur.healthStatus.badgeLabel,
                        ),
                    )
                }
            } else if (prev != null && !cur.isInternal && cur.healthStatus.isWarning && prev.healthStatus != cur.healthStatus) {
                pushEvent(
                    UsbHotplugEvent(
                        timestampLabel = tsLabel,
                        elapsedRealtimeMs = nowElapsedMs,
                        nodeId = id,
                        deviceName = cur.displayTitle,
                        vidPidHex = cur.vidPidHex,
                        speedBadgeLabel = cur.speedBadgeLabel,
                        action = warningActionFor(cur.healthStatus),
                        detail = cur.healthMessage ?: cur.healthStatus.badgeLabel,
                    ),
                )
            }
        }

        previousNodes.clear()
        previousNodes.putAll(currentMap)
        return snapshotEvents()
    }

    @Synchronized
    fun reset() {
        hasBaseline = false
        previousNodes.clear()
        events.clear()
    }

    private fun pushEvent(event: UsbHotplugEvent) {
        if (events.size >= capacity) {
            events.removeLast()
        }
        events.addFirst(event)
    }

    private fun snapshotEvents(): List<UsbHotplugEvent> = events.toList()

    private fun warningActionFor(status: UsbHealthStatus): UsbHotplugAction = when (status) {
        UsbHealthStatus.BillboardFallback -> UsbHotplugAction.BillboardAlert
        UsbHealthStatus.PowerBudgetWarning -> UsbHotplugAction.PowerBudgetAlert
        else -> UsbHotplugAction.BottleneckDetected
    }
}
