package dev.irshadm.volta

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class VoltaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        dev.irshadm.volta.service.Notifications.createChannels(this)
        dev.irshadm.volta.service.CarCheckWorker.schedule(this)
        // Smart pre-cool: watch Wi-Fi while the process lives; (re)create zones and the routine prefetch.
        val deps = dagger.hilt.android.EntryPointAccessors.fromApplication(this, dev.irshadm.volta.service.smart.SmartDeps::class.java)
        // Never let smart pre-cool setup take the app down.
        runCatching { dev.irshadm.volta.service.smart.SmartPrecool.watchWifi(this, deps.smartSettings()) }
        androidx.work.WorkManager.getInstance(this).enqueue(androidx.work.OneTimeWorkRequestBuilder<dev.irshadm.volta.service.smart.RearmWorker>().build())
        val app = dagger.hilt.android.EntryPointAccessors.fromApplication(this, AppDeps::class.java)
        watchCarConnection(app.inCar())
    }

    /**
     * Android Auto connected (projection) means the phone is in the car: run the in-car cadence even when Volta
     * isn't open on the head unit. Its end is a "left the car" signal. Only seen while the process is alive; the
     * car session and the Bluetooth receiver cover the rest.
     */
    private fun watchCarConnection(inCar: dev.irshadm.volta.car.InCarRefresh) = runCatching {
        var projected = false
        androidx.car.app.connection.CarConnection(this).type.observeForever { type ->
            val now = type == androidx.car.app.connection.CarConnection.CONNECTION_TYPE_PROJECTION
            if (now) inCar.hold("projection") else inCar.release("projection")
            if (projected && !now) dev.irshadm.volta.service.CarCheckWorker.leftCar(this)
            projected = now
        }
    }

    @dagger.hilt.EntryPoint
    @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
    interface AppDeps {
        fun inCar(): dev.irshadm.volta.car.InCarRefresh
    }
}
