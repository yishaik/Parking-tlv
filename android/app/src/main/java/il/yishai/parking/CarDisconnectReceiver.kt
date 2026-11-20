package il.yishai.parking

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * When the Bluetooth device the user marked as "my car" disconnects (engine off / leaving the car),
 * save the current location as the last parking spot. Needs background location ("Allow all the time").
 */
class CarDisconnectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_DISCONNECTED) return
        val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
        val store = LastSpotStore(context)
        val car = store.carDeviceAddress ?: return
        if (!device.address.equals(car, ignoreCase = true)) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                LocationHelper.current(context, timeoutMs = 7000)?.let { Parking.record(context, it.latitude, it.longitude, "bluetooth") }
            } finally {
                pending.finish()
            }
        }
    }
}
