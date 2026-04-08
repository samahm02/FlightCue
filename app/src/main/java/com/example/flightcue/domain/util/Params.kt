package com.example.flightcue.domain.util

// Runtime constants that mirror preprocess params.json (phase 2 "parity mode").
object Params {
    // resample
    const val ACCEL_HZ = 20.0
    const val BARO_HZ  = 1.0
    const val BIG_GAP_FACTOR = 5.0

    // training-only knobs we keep identical (for feature parity)
    //const val COVERAGE = 0.90
    //const val WINDOW_ANCHOR_RIGHT = true
    const val DO_PSD = true
    //const val ROBUST_PER_FLIGHT = true
    // com.example.flightcue.domain.util.Params
    const val RUN_PARITY_AT_START = true

    //const val ROBUST_TAU_S: Double = 120.0


    // windows (right-anchored), identical to preprocessor
    //const val WIN = 10.0; const val HOP = 7.0
   // const val WIN_TO = 20.0; const val HOP_TO = 10.0
    //const val WIN_LD = 24.0; const val HOP_LD = 12.0

    // stream filters (match script)
    const val GRAV_TAU_S = 0.6
    const val DYN_TAU_S  = 2.0
    const val DHDT_TAU_S = 2.0
    const val P0_HORIZON_S = 180.0

    // feature thresholds (match script)
    const val DHDT_PLATEAU_THR = 0.3
    const val ZCR_THR = 0.05
    const val RUNLEN_CLIMB_THR = 0.2
    const val RUNLEN_DESC_THR  = -0.2


    const val ENABLE_MEDIAN_WARMUP = false  // flip to true when diagnosing model IO
    const val LOG_SENSOR_RATES = false       // set true temporarily when you want to verify real Hz


    // Detection trigger settings (match Python training: --cooldown_s 60 --trigger_k 2)
    const val COOLDOWN_SEC = 60.0
    const val MIN_SEP_SEC  = 60.0


    // Params.kt - add:
    const val SENSOR_BATCH_SEC = 5  // buffer up to 5s of samples in hardware FIFO
}