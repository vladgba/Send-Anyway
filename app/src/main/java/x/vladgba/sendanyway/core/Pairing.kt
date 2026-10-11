package x.vladgba.sendanyway.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.util.UUID

/**
 * Contents of the pairing QR code shown by the sender.
 *
 * The QR code is the out-of-band channel: whoever can see it gets the session key, and
 * every byte sent over LAN / Bluetooth is encrypted with that key (see [SecureChannel]).
 *
 * Binary layout (big endian):
 * ```
 * u8  MAGIC (0xA5)      u8 TYPE_PAIRING (1)     u8 VERSION
 * u8[8]  sessionId      u8[32] key (AES-256)
 * u8  flags             bit0 = Bluetooth RFCOMM listening, bit1 = Wi-Fi Direct group
 * str deviceName        (u16 length + UTF-8, max 64 bytes)
 * u16 lanPort           0 = no LAN server
 * u8  addressCount      then per address: u8 len (4 or 16) + bytes
 * if flags bit1:        str networkName, str passphrase   (sender's Wi-Fi Direct group)
 * ```
 */
class Pairing(
    val sessionId: ByteArray,
    val key: ByteArray,
    val deviceName: String,
    val lanPort: Int,
    val lanAddresses: List<ByteArray>,
    val bluetooth: Boolean,
    val wifiGroup: WifiGroup? = null,
) {
    /** RFCOMM service UUID for Bluetooth, unique per session. */
    val rfcommUuid: UUID get() = UUID.nameUUIDFromBytes("SendAnyway/rfcomm".toByteArray() + sessionId)

    /**
     * Wi-Fi Direct group the sender owns. Its owner is always 192.168.49.1; the network is
     * also joinable as a normal WPA2 Wi-Fi network ("DIRECT-…") from the system settings.
     */
    class WifiGroup(val networkName: String, val passphrase: String)

    fun encode(): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { out ->
            out.writeByte(Wire.MAGIC)
            out.writeByte(Wire.TYPE_PAIRING)
            out.writeByte(Wire.VERSION)
            out.write(sessionId)
            out.write(key)
            out.writeByte(
                (if (bluetooth) FLAG_BLUETOOTH else 0) or (if (wifiGroup != null) FLAG_WIFI_GROUP else 0)
            )
            out.writeStr(deviceName, 64)
            out.writeShort(lanPort)
            val addrs = lanAddresses.take(8)
            out.writeByte(addrs.size)
            for (a in addrs) {
                out.writeByte(a.size)
                out.write(a)
            }
            if (wifiGroup != null) {
                out.writeStr(wifiGroup.networkName, 32)
                out.writeStr(wifiGroup.passphrase, 63)
            }
        }
        return bos.toByteArray()
    }

    fun sameSession(other: Pairing?): Boolean = other != null && other.sessionId.contentEquals(sessionId)

    fun withWifiGroup(h: WifiGroup?, addresses: List<ByteArray>) =
        Pairing(sessionId, key, deviceName, lanPort, addresses, bluetooth, h)

    companion object {
        const val SESSION_ID_LEN = 8
        const val KEY_LEN = 32
        private const val FLAG_BLUETOOTH = 1
        private const val FLAG_WIFI_GROUP = 2

        private val random = SecureRandom()

        fun newSecrets(): Pair<ByteArray, ByteArray> {
            val id = ByteArray(SESSION_ID_LEN).also(random::nextBytes)
            val key = ByteArray(KEY_LEN).also(random::nextBytes)
            return id to key
        }

        fun decode(bytes: ByteArray): Pairing? {
            if (Wire.typeOf(bytes) != Wire.TYPE_PAIRING) return null
            return try {
                DataInputStream(ByteArrayInputStream(bytes)).use { inp ->
                    inp.skipBytes(2)
                    val version = inp.readUnsignedByte()
                    if (version != Wire.VERSION) return null
                    val id = ByteArray(SESSION_ID_LEN).also(inp::readFully)
                    val key = ByteArray(KEY_LEN).also(inp::readFully)
                    val flags = inp.readUnsignedByte()
                    val name = inp.readStr()
                    val port = inp.readUnsignedShort()
                    val count = inp.readUnsignedByte()
                    val addrs = List(count) {
                        val len = inp.readUnsignedByte()
                        if (len != 4 && len != 16) throw IOException("bad address length")
                        ByteArray(len).also(inp::readFully)
                    }
                    val group = if (flags and FLAG_WIFI_GROUP != 0) WifiGroup(inp.readStr(), inp.readStr()) else null
                    Pairing(id, key, name, port, addrs, flags and FLAG_BLUETOOTH != 0, group)
                }
            } catch (e: IOException) {
                null
            }
        }
    }
}
