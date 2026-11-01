package dev.irshadm.volta.core.data.di

import android.content.Context
import android.content.pm.PackageManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.irshadm.volta.core.data.account.IsmartAccountRepository
import dev.irshadm.volta.core.data.maps.SecretMapsKeyRepository
import dev.irshadm.volta.core.data.security.KeystoreSecretStore
import dev.irshadm.volta.core.data.security.SecretStore
import dev.irshadm.volta.core.data.setup.DataStoreSetupRepository
import dev.irshadm.volta.core.domain.AccountRepository
import dev.irshadm.volta.core.domain.MapsKeyRepository
import dev.irshadm.volta.core.domain.SetupRepository
import dev.irshadm.volta.core.ismart.MgIsmartClient
import dev.irshadm.volta.core.maps.AppIdentity
import dev.irshadm.volta.core.maps.GoogleApi
import dev.irshadm.volta.core.maps.GoogleMapsKeyChecker
import dev.irshadm.volta.core.maps.MapsKeyChecker
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

private val Context.setupStore: DataStore<Preferences> by preferencesDataStore(name = "setup")

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun secretStore(@ApplicationContext context: Context): SecretStore = KeystoreSecretStore(context)

    @Provides
    @Singleton
    fun accountRepository(secrets: SecretStore, http: OkHttpClient): AccountRepository =
        IsmartAccountRepository(secrets) { credentials, sessions -> MgIsmartClient(http, credentials, sessions) }

    @Provides
    @Singleton
    fun mapsKeyRepository(secrets: SecretStore): MapsKeyRepository = SecretMapsKeyRepository(secrets)

    @Provides
    @Singleton
    fun setupRepository(@ApplicationContext context: Context): SetupRepository = DataStoreSetupRepository(context.setupStore)

    /** Package and signing SHA-1, sent with every Google request so keys restricted to this app accept it. */
    @Provides
    @Singleton
    fun appIdentity(@ApplicationContext context: Context): AppIdentity {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val cert = info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray() ?: ByteArray(0)
        val sha1 = MessageDigest.getInstance("SHA-1").digest(cert).joinToString("") { "%02X".format(Locale.ROOT, it) }
        return AppIdentity(context.packageName, sha1)
    }

    @Provides
    @Singleton
    fun googleApi(http: OkHttpClient, identity: AppIdentity, keys: MapsKeyRepository): GoogleApi = GoogleApi(http, identity, keys)

    @Provides
    @Singleton
    fun mapsKeyChecker(api: GoogleApi): MapsKeyChecker = GoogleMapsKeyChecker(api)
}
