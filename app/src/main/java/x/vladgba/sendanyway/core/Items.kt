package x.vladgba.sendanyway.core

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Description of one transferred item. [size] is -1 when unknown up front (stream transports only). */
class ItemInfo(val kind: Int, val name: String, val mime: String, val size: Long) {
    val isText: Boolean get() = kind == KIND_TEXT

    fun write(out: DataOutputStream) {
        out.writeByte(kind)
        out.writeStr(name)
        out.writeStr(mime, 255)
        out.writeLong(size)
    }

    fun withSize(newSize: Long) = ItemInfo(kind, name, mime, newSize)

    companion object {
        const val KIND_FILE = 0
        const val KIND_TEXT = 1

        fun read(inp: DataInputStream) = ItemInfo(
            kind = inp.readUnsignedByte(),
            name = inp.readStr(),
            mime = inp.readStr(),
            size = inp.readLong(),
        )
    }
}

/** Something the sender can stream. Implementations may open the data lazily. */
interface OutgoingItem {
    val info: ItemInfo
    fun open(): InputStream
}

class TextItem(text: String) : OutgoingItem {
    private val bytes = text.toByteArray(Charsets.UTF_8)
    override val info = ItemInfo(ItemInfo.KIND_TEXT, "text.txt", "text/plain", bytes.size.toLong())
    override fun open(): InputStream = ByteArrayInputStream(bytes)
}

/** Where received items go. [finish] is always called after [open], with ok=false on failure. */
interface ReceiveSink {
    fun open(index: Int, info: ItemInfo): OutputStream
    fun finish(index: Int, info: ItemInfo, ok: Boolean)
}

interface TransferListener {
    fun onProgress(done: Long, total: Long) {}
    fun onItemDone(index: Int, info: ItemInfo) {}
}

fun List<ItemInfo>.knownTotal(): Long = sumOf { if (it.size > 0) it.size else 0L }
