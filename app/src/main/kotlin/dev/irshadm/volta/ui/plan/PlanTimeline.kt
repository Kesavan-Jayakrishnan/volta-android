package dev.irshadm.volta.ui.plan

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.irshadm.volta.core.designsystem.VoltaColors
import dev.irshadm.volta.core.designsystem.VoltaIcons
import dev.irshadm.volta.core.designsystem.tabular
import dev.irshadm.volta.core.planner.Charger
import dev.irshadm.volta.core.planner.Meal
import dev.irshadm.volta.core.planner.PlanOutcome
import dev.irshadm.volta.core.planner.Stop
import dev.irshadm.volta.data.maps.Confidence
import dev.irshadm.volta.data.maps.PlannedTrip
import dev.irshadm.volta.ui.components.VoltaButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Width of the badge column; leg text sits at RAIL + GAP from the row's left edge. */
private val RAIL = 22.dp
private val GAP = 14.dp

/** One drive between two timeline points. */
data class Leg(val km: Double, val minutes: Double, val socUsed: Double, val climbM: Double)

/** Clock times for the whole plan, from when you leave: one set of numbers for the hero, the rows and the sheet. */
class Schedule(val legs: List<Leg>, val arriveAt: List<Double>, val leaveAt: List<Double>, val endAt: Double)

internal fun stopsOf(outcome: PlanOutcome): List<Stop> = when (outcome) {
    is PlanOutcome.Ok -> outcome.stops
    is PlanOutcome.Unreachable -> outcome.stops
}

/** A meal stop lasts at least the meal, even when the battery is done sooner. */
internal fun stopMinutes(s: Stop): Double = maxOf(s.chargeMinutes, if (s.meal != null) 35.0 else 0.0)

private fun legOf(trip: PlannedTrip, fromM: Double, toM: Double, fromSoc: Double, toSoc: Double): Leg {
    val pts = trip.profile.filter { it.distanceM in fromM..toM }
    var minutes = 0.0
    pts.zipWithNext { a, b -> minutes += (b.distanceM - a.distanceM) / 1000 / b.speedKmh * 60 }
    val climb = dev.irshadm.volta.core.planner.Terrain.ascentM(pts.map { it.elevationM })
    return Leg((toM - fromM) / 1000, minutes, (fromSoc - toSoc).coerceAtLeast(0.0), climb)
}

fun schedule(trip: PlannedTrip, startSoc: Double): Schedule {
    val outcome = trip.plan.outcome
    val stops = stopsOf(outcome)
    val endM = when (outcome) {
        is PlanOutcome.Ok -> trip.route.distanceM
        is PlanOutcome.Unreachable -> outcome.strandedAtM
    }
    val endSoc = when (outcome) {
        is PlanOutcome.Ok -> outcome.arrivalSoc
        is PlanOutcome.Unreachable -> trip.plan.socTrace.lastOrNull()?.socPercent ?: 0.0
    }
    val legs = ArrayList<Leg>()
    val arrive = ArrayList<Double>()
    val leave = ArrayList<Double>()
    var clock = trip.departAt.toDouble()
    var atM = 0.0
    var soc = startSoc
    stops.forEach { s ->
        val leg = legOf(trip, atM, s.charger.alongRouteM, soc, s.arriveSoc)
        legs += leg
        clock += leg.minutes * 60_000
        arrive += clock
        clock += stopMinutes(s) * 60_000
        leave += clock
        atM = s.charger.alongRouteM
        soc = s.departSoc
    }
    val last = legOf(trip, atM, endM, soc, endSoc)
    legs += last
    clock += last.minutes * 60_000
    return Schedule(legs, arrive, leave, clock)
}

// ---- naming ----

private val GENERIC = setOf("ev", "charging", "charger", "chargers", "station", "stations", "dc", "fast", "hub", "point", "charge", "electric", "vehicle", "-", "–", "·", "|")

/** "OnePlug EV Charging Station" → "OnePlug"; a name that starts with a generic word stays as it is. */
internal fun brandOf(name: String): String = name.trim().split(Regex("\\s+")).takeWhile { it.lowercase().trim('(', ')', ',') !in GENERIC }.joinToString(" ").ifBlank { name.trim() }

/** "Shop 3, NH 85, Adimali, Kerala 685561, India" → "NH 85, Adimali". */
internal fun shortAddress(full: String?): String? {
    val parts = full?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return null
    val useful = parts.dropLast(1).filterNot { p -> p.any(Char::isDigit) && p.count(Char::isDigit) >= 5 }
        .filterNot { it.equals("Kerala", true) || it.equals("Tamil Nadu", true) || it.equals("Karnataka", true) }
    return useful.takeLast(2).joinToString(", ").ifEmpty { null }
}

/** The town from the address: the last part of [shortAddress]. */
internal fun townOf(c: Charger): String? = shortAddress(c.address)?.substringAfterLast(", ")?.takeIf { it.isNotBlank() }

/** "OnePlug · Panagudi" when the name can be shortened to a brand and a town is known; else the name. */
internal fun shortName(c: Charger): String {
    val brand = brandOf(c.name)
    val town = townOf(c)
    return if (town != null && brand != c.name.trim()) "$brand · $town" else brand
}

/** Backup as a phrase: "Goec, 6 km earlier". */
internal fun backupPhrase(c: Confidence): String? = c.backup?.let { b ->
    val km = c.backupKm ?: 0.0
    brandOf(b.name) + when {
        abs(km) < 1 -> ", next door"
        km < 0 -> ", ${abs(km).roundToInt()} km earlier"
        else -> ", ${km.roundToInt()} km later"
    }
}

private fun plugsPhrase(c: Charger, now: Boolean = false): String? = c.availableCount?.let { free ->
    (c.connectorCount?.let { "$free of $it plugs free" } ?: "$free plugs free") + if (now) " right now" else ""
}

private fun statusColor(c: Confidence): Color = when {
    c.unsure -> VoltaColors.AmberText
    c.dots >= 4 -> VoltaColors.Green
    else -> VoltaColors.Muted
}

/** Restaurant picked (or the best one) at a meal stop, else the planner's own place. */
internal fun mealSpot(trip: PlannedTrip, s: Stop): dev.irshadm.volta.data.maps.MealSpot? {
    val meal = s.meal ?: return null
    val spots = trip.spots(s.charger.id, meal)
    return trip.spotChoice[s.charger.id]?.let { name -> spots.firstOrNull { it.name == name } } ?: spots.firstOrNull()
}

private fun mealPhrase(trip: PlannedTrip, s: Stop): String? {
    val meal = s.meal ?: return null
    val place = mealSpot(trip, s)?.name ?: s.mealPlace
    return if (place != null) "${meal.label} at $place" else "${meal.label} stop"
}

// ---- text styles ----

@Composable private fun rowTitle(): TextStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold).tabular()

@Composable private fun rowSub(): TextStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp).tabular()

@Composable private fun legText(): TextStyle = MaterialTheme.typography.bodySmall.tabular()

/**
 * Start → charging stops → destination as rows on a thin rail: time and place on one line, charge, minutes and
 * one status word under it. Unsure stops add their plugs/backup line and the tip; everything else lives in the
 * stop sheet, opened by tapping the row.
 */
@Composable
fun TripTimeline(
    trip: PlannedTrip,
    originName: String,
    startSoc: Double,
    schedule: Schedule,
    confidence: (Stop) -> Confidence,
    onStop: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val outcome = trip.plan.outcome
    val stops = stopsOf(outcome)
    val tips = remember(trip, startSoc) { RouteAdvisor.perStop(trip, startSoc, dev.irshadm.volta.core.model.VehicleProfile()) }

    Column(modifier.fillMaxWidth()) {
        Node(last = false, badge = { Box(Modifier.padding(top = 6.dp).size(10.dp).clip(CircleShape).background(VoltaColors.Green)) }) {
            Text("${time(trip.departAt.toDouble())} · $originName", style = rowTitle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Leave with ${startSoc.roundToInt()} %", style = rowSub(), color = VoltaColors.Muted, modifier = Modifier.padding(top = 2.dp))
        }
        stops.forEachIndexed { i, s ->
            LegLine(schedule.legs[i])
            val c = confidence(s)
            StopRow(i, s, schedule.arriveAt[i], c, mealPhrase(trip, s), tips[i].orEmpty()) { onStop(i) }
        }
        LegLine(schedule.legs.last())
        val ok = outcome is PlanOutcome.Ok
        Node(last = true, badge = { Box(Modifier.padding(top = 4.dp).size(14.dp).clip(RoundedCornerShape(4.dp)).background(if (ok) VoltaColors.Gold else VoltaColors.Amber)) }) {
            Text("${time(schedule.endAt)} · ${trip.destination.name}", style = rowTitle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            when (outcome) {
                is PlanOutcome.Ok -> Text("Arrive with ${outcome.arrivalSoc.roundToInt()} %", style = rowSub(), color = VoltaColors.Champagne, modifier = Modifier.padding(top = 2.dp))
                is PlanOutcome.Unreachable -> Text("Battery hits your limit around ${(outcome.strandedAtM / 1000).roundToInt()} km", style = rowSub(), color = VoltaColors.AmberText, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
private fun StopRow(index: Int, s: Stop, arriveAt: Double, c: Confidence, meal: String?, tips: List<Advice>, onClick: () -> Unit) {
    Node(last = false, onClick = onClick, badge = { Badge(index + 1, c.unsure, 22.dp) }, trailing = {
        Icon(VoltaIcons.Chevron, null, tint = VoltaColors.Dim, modifier = Modifier.padding(top = 4.dp).size(16.dp))
    }) {
        Row {
            Text(time(arriveAt), style = rowTitle(), maxLines = 1, modifier = Modifier.alignByBaseline())
            Spacer(Modifier.width(8.dp))
            Text(shortName(s.charger), style = rowTitle(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.alignByBaseline())
        }
        Text(
            buildAnnotatedString {
                append("${s.arriveSoc.roundToInt()} → ${s.departSoc.roundToInt()} % · ${stopMinutes(s).roundToInt()} min")
                if (!c.unsure) {
                    append(" · ")
                    withStyle(SpanStyle(color = statusColor(c))) { append(c.label) }
                }
                if (meal != null) {
                    append(" · ")
                    withStyle(SpanStyle(color = VoltaColors.Champagne)) { append(meal) }
                }
            },
            style = rowSub(),
            color = VoltaColors.Muted,
            modifier = Modifier.padding(top = 2.dp),
        )
        if (c.unsure) {
            Text(
                listOfNotNull(c.label, plugsPhrase(s.charger), c.reasons.firstOrNull { it != "24 h" }, backupPhrase(c)?.let { "backup $it" }).joinToString(" · "),
                style = rowSub(),
                color = VoltaColors.AmberText,
                modifier = Modifier.padding(top = 4.dp),
            )
            tips.firstOrNull()?.let { Text(it.text, style = rowSub(), color = VoltaColors.Muted, modifier = Modifier.padding(top = 4.dp)) }
        }
    }
}

/** Numbered stop badge: white for a normal stop, amber for one the planner isn't sure about. */
@Composable
private fun Badge(n: Int, unsure: Boolean, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(if (unsure) VoltaColors.AmberText else VoltaColors.Text), contentAlignment = Alignment.Center) {
        Text("$n", style = (if (size > 24.dp) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium).copy(fontWeight = FontWeight.Bold), color = VoltaColors.GoldInk)
    }
}

/** A row on the rail: badge column, content, optional chevron; the rail continues below the badge unless [last]. */
@Composable
private fun Node(last: Boolean, badge: @Composable () -> Unit, onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 4.dp, vertical = 10.dp),
    ) {
        Box(Modifier.width(RAIL).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            if (!last) Box(Modifier.fillMaxHeight().padding(top = 28.dp).width(2.dp).background(VoltaColors.BorderStrong))
            badge()
        }
        Spacer(Modifier.width(GAP))
        Column(Modifier.weight(1f), content = content)
        trailing?.invoke()
    }
}

/** "103 km · 2 h 31 min · −47 %" in small muted text between two rows, the rail running past it. */
@Composable
private fun LegLine(leg: Leg) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 4.dp)) {
        Box(Modifier.width(RAIL).fillMaxHeight(), contentAlignment = Alignment.TopCenter) { Box(Modifier.fillMaxHeight().width(2.dp).background(VoltaColors.BorderStrong)) }
        Spacer(Modifier.width(GAP))
        Text(
            "${leg.km.roundToInt()} km · ${duration(leg.minutes)} · −${leg.socUsed.roundToInt()} %" +
                if (leg.climbM >= 300) " · climb ${"%,d".format(leg.climbM.roundToInt())} m" else "",
            style = legText(),
            color = VoltaColors.Dim,
            modifier = Modifier.padding(vertical = 2.dp),
        )
    }
}

// ---- stop detail ----

/** Per-stop rating and the "use backup" action, from the plan screen's view model. */
class StopActions(
    val confidence: (Charger, List<Charger>) -> Confidence,
    val useBackup: (String) -> Unit,
)

/** What the meal chooser can do: eat at another charger, skip the meal, or pick a restaurant here. */
class MealActions(
    val eatAt: (Meal, String) -> Unit,
    val skip: (Meal) -> Unit,
    val pickSpot: (String, String) -> Unit,
)

/**
 * Everything the old stop card carried, with room to breathe: when you reach, charge to and leave; the rating
 * with plugs, hours and backup; the tip for the road after; the meal with one Change action; Navigate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StopSheet(
    trip: PlannedTrip,
    index: Int,
    startSoc: Double,
    schedule: Schedule,
    confidence: Confidence,
    meals: MealActions,
    onUseBackup: () -> Unit,
    onDismiss: () -> Unit,
) {
    val stops = stopsOf(trip.plan.outcome)
    val s = stops.getOrNull(index) ?: return
    val c = s.charger
    val context = LocalContext.current
    val tips = remember(trip, startSoc) { RouteAdvisor.perStop(trip, startSoc, dev.irshadm.volta.core.model.VehicleProfile())[index].orEmpty() }
    var choosingMeal by remember(index) { mutableStateOf(false) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val tile = RoundedCornerShape(16.dp)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = VoltaColors.Surface, contentColor = VoltaColors.Text) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 26.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 4.dp)) { Badge(index + 1, confidence.unsure, 30.dp) }
                Column(Modifier.weight(1f)) {
                    Text(c.name, style = MaterialTheme.typography.headlineSmall)
                    val fromKm = schedule.legs[index].km.roundToInt()
                    val from = if (index == 0) "from the start" else "from stop $index"
                    Text(
                        listOfNotNull(shortAddress(c.address), "$fromKm km $from").joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = VoltaColors.Muted,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val toGo = schedule.legs[index + 1].km.roundToInt()
                Tile("Reach", time(schedule.arriveAt[index]), "with ${s.arriveSoc.roundToInt()} %", if (s.arriveSoc < 15) VoltaColors.AmberText else VoltaColors.Muted, Modifier.weight(1f))
                Tile("Charge to", "${s.departSoc.roundToInt()} %", "${stopMinutes(s).roundToInt()} min · ${c.maxKw.roundToInt()} kW", VoltaColors.Muted, Modifier.weight(1f))
                Tile("Leave", time(schedule.leaveAt[index]), "$toGo km to go", VoltaColors.Muted, Modifier.weight(1f))
            }

            val tone = when {
                confidence.unsure -> VoltaColors.Amber
                confidence.dots >= 4 -> VoltaColors.Green
                else -> VoltaColors.TextSoft
            }
            Column(
                Modifier.fillMaxWidth().clip(tile)
                    .background(if (confidence.unsure) VoltaColors.tint(VoltaColors.Amber, 0.12f) else VoltaColors.SurfaceHigh)
                    .border(1.dp, if (confidence.unsure) VoltaColors.tint(VoltaColors.Amber, 0.45f) else VoltaColors.Border, tile)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        repeat(5) { i -> Box(Modifier.size(7.dp).clip(CircleShape).background(if (i < confidence.dots) tone else VoltaColors.PanelTrack)) }
                    }
                    Text(confidence.label, style = MaterialTheme.typography.labelLarge, color = tone)
                }
                val facts = listOfNotNull(plugsPhrase(c, now = true), if (c.open24h == true) "open 24 h" else null) +
                    confidence.reasons.filter { it != "24 h" } + "CCS2"
                Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = if (confidence.unsure) VoltaColors.AmberText else VoltaColors.Muted, modifier = Modifier.padding(top = 4.dp))
                confidence.backup?.let { b ->
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Backup: ${b.name}${backupPhrase(confidence)?.substringAfter(brandOf(b.name)) ?: ""}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = VoltaColors.TextSoft,
                            modifier = Modifier.weight(1f),
                        )
                        if (confidence.unsure) {
                            Text(
                                "Use backup",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = VoltaColors.GoldInk,
                                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(VoltaColors.Gold).clickable(role = Role.Button, onClick = onUseBackup).padding(horizontal = 14.dp, vertical = 9.dp),
                            )
                        }
                    }
                }
            }

            tips.forEach { a ->
                Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    Icon(adviceIcon(a.kind), null, tint = VoltaColors.AmberText, modifier = Modifier.padding(top = 2.dp).size(16.dp))
                    Text(a.text, style = MaterialTheme.typography.bodyMedium, color = VoltaColors.TextSoft)
                }
            }

            val meal = s.meal
            val amenities = trip.amenities[c.id]
            if (meal != null) {
                val spot = mealSpot(trip, s)
                Row(
                    Modifier.fillMaxWidth().clip(tile).background(VoltaColors.SurfaceHigh).padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(VoltaIcons.Coffee, null, tint = VoltaColors.Champagne, modifier = Modifier.size(20.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${meal.label} · " + (spot?.let { it.name + (it.rating?.let { r -> " %.1f★".format(r) } ?: "") } ?: s.mealPlace ?: "no place next to this charger"),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            spot?.let { "${(it.distanceM / 10).roundToInt() * 10} m from the charger · charge while you eat" } ?: "Eat here or at another stop",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
                            color = VoltaColors.Muted,
                        )
                    }
                    Text(
                        if (choosingMeal) "Done" else "Change",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = VoltaColors.Gold,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { choosingMeal = !choosingMeal }.padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
                if (choosingMeal) MealChooser(trip, s, meal, meals)
            } else if (!amenities.isNullOrEmpty()) {
                Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    Icon(VoltaIcons.Coffee, null, tint = VoltaColors.Muted, modifier = Modifier.padding(top = 2.dp).size(16.dp))
                    Text("Nearby · " + amenities.take(2).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = VoltaColors.TextSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            VoltaButton("Navigate", {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${c.position.lat},${c.position.lng}")).setPackage("com.google.android.apps.maps").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }, Modifier.fillMaxWidth(), icon = VoltaIcons.Navigate)
        }
    }
}

@Composable
private fun Tile(label: String, value: String, sub: String, subColor: Color, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(VoltaColors.SurfaceHigh).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = VoltaColors.Muted)
        Text(value, style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold).tabular(), modifier = Modifier.padding(top = 2.dp), maxLines = 1)
        Text(sub, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp).tabular(), color = subColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

internal fun adviceIcon(kind: Advice.Kind): ImageVector = when (kind) {
    Advice.Kind.Climb -> VoltaIcons.Mountain
    Advice.Kind.Break, Advice.Kind.Meal -> VoltaIcons.Coffee
    Advice.Kind.Gap, Advice.Kind.Battery -> VoltaIcons.Warning
    Advice.Kind.Dark, Advice.Kind.Night -> VoltaIcons.Clock
}

/**
 * On a meal stop: the best restaurants next to this charger to choose from, or another charger in the
 * meal window with a better one; when there's nothing good here, asks where you'd like to eat.
 */
@Composable
private fun MealChooser(trip: PlannedTrip, stop: Stop, meal: Meal, actions: MealActions) {
    val spots = trip.spots(stop.charger.id, meal)
    val chosen = trip.spotChoice[stop.charger.id] ?: spots.firstOrNull()?.name
    val departMin = java.util.Calendar.getInstance().apply { timeInMillis = trip.departAt }.let { it.get(java.util.Calendar.HOUR_OF_DAY) * 60 + it.get(java.util.Calendar.MINUTE) }
    fun clockOf(m: Double) = trip.departAt + ((m - departMin) * 60_000).toLong()
    val others = trip.mealOptions.filter { it.meal == meal && it.charger.id != stop.charger.id }.sortedBy { abs(it.charger.alongRouteM - stop.charger.alongRouteM) }.take(2)
    val name = meal.label.lowercase()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(VoltaColors.tint(if (spots.isEmpty()) VoltaColors.Amber else VoltaColors.Gold, 0.07f))
            .border(1.dp, VoltaColors.tint(if (spots.isEmpty()) VoltaColors.Amber else VoltaColors.Gold, 0.25f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        if (spots.isNotEmpty()) {
            Text("${meal.label} — choose a place", style = MaterialTheme.typography.labelLarge, color = VoltaColors.Gold)
            spots.forEach { sp ->
                Choice(sp.name == chosen, sp.name + (sp.rating?.let { " · %.1f★".format(it) } ?: ""), "${(sp.distanceM / 10).roundToInt() * 10} m from the charger") {
                    actions.pickSpot(stop.charger.id, sp.name)
                }
            }
        } else {
            Text("No $name place next to this charger", style = MaterialTheme.typography.labelLarge, color = VoltaColors.AmberText)
            Text("Where would you like to eat?", style = MaterialTheme.typography.bodySmall, color = VoltaColors.Muted)
        }
        others.forEach { o ->
            val best = o.spots.first()
            Choice(
                false,
                best.name + (best.rating?.let { " · %.1f★".format(it) } ?: ""),
                "stop at ${shortName(o.charger)} instead · ${time(clockOf(o.clock).toDouble())}",
            ) { actions.eatAt(meal, o.charger.id) }
        }
        Text(
            if (spots.isEmpty()) "Eat before leaving or on arrival" else "Skip $name",
            style = MaterialTheme.typography.labelMedium,
            color = VoltaColors.Muted,
            modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(8.dp)).clickable { actions.skip(meal) }.padding(vertical = 4.dp),
        )
    }
}

@Composable
private fun Choice(on: Boolean, title: String, sub: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 3.dp).size(14.dp).clip(CircleShape).border(1.5.dp, if (on) VoltaColors.Gold else VoltaColors.BorderStrong, CircleShape), contentAlignment = Alignment.Center) {
            if (on) Box(Modifier.size(7.dp).clip(CircleShape).background(VoltaColors.Gold))
        }
        Column(Modifier.padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodySmall, color = if (on) VoltaColors.Text else VoltaColors.TextSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = MaterialTheme.typography.labelSmall, color = VoltaColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---- formats ----

private val clockFmt = SimpleDateFormat("h:mm a", Locale.getDefault())

/** "2:01 am". */
internal fun time(millis: Double): String = clockFmt.format(Date(millis.toLong())).lowercase()

/** One duration format everywhere: "8 h 9 min", "2 h", "35 min". */
internal fun duration(min: Double): String {
    val m = min.roundToInt()
    return when {
        m < 60 -> "$m min"
        m % 60 == 0 -> "${m / 60} h"
        else -> "${m / 60} h ${m % 60} min"
    }
}
