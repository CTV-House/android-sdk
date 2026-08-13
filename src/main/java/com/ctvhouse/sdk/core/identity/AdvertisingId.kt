package com.ctvhouse.sdk.core.identity

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.provider.Settings
import androidx.annotation.VisibleForTesting
import com.ctvhouse.sdk.core.runtime.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The advertising ID a device is willing to give out, if any. */
internal data class AdId(
    val value: String,
    val type: String,
    val limitAdTracking: Boolean,
)

/** Seam for tests: reading an ID needs a live device service. */
internal fun interface AdIdSource {
    fun resolve(context: Context): AdId?
}

/**
 * Reads the resettable advertising ID the platform offers.
 *
 * Talks to the Play Services identifier service directly instead of depending on
 * `play-services-ads-identifier`: an ad library has no business dictating a GMS version to the
 * host, and half of the TV boxes this runs on have no Play Services at all. Devices without the
 * service fall back to the `Settings.Secure` pair Amazon publishes, and a device with neither
 * simply has no ID — the tag is then sent without one.
 *
 * Blocking. Bind callbacks arrive on the main looper, so this must run on a background thread:
 * calling it from the main thread would wait for a message the same thread has to deliver.
 */
internal object AdvertisingId : AdIdSource {

    override fun resolve(context: Context): AdId? =
        fromPlayServices(context) ?: fromSecureSettings(context)

    private fun fromPlayServices(context: Context): AdId? {
        val intent = Intent(GMS_ACTION).setPackage(GMS_PACKAGE)
        val connection = IdConnection()
        val bound = try {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (t: Throwable) {
            Log.i(TAG, "advertising id service refused the bind: ${t.javaClass.simpleName}")
            false
        }
        if (!bound) {
            unbind(context, connection)
            return null
        }
        return try {
            val binder = connection.awaitBinder() ?: return null
            val id = transactString(binder, TRANSACTION_GET_ID)
            val limited = transactBoolean(binder, TRANSACTION_GET_LIMIT)
            adId(id, TYPE_GAID, limited)
        } catch (t: Throwable) {
            Log.i(TAG, "advertising id unavailable: ${t.javaClass.simpleName}")
            null
        } finally {
            unbind(context, connection)
        }
    }

    /** Fire TV and other Amazon devices publish the same pair as system settings. */
    private fun fromSecureSettings(context: Context): AdId? = try {
        val resolver = context.contentResolver
        val id = Settings.Secure.getString(resolver, SETTING_ID)
        val limited = Settings.Secure.getInt(resolver, SETTING_LIMIT, 0) != 0
        adId(id, TYPE_AMAZON, limited)
    } catch (t: Throwable) {
        Log.i(TAG, "secure settings hold no advertising id: ${t.javaClass.simpleName}")
        null
    }

    /**
     * An opted-out device answers with a zeroed ID. Passing that on would look like a real
     * audience of one to every ad server, so it is dropped and only the opt-out is reported.
     */
    @VisibleForTesting
    internal fun adId(raw: String?, type: String, limited: Boolean): AdId? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (limited || value == ZEROED_ID) return AdId("", "", limitAdTracking = true)
        return AdId(value, type, limitAdTracking = false)
    }

    private fun transactString(binder: IBinder, code: Int): String? =
        transact(binder, code, writeLimitFlag = false) { it.readString() }

    private fun transactBoolean(binder: IBinder, code: Int): Boolean =
        transact(binder, code, writeLimitFlag = true) { it.readInt() != 0 } ?: false

    private fun <R> transact(
        binder: IBinder,
        code: Int,
        writeLimitFlag: Boolean,
        read: (Parcel) -> R,
    ): R? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(GMS_INTERFACE)
            if (writeLimitFlag) data.writeInt(1)
            binder.transact(code, data, reply, 0)
            reply.readException()
            read(reply)
        } catch (t: Throwable) {
            Log.i(TAG, "advertising id call failed: ${t.javaClass.simpleName}")
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun unbind(context: Context, connection: ServiceConnection) {
        try {
            context.unbindService(connection)
        } catch (_: Throwable) {
            // Never bound, or already gone: nothing to release.
        }
    }

    private class IdConnection : ServiceConnection {
        private val binders = LinkedBlockingQueue<IBinder>(1)

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service != null) binders.offer(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) = Unit

        fun awaitBinder(): IBinder? = binders.poll(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    private const val TAG = "AdvertisingId"
    private const val GMS_PACKAGE = "com.google.android.gms"
    private const val GMS_ACTION = "com.google.android.gms.ads.identifier.service.START"
    private const val GMS_INTERFACE =
        "com.google.android.gms.ads.identifier.internal.IAdvertisingIdService"
    private const val TRANSACTION_GET_ID = 1
    private const val TRANSACTION_GET_LIMIT = 2
    private const val SETTING_ID = "advertising_id"
    private const val SETTING_LIMIT = "limit_ad_tracking"
    private const val ZEROED_ID = "00000000-0000-0000-0000-000000000000"
    private const val BIND_TIMEOUT_MS = 2_000L
    private const val TYPE_GAID = "gaid"
    private const val TYPE_AMAZON = "afai"
}
