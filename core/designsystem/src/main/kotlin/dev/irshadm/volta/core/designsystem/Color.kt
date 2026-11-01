package dev.irshadm.volta.core.designsystem

import androidx.compose.ui.graphics.Color

/** Colour tokens. Dark only: Volta has no light theme. */
object Wt {
    val Bg = Color(0xFF0B0D10)
    /** The app icon's charcoal, which the car artwork is painted on. */
    val IconTile = Color(0xFF292B30)
    val Surface = Color(0xFF12161B)
    val SurfaceHigh = Color(0xFF1A1F26)
    val Border = Color(0xFF1F2937)
    val BorderStrong = Color(0xFF334155)
    val Text = Color(0xFFF1F5F9)
    val TextSoft = Color(0xFFCBD5E1)
    val Muted = Color(0xFF94A3B8)
    val Dim = Color(0xFF64748B)
    /** Primary accent (buttons, selection, brand): champagne, picked to sit with the warm glow behind the car. */
    val Gold = Color(0xFFD6B98A)
    /** Dashboard accent: picks up the warm dusk glow behind the car photo. */
    val Champagne = Gold
    /** The dashboard's single soft panel, and the tracks inside it. */
    val Panel = Color(0xFF15181C)
    val PanelTrack = Color(0xFF1F2227)
    val GoldInk = Color(0xFF1A1206)
    val Green = Color(0xFF22C55E)
    val Amber = Color(0xFFF59E0B)
    /** Act-now alerts only (unlocked, open while parked, very low battery). */
    val Red = Color(0xFFF87171)
    val RedInk = Color(0xFF1A0B0B)
    val AmberText = Color(0xFFFCD9A0)
    val AmberTint = Color(0x24F59E0B)
    val Blue = Color(0xFF3B82F6)
    val BlueSoft = Color(0xFF60A5FA)
    val Purple = Color(0xFFA855F7)
    val PurpleSoft = Color(0xFFC084FC)
    val Orange = Color(0xFFF97316)

    fun tint(c: Color, alpha: Float = 0.16f) = c.copy(alpha = alpha)
}
