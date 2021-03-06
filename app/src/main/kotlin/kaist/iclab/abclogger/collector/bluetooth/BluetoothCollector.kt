package kaist.iclab.abclogger.collector.bluetooth

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.app.AlarmManagerCompat
import kaist.iclab.abclogger.*
import kaist.iclab.abclogger.collector.BaseCollector
import kaist.iclab.abclogger.collector.BaseStatus
import kaist.iclab.abclogger.collector.fill
import kaist.iclab.abclogger.collector.setStatus
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class BluetoothCollector(val context: Context) : BaseCollector {
    data class Status(override val hasStarted: Boolean? = null,
                               override val lastTime: Long? = null) : BaseStatus() {
        override fun info(): String = ""
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        BluetoothAdapter.getDefaultAdapter()
    }

    private val alarmManager: AlarmManager by lazy {
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    }

    private val scanCallback: ScanCallback by lazy {
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                super.onScanResult(callbackType, result)
                val timestamp = System.currentTimeMillis()
                val address = result?.device?.address ?: return

                if (address in discoveredBLEDevices) return
                discoveredBLEDevices.add(address)

                BluetoothEntity(
                        deviceName = result.device?.name ?: "",
                        address = address,
                        rssi = result.rssi
                ).fill(timeMillis = timestamp).also { entity ->
                    GlobalScope.launch {
                        ObjBox.put(entity)
                        setStatus(Status(lastTime = timestamp))
                    }
                }
            }
        }
    }

    private val receiver: BroadcastReceiver by lazy {
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_FOUND -> handleActionFound(intent)
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> handleBluetoothDiscovered()
                    ACTION_BLUETOOTH_SCAN -> handleBluetoothScanRequest()
                }
            }
        }
    }

    private val filter = IntentFilter().apply {
        addAction(ACTION_BLUETOOTH_SCAN)
        addAction(BluetoothDevice.ACTION_FOUND)
        addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
    }

    private val intent = PendingIntent.getBroadcast(
            context, REQUEST_CODE_BLUETOOTH_SCAN,
            Intent(ACTION_BLUETOOTH_SCAN), PendingIntent.FLAG_UPDATE_CURRENT
    )

    private val discoveredBLEDevices: HashSet<String> = hashSetOf()
    private val isBLEScanning: AtomicBoolean = AtomicBoolean(false)


    private fun handleBluetoothDiscovered() = GlobalScope.launch(Dispatchers.IO) {
        if (isBLEScanning.get()) return@launch

        isBLEScanning.set(true)

        val scanner = BluetoothAdapter.getDefaultAdapter().bluetoothLeScanner

        scanner.startScan(scanCallback)
        delay(5000)
        scanner.stopScan(scanCallback)

        discoveredBLEDevices.clear()
        isBLEScanning.set(false)
    }

    private fun handleActionFound(intent: Intent) {
        val timestamp = System.currentTimeMillis()
        val extras = intent.extras ?: return
        val device = extras.getParcelable<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
        val rssi = extras.getShort(BluetoothDevice.EXTRA_RSSI, 0).toInt()

        BluetoothEntity(
                deviceName = device.name ?: "Unknown",
                address = device.address ?: "Unknown",
                rssi = rssi
        ).fill(timeMillis = timestamp).also { entity ->
            GlobalScope.launch {
                ObjBox.put(entity)
                setStatus(Status(lastTime = timestamp))
            }
        }
    }

    private fun handleBluetoothScanRequest() {
        if (bluetoothAdapter?.isDiscovering == true) bluetoothAdapter?.cancelDiscovery()

        if (bluetoothAdapter?.isDiscovering == false && bluetoothAdapter?.isEnabled == true) {
            bluetoothAdapter?.startDiscovery()
        }

        scheduleAlarm()
    }

    private fun scheduleAlarm() {
        val curTime = System.currentTimeMillis()
        AlarmManagerCompat.setAndAllowWhileIdle(
                alarmManager, AlarmManager.RTC_WAKEUP, curTime + TimeUnit.MINUTES.toMillis(10), intent
        )
    }

    override suspend fun onStart() {
        context.safeRegisterReceiver(receiver, filter)
        alarmManager.cancel(intent)

        scheduleAlarm()
    }

    override suspend fun onStop() {
        context.safeUnregisterReceiver(receiver)

        alarmManager.cancel(intent)
    }

    override suspend fun checkAvailability(): Boolean =
            bluetoothAdapter?.isEnabled == true && context.checkPermission(requiredPermissions)

    override val requiredPermissions: List<String>
        get() = listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        )

    override val newIntentForSetUp: Intent?
        get() = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)

    companion object {
        private const val ACTION_BLUETOOTH_SCAN = "${BuildConfig.APPLICATION_ID}.ACTION_BLUETOOTH_SCAN"
        private const val REQUEST_CODE_BLUETOOTH_SCAN = 0xdd
    }
}
