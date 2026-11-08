package dev.irshadm.volta

import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dev.irshadm.volta.core.designsystem.VoltaColors
import dev.irshadm.volta.core.designsystem.VoltaIcons
import dev.irshadm.volta.core.designsystem.VoltaMotion
import dev.irshadm.volta.core.designsystem.VoltaTheme
import dev.irshadm.volta.core.domain.ObserveStartDestinationUseCase
import dev.irshadm.volta.core.domain.StartDestination
import dev.irshadm.volta.feature.onboarding.mapskey.MapsKeyMode
import dev.irshadm.volta.feature.onboarding.mapskey.MapsKeyRoute
import dev.irshadm.volta.feature.onboarding.pin.ControlPinRoute
import dev.irshadm.volta.feature.onboarding.signin.SignInRoute
import dev.irshadm.volta.ui.components.BottomTab
import dev.irshadm.volta.ui.components.VoltaBottomBar
import dev.irshadm.volta.ui.energy.EnergyScreen
import dev.irshadm.volta.ui.home.HomeScreen
import dev.irshadm.volta.ui.plan.PlanScreen
import dev.irshadm.volta.ui.settings.SettingsScreen
import dev.irshadm.volta.ui.trip.TripScreen
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(VoltaColors.Bg.toArgb()),
            navigationBarStyle = SystemBarStyle.dark(VoltaColors.Bg.toArgb()),
        )
        super.onCreate(savedInstanceState)
        setContent {
            VoltaTheme {
                // The intro plays once per cold start, over the app (which is already loading underneath).
                var intro by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(savedInstanceState == null) }
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    if (intro) {
                        dev.irshadm.volta.ui.splash.SplashGate.holding = true
                        dev.irshadm.volta.ui.splash.SplashGate.landed = false
                    }
                }
                val link by ismart.link.collectAsStateWithLifecycle()
                // First run (sign-in, then the optional Google key) is a gate over the app; so is the connection-lost
                // screen when the saved login stops working, until it's dismissed.
                val start by observeStart().collectAsStateWithLifecycle(initialValue = null)
                val dismissed by ismart.reconnectDismissed.collectAsStateWithLifecycle()
                Box(Modifier.fillMaxSize()) {
                    if (start == StartDestination.Home) AppNav()
                    when {
                        start == StartDestination.SignIn -> SignInRoute()

                        start == StartDestination.ControlPin -> ControlPinRoute()

                        start == StartDestination.MapsKey -> MapsKeyRoute(MapsKeyMode.FirstRun)

                        link is dev.irshadm.volta.data.ismart.LinkState.NeedsReconnect && !dismissed ->
                            dev.irshadm.volta.ui.account.ConnectionLostScreen()
                    }
                    if (intro) dev.irshadm.volta.ui.splash.SplashIntro(link) { intro = false }
                }
            }
        }
    }

    @javax.inject.Inject lateinit var ismart: dev.irshadm.volta.data.ismart.IsmartRepository

    @javax.inject.Inject lateinit var trips: dev.irshadm.volta.data.trip.TripRepository

    @javax.inject.Inject lateinit var observeStart: ObserveStartDestinationUseCase

    private var lastAutoRefresh = 0L

    /** Opening the app (or coming back after a couple of minutes) fetches fresh car data straight away. */
    override fun onStart() {
        super.onStart()
        val now = System.currentTimeMillis()
        if (now - lastAutoRefresh > 2 * 60_000) {
            lastAutoRefresh = now
            lifecycleScope.launch { ismart.refresh() }
        }
        // A planned trip the process died in the middle of stays "in progress" until we close it here.
        lifecycleScope.launch { runCatching { trips.closeStale() } }
    }
}

private val Tabs = listOf(
    BottomTab("battery", "Dashboard", VoltaIcons.Dashboard),
    BottomTab("plan", "Plan", VoltaIcons.Pin),
    BottomTab("trip", "Trip", VoltaIcons.Route),
    BottomTab("energy", "Energy", VoltaIcons.Bars),
    BottomTab("settings", "Settings", VoltaIcons.Gear),
)

@Composable
private fun AppNav() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    fun go(route: String) = nav.navigate(route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
    Scaffold(
        containerColor = VoltaColors.Bg,
        bottomBar = { VoltaBottomBar(Tabs, current) { go(it.route) } },
    ) { padding ->
        NavHost(
            nav,
            startDestination = "battery",
            modifier = Modifier.fillMaxSize().background(VoltaColors.Bg).padding(bottom = padding.calculateBottomPadding()),
            enterTransition = { fadeIn(tween(260)) },
            exitTransition = { fadeOut(tween(180)) },
        ) {
            composable("battery") { HomeScreen(onPlanTrip = { go("plan") }, onSettings = { go("settings") }) }
            composable("plan") { PlanScreen(onTripStarted = { go("trip") }) }
            composable("trip") { TripScreen(onPlan = { go("plan") }) }
            composable("energy") { EnergyScreen(onSettings = { go("settings") }) }
            composable("settings") { Box(Modifier.fillMaxSize()) { SettingsScreen(onBack = { go("battery") }) } }
        }
    }
}
