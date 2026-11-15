package com.swift.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * One place that says whether the browser UI is dark or light right now.
 * Every colour in the app reads [dark], so flipping it recolours everything.
 * Private tabs always use the dark look.
 */
object SwiftPalette {
    var dark by mutableStateOf(true)
    var priv by mutableStateOf(false)
}

/** Dark / light / private (deep indigo, like Brave's private tabs). */
internal fun themed(dark: Long, light: Long, priv: Long): Color =
    Color(if (SwiftPalette.priv) priv else if (SwiftPalette.dark) dark else light)

internal fun themed(dark: Long, light: Long): Color = Color(if (SwiftPalette.dark) dark else light)
