package dev.irshadm.volta.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.irshadm.volta.feature.onboarding.mapskey.MapsKeyMode
import dev.irshadm.volta.feature.onboarding.mapskey.MapsKeyRoute
import dev.irshadm.volta.home.HomeRoute
import kotlinx.serialization.Serializable

@Serializable data object HomeDestination
@Serializable data object MapsKeySettingsDestination

/** Navigation inside the signed-in app. Type-safe routes: each destination is a serializable object. */
@Composable
fun VoltaNavHost() {
    val nav = rememberNavController()
    NavHost(nav, startDestination = HomeDestination) {
        composable<HomeDestination> {
            HomeRoute(onOpenMapsKey = { nav.navigate(MapsKeySettingsDestination) })
        }
        composable<MapsKeySettingsDestination> {
            MapsKeyRoute(MapsKeyMode.Settings, onClose = { nav.popBackStack() })
        }
    }
}
