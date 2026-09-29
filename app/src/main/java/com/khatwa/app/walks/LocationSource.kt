package com.khatwa.app.walks

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import com.khatwa.app.debug.DebugHooks
import com.khatwa.app.permissions.PermissionChecks
import com.khatwa.core.geo.LatLon
import com.khatwa.core.walk.Fix
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Where GPS readings come from: the phone's location service, or a fake one in tests. */
interface LocationSource {
    /** Starts continuous readings (a walk). Returns false without permission or a provider. */
    fun start(onFix: (Fix) -> Unit): Boolean
    fun stop()
    /** A recent position without waiting (null if none is known). */
    fun lastKnown(): Fix?
    /** One current position, waiting up to [timeoutMs] (null: no permission, no fix in time). */
    suspend fun current(timeoutMs: Long): Fix?

    companion object {
        fun create(context: Context): LocationSource = DebugHooks.fakeLocationSource(context) ?: SystemLocationSource(context.applicationContext)
    }
}

/** Android's own location service (no Google Play services needed). */
class SystemLocationSource(private val context: Context) : LocationSource {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var listener: LocationListener? = null

    private fun Location.toFix() = Fix(LatLon(latitude, longitude), if (hasAccuracy()) accuracy else 50f, time)

    @SuppressLint("MissingPermission")
    override fun start(onFix: (Fix) -> Unit): Boolean {
        if (!PermissionChecks.location(context)) return false
        stop()
        val l = LocationListener { onFix(it.toFix()) }
        val providers = lm.getProviders(true)
        val provider = when {
            LocationManager.GPS_PROVIDER in providers -> LocationManager.GPS_PROVIDER
            LocationManager.FUSED_PROVIDER in providers -> LocationManager.FUSED_PROVIDER
            else -> return false
        }
        lm.requestLocationUpdates(provider, 2_000L, 0f, l, Looper.getMainLooper())
        listener = l
        return true
    }

    override fun stop() {
        listener?.let { lm.removeUpdates(it) }
        listener = null
    }

    @SuppressLint("MissingPermission")
    override fun lastKnown(): Fix? {
        if (!PermissionChecks.location(context)) return null
        return lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }?.toFix()
    }

    @SuppressLint("MissingPermission")
    override suspend fun current(timeoutMs: Long): Fix? {
        if (!PermissionChecks.location(context)) return null
        lastKnown()?.takeIf { System.currentTimeMillis() - it.timeMs < 10 * 60_000 }?.let { return it }
        val providers = lm.getProviders(true)
        val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { it in providers } ?: return lastKnown()
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val l = object : LocationListener {
                    override fun onLocationChanged(loc: Location) {
                        lm.removeUpdates(this)
                        if (cont.isActive) cont.resume(loc.toFix())
                    }
                }
                lm.requestLocationUpdates(provider, 0L, 0f, l, Looper.getMainLooper())
                cont.invokeOnCancellation { lm.removeUpdates(l) }
            }
        } ?: lastKnown()
    }
}
