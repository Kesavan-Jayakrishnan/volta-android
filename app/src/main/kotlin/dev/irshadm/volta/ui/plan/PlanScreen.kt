package dev.irshadm.volta.ui.plan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.irshadm.volta.core.designsystem.VoltaColors
import dev.irshadm.volta.core.designsystem.VoltaIcons
import dev.irshadm.volta.core.designsystem.tabular
import dev.irshadm.volta.core.planner.Geo
import dev.irshadm.volta.core.planner.PlanOutcome
import dev.irshadm.volta.core.planner.Stop
import dev.irshadm.volta.data.maps.Confidence
import dev.irshadm.volta.data.maps.PlannedTrip
import dev.irshadm.volta.feature.onboarding.mapskey.MapsKeyMode
import dev.irshadm.volta.feature.onboarding.mapskey.MapsKeyRoute
import dev.irshadm.volta.ui.components.VoltaButton
import dev.irshadm.volta.ui.components.VoltaCard
import dev.irshadm.volta.ui.components.appear
import kotlin.math.abs
import kotlin.math.roundToInt

/** Which of the four screens is showing; a change of class animates, a change inside one doesn't. */
private sealed interface Screen {
    data object Form : Screen
    data class Working(val message: String) : Screen
    data class Routes(val phase: PlanPhase.Choosing) : Screen
    data class Plan(val trip: PlannedTrip) : Screen
}

@Composable
fun PlanScreen(onTripStarted: () -> Unit = {}, viewModel: PlanViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var addingKey by rememberSaveable { mutableStateOf(false) }
    BackHandler(addingKey) { addingKey = false }
    if (addingKey) {
        MapsKeyRoute(MapsKeyMode.Settings, onClose = { addingKey = false })
        return
    }
    if (state.needsMapsKey) {
        NeedsMapsKey(onAdd = { addingKey = true })
        return
    }

    val feedbackVersion by viewModel.feedback.version.collectAsStateWithLifecycle()
    val stopActions = remember(feedbackVersion) {
        StopActions({ c, all -> Confidence.of(c, all, viewModel.feedback.history(c.id)) }, viewModel::avoidCharger)
    }
    val meals = remember(viewModel) { MealActions(viewModel::eatAt, viewModel::skipMeal, viewModel::pickSpot) }
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) {
            viewModel.plan()
        } else {
            viewModel.onFocus(Field.Origin)
        }
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        viewModel.startTrip {
            dev.irshadm.volta.service.TripService.start(context)
            openMaps(context, (state.phase as? PlanPhase.Done)?.trip)
            onTripStarted()
        }
    }
    fun startTrip() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.startTrip {
                dev.irshadm.volta.service.TripService.start(context)
                openMaps(context, (state.phase as? PlanPhase.Done)?.trip)
                onTripStarted()
            }
        }
    }
    fun startPlanning() {
        focus.clearFocus()
        val needsLocation = state.origin == null
        val has = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (needsLocation && !has) {
            permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        } else {
            viewModel.plan()
        }
    }

    // Picking a destination (or a new start) plans straight away — no separate button needed.
    LaunchedEffect(state.origin, state.destination) {
        if (state.destination != null && state.routeDirty && state.canPlan) startPlanning()
    }
    // "Where to?" gives way to the routes or the plan once there is one; the summary line under the title brings it back.
    var formOpen by rememberSaveable { mutableStateOf(true) }
    val plannedRoute = (state.phase as? PlanPhase.Done)?.trip?.route ?: (state.phase as? PlanPhase.Choosing)?.routes
    LaunchedEffect(plannedRoute) { if (plannedRoute != null) formOpen = false }

    val phase = state.phase
    val hasPlan = phase is PlanPhase.Done || phase is PlanPhase.Choosing
    val screen: Screen = when {
        phase is PlanPhase.Working -> Screen.Working(phase.message)
        formOpen || !hasPlan -> Screen.Form
        phase is PlanPhase.Choosing -> Screen.Routes(phase)
        phase is PlanPhase.Done -> Screen.Plan(phase.trip)
        else -> Screen.Form
    }

    // Which stop's sheet is open; closes itself when the plan, or that stop, goes away.
    var openStop by remember { mutableStateOf<Int?>(null) }
    val trip = (phase as? PlanPhase.Done)?.trip
    LaunchedEffect(trip, openStop) {
        val i = openStop ?: return@LaunchedEffect
        if (trip == null || stopsOf(trip.plan.outcome).getOrNull(i) == null) openStop = null
    }

    // "Map view" opens the plan on the map with a card per stop; "Back to list" returns here.
    var previewing by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = screen,
            contentKey = { it::class },
            transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(160)) },
            label = "screen",
        ) { s ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                when (s) {
                    Screen.Form -> FormScreen(state, viewModel, hasPlan, onShowPlan = {
                        formOpen = false
                        focus.clearFocus()
                    }, onPlan = ::startPlanning)

                    is Screen.Working -> WorkingScreen(s.message, state)

                    is Screen.Routes -> RoutePicker(s.phase, state, viewModel::chooseRoute) { formOpen = true }

                    is Screen.Plan -> Result(
                        s.trip,
                        state,
                        stopActions,
                        viewModel::selectOption,
                        viewModel::changeRoute,
                        onEdit = { formOpen = true },
                        onPreview = { previewing = true },
                    ) { openStop = it }
                }
                Spacer(Modifier.height(if (s is Screen.Plan) 104.dp else 24.dp))
            }
        }
        val done = (screen as? Screen.Plan)?.trip
        if (done != null && done.plan.outcome is PlanOutcome.Ok) {
            PinnedActions(Modifier.align(Alignment.BottomCenter), onStart = ::startTrip) { openMaps(context, done) }
        }
    }

    if (previewing && trip != null && trip.plan.outcome is PlanOutcome.Ok) {
        val soc = state.startSoc ?: 0.0
        val journey = remember(trip, soc) { Journey(trip, soc, schedule(trip, soc)) }
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { previewing = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            val backups = remember(journey) { journey.stops.map { stopActions.confidence(it.stop.charger, trip.chargers) } }
            JourneyPreview(journey, state.originQuery.ifBlank { "Your location" }, backups, viewModel::photo) { previewing = false }
        }
    }

    val index = openStop
    val stop = if (trip != null && index != null) stopsOf(trip.plan.outcome).getOrNull(index) else null
    if (trip != null && index != null && stop != null) {
        val soc = state.startSoc ?: 0.0
        val sched = remember(trip, soc) { schedule(trip, soc) }
        StopSheet(
            trip,
            index,
            soc,
            sched,
            stopActions.confidence(stop.charger, trip.chargers),
            meals,
            onUseBackup = {
                stopActions.useBackup(stop.charger.id)
                openStop = null
            },
        ) { openStop = null }
    }
}

// ---- shared bits ----

@Composable private fun bigTitle(): TextStyle = MaterialTheme.typography.headlineLarge.copy(fontSize = 34.sp, lineHeight = 40.sp)

@Composable private fun lead(): TextStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 20.sp).tabular()

@Composable private fun small(): TextStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp).tabular()

@Composable private fun semi(): TextStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)

/** "PLAN", "RECENT": small, muted, letter-spaced. */
@Composable
private fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, letterSpacing = 0.02.em), color = VoltaColors.Muted, modifier = modifier)
}

/** Summary line with a chevron that opens the form again: "from Office · leave 11:30 pm · 59 %". */
@Composable
private fun SummaryLine(text: String, color: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text, style = lead(), color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Icon(VoltaIcons.Chevron, "Edit trip", tint = VoltaColors.Muted, modifier = Modifier.size(14.dp).graphicsLayer { rotationZ = 90f })
    }
}

private fun originName(state: PlanUiState) = state.originQuery.ifBlank { "your location" }

private fun socText(state: PlanUiState) = state.startSoc?.let { "${it.roundToInt()} %" } ?: "no battery reading"

// ---- before planning ----

/** Stands in for the form until a Google key is saved, since nothing on it can work without one. */
@Composable
private fun NeedsMapsKey(onAdd: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 16.dp).padding(top = 16.dp)) {
        Text("Where to?", style = bigTitle(), modifier = Modifier.appear(0))
        Text("Add a Google key to plan trips", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp).appear(1))
        Text(
            "Google supplies the routes, chargers and places. You use your own key, and it stays encrypted on this phone. The rest of Volta works without one.",
            style = lead(),
            color = VoltaColors.Muted,
            modifier = Modifier.padding(top = 6.dp).appear(1),
        )
        VoltaButton("Add your key", onAdd, Modifier.padding(top = 20.dp).fillMaxWidth().appear(2), icon = VoltaIcons.Key)
    }
}

/**
 * "Where to?" as a trip ticket: From and To in one card, then the three things that shape the plan as chips —
 * Leave, Battery and Buffers — each opening a sheet. Saved places and recent trips are one-tap answers below.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormScreen(state: PlanUiState, vm: PlanViewModel, hasPlan: Boolean, onShowPlan: () -> Unit, onPlan: () -> Unit) {
    val focus = LocalFocusManager.current
    var sheet by rememberSaveable { mutableStateOf<TripSheet?>(null) }
    val canGo = state.canPlan && (state.phase !is PlanPhase.Done || state.routeDirty)
    val typing = state.active != null && state.suggestions.isNotEmpty()

    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp).padding(top = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Where to?", style = bigTitle(), modifier = Modifier.weight(1f).appear(0))
            if (hasPlan) {
                Text(
                    "Show plan",
                    style = MaterialTheme.typography.labelLarge,
                    color = VoltaColors.Gold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onShowPlan).padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }

        // ---- the ticket: from, to ----
        val shape = RoundedCornerShape(18.dp)
        Column(Modifier.padding(top = 16.dp).fillMaxWidth().clip(shape).background(VoltaColors.Surface).border(1.dp, VoltaColors.Border, shape).appear(1)) {
            TicketRow(
                label = "From",
                icon = { Box(Modifier.size(11.dp).border(2.5.dp, VoltaColors.Text, CircleShape)) },
                value = state.originQuery,
                placeholder = "Your location",
                onChange = { vm.onQuery(Field.Origin, it) },
                onFocus = { if (it) vm.onFocus(Field.Origin) },
                trailing = if (state.origin != null) {
                    (
                        {
                            Text("Use GPS", style = MaterialTheme.typography.labelMedium, color = VoltaColors.Gold, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { vm.useCurrentLocation() }.padding(6.dp))
                        }
                        )
                } else {
                    null
                },
            )
            HorizontalDivider(color = VoltaColors.Border, modifier = Modifier.padding(start = 46.dp))
            TicketRow(
                label = "To", icon = { Icon(VoltaIcons.Pin, null, tint = VoltaColors.Gold, modifier = Modifier.size(16.dp)) },
                value = state.destinationQuery, placeholder = "Search a place",
                onChange = { vm.onQuery(Field.Destination, it) }, onFocus = { if (it) vm.onFocus(Field.Destination) },
                imeAction = ImeAction.Go,
                onSubmit = {
                    state.suggestions.firstOrNull()?.let {
                        vm.pick(it)
                        focus.clearFocus()
                    } ?: run { if (canGo) onPlan() }
                },
                trailing = if (canGo) {
                    (
                        {
                            Box(Modifier.size(36.dp).clip(CircleShape).background(VoltaColors.Gold).clickable(role = Role.Button, onClick = onPlan), contentAlignment = Alignment.Center) {
                                Icon(VoltaIcons.Chevron, "Plan route", tint = VoltaColors.GoldInk, modifier = Modifier.size(20.dp))
                            }
                        }
                        )
                } else {
                    null
                },
            )
        }
        Suggestions(state.active != null, state) {
            vm.pick(it)
            focus.clearFocus()
        }

        // ---- leave, battery, buffers ----
        AnimatedVisibility(!typing) {
            Column {
                Row(Modifier.padding(top = 10.dp).fillMaxWidth().height(IntrinsicSize.Min).appear(2), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val leave = leaveLabel(state.departAt)
                    TicketChip(
                        VoltaIcons.Clock,
                        "Leave",
                        leave.removePrefix("tomorrow ").replaceFirstChar { it.uppercase() },
                        if (leave.startsWith("tomorrow")) "tomorrow" else "today",
                        Modifier.weight(1f).fillMaxHeight(),
                    ) {
                        focus.clearFocus()
                        sheet = TripSheet.Leave
                    }
                    TicketChip(
                        VoltaIcons.BatteryTab,
                        "Battery",
                        state.startSoc?.let { "${it.roundToInt()} %" } ?: "—",
                        if (state.socOverridden) "set by you" else "from car",
                        Modifier.weight(1f).fillMaxHeight(),
                    ) {
                        focus.clearFocus()
                        sheet = TripSheet.Battery
                    }
                    TicketChip(
                        VoltaIcons.Sliders,
                        "Buffers",
                        "${state.settings.minArrivalAtChargerPercent} · ${state.settings.minArrivalAtDestinationPercent} %",
                        "charge · arrive",
                        Modifier.weight(1f).fillMaxHeight(),
                    ) {
                        focus.clearFocus()
                        sheet = TripSheet.Buffers
                    }
                }

                if (state.places.isNotEmpty()) {
                    FlowRow(Modifier.padding(top = 18.dp).appear(3), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.places.forEach { p ->
                            val km = state.here?.let { Geo.distanceM(it, p.position) / 1000 }
                            PlaceChip(if (p.home) VoltaIcons.Home else VoltaIcons.Pin, p.name + (km?.takeIf { it >= 0.5 }?.let { " · ${it.roundToInt()} km" } ?: "")) {
                                focus.clearFocus()
                                vm.pickPlace(p)
                            }
                        }
                    }
                }
                if (state.recent.isNotEmpty()) {
                    Eyebrow("RECENT", Modifier.padding(top = 24.dp).appear(4))
                    Column(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(VoltaColors.Surface).appear(4)) {
                        state.recent.forEachIndexed { i, r ->
                            if (i > 0) HorizontalDivider(color = VoltaColors.Border)
                            Row(
                                Modifier.fillMaxWidth().clickable(role = Role.Button) {
                                    focus.clearFocus()
                                    vm.pickRecent(r)
                                }.padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                Icon(VoltaIcons.Clock, null, tint = VoltaColors.Muted, modifier = Modifier.size(18.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(r.destination, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(listOfNotNull(r.km?.let { "$it km" }, whenLabel(r.at)).joinToString(" · "), style = small(), color = VoltaColors.Muted)
                                }
                                Icon(VoltaIcons.Chevron, null, tint = VoltaColors.Dim, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                (state.phase as? PlanPhase.Failed)?.let { Problem(it.message, Modifier.padding(top = 14.dp)) }

                Text(
                    "Routes come from Google Maps. The battery estimate includes hills, speed and your buffers.",
                    style = small().copy(lineHeight = 19.sp),
                    color = VoltaColors.Dim,
                    modifier = Modifier.padding(top = 14.dp, start = 4.dp, end = 4.dp),
                )
            }
        }
    }

    sheet?.let { TripSettingsSheet(it, state, vm) { sheet = null } }
}

private enum class TripSheet { Leave, Battery, Buffers }

/** One line of the ticket: icon, a small label over the text field, optional trailing action. */
@Composable
private fun TicketRow(
    label: String,
    icon: @Composable () -> Unit,
    value: String,
    placeholder: String,
    onChange: (String) -> Unit,
    onFocus: (Boolean) -> Unit,
    trailing: (@Composable () -> Unit)?,
    imeAction: ImeAction = ImeAction.Next,
    onSubmit: () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { icon() }
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = VoltaColors.Muted)
            Box {
                if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp), color = if (label == "From") VoltaColors.TextSoft else VoltaColors.Muted)
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, color = VoltaColors.Text),
                    cursorBrush = SolidColor(VoltaColors.Gold),
                    keyboardOptions = KeyboardOptions(imeAction = imeAction),
                    keyboardActions = KeyboardActions(onGo = { onSubmit() }, onNext = { onSubmit() }),
                    modifier = Modifier.fillMaxWidth().onFocusChanged { onFocus(it.isFocused) },
                )
            }
        }
        trailing?.invoke()
    }
}

/** Leave / Battery / Buffers: a label, the value, and what it's based on. */
@Composable
private fun TicketChip(icon: ImageVector, label: String, value: String, sub: String?, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.clip(shape).background(VoltaColors.Surface).border(1.dp, VoltaColors.Border, shape).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(icon, null, tint = VoltaColors.Muted, modifier = Modifier.size(13.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = VoltaColors.Muted)
        }
        Text(value, style = MaterialTheme.typography.titleMedium.tabular(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        sub?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = VoltaColors.Dim, maxLines = 1) }
    }
}

/** The sheet behind one chip; every change re-plans straight away. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TripSettingsSheet(which: TripSheet, state: PlanUiState, vm: PlanViewModel, onDismiss: () -> Unit) {
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        LeaveTimeDialog(state, vm) {
            picking = false
            onDismiss()
        }
    }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, containerColor = VoltaColors.Surface, contentColor = VoltaColors.Text) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (which) {
                TripSheet.Leave -> {
                    Text("Leave", style = MaterialTheme.typography.titleLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        QuickPick("Now", state.departAt == null) {
                            vm.setDepartAt(null)
                            onDismiss()
                        }
                        listOf(5, 6, 7).forEach { h ->
                            val at = nextAt(h)
                            QuickPick(leaveLabel(at).replaceFirstChar { it.uppercase() }, state.departAt == at) {
                                vm.setDepartAt(at)
                                onDismiss()
                            }
                        }
                        QuickPick("Pick a time…", false) { picking = true }
                    }
                    Text("Meal stops and night-safe chargers follow from this.", style = small(), color = VoltaColors.Muted)
                }

                TripSheet.Battery -> {
                    Text("Battery at the start", style = MaterialTheme.typography.titleLarge)
                    Text(state.startSoc?.let { "${it.roundToInt()} %" } ?: "—", style = MaterialTheme.typography.headlineMedium.tabular(), color = VoltaColors.Champagne)
                    Slider(
                        value = state.startSoc?.toFloat() ?: 50f,
                        onValueChange = { vm.setStartSoc(it.roundToInt().toDouble()) },
                        valueRange = 1f..100f,
                        colors = SliderDefaults.colors(thumbColor = VoltaColors.Champagne, activeTrackColor = VoltaColors.Champagne, inactiveTrackColor = VoltaColors.Border),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.carSoc?.let { c -> QuickPick("Car ${c.roundToInt()} %", !state.socOverridden) { vm.useCarSoc() } }
                        QuickPick("80 %", state.socOverridden && state.startSoc == 80.0) { vm.setStartSoc(80.0) }
                        QuickPick("Full", state.socOverridden && state.startSoc == 100.0) { vm.setStartSoc(100.0) }
                    }
                    Text(if (state.socOverridden) "Set by you" else "From the car · ${state.socLabel.replaceFirstChar { it.lowercase() }}", style = small(), color = VoltaColors.Muted)
                }

                TripSheet.Buffers -> {
                    Text("Buffers", style = MaterialTheme.typography.titleLarge)
                    Text("Stop to charge before the battery drops below", style = small(), color = VoltaColors.Muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(5, 10, 15, 20, 25).forEach { p -> QuickPick("$p %", state.settings.minArrivalAtChargerPercent == p) { vm.setRechargeAt(p) } }
                    }
                    Text("Arrive at the destination with at least", style = small(), color = VoltaColors.Muted, modifier = Modifier.padding(top = 6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(10, 15, 20, 25, 30).forEach { p -> QuickPick("$p %", state.settings.minArrivalAtDestinationPercent == p) { vm.setArriveWith(p) } }
                    }
                }
            }
        }
    }
}

/** The next time the clock reads [hour]:00 — today if it's still ahead, else tomorrow. */
private fun nextAt(hour: Int): Long = java.util.Calendar.getInstance().apply {
    set(java.util.Calendar.HOUR_OF_DAY, hour)
    set(java.util.Calendar.MINUTE, 0)
    set(java.util.Calendar.SECOND, 0)
    set(java.util.Calendar.MILLISECOND, 0)
    if (timeInMillis < System.currentTimeMillis()) add(java.util.Calendar.DAY_OF_YEAR, 1)
}.timeInMillis

/** Autocomplete dropdown attached right under the field being typed in. */
@Composable
private fun Suggestions(forThisField: Boolean, state: PlanUiState, onPick: (dev.irshadm.volta.data.maps.PlaceSuggestion) -> Unit) {
    AnimatedVisibility(
        visible = forThisField && state.suggestions.isNotEmpty(),
        enter = expandVertically(tween(200)) + fadeIn(tween(160)),
        exit = shrinkVertically(tween(150)) + fadeOut(tween(100)),
    ) {
        Column(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(14.dp)).background(VoltaColors.SurfaceHigh)) {
            val q = (if (state.active == Field.Origin) state.originQuery else state.destinationQuery).trim()
            state.suggestions.take(5).forEachIndexed { i, s ->
                if (i > 0) HorizontalDivider(color = VoltaColors.Border, modifier = Modifier.padding(start = 44.dp))
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Button) { onPick(s) }.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(VoltaIcons.Pin, null, tint = VoltaColors.Muted, modifier = Modifier.size(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text(highlight(s.title, q), style = MaterialTheme.typography.bodyMedium, color = VoltaColors.TextSoft, maxLines = 1)
                        s.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = VoltaColors.Dim, maxLines = 1) }
                    }
                    s.distanceM?.let { d ->
                        Text(if (d < 1_000) "${d.roundToInt()} m" else "${(d / 1000).roundToInt()} km", style = MaterialTheme.typography.labelSmall.tabular(), color = VoltaColors.Muted)
                    }
                }
            }
        }
    }
}

/** Bold + bright for the part of [text] that matches what was typed. */
private fun highlight(text: String, query: String) = buildAnnotatedString {
    val i = if (query.isEmpty()) -1 else text.indexOf(query, ignoreCase = true)
    if (i < 0) {
        append(text)
        return@buildAnnotatedString
    }
    append(text.substring(0, i))
    withStyle(SpanStyle(color = VoltaColors.Text, fontWeight = FontWeight.Bold)) { append(text.substring(i, i + query.length)) }
    append(text.substring(i + query.length))
}

@Composable
private fun PlaceChip(icon: ImageVector, label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier.height(40.dp).clip(shape).background(VoltaColors.Surface).border(1.dp, VoltaColors.Border, shape).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = VoltaColors.Champagne, modifier = Modifier.size(16.dp))
        Text(label, style = semi().tabular(), maxLines = 1)
    }
}

/** "today", "yesterday", "4 days ago", "last week", "12 Oct". */
private fun whenLabel(at: Long): String {
    val days = ((System.currentTimeMillis() - at) / 86_400_000L).toInt()
    return when {
        days <= 0 -> "today"
        days == 1 -> "yesterday"
        days < 7 -> "$days days ago"
        days < 14 -> "last week"
        else -> java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(at))
    }
}

private val SearchIcon: ImageVector = ImageVector.Builder("search", 24.dp, 24.dp, 24f, 24f).apply {
    listOf("M11 4a7 7 0 1 0 0 14a7 7 0 1 0 0-14z", "M20 20l-3.5-3.5").forEach {
        addPath(pathData = addPathNodes(it), stroke = SolidColor(Color.White), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
    }
}.build()

// ---- planning ----

@Composable
private fun WorkingScreen(message: String, state: PlanUiState) {
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp).padding(top = 16.dp)) {
        Eyebrow("PLAN")
        Text(state.destinationQuery.ifBlank { "Your trip" }, style = bigTitle(), modifier = Modifier.padding(top = 2.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("from ${originName(state)} · leave ${leaveLabel(state.departAt)} · ${socText(state)}", style = lead(), color = VoltaColors.TextSoft, modifier = Modifier.padding(top = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        PlanningSteps(message, state, Modifier.padding(top = 18.dp))
    }
}

/** Planning progress: the road-trip loader over a checklist where done steps tick off and the current one spins. */
@Composable
private fun PlanningSteps(message: String, state: PlanUiState, modifier: Modifier = Modifier) {
    val steps = buildList {
        if (state.origin == null) add("Locating you" to "Using your phone's GPS")
        add("Finding the route" to "Google Maps, live road data")
        add("Reading hills and chargers" to "Elevation every 200 m · DC CCS2 chargers along the way")
        add("Planning charging stops" to "Fastest, fewer stops and preferred networks")
    }
    val current = when {
        message.startsWith("Locating") -> 0
        message.startsWith("Finding the route") -> steps.indexOfFirst { it.first == "Finding the route" }
        message.startsWith("Finding") -> 0
        message.startsWith("Reading") -> steps.indexOfFirst { it.first.startsWith("Reading") }
        else -> steps.lastIndex
    }.coerceAtLeast(0)
    val stage = when (steps[current].first) {
        "Locating you" -> LoaderStage.Locating
        "Finding the route" -> LoaderStage.Route
        "Reading hills and chargers" -> LoaderStage.Terrain
        else -> LoaderStage.Planning
    }
    VoltaCard(modifier.fillMaxWidth(), radius = 18.dp, padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Text(message.trimEnd('…', '.'), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        PlanningLoader(stage, Modifier.padding(top = 10.dp, bottom = 12.dp))
        steps.forEachIndexed { i, (title, detail) ->
            val done = i < current
            val now = i == current
            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                    when {
                        done -> Box(Modifier.size(20.dp).clip(CircleShape).background(VoltaColors.tint(VoltaColors.Champagne, 0.18f)), contentAlignment = Alignment.Center) {
                            Text("✓", style = MaterialTheme.typography.labelMedium, color = VoltaColors.Champagne)
                        }

                        now -> CircularProgressIndicator(color = VoltaColors.Gold, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))

                        else -> Box(Modifier.size(8.dp).clip(CircleShape).background(VoltaColors.BorderStrong))
                    }
                }
                Column {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = when {
                            now -> VoltaColors.Text
                            done -> VoltaColors.TextSoft
                            else -> VoltaColors.Dim
                        },
                    )
                    AnimatedVisibility(now) { Text(detail, style = MaterialTheme.typography.bodySmall, color = VoltaColors.Muted) }
                }
            }
        }
    }
}

@Composable
private fun QuickPick(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium.tabular(),
        color = if (on) VoltaColors.Champagne else VoltaColors.TextSoft,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) VoltaColors.tint(VoltaColors.Champagne, 0.14f) else VoltaColors.SurfaceHigh).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun Problem(message: String, modifier: Modifier = Modifier) {
    VoltaCard(modifier.fillMaxWidth(), background = VoltaColors.tint(VoltaColors.Amber, 0.08f), border = VoltaColors.tint(VoltaColors.Amber, 0.4f)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(VoltaIcons.Warning, null, tint = VoltaColors.Amber, modifier = Modifier.size(20.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = VoltaColors.AmberText)
        }
    }
}

private val leaveFmt = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())

/** "now", "7:00 am" or "tomorrow 6:30 am". */
private fun leaveLabel(at: Long?): String {
    if (at == null) return "now"
    val today = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
    val day = java.util.Calendar.getInstance().apply { timeInMillis = at }.get(java.util.Calendar.DAY_OF_YEAR)
    return (if (day != today) "tomorrow " else "") + leaveFmt.format(java.util.Date(at)).lowercase()
}

/** "Leave at" clock; a time already past today means tomorrow. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun LeaveTimeDialog(state: PlanUiState, vm: PlanViewModel, onDone: () -> Unit) {
    val now = java.util.Calendar.getInstance().apply { state.departAt?.let { timeInMillis = it } }
    val picker = androidx.compose.material3.rememberTimePickerState(now.get(java.util.Calendar.HOUR_OF_DAY), now.get(java.util.Calendar.MINUTE), is24Hour = false)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDone,
        containerColor = VoltaColors.Surface,
        title = { Text("Leave at", style = MaterialTheme.typography.titleMedium) },
        text = {
            androidx.compose.material3.TimePicker(
                picker,
                colors = androidx.compose.material3.TimePickerDefaults.colors(
                    clockDialColor = VoltaColors.SurfaceHigh,
                    selectorColor = VoltaColors.Gold,
                    timeSelectorSelectedContainerColor = VoltaColors.tint(VoltaColors.Gold, 0.2f),
                    timeSelectorSelectedContentColor = VoltaColors.Gold,
                    periodSelectorSelectedContainerColor = VoltaColors.tint(VoltaColors.Gold, 0.2f),
                    periodSelectorSelectedContentColor = VoltaColors.Gold,
                ),
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton({
                val c = java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.HOUR_OF_DAY, picker.hour)
                    set(java.util.Calendar.MINUTE, picker.minute)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }
                if (c.timeInMillis < System.currentTimeMillis() - 5 * 60_000) c.add(java.util.Calendar.DAY_OF_YEAR, 1)
                vm.setDepartAt(c.timeInMillis)
                onDone()
            }) { Text("Set", color = VoltaColors.Gold) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onDone) { Text("Cancel", color = VoltaColors.Muted) } },
    )
}

// ---- routes ----

/**
 * Google's routes compared on what matters for an EV: the suggested one first and already chosen, one metric
 * line each (time, km, climb), the same three chips on every card. Tapping a card plans it.
 */
@Composable
private fun RoutePicker(phase: PlanPhase.Choosing, state: PlanUiState, onPlan: (Int) -> Unit, onEdit: () -> Unit) {
    val routes = phase.routes
    val fastest = routes.indices.minBy { routes[it].route.durationS }
    val flattest = routes.indices.minBy { routes[it].climbM }
    val suggestion = remember(routes) { RouteSuggestion.pick(routes) }
    val order = remember(routes) { listOf(suggestion.index) + routes.indices.filter { it != suggestion.index } }
    val vehicle = remember { dev.irshadm.volta.core.model.VehicleProfile() }
    val hillCost = remember(routes) {
        val extra = routes.maxOf { it.climbM } - routes.minOf { it.climbM }
        (vehicle.massKg * 9.81 * extra / 3.6e6 / vehicle.driveEfficiency / vehicle.usableCapacityKwh * 100).roundToInt()
    }

    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp).padding(top = 16.dp)) {
        Eyebrow("PLAN", Modifier.appear(0))
        Text(state.destinationQuery, style = bigTitle(), modifier = Modifier.padding(top = 2.dp).appear(0), maxLines = 2, overflow = TextOverflow.Ellipsis)
        SummaryLine("from ${originName(state)} · ${routes.size} routes · tap one to plan it", VoltaColors.TextSoft, onEdit, Modifier.padding(top = 4.dp).appear(0))

        Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            order.forEachIndexed { n, i ->
                val c = routes[i]
                val r = c.route
                val suggested = routes.size > 1 && i == suggestion.index
                val shape = RoundedCornerShape(20.dp)
                Column(
                    Modifier.fillMaxWidth().appear(n + 1).clip(shape).background(VoltaColors.Surface)
                        .border(if (suggested) 1.5.dp else 1.dp, if (suggested) VoltaColors.Gold else VoltaColors.Border, shape)
                        .clickable(role = Role.Button) { onPlan(i) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "via ${r.label ?: "route ${i + 1}"}",
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        when {
                            suggested -> Text(
                                "Suggested",
                                style = MaterialTheme.typography.labelMedium,
                                color = VoltaColors.GoldInk,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(VoltaColors.Gold).padding(horizontal = 8.dp, vertical = 2.dp),
                            )

                            i == fastest && routes.size > 1 -> Text("Fastest", style = MaterialTheme.typography.bodySmall, color = VoltaColors.Muted)
                        }
                    }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        val extra = ((r.durationS - routes[fastest].route.durationS) / 60).roundToInt()
                        Text(
                            buildAnnotatedString {
                                append(duration(r.durationS / 60))
                                if (extra > 0) withStyle(SpanStyle(color = VoltaColors.Muted, fontWeight = FontWeight.Normal)) { append(" · +$extra") }
                            },
                            style = lead().copy(fontWeight = FontWeight.SemiBold),
                        )
                        Text("${(r.distanceM / 1000).roundToInt()} km", style = lead().copy(fontWeight = FontWeight.SemiBold))
                        Text(
                            "↗ ${"%,d".format(c.climbM.roundToInt())} m",
                            style = lead().copy(fontWeight = FontWeight.SemiBold),
                            color = if (i == flattest) VoltaColors.Green else VoltaColors.AmberText,
                        )
                    }
                    Row(Modifier.padding(top = 10.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip("${c.chargers.size} chargers", warn = c.chargers.size < 3)
                        Chip("gap ${c.longestGapKm.roundToInt()} km", warn = c.longestGapKm >= 50)
                        Chip(if (r.tolls) "tolls" else "no tolls")
                        Spacer(Modifier.weight(1f))
                        if (routes.size > 1 && i == flattest) Text("least climbing", style = MaterialTheme.typography.bodySmall, color = VoltaColors.TextSoft)
                    }
                    // Google's live traffic: the worst slow or jammed stretch, when there is one.
                    r.slow.firstOrNull()?.let { st ->
                        Text(
                            "${if (st.jam) "Jam" else "Slow"} ${"%.0f".format(st.lengthM / 1000).let { if (it == "0") "<1" else it }} km" + (st.near?.let { " near $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (st.jam) VoltaColors.AmberText else VoltaColors.Muted,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
        Text(
            "Times from Google Maps traffic now. Climb is total metres uphill" +
                (if (routes.size > 1 && hillCost >= 3) "; the hill route costs about $hillCost % more battery." else "."),
            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 17.sp),
            color = VoltaColors.Dim,
            modifier = Modifier.padding(top = 14.dp),
        )
    }
}

@Composable
private fun Chip(text: String, warn: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall.tabular(),
        color = if (warn) VoltaColors.AmberText else VoltaColors.Muted,
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (warn) VoltaColors.tint(VoltaColors.Amber, 0.16f) else VoltaColors.SurfaceHigh).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

// ---- the plan ----

/**
 * The map leads and the destination is the title. One hero line answers the question (arrive when, with what),
 * then the one heads-up that matters, the stops as rows, and the battery over the route.
 */
@Composable
private fun Result(
    trip: PlannedTrip,
    state: PlanUiState,
    stopActions: StopActions,
    onSelect: (Int) -> Unit,
    onChangeRoute: () -> Unit,
    onEdit: () -> Unit,
    onPreview: () -> Unit,
    onStop: (Int) -> Unit,
) {
    val plan = trip.plan
    val outcome = plan.outcome
    val stops = stopsOf(outcome)
    val soc = state.startSoc ?: 0.0
    val sched = remember(trip, soc) { schedule(trip, soc) }
    val recharge = state.settings.minArrivalAtChargerPercent.toDouble()

    Column(Modifier.fillMaxWidth()) {
        MapHeader(trip, state, onEdit, onPreview)

        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Hero(trip, sched, stops, onSelect, onChangeRoute, Modifier.appear(0))

            if (outcome is PlanOutcome.Unreachable) {
                Problem("Battery reaches ${recharge.roundToInt()} % around ${(outcome.strandedAtM / 1000).roundToInt()} km. ${outcome.reason}. Try leaving with more charge or lowering \"Charge before\".")
            }

            val advice = remember(trip, soc) { RouteAdvisor.advise(trip, soc, dev.irshadm.volta.core.model.VehicleProfile()) }
            advice.firstOrNull()?.let { a ->
                Row(Modifier.padding(horizontal = 4.dp).appear(1), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    Icon(
                        adviceIcon(a.kind),
                        null,
                        tint = if (a.kind == Advice.Kind.Break || a.kind == Advice.Kind.Meal) VoltaColors.Champagne else VoltaColors.AmberText,
                        modifier = Modifier.padding(top = 2.dp).size(16.dp),
                    )
                    Text(a.text, style = MaterialTheme.typography.bodyMedium, color = VoltaColors.TextSoft)
                }
            }

            TripTimeline(
                trip,
                state.originQuery.ifBlank { "Current location" },
                soc,
                sched,
                confidence = { s -> stopActions.confidence(s.charger, trip.chargers) },
                onStop = onStop,
                modifier = Modifier.appear(2),
            )

            VoltaCard(Modifier.fillMaxWidth().appear(3), radius = 18.dp, padding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Battery over the route", style = small(), color = VoltaColors.TextSoft, modifier = Modifier.weight(1f))
                    val t = plan.terrainPercent
                    if (abs(t) >= 1) Text(if (t > 0) "Hills +${t.roundToInt()} %" else "Downhill −${abs(t).roundToInt()} %", style = small(), color = if (t > 0) VoltaColors.AmberText else VoltaColors.Green)
                }
                ElevationChart(trip, recharge)
                Row {
                    Text("0 km", style = MaterialTheme.typography.labelSmall, color = VoltaColors.Dim)
                    Spacer(Modifier.weight(1f))
                    Text("peak ${trip.profile.maxOf { it.elevationM }.roundToInt()} m", style = MaterialTheme.typography.labelSmall, color = VoltaColors.Dim)
                    Spacer(Modifier.weight(1f))
                    Text("${(trip.route.distanceM / 1000).roundToInt()} km", style = MaterialTheme.typography.labelSmall, color = VoltaColors.Dim)
                }
            }

            Text(
                "${trip.chargers.size} DC chargers found along the route. Drive times include today's traffic.",
                style = MaterialTheme.typography.bodySmall,
                color = VoltaColors.Dim,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

/** The route on a dark map, fading into the page, with PLAN · destination · the trip in one line over it. */
@Composable
private fun MapHeader(trip: PlannedTrip, state: PlanUiState, onEdit: () -> Unit, onPreview: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(300.dp).clipToBounds()) {
        // The map reserves the title block at the bottom, so the route (and Google's logo) sit in the clear top part.
        RouteMap(trip, Modifier.fillMaxWidth().height(300.dp), bottomPadding = 120.dp, radius = 0.dp)
        // The preview draws on the map, and the Maps SDK never starts in a build without a map key.
        if (trip.plan.outcome is PlanOutcome.Ok && interactiveMapAvailable) {
            Row(
                Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp).clip(RoundedCornerShape(50)).background(VoltaColors.Gold)
                    .clickable(role = Role.Button, onClick = onPreview).padding(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(VoltaIcons.Map, null, tint = VoltaColors.GoldInk, modifier = Modifier.size(16.dp))
                Text("Map view", style = MaterialTheme.typography.labelLarge, color = VoltaColors.GoldInk)
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(200.dp).background(Brush.verticalGradient(0f to Color.Transparent, 0.45f to VoltaColors.Bg.copy(alpha = 0.85f), 0.7f to VoltaColors.Bg, 1f to VoltaColors.Bg)))
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
            Eyebrow("PLAN")
            Text(trip.destination.name, style = bigTitle(), modifier = Modifier.padding(top = 2.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            SummaryLine("from ${originName(state)} · leave ${leaveLabel(state.departAt)} · ${socText(state)}", VoltaColors.TextSoft, onEdit, Modifier.padding(top = 6.dp))
        }
    }
}

/** "Arrive 9:40 am  31 %", the trip in one muted sentence, and the option / route switches. */
@Composable
private fun Hero(trip: PlannedTrip, sched: Schedule, stops: List<Stop>, onSelect: (Int) -> Unit, onChangeRoute: () -> Unit, modifier: Modifier) {
    val plan = trip.plan
    val outcome = plan.outcome
    val many = trip.routes.size > 1
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(VoltaColors.Surface).border(1.dp, VoltaColors.Border, RoundedCornerShape(22.dp)).padding(horizontal = 18.dp, vertical = 16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when (outcome) {
                is PlanOutcome.Ok -> {
                    Text("Arrive ${time(sched.endAt)}", style = MaterialTheme.typography.headlineLarge.copy(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold).tabular(), maxLines = 1, modifier = Modifier.alignByBaseline())
                    Text("${outcome.arrivalSoc.roundToInt()} %", style = MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold).tabular(), color = VoltaColors.Champagne, modifier = Modifier.alignByBaseline())
                }

                is PlanOutcome.Unreachable -> Text("Short of range", style = MaterialTheme.typography.headlineLarge.copy(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold), color = VoltaColors.Amber)
            }
        }
        val stopText = when (stops.size) {
            0 -> "no stops"
            1 -> "1 stop"
            else -> "${stops.size} stops"
        }
        Text(
            listOfNotNull(
                duration((sched.endAt - trip.departAt) / 60_000),
                stopText,
                if (stops.isNotEmpty()) "${duration(plan.chargeMinutes)} charging" else null,
                trip.route.label?.let { "via $it" },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium.tabular(),
            color = VoltaColors.Muted,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (trip.options.size > 1 || many) {
            val best = trip.options.minOf { it.plan.driveMinutes + it.plan.chargeMinutes }
            Row(Modifier.padding(top = 12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (trip.options.size > 1) {
                    trip.options.forEachIndexed { i, o ->
                        val on = i == trip.selected
                        val extra = ((o.plan.driveMinutes + o.plan.chargeMinutes) - best).roundToInt()
                        Box(
                            Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(12.dp)).background(if (on) VoltaColors.SurfaceHigh else Color.Transparent)
                                .clickable(role = Role.Tab) { onSelect(i) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                o.goal.label + if (extra > 0) " · +$extra min" else "",
                                style = MaterialTheme.typography.labelMedium.tabular(),
                                color = if (on) VoltaColors.Text else VoltaColors.Muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 6.dp),
                            )
                        }
                    }
                }
                if (many) {
                    Box(
                        Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onChangeRoute),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Route ▾", style = MaterialTheme.typography.labelMedium, color = VoltaColors.Gold, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** Start trip and the Maps shortcut, pinned over the bottom of the page. */
@Composable
private fun PinnedActions(modifier: Modifier, onStart: () -> Unit, onMaps: () -> Unit) {
    Row(
        modifier.fillMaxWidth().background(Brush.verticalGradient(0f to Color.Transparent, 0.3f to VoltaColors.Bg)).padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VoltaButton("Start trip", onStart, Modifier.weight(1f), icon = VoltaIcons.Trend)
        Box(
            Modifier.size(54.dp).clip(RoundedCornerShape(18.dp)).background(VoltaColors.Bg).border(1.dp, VoltaColors.BorderStrong, RoundedCornerShape(18.dp)).clickable(role = Role.Button, onClick = onMaps),
            contentAlignment = Alignment.Center,
        ) { Icon(VoltaIcons.Map, "Open in Google Maps", tint = VoltaColors.Gold, modifier = Modifier.size(22.dp)) }
    }
}

private fun mapsUrl(trip: PlannedTrip, stops: List<Stop>): String {
    fun ll(lat: Double, lng: Double) = "$lat,$lng"
    val waypoints = stops.joinToString("|") { ll(it.charger.position.lat, it.charger.position.lng) }
    return "https://www.google.com/maps/dir/?api=1&origin=${ll(trip.origin.lat, trip.origin.lng)}" +
        "&destination=${ll(trip.destination.position.lat, trip.destination.position.lng)}" +
        (if (waypoints.isNotEmpty()) "&waypoints=${Uri.encode(waypoints)}" else "") + "&travelmode=driving"
}

private fun openMaps(context: android.content.Context, trip: PlannedTrip?) {
    trip ?: return
    val stops = (trip.plan.outcome as? PlanOutcome.Ok)?.stops.orEmpty()
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(mapsUrl(trip, stops))).setPackage("com.google.android.apps.maps").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
