package com.ctvhouse.sdk.core.identity

/**
 * What the library knows about the device and the app around it.
 *
 * A snapshot rather than a set of live getters: the tag request and every tracker earned by the
 * show it answered describe the same device, even if the viewer switched network in between.
 * Numeric codes follow OpenRTB, which is what ad servers expect behind these macros.
 */
internal data class Device(
    /** Advertising ID, empty when the platform has none or the viewer opted out. */
    val ifa: String = "",
    /** Source of [ifa] — `gaid` on Google devices, `afai` on Amazon, empty without one. */
    val ifaType: String = "",
    /** Viewer asked not to be tracked (or no resettable ID is available at all). */
    val limitAdTracking: Boolean = true,
    val userAgent: String = "",
    /** Host-supplied; the library cannot see the public address from inside the app. */
    val ip: String = "",
    val make: String = "",
    val model: String = "",
    val osVersion: String = "",
    val type: DeviceType = DeviceType.PHONE,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    val language: String = "",
    val connection: Connection = Connection.UNKNOWN,
    val appBundle: String = "",
    val appName: String = "",
    val appVersion: String = "",
)

/** OpenRTB `device.devicetype`. */
internal enum class DeviceType(val rtbCode: Int) {
    PHONE(4),
    TABLET(5),
    TV(3),
}

/** OpenRTB `device.connectiontype`. */
internal enum class Connection(val rtbCode: Int) {
    UNKNOWN(0),
    ETHERNET(1),
    WIFI(2),
    CELLULAR(3),
}
