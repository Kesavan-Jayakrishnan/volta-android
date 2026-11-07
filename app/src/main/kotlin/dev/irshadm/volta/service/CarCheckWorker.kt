package dev.irshadm.volta.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.irshadm.volta.core.model.TyrePressures
import dev.irshadm.volta.core.model.VehicleStatus
import dev.irshadm.volta.data.ismart.IsmartRepository
import dev.irshadm.volta.data.ismart.LinkState
import dev.irshadm.volta.data.trip.TripRepository
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Alerts and charge-session capture when no trip is active. Each alert fires once
 * and re-arms only after the condition clears. The multi-reading alerts (car moved, speeding, trends,
 * pre-trip, charge by morning) are in [SmartAlerts].
 *
 * When it runs: mostly on events, not on a clock. One-shot checks ([checkIn]) come after you leave the car
 * ([leftCar]: Bluetooth to the head unit dropped, Android Auto ended), when a charge should be finished,
 * 45 min before a planned departure, and every 10 min while the car is on and the phone isn't in it. The
 * periodic run is a safety net every 30 min (15 while the anti-theft alert is on, since only polling can
 * see the car move). While Android Auto is connected [dev.irshadm.volta.car.InCarRefresh] reads the car instead.
 */
class CarCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun ismart(): IsmartRepository
        fun trips(): TripRepository
        fun location(): dev.irshadm.volta.data.trip.LocationProvider
        fun tripDao(): dev.irshadm.volta.data.db.TripDao
        fun settings(): dev.irshadm.volta.data.SettingsRepository
        fun maps(): dev.irshadm.volta.data.maps.GoogleMapsClient
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        val ismart = deps.ismart()
        if (deps.trips().active.value != null) return Result.success() // the trip service is already polling
        if (ismart.link.value !is LinkState.Connected) return Result.success()
        ismart.refresh(includeCharge = true)
        ismart.status.value?.let {
            evaluate(applicationContext, it)
            runCatching { SmartAlerts.evaluate(applicationContext, it, deps.location(), deps.tripDao()) }
            runCatching {
                RangeWatch.evaluate(applicationContext, it, deps.tripDao(), deps.settings().energySettings.first(), deps.settings().vehicleProfile.first(), deps.maps())
            }
            runCatching {
                BatteryCare.record(applicationContext, it)
                val weather = it.gps?.value?.let { g ->
                    EntryPointAccessors.fromApplication(applicationContext, dev.irshadm.volta.service.smart.SmartDeps::class.java).smartWeather().now(g.latitude, g.longitude)
                }
                val km = it.distanceSinceLastChargeKm?.value
                val kwh = it.energySinceLastChargeKwh?.value
                val kmPerKwh = if (km != null && kwh != null && kwh >= 1) km / kwh else 6.0
                BatteryCare.notify(
                    applicationContext,
                    BatteryCare.tip(
                        applicationContext,
                        it,
                        weather?.tempC,
                        BatteryCare.usualDailyKm(deps.tripDao().finished(300)),
                        kmPerKwh,
                        dev.irshadm.volta.core.model.VehicleProfile().usableCapacityKwh,
                    ),
                )
            }
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "car-check"
        private const val PREFS = "alerts"

        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** The safety-net interval: 15 min (Android's shortest) only while "car moved" is on, else 30. */
        fun intervalMinutes(context: Context): Long = if (Alerts.isOn(context, "moved")) 15 else 30

        fun schedule(context: Context, intervalMinutes: Long = intervalMinutes(context)) {
            val req = PeriodicWorkRequestBuilder<CarCheckWorker>(intervalMinutes, TimeUnit.MINUTES).setConstraints(online).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(NAME)

        /** One check in [delayMinutes]; a later call with the same [name] moves it rather than adding another. */
        fun checkIn(context: Context, delayMinutes: Long, name: String) {
            val req = OneTimeWorkRequestBuilder<CarCheckWorker>().setInitialDelay(delayMinutes.coerceAtLeast(1), TimeUnit.MINUTES)
                .setConstraints(online).addTag("check-in").build()
            WorkManager.getInstance(context).enqueueUniqueWork("check-$name", ExistingWorkPolicy.REPLACE, req)
        }

        /**
         * You've just left the car: look once in 2 min (doors, lock, lights, left on) and again after the parked
         * wait, when "still unlocked / window open" alerts are due.
         */
        fun leftCar(context: Context) {
            checkIn(context, 2, "left-car-soon")
            checkIn(context, Alerts.parkedDelayMin(context) + 1L, "left-car-later")
        }

        /** Timed follow-ups a reading makes due: the end of a charge, and a car that's on while the phone isn't in it. */
        private fun followUps(context: Context, v: VehicleStatus) {
            val minutesLeft = v.chargeMinutesRemaining?.value
            if (v.charging?.value == true && minutesLeft != null && minutesLeft > 0) checkIn(context, minutesLeft + 2L, "charge-done")
            if (v.ignitionOn?.value == true && !dev.irshadm.volta.car.InCarRefresh.active) checkIn(context, 10, "driving")
        }

        /** Fires each alert once; it re-arms when the condition clears. Also used right after a manual refresh. */
        fun evaluate(context: Context, v: VehicleStatus) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            fun once(key: String, isActive: Boolean, title: () -> String, text: () -> String, level: Notifications.Level = Notifications.Level.Normal) {
                val active = isActive && Alerts.isOn(context, key)
                val fired = prefs.getBoolean(key, false)
                if (active && !fired) {
                    Notifications.alert(context, title(), text(), level)
                    prefs.edit().putBoolean(key, true).apply()
                }
                if (!active && fired) prefs.edit().putBoolean(key, false).apply()
            }
            val soc = v.soc?.value?.socPercent
            val wasCharging = prefs.getBoolean("was_charging", false)
            val charging = v.charging?.value == true
            val plugged = v.pluggedIn?.value == true
            if (wasCharging && !charging && Alerts.isOn(context, "charging")) {
                if (plugged && soc != null && soc < 95) {
                    Notifications.alert(context, "Charging stopped", "Stopped at ${soc.roundToInt()} % while still plugged in.")
                } else if (soc != null) {
                    Notifications.alert(context, "Charging finished", "Battery at ${soc.roundToInt()} %.")
                }
            }
            prefs.edit().putBoolean("was_charging", charging).apply()

            val b = v.body?.value
            once("open", b?.anyOpen == true, { "Something is open" }, {
                b!!.openNames.joinToString(", ").replaceFirstChar { it.uppercase() } + " open."
            }, Notifications.Level.Urgent)
            val parked = v.ignitionOn?.value != true
            val now = System.currentTimeMillis()
            val delay = Alerts.parkedDelayMin(context) * 60_000L
            val gps = v.gps?.value

            /**
             * True once [condition] has held for the wait time. It's timed from [since] when the car knows
             * (e.g. when it was unlocked), otherwise from the first reading that saw it; a car that has
             * moved more than 100 m in between starts the clock again.
             */
            fun heldFor(key: String, condition: Boolean, since: Long? = null): Boolean {
                if (!condition) {
                    prefs.edit().remove("since_$key").remove("at_$key").apply()
                    return false
                }
                val here = gps?.let { "${it.latitude},${it.longitude}" }
                val there = prefs.getString("at_$key", null)
                val moved = here != null && there != null && there.split(",").let { (a, b) ->
                    dev.irshadm.volta.core.planner.Geo.distanceM(
                        dev.irshadm.volta.core.planner.LatLng(a.toDouble(), b.toDouble()),
                        dev.irshadm.volta.core.planner.LatLng(gps!!.latitude, gps.longitude),
                    ) > 100
                }
                if (!prefs.contains("since_$key") || moved) prefs.edit().putLong("since_$key", since ?: now).putString("at_$key", here).apply()
                return now - prefs.getLong("since_$key", now) >= delay
            }
            fun mins(key: String) = ((now - prefs.getLong("since_$key", now)) / 60_000).toInt()
            val unlocked = parked && v.locked?.value == false
            once(
                "unlocked",
                heldFor("unlocked", unlocked, v.lockChangedAtMillis?.takeIf { unlocked }),
                { "Car is unlocked" },
                { "Parked and unlocked for ${mins("unlocked")} min. Lock it from the app." },
                Notifications.Level.Urgent,
            )
            val w = v.windows?.value
            once(
                "windows",
                heldFor("windows", parked && w?.anyOpen == true),
                { "Window open" },
                { w!!.openNames.joinToString(", ").replaceFirstChar { it.uppercase() } + " open, parked for ${mins("windows")} min." },
                Notifications.Level.Urgent,
            )
            // On but standing still (not at a signal: the same spot across readings, for the whole wait).
            val leftOn = v.ignitionOn?.value == true && (v.speedKmh?.value ?: 0.0) <= 2
            once(
                "lefton",
                heldFor("lefton", leftOn),
                { "Car left switched on" },
                { "Your car has been on and not moving for ${mins("lefton")} min. Switch it off to save the battery." },
                Notifications.Level.Urgent,
            )
            once("lights", parked && v.lights?.value?.dipped == true, { "Headlights left on" }, { "The headlights are on while the car is parked." })
            val tyres = v.tyrePsi?.value
            once("tyre", tyres != null && tyres.min < TyrePressures.LOW_PSI, { "Low tyre pressure" }, { "Lowest tyre ${tyres?.min} psi." })
            val aux = v.auxBatteryVolts?.value
            once("aux", aux != null && aux < 11.8, { "12 V battery is weak" }, { "$aux V — drive the car or check the 12 V battery." })
            once("low", soc != null && soc < 15 && !plugged, { "Battery low" }, { "${soc?.roundToInt()} % while parked." }, Notifications.Level.Urgent)
            runCatching { followUps(context, v) }
        }
    }
}
