/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.diegonmarcos.mediacenter.cloud.genericwebdav.di

import android.content.Context
import com.diegonmarcos.mediacenter.cloud.core.MediaCapabilityProvider
import com.diegonmarcos.mediacenter.cloud.core.ProviderInstanceFactory
import com.diegonmarcos.mediacenter.cloud.core.ProviderType
import com.diegonmarcos.mediacenter.cloud.data.dao.CloudMediaDao
import com.diegonmarcos.mediacenter.cloud.genericwebdav.GenericWebDavDialect
import com.diegonmarcos.mediacenter.cloud.webdav.WebDavMediaProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object WebDavModule {

    @Provides
    @Singleton
    @IntoSet
    fun provideWebDavProviderFactory(
        @ApplicationContext context: Context,
        cloudMediaDao: CloudMediaDao
    ): ProviderInstanceFactory = object : ProviderInstanceFactory {
        override val providerType = ProviderType.WEBDAV
        override fun create(): MediaCapabilityProvider =
            WebDavMediaProvider(context, cloudMediaDao, GenericWebDavDialect())
    }
}
