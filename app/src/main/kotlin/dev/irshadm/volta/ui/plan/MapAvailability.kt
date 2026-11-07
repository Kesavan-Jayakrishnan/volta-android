package dev.irshadm.volta.ui.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.GoogleMapOptions
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.GoogleMapComposable
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import dev.irshadm.volta.BuildConfig
import dev.irshadm.volta.core.designsystem.VoltaColors
import dev.irshadm.volta.core.designsystem.VoltaIcons

/**
 * Whether this build can draw Google's interactive map. The Maps SDK only reads its key from the app's manifest at
 * build time, so the key entered in Settings (which covers routes, places and elevation) can't reach it. Builds made
 * with MAPS_API_KEY in local.properties get the map; others show [MapNeedsBuildKey] in its place.
 */
val interactiveMapAvailable: Boolean get() = BuildConfig.HAS_MAP_KEY

/** [GoogleMap] when this build has a map key, otherwise a quiet note in the same space. */
@Composable
fun KeyedGoogleMap(
    cameraPositionState: CameraPositionState,
    properties: MapProperties,
    uiSettings: MapUiSettings,
    googleMapOptionsFactory: () -> GoogleMapOptions,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    onMapLoaded: () -> Unit = {},
    content:
    @Composable @GoogleMapComposable
    () -> Unit,
) {
    if (!interactiveMapAvailable) {
        MapNeedsBuildKey(modifier.fillMaxSize())
        return
    }
    GoogleMap(
        modifier = modifier,
        cameraPositionState = cameraPositionState,
        properties = properties,
        uiSettings = uiSettings,
        googleMapOptionsFactory = googleMapOptionsFactory,
        contentPadding = contentPadding,
        onMapLoaded = onMapLoaded,
        content = content,
    )
}

@Composable
fun MapNeedsBuildKey(modifier: Modifier = Modifier) {
    Box(modifier.background(VoltaColors.Surface).padding(20.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(VoltaIcons.Map, null, tint = VoltaColors.Dim, modifier = Modifier.size(22.dp))
            Text("Map not included in this build", style = MaterialTheme.typography.titleSmall, color = VoltaColors.TextSoft)
            Text(
                "The route, stops and timings are all below. Builds made with a Maps SDK key show the map here.",
                style = MaterialTheme.typography.bodySmall,
                color = VoltaColors.Muted,
                textAlign = TextAlign.Center,
            )
        }
    }
}
