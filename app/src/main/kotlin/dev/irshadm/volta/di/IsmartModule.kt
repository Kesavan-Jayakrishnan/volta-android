package dev.irshadm.volta.di

import android.content.Context
import dev.irshadm.volta.data.ismart.SecureStore
import dev.irshadm.volta.core.ismart.MgIsmartClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object IsmartModule {

    @Provides
    @Singleton
    fun secureStore(@ApplicationContext context: Context) = SecureStore(context)

    @Provides
    @Singleton
    fun ismartClient(http: OkHttpClient, secure: SecureStore): MgIsmartClient = MgIsmartClient(
        http = http.newBuilder().readTimeout(40, TimeUnit.SECONDS).build(),
        credentials = { secure.credentials },
        sessions = secure,
    )
}
