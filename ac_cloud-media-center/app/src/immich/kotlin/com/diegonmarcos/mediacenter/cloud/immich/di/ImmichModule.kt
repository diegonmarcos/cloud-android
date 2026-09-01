/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.diegonmarcos.mediacenter.cloud.immich.di

import android.content.Context
import com.diegonmarcos.mediacenter.cloud.core.MediaCapabilityProvider
import com.diegonmarcos.mediacenter.cloud.core.ProviderInstanceFactory
import com.diegonmarcos.mediacenter.cloud.core.ProviderType
import com.diegonmarcos.mediacenter.cloud.data.dao.CloudMediaDao
import com.diegonmarcos.mediacenter.cloud.immich.ImmichProvider
import com.diegonmarcos.mediacenter.cloud.immich.data.api.ImmichAuthInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ImmichModule {

    // One factory per provider type. Each call to create() mints a fully isolated
    // ImmichProvider with its OWN auth interceptor, so multiple Immich accounts never
    // share credentials or base URL.
    @Provides
    @Singleton
    @IntoSet
    fun provideImmichProviderFactory(
        @ApplicationContext context: Context,
        cloudMediaDao: CloudMediaDao
    ): ProviderInstanceFactory = object : ProviderInstanceFactory {
        override val providerType = ProviderType.IMMICH
        override fun create(): MediaCapabilityProvider =
            ImmichProvider(context, ImmichAuthInterceptor(), cloudMediaDao)
    }
}
