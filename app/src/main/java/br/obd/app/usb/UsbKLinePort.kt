package br.obd.app.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import br.obd.app.vag.KLinePort
import com.hoho.android.usbserial.driver.FtdiSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Cabo KKL (FTDI ou CH340) ligado pelo OTG, visto como porta da linha K. */
class UsbKLinePort private constructor(private val port: UsbSerialPort) : KLinePort {

    private val buf = ByteArray(256)
    private var len = 0
    private var pos = 0

    override fun setBaud(baud: Int) {
        port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
    }

    override fun setBreak(on: Boolean) = port.setBreak(on)

    override fun write(b: Int) {
        port.write(byteArrayOf(b.toByte()), 200)
    }

    override fun writeAll(bytes: IntArray) {
        port.write(ByteArray(bytes.size) { bytes[it].toByte() }, 500)
    }

    override fun read(timeoutMs: Int): Int {
        if (pos < len) return buf[pos++].toInt() and 0xFF
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = (deadline - System.currentTimeMillis()).toInt()
            if (left <= 0) return -1
            val n = port.read(buf, left.coerceAtLeast(1))
            if (n > 0) {
                len = n; pos = 1
                return buf[0].toInt() and 0xFF
            }
        }
    }

    override fun purge() {
        pos = len
        runCatching { port.purgeHwBuffers(true, true) }
        // esvazia o que já estava a caminho
        while (runCatching { port.read(buf, 5) }.getOrDefault(0) > 0) Unit
        len = 0; pos = 0
    }

    override fun close() {
        runCatching { port.setBreak(false) }
        runCatching { port.close() }
    }

    companion object {
        private const val ACTION_PERMISSION = "br.obd.app.USB_PERMISSION"

        /** Cabos USB-serial conectados (o KKL aparece como FTDI, vendor 0403). */
        fun findDrivers(ctx: Context): List<UsbSerialDriver> {
            val um = ctx.getSystemService(UsbManager::class.java) ?: return emptyList()
            return UsbSerialProber.getDefaultProber().findAllDrivers(um)
        }

        fun describe(d: UsbDevice) = buildString {
            append(d.productName ?: "USB")
            append(" · ")
            append(if (d.vendorId == 0x0403) "FTDI" else if (d.vendorId == 0x1A86) "CH340" else "VID %04X".format(d.vendorId))
        }

        /** Usa uma porta já aberta (o USB do ELM327 que descobriu ser um cabo KKL). */
        fun wrap(p: UsbSerialPort): UsbKLinePort = UsbKLinePort(p)

        /** Pede permissão ao usuário (se preciso) e abre a porta. */
        suspend fun open(ctx: Context, driver: UsbSerialDriver): UsbKLinePort {
            val um = ctx.getSystemService(UsbManager::class.java)!!
            val device = driver.device
            if (!um.hasPermission(device)) {
                val granted = requestPermission(ctx, um, device)
                if (!granted) throw IllegalStateException("Permissão USB negada")
            }
            val conn = um.openDevice(device) ?: throw IllegalStateException("Não foi possível abrir o cabo USB")
            val p = driver.ports[0]
            p.open(conn)
            // FTDI: latência mínima, senão cada resposta atrasa 16 ms (o KW1281 exige respostas rápidas)
            if (p is FtdiSerialDriver.FtdiSerialPort) runCatching { p.setLatencyTimer(1) }
            return UsbKLinePort(p)
        }

        private suspend fun requestPermission(ctx: Context, um: UsbManager, device: UsbDevice): Boolean =
            suspendCancellableCoroutine { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, i: Intent) {
                        if (i.action != ACTION_PERMISSION) return
                        runCatching { ctx.unregisterReceiver(this) }
                        if (cont.isActive) cont.resume(i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                    }
                }
                ContextCompat.registerReceiver(ctx, receiver, IntentFilter(ACTION_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
                val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val pi = PendingIntent.getBroadcast(ctx, 0, Intent(ACTION_PERMISSION).setPackage(ctx.packageName), flags)
                um.requestPermission(device, pi)
                cont.invokeOnCancellation { runCatching { ctx.unregisterReceiver(receiver) } }
            }
    }
}
