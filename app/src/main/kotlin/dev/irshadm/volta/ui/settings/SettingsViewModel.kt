package dev.irshadm.volta.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.irshadm.volta.core.model.ChargerPreferences
import dev.irshadm.volta.core.model.EnergySettings
import dev.irshadm.volta.core.model.Tariff
import dev.irshadm.volta.core.model.TripSettings
import dev.irshadm.volta.core.model.VehicleProfile
import dev.irshadm.volta.data.SavedPlace
import dev.irshadm.volta.data.SettingsRepository
import dev.irshadm.volta.data.SmartPrecool
import dev.irshadm.volta.data.ismart.IsmartRepository
import dev.irshadm.volta.service.Alerts
import dev.irshadm.volta.service.smart.Narrator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Every setting saves as soon as it changes (no Save button); invalid values are simply not stored. */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: SettingsRepository,
    val ismart: IsmartRepository,
    val spin: dev.irshadm.volta.data.charger.SpinCharger,
    mapsKeys: dev.irshadm.volta.core.domain.MapsKeyRepository,
) : ViewModel() {
    /** The user's own Google key, for the Settings row; the key screen itself manages it. */
    val mapsKey: StateFlow<String?> = mapsKeys.key
    private fun <T> stateFlowOf(flow: kotlinx.coroutines.flow.Flow<T>, initial: T): StateFlow<T> = flow.stateIn(viewModelScope, SharingStarted.Eagerly, initial)

    val vehicle = stateFlowOf(repo.vehicleProfile, VehicleProfile())
    val trip = stateFlowOf(repo.tripSettings, TripSettings())
    val chargers = stateFlowOf(repo.chargerPreferences, ChargerPreferences())
    val energy = stateFlowOf(repo.energySettings, EnergySettings())
    val tariff = stateFlowOf(repo.tariff, Tariff())
    val precool = stateFlowOf(repo.smartPrecool, SmartPrecool())
    val places = stateFlowOf(repo.savedPlaces, emptyList())
    val meals = stateFlowOf(repo.mealRules, dev.irshadm.volta.core.planner.MealRules())
    val nano = MutableStateFlow(Narrator.State.Unavailable)
    val alertsOn = MutableStateFlow(Alerts.onCount(context))
    val alertVersion = MutableStateFlow(0)

    init {
        viewModelScope.launch { nano.value = Narrator.state() }
    }

    fun saveTrip(t: TripSettings) {
        if (t.validate().isEmpty()) viewModelScope.launch { repo.save(t) }
    }
    fun saveVehicle(p: VehicleProfile) {
        if (p.validate().isEmpty()) viewModelScope.launch { repo.save(p) }
    }
    fun saveChargers(c: ChargerPreferences) = viewModelScope.launch { repo.save(c) }
    fun saveEnergy(e: EnergySettings) = viewModelScope.launch { repo.saveEnergy(e) }
    fun saveTariff(t: Tariff) = viewModelScope.launch { repo.saveTariff(t) }
    fun saveMeals(r: dev.irshadm.volta.core.planner.MealRules) = viewModelScope.launch { repo.saveMealRules(r) }
    val homeCovered = stateFlowOf(repo.homeCovered, true)
    fun setHomeCovered(on: Boolean) = viewModelScope.launch { repo.setHomeCovered(on) }

    fun setHome(p: SavedPlace) = saveEnergy(energy.value.copy(homeLat = p.lat, homeLng = p.lng))

    fun alertOn(key: String) = Alerts.isOn(context, key)
    fun setAlert(key: String, on: Boolean) {
        Alerts.set(context, key, on)
        alertsOn.value = Alerts.onCount(context)
        alertVersion.value++
    }
}
