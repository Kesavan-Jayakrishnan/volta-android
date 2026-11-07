package dev.irshadm.volta.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.irshadm.volta.data.SettingsRepository
import dev.irshadm.volta.data.db.TripDao
import dev.irshadm.volta.data.ismart.IsmartRepository
import dev.irshadm.volta.data.trip.LocationProvider
import dev.irshadm.volta.ui.components.VoltaButton
import dev.irshadm.volta.ui.components.VoltaButtonStyle
import dev.irshadm.volta.core.designsystem.VoltaColors
import dev.irshadm.volta.core.model.EnergySettings
import dev.irshadm.volta.core.model.Tariff
import dev.irshadm.volta.core.model.VehicleProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class Learned(val kmPerKwh: Double, val source: String)

@HiltViewModel
class EnergySettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val ismart: IsmartRepository,
    private val location: LocationProvider,
    private val trips: TripDao,
) : ViewModel() {
    val energy = settings.energySettings.stateIn(viewModelScope, SharingStarted.Eagerly, EnergySettings())
    val tariff = settings.tariff.stateIn(viewModelScope, SharingStarted.Eagerly, Tariff())

    suspend fun setHomeFromCar(): String {
        val gps = ismart.status.value?.gps?.value ?: return "The car hasn't reported a location yet — refresh on Battery first."
        settings.saveEnergy(energy.value.copy(homeLat = gps.latitude, homeLng = gps.longitude))
        return "Home set to the car's location."
    }

    suspend fun setHomeFromPhone(): String {
        val fix = location.current() ?: return "Couldn't get your location (allow location for Volta)."
        settings.saveEnergy(energy.value.copy(homeLat = fix.position.lat, homeLng = fix.position.lng))
        return "Home set to your phone's location."
    }

    fun save(e: EnergySettings, t: Tariff) = viewModelScope.launch { settings.saveEnergy(e); settings.saveTariff(t) }

    /** Efficiency learned from logged trips, else the car's "since last charge" counters. */
    suspend fun learned(): Learned? {
        val done = trips.finished(50).mapNotNull { t ->
            val km = if (t.startOdometerKm != null && t.endOdometerKm != null) t.endOdometerKm - t.startOdometerKm else t.plannedDistanceM?.div(1000)
            val kwh = t.usedKwh
            if (km != null && kwh != null && km >= 5 && kwh >= 1) km to kwh else null
        }
        if (done.isNotEmpty()) return Learned(done.sumOf { it.first } / done.sumOf { it.second }, "from ${done.size} logged trip${if (done.size > 1) "s" else ""}")
        val s = ismart.status.value
        val km = s?.distanceSinceLastChargeKm?.value
        val kwh = s?.energySinceLastChargeKwh?.value
        return if (km != null && kwh != null && km >= 5 && kwh >= 1) Learned(km / kwh, "since the last charge (${km.toInt()} km)") else null
    }

    fun applyLearned(l: Learned) = viewModelScope.launch {
        val p = settings.vehicleProfile.first()
        val d = VehicleProfile()
        settings.save(p.copy(highwayKmPerKwh = round1(l.kmPerKwh), cityKmPerKwh = round1(l.kmPerKwh * d.cityKmPerKwh / d.highwayKmPerKwh)))
    }

    private fun round1(x: Double) = Math.round(x * 10) / 10.0
}

@Composable
fun EnergySettingsSection(vm: EnergySettingsViewModel = hiltViewModel()) {
    val e by vm.energy.collectAsStateWithLifecycle()
    val t by vm.tariff.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var household by remember(e) { mutableStateOf(e.householdUnitsPerCycle.toInt().toString()) }
    var acRate by remember(e) { mutableStateOf(e.publicAcRatePerKwh.toString()) }
    var dcRate by remember(e) { mutableStateOf(e.dcRatePerKwh.toString()) }
    var tele by remember(t) { mutableStateOf(t.telescopic.joinToString("  ") { "${it.first}@${it.second}" }) }
    var flat by remember(t) { mutableStateOf(t.nonTelescopic.joinToString("  ") { "${if (it.first == Int.MAX_VALUE) "∞" else it.first}@${it.second}" }) }
    var duty by remember(t) { mutableStateOf(t.dutyPercent.toString()) }
    var learned by remember { mutableStateOf<Learned?>(null) }
    LaunchedEffect(Unit) { learned = vm.learned() }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (e.hasHome) "Home is set (charging within ${e.homeRadiusM.toInt()} m counts as home)." else "Home not set yet.", style = MaterialTheme.typography.bodyMedium, color = if (e.hasHome) VoltaColors.Green else VoltaColors.AmberText)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            VoltaButton("Car's location", { scope.launch { message = vm.setHomeFromCar() } }, Modifier.weight(1f), style = VoltaButtonStyle.Secondary)
            VoltaButton("My location", { scope.launch { message = vm.setHomeFromPhone() } }, Modifier.weight(1f), style = VoltaButtonStyle.Secondary)
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = VoltaColors.TextSoft) }

        OutlinedTextField(household, { household = it.filter(Char::isDigit) }, label = { Text("Household units per bill (without EV)") }, suffix = { Text("units") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(acRate, { acRate = it }, label = { Text("Public AC") }, suffix = { Text("₹/kWh") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
            OutlinedTextField(dcRate, { dcRate = it }, label = { Text("DC fast") }, suffix = { Text("₹/kWh") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
        }
        Text("KSEB LT-1A tariff (per month: units@₹). Copy the rates from your latest bill.", style = MaterialTheme.typography.bodySmall, color = VoltaColors.Muted)
        OutlinedTextField(tele, { tele = it }, label = { Text("Telescopic slabs (up to 250/month)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(flat, { flat = it }, label = { Text("Above 250 — all units at band rate (up to@₹)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(duty, { duty = it }, label = { Text("Electricity duty") }, suffix = { Text("%") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        Text(t.verifiedOn?.let { "Rates last checked $it" } ?: "Sample rates — not yet checked against your bill", style = MaterialTheme.typography.bodySmall,
            color = if (t.verifiedOn == null) VoltaColors.Amber else VoltaColors.Muted)
        VoltaButton("Save & mark rates checked today", {
            fun pairs(s: String) = s.split(Regex("\\s+")).mapNotNull { p ->
                val (a, b) = p.split("@").takeIf { it.size == 2 } ?: return@mapNotNull null
                val n = if (a == "∞") Int.MAX_VALUE else a.toIntOrNull() ?: return@mapNotNull null
                n to (b.toDoubleOrNull() ?: return@mapNotNull null)
            }
            val today = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date())
            vm.save(
                e.copy(householdUnitsPerCycle = household.toDoubleOrNull() ?: e.householdUnitsPerCycle,
                    publicAcRatePerKwh = acRate.toDoubleOrNull() ?: e.publicAcRatePerKwh, dcRatePerKwh = dcRate.toDoubleOrNull() ?: e.dcRatePerKwh),
                t.copy(telescopic = pairs(tele).ifEmpty { t.telescopic }, nonTelescopic = pairs(flat).ifEmpty { t.nonTelescopic },
                    dutyPercent = duty.toDoubleOrNull() ?: t.dutyPercent, verifiedOn = today),
            )
            message = "Saved."
        }, Modifier.fillMaxWidth())

        Text("Learned efficiency", style = MaterialTheme.typography.titleSmall)
        val l = learned
        if (l == null) {
            Text("Appears after your first logged trip (or once the car reports driving since the last charge).", style = MaterialTheme.typography.bodySmall, color = VoltaColors.Muted)
        } else {
            Text("%.1f km/kWh %s".format(l.kmPerKwh, l.source), style = MaterialTheme.typography.bodyMedium, color = VoltaColors.TextSoft)
            VoltaButton("Use for planning", { vm.applyLearned(l); message = "Planner efficiency updated." }, style = VoltaButtonStyle.Secondary)
        }
    }
}
