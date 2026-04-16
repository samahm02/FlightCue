package com.example.flightcue.data.logging

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.flightcue.data.modelspec.AppPaths
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Utilities for exporting and clearing flight log files. */
object LogExporter {

    /** Zips all rotated logs (flightlog*.jsonl) and opens a share sheet. */
    fun shareLogsZip(context: Context) {
        val dir = File(context.filesDir, AppPaths.LOG_DIR_NAME).apply { mkdirs() }
        val logs = dir.listFiles { f -> f.name.startsWith("flightlog") && f.name.endsWith(".jsonl") }
            ?.sortedBy { it.name } ?: emptyList()
        if (logs.isEmpty()) return

        val outDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val zipFile = File(outDir, "flightlogs_$stamp.zip")

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            logs.forEach { file ->
                FileInputStream(file).use { fis ->
                    zos.putNextEntry(ZipEntry(file.name))
                    fis.copyTo(zos)
                    zos.closeEntry()
                }
            }
        }

        shareZipFile(context, zipFile)
    }

    /** Deletes all log files. Returns true if anything was deleted. */
    fun clearAllLogs(context: Context): Boolean {
        val dir = File(context.filesDir, AppPaths.LOG_DIR_NAME)
        if (!dir.exists()) return false
        var any = false
        dir.listFiles()?.forEach { f -> if (f.delete()) any = true }
        return any
    }

    private fun shareZipFile(context: Context, file: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share Flight logs"))
    }
}