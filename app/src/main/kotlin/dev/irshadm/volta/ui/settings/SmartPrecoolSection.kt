package dev.irshadm.volta.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import dev.irshadm.volta.data.SavedPlace
import dev.irshadm.volta.data.SettingsRepository
import dev.irshadm.volta.data.SmartPrecool as PrecoolConfig
import dev.irshadm.volta.data.db.TripDao
import dev.irshadm.volta.data.ismart.IsmartRepository
import dev.irshadm.volta.data.trip.LocationProvider
import dev.irshadm.volta.service.smart.Feedback
import dev.irshadm.volta.service.smart.Narrator
import dev.irshadm.volta.service.smart.Routine
import dev.irshadm.volta.service.smart.SmartPrecool
import dev.irshadm.volta.ui.components.WtButton
import dev.irshadm.volta.ui.components.WtButtonStyle
import dev.irshadm.volta.core.designsystem.Wt
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SmartPrecoolViewModel @Inject constructor(
    @ApplicationContext private val context: android.content.Context,
    private val settings: SettingsRepository,
    private val ismart: IsmartRepository,
    private val trips: TripDao,
    private val location: LocationProvider,
) : ViewModel() {
    val config = settings.smartPrecool.stateIn(viewModelScope, SharingStarted.Eagerly, PrecoolConfig())
    val places = settings.savedPlaces.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val routine = MutableStateFlow<List<Routine.Slot>>(emptyList())
    val memory = MutableStateFlow<Map<String, Feedback.PlaceMemory>>(emptyMap())
    val ai = MutableStateFlow(Narrator.State.Unavailable)
    val note = MutableStateFlow<String?>(null)
    val message = MutableStateFlow<String?>(null)

    init { reload() }

    fun reload() = viewModelScope.launch {
        routine.value = Routine.slots(trips.finished(300))
        memory.value = Feedback.all(context)
        ai.value = Narrator.state()
        if (ai.value == Narrator.State.Ready && note.value == null) {
            val facts = buildList {
                routine.value.take(4).forEach { add("usual departure: ${it.label} (${it.count} times in 4 weeks)") }
                memory.value.forEach { (p, m) ->
                    if (m.accepted + m.dismissed > 0) add("at $p: accepted ${m.accepted}, said not now ${m.dismissed}" + if (m.muted) ", now muted there" else "")
                }
            }
            note.value = Narrator.weeklyNote(facts)
        }
    }

    private fun rearm() = viewModelScope.launch {
        SmartPrecool.arm(context, settings.smartPrecool.first().enabled, settings.savedPlaces.first(), ismart.status.value)
    }

    fun save(c: PrecoolConfig) = viewModelScope.launch { settings.saveSmartPrecool(c); rearm() }

    /** Saves where the phone is now, with the Wi-Fi it's on. */
    fun addHere(name: String) = viewModelScope.launch {
        val fix = location.current()
        if (fix == null) { message.value = "Couldn't get your location."; return@launch }
        val ssid = SmartPrecool.currentWifi(context)
        settings.savePlace(SavedPlace(name.trim(), fix.position.lat, fix.position.lng, ssid = ssid))
        message.value = if (ssid == null) "Saved $name — connect to its Wi-Fi and add it again to use leaving it as a signal." else "Saved $name with Wi-Fi “$ssid”."
        rearm()
    }

    fun remove(p: SavedPlace) = viewModelScope.launch { settings.removePlace(p.name); rearm() }
    fun unmute(place: String) { Feedback.unmute(context, place); memory.value = Feedback.all(context) }
    fun downloadAi() = viewModelScope.launch { ai.value = Narrator.State.Downloading; Narrator.download(); ai.value = Narrator.state() }
}

@Composable
fun SmartPrecoolSection(vm: SmartPrecoolViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val cfg by vm.config.collectAsStateWithLifecycle()
    val places by vm.places.collectAsStateWithLifecycle()
    val routine by vm.routine.collectAsStateWithLifecycle()
    val memory by vm.memory.collectAsStateWithLifecycle()
    val ai by vm.ai.collectAsStateWithLifecycle()
    val note by vm.note.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var allowed by remember { mutableStateOf(SmartPrecool.hasPermission(context)) }
    var adding by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val now = SmartPrecool.hasPermission(context)
            if (now && !allowed && cfg.enabled) vm.save(cfg)
            allowed = now
        }
    }
    val fine = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { allowed = SmartPrecool.hasPermission(context) }
    var onFoot by remember { mutableStateOf(dev.irshadm.volta.service.smart.Walking.hasPermission(context)) }
    val activity = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        onFoot = dev.irshadm.volta.service.smart.Walking.hasPermission(context)
        if (onFoot) vm.save(cfg)
    }

    if (adding) {
        var name by remember { mutableStateOf("") }
        val wifi = remember { SmartPrecool.currentWifi(context) }
        AlertDialog(
            onDismissRequest = { adding = false },
            containerColor = Wt.Surface,
            title = { Text("Add this place", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(wifi?.let { "Uses where you are now and the Wi-Fi “$it”." } ?: "Not on Wi-Fi — connect to this place's Wi-Fi first for the best signal.",
                        style = MaterialTheme.typography.bodySmall, color = if (wifi == null) Wt.AmberText else Wt.Muted)
                    OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("Office") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton({ vm.addHere(name); adding = false }, enabled = name.isNotBlank()) { Text("Save", color = Wt.Gold) } },
            dismissButton = { TextButton({ adding = false }) { Text("Cancel", color = Wt.Muted) } },
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Suggest AC when I head to the car", style = MaterialTheme.typography.bodyMedium)
                Text("Daytime, car parked, inside and outside above ${cfg.aboveC}°. One suggestion per departure — you always confirm with your fingerprint.",
                    style = MaterialTheme.typography.bodySmall, color = Wt.Muted)
            }
            Switch(cfg.enabled, { vm.save(cfg.copy(enabled = it)) }, colors = SwitchDefaults.colors(checkedTrackColor = Wt.Champagne, checkedThumbColor = Wt.Bg))
        }
        if (!cfg.enabled) return@Column

        Text("Above ${cfg.aboveC}° (it learns per place from your answers)", style = MaterialTheme.typography.labelMedium, color = Wt.TextSoft)
        Slider(cfg.aboveC.toFloat(), { vm.save(cfg.copy(aboveC = it.toInt())) }, valueRange = 25f..35f, steps = 9,
            colors = SliderDefaults.colors(thumbColor = Wt.Gold, activeTrackColor = Wt.Gold, inactiveTrackColor = Wt.Border))

        if (!allowed) {
            Text("Needs location “Allow all the time” so Android can notice you leaving a place or walking back to the car.",
                style = MaterialTheme.typography.bodySmall, color = Wt.AmberText)
            WtButton("Allow location", {
                val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (!hasFine) fine.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                else context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            }, style = WtButtonStyle.Secondary)
        }

        if (allowed && !onFoot && android.os.Build.VERSION.SDK_INT >= 29) {
            Text("Optional: let Volta notice when you're walking, so it can say \"cool it while you walk there\" and skip passing the car in a cab.",
                style = MaterialTheme.typography.bodySmall, color = Wt.Muted)
            WtButton("Allow walking detection", { activity.launch(Manifest.permission.ACTIVITY_RECOGNITION) }, style = WtButtonStyle.Secondary)
        }

        Text("Uses your Places: leaving one's Wi-Fi means you're heading to the car (Settings → Places).", style = MaterialTheme.typography.bodySmall, color = Wt.Muted)
        val muted = memory.filterValues { it.muted }
        if (muted.isNotEmpty()) {
            Text("MUTED", style = MaterialTheme.typography.labelSmall, color = Wt.Dim)
            muted.keys.forEach { place ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(place.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text("Unmute", style = MaterialTheme.typography.labelMedium, color = Wt.Gold, modifier = Modifier.clickable { vm.unmute(place) }.padding(6.dp))
                }
            }
        }

        // Routine learned from drives.
        Text("YOUR ROUTINE", style = MaterialTheme.typography.labelSmall, color = Wt.Dim)
        if (routine.isEmpty()) Text("Learning — needs about 1–2 weeks of drives. Until then it uses Wi-Fi + temperature only.",
            style = MaterialTheme.typography.bodySmall, color = Wt.Muted)
        routine.forEach { Text("• ${it.label}  (${it.count}×)", style = MaterialTheme.typography.bodySmall, color = Wt.TextSoft) }

        // On-device AI.
        Text("ON-DEVICE AI", style = MaterialTheme.typography.labelSmall, color = Wt.Dim)
        when (ai) {
            Narrator.State.Ready -> {
                Text("Gemini Nano ready — writes suggestions and this summary on your phone, offline.", style = MaterialTheme.typography.bodySmall, color = Wt.Green)
                note?.let { Text("✦ $it", style = MaterialTheme.typography.bodyMedium, color = Wt.TextSoft) }
            }
            Narrator.State.Downloadable -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Gemini Nano can be downloaded (free, one time).", style = MaterialTheme.typography.bodySmall, color = Wt.Muted, modifier = Modifier.weight(1f))
                WtButton("Download", { vm.downloadAi() }, style = WtButtonStyle.Secondary)
            }
            Narrator.State.Downloading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(color = Wt.Gold, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                Text("Downloading Gemini Nano…", style = MaterialTheme.typography.bodySmall, color = Wt.Muted)
            }
            Narrator.State.Unavailable -> Text("Gemini Nano isn't available on this phone right now — suggestions use plain text.",
                style = MaterialTheme.typography.bodySmall, color = Wt.Muted)
        }
    }
}
