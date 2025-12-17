package com.example.flightcue.domain.events

import com.example.flightcue.BuildConfig

/**
 * Public broadcast contract for flight events.
 *
 * Other apps (or automation tools) can register a receiver for these
 * ACTION_* intents to react when FlightCue detects or marks a flight.
 */
object FlightIntents {
    private const val PREFIX = "${BuildConfig.APPLICATION_ID}."

    // Sent when the FSM transitions NotFlying -> Flying
    const val ACTION_TAKEOFF_DETECTED = PREFIX + "ACTION_TAKEOFF_DETECTED"

    // Sent when the FSM transitions Flying -> NotFlying
    const val ACTION_LANDING_DETECTED = PREFIX + "ACTION_LANDING_DETECTED"

    // Extras for both actions
    const val EXTRA_EVENT_MODE = "mode"               // String: "AUTO" or "FORCED"
    const val EXTRA_CONFIDENCE = "confidence"         // Double: model confidence
    const val EXTRA_AT_ELAPSED_SEC = "at_elapsed_sec" // Double: seconds on detector clock
}
