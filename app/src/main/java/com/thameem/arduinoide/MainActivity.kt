package com.thameem.arduinoide

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), SerialInputOutputManager.Listener {

    private lateinit var webView: WebView
    private lateinit var usbManager: UsbManager
    private var serialPort: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private val executor = Executors.newSingleThreadExecutor()
    private var currentBaud = 9600

    private val ACTION_USB_PERMISSION = "com.thameem.arduinoide.USB_PERMISSION"
    private var pageReady = false
    private var pendingAttachedDevice: UsbDevice? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let { openConnection(it) }
                    } else {
                        notifyJs("onUsbError", "Permission denied for USB device.")
                    }
                }
            }
        }
    }

    // Fires when a board is plugged in while the app is already open (no page
    // reload involved here, unlike the old manifest-level launch approach).
    private val attachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
                val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                device?.let {
                    if (pageReady) requestPermissionAndConnect(it) else pendingAttachedDevice = it
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        setContentView(R.layout.activity_main)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        webView = findViewById(R.id.webview)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                pageReady = true
                // If a board was plugged in before the page finished loading,
                // connect now that window.onUsbConnected actually exists.
                pendingAttachedDevice?.let {
                    requestPermissionAndConnect(it)
                    pendingAttachedDevice = null
                }
            }
        }
        webView.addJavascriptInterface(UsbBridge(), "AndroidUSB")
        webView.loadUrl("file:///android_asset/index.html")

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        val attachFilter = IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(attachReceiver, attachFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(attachReceiver, attachFilter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        closeConnection()
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(attachReceiver) } catch (_: Exception) {}
    }

    override fun onBackPressed() {
        super.onBackPressed()
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }

    // ============ JavaScript-facing bridge ============
    inner class UsbBridge {

        @JavascriptInterface
        fun listDevices(): String {
            val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            if (drivers.isEmpty()) return "[]"
            val items = drivers.mapIndexed { i, d ->
                val dev = d.device
                "{\"index\":$i,\"name\":\"${dev.deviceName}\",\"vendorId\":${dev.vendorId},\"productId\":${dev.productId}}"
            }
            return "[" + items.joinToString(",") + "]"
        }

        @JavascriptInterface
        fun requestConnection(baud: Int) {
            currentBaud = if (baud > 0) baud else 9600
            val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            if (drivers.isEmpty()) {
                notifyJs("onUsbError", "No supported board found. Check the cable and OTG connection.")
                return
            }
            val device = drivers[0].device
            requestPermissionAndConnect(device)
        }

        @JavascriptInterface
        fun write(data: String) {
            try {
                serialPort?.write(data.toByteArray(), 1000)
            } catch (e: Exception) {
                notifyJs("onUsbError", "Write failed: ${e.message}")
            }
        }

        // Used for the bootloader upload protocol, where exact byte values (0-255) matter
        // and must not be mangled by text/UTF-8 encoding.
        @JavascriptInterface
        fun writeBytes(csvBytes: String) {
            try {
                val bytes = csvBytes.split(",").filter { it.isNotBlank() }
                    .map { it.trim().toInt().toByte() }.toByteArray()
                serialPort?.write(bytes, 2000)
            } catch (e: Exception) {
                notifyJs("onUsbError", "Write failed: ${e.message}")
            }
        }

        // Toggles the DTR line, which resets most Arduino-compatible boards
        // into bootloader mode before an upload — the native equivalent of
        // the Web Serial port.setSignals({dataTerminalReady}) call.
        @JavascriptInterface
        fun setDTR(state: Boolean) {
            try {
                serialPort?.dtr = state
            } catch (_: Exception) {}
        }

        @JavascriptInterface
        fun disconnect() {
            closeConnection()
            notifyJs("onUsbDisconnected", "")
        }
    }

    private fun requestPermissionAndConnect(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            openConnection(device)
        } else {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE else 0
            val pendingIntent = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_USB_PERMISSION), flags
            )
            usbManager.requestPermission(device, pendingIntent)
        }
    }

    private fun openConnection(device: UsbDevice) {
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        val driver: UsbSerialDriver = drivers.firstOrNull { it.device.deviceId == device.deviceId }
            ?: return

        val connection = usbManager.openDevice(driver.device)
        if (connection == null) {
            notifyJs("onUsbError", "Failed to open USB connection. Try unplugging and reconnecting.")
            return
        }

        val port = driver.ports[0]
        try {
            port.open(connection)
            port.setParameters(currentBaud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            serialPort = port

            ioManager = SerialInputOutputManager(port, this)
            executor.submit(ioManager)

            notifyJs("onUsbConnected", "")
        } catch (e: Exception) {
            notifyJs("onUsbError", "Failed to open port: ${e.message}")
        }
    }

    private fun closeConnection() {
        try {
            ioManager?.stop()
            serialPort?.close()
        } catch (_: Exception) {}
        serialPort = null
        ioManager = null
    }

    // ============ Incoming board data -> JS ============
    override fun onNewData(data: ByteArray) {
        val text = String(data)
        val escaped = text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
        runOnUiThread {
            webView.evaluateJavascript("window.onUsbData && window.onUsbData('$escaped');", null)
        }
    }

    override fun onRunError(e: Exception) {
        notifyJs("onUsbError", "Connection lost: ${e.message}")
    }

    private fun notifyJs(fn: String, message: String) {
        val safeMsg = message.replace("'", "\\'")
        runOnUiThread {
            webView.evaluateJavascript("window.$fn && window.$fn('$safeMsg');", null)
        }
    }
}
