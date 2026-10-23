package com.junxi.padka

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper

/** 定位：优先取系统最近一次位置，取不到再现场等一次（GPS/网络双通道）。 */
object LocHelper {

    class Loc(val lat: Double, val lng: Double)

    fun hasPermission(c: Context): Boolean =
        c.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED ||
            c.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED

    /** 阻塞式取位置（在线程里调用）。timeoutMs 内取不到返回 null。 */
    fun getBest(c: Context, timeoutMs: Long = 15000): Loc? {
        val lm = c.getSystemService(Context.LOCATION_SERVICE) as LocationManager? ?: return null
        if (!hasPermission(c)) return null

        // 1) 最近已知位置（可能刚有其他 App 用过 GPS）
        var best: Location? = null
        for (prov in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            try {
                val l = lm.getLastKnownLocation(prov) ?: continue
                if (best == null || l.time > best!!.time) best = l
            } catch (_: SecurityException) {
            } catch (_: Exception) {
            }
        }
        // 最近位置超过 10 分钟就再现场刷新一次
        if (best != null && System.currentTimeMillis() - best.time < 10 * 60_000L) {
            return Loc(best.latitude, best.longitude)
        }

        // 2) 现场请求一次
        val latch = java.util.concurrent.CountDownLatch(1)
        val holder = arrayOfNulls<Location>(1)
        val listener = object : LocationListener {
            override fun onLocationChanged(l: Location) {
                holder[0] = l
                latch.countDown()
            }
            override fun onStatusChanged(p: String, s: Int, b: Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }
        var requested = false
        for (prov in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (lm.isProviderEnabled(prov)) {
                    lm.requestLocationUpdates(prov, 0L, 0f, listener, Looper.getMainLooper())
                    requested = true
                }
            } catch (_: Exception) {
            }
        }
        if (requested) {
            try {
                latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
            }
            for (prov in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                try {
                    lm.removeUpdates(listener)
                } catch (_: Exception) {
                }
            }
            holder[0]?.let { return Loc(it.latitude, it.longitude) }
        }
        // 3) 退回旧的最近位置（有总比没有强）
        return best?.let { Loc(it.latitude, it.longitude) }
    }
}
