package x.vladgba.sendanyway.data

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
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
import java.util.zip.CRC32

/** A file picked in the system picker or shared from another app. */
class UriItem(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val overrideName: String? = null,
) : OutgoingItem {
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
            overrideName ?: name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file",
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

/** Recursively traverses document trees picked via ACTION_OPEN_DOCUMENT_TREE. */
object TreeWalker {
    fun walk(resolver: ContentResolver, treeUri: Uri): List<OutgoingItem> {
        val out = ArrayList<OutgoingItem>()
        val rootDocId = try { DocumentsContract.getTreeDocumentId(treeUri) } catch (_: Exception) { return out }
        fun traverse(docId: String, currentPath: String) {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
            )
            resolver.query(childrenUri, projection, null, null, null)?.use { c ->
                val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                while (c.moveToNext()) {
                    val childId = if (idIdx >= 0) c.getString(idIdx) else continue
                    val displayName = if (nameIdx >= 0) c.getString(nameIdx) ?: "file" else "file"
                    val mime = if (mimeIdx >= 0) c.getString(mimeIdx) ?: "" else ""
                    val path = if (currentPath.isEmpty()) displayName else "$currentPath/$displayName"
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        traverse(childId, path)
                    } else {
                        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                        val size = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else -1L
                        out.add(UriItem(resolver, docUri, overrideName = path))
                    }
                }
            }
        }
        traverse(rootDocId, "")
        return out
    }
}

object Device {
    fun name(ctx: Context): String =
        Settings.Global.getString(ctx.contentResolver, "device_name")?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER} ${Build.MODEL}"
}

/**
 * Saves received files to Download/SendAnyway (MediaStore on Android 10+, no permission needed;
 * the app's own external files dir on older versions). Text items are kept in memory.
 * Preserves subfolder structure and supports resuming interrupted downloads via .part files.
 * Callbacks are invoked on the worker thread.
 */
class DownloadSink(
    ctx: Context,
    private val onText: (String) -> Unit,
    private val onFile: (name: String, location: String, uri: Uri?, mime: String) -> Unit,
) : ReceiveSink {
    private val app = ctx.applicationContext
    private val resolver = app.contentResolver
    private val stagingDir = File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: app.filesDir, ".staging").apply { mkdirs() }
    private val texts = ConcurrentHashMap<Int, ByteArrayOutputStream>()

    private fun partFile(info: ItemInfo): File {
        val (dir, name) = parsePath(info.name)
        val key = if (dir.isEmpty()) name else "${dir.replace('/', '_')}_$name"
        return File(stagingDir, "$key.${info.size}.part")
    }

    override fun offset(index: Int, info: ItemInfo): Long {
        if (info.isText || info.size <= 0) return 0L
        val p = partFile(info)
        if (p.exists()) {
            val len = p.length()
            if (len in 1 until info.size) return len
            if (len >= info.size) p.delete()
        }
        return 0L
    }

    override fun feedPrefix(index: Int, info: ItemInfo, offset: Long, crc: CRC32) {
        if (offset <= 0) return
        val p = partFile(info)
        if (!p.exists()) return
        val buf = ByteArray(64 * 1024)
        p.inputStream().use { inp ->
            var left = offset
            while (left > 0) {
                val r = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (r < 0) break
                crc.update(buf, 0, r)
                left -= r
            }
        }
    }

    override fun open(index: Int, info: ItemInfo): OutputStream {
        if (info.isText) return ByteArrayOutputStream().also { texts[index] = it }
        val p = partFile(info)
        val appending = p.exists() && p.length() > 0 && p.length() < info.size
        return FileOutputStream(p, appending)
    }

    override fun finish(index: Int, info: ItemInfo, ok: Boolean) {
        texts.remove(index)?.let { if (ok) onText(it.toString("UTF-8")); return }
        val p = partFile(info)
        if (!ok) {
            // Leave the .part file so a subsequent retry can resume!
            return
        }

        val (relDir, fileName) = parsePath(info.name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relPath = if (relDir.isEmpty()) "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER"
            else "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/$relDir"
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, info.mime)
                put(MediaStore.Downloads.RELATIVE_PATH, relPath)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("cannot create $fileName")
            try {
                p.inputStream().use { inp ->
                    resolver.openOutputStream(uri)?.use { out -> inp.copyTo(out) }
                }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                p.delete()
                val displayLocation = if (relDir.isEmpty()) "Download/$FOLDER" else "Download/$FOLDER/$relDir"
                onFile(fileName, displayLocation, uri, info.mime)
            } catch (e: Exception) {
                try { resolver.delete(uri, null, null) } catch (_: Exception) {}
                throw e
            }
            return
        }

        val baseDir = File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: app.filesDir, FOLDER)
        val targetDir = if (relDir.isEmpty()) baseDir else File(baseDir, relDir).apply { mkdirs() }
        val finalFile = unique(targetDir, fileName)
        if (!p.renameTo(finalFile)) {
            p.inputStream().use { inp -> finalFile.outputStream().use { out -> inp.copyTo(out) } }
            p.delete()
        }
        val uri = Uri.fromFile(finalFile)
        onFile(finalFile.name, targetDir.absolutePath, uri, info.mime)
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

        fun parsePath(path: String): Pair<String, String> {
            val normalized = path.replace('\\', '/').trim().trim('/')
            val parts = normalized.split('/').map { safeSegment(it) }.filter { it.isNotEmpty() && it != "." && it != ".." }
            return if (parts.size > 1) {
                val dir = parts.dropLast(1).joinToString("/")
                val file = parts.last()
                dir to file
            } else {
                "" to (parts.firstOrNull()?.ifEmpty { "file" } ?: "file")
            }
        }

        fun safeSegment(name: String): String =
            name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trimStart('.').take(120).ifEmpty { "file" }

        fun safeName(name: String): String = parsePath(name).second
    }
}
