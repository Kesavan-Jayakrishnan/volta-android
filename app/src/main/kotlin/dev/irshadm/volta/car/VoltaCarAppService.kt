package dev.irshadm.volta.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.irshadm.volta.service.CarCheckWorker
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Volta on Android Auto. Read and guide only: the live trip, its stops and nearby chargers,
 * with navigation handed to Google Maps. No car commands: lock, unlock, windows, AC and Find stay on the phone.
 */
@AndroidEntryPoint
class VoltaCarAppService : CarAppService() {
    @Inject lateinit var deps: CarDeps

    /** Debug builds (the sideloaded APK, the Desktop Head Unit) accept any host; release accepts Google's hosts only. */
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        else HostValidator.Builder(applicationContext).addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build()

    override fun onCreateSession(): Session = VoltaSession(deps)
}

class VoltaSession(private val deps: CarDeps) : Session() {
    init {
        // The phone is in the car for as long as this session lives: keep the car data fresh (InCarRefresh), and
        // treat the session's end as "left the car" so the parked alerts get their checks.
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) { deps.inCar.hold("session") }
            override fun onDestroy(owner: LifecycleOwner) { deps.inCar.release("session"); CarCheckWorker.leftCar(deps.context) }
        })
    }

    /** The root screen follows the trip by itself: no trip, driving, at a stop. */
    override fun onCreateScreen(intent: Intent): Screen = RootScreen(carContext, deps)

    /** "Show plan" from a heads-up alert (a CarPendingIntent) brings the trip back to the front. */
    override fun onNewIntent(intent: Intent) {
        carContext.getCarService(androidx.car.app.ScreenManager::class.java).popToRoot()
    }
}
