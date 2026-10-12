package x.vladgba.sendanyway.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import x.vladgba.sendanyway.R

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnSendFiles).setOnClickListener {
            val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            @Suppress("DEPRECATION")
            startActivityForResult(pick, REQ_PICK)
        }
        findViewById<Button>(R.id.btnSendFolder).setOnClickListener {
            val pick = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            @Suppress("DEPRECATION")
            startActivityForResult(pick, REQ_PICK_FOLDER)
        }
        findViewById<Button>(R.id.btnSendText).setOnClickListener { askText() }
        findViewById<Button>(R.id.btnReceive).setOnClickListener {
            startActivity(Intent(this, ReceiveActivity::class.java))
        }
    }

    private fun askText() {
        val input = EditText(this).apply {
            hint = getString(R.string.text_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
        }
        val clip = getSystemService(android.content.ClipboardManager::class.java)?.primaryClip
        val clipText = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString() else null
        if (!clipText.isNullOrEmpty()) {
            input.setText(clipText)
            input.setSelection(clipText.length)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle(R.string.send_text)
            .setView(box)
            .setPositiveButton(R.string.ok) { _, _ ->
                val text = input.text.toString()
                if (text.isNotEmpty()) {
                    startActivity(Intent(this, SendActivity::class.java).putExtra(Intent.EXTRA_TEXT, text))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    @Deprecated("Framework Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_PICK -> {
                val uris = ArrayList<Uri>()
                data.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(uris::add) }
                if (uris.isEmpty()) data.data?.let(uris::add)
                if (uris.isEmpty()) return
                startActivity(
                    Intent(this, SendActivity::class.java)
                        .putParcelableArrayListExtra(SendActivity.EXTRA_URIS, uris)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
            }
            REQ_PICK_FOLDER -> {
                val treeUri = data.data ?: return
                try {
                    contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {}
                startActivity(
                    Intent(this, SendActivity::class.java)
                        .putExtra(SendActivity.EXTRA_TREE_URI, treeUri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
            }
        }
    }

    private companion object {
        const val REQ_PICK = 1
        const val REQ_PICK_FOLDER = 2
    }
}
