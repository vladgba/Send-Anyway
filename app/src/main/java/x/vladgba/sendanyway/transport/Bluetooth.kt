package x.vladgba.sendanyway.transport

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Shared Bluetooth helpers (the transport itself is RFCOMM, see Classic.kt). */
object Bluetooth {
    /** Runtime permissions (sender = server, receiver = client). */
    fun permissions(server: Boolean): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            // ADVERTISE also covers the "make this phone visible" request.
            if (server) arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
            else arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        // Before Android 12, classic discovery needs Location.
        else ->
            if (server) emptyArray()
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    fun hasPermissions(ctx: Context, server: Boolean) = permissions(server).all {
        ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    fun adapter(ctx: Context): BluetoothAdapter? =
        ctx.getSystemService(BluetoothManager::class.java)?.adapter?.takeIf { it.isEnabled }
}
