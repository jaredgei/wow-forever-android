package app.gamenative.utils

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.File

object StorageUtils {
    fun hasStoragePermission(context: Context, path: String): Boolean {
        val isInsideSandbox = path.contains("/Android/data/${context.packageName}") || path.contains(context.dataDir.path)
        if (isInsideSandbox) return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun requestManageExternalStoragePermission(context: Context): Boolean {
        val packageUri = Uri.parse("package:${context.packageName}")
        val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
        val candidates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            listOf(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri),
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                appDetails,
            )
        } else {
            listOf(appDetails)
        }
        return candidates.any { intent ->
            runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        }
    }

    fun getPathFromTreeUri(context: Context, uri: Uri?): String? {
        if (uri == null) return null
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            when {
                docId.startsWith("primary:") -> primaryStoragePath(docId)
                docId.contains(":") -> {
                    val (volumeId, subPath) = docId.split(":", limit = 2)
                    joinPath(resolveVolumeRoot(context, volumeId), subPath)
                }
                else -> docId
            }
        } catch (_: Exception) {
            uri.path?.removePrefix("/tree/")?.let { if (it.startsWith("primary:")) primaryStoragePath(it) else it }
        }
    }

    private fun primaryStoragePath(docId: String) =
        joinPath(Environment.getExternalStorageDirectory().path, docId.substringAfter(":"))

    private fun joinPath(root: String, subPath: String) = if (subPath.isEmpty()) root else "$root/$subPath"

    private fun resolveVolumeRoot(context: Context, volumeId: String): String {
        val defaultRoot = "/storage/$volumeId"
        if (File(defaultRoot).exists() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return defaultRoot
        val volume = context.getSystemService(StorageManager::class.java)
            ?.storageVolumes
            ?.firstOrNull { it.uuid?.equals(volumeId, ignoreCase = true) == true }
        return volume?.directory?.absolutePath ?: defaultRoot
    }
}
