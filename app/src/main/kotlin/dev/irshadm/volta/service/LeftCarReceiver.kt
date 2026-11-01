package dev.irshadm.volta.service

import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.IntentCompat
import dagger.hilt.android.EntryPointAccessors

/**
 * "You just left the car": the phone's Bluetooth link to a car head unit (car audio or hands-free class) dropped.
 * That's the moment the parked alerts matter (unlocked, window open, lights, left on), so it schedules the
 * left-car checks instead of waiting for the periodic worker. The car's own disconnect (Android Auto ending,
 * the Volta car session closing) reaches [CarCheckWorker.leftCar] by other paths; the one-shot names dedupe them.
 */
class LeftCarReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_DISCONNECTED) return
        val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
        // Needs BLUETOOTH_CONNECT on Android 12+; without it we can't tell a car from earbuds, so do nothing.
        val kind = runCatching { device.bluetoothClass?.deviceClass }.getOrNull() ?: return
        if (kind != BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO && kind != BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE) return
        // Only worth a read if the car may just have been driven: it was on at the last reading, or we haven't looked for a while.
        val status = EntryPointAccessors.fromApplication(context, CarCheckWorker.Deps::class.java).ismart().status.value
        val lastSeen = status?.ignitionOn?.atMillis ?: 0L
        if (status?.ignitionOn?.value == true || System.currentTimeMillis() - lastSeen > 20 * 60_000) CarCheckWorker.leftCar(context)
    }
}
