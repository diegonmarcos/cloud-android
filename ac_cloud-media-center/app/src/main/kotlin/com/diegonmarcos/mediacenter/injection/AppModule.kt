/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.diegonmarcos.mediacenter.injection

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.location.Geocoder
import android.os.Build
import androidx.room.Room
import androidx.work.WorkManager
import com.diegonmarcos.mediacenter.core.DefaultEventHandler
import com.diegonmarcos.mediacenter.core.EditBackupManager
import com.diegonmarcos.mediacenter.core.encryption.EncryptedDatabaseFactory
import com.diegonmarcos.mediacenter.core.metrics.StartupTracer
import com.diegonmarcos.mediacenter.core.metadata.AndroidMetadataSanitizer
import com.diegonmarcos.mediacenter.core.metadata.MetadataSanitizer
import com.diegonmarcos.mediacenter.core.sandbox.IsolatedImageDecoder
import com.diegonmarcos.mediacenter.core.sandbox.IsolatedMetadataParser
import com.diegonmarcos.mediacenter.core.sandbox.PrivateFolderRepository
import com.diegonmarcos.mediacenter.core.MediaDistributor
import com.diegonmarcos.mediacenter.core.MediaDistributorImpl
import com.diegonmarcos.mediacenter.core.MediaHandler
import com.diegonmarcos.mediacenter.core.MediaHandlerImpl
import com.diegonmarcos.mediacenter.core.MediaSelector
import com.diegonmarcos.mediacenter.core.MediaSelectorImpl
import com.diegonmarcos.mediacenter.feature_node.data.data_source.InternalDatabase
import com.diegonmarcos.mediacenter.feature_node.data.data_source.KeychainHolder
import com.diegonmarcos.mediacenter.feature_node.data.data_source.migration.MIGRATION_12_13
import com.diegonmarcos.mediacenter.feature_node.data.data_source.migration.MIGRATION_33_34
import com.diegonmarcos.mediacenter.feature_node.data.data_source.migration.MIGRATION_35_36
import com.diegonmarcos.mediacenter.feature_node.data.data_source.migration.MIGRATION_36_37
import com.diegonmarcos.mediacenter.feature_node.data.data_source.migration.MIGRATION_37_38
import com.diegonmarcos.mediacenter.feature_node.data.repository.MediaRepositoryImpl
import com.diegonmarcos.mediacenter.feature_node.domain.repository.MediaRepository
import com.diegonmarcos.mediacenter.feature_node.domain.util.EventHandler
import com.diegonmarcos.mediacenter.core.ml.ModelManager
import com.diegonmarcos.mediacenter.feature_node.presentation.search.SearchHelper
import com.diegonmarcos.mediacenter.feature_node.presentation.search.SearchHelperImpl
import com.diegonmarcos.mediacenter.core.decryption.DecryptManager
import com.diegonmarcos.mediacenter.core.decryption.MediaMetadataSidecarCache
import com.diegonmarcos.mediacenter.core.memory.AdaptiveDecryptConfig
import com.diegonmarcos.mediacenter.core.metrics.MetricsCollector
import com.diegonmarcos.mediacenter.core.memory.ByteArrayPool
import com.diegonmarcos.mediacenter.cloud.core.ProviderRegistry
import com.diegonmarcos.mediacenter.cloud.data.dao.CloudMediaDao
import com.diegonmarcos.mediacenter.cloud.data.repository.CloudRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    fun provideContentResolver(@ApplicationContext context: Context): ContentResolver =
        context.contentResolver

    @Provides
    @Singleton
    fun provideDatabase(app: Application): InternalDatabase = StartupTracer.trace("AppModule.provideDatabase") {
        try {
            EncryptedDatabaseFactory.create(app)
        } catch (_: Exception) {
            // Device doesn't support SQLCipher or hardware-backed keystore —
            // fall back to plaintext database silently.
            StartupTracer.trace("AppModule.provideDatabase.fallbackPlaintext") {
                Room.databaseBuilder(app, InternalDatabase::class.java, InternalDatabase.NAME)
                    .addMigrations(MIGRATION_12_13, MIGRATION_33_34, MIGRATION_35_36, MIGRATION_36_37, MIGRATION_37_38)
                    .fallbackToDestructiveMigrationOnDowngrade(true)
                    .fallbackToDestructiveMigration(false)
                    .build()
            }
        }
    }

    @Provides
    @Singleton
    fun provideKeychainHolder(@ApplicationContext context: Context): KeychainHolder =
        StartupTracer.trace("AppModule.provideKeychainHolder") { KeychainHolder(context) }

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        StartupTracer.trace("AppModule.provideWorkManager") { WorkManager.getInstance(context) }

    @Provides
    @Singleton
    fun provideEventHandler(): EventHandler = DefaultEventHandler()

    @Provides
    @Singleton
    fun provideMediaDistributor(
        @ApplicationContext context: Context,
        workManager: WorkManager,
        repository: MediaRepository,
        cloudRepository: CloudRepository,
        eventHandler: EventHandler,
        database: InternalDatabase
    ): MediaDistributor = StartupTracer.trace("AppModule.provideMediaDistributor") {
        MediaDistributorImpl(context, repository, cloudRepository, eventHandler, workManager, database.getScannedMediaDao())
    }

    @Provides
    @Singleton
    fun provideMediaSelector(): MediaSelector = MediaSelectorImpl()

    @Provides
    @Singleton
    fun provideMediaHandler(
        @ApplicationContext context: Context,
        mediaRepository: MediaRepository,
        workManager: WorkManager,
        providerRegistry: ProviderRegistry,
        cloudMediaDao: CloudMediaDao,
    ): MediaHandler = StartupTracer.trace("AppModule.provideMediaHandler") {
        MediaHandlerImpl(mediaRepository, context, workManager, providerRegistry, cloudMediaDao)
    }

    @Provides
    @Singleton
    fun provideIsolatedMetadataParser(@ApplicationContext context: Context): IsolatedMetadataParser =
        StartupTracer.trace("AppModule.provideIsolatedMetadataParser") { IsolatedMetadataParser(context) }

    @Provides
    @Singleton
    fun provideIsolatedImageDecoder(@ApplicationContext context: Context): IsolatedImageDecoder =
        StartupTracer.trace("AppModule.provideIsolatedImageDecoder") { IsolatedImageDecoder(context) }

    @Provides
    @Singleton
    fun provideMetadataSanitizer(
        sanitizer: AndroidMetadataSanitizer
    ): MetadataSanitizer = sanitizer

    @Provides
    @Singleton
    fun provideMediaRepository(
        @ApplicationContext context: Context,
        workManager: WorkManager,
        database: InternalDatabase,
        keychainHolder: KeychainHolder,
        geocoder: Geocoder?,
        isolatedParser: IsolatedMetadataParser,
        metadataSanitizer: MetadataSanitizer,
    ): MediaRepository = StartupTracer.trace("AppModule.provideMediaRepository") {
        MediaRepositoryImpl(
            context,
            workManager,
            database,
            keychainHolder,
            geocoder,
            isolatedParser,
            metadataSanitizer
        )
    }

    @Provides
    @Singleton
    fun provideModelManager(@ApplicationContext context: Context): ModelManager =
        StartupTracer.trace("AppModule.provideModelManager") { ModelManager(context) }

    @Provides
    @Singleton
    fun provideSearchHelper(modelManager: ModelManager): SearchHelper =
        StartupTracer.trace("AppModule.provideSearchHelper") { SearchHelperImpl(modelManager) }

    @Provides
    @Singleton
    fun provideGeocoder(@ApplicationContext context: Context): Geocoder? =
        StartupTracer.trace("AppModule.provideGeocoder") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && Geocoder.isPresent()) Geocoder(context) else null
        }

    @Provides
    @Singleton
    fun provideDecryptManager(@ApplicationContext context: Context, metrics: MetricsCollector): DecryptManager =
        StartupTracer.trace("AppModule.provideDecryptManager") { DecryptManager(context, metrics) }

    @Provides
    @Singleton
    fun provideMediaMetadataSidecarCache(@ApplicationContext context: Context): MediaMetadataSidecarCache =
        StartupTracer.trace("AppModule.provideMediaMetadataSidecarCache") { MediaMetadataSidecarCache(context) }

    @Provides
    @Singleton
    fun provideAdaptiveDecryptConfig(app: Application): AdaptiveDecryptConfig =
        StartupTracer.trace("AppModule.provideAdaptiveDecryptConfig") { AdaptiveDecryptConfig(app) }

    @Provides
    @Singleton
    fun provideMetricsCollector(): MetricsCollector = MetricsCollector()

    @Provides
    @Singleton
    fun provideByteArrayPool(): ByteArrayPool = ByteArrayPool()

    @Provides
    @Singleton
    fun providePrivateFolderRepository(@ApplicationContext context: Context): PrivateFolderRepository =
        StartupTracer.trace("AppModule.providePrivateFolderRepository") { PrivateFolderRepository(context) }

    @Provides
    @Singleton
    fun provideEditBackupManager(
        @ApplicationContext context: Context,
        database: InternalDatabase
    ): EditBackupManager = StartupTracer.trace("AppModule.provideEditBackupManager") {
        EditBackupManager(context, database.getEditHistoryDao())
    }

}
