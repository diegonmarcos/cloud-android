/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.diegonmarcos.mediacenter.cloud.di

import com.diegonmarcos.mediacenter.cloud.core.ProviderInstanceFactory
import com.diegonmarcos.mediacenter.cloud.core.ProviderRegistry
import com.diegonmarcos.mediacenter.cloud.data.dao.CloudAlbumSyncDao
import com.diegonmarcos.mediacenter.cloud.data.dao.CloudDeleteLocalPrefDao
import com.diegonmarcos.mediacenter.cloud.data.dao.CloudMediaDao
import com.diegonmarcos.mediacenter.cloud.data.dao.CloudOfflinePinDao
import com.diegonmarcos.mediacenter.cloud.data.dao.CloudServerConfigDao
import com.diegonmarcos.mediacenter.cloud.data.dao.CloudUploadPrefDao
import com.diegonmarcos.mediacenter.cloud.data.dao.DetectedFaceDao
import com.diegonmarcos.mediacenter.cloud.data.dao.PersonDao
import com.diegonmarcos.mediacenter.cloud.data.dao.SyncStateDao
import com.diegonmarcos.mediacenter.cloud.data.repository.CloudRepository
import com.diegonmarcos.mediacenter.cloud.data.repository.CloudRepositoryImpl
import com.diegonmarcos.mediacenter.cloud.network.ServerUrlResolver
import com.diegonmarcos.mediacenter.feature_node.data.data_source.InternalDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CloudModule {

    @Multibinds
    abstract fun bindProviderFactorySet(): Set<ProviderInstanceFactory>

    companion object {
        @Provides
        @Singleton
        fun provideCloudMediaDao(database: InternalDatabase): CloudMediaDao =
            database.getCloudMediaDao()

        @Provides
        @Singleton
        fun provideCloudServerConfigDao(database: InternalDatabase): CloudServerConfigDao =
            database.getCloudServerConfigDao()

        @Provides
        @Singleton
        fun providePersonDao(database: InternalDatabase): PersonDao =
            database.getPersonDao()

        @Provides
        @Singleton
        fun provideDetectedFaceDao(database: InternalDatabase): DetectedFaceDao =
            database.getDetectedFaceDao()

        @Provides
        @Singleton
        fun provideSyncStateDao(database: InternalDatabase): SyncStateDao =
            database.getSyncStateDao()

        @Provides
        @Singleton
        fun provideCloudAlbumSyncDao(database: InternalDatabase): CloudAlbumSyncDao =
            database.getCloudAlbumSyncDao()

        @Provides
        @Singleton
        fun provideCloudUploadPrefDao(database: InternalDatabase): CloudUploadPrefDao =
            database.getCloudUploadPrefDao()

        @Provides
        @Singleton
        fun provideCloudDeleteLocalPrefDao(database: InternalDatabase): CloudDeleteLocalPrefDao =
            database.getCloudDeleteLocalPrefDao()

        @Provides
        @Singleton
        fun provideCloudOfflinePinDao(database: InternalDatabase): CloudOfflinePinDao =
            database.getCloudOfflinePinDao()

        @Provides
        @Singleton
        fun provideCloudRepository(
            registry: ProviderRegistry,
            cloudMediaDao: CloudMediaDao,
            urlResolver: ServerUrlResolver
        ): CloudRepository = CloudRepositoryImpl(registry, cloudMediaDao, urlResolver)
    }
}
