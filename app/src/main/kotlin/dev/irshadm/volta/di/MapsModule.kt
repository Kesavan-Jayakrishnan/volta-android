package dev.irshadm.volta.di

import android.content.Context
import android.content.pm.PackageManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.irshadm.volta.core.domain.MapsKeyRepository
import dev.irshadm.volta.data.maps.AppIdentity
import dev.irshadm.volta.data.maps.GoogleMapsClient
import dev.irshadm.volta.data.maps.PlanRepository
import okhttp3.OkHttpClient
import java.security.MessageDigest
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MapsModule {

    /** Package + signing SHA-1, sent with every request so the Android-restricted key accepts it. */
    @Provides
    @Singleton
    fun appIdentity(@ApplicationContext context: Context): AppIdentity {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signing = info.signingInfo
        fun sha1(cert: ByteArray) = MessageDigest.getInstance("SHA-1").digest(cert).joinToString("") { "%02X".format(java.util.Locale.ROOT, it) }
        val current = signing?.apkContentsSigners.orEmpty().map { sha1(it.toByteArray()) }
        // Newest first: the history runs oldest → newest.
        val history = signing?.signingCertificateHistory.orEmpty().map { sha1(it.toByteArray()) }.reversed()
        val all = (current + history).distinct()
        return AppIdentity(context.packageName, all.firstOrNull() ?: sha1(ByteArray(0)), all.drop(1))
    }

    @Provides
    @Singleton
    fun mapsClient(http: OkHttpClient, identity: AppIdentity, keys: MapsKeyRepository): GoogleMapsClient = GoogleMapsClient(http, keys, identity)

    @Provides
    @Singleton
    fun planRepository(client: GoogleMapsClient, @ApplicationContext context: Context): PlanRepository = PlanRepository(client, context.cacheDir)
}
