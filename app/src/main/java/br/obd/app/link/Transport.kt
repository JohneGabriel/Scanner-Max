package br.obd.app.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import br.obd.app.usb.UsbKLinePort
import com.hoho.android.usbserial.driver.FtdiSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** Tipo de ligação com o adaptador. */
enum class LinkKind(val label: String) {
    BT_CLASSIC("Bluetooth"),
    BLE("Bluetooth LE"),
    WIFI("Wi-Fi"),
    USB("USB"),
    DEMO("Demonstração"),
}

/**
 * Onde está o adaptador. Vira texto para ficar salvo ("último usado") e ser
 * reaberto na reconexão.
 */
data class LinkTarget(val kind: LinkKind, val address: String, val name: String) {
    fun encode() = "${kind.name}|$address|$name"

    val display get() = when (kind) {
        LinkKind.WIFI -> "$name ($address)"
        LinkKind.DEMO -> "Carro de demonstração"
        else -> name
    }

    companion object {
        fun decode(s: String?): LinkTarget? {
            val p = s?.split("|", limit = 3) ?: return null
            if (p.size < 3) return null
            val kind = runCatching { LinkKind.valueOf(p[0]) }.getOrNull() ?: return null
            return LinkTarget(kind, p[1], p[2])
        }

        val DEMO = LinkTarget(LinkKind.DEMO, "demo", "Demonstração")
        const val WIFI_DEFAULT = "192.168.0.10:35000"
    }
}

/**
 * Canal de bytes com o adaptador. O ELM327 fala texto ASCII terminado por '>'
 * em qualquer um dos meios; só o transporte muda.
 */
interface Transport {
    val kind: LinkKind
    /** Bloqueia até abrir (ou lança IOException com a causa). */
    fun open()
    fun write(data: ByteArray)
    /** Bytes já recebidos e ainda não lidos. */
    fun available(): Int
    /** Próximo byte (chamar só com available() > 0). */
    fun read(): Int
    val isOpen: Boolean
    fun close()
}

/** Transporte baseado em fila: quem recebe por callback (BLE, USB, simulador) empilha aqui. */
abstract class QueueTransport : Transport {
    protected val rx = LinkedBlockingQueue<Int>()
    @Volatile protected var opened = false
    override val isOpen get() = opened
    override fun available() = rx.size
    override fun read(): Int = rx.poll() ?: -1
    protected fun push(bytes: ByteArray, n: Int = bytes.size) {
        for (i in 0 until n) rx.offer(bytes[i].toInt() and 0xFF)
    }
}

// ------------------------------------------------------------------ Bluetooth clássico

@SuppressLint("MissingPermission")
class BtClassicTransport(private val device: BluetoothDevice, private val log: (String) -> Unit) : Transport {
    override val kind = LinkKind.BT_CLASSIC
    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    @Volatile private var alive = false

    override fun open() {
        val sock = try {
            device.createInsecureRfcommSocketToServiceRecord(SPP_UUID).also { it.connect() }
        } catch (e: Exception) {
            // muitos clones só aceitam o canal RFCOMM 1 direto
            log("SPP padrão falhou (${e.message}), tentando canal 1…")
            try {
                val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                (m.invoke(device, 1) as BluetoothSocket).also { it.connect() }
            } catch (e2: Exception) {
                throw IOException("Não conectou ao Bluetooth: ${e2.cause?.message ?: e2.message}")
            }
        }
        socket = sock; input = sock.inputStream; output = sock.outputStream
        alive = true
    }

    override fun write(data: ByteArray) = guard { output!!.write(data); output!!.flush() }
    override fun available(): Int = guard { input?.available() ?: 0 }
    override fun read(): Int = guard { input!!.read() }
    override val isOpen get() = alive && socket?.isConnected == true

    private inline fun <T> guard(block: () -> T): T = try { block() } catch (e: IOException) { alive = false; throw e }

    override fun close() {
        alive = false
        runCatching { socket?.close() }
        socket = null; input = null; output = null
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}

// ------------------------------------------------------------------ Wi-Fi

/**
 * Adaptadores Wi-Fi criam uma rede própria (sem internet) e escutam TCP em
 * 192.168.0.10:35000. O socket é criado pela rede Wi-Fi: sem isso o Android manda o
 * tráfego pelos dados móveis, já que a rede do adaptador "não tem internet".
 */
class WifiTransport(private val ctx: Context, private val hostPort: String) : Transport {
    override val kind = LinkKind.WIFI
    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    @Volatile private var alive = false

    override fun open() {
        val host = hostPort.substringBefore(":").trim()
        val port = hostPort.substringAfter(":", "35000").trim().toIntOrNull() ?: 35000
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        val wifi = cm?.allNetworks?.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: throw IOException("O celular não está numa rede Wi-Fi. Conecte na rede do adaptador (ex.: WiFi_OBDII) e tente de novo.")
        val s = wifi.socketFactory.createSocket()
        try {
            s.connect(InetSocketAddress(host, port), 5000)
        } catch (e: Exception) {
            runCatching { s.close() }
            throw IOException("Sem resposta em $host:$port — o celular está na rede do adaptador?")
        }
        s.tcpNoDelay = true
        s.soTimeout = 0
        socket = s; input = s.getInputStream(); output = s.getOutputStream()
        alive = true
    }

    override fun write(data: ByteArray) = guard { output!!.write(data); output!!.flush() }
    override fun available(): Int = guard { input?.available() ?: 0 }
    override fun read(): Int = guard { input!!.read() }
    override val isOpen get() = alive && socket?.isConnected == true && socket?.isClosed == false
    private inline fun <T> guard(block: () -> T): T = try { block() } catch (e: IOException) { alive = false; throw e }

    override fun close() {
        alive = false
        runCatching { socket?.close() }
        socket = null; input = null; output = null
    }
}

// ------------------------------------------------------------------ USB (ELM327 com cabo USB)

/** ELM327 USB (FTDI, CH340, CP210x, PL2303). Testa as velocidades mais comuns. */
class UsbElmTransport(private val ctx: Context, private val log: (String) -> Unit) : QueueTransport() {
    override val kind = LinkKind.USB
    private var port: UsbSerialPort? = null
    private var reader: Thread? = null
    /** Cabo KKL: o próprio app fala OBD2 na linha K (ELM327 "virtual"). */
    private var kkl: KLineElm? = null
    private var keeper: Thread? = null
    private val pending = StringBuilder()

    /** true quando o USB é um cabo KKL (sem chip ELM). */
    val isKkl get() = kkl != null

    override fun open() {
        val driver = UsbKLinePort.findDrivers(ctx).firstOrNull()
            ?: throw IOException("Nenhum adaptador USB encontrado. Plugue pelo OTG.")
        val um = ctx.getSystemService(android.hardware.usb.UsbManager::class.java)!!
        if (!um.hasPermission(driver.device)) {
            throw IOException("Permita o acesso ao USB na janela do Android e toque em conectar de novo.")
        }
        val conn = um.openDevice(driver.device) ?: throw IOException("Não foi possível abrir o USB")
        val p = driver.ports[0]
        p.open(conn)
        if (p is FtdiSerialDriver.FtdiSerialPort) runCatching { p.setLatencyTimer(1) }
        port = p
        opened = true
        // ELM327 USB costuma vir a 38400; versões novas a 115200; algumas a 9600
        for (baud in listOf(38400, 115200, 9600, 230400)) {
            p.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            runCatching { p.purgeHwBuffers(true, true) }
            p.write("\rATI\r".toByteArray(), 300)
            val got = StringBuilder()
            val end = System.currentTimeMillis() + 900
            val buf = ByteArray(64)
            while (System.currentTimeMillis() < end) {
                val n = runCatching { p.read(buf, 100) }.getOrDefault(0)
                if (n > 0) got.append(String(buf, 0, n, Charsets.ISO_8859_1))
                if (got.contains('>')) break
            }
            if (got.contains("ELM") || got.contains("STN") || got.contains("OBD")) {
                log("USB: adaptador ELM327 respondeu a $baud baud")
                startReader(p)
                return
            }
        }
        // não é ELM327: cabo KKL (só devolve o eco do que enviamos). O app fala OBD2 direto na linha K.
        log("USB: sem chip ELM327; usando como cabo KKL (linha K direta)")
        val k = KLineElm(UsbKLinePort.wrap(p), log)
        kkl = k
        keeper = Thread({
            while (opened) {
                try { Thread.sleep(500); k.keepAlive() } catch (e: InterruptedException) { break } catch (e: Exception) { opened = false }
            }
        }, "kkl-keepalive").apply { isDaemon = true; start() }
    }

    private fun startReader(p: UsbSerialPort) {
        reader = Thread({
            val buf = ByteArray(256)
            while (opened) {
                val n = try { p.read(buf, 200) } catch (e: Exception) { opened = false; break }
                if (n > 0) push(buf, n)
            }
        }, "usb-elm").apply { isDaemon = true; start() }
    }

    override fun write(data: ByteArray) {
        val k = kkl
        if (k != null) {
            if (!opened) throw IOException("Cabo USB desconectado")
            for (b in data) {
                val c = (b.toInt() and 0xFF).toChar()
                if (c != '\r') { pending.append(c); continue }
                val cmd = pending.toString(); pending.clear()
                val r = try { k.handle(cmd) } catch (e: Exception) { opened = false; throw IOException("Cabo KKL: ${e.message}") }
                push(r.toByteArray(Charsets.ISO_8859_1))
            }
            return
        }
        val p = port ?: throw IOException("USB fechado")
        try { p.write(data, 1000) } catch (e: Exception) { opened = false; throw IOException("USB: ${e.message}") }
    }

    override fun close() {
        opened = false
        keeper?.interrupt()
        runCatching { kkl?.close() }
        kkl = null
        runCatching { port?.close() }
        port = null
    }

    companion object {
        /** Pede a permissão USB antes de abrir (precisa de corrotina; o open() é bloqueante). */
        suspend fun ensurePermission(ctx: Context): Boolean {
            val driver = UsbKLinePort.findDrivers(ctx).firstOrNull() ?: return false
            val um = ctx.getSystemService(android.hardware.usb.UsbManager::class.java)!!
            if (um.hasPermission(driver.device)) return true
            return runCatching { UsbKLinePort.open(ctx, driver).close(); true }.getOrDefault(false)
        }
    }
}

// ------------------------------------------------------------------ Bluetooth LE

/**
 * Adaptadores BLE (Vgate iCar Pro BLE, Veepeak BLE, OBDLink CX, clones "IOS-Vlink"…)
 * expõem um serviço com uma característica que notifica (RX) e outra que aceita escrita
 * (TX). Os UUIDs mudam de marca para marca (FFF0/FFF1/FFF2, FFE0/FFE1, 18F0/2AF0/2AF1…),
 * então procuramos qualquer par que sirva, preferindo os conhecidos.
 */
@SuppressLint("MissingPermission")
class BleTransport(
    private val ctx: Context,
    private val address: String,
    private val log: (String) -> Unit,
) : QueueTransport() {
    override val kind = LinkKind.BLE
    private var gatt: BluetoothGatt? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    private var mtu = 23
    private val writeDone = Semaphore(0)
    private val ready = CountDownLatch(1)
    @Volatile private var failure: String? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                opened = false
                if (ready.count > 0) { failure = "o adaptador recusou a conexão (status $status)"; ready.countDown() }
                writeDone.release()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val pair = pickCharacteristics(g)
            if (pair == null) {
                failure = "não achei o canal serial do adaptador"
                ready.countDown(); return
            }
            val (rxc, txc) = pair
            tx = txc
            writeType = if (txc.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            log("BLE: serviço ${rxc.service.uuid.short()} · RX ${rxc.uuid.short()} · TX ${txc.uuid.short()}")
            g.setCharacteristicNotification(rxc, true)
            val cccd = rxc.getDescriptor(CCCD)
            if (cccd != null) {
                @Suppress("DEPRECATION")
                cccd.value = if (rxc.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            } else {
                g.requestMtu(185)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            g.requestMtu(185)
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            opened = true
            ready.countDown()
        }

        @Deprecated("API antiga, ainda usada até o Android 12")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            c.value?.let { push(it) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            push(value)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeDone.release()
        }
    }

    override fun open() {
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw IOException("Bluetooth indisponível")
        val dev = adapter.getRemoteDevice(address)
        gatt = dev.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
        if (!ready.await(15, TimeUnit.SECONDS)) {
            close(); throw IOException("O adaptador BLE não respondeu em 15 s")
        }
        failure?.let { close(); throw IOException("BLE: $it") }
    }

    override fun write(data: ByteArray) {
        val g = gatt ?: throw IOException("BLE fechado")
        val c = tx ?: throw IOException("BLE sem canal de escrita")
        if (!opened) throw IOException("Conexão BLE perdida")
        val chunk = (mtu - 3).coerceAtLeast(20)
        var i = 0
        while (i < data.size) {
            val part = data.copyOfRange(i, minOf(data.size, i + chunk))
            writeDone.drainPermits()
            val ok = if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, part, writeType) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.value = part
                c.writeType = writeType
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
            if (!ok) throw IOException("BLE: escrita recusada")
            if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) writeDone.tryAcquire(2, TimeUnit.SECONDS)
            else Thread.sleep(8)
            if (!opened) throw IOException("Conexão BLE perdida")
            i += chunk
        }
    }

    override fun close() {
        opened = false
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
    }

    private fun UUID.short() = toString().substring(4, 8).uppercase()

    companion object {
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val KNOWN = listOf("fff0", "ffe0", "18f0", "e7810a71", "49535343", "0000fff0")

        fun pickCharacteristics(g: BluetoothGatt): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
            val services = g.services.sortedBy { s ->
                val u = s.uuid.toString().lowercase()
                val i = KNOWN.indexOfFirst { u.startsWith(it) || u.substring(4, 8) == it }
                if (i >= 0) i else 99
            }
            for (s in services) {
                // serviços padrão do Bluetooth (GAP, GATT, info do aparelho) ficam de fora
                val u = s.uuid.toString().substring(4, 8).lowercase()
                if (u in listOf("1800", "1801", "180a", "180f")) continue
                val notify = s.characteristics.firstOrNull {
                    it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                } ?: continue
                val write = s.characteristics.firstOrNull {
                    it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                } ?: continue
                return notify to write
            }
            return null
        }
    }
}

// ------------------------------------------------------------------ simulador

/** Carro de demonstração: responde como um ELM327 ligado num carro CAN funcionando. */
class SimTransport : QueueTransport() {
    override val kind = LinkKind.DEMO
    private val sim = ElmSimulator()
    private val pending = StringBuilder()

    override fun open() { opened = true }

    override fun write(data: ByteArray) {
        for (b in data) {
            val c = b.toInt().toChar()
            if (c == '\r') {
                val cmd = pending.toString().trim()
                pending.clear()
                if (cmd.isEmpty()) continue
                Thread.sleep(if (cmd.startsWith("AT")) 5 else 25)
                push((sim.respond(cmd) + "\r\r>").toByteArray())
            } else pending.append(c)
        }
    }

    override fun close() { opened = false }
}
