package com.kitt.audiobridge

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Cross-component state shared between PanelActivity and the edge-handle
 * overlay, both of which run in the same process.
 */
object AppState {

    val panelVisible = MutableStateFlow(false)

    /** Set by PanelActivity while resumed; cleared on pause. */
    var requestMinimizePanel: (() -> Unit)? = null
}
