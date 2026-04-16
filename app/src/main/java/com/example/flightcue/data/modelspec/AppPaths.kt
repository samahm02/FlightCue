package com.example.flightcue.data.modelspec

/** Central location for asset paths and log file configuration. */
object AppPaths {

    // ---- Model asset directories (under assets/) ----
    const val TAKEOFF_DIR = "models/gru/takeoff"
    const val LANDING_DIR = "models/gru/landing"

    // ---- Local event log ----
    const val LOG_DIR_NAME  = "flight_logs"
    const val LOG_FILE_BASE = "flightlog.jsonl"
    const val LOG_MAX_BYTES: Long = 5L * 1024L * 1024L  // rotate at 5 MB
    const val LOG_KEEP: Int = 2                          // flightlog.1.jsonl .. flightlog.2.jsonl
}