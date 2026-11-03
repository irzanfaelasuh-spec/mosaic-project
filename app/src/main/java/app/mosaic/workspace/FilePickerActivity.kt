package app.mosaic.workspace

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import android.util.Base64

class FilePickerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(pick, REQUEST_PICK)
    }

    @Deprecated("Legacy activity result keeps this lightweight activity compatible with Android 10+")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK) return finish()
        if (resultCode != RESULT_OK || data?.data == null) return finish()
        try {
            val uri = data.data!!
            val bytes = contentResolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    total += n
                    require(total <= MAX_BYTES) { "File maximum size is 8 MB" }
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            } ?: error("Cannot read selected file")
            val name = queryName(uri) ?: "attachment"
            val mime = contentResolver.getType(uri) ?: "application/octet-stream"
            sendBroadcast(Intent(ACTION_FILE_PICKED).setPackage(packageName)
                .putExtra("name", name).putExtra("mime", mime)
                .putExtra("base64", Base64.encodeToString(bytes, Base64.NO_WRAP)))
            Toast.makeText(this, "Attached: $name", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "File could not be attached", Toast.LENGTH_LONG).show()
        }
        finish()
    }

    private fun queryName(uri: android.net.Uri): String? {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return null
    }

    companion object {
        const val ACTION_FILE_PICKED = "app.mosaic.workspace.FILE_PICKED"
        private const val REQUEST_PICK = 71
        private const val MAX_BYTES = 8 * 1024 * 1024
    }
}
