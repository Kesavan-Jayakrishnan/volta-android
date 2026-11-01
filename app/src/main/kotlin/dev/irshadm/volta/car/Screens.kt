package dev.irshadm.volta.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.CarLocation
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Place
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.irshadm.volta.car.CarUi.perform
import dev.irshadm.volta.data.maps.ChargeVerdict
import dev.irshadm.volta.core.planner.LatLng
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Collects [source] while the screen is started; each distinct value is kept and the template re-requested. */
private fun <T> Screen.follow(source: Flow<T>, onEach: (T) -> Unit) {
    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.STARTED) {
            source.distinctUntilChanged().collect { onEach(it); invalidate() }
        }
    }
}

/**
 * The root: no trip (battery, range, range watch), the live trip (layout A: next stop, arrival %, meal and charge,
 * confidence, with the battery ring) or "at a stop" (unplug at X %, minutes left, next leg). One screen, one
 * template type, so switching between them is a refresh and never costs a step of the host's quota.
 */
@Suppress("DEPRECATION")
class RootScreen(carContext: CarContext, private val deps: CarDeps) : Screen(carContext) {
    private var ui: PaneUi? = null

    init {
        follow(deps.state.map { s -> deps.facts(s)?.let { CarModels.root(it) } ?: CarModels.home(deps.home(s)) }) { ui = it }
        // Fresh car data when the screen comes up with no trip running (a trip's own ticks refresh it otherwise).
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (deps.trips.active.value == null) lifecycleScope.launch { runCatching { deps.ismart.refresh() } }
            }
        })
    }

    override fun onGetTemplate(): Template {
        val u = ui ?: return CarUi.loadingPane("Volta", root = true)
        return CarUi.pane(carContext, u, root = true) { perform(deps, it) }
    }
}

/** Every stop and the destination, one row each; a row opens that stop. */
class StopsScreen(carContext: CarContext, private val deps: CarDeps) : Screen(carContext) {
    private var rows: List<StopRowUi>? = null
    private var gone = false

    init {
        follow(deps.state.map { s -> deps.facts(s)?.let { CarModels.stopRows(it) } }) { r ->
            if (r == null) gone = true else rows = r
        }
    }

    override fun onGetTemplate(): Template {
        if (gone) screenManager.popToRoot()
        val b = ListTemplate.Builder().setTitle("Stops").setHeaderAction(Action.BACK)
        val all = rows ?: return b.setLoading(true).build()
        val limit = CarUi.limit(carContext, ConstraintManager.CONTENT_LIMIT_TYPE_LIST, 6)
        // Too many for the screen: drop the stops already behind you first.
        val shown = if (all.size <= limit) all else (all.filter { it.badge != Badge.Done }).let { if (it.size <= limit) it else it.take(limit - 1) + all.last() }
        val list = ItemList.Builder()
        shown.forEach { r ->
            list.addItem(CarUi.stopRow(carContext, r, r.chargerId?.let { id -> { screenManager.push(StopDetailScreen(carContext, deps, id)) } }))
        }
        return b.setSingleList(list.build()).build()
    }
}

/** One stop: reach time and %, charge plan, confidence reasons and the backup charger, each a tap from navigation. */
class StopDetailScreen(carContext: CarContext, private val deps: CarDeps, private val chargerId: String) : Screen(carContext) {
    private var ui: PaneUi? = null
    private var gone = false

    init {
        follow(deps.state.map { s -> deps.facts(s)?.let { CarModels.stopDetail(it, chargerId) } }) { u ->
            if (u == null) gone = true else ui = u
        }
    }

    override fun onGetTemplate(): Template {
        if (gone) screenManager.popToRoot()
        val u = ui ?: return CarUi.loadingPane("Stop", root = false)
        return CarUi.pane(carContext, u, root = false) { perform(deps, it) }
    }
}

/** "Charger problem" at a stop: had to wait, or broken (dropped from the plan and the rest re-planned). */
class ChargerProblemScreen(carContext: CarContext, private val deps: CarDeps, private val chargerId: String) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        fun report(verdict: ChargeVerdict, toast: String) {
            deps.scope.launch { runCatching { deps.trips.reportChargerProblem(chargerId, verdict) } }
            CarToast.makeText(carContext, toast, CarToast.LENGTH_LONG).show()
            screenManager.pop()
        }
        val list = ItemList.Builder()
            .addItem(Row.Builder().setTitle("Had to wait").addText("All plugs were busy when you arrived")
                .setOnClickListener { report(ChargeVerdict.Waited, "Noted. Volta will plan more time here next time.") }.build())
            .addItem(Row.Builder().setTitle("Charger broken").addText("I'll drop it and re-plan the rest from here")
                .setOnClickListener { report(ChargeVerdict.Broken, "Noted. Planning the rest without it…") }.build())
            .build()
        return ListTemplate.Builder().setTitle("What went wrong?").setHeaderAction(Action.BACK).setSingleList(list).build()
    }
}

/**
 * DC chargers around the car on the host's map, nearest first, each with your own history there. Tapping one
 * starts navigation. The header's refresh button looks again.
 */
class ChargersScreen(carContext: CarContext, private val deps: CarDeps) : Screen(carContext) {
    private var loading = true
    private var here: LatLng? = null
    private var rows: List<ChargerRowUi> = emptyList()
    private var message: String? = null

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { if (rows.isEmpty()) load() }
        })
    }

    private fun load() {
        loading = true
        invalidate()
        lifecycleScope.launch {
            val fix = deps.location.current()
            if (fix == null) {
                message = if (deps.location.hasPermission()) "Couldn't get your location" else "Allow location for Volta on your phone"
            } else {
                here = fix.position
                val limit = CarUi.limit(carContext, ConstraintManager.CONTENT_LIMIT_TYPE_PLACE_LIST, 6)
                runCatching { deps.maps.nearbyFastChargers(fix.position, RADIUS_M, limit) }
                    .onSuccess { found ->
                        rows = CarModels.chargerRows(fix.position, found, deps.feedback::history)
                        message = if (rows.isEmpty()) "No fast chargers within ${(RADIUS_M / 1000).toInt()} km" else null
                    }
                    .onFailure { message = "Couldn't load chargers. Check the phone's connection." }
            }
            loading = false
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val b = PlaceListMapTemplate.Builder()
            .setTitle("Fast chargers nearby")
            .setHeaderAction(Action.BACK)
            .setCurrentLocationEnabled(deps.location.hasPermission())
            .setOnContentRefreshListener { load() }
        if (loading) return b.setLoading(true).build()
        here?.let { b.setAnchor(Place.Builder(CarLocation.create(it.lat, it.lng)).build()) }
        val list = ItemList.Builder()
        if (rows.isEmpty()) list.setNoItemsMessage(message ?: "No fast chargers nearby")
        rows.forEachIndexed { i, r -> list.addItem(CarUi.placeRow(r, ('A' + i).toString(), best = i == 0) { Navigate.to(carContext, r.name, r.position) }) }
        return b.setItemList(list.build()).build()
    }

    private companion object { const val RADIUS_M = 25_000.0 }
}
