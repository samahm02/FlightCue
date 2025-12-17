package com.example.flightcue.data.modelspec

object AppPaths {
    const val TAKEOFF_DIR = "models/takeoff"
    const val LANDING_DIR = "models/landing"

    const val MODEL_FILE_TO = "takeoff_0815.onnx"
    const val FEATURES_FILE_TO = "takeoff_0815.features.json"
    const val MEDIANS_FILE_TO = "takeoff_0815.medians.json"
    const val PROFILE_FILE_TO = "takeoff_0815.profile.json"

    const val MODEL_FILE_LD = "landing_0776.onnx"
    const val FEATURES_FILE_LD = "landing_0776.features.json"
    const val MEDIANS_FILE_LD = "landing_0776.medians.json"
    const val PROFILE_FILE_LD = "landing_0776.profile.json"

    const val EVENT_TAKEOFF = "TAKEOFF"
    const val EVENT_LANDING = "LANDING"

    const val FGS_CHANNEL_ID = "flightcue_monitoring"
    const val FGS_CHANNEL_NAME = "Flight monitoring"
    const val FGS_NOTIFICATION_ID = 1001

    // ---- Sprint C: Local event log ----
    const val LOG_DIR_NAME = "flight_logs"
    const val LOG_FILE_BASE = "flightlog.jsonl"
    const val LOG_MAX_BYTES: Long = 5L * 1024L * 1024L // 5 MB
    const val LOG_KEEP: Int = 2                         // flightlog.1.jsonl .. flightlog.2.jsonl
}
