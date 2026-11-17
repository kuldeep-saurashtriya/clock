package app.expressiveclock.ui.sheets

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.FileInputStream

object CustomSoundStorage {

    fun persistIfExternal(context: Context, uri: Uri?): Uri? {
        if (uri == null) return null

        val authority = uri.authority.orEmpty()
        if (authority == "settings" || (authority == "media" && uri.toString().contains("/internal/"))) {
            return uri
        }

        return runCatching {
            val dir = File(context.filesDir, "custom_alarm_sounds").apply { mkdirs() }
            val displayName = resolveDisplayName(context, uri)
            val safeName = displayName.replace(Regex("[^a-zA-Z0-9._ -]"), "_")
            val destFile = File(dir, "${System.currentTimeMillis()}_$safeName")

            val inputStream = if (uri.scheme == "file" && uri.path != null) {
                FileInputStream(File(uri.path!!))
            } else {
                context.contentResolver.openInputStream(uri)
            }

            inputStream?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: error("Could not open input stream for $uri")

            if (!destFile.exists() || destFile.length() == 0L) {
                destFile.delete()
                error("Copied file was 0 bytes for $uri")
            }

            Log.d("CustomSoundStorage", "Copied ${destFile.length()} bytes to ${destFile.absolutePath}")
            Uri.fromFile(destFile)
        }.getOrElse { e ->
            Log.e("CustomSoundStorage", "Failed to copy $uri: ${e.message}", e)
            null
        }
    }

    fun getSoundTitle(context: Context, uriString: String?): String {
        if (uriString == null) return "Default"
        val uri = Uri.parse(uriString)
        if (uri.scheme == "file") {
            val rawName = File(uri.path.orEmpty()).name
            return rawName.substringAfter("_", rawName).substringBeforeLast(".")
        }
        return runCatching {
            RingtoneManager.getRingtone(context, uri)?.getTitle(context)
        }.getOrNull() ?: "Custom sound"
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String {
        if (uri.scheme == "file") {
            return File(uri.path.orEmpty()).name.ifBlank { "custom_alarm.mp3" }
        }
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) {
                    val name = cursor.getString(idx)
                    if (!name.isNullOrBlank()) return name
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "custom_alarm.mp3"
    }
}