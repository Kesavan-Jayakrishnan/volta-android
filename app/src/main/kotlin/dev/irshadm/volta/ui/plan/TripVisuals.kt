package dev.irshadm.volta.ui.plan

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import dev.irshadm.volta.R
import dev.irshadm.volta.core.designsystem.LocalReducedMotion
import dev.irshadm.volta.core.designsystem.VoltaColors
import dev.irshadm.volta.core.designsystem.VoltaMotion
import dev.irshadm.volta.core.planner.PlanOutcome
import dev.irshadm.volta.data.maps.PlannedTrip
import com.google.android.gms.maps.model.LatLng as GLatLng

/**
 * Elevation area (grey) with the predicted battery line (green; amber below the buffer) and gold
 * dots at charging stops. The whole chart "draws in" left to right when a new plan arrives.
 */
@Composable
fun ElevationChart(trip: PlannedTrip, bufferSoc: Double, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val reveal = remember(trip) { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(trip) { if (!reduced) reveal.animateTo(1f, tween(1100, easing = VoltaMotion.EaseOut)) }
    val profile = trip.profile
    val trace = trip.plan.socTrace
    val total = trip.route.distanceM
    val minE = profile.minOf { it.elevationM }
    val maxE = maxOf(profile.maxOf { it.elevationM }, minE + 50)
    val stops = (trip.plan.outcome as? PlanOutcome.Ok)?.stops ?: (trip.plan.outcome as? PlanOutcome.Unreachable)?.stops.orEmpty()
    val desc = "Elevation from ${minE.toInt()} to ${maxE.toInt()} metres; battery from ${trace.first().socPercent.toInt()} to ${trace.last().socPercent.toInt()} percent"
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val labelStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = VoltaColors.Muted)

    // Compact: the battery line over a low terrain band, the buffer dashed, start and end % labelled on the line.
    Canvas(modifier.fillMaxWidth().height(96.dp).semantics { contentDescription = desc }) {
        val inset = 7.dp.toPx() // room for the end ring and the labels
        val w = size.width - inset * 2
        val h = size.height
        val top = 10.dp.toPx()
        fun x(d: Double) = inset + (d / total * w).toFloat()
        fun yE(e: Double) = (h - (e - minE) / (maxE - minE) * h * 0.38).toFloat()
        fun yS(s: Double) = (top + (1 - s.coerceIn(0.0, 100.0) / 100.0) * (h - top)).toFloat()

        clipRect(right = inset + w * reveal.value + inset) {
            val area = Path().apply {
                moveTo(x(0.0), h)
                profile.forEach { lineTo(x(it.distanceM), yE(it.elevationM)) }
                lineTo(x(total), h)
                close()
            }
            drawPath(area, VoltaColors.tint(VoltaColors.Muted, 0.12f))
            val ridge = Path().apply {
                profile.forEachIndexed { i, p -> if (i == 0) moveTo(x(p.distanceM), yE(p.elevationM)) else lineTo(x(p.distanceM), yE(p.elevationM)) }
            }
            drawPath(ridge, VoltaColors.tint(VoltaColors.Muted, 0.4f), style = Stroke(1.dp.toPx()))

            drawLine(
                VoltaColors.tint(VoltaColors.Amber, 0.5f),
                Offset(x(0.0), yS(bufferSoc)),
                Offset(x(total), yS(bufferSoc)),
                1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
            )

            val line = Path().apply {
                trace.forEachIndexed { i, p -> if (i == 0) moveTo(x(p.distanceM), yS(p.socPercent)) else lineTo(x(p.distanceM), yS(p.socPercent)) }
            }
            drawPath(line, VoltaColors.Champagne, style = Stroke(2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            stops.forEach { s ->
                val sx = x(s.charger.alongRouteM)
                drawCircle(VoltaColors.Bg, 5.dp.toPx(), Offset(sx, yS(s.departSoc)))
                drawCircle(VoltaColors.Gold, 3.5.dp.toPx(), Offset(sx, yS(s.departSoc)))
            }
            val first = trace.first()
            val end = trace.last()
            drawCircle(VoltaColors.Bg, 4.5.dp.toPx(), Offset(x(end.distanceM), yS(end.socPercent)))
            drawCircle(if (end.socPercent < bufferSoc) VoltaColors.Amber else VoltaColors.Champagne, 4.5.dp.toPx(), Offset(x(end.distanceM), yS(end.socPercent)), style = Stroke(2.dp.toPx()))

            // Start and end percentages sit just above the line at each end; the buffer is named at the left.
            val startLabel = measurer.measure("${first.socPercent.toInt()} %", labelStyle.copy(color = VoltaColors.TextSoft))
            drawText(startLabel, topLeft = Offset(x(0.0), (yS(first.socPercent) - startLabel.size.height - 4.dp.toPx()).coerceAtLeast(0f)))
            val endLabel = measurer.measure("${end.socPercent.toInt()} %", labelStyle.copy(color = if (end.socPercent < bufferSoc) VoltaColors.Amber else VoltaColors.Champagne))
            drawText(endLabel, topLeft = Offset(x(total) - endLabel.size.width, (yS(end.socPercent) - endLabel.size.height - 6.dp.toPx()).coerceAtLeast(0f)))
            val bufferLabel = measurer.measure("buffer ${bufferSoc.toInt()} %", labelStyle.copy(color = VoltaColors.tint(VoltaColors.Amber, 0.8f)))
            val by = yS(bufferSoc) + 3.dp.toPx()
            if (by + bufferLabel.size.height < h - 2.dp.toPx() && kotlin.math.abs(yS(bufferSoc) - yS(first.socPercent)) > 14.dp.toPx()) {
                drawText(bufferLabel, topLeft = Offset(x(0.0), by))
            }
        }
    }
}

/** Dark-styled map: route in gold, planned stops gold, other chargers blue. */
@Composable
fun RouteMap(trip: PlannedTrip, modifier: Modifier = Modifier, bottomPadding: androidx.compose.ui.unit.Dp = 0.dp, radius: androidx.compose.ui.unit.Dp = 20.dp) {
    val context = LocalContext.current
    val style = remember { MapStyleOptions.loadRawResourceStyle(context, R.raw.map_dark) }
    val camera = rememberCameraPositionState()
    val path = remember(trip) { trip.route.path.map { GLatLng(it.lat, it.lng) } }
    val stops = (trip.plan.outcome as? PlanOutcome.Ok)?.stops ?: (trip.plan.outcome as? PlanOutcome.Unreachable)?.stops.orEmpty()
    val stopIds = stops.map { it.charger.id }.toSet()

    val density = LocalDensity.current
    var loaded by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(radius)).background(VoltaColors.Bg)) {
        KeyedGoogleMap(
            cameraPositionState = camera,
            properties = MapProperties(mapStyleOptions = style),
            uiSettings = MapUiSettings(zoomControlsEnabled = false, mapToolbarEnabled = false, compassEnabled = false),
            googleMapOptionsFactory = { com.google.android.gms.maps.GoogleMapOptions().backgroundColor(VoltaColors.Bg.toArgb()) },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = bottomPadding),
            onMapLoaded = {
                val bounds = LatLngBounds.builder().apply { path.forEach { include(it) } }.build()
                camera.move(CameraUpdateFactory.newLatLngBounds(bounds, with(density) { 56.dp.roundToPx() }))
                loaded = true
            },
        ) {
            Polyline(points = path, color = VoltaColors.Gold, width = 10f)
            // Volta's own markers: the car where you start, a flag where you're going (no Google pins).
            val carIcon = remember { BitmapDescriptorFactory.fromBitmap(markerBitmap(MarkerGlyph.Car)) }
            val flagIcon = remember { BitmapDescriptorFactory.fromBitmap(markerBitmap(MarkerGlyph.Flag)) }
            Marker(rememberUpdatedMarkerState(path.first()), title = "Start", icon = carIcon, anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f), zIndex = 3f)
            Marker(rememberUpdatedMarkerState(path.last()), title = trip.destination.name, icon = flagIcon, anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f), zIndex = 3f)
            val dot = remember { BitmapDescriptorFactory.fromBitmap(dotBitmap()) }
            trip.chargers.forEach { c ->
                val planned = c.id in stopIds
                Marker(
                    rememberUpdatedMarkerState(GLatLng(c.position.lat, c.position.lng)),
                    title = c.name,
                    snippet = "${c.maxKw.toInt()} kW · ${(c.alongRouteM / 1000).toInt()} km",
                    icon = if (planned) BitmapDescriptorFactory.defaultMarker(38f) else dot,
                    anchor = if (planned) androidx.compose.ui.geometry.Offset(0.5f, 1f) else androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
                    zIndex = if (planned) 2f else 1f,
                )
            }
        }
        // Covers the light flash before the dark style and tiles are in.
        val cover by androidx.compose.animation.core.animateFloatAsState(if (loaded || !interactiveMapAvailable) 0f else 1f, androidx.compose.animation.core.tween(350), label = "cover")
        if (cover > 0f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = cover }.background(VoltaColors.Bg))
    }
}

private enum class MarkerGlyph { Car, Flag }

/** A round gold badge with the car or the flag drawn in it, for the start and end of the route. */
private fun markerBitmap(glyph: MarkerGlyph): android.graphics.Bitmap {
    val size = 84
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    p.color = 0xFF0B0D10.toInt()
    c.drawCircle(size / 2f, size / 2f, size / 2f, p)
    p.color = if (glyph == MarkerGlyph.Car) 0xFFF1F1F1.toInt() else 0xFFD4AF7C.toInt()
    c.drawCircle(size / 2f, size / 2f, size / 2f - 5, p)
    // Icons are 24-unit paths, scaled to the badge.
    val path = android.graphics.Path()
    val s = size / 24f * 0.58f
    val off = (size - 24 * s) / 2f
    val m = android.graphics.Matrix().apply {
        setScale(s, s)
        postTranslate(off, off)
    }
    val stroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 2.2f * s
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
        color = 0xFF1A1407.toInt()
    }
    // Lucide's car and flag (ISC), as path data.
    val data = when (glyph) {
        MarkerGlyph.Car -> listOf("M19 17 h2 c.6 0 1 -.4 1 -1 v-3 c0 -.9 -.7 -1.7 -1.5 -1.9 C18.7 10.6 16 10 16 10 s-1.3 -1.4 -2.2 -2.3 c-.5 -.4 -1.1 -.7 -1.8 -.7 H5 c-.6 0 -1.1 .4 -1.4 .9 l-1.4 2.9 A3.7 3.7 0 0 0 2 12 v4 c0 .6 .4 1 1 1 h2", "M5 17 a2 2 0 1 0 4 0 a2 2 0 1 0 -4 0", "M9 17 h6", "M15 17 a2 2 0 1 0 4 0 a2 2 0 1 0 -4 0")
        MarkerGlyph.Flag -> listOf("M4 22 V4 a1 1 0 0 1 .4 -.8 A6 6 0 0 1 8 2 c3 0 5 2 7.333 2 q2 0 3.067 -.8 A1 1 0 0 1 20 4 v10 a1 1 0 0 1 -.4 .8 A6 6 0 0 1 16 16 c-3 0 -5 -2 -8 -2 a6 6 0 0 0 -4 1.528")
    }
    data.forEach { d -> path.addPath(androidx.core.graphics.PathParser.createPathFromPathData(d)) }
    path.transform(m)
    c.drawPath(path, stroke)
    return bmp
}

/** Small blue dot for chargers that aren't part of the plan — keeps the map readable. */
private fun dotBitmap(): android.graphics.Bitmap {
    val size = 30
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    p.color = 0xFF0B0D10.toInt()
    c.drawCircle(size / 2f, size / 2f, size / 2f, p)
    p.color = 0xFF60A5FA.toInt()
    c.drawCircle(size / 2f, size / 2f, size / 2f - 4, p)
    return bmp
}
