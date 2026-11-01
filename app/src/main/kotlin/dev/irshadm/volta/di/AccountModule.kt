package dev.irshadm.volta.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.irshadm.volta.core.domain.AccountRepository
import dev.irshadm.volta.data.ismart.IsmartAccount

@Module
@InstallIn(SingletonComponent::class)
abstract class AccountModule {
    @Binds
    abstract fun accountRepository(impl: IsmartAccount): AccountRepository
}
