package com.example.flightcue.data.replay

import android.content.Context
import android.content.Intent
import android.net.Uri

// ReplayRepository.kt
// Entry point for opening a recording from a URI.
// Takes a persistable read permission where possible and delegates parsing to RecordingParser.
class ReplayRepository {

    fun openRecording(context: Context, uri: Uri): Recording {
        takePersistableIfPossible(context, uri)
        return RecordingParser.parse(context, uri)
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
