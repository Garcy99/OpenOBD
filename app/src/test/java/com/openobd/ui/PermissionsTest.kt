package com.openobd.ui

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Which permissions an adapter needs on each Android version. */
@RunWith(AndroidJUnit4::class)
class PermissionsTest {
    /** Android 6-11 (most car head units): a paired classic adapter needs nothing, a BLE scan needs location. */
    @Test
    @Config(sdk = [23])
    fun oldAndroidOnlyBleNeedsLocation() {
        assertTrue(Permissions.bluetooth(ble = false).isEmpty())
        assertTrue(Manifest.permission.ACCESS_FINE_LOCATION in Permissions.bluetooth(ble = true))
    }

    @Test
    @Config(sdk = [34])
    fun android12AndNewerUseBluetoothPermissions() {
        val expected = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        assertArrayEquals(expected, Permissions.bluetooth(ble = false))
        assertArrayEquals(expected, Permissions.bluetooth(ble = true))
    }
}
