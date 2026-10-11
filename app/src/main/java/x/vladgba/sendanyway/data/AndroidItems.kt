package x.vladgba.sendanyway.data

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import x.vladgba.sendanyway.core.ItemInfo
import x.vladgba.sendanyway.core.OutgoingItem
import x.vladgba.sendanyway.core.ReceiveSink
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/** A file picked in the system picker or shared from another app. */
class UriItem(private val resolver: ContentResolver, private val uri: Uri) : OutgoingItem {
    override val info: ItemInfo

    init {
        var name: String? = null
        var size = -1L
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (_: Exception) {}
        info = ItemInfo(
            ItemInfo.KIND_FILE,
            name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file",
            resolver.getType(uri) ?: "application/octet-stream",
            size,
        )
    }

    @Volatile private var opened: InputStream? = null

    /**
     * Opens the file now. Read access to shared URIs can end when the sending screen is closed;
     * a stream opened while access was granted stays readable, so the transfer can continue in
     * the background.
     */
    fun preopen() {
        if (opened == null) opened = resolver.openInputStream(uri) ?: throw IOException("cannot open $uri")
    }

    override fun open(): InputStream =
        opened?.also { opened = null } ?: resolver.openInputStream(uri) ?: throw IOException("cannot open $uri")
}

object Device {
    fun name(ctx: Context): String =
        Settings.Global.getString(ctx.contentResolver, "device_name")?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER} ${Build.MODEL}"
}

/**
 * Saves received files to Download/SendAnyway (MediaStore on Android 10+, no permission needed;
 * the app's own external files dir on older versions). Text items are kept in memory.
 * Callbacks are invoked on the worker thread.
 */
class DownloadSink(
    ctx: Context,
    private val onText: (String) -> Unit,
    private val onFile: (name: String, location: String) -> Unit,
) : ReceiveSink {
    private val app = ctx.applicationContext
    private val resolver = app.contentResolver
    private val pendingUris = ConcurrentHashMap<Int, Uri>()
    private val files = ConcurrentHashMap<Int, File>()
    private val texts = ConcurrentHashMap<Int, ByteArrayOutputStream>()

    override fun open(index: Int, info: ItemInfo): OutputStream {
        if (info.isText) return ByteArrayOutputStream().also { texts[index] = it }
        val name = safeName(info.name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, info.mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("cannot create $name")
            pendingUris[index] = uri
            return resolver.openOutputStream(uri) ?: throw IOException("cannot write $name")
        }
        val dir = File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: app.filesDir, FOLDER).apply { mkdirs() }
        val file = unique(dir, name)
        files[index] = file
        return FileOutputStream(file)
    }

    override fun finish(index: Int, info: ItemInfo, ok: Boolean) {
        texts.remove(index)?.let { if (ok) onText(it.toString("UTF-8")); return }
        pendingUris.remove(index)?.let { uri ->
            if (ok && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                onFile(safeName(info.name), "Download/$FOLDER")
            } else {
                try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            }
            return
        }
        files.remove(index)?.let { f ->
            if (ok) onFile(f.name, f.parent ?: "") else f.delete()
        }
    }

    private fun unique(dir: File, name: String): File {
        var f = File(dir, name)
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var n = 1
        while (f.exists()) {
            f = File(dir, "$base ($n)$ext")
            n++
        }
        return f
    }

    companion object {
        const val FOLDER = "SendAnyway"

        fun safeName(name: String): String =
            name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trimStart('.').take(120).ifEmpty { "file" }
    }
}
