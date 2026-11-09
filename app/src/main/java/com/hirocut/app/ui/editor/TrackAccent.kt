package com.hirocut.app.ui.editor

import androidx.compose.ui.graphics.Color

/** Stable display-order palette shared by the main tracks and sidebar. */
fun trackAccent(index: Int): Color = listOf(
    Color(0xFF8FE3C2),
    Color(0xFF8CC8FF),
    Color(0xFFFFC978),
    Color(0xFFE7A7FF),
    Color(0xFFFF9F9F),
)[index % 5]
