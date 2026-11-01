package dev.irshadm.volta.ui.trip

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.irshadm.volta.data.db.TripEntity
import dev.irshadm.volta.data.ismart.LinkState
import dev.irshadm.volta.data.trip.ActiveTrip
import dev.irshadm.volta.service.TripService
import dev.irshadm.volta.ui.components.WtButton
import dev.irshadm.volta.ui.components.WtCard
import dev.irshadm.volta.core.designsystem.WtIcons
import dev.irshadm.volta.ui.components.appear
import dev.irshadm.volta.core.designsystem.Wt
import dev.irshadm.volta.core.designsystem.WtMotion
import dev.irshadm.volta.core.designsystem.tabular
import dev.irshadm.volta.core.planner.PlanOutcome
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import dev.irshadm.volta.ui.components.Spacer1
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import androidx.compose.ui.text.font.FontWeight

@Composable
fun TripScreen(onPlan: () -> Unit, vm: TripViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 16.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val t = state.active
        if (t == null) Idle(state, vm) else Live(t, state, vm)
    }
}

// ---------------------------------------------------------------- no trip running (real range and history)

private val PRESETS = listOf("30 days" to 30, "2 months" to 61, "3 months" to 92)
private const val DAY_MS = 86_400_000L

@Composable
private fun Idle(state: TripUiState, vm: TripViewModel) {
    val cap = state.capacityKwh
    val month = remember(state.recent) { monthStats(state.recent) }
    Column(Modifier.padding(start = 4.dp, top = 16.dp).appear(0)) {
        Text("Trips", style = MaterialTheme.typography.headlineLarge)
        Text(
            month.label + " · " + (if (month.trips == 0) "no drives yet" else "${month.km.roundToInt()} km in ${month.trips} ${plural(month.trips, "drive")}"),
            style = MaterialTheme.typography.bodyMedium, color = Wt.Muted, modifier = Modifier.padding(top = 2.dp),
        )
    }

    // The period the real range is worked out over; the same period sets "your usual" for the amber rule below.
    var preset by rememberSaveable { mutableStateOf(1) }                  // 2 months
    var custom by rememberSaveable { mutableStateOf<Pair<Long, Long>?>(null) }
    var picking by remember { mutableStateOf(false) }
    val (from, to) = custom ?: ((System.currentTimeMillis() - PRESETS[preset].second * DAY_MS) to System.currentTimeMillis())
    val totals = remember(state.recent, preset, custom) { Totals.of(state.recent.filter { it.startedAt in from..to }) }
    if (picking) DateRangeDialog(from, to, onPick = { custom = it; picking = false }, onDismiss = { picking = false })

    RangeHero(totals, cap, state.carClaimKm, preset, custom, onPreset = { preset = it; custom = null }, onDates = { picking = true },
        modifier = Modifier.padding(top = 6.dp).appear(1))

    Row(Modifier.padding(horizontal = 4.dp).padding(top = 14.dp).appear(2), verticalAlignment = Alignment.CenterVertically) {
        Text("History", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        Text("logged from the car", style = MaterialTheme.typography.bodySmall, color = Wt.Dim)
    }
    if (state.recent.isEmpty()) {
        Text("Drives appear here automatically as the car reports them (checked every 30 minutes and whenever you open the app).",
            style = MaterialTheme.typography.bodySmall, color = Wt.Muted, modifier = Modifier.padding(horizontal = 4.dp))
        return
    }
    val days = remember(state.recent) { byDay(state.recent.take(60)) }
    days.forEachIndexed { i, day ->
        DayGroup(day, totals.efficiency, cap, onCloseOpen = { vm.closeOpenTrips() }, modifier = Modifier.padding(top = if (i == 0) 0.dp else 8.dp).appear((3 + i).coerceAtMost(6)))
    }
    Text(
        "Full-charge range shows when a drive is long enough to measure; amber when it is well below your usual.",
        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 17.sp), color = Wt.Dim, modifier = Modifier.padding(horizontal = 20.dp).padding(top = 2.dp),
    )
}

private data class MonthStats(val label: String, val km: Double, val trips: Int)

private fun TripEntity.km(): Double? =
    if (startOdometerKm != null && endOdometerKm != null) endOdometerKm - startOdometerKm else plannedDistanceM?.div(1000)

/** Only for drives long enough to measure: the car reports whole percent (~0.37 kWh), so short hops are noise. */
private fun TripEntity.efficiency(): Double? = km()?.takeIf { it >= 5 }?.let { k -> usedKwh?.takeIf { it >= 1.1 }?.let { k / it } }

/** Finished car drives under 3 km fold into one line per day; planned and open trips always get a row. */
private fun TripEntity.isShortHop(): Boolean = endedAt != null && source != "APP" && (km() ?: Double.MAX_VALUE) < 3

private fun monthStats(list: List<TripEntity>): MonthStats {
    val c = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }
    val mine = list.filter { it.startedAt >= c.timeInMillis }
    return MonthStats(SimpleDateFormat("MMMM", Locale.getDefault()).format(Date()), mine.sumOf { it.km() ?: 0.0 }, mine.size)
}

/**
 * Every finished drive in a period with both distance and energy, added up: total km ÷ total kWh. Single short
 * drives are too coarse to judge (the car reports whole percent), but summed over many the rounding evens out.
 */
private data class Totals(val drives: Int, val km: Double, val kwh: Double) {
    val efficiency: Double? get() = if (kwh >= 1.0 && km >= 5) km / kwh else null

    companion object {
        fun of(list: List<TripEntity>): Totals {
            val measured = list.filter { it.endedAt != null && (it.km() ?: 0.0) > 0 && it.usedKwh != null && it.usedKwh >= 0 }
            return Totals(measured.size, measured.sumOf { it.km()!! }, measured.sumOf { it.usedKwh!! })
        }
    }
}

private fun plural(n: Int, word: String) = if (n == 1) word else word + "s"

/** Your actual range on a full charge over the chosen period, against the car's own claim, with the period as a quiet control. */
@Composable
private fun RangeHero(
    totals: Totals, capacityKwh: Double, claimKm: Double?, preset: Int, custom: Pair<Long, Long>?,
    onPreset: (Int) -> Unit, onDates: () -> Unit, modifier: Modifier,
) {
    val eff = totals.efficiency
    WtCard(modifier.fillMaxWidth(), radius = 22.dp, padding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 14.dp)) {
        Text("Your real range on a full charge", style = MaterialTheme.typography.bodySmall, color = Wt.Muted)
        if (eff == null) {
            Text(
                if (totals.drives == 0) "No drives with battery readings in these dates." else "Not enough driving yet in these dates (${totals.km.roundToInt()} km) to work it out.",
                style = MaterialTheme.typography.bodyMedium, color = Wt.TextSoft,
            )
        } else {
            val range = eff * capacityKwh
            Row(Modifier.fillMaxWidth()) {
                Text(
                    "${range.roundToInt()}", modifier = Modifier.alignByBaseline(),
                    style = MaterialTheme.typography.headlineLarge.copy(fontSize = 44.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.03).em).tabular(),
                )
                Text(" km", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Normal), color = Wt.Muted, modifier = Modifier.alignByBaseline())
                Spacer1()
                Text("%.1f km/kWh".format(eff), style = MaterialTheme.typography.bodyMedium.tabular(), color = Wt.Muted, modifier = Modifier.alignByBaseline())
            }
            RangeBar(range, claimKm, Modifier.padding(top = 4.dp))
            Row(Modifier.fillMaxWidth()) {
                Text("${totals.drives} ${plural(totals.drives, "drive")} · ${totals.km.roundToInt()} km · %.1f kWh".format(totals.kwh),
                    style = MaterialTheme.typography.bodySmall.tabular(), color = Wt.Dim, modifier = Modifier.weight(1f))
                claimKm?.let { Text("car's own claim ${it.roundToInt()} km", style = MaterialTheme.typography.bodySmall.tabular(), color = Wt.Dim) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            PRESETS.forEachIndexed { i, (label, _) -> Segment(label, custom == null && preset == i) { onPreset(i) } }
            Spacer1()
            Segment(if (custom != null) "Custom" else "Dates…", custom != null, onDates)
        }
        custom?.let { (from, to) ->
            val fmt = SimpleDateFormat("d MMM", Locale.getDefault())
            Text("${fmt.format(Date(from))} – ${fmt.format(Date(to))}", style = MaterialTheme.typography.labelSmall, color = Wt.Dim, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

/** Real range as a gold fill on a scale that leaves room past the larger of it and the car's claim; the claim is a thin tick. */
@Composable
private fun RangeBar(rangeKm: Double, claimKm: Double?, modifier: Modifier) {
    val scale = maxOf(rangeKm, claimKm ?: 0.0, 1.0) * 1.2
    val frac by animateFloatAsState((rangeKm / scale).toFloat().coerceIn(0f, 1f), tween(700, easing = WtMotion.EaseOut), label = "range")
    Canvas(modifier.fillMaxWidth().height(14.dp)) {
        val h = 6.dp.toPx()
        val y = (size.height - h) / 2
        drawRoundRect(Wt.PanelTrack, Offset(0f, y), Size(size.width, h), CornerRadius(h / 2))
        drawRoundRect(Wt.Gold, Offset(0f, y), Size(size.width * frac, h), CornerRadius(h / 2))
        claimKm?.let { c ->
            val x = (c / scale).toFloat().coerceIn(0f, 1f) * size.width
            drawRoundRect(Wt.Dim, Offset(x - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height), CornerRadius(1.dp.toPx()))
        }
    }
}

/** Quiet period segment: the chosen one sits on a raised surface, the rest are plain text. */
@Composable
private fun Segment(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(10.dp)).background(if (on) Wt.SurfaceHigh else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick).height(32.dp).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium),
            color = if (on) Wt.Text else Wt.Muted, maxLines = 1)
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun DateRangeDialog(from: Long, to: Long, onPick: (Pair<Long, Long>) -> Unit, onDismiss: () -> Unit) {
    val st = androidx.compose.material3.rememberDateRangePickerState(initialSelectedStartDateMillis = from, initialSelectedEndDateMillis = to)
    androidx.compose.material3.DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            androidx.compose.material3.TextButton({
                val s = st.selectedStartDateMillis
                val e = st.selectedEndDateMillis
                if (s != null) onPick(s to ((e ?: s) + DAY_MS - 1)) else onDismiss()
            }) { Text("Use these dates", color = Wt.Champagne) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onDismiss) { Text("Cancel", color = Wt.Muted) } },
    ) {
        androidx.compose.material3.DateRangePicker(
            st, Modifier.height(460.dp), title = { Text("Pick dates", Modifier.padding(start = 24.dp, top = 16.dp)) },
            colors = androidx.compose.material3.DatePickerDefaults.colors(
                selectedDayContainerColor = Wt.Champagne, selectedDayContentColor = Wt.Bg,
                dayInSelectionRangeContainerColor = Wt.tint(Wt.Champagne, 0.22f), dayInSelectionRangeContentColor = Wt.Text,
                todayDateBorderColor = Wt.Champagne, todayContentColor = Wt.Champagne,
            ),
        )
    }
}

// ---------------------------------------------------------------- history, one card per day

/** One day's drives: [rows] get a line each, [hops] (short car drives) fold into one trailing line. */
private data class Day(val label: String, val rows: List<TripEntity>, val hops: List<TripEntity>)

private fun dayStart(ms: Long): Long = Calendar.getInstance().apply {
    timeInMillis = ms
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun byDay(list: List<TripEntity>): List<Day> {
    val today = dayStart(System.currentTimeMillis())
    val thisYear = Calendar.getInstance().get(Calendar.YEAR)
    val fmt = SimpleDateFormat("EEE d MMM", Locale.getDefault())
    val fmtYear = SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault())
    return list.groupBy { dayStart(it.startedAt) }.entries.sortedByDescending { it.key }.map { (start, trips) ->
        val year = Calendar.getInstance().apply { timeInMillis = start }.get(Calendar.YEAR)
        val date = (if (year == thisYear) fmt else fmtYear).format(Date(start)).uppercase()
        val (hops, rows) = trips.sortedByDescending { it.startedAt }.partition { it.isShortHop() }
        Day(if (start == today) "TODAY · $date" else date, rows, hops)
    }
}

@Composable
private fun DayGroup(day: Day, usual: Double?, capacityKwh: Double, onCloseOpen: () -> Unit, modifier: Modifier) {
    Column(modifier) {
        Text(day.label, style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.04.em), color = Wt.Muted, modifier = Modifier.padding(horizontal = 4.dp))
        if (day.rows.isNotEmpty()) {
            WtCard(Modifier.fillMaxWidth().padding(top = 6.dp), radius = 18.dp, padding = PaddingValues(0.dp)) {
                Column {
                    day.rows.forEachIndexed { i, e ->
                        if (i > 0) HorizontalDivider(color = Wt.Border)
                        DriveRow(e, usual, capacityKwh, onCloseOpen)
                    }
                }
            }
        }
        if (day.hops.isNotEmpty()) {
            Text(
                "+ ${day.hops.size} short ${plural(day.hops.size, "hop")} · ${day.hops.sumOf { it.km() ?: 0.0 }.roundToInt()} km",
                style = MaterialTheme.typography.bodySmall, color = Wt.Dim, modifier = Modifier.padding(horizontal = 20.dp).padding(top = 10.dp),
            )
        }
    }
}

/**
 * One drive: start time as a column, and on the right what a full charge would give at this drive's efficiency —
 * only when the drive is long enough to measure, amber when well below [usual] (the period's km/kWh). A planned trip left open with no live trip running says so in amber and closes on tap; any other row
 * opens on tap to show its times, battery and odometer.
 */
@Composable
private fun DriveRow(e: TripEntity, usual: Double?, capacityKwh: Double, onCloseOpen: () -> Unit) {
    val planned = e.source == "APP"
    val open = e.endedAt == null
    val stale = planned && open
    val km = e.km()
    val eff = e.efficiency()
    val mins = e.endedAt?.let { ((it - e.startedAt) / 60_000).coerceAtLeast(1) }
    val thirsty = eff != null && usual != null && eff < usual * 0.7
    var expanded by rememberSaveable(e.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { if (stale) onCloseOpen() else expanded = !expanded }) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.width(44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(hourMin(e.startedAt), style = MaterialTheme.typography.bodyMedium.tabular().copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
            Text(meridiem(e.startedAt), style = MaterialTheme.typography.labelSmall, color = Wt.Dim)
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.destination ?: "Drive", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (planned) Text("planned", style = MaterialTheme.typography.labelSmall, color = Wt.GoldInk,
                    modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(6.dp)).background(Wt.Gold).padding(horizontal = 6.dp, vertical = 1.dp))
            }
            val detail = if (stale) "Left open · tap to close" else listOfNotNull(
                km?.let { "${it.roundToInt()} km" }, mins?.let { duration(it) }, if (open) "in progress" else null,
            ).joinToString(" · ").ifEmpty { "—" }
            Text(detail, style = MaterialTheme.typography.bodySmall.tabular(), color = if (stale) Wt.AmberText else Wt.Muted, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        if (stale) Icon(WtIcons.Chevron, contentDescription = "Close this trip", tint = Wt.Dim, modifier = Modifier.size(16.dp))
        else if (eff != null) Column(horizontalAlignment = Alignment.End) {
            Text("${(eff * capacityKwh).roundToInt()} km", style = MaterialTheme.typography.bodyLarge.tabular().copy(fontWeight = FontWeight.SemiBold),
                color = if (thirsty) Wt.AmberText else Wt.Text)
            Text("full charge", style = MaterialTheme.typography.labelSmall, color = Wt.Dim)
        }
    }
    AnimatedVisibility(expanded && !stale) {
        Column(Modifier.padding(start = 46.dp, end = 16.dp, bottom = 13.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            DriveDetail("Time", clock(e.startedAt) + (e.endedAt?.let { " – " + clock(it) } ?: ""))
            eff?.let { DriveDetail("Efficiency", "%.1f km/kWh".format(it)) }
            if (e.startSoc != null && e.endSoc != null) DriveDetail("Battery", "${e.startSoc.roundToInt()} % → ${e.endSoc.roundToInt()} %")
            e.usedKwh?.let { DriveDetail("Energy", "%.1f kWh".format(it)) }
            if (e.startOdometerKm != null) DriveDetail("Odometer", "%,.0f → %,.0f km".format(e.startOdometerKm, e.endOdometerKm ?: e.startOdometerKm))
            DriveDetail("Logged", if (planned) "Planned trip" else "Automatically from the car")
        }
    }
    }
}

@Composable
private fun DriveDetail(label: String, value: String) = Row {
    Text(label, style = MaterialTheme.typography.bodySmall, color = Wt.Dim, modifier = Modifier.width(96.dp))
    Text(value, style = MaterialTheme.typography.bodySmall.tabular(), color = Wt.Muted)
}

private val meridiemFmt = SimpleDateFormat("a", Locale.getDefault())
private fun meridiem(ms: Long) = meridiemFmt.format(Date(ms)).lowercase()
private val hourMinFmt = SimpleDateFormat("h:mm", Locale.getDefault())
private fun hourMin(ms: Long) = hourMinFmt.format(Date(ms))

// ---------------------------------------------------------------- live trip (design A: route ribbon)

@Composable
private fun Live(t: ActiveTrip, state: TripUiState, vm: TripViewModel) {
    val context = LocalContext.current
    Row(Modifier.padding(top = 16.dp).appear(0), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Driving to", style = MaterialTheme.typography.bodySmall, color = Wt.Muted)
            Text(t.planned.destination.name, style = MaterialTheme.typography.headlineLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val secs = ((t.nextCheckAt - state.now) / 1000).coerceAtLeast(0)
        val live = state.link is LinkState.Connected
        Text(
            if (state.checking || t.lastCheckAt == 0L) "● Checking…" else if (live) "● Live · ${secs / 60}:${"%02d".format(secs % 60)}" else "● Offline",
            style = MaterialTheme.typography.labelMedium.tabular(), color = if (live) Wt.Green else Wt.Amber,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(Wt.tint(if (live) Wt.Green else Wt.Amber, 0.12f))
                .border(1.dp, Wt.tint(if (live) Wt.Green else Wt.Amber, 0.35f), RoundedCornerShape(50))
                .clickable(enabled = !state.checking) { vm.checkNow() }.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }

    if (state.link is LinkState.NeedsReconnect) {
        WtCard(Modifier.fillMaxWidth(), background = Wt.tint(Wt.Amber, 0.10f), border = Wt.tint(Wt.Amber, 0.45f), radius = 18.dp) {
            Text("Car connection lost", style = MaterialTheme.typography.titleSmall, color = Wt.AmberText)
            Text("Live re-planning is paused. Battery is estimated from distance until you reconnect.", style = MaterialTheme.typography.bodySmall, color = Wt.AmberText)
            WtButton("Reconnect", { vm.reconnect() })
        }
    }

    // Hero: battery vs plan + route ribbon.
    val d = t.deviation
    val devColor = if (d >= 0) Wt.Green else if (d > -5) Wt.TextSoft else Wt.Amber
    val remainingFrac = (1 - t.alongM / t.totalM).coerceIn(0.0, 1.0)
    val etaMs = state.now + ((t.plan.driveMinutes * remainingFrac + t.stops.filter { it.charger.alongRouteM > t.alongM }.sumOf { it.chargeMinutes }) * 60_000).toLong()
    val arriveSoc = (t.plan.outcome as? PlanOutcome.Ok)?.arrivalSoc?.plus(d)
    WtCard(Modifier.fillMaxWidth().appear(1), radius = 20.dp, padding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${t.soc.roundToInt()} %", style = MaterialTheme.typography.displayLarge.copy(fontSize = MaterialTheme.typography.headlineLarge.fontSize * 1.6f))
            Column {
                Text((if (d >= 0) "+" else "−") + "${abs(d).roundToInt()} % vs plan", style = MaterialTheme.typography.labelMedium.tabular(), color = devColor,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Wt.tint(devColor, 0.12f)).padding(horizontal = 9.dp, vertical = 3.dp))
                if (t.socEstimated) Text("estimated", style = MaterialTheme.typography.labelSmall, color = Wt.Amber, modifier = Modifier.padding(start = 4.dp, top = 2.dp))
            }
        }
        Text(
            "${(t.alongM / 1000).roundToInt()} of ${(t.totalM / 1000).roundToInt()} km · arrive ${clock(etaMs)}" + (arriveSoc?.let { " with ~${it.roundToInt()} %" } ?: ""),
            style = MaterialTheme.typography.bodySmall.tabular(), color = Wt.Muted, modifier = Modifier.padding(top = 4.dp),
        )
        RouteRibbon(t, Modifier.padding(top = 14.dp))
    }

    t.nextStop?.let { s ->
        val idx = t.stops.indexOf(s) + 1
        WtCard(Modifier.fillMaxWidth().appear(2), radius = 18.dp, padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(26.dp).clip(CircleShape).background(Wt.Gold), contentAlignment = Alignment.Center) {
                    Text("$idx", style = MaterialTheme.typography.labelMedium, color = Wt.GoldInk)
                }
                Column(Modifier.weight(1f)) {
                    Text("Next: ${s.charger.name}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("in ${((s.charger.alongRouteM - t.alongM) / 1000).coerceAtLeast(0.0).roundToInt()} km · arrive ~${s.arriveSoc.roundToInt()} % · charge to ${s.departSoc.roundToInt()} % · ${s.chargeMinutes.roundToInt()} min",
                        style = MaterialTheme.typography.bodySmall.tabular(), color = Wt.Muted, maxLines = 2)
                }
                Row(
                    Modifier.clip(RoundedCornerShape(50)).border(1.dp, Wt.Border, RoundedCornerShape(50)).clickable {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${s.charger.position.lat},${s.charger.position.lng}"))
                                .setPackage("com.google.android.apps.maps").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(WtIcons.Navigate, null, tint = Wt.Gold, modifier = Modifier.size(14.dp))
                    Text(" Go", style = MaterialTheme.typography.labelLarge, color = Wt.Gold)
                }
            }
        }
    }

    if (t.log.isNotEmpty()) {
        var open by rememberSaveable { mutableStateOf(false) }
        val rot by animateFloatAsState(if (open) 180f else 0f, tween(200), label = "logChev")
        WtCard(Modifier.fillMaxWidth().appear(3).clip(RoundedCornerShape(18.dp)).clickable { open = !open }, radius = 18.dp, padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Timeline", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(t.log.last(), style = MaterialTheme.typography.bodySmall, color = Wt.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(2f).padding(end = 8.dp), textAlign = TextAlign.End)
                Icon(WtIcons.Chevron, null, tint = Wt.Muted, modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = 90f + rot })
            }
            AnimatedVisibility(open) {
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    t.log.reversed().forEach { Text("· $it", style = MaterialTheme.typography.bodySmall, color = Wt.TextSoft) }
                }
            }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.appear(4)) {
        ActionTile(WtIcons.Map, "Open Maps", Wt.Gold, Modifier.weight(1f)) {
            val dest = t.planned.destination.position
            val wps = t.stops.filter { it.charger.alongRouteM > t.alongM }.joinToString("|") { "${it.charger.position.lat},${it.charger.position.lng}" }
            val url = "https://www.google.com/maps/dir/?api=1&destination=${dest.lat},${dest.lng}" + (if (wps.isNotEmpty()) "&waypoints=" + Uri.encode(wps) else "") + "&travelmode=driving"
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.google.android.apps.maps").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        ActionTile(WtIcons.Flag, "End trip", Wt.Amber, Modifier.weight(1f)) { TripService.stop(context) }
    }
}

/** Start → stops → destination bar with the car moving along it and names under the key points. */
@Composable
private fun RouteRibbon(t: ActiveTrip, modifier: Modifier) {
    val frac by animateFloatAsState((t.alongM / t.totalM).toFloat().coerceIn(0f, 1f), tween(700, easing = WtMotion.EaseOut), label = "ribbon")
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val w = maxWidth
        Box(Modifier.fillMaxWidth().height(28.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val y = size.height / 2
                val h = 4.dp.toPx()
                drawRoundRect(Wt.Border, Offset(0f, y - h / 2), Size(size.width, h), CornerRadius(h))
                drawRoundRect(Wt.Gold, Offset(0f, y - h / 2), Size(size.width * frac, h), CornerRadius(h))
                t.stops.forEach { s ->
                    val x = (s.charger.alongRouteM / t.totalM).toFloat() * size.width
                    val passed = s.charger.alongRouteM < t.alongM
                    drawCircle(Wt.Bg, 7.dp.toPx(), Offset(x, y))
                    drawCircle(if (passed) Wt.Dim else Wt.Champagne, 7.dp.toPx(), Offset(x, y), style = Stroke(2.5.dp.toPx()))
                }
                val sq = 12.dp.toPx()
                drawRoundRect(Wt.Gold, Offset(size.width - sq, y - sq / 2), Size(sq, sq), CornerRadius(3.dp.toPx()))
            }
            Box(
                Modifier.offset(x = (w - 24.dp) * frac).size(24.dp).align(Alignment.CenterStart).clip(CircleShape).background(Wt.Gold),
                contentAlignment = Alignment.Center,
            ) { Icon(WtIcons.Car, null, tint = Wt.GoldInk, modifier = Modifier.size(14.dp)) }
        }
        val next = t.nextStop
        Row(Modifier.fillMaxWidth().padding(top = 32.dp)) {
            Text("Start", style = MaterialTheme.typography.labelSmall, color = Wt.Dim, modifier = Modifier.weight(1f))
            next?.let { s ->
                Text("${s.charger.name.take(18)} · ${((s.charger.alongRouteM - t.alongM) / 1000).coerceAtLeast(0.0).roundToInt()} km",
                    style = MaterialTheme.typography.labelSmall, color = Wt.Champagne, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(2f), textAlign = TextAlign.Center)
            }
            Text(t.planned.destination.name, style = MaterialTheme.typography.labelSmall, color = Wt.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        }
    }
}

@Composable
private fun ActionTile(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(48.dp).clip(RoundedCornerShape(14.dp)).background(Wt.Surface).border(1.dp, Wt.Border, RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        Text("  $label", style = MaterialTheme.typography.labelLarge, color = tint)
    }
}

private val clockFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
private fun clock(ms: Long) = clockFmt.format(Date(ms)).lowercase()
private fun duration(min: Long) = when {
    min < 60 -> "$min min"
    min % 60 == 0L -> "${min / 60} h"
    else -> "${min / 60} h ${min % 60} min"
}
