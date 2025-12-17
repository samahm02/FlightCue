package com.example.flightcue.data.replay

import android.content.Context
import android.content.Intent
import android.net.Uri

class ReplayRepository {
    fun openRecording(context: Context, uri: Uri): ReplaySource {
        takePersistableIfPossible(context, uri)
        val rec = RecordingParser.parse(context, uri)
        return ReplaySource(rec)
    }

    private fun takePersistableIfPossible(context: Context, uri: Uri) {
        // With SAF (OpenDocument), we can persist the read permission if granted.
        // Only READ is needed for replays.
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Best effort; SAFE-TO-IGNORE if not granted.
        }
    }
}
