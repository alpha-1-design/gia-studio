package com.giastudio.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * "Neobrutalism Minimalism" tokens:
 * paper canvas, ink borders, hard 3dp borders, square corners, a single loud
 * accent (signal yellow), and a strict per-track palette.
 */
object Neo {
    val Paper = Color(0xFFF4EFE3)
    val PaperRaised = Color(0xFFFBF7EC)
    val Ink = Color(0xFF131313)
    val InkSoft = Color(0xFF3A3A34)
    val Accent = Color(0xFFFFD400)
    val AccentHot = Color(0xFFFFB300)
    val Record = Color(0xFFE3342F)
    val RecordDark = Color(0xFFB71C1C)
    val Ok = Color(0xFF2FA84F)
    val Disabled = Color(0xFFC9C2B2)

    val Border = 3.dp
    val Radius = 0.dp
    val Shadow = 5.dp

    /** Track header / clip fills. */
    val TrackPalette = listOf(
        Color(0xFFE3342F), // vocal red
        Color(0xFF1F6FEB), // beat blue
        Color(0xFFEAB308), // keys yellow
        Color(0xFF2FA84F), // bass green
        Color(0xFFEA580C), // guitar orange
        Color(0xFF9333EA), // fx purple
        Color(0xFF0EA5A5), // memo teal
        Color(0xFFDB2777), // live pink
    )

    fun trackColor(index: Int): Color =
        TrackPalette[index % TrackPalette.size]
}
