package com.ctvhouse.sdk.core.identity

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.DisplayMetrics
import androidx.annotation.RequiresApi
import com.ctvhouse.sdk.core.runtime.Log
import java.util.Locale

/**
 * Builds and owns the [Device] snapshot the rest of the library reports with.
 *
 * Construction reads only what is already in the process. Anything that crosses a binder — the
 * advertising ID above all — waits for [refresh], which runs on a background thread before the
 * first request is built. Until it has, the snapshot carries no ID and says tracking is limited:
 * the honest answer while we do not know.
 *
 * Reads are lock-free on purpose: macros are expanded on the main thread and on both worker
 * pools, and none of those may wait on a device lookup.
 */
internal class Identity(
    private val appContext: Context,
    private val adIdSource: AdIdSource = AdvertisingId,
) {
    private val lock = Any()

    /** What the platform said, before host overrides. */
    private var platform: Device = readLocal()

    private var ifaOverride: String = ""
    private var ipOverride: String = ""

    @Volatile
    private var snapshot: Device = platform

    private var resolved = false

    fun current(): Device = snapshot

    /**
     * Fills in everything that has to cross a binder — the advertising ID, the app label and
     * version, the connection type, whether this is a TV. Blocking; safe to call twice, the work
     * happens once.
     */
    fun refresh() {
        synchronized(lock) {
            if (resolved) return
            resolved = true
        }
        val adId = try {
            adIdSource.resolve(appContext)
        } catch (t: Throwable) {
            Log.w(TAG, "advertising id lookup failed: ${t.javaClass.simpleName}")
            null
        }
        val label = appName()
        val version = appVersion()
        val connection = connection()
        val type = deviceType()
        synchronized(lock) {
            platform = readLocal().copy(
                ifa = adId?.value.orEmpty(),
                ifaType = adId?.type.orEmpty(),
                limitAdTracking = adId?.limitAdTracking ?: true,
                type = type,
                connection = connection,
                appName = label,
                appVersion = version,
            )
            recompose()
        }
        val device = snapshot
        Log.i(
            TAG,
            "device type=${device.type} ifa=${device.ifaType.ifEmpty { "none" }} " +
                "lmt=${device.limitAdTracking} connection=${device.connection}",
        )
    }

    /**
     * Advertising ID from the host. Wins over the platform lookup: a host that passes one has
     * consent it can prove, which the library cannot see from here.
     */
    fun setIfa(value: String) {
        synchronized(lock) {
            ifaOverride = value.trim()
            recompose()
        }
    }

    /** Client IP from the host. The library cannot see the public address from inside the app. */
    fun setIp(value: String) {
        synchronized(lock) {
            ipOverride = value.trim()
            recompose()
        }
    }

    private fun recompose() {
        val base = platform
        val ifa = ifaOverride
        snapshot = base.copy(
            ifa = ifa.ifEmpty { base.ifa },
            ifaType = if (ifa.isEmpty()) base.ifaType else TYPE_HOST,
            limitAdTracking = if (ifa.isEmpty()) base.limitAdTracking else false,
            ip = ipOverride,
        )
    }

    /**
     * The half of the snapshot that costs nothing: process constants and in-memory resources.
     *
     * A format is constructed on the main thread, and the app label, the package version, the
     * connection type and the TV check are all binder calls — those wait for [refresh], which runs
     * before the first request is built.
     */
    private fun readLocal(): Device = Device(
        limitAdTracking = true,
        userAgent = userAgent(),
        make = Build.MANUFACTURER.orEmpty(),
        model = Build.MODEL.orEmpty(),
        osVersion = Build.VERSION.RELEASE.orEmpty(),
        widthPx = metric { it.widthPixels },
        heightPx = metric { it.heightPixels },
        language = Locale.getDefault().language.orEmpty(),
        appBundle = appContext.packageName.orEmpty(),
    )

    private fun userAgent(): String =
        System.getProperty("http.agent")?.takeIf { it.isNotBlank() } ?: FALLBACK_USER_AGENT

    private fun deviceType(): DeviceType = try {
        val uiMode = appContext.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) {
            DeviceType.TV
        } else if (appContext.resources.configuration.smallestScreenWidthDp >= TABLET_MIN_DP) {
            DeviceType.TABLET
        } else {
            DeviceType.PHONE
        }
    } catch (_: Throwable) {
        DeviceType.PHONE
    }

    /**
     * `ACCESS_NETWORK_STATE` ships with the library, but a host is free to strip it from the
     * merged manifest — then the answer is simply unknown instead of an exception.
     */
    private fun connection(): Connection = try {
        val manager =
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        when {
            manager == null -> Connection.UNKNOWN
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> capabilityConnection(manager)
            else -> legacyConnection(manager)
        }
    } catch (t: Throwable) {
        Log.i(TAG, "connection type unavailable: ${t.javaClass.simpleName}")
        Connection.UNKNOWN
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun capabilityConnection(manager: ConnectivityManager): Connection {
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
            ?: return Connection.UNKNOWN
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Connection.ETHERNET
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Connection.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Connection.CELLULAR
            else -> Connection.UNKNOWN
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyConnection(manager: ConnectivityManager): Connection =
        when (manager.activeNetworkInfo?.type) {
            ConnectivityManager.TYPE_ETHERNET -> Connection.ETHERNET
            ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_WIMAX -> Connection.WIFI
            ConnectivityManager.TYPE_MOBILE -> Connection.CELLULAR
            else -> Connection.UNKNOWN
        }

    private fun appName(): String = try {
        appContext.packageManager.getApplicationLabel(appContext.applicationInfo).toString()
    } catch (_: Throwable) {
        ""
    }

    private fun appVersion(): String = try {
        @Suppress("DEPRECATION")
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName.orEmpty()
    } catch (_: PackageManager.NameNotFoundException) {
        ""
    } catch (_: Throwable) {
        ""
    }

    private inline fun metric(pick: (DisplayMetrics) -> Int): Int = try {
        pick(appContext.resources.displayMetrics)
    } catch (_: Throwable) {
        0
    }

    private companion object {
        const val TAG = "Identity"
        const val TYPE_HOST = "host"
        const val FALLBACK_USER_AGENT = "Dalvik"
        const val TABLET_MIN_DP = 600
    }
}
