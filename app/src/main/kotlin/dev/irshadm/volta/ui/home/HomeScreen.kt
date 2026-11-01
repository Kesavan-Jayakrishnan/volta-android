package dev.irshadm.volta.ui.home

import androidx.compose.animation.AnimatedVisibility
import dev.irshadm.volta.core.ismart.RemoteCommand
import dev.irshadm.volta.ui.components.confirmWithBiometrics
import dev.irshadm.volta.data.ismart.LinkState
import dev.irshadm.volta.data.ismart.CommandState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.irshadm.volta.ui.components.CarHero
import dev.irshadm.volta.ui.components.StatusTile
import dev.irshadm.volta.ui.components.WtButton
import dev.irshadm.volta.ui.components.WtButtonStyle
import dev.irshadm.volta.ui.components.WtCard
import dev.irshadm.volta.ui.components.WtChip
import dev.irshadm.volta.core.designsystem.WtIcons
import dev.irshadm.volta.ui.components.appear
import dev.irshadm.volta.ui.components.pressScale
import dev.irshadm.volta.core.designsystem.LocalReducedMotion
import dev.irshadm.volta.core.designsystem.Wt
import dev.irshadm.volta.core.designsystem.WtMotion
import dev.irshadm.volta.core.designsystem.tabular
import dev.irshadm.volta.core.model.SocReading
import dev.irshadm.volta.core.model.SocSource
import dev.irshadm.volta.core.model.TyrePressures
import kotlin.math.roundToInt

@Composable
fun HomeScreen(onPlanTrip: () -> Unit, onSettings: () -> Unit, viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var forceDiagram by rememberSaveable { mutableStateOf(false) }
    if (!state.loaded) return

    val pullState = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = state.refreshing && !state.waitingForCar,
        onRefresh = { if (state.mode == DataMode.Live) viewModel.refresh() },
        state = pullState,
        modifier = Modifier.fillMaxSize(),
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = pullState, isRefreshing = state.refreshing && !state.waitingForCar,
                containerColor = Wt.Surface, color = Wt.Gold,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding(),
            )
        },
    ) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
    ) {
    // The dusk sky runs from the very top of the screen, behind the header, down past the location line,
    // so there's no edge where the photo starts.
    var topPx by remember { mutableStateOf(0f) }
    var heroTop by remember { mutableStateOf(0f) }
    var heroH by remember { mutableStateOf<Float?>(null) }
    // Tyres worth a look: an amber dot on the wheel in the photo, the tyre line in the panel, and this sheet.
    var tyresOpen by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val tyreContext = LocalContext.current
    val tyreVersion by TyreCare.version.collectAsState()
    val lowWheels = remember(state.vehicle?.tyrePsi, state.vehicle?.warningCodes, tyreVersion) {
        TyreCare.checks(tyreContext, state.vehicle?.tyrePsi?.value, carFlag = TyreCare.carFlag(state.vehicle?.warningCodes?.value)).map { it.wheel }.toSet()
    }
    if (tyresOpen) state.vehicle?.let { TyreSheet(it) { tyresOpen = false } }
    Box(Modifier.fillMaxWidth().onGloballyPositioned { topPx = it.boundsInWindow().top }) {
        if (state.hero != null || state.waitingForCar) dev.irshadm.volta.ui.components.DuskBackdrop(Modifier.matchParentSize(), heroTop, heroH)
    Column(Modifier.statusBarsPadding()) {
        Header(state, onSettings, Modifier.appear(0))
        LinkBanner(state, viewModel, onSettings, Modifier.padding(horizontal = 16.dp).padding(top = 10.dp))

        if (state.waitingForCar) SkeletonCar(state.car.isWindsor, Modifier.onGloballyPositioned {
            val b = it.boundsInWindow()
            heroTop = b.top - topPx; heroH = b.height
        }.appear(1))
        state.hero?.let { hero ->
            Box(Modifier.onGloballyPositioned {
                val b = it.boundsInWindow()
                dev.irshadm.volta.ui.splash.HeroAnchor.bounds = b
                heroTop = b.top - topPx; heroH = b.height
            }.appear(1)) {
                CarHero(hero, forceDiagram, onToggleView = { forceDiagram = !forceDiagram }, backdrop = false, lowWheels = lowWheels, onTyreTap = { tyresOpen = true }, windsor = state.car.isWindsor)
                state.vehicle?.let { v ->
                    val context = LocalContext.current
                    AttentionBanner(v, state.nowMillis, onFix = { cmd ->
                        if (!state.hasPin) onSettings()
                        else confirmWithBiometrics(context, "Confirm: ${cmd.label.lowercase()}", "Sends the command to your car") { viewModel.send(cmd) }
                    }, Modifier.align(Alignment.TopCenter).padding(horizontal = 16.dp, vertical = 4.dp))
                }
            }
        }

        // Where the car is: one quiet line under the photo. Its space is reserved from the first frame and the text
        // fades in once the car has landed after the splash, so nothing below it jumps.
        if (state.waitingForCar) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(34.dp), contentAlignment = Alignment.Center) {
                SkeletonBar(skeletonBrush(), 230.dp, 14.dp)
            }
        } else if (state.mode == DataMode.Live) {
            val landed = dev.irshadm.volta.ui.splash.SplashGate.landed
            val lineAlpha by androidx.compose.animation.core.animateFloatAsState(if (landed) 1f else 0f, tween(480, easing = WtMotion.EaseOut), label = "locationLine")
            Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(34.dp).graphicsLayer { alpha = lineAlpha }, contentAlignment = Alignment.Center) {
                LocationLine(state, viewModel::nameCurrentPlace)
            }
        }
    }
    }

        Column(Modifier.padding(horizontal = 12.dp).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.waitingForCar) PanelSkeleton(skeletonBrush(), Modifier.appear(3))
            else HomePanel(state, viewModel, onSettings, Modifier.appear(3), onTyres = { tyresOpen = true })
        }

    }
    }

}

@Composable
private fun Header(state: HomeUiState, onSettings: () -> Unit, modifier: Modifier) {
    Row(modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 16.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(state.car.name, style = MaterialTheme.typography.headlineLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                state.vehicle?.let { StatusChip(it, state.charger.chargerCharging) }
                if (state.waitingForCar) SkeletonBar(skeletonBrush(), 150.dp, 30.dp, shape = CircleShape)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // A sleeping car can take a minute or two, so after a while say why it's still loading.
                var slow by remember { mutableStateOf(false) }
                androidx.compose.runtime.LaunchedEffect(state.waitingForCar) {
                    slow = false
                    if (state.waitingForCar) { kotlinx.coroutines.delay(20_000); slow = true }
                }
                val (dot, label) = if (state.waitingForCar) Wt.Gold to (if (slow) "Waking your car, this can take a minute" else "Reading your car…")
                    else sourceLabel(state)
                Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
                Text(label, style = MaterialTheme.typography.bodyMedium, color = Wt.Muted)
            }
        }
    }
}

private fun sourceLabel(state: HomeUiState): Pair<Color, String> {
    val r = state.soc ?: return Wt.Dim to "No battery reading yet"
    val mins = r.ageMinutes(state.nowMillis)
    val age = when {
        mins < 1 -> "just now"
        mins < 60 -> "$mins min ago"
        mins < 24 * 60 -> "${mins / 60} h ago"
        else -> "${mins / (24 * 60)} d ago"
    }
    return when (r.source) {
        SocSource.MANUAL -> Wt.Gold to "Manual · $age"
        SocSource.ISMART -> if (mins > 60) Wt.Amber to "Car data from $age" else Wt.Green to "Live · updated $age"
    }
}

/** The reconnect banner and refresh errors. */
@Composable
private fun LinkBanner(state: HomeUiState, vm: HomeViewModel, onSettings: () -> Unit, modifier: Modifier) {
    val link = state.link
    AnimatedVisibility(link is LinkState.NeedsReconnect || (link is LinkState.Connected && link.error != null) || link is LinkState.SignedOut) {
        when (link) {
            is LinkState.NeedsReconnect -> WtCard(modifier.fillMaxWidth(), background = Wt.tint(Wt.Amber, 0.10f), border = Wt.tint(Wt.Amber, 0.45f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(WtIcons.Warning, null, tint = Wt.Amber, modifier = Modifier.size(20.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Car connection lost", style = MaterialTheme.typography.titleSmall, color = Wt.AmberText)
                        Text("Signed out by another app. Live data is paused.", style = MaterialTheme.typography.bodySmall, color = Wt.AmberText)
                    }
                    WtButton("Reconnect", { vm.reconnect() })
                }
            }
            is LinkState.Connected -> Text(link.error.orEmpty(), style = MaterialTheme.typography.bodySmall, color = Wt.Amber, modifier = modifier)
            // Signed out, the sign-in screen covers the app; nothing to show here.
            LinkState.SignedOut -> Unit
        }
    }
}

private enum class CarActivity(val label: String, val color: Color) {
    Charging("Charging", Wt.Green), PluggedIn("Plugged in", Wt.BlueSoft), Driving("Driving", Wt.Gold), Parked("Parked", Wt.Muted)
}

/** Parked / driving / charging at a glance, next to the car's name. */
@Composable
private fun StatusChip(v: dev.irshadm.volta.core.model.VehicleStatus, chargerCharging: Boolean = false) {
    val context = LocalContext.current
    val activity = when {
        v.charging?.value == true || chargerCharging -> CarActivity.Charging
        v.pluggedIn?.value == true -> CarActivity.PluggedIn
        v.ignitionOn?.value == true || (v.speedKmh?.value ?: 0.0) > 3 -> CarActivity.Driving
        else -> CarActivity.Parked
    }
    val detail = when (activity) {
        CarActivity.Charging -> chargeMinutesLeft(v, dev.irshadm.volta.core.model.VehicleProfile().usableCapacityKwh)?.takeIf { it > 0 }?.let { m -> "full by ~" + sinceClock(System.currentTimeMillis() + m * 60_000L) }
            ?: v.chargePowerKw?.value?.takeIf { it > 0.5 }?.let { "%.1f kW".format(it) }
        CarActivity.Driving -> v.speedKmh?.value?.takeIf { it > 3 }?.let { "${it.roundToInt()} km/h" }
        CarActivity.Parked -> when {
            v.climateRunning?.value == true -> if (dev.irshadm.volta.service.smart.Cooling.session(context) != null) "· cooling" else "· AC on"
            else -> v.ignitionChangedAtMillis?.let { "since " + sinceClock(it) }
        }
        else -> null
    }
    val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "chip").animateFloat(
        1f, if (activity == CarActivity.Charging) 0.35f else 1f,
        androidx.compose.animation.core.infiniteRepeatable(tween(900), androidx.compose.animation.core.RepeatMode.Reverse), label = "dot",
    )
    androidx.compose.animation.AnimatedContent(activity to detail, label = "chip") { (a, d) ->
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(Wt.tint(a.color, 0.14f)).border(1.dp, Wt.tint(a.color, 0.35f), RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(7.dp).graphicsLayer { alpha = if (a == CarActivity.Charging) pulse else 1f }.clip(CircleShape).background(a.color))
            Text(a.label + (d?.let { if (a == CarActivity.Parked) " $it" else " · $it" } ?: ""), style = MaterialTheme.typography.labelMedium, color = a.color)
        }
    }
}

/** "7:45 pm" today, "Mon 7:45 pm" this week, "21 Oct" before that. */
private fun sinceClock(at: Long): String {
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = at }
    val sameDay = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) && now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
    val days = (now.timeInMillis - at) / 86_400_000L
    val fmt = when { sameDay -> "h:mm a"; days < 6 -> "EEE h:mm a"; else -> "d MMM" }
    return java.text.SimpleDateFormat(fmt, java.util.Locale.getDefault()).format(java.util.Date(at)).replace("AM", "am").replace("PM", "pm")
}
