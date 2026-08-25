package com.diegonmarcos.superapp.core

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Service half of [IDataBackend]. An engine library's APK subclasses this and
 * implements [dispatch]; everything else - the binder, the error contract, the
 * method list - is shared.
 *
 * [dispatch] is a plain function of (method, args) to JSON, deliberately NOT
 * reflection: reflective dispatch is exactly what R8 cannot see, and this app
 * now ships with R8 on. A `when` block keeps the call graph visible.
 */
abstract class DataBackendService : Service() {

    /** Answer [method], or throw - throwing is caught and returned as JSON. */
    protected abstract fun dispatch(method: String, args: Array<String>): String

    /** Every method [dispatch] answers. Used by clients to degrade knowingly. */
    protected abstract fun methodNames(): Array<String>

    private val binder = object : IDataBackend.Stub() {
        override fun call(method: String?, args: Array<out String>?): String =
            runCatching { dispatch(method.orEmpty(), args?.map { it }?.toTypedArray() ?: emptyArray()) }
                .getOrElse { t ->
                    // Never let an exception cross the binder: on the far side
                    // it arrives as a bare DeadObjectException with nothing a
                    // user could be shown.
                    """{"error":"${(t.message ?: t.toString()).replace("\"", "'")}"}"""
                }

        override fun methods(): Array<String> = methodNames()
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
