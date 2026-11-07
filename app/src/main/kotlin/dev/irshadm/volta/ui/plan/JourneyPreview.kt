package dev.irshadm.volta.ui.plan

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import dev.irshadm.volta.R
import dev.irshadm.volta.core.designsystem.VoltaColors
import dev.irshadm.volta.core.designsystem.tabular
import dev.irshadm.volta.core.planner.LatLng
import dev.irshadm.volta.data.maps.Confidence
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.google.android.gms.maps.model.LatLng as GLatLng

/**
 * The journey preview: the whole route on the map with every stop, and a card for each place from the start to
 * the destination. Swiping the cards, or tapping a pin, brings that place into view with the drive into it lit
 * up; each card says what to do there. Each stop's backup charger is a small hollow amber dot; the shown stop's
 * backup gets its name and a dashed line to the stop.
 */
@Composable
fun JourneyPreview(journey: Journey, originName: String, backups: List<Confidence>, photo: suspend (String?, String?) -> Bitmap?, onClose: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val style = remember { MapStyleOptions.loadRawResourceStyle(context, R.raw.map_dark) }
    val camera = rememberCameraPositionState()
    val scope = rememberCoroutineScope()

    // Places: the start, each stop, the destination.
    val last = journey.stops.size + 1
    val pager = rememberPagerState { last + 1 }
    val sel = pager.currentPage
    val route = remember(journey) { journey.points.map { it.g() } }
    val spots = remember(journey) { listOf(route.first()) + journey.stops.map { it.stop.charger.position.g() } + route.last() }

    /** The drive each card is about: into the place, or the first leg for the start. */
    fun leg(p: Int): Pair<Double, Double> = when (p) {
        0 -> 0.0 to (journey.stops.firstOrNull()?.atM ?: journey.totalM)
        else -> (journey.stops.getOrNull(p - 2)?.atM ?: 0.0) to (journey.stops.getOrNull(p - 1)?.atM ?: journey.totalM)
    }
    val legPath = remember(journey, sel) { leg(sel).let { (a, b) -> journey.pathBetween(a, b).map { it.g() } } }

    var topH by remember { mutableStateOf(110.dp) }
    var bottomH by remember { mutableStateOf(340.dp) }
    var loaded by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(false) }

    // The whole route first, then the selected card's leg.
    LaunchedEffect(loaded, sel, topH, bottomH) {
        if (!loaded) return@LaunchedEffect
        val pad = with(density) { 64.dp.roundToPx() } // room for the pin labels
        if (!shown) {
            camera.move(CameraUpdateFactory.newLatLngBounds(bounds(route), pad))
            shown = true
            delay(900)
        }
        val backup = backups.getOrNull(sel - 1)?.backup?.position?.g()
        camera.animate(CameraUpdateFactory.newLatLngBounds(bounds(legPath + spots[sel] + listOfNotNull(backup)), pad), 700)
    }

    Box(Modifier.fillMaxSize().background(VoltaColors.Bg)) {
        val d = density.density
        val pins = remember(journey) {
            (listOf(startPin(journey, d)) + journey.stops.map { stopPin(it, d) } + destinationPin(journey, d)).map { BitmapDescriptorFactory.fromBitmap(it) }
        }
        val ring = remember { BitmapDescriptorFactory.fromBitmap(ringBitmap(d)) }
        val backupDot = remember { BitmapDescriptorFactory.fromBitmap(backupPin(null, d)) }
        val backupLabels = remember(backups) { backups.map { c -> backupPhrase(c)?.let { BitmapDescriptorFactory.fromBitmap(backupPin("Backup · $it", d)) } } }
        val backupStates = remember(backups) { backups.map { c -> c.backup?.let { MarkerState(it.position.g()) } } }
        val pinStates = remember(journey) { spots.map { MarkerState(it) } }
        // A pill halfway along each leg: distance and drive time. Leg i leads into place i + 1.
        val legs = remember(journey) { (1..last).map { leg(it) } }
        val legLabels = remember(journey) {
            legs.mapIndexed { i, (a, b) ->
                val from = if (i == 0) journey.trip.departAt.toDouble() else journey.stops[i - 1].leaveAt
                val to = journey.stops.getOrNull(i)?.arriveAt ?: journey.arriveAt
                "${((b - a) / 1000).roundToInt()} km · ${duration((to - from) / 60_000)}"
            }
        }
        val legIcons = remember(journey) { legLabels.map { BitmapDescriptorFactory.fromBitmap(legPill(it, false, d)) to BitmapDescriptorFactory.fromBitmap(legPill(it, true, d)) } }
        val legStates = remember(journey) { legs.map { (a, b) -> MarkerState(journey.positionAt((a + b) / 2).g()) } }
        val ringState = remember { MarkerState(spots.first()) }
        ringState.position = spots[sel]

        KeyedGoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = camera,
            properties = MapProperties(mapStyleOptions = style, isBuildingEnabled = false),
            uiSettings = MapUiSettings(
                compassEnabled = false,
                indoorLevelPickerEnabled = false,
                mapToolbarEnabled = false,
                myLocationButtonEnabled = false,
                rotationGesturesEnabled = false,
                tiltGesturesEnabled = false,
                zoomControlsEnabled = false,
            ),
            contentPadding = PaddingValues(start = 36.dp, top = topH, end = 36.dp, bottom = bottomH), // sides: room for half a label
            googleMapOptionsFactory = { com.google.android.gms.maps.GoogleMapOptions().backgroundColor(VoltaColors.Bg.toArgb()) },
            onMapLoaded = { loaded = true },
        ) {
            Polyline(points = route, color = Color.Black.copy(alpha = 0.5f), width = 12 * d, zIndex = 1f)
            Polyline(points = route, color = Color(0xFF4A5666), width = 5 * d, zIndex = 2f)
            Polyline(points = legPath, color = VoltaColors.Gold, width = 6 * d, zIndex = 3f)
            Marker(ringState, icon = ring, anchor = Offset(0.5f, 0.5f), zIndex = 4f)
            legStates.forEachIndexed { i, st ->
                val on = sel == i + 1 || (sel == 0 && i == 0)
                Marker(
                    st,
                    icon = if (on) legIcons[i].second else legIcons[i].first,
                    anchor = Offset(0.5f, 0.5f),
                    zIndex = if (on) 5.5f else 3.5f,
                    onClick = {
                        scope.launch { pager.animateScrollToPage(i + 1) }
                        true
                    },
                )
            }
            backupStates.forEachIndexed { i, st ->
                if (st == null) return@forEachIndexed
                val shown = sel == i + 1
                if (shown) {
                    Polyline(
                        points = listOf(spots[sel], st.position),
                        color = VoltaColors.Amber,
                        width = 2.5f * d,
                        zIndex = 3f,
                        pattern = listOf(Dash(8 * d), Gap(6 * d)),
                    )
                }
                Marker(
                    st,
                    icon = (if (shown) backupLabels[i] else null) ?: backupDot,
                    anchor = Offset(0.5f, 0.5f),
                    zIndex = if (shown) 7f else 4f,
                    alpha = if (shown) 1f else 0.75f,
                    onClick = {
                        scope.launch { pager.animateScrollToPage(i + 1) }
                        true
                    },
                )
            }
            spots.indices.forEach { i ->
                Marker(
                    pinStates[i],
                    icon = pins[i],
                    anchor = Offset(0.5f, 0.28f),
                    zIndex = if (i == sel) 6f else 5f,
                    onClick = {
                        scope.launch { pager.animateScrollToPage(i) }
                        true
                    },
                )
            }
        }

        // The map paints light until its dark style and tiles are in; keep it covered until then.
        val cover by animateFloatAsState(if (shown) 0f else 1f, tween(350), label = "cover")
        if (cover > 0f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = cover }.background(VoltaColors.Bg))

        // ---- top: the trip in a line, back to the list ----
        Box(Modifier.fillMaxWidth().height(150.dp).background(Brush.verticalGradient(listOf(VoltaColors.Bg.copy(alpha = 0.92f), Color.Transparent))))
        Row(
            Modifier.onSizeChanged { topH = with(density) { it.height.toDp() } + 8.dp }.statusBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("$originName → ${journey.trip.destination.name}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${(journey.totalM / 1000).roundToInt()} km · ${stopCount(journey.stops.size)} · arrive ${time(journey.arriveAt)} with ${journey.arrivalSoc.roundToInt()} %",
                    style = MaterialTheme.typography.bodySmall.tabular(),
                    color = VoltaColors.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                Modifier.clip(CircleShape).background(VoltaColors.Surface.copy(alpha = 0.9f)).border(1.dp, VoltaColors.Border, CircleShape).clickable(role = Role.Button, onClick = onClose)
                    .padding(start = 10.dp, end = 14.dp, top = 9.dp, bottom = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(dev.irshadm.volta.core.designsystem.VoltaIcons.Back, null, tint = VoltaColors.Text, modifier = Modifier.size(16.dp))
                Text("Back to list", style = MaterialTheme.typography.labelLarge)
            }
        }

        // ---- bottom: a card per place ----
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { bottomH = with(density) { it.height.toDp() } }.navigationBarsPadding().padding(bottom = 10.dp)) {
            HorizontalPager(pager, contentPadding = PaddingValues(horizontal = 14.dp), pageSpacing = 10.dp, verticalAlignment = Alignment.Bottom) { p ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(VoltaColors.Surface.copy(alpha = 0.97f))) {
                    when (p) {
                        0 -> StartCard(journey, originName)
                        last -> EndCard(journey, photo)
                        else -> StopCard(journey, journey.stops[p - 1], photo)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                repeat(last + 1) { i ->
                    Box(
                        Modifier.padding(horizontal = 3.dp).height(6.dp).width(if (sel == i) 18.dp else 6.dp).clip(CircleShape)
                            .background(if (sel == i) VoltaColors.Gold else VoltaColors.Text.copy(alpha = 0.3f))
                            .clickable { scope.launch { pager.animateScrollToPage(i) } },
                    )
                }
            }
            Text(
                "Swipe the cards or tap a stop on the map",
                style = MaterialTheme.typography.labelSmall,
                color = VoltaColors.Dim,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp),
            )
        }
    }
}

private fun LatLng.g() = GLatLng(lat, lng)

private fun bounds(points: List<GLatLng>): LatLngBounds = LatLngBounds.builder().apply { points.forEach { include(it) } }.build()

/** Name of a stop's town, or the destination when there is no next stop. */
private fun placeName(j: Journey, s: Journey.JStop?): String = s?.let { townOf(it.stop.charger) ?: shortName(it.stop.charger) } ?: j.trip.destination.name

// ---- cards ----

@Composable
private fun StartCard(j: Journey, originName: String) {
    val next = j.stops.firstOrNull()
    val km = ((next?.atM ?: j.totalM) / 1000).roundToInt()
    val arrive = next?.arriveAt ?: j.arriveAt
    val arriveSoc = next?.stop?.arriveSoc ?: j.arrivalSoc
    val drive = duration((arrive - j.trip.departAt) / 60_000)
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Head("START", VoltaColors.Gold, "Leave ${time(j.trip.departAt.toDouble())}")
        Text(originName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Chips(listOf("${j.startSoc.roundToInt()} %", "$km km to ${placeName(j, next)}", drive))
        Steps(
            listOf(
                "Leave ${time(j.trip.departAt.toDouble())} with ${j.startSoc.roundToInt()} %",
                "Drive $km km to ${placeName(j, next)} · about $drive",
                "Arrive ${time(arrive)} with ${arriveSoc.roundToInt()} %",
            ),
        )
    }
}

@Composable
private fun StopCard(j: Journey, s: Journey.JStop, photo: suspend (String?, String?) -> Bitmap?) {
    val c = s.stop.charger
    val next = j.stops.getOrNull(s.index + 1)
    val nextKm = (((next?.atM ?: j.totalM) - s.atM) / 1000).roundToInt()
    val nextSoc = next?.stop?.arriveSoc ?: j.arrivalSoc
    PlacePhoto(c.id, null, photo, 104.dp, "${townOf(c) ?: shortName(c)} · ${time(s.arriveAt)}", Glyphs.Bolt)
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Head("STOP ${s.index + 1} OF ${j.stops.size}", VoltaColors.Green, "Arrive ${time(s.arriveAt)} with ${s.stop.arriveSoc.roundToInt()} %")
        Column {
            Text(shortName(c), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${c.maxKw.roundToInt()} kW · ${(s.atM / 1000).roundToInt()} km in", style = MaterialTheme.typography.bodySmall.tabular(), color = VoltaColors.Muted)
        }
        Chips(listOf("${s.stop.arriveSoc.roundToInt()} % → ${s.stop.departSoc.roundToInt()} %", duration(s.minutes), "Leave ${time(s.leaveAt)}"))
        val meal = s.stop.meal
        Steps(
            listOfNotNull(
                "Plug in at the ${c.maxKw.roundToInt()} kW charger",
                "Charge to ${s.stop.departSoc.roundToInt()} % · about ${s.stop.chargeMinutes.roundToInt()} min",
                meal?.let { m -> "${m.label} while it charges" + (s.spot?.let { " · ${it.name}" } ?: s.stop.mealPlace?.let { " · $it" } ?: "") },
                "Then $nextKm km to ${placeName(j, next)} · arrive with ${nextSoc.roundToInt()} %",
            ),
        )
        s.spot?.let { FoodRow(s, it, photo) }
    }
}

@Composable
private fun EndCard(j: Journey, photo: suspend (String?, String?) -> Bitmap?) {
    val dest = j.trip.destination
    val charging = j.stops.sumOf { it.minutes }
    PlacePhoto(dest.placeId.takeUnless { it.startsWith("saved:") }, null, photo, 104.dp, dest.name, Glyphs.Flag)
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Head("DESTINATION", VoltaColors.Gold, "Arrive ${time(j.arriveAt)}")
        Text(dest.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Chips(listOf("${j.arrivalSoc.roundToInt()} % left", "${(j.totalM / 1000).roundToInt()} km", duration((j.arriveAt - j.trip.departAt) / 60_000) + " door to door"))
        Steps(
            listOf(
                "Arrive ${time(j.arriveAt)} with ${j.arrivalSoc.roundToInt()} % left",
                if (j.stops.isEmpty()) "No charging needed on the way" else "${stopCount(j.stops.size).replaceFirstChar { it.uppercase() }} · ${duration(charging)} charging on the way",
            ),
        )
    }
}

/** The meal spot under a stop's steps: its photo, name, rating and the walk. */
@Composable
private fun FoodRow(s: Journey.JStop, spot: dev.irshadm.volta.data.maps.MealSpot, photo: suspend (String?, String?) -> Bitmap?) {
    val bmp by produceState<Bitmap?>(null, spot.photo) { value = photo(null, spot.photo) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(VoltaColors.PanelTrack).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(VoltaColors.SurfaceHigh), contentAlignment = Alignment.Center) {
            val b = bmp
            if (b != null) {
                Image(b.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Glyphs.Fork, null, tint = VoltaColors.Dim, modifier = Modifier.size(18.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            val away = if (spot.distanceM <= 800) "${(spot.distanceM / 80).roundToInt().coerceAtLeast(1)} min walk" else "${"%.1f".format(spot.distanceM / 1000)} km away"
            Text("${s.stop.meal?.label ?: "Food"} · $away", style = MaterialTheme.typography.labelSmall, color = VoltaColors.Gold)
            Text(spot.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(spot.rating?.let { "${"%.1f".format(it)} ★" }, spot.reviews?.let { "%,d reviews".format(it) }, spot.kind).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall.tabular(),
                color = VoltaColors.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Head(kicker: String, color: Color, right: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(kicker, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold), color = color, modifier = Modifier.weight(1f))
        Text(right, style = MaterialTheme.typography.labelSmall.tabular(), color = VoltaColors.Muted)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(items: List<String>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold).tabular(),
                color = VoltaColors.TextSoft,
                maxLines = 1,
                modifier = Modifier.clip(CircleShape).background(VoltaColors.PanelTrack).padding(horizontal = 9.dp, vertical = 4.dp),
            )
        }
    }
}

/** What to do here, numbered. */
@Composable
private fun Steps(lines: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        lines.forEachIndexed { i, line ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${i + 1}", style = MaterialTheme.typography.labelMedium.tabular(), color = VoltaColors.Gold, modifier = Modifier.width(12.dp))
                Text(line, style = MaterialTheme.typography.bodySmall, color = VoltaColors.TextSoft)
            }
        }
    }
}

@Composable
private fun PlacePhoto(placeId: String?, name: String?, photo: suspend (String?, String?) -> Bitmap?, height: Dp, caption: String, icon: ImageVector) {
    val bmp by produceState<Bitmap?>(null, placeId, name) { value = photo(placeId, name) }
    Box(Modifier.fillMaxWidth().height(height).background(Brush.linearGradient(listOf(VoltaColors.SurfaceHigh, VoltaColors.Panel)))) {
        val b = bmp
        if (b != null) {
            val zoom = remember(b) { androidx.compose.animation.core.Animatable(1.08f) }
            LaunchedEffect(b) { zoom.animateTo(1f, tween(4000)) }
            Image(b.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().scale(zoom.value))
        } else {
            Icon(icon, null, tint = VoltaColors.Dim, modifier = Modifier.align(Alignment.Center).size(28.dp))
        }
        Text(
            caption,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold).tabular(),
            modifier = Modifier.align(Alignment.BottomStart).padding(10.dp).clip(CircleShape).background(VoltaColors.Bg.copy(alpha = 0.72f)).padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

private fun stopCount(n: Int) = when (n) {
    0 -> "no stops"
    1 -> "1 stop"
    else -> "$n stops"
}

// ---- map pins (bitmaps) ----

private fun paint(color: Int) = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

private fun boltPath(cx: Float, cy: Float, s: Float) = android.graphics.Path().apply {
    moveTo(cx + 1 * s, cy - 6 * s)
    lineTo(cx - 3.6f * s, cy + 1 * s)
    lineTo(cx - 0.4f * s, cy + 1 * s)
    lineTo(cx - 1 * s, cy + 6 * s)
    lineTo(cx + 3.6f * s, cy - 1 * s)
    lineTo(cx + 0.4f * s, cy - 1 * s)
    close()
}

/** A pin with a label pill under it: "7:53 am · Breakfast". */
private fun labelledPin(label: String, labelBg: Int, labelInk: Int, d: Float, drawIcon: (android.graphics.Canvas, Float, Float, Float) -> Unit): Bitmap {
    val text = paint(labelInk).apply {
        textSize = 11.5f * d
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }
    val tw = text.measureText(label)
    val r = 12 * d
    val w = maxOf(tw + 20 * d, r * 2 + 16 * d).toInt()
    val h = (r * 2 + 6 * d + 22 * d).toInt()
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val cx = w / 2f
    val cy = r + 2 * d
    drawIcon(c, cx, cy, r)
    val top = cy + r + 5 * d
    c.drawRoundRect(cx - tw / 2 - 9 * d, top, cx + tw / 2 + 9 * d, top + 20 * d, 10 * d, 10 * d, paint(labelBg))
    c.drawText(label, cx - tw / 2, top + 14 * d, text)
    return bmp
}

private fun startPin(j: Journey, d: Float): Bitmap = labelledPin("Start · ${time(j.trip.departAt.toDouble())}", 0xEB13171C.toInt(), 0xFFE9EBEE.toInt(), d) { c, cx, cy, r ->
    c.drawCircle(cx, cy, r, paint(0xFF0B0D10.toInt()))
    c.drawCircle(
        cx,
        cy,
        r,
        paint(VoltaColors.Text.toArgb()).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 2.4f * d
        },
    )
    c.drawCircle(cx, cy, r * 0.38f, paint(VoltaColors.Text.toArgb()))
}

private fun stopPin(s: Journey.JStop, d: Float): Bitmap {
    val label = time(s.arriveAt) + (s.stop.meal?.let { " · ${it.label}" } ?: "")
    return labelledPin(label, 0xEB13171C.toInt(), 0xFFE9EBEE.toInt(), d) { c, cx, cy, r ->
        c.drawCircle(cx, cy, r, paint(0xFF0B0D10.toInt()))
        c.drawCircle(
            cx,
            cy,
            r,
            paint(VoltaColors.Green.toArgb()).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 2.4f * d
            },
        )
        c.drawPath(boltPath(cx, cy, 1.1f * d), paint(VoltaColors.Green.toArgb()))
        if (s.stop.meal != null) {
            c.drawCircle(cx + r * 0.85f, cy - r * 0.6f, 6.5f * d, paint(VoltaColors.Gold.toArgb()))
            val ink = paint(VoltaColors.GoldInk.toArgb()).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 1.2f * d
                strokeCap = android.graphics.Paint.Cap.ROUND
            }
            val fx = cx + r * 0.85f
            val fy = cy - r * 0.6f
            c.drawLine(fx - 1.6f * d, fy - 3 * d, fx - 1.6f * d, fy + 3 * d, ink)
            c.drawLine(fx + 1.8f * d, fy - 3 * d, fx + 1.8f * d, fy + 3 * d, ink)
        }
    }
}

private fun destinationPin(j: Journey, d: Float): Bitmap = labelledPin("${time(j.arriveAt)} · ${j.arrivalSoc.roundToInt()} %", VoltaColors.Gold.toArgb(), VoltaColors.GoldInk.toArgb(), d) { c, cx, cy, r ->
    val s = r * 0.8f
    c.drawRoundRect(cx - s, cy - s, cx + s, cy + s, 3 * d, 3 * d, paint(VoltaColors.Gold.toArgb()))
    val ink = paint(VoltaColors.GoldInk.toArgb())
    val q = s / 2
    for (a in 0..3) for (b in 0..3) if ((a + b) % 2 == 0) c.drawRect(cx - s + a * q, cy - s + b * q, cx - s + (a + 1) * q, cy - s + (b + 1) * q, ink)
}

/** A backup charger: a small hollow amber dot with a bolt, and its name in a pill above it when [label] is given (stop pins have theirs below). */
private fun backupPin(label: String?, d: Float): Bitmap {
    val r = 8 * d
    val text = paint(0xFF1F1405.toInt()).apply {
        textSize = 11f * d
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }
    val tw = label?.let { text.measureText(it) } ?: 0f
    val w = maxOf(r * 2 + 4 * d, tw + 18 * d)
    val h = if (label == null) r * 2 + 4 * d else (r + 2 * d) * 2 + 2 * (24 * d) // dot in the middle, pill above, the same space below
    val bmp = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val cx = bmp.width / 2f
    val cy = bmp.height / 2f
    c.drawCircle(cx, cy, r, paint(0xFF0B0D10.toInt()))
    c.drawCircle(
        cx,
        cy,
        r,
        paint(VoltaColors.Amber.toArgb()).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 1.8f * d
        },
    )
    c.drawPath(boltPath(cx, cy, 0.75f * d), paint(VoltaColors.Amber.toArgb()))
    if (label != null) {
        val top = cy - r - 24 * d
        c.drawRoundRect(cx - tw / 2 - 9 * d, top, cx + tw / 2 + 9 * d, top + 20 * d, 10 * d, 10 * d, paint(VoltaColors.Amber.toArgb()))
        c.drawText(label, cx - tw / 2, top + 14 * d, text)
    }
    return bmp
}

/** "102 km · 2 h 28 min" in a pill: gold for the leg the card is about, dark for the rest. */
private fun legPill(label: String, on: Boolean, d: Float): Bitmap {
    val text = paint(if (on) VoltaColors.GoldInk.toArgb() else 0xFFC9D1DC.toInt()).apply {
        textSize = 11f * d
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }
    val tw = text.measureText(label)
    val bmp = Bitmap.createBitmap((tw + 20 * d).toInt(), (22 * d).toInt(), Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val h = bmp.height.toFloat()
    c.drawRoundRect(0f, 0f, bmp.width.toFloat(), h, h / 2, h / 2, paint(if (on) VoltaColors.Gold.toArgb() else 0xEB13171C.toInt()))
    if (!on) {
        c.drawRoundRect(
            0.5f * d,
            0.5f * d,
            bmp.width - 0.5f * d,
            h - 0.5f * d,
            h / 2,
            h / 2,
            paint(0xFF3A4655.toInt()).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 1f * d
            },
        )
    }
    c.drawText(label, 10 * d, h / 2 + 4 * d, text)
    return bmp
}

/** The gold halo under the pin of the place the card shows. */
private fun ringBitmap(d: Float): Bitmap {
    val r = 24 * d
    val bmp = Bitmap.createBitmap((r * 2 + 4 * d).toInt(), (r * 2 + 4 * d).toInt(), Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val cx = bmp.width / 2f
    c.drawCircle(cx, cx, r, paint(VoltaColors.Gold.copy(alpha = 0.22f).toArgb()))
    c.drawCircle(
        cx,
        cx,
        r,
        paint(VoltaColors.Gold.toArgb()).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 2.5f * d
        },
    )
    return bmp
}

// ---- glyphs: VoltaIcons where it has one, the rest as 24-unit paths drawn with the Icon tint ----

internal object Glyphs {
    private fun glyph(vararg d: String, fill: Boolean = false): ImageVector = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
        d.forEach { p ->
            addPath(
                PathParser().parsePathString(p).toNodes(),
                fill = if (fill) SolidColor(Color.White) else null,
                stroke = if (fill) null else SolidColor(Color.White),
                strokeLineWidth = 2.2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

    val Close = glyph("M6 6l12 12M18 6L6 18")
    val Bolt = dev.irshadm.volta.core.designsystem.VoltaIcons.Bolt
    val Fork = glyph("M7 3v18M4 3v5a3 3 0 0 0 6 0V3M17 21V3c2 1.5 3 4 3 7h-3")
    val Flag = dev.irshadm.volta.core.designsystem.VoltaIcons.Flag
    val Route = glyph("M6 19a2 2 0 1 0 0-4a2 2 0 1 0 0 4M18 9a2 2 0 1 0 0-4a2 2 0 1 0 0 4M6 15V9a4 4 0 0 1 4-4h2M18 9v6a4 4 0 0 1-4 4h-2")
}
