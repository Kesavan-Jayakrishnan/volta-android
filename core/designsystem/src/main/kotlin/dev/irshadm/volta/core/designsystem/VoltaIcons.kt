package dev.irshadm.volta.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Stroke icons (24×24, 2 px, round caps and joins) from Lucide (https://lucide.dev, ISC licence; see NOTICE).
 * The names are Volta's; the Lucide name is in each icon's id. Tint them with Icon(tint = …).
 */
object VoltaIcons {
    private fun icon(name: String, vararg paths: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach {
            addPath(
                pathData = addPathNodes(it),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

    val Locked = icon("lock", "M5 11 h14 a2 2 0 0 1 2 2 v7 a2 2 0 0 1 -2 2 h-14 a2 2 0 0 1 -2 -2 v-7 a2 2 0 0 1 2 -2 z", "M7 11 V7 a5 5 0 0 1 10 0 v4")
    val Unlocked = icon("lock-open", "M5 11 h14 a2 2 0 0 1 2 2 v7 a2 2 0 0 1 -2 2 h-14 a2 2 0 0 1 -2 -2 v-7 a2 2 0 0 1 2 -2 z", "M7 11 V7 a5 5 0 0 1 9.9 -1")
    val Tyre = icon("circle-dot", "M11 12 a1 1 0 1 0 2 0 a1 1 0 1 0 -2 0", "M2 12 a10 10 0 1 0 20 0 a10 10 0 1 0 -20 0")
    val Battery12V = icon("car-battery", "M14 13 h4", "M16 15 v-4", "M18 5 v2", "M6 13 h4", "M6 5 v2", "M4 7 h16 a2 2 0 0 1 2 2 v8 a2 2 0 0 1 -2 2 h-16 a2 2 0 0 1 -2 -2 v-8 a2 2 0 0 1 2 -2 z")
    val Thermo = icon("thermometer", "M14 4 v10.54 a4 4 0 1 1 -4 0 V4 a2 2 0 0 1 4 0 Z")
    val Plug = icon("plug", "M12 22 v-5", "M15 8 V2", "M17 8 a1 1 0 0 1 1 1 v4 a4 4 0 0 1 -4 4 h-4 a4 4 0 0 1 -4 -4 V9 a1 1 0 0 1 1 -1 z", "M9 8 V2")
    val Bolt = icon("zap", "M15.914 4 a1.5 1.5 0 0 0 -2.474 -1.561 l-9 9 A1.5 1.5 0 0 0 5.5 14 h4.002 a.5 .5 0 0 1 .471 .666 L8.086 20 a1.5 1.5 0 0 0 2.475 1.56 l9 -9 A1.5 1.5 0 0 0 18.5 10 h-3.997 a.5 .5 0 0 1 -.472 -.667 z")
    val Map = icon("map", "M14.106 5.553 a2 2 0 0 0 1.788 0 l3.659 -1.83 A1 1 0 0 1 21 4.619 v12.764 a1 1 0 0 1 -.553 .894 l-4.553 2.277 a2 2 0 0 1 -1.788 0 l-4.212 -2.106 a2 2 0 0 0 -1.788 0 l-3.659 1.83 A1 1 0 0 1 3 19.381 V6.618 a1 1 0 0 1 .553 -.894 l4.553 -2.277 a2 2 0 0 1 1.788 0 z", "M15 5.764 v15", "M9 3.236 v15")
    val Pin = icon("map-pin", "M20 10 c0 4.993 -5.539 10.193 -7.399 11.799 a1 1 0 0 1 -1.202 0 C9.539 20.193 4 14.993 4 10 a8 8 0 0 1 16 0", "M9 10 a3 3 0 1 0 6 0 a3 3 0 1 0 -6 0")
    val Trend = icon("trending-up", "M16 7 h6 v6", "m22 7 -8.5 8.5 -5 -5 L2 17")
    val Bars = icon("chart-column", "M3 3 v16 a2 2 0 0 0 2 2 h16", "M18 17 V9", "M13 17 V5", "M8 17 v-3")
    val BatteryTab = icon("battery-medium", "M10 14 v-4", "M22 14 v-4", "M6 14 v-4", "M4 6 h12 a2 2 0 0 1 2 2 v8 a2 2 0 0 1 -2 2 h-12 a2 2 0 0 1 -2 -2 v-8 a2 2 0 0 1 2 -2 z")
    val Warning = icon("triangle-alert", "m21.73 18 -8 -14 a2 2 0 0 0 -3.48 0 l-8 14 A2 2 0 0 0 4 21 h16 a2 2 0 0 0 1.73 -3", "M12 9 v4", "M12 17 h.01")
    val Refresh = icon("refresh-cw", "M3 12 a9 9 0 0 1 9 -9 9.75 9.75 0 0 1 6.74 2.74 L21 8", "M21 3 v5 h-5", "M21 12 a9 9 0 0 1 -9 9 9.75 9.75 0 0 1 -6.74 -2.74 L3 16", "M8 16 H3 v5")
    val Dashboard = icon("layout-dashboard", "M4 3 h5 a1 1 0 0 1 1 1 v7 a1 1 0 0 1 -1 1 h-5 a1 1 0 0 1 -1 -1 v-7 a1 1 0 0 1 1 -1 z", "M15 3 h5 a1 1 0 0 1 1 1 v3 a1 1 0 0 1 -1 1 h-5 a1 1 0 0 1 -1 -1 v-3 a1 1 0 0 1 1 -1 z", "M15 12 h5 a1 1 0 0 1 1 1 v7 a1 1 0 0 1 -1 1 h-5 a1 1 0 0 1 -1 -1 v-7 a1 1 0 0 1 1 -1 z", "M4 16 h5 a1 1 0 0 1 1 1 v3 a1 1 0 0 1 -1 1 h-5 a1 1 0 0 1 -1 -1 v-3 a1 1 0 0 1 1 -1 z")
    val Snow = icon("snowflake", "m10 20 -1.25 -2.5 L6 18", "M10 4 8.75 6.5 6 6", "m14 20 1.25 -2.5 L18 18", "m14 4 1.25 2.5 L18 6", "m17 21 -3 -6 h-4", "m17 3 -3 6 1.5 3", "M2 12 h6.5 L10 9", "m20 10 -1.5 2 1.5 2", "M22 12 h-6.5 L14 15", "m4 10 1.5 2 L4 14", "m7 21 3 -6 -1.5 -3", "m7 3 3 6 h4")
    val Horn = icon("megaphone", "M11 6 a13 13 0 0 0 8.4 -2.8 A1 1 0 0 1 21 4 v12 a1 1 0 0 1 -1.6 .8 A13 13 0 0 0 11 14 H5 a2 2 0 0 1 -2 -2 V8 a2 2 0 0 1 2 -2 z", "M6 14 a12 12 0 0 0 2.4 7.2 2 2 0 0 0 3.2 -2.4 A8 8 0 0 1 10 14", "M8 6 v8")
    val Walk = icon("footprints", "M4 16 v-2.38 C4 11.5 2.97 10.5 3 8 c.03 -2.72 1.49 -6 4.5 -6 C9.37 2 10 3.8 10 5.5 c0 3.11 -2 5.66 -2 8.68 V16 a2 2 0 1 1 -4 0 Z", "M20 20 v-2.38 c0 -2.12 1.03 -3.12 1 -5.62 -.03 -2.72 -1.49 -6 -4.5 -6 C14.63 6 14 7.8 14 9.5 c0 3.11 2 5.66 2 8.68 V20 a2 2 0 1 0 4 0 Z", "M16 17 h4", "M4 13 h4")
    val Check = icon("check", "M20 6 9 17 l-5 -5")
    val Boot = icon("car", "M19 17 h2 c.6 0 1 -.4 1 -1 v-3 c0 -.9 -.7 -1.7 -1.5 -1.9 C18.7 10.6 16 10 16 10 s-1.3 -1.4 -2.2 -2.3 c-.5 -.4 -1.1 -.7 -1.8 -.7 H5 c-.6 0 -1.1 .4 -1.4 .9 l-1.4 2.9 A3.7 3.7 0 0 0 2 12 v4 c0 .6 .4 1 1 1 h2", "M5 17 a2 2 0 1 0 4 0 a2 2 0 1 0 -4 0", "M9 17 h6", "M15 17 a2 2 0 1 0 4 0 a2 2 0 1 0 -4 0")
    val Window = icon("app-window", "M4 4 h16 a2 2 0 0 1 2 2 v12 a2 2 0 0 1 -2 2 h-16 a2 2 0 0 1 -2 -2 v-12 a2 2 0 0 1 2 -2 z", "M10 4 v4", "M2 8 h20", "M6 4 v4")
    val Bulb = icon("lightbulb", "M15 14 c.2 -1 .7 -1.7 1.5 -2.5 1 -.9 1.5 -2.2 1.5 -3.5 A6 6 0 0 0 6 8 c0 1 .2 2.2 1.5 3.5 .7 .7 1.3 1.5 1.5 2.5", "M9 18 h6", "M10 22 h4")
    val Bell = icon("bell", "M10.268 21 a2 2 0 0 0 3.464 0", "M3.262 15.326 A1 1 0 0 0 4 17 h16 a1 1 0 0 0 .74 -1.673 C19.41 13.956 18 12.499 18 8 A6 6 0 0 0 6 8 c0 4.499 -1.411 5.956 -2.738 7.326")
    val Gauge = icon("gauge", "m12 14 4 -4", "M3.34 19 a10 10 0 1 1 17.32 0")
    val Home = icon("house", "M15 21 v-8 a1 1 0 0 0 -1 -1 h-4 a1 1 0 0 0 -1 1 v8", "M3 10 a2 2 0 0 1 .709 -1.528 l7 -6 a2 2 0 0 1 2.582 0 l7 6 A2 2 0 0 1 21 10 v9 a2 2 0 0 1 -2 2 H5 a2 2 0 0 1 -2 -2 z")
    val Rupee = icon("indian-rupee", "m14 20 -8 -8 h4 c5.333 0 5.333 -8 0 -8", "M6 4 h12", "M6 8 h12")
    val Info = icon("info", "M2 12 a10 10 0 1 0 20 0 a10 10 0 1 0 -20 0", "M12 16 v-4", "M12 8 h.01")
    val Plus = icon("plus", "M5 12 h14", "M12 5 v14")
    val Minus = icon("minus", "M5 12 h14")
    val Flag = icon("flag", "M4 22 V4 a1 1 0 0 1 .4 -.8 A6 6 0 0 1 8 2 c3 0 5 2 7.333 2 q2 0 3.067 -.8 A1 1 0 0 1 20 4 v10 a1 1 0 0 1 -.4 .8 A6 6 0 0 1 16 16 c-3 0 -5 -2 -8 -2 a6 6 0 0 0 -4 1.528")
    val Clock = icon("clock", "M2 12 a10 10 0 1 0 20 0 a10 10 0 1 0 -20 0", "M12 6 v6 l4 2")
    val Navigate = icon("navigation", "M3 11 L22 2 L13 21 L11 13 L3 11 z")
    val Mountain = icon("mountain", "m8 3 4 8 5 -5 5 15 H2 L8 3 z")
    val Route = icon("route", "M3 19 a3 3 0 1 0 6 0 a3 3 0 1 0 -6 0", "M9 19 h8.5 a3.5 3.5 0 0 0 0 -7 h-11 a3.5 3.5 0 0 1 0 -7 H15", "M15 5 a3 3 0 1 0 6 0 a3 3 0 1 0 -6 0")
    val Coffee = icon("coffee", "M10 2 v2", "M14 2 v2", "M16 8 a1 1 0 0 1 1 1 v8 a4 4 0 0 1 -4 4 H7 a4 4 0 0 1 -4 -4 V9 a1 1 0 0 1 1 -1 h14 a4 4 0 1 1 0 8 h-1", "M6 2 v2")
    val Car = icon("car", "M19 17 h2 c.6 0 1 -.4 1 -1 v-3 c0 -.9 -.7 -1.7 -1.5 -1.9 C18.7 10.6 16 10 16 10 s-1.3 -1.4 -2.2 -2.3 c-.5 -.4 -1.1 -.7 -1.8 -.7 H5 c-.6 0 -1.1 .4 -1.4 .9 l-1.4 2.9 A3.7 3.7 0 0 0 2 12 v4 c0 .6 .4 1 1 1 h2", "M5 17 a2 2 0 1 0 4 0 a2 2 0 1 0 -4 0", "M9 17 h6", "M15 17 a2 2 0 1 0 4 0 a2 2 0 1 0 -4 0")
    val Sliders = icon("sliders-horizontal", "M10 5 H3", "M12 19 H3", "M14 3 v4", "M16 17 v4", "M21 12 h-9", "M21 19 h-5", "M21 5 h-7", "M8 10 v4", "M8 12 H3")
    val Chevron = icon("chevron-right", "m9 18 6 -6 -6 -6")
    val Back = icon("chevron-left", "m15 18 -6 -6 6 -6")
    val Gear = icon("settings", "M9.671 4.136 a2.34 2.34 0 0 1 4.659 0 2.34 2.34 0 0 0 3.319 1.915 2.34 2.34 0 0 1 2.33 4.033 2.34 2.34 0 0 0 0 3.831 2.34 2.34 0 0 1 -2.33 4.033 2.34 2.34 0 0 0 -3.319 1.915 2.34 2.34 0 0 1 -4.659 0 2.34 2.34 0 0 0 -3.32 -1.915 2.34 2.34 0 0 1 -2.33 -4.033 2.34 2.34 0 0 0 0 -3.831 A2.34 2.34 0 0 1 6.35 6.051 a2.34 2.34 0 0 0 3.319 -1.915", "M9 12 a3 3 0 1 0 6 0 a3 3 0 1 0 -6 0")
    val Key = icon("key-round", "M2.586 17.414 A2 2 0 0 0 2 18.828 V21 a1 1 0 0 0 1 1 h3 a1 1 0 0 0 1 -1 v-1 a1 1 0 0 1 1 -1 h1 a1 1 0 0 0 1 -1 v-1 a1 1 0 0 1 1 -1 h.172 a2 2 0 0 0 1.414 -.586 l.814 -.814 a6.5 6.5 0 1 0 -4 -4 z", "M16 7.5 a.5 .5 0 1 0 1 0 a.5 .5 0 1 0 -1 0")
    val ExternalLink = icon("external-link", "M15 3 h6 v6", "M10 14 21 3", "M18 13 v6 a2 2 0 0 1 -2 2 H5 a2 2 0 0 1 -2 -2 V8 a2 2 0 0 1 2 -2 h6")
    val Close = icon("x", "M18 6 6 18", "m6 6 12 12")
}
