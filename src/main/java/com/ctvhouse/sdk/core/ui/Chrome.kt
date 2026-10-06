package com.ctvhouse.sdk.core.ui

/** Snapshot of overlay chrome for one frame. */
internal data class Chrome(
    val marking: String,
    val skipLabel: String,
    val skipEnabled: Boolean,
    val landingLabel: String = "",
    val landingAvailable: Boolean = false,
    val muted: Boolean = false,
    val paused: Boolean = false,
    val pauseAvailable: Boolean = false,
    val muteAvailable: Boolean = false,
    val infoAvailable: Boolean = false,
)
