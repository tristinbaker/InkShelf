package com.tristinbaker.inkshelf.core.eink

import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException

/**
 * Hand-rolled client for `android.meink.IMeinkService`, a hidden Mudita binder
 * with no public SDK. Marshalling order is fixed by the AIDL signature
 * `void setDisplayMode(String packageName, int mode)`:
 *
 *   1. writeInterfaceToken
 *   2. writeString(packageName)
 *   3. writeInt(mode)
 *   4. transact(TRANSACTION_SET_DISPLAY_MODE, ..., flags = 0)
 *   5. readException
 *
 * Every failure mode throws; the controller turns that into `isAvailable =
 * false` rather than propagating, because on any non-Kompakt device the
 * service simply does not exist.
 */
internal class MeinkBinder(private val remote: IBinder) {

    fun setDisplayMode(packageName: String, mode: Int) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeString(packageName)
            data.writeInt(mode)
            remote.transact(TRANSACTION_SET_DISPLAY_MODE, data, reply, 0)
            reply.readException()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    companion object {
        const val SERVICE_NAME = "meink"
        const val DESCRIPTOR = "android.meink.IMeinkService"
        const val TRANSACTION_SET_DISPLAY_MODE = 5
    }
}

/** Thrown when the panel rejects a mode outright, as opposed to the service being absent. */
class MeinkTransactionException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
