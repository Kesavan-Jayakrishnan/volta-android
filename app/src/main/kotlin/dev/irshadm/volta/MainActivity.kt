package dev.irshadm.volta

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.irshadm.volta.core.designsystem.VoltaTheme
import dev.irshadm.volta.core.designsystem.Wt
import dev.irshadm.volta.core.domain.StartDestination
import dev.irshadm.volta.feature.onboarding.mapskey.MapsKeyMode
import dev.irshadm.volta.feature.onboarding.mapskey.MapsKeyRoute
import dev.irshadm.volta.feature.onboarding.signin.SignInRoute
import dev.irshadm.volta.navigation.VoltaNavHost

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val app: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dark-only app: light status and navigation bar icons on every device setting.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        setContent {
            VoltaTheme {
                val destination by app.destination.collectAsStateWithLifecycle()
                Box(Modifier.fillMaxSize().background(Wt.Bg)) {
                    // First run is a gate in front of the app, not part of its back stack: Back from step 1 leaves.
                    when (destination) {
                        null -> Unit
                        StartDestination.SignIn -> SignInRoute()
                        StartDestination.MapsKey -> MapsKeyRoute(MapsKeyMode.FirstRun)
                        StartDestination.Home -> VoltaNavHost()
                    }
                }
            }
        }
    }
}
