/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.data.data_source.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration from version 35 to 36: scope cloud media identity per cloud account.
 *
 * Previously `cloud_media` was keyed by `(remoteId, providerType)`. With multiple
 * accounts of the SAME provider type (e.g. two Immich servers), two different assets
 * sharing a remote id would collide on this primary key — one silently overwriting the
 * other on insert, and producing duplicate timeline ids. The table is rebuilt with the
 * composite primary key `(remoteId, providerType, serverConfigId)`.
 *
 * The new table definition mirrors exactly what Room generates for
 * [com.dot.gallery.cloud.data.entity.CloudMediaEntity] (the v35 schema plus the extended
 * primary key) so the post-migration schema validation passes. Existing rows already carry
 * a `serverConfigId`, so they copy across unchanged.
 */
val MIGRATION_35_36 = object : Migration(35, 36) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `cloud_media_new` (
                `remoteId` TEXT NOT NULL, `providerType` TEXT NOT NULL, `serverConfigId` INTEGER NOT NULL,
                `label` TEXT NOT NULL, `path` TEXT NOT NULL, `relativePath` TEXT NOT NULL,
                `mimeType` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `takenTimestamp` INTEGER,
                `size` INTEGER NOT NULL, `width` INTEGER NOT NULL, `height` INTEGER NOT NULL,
                `duration` TEXT, `favorite` INTEGER NOT NULL, `trashed` INTEGER NOT NULL,
                `archived` INTEGER NOT NULL DEFAULT 0, `syncState` TEXT NOT NULL,
                `localCopyPath` TEXT NOT NULL DEFAULT '', `contentHash` TEXT,
                `thumbnailUrl` TEXT NOT NULL, `originalUrl` TEXT NOT NULL, `lastSyncedAt` INTEGER NOT NULL,
                `latitude` REAL, `longitude` REAL, `city` TEXT, `state_name` TEXT, `country` TEXT,
                `cameraMake` TEXT, `cameraModel` TEXT, `lensModel` TEXT DEFAULT '',
                `imageDescription` TEXT DEFAULT '', `dateTimeOriginal` TEXT, `exposureTime` TEXT,
                `aperture` TEXT, `iso` INTEGER, `focalLength` REAL, `fileId` TEXT NOT NULL DEFAULT '',
                PRIMARY KEY(`remoteId`, `providerType`, `serverConfigId`)
            )
            """.trimIndent()
        )

        // Copy by EXPLICIT column name — never `SELECT *`. Room's auto-migrations add columns
        // via `ALTER TABLE ADD COLUMN`, which appends them physically at the end, so an
        // incrementally-upgraded DB's physical column order does NOT match this recreated table.
        // A positional `SELECT *` would therefore scramble values (e.g. into `archived`/`trashed`,
        // silently hiding rows from the timeline). Name-based copy is order-independent.
        val columns = listOf(
            "remoteId", "providerType", "serverConfigId", "label", "path", "relativePath",
            "mimeType", "timestamp", "takenTimestamp", "size", "width", "height", "duration",
            "favorite", "trashed", "archived", "syncState", "localCopyPath", "contentHash",
            "thumbnailUrl", "originalUrl", "lastSyncedAt", "latitude", "longitude", "city",
            "state_name", "country", "cameraMake", "cameraModel", "lensModel", "imageDescription",
            "dateTimeOriginal", "exposureTime", "aperture", "iso", "focalLength", "fileId"
        ).joinToString(", ") { "`$it`" }
        db.execSQL(
            "INSERT OR IGNORE INTO `cloud_media_new` ($columns) SELECT $columns FROM `cloud_media`"
        )

        db.execSQL("DROP TABLE `cloud_media`")
        db.execSQL("ALTER TABLE `cloud_media_new` RENAME TO `cloud_media`")

        // Recreate every index exactly as Room expects them.
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_cloud_media_serverConfigId` ON `cloud_media` (`serverConfigId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_cloud_media_syncState` ON `cloud_media` (`syncState`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_cloud_media_timestamp` ON `cloud_media` (`timestamp`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_cloud_media_favorite` ON `cloud_media` (`favorite`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_cloud_media_trashed` ON `cloud_media` (`trashed`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_cloud_media_contentHash` ON `cloud_media` (`contentHash`)")
    }
}

/**
 * Migration from version 36 to 37: purge the cloud media cache to recover from a faulty 35→36
 * migration.
 *
 * An earlier build of [MIGRATION_35_36] copied rows with a positional `SELECT *`, which
 * scrambled column values on databases whose physical column order had drifted from
 * auto-migration `ALTER TABLE ADD COLUMN`s. The corrupted `archived`/`trashed` flags hid cloud
 * media from the unified timeline (which filters `trashed = 0 AND archived = 0`). `cloud_media`
 * is a re-fetchable cache, so the safest recovery is to empty it; the providers repopulate it
 * with correct values on the next sync/prefetch. Fresh installs start at version 37 and never
 * run this migration, so their (already correct) data is untouched.
 */
val MIGRATION_36_37 = object : Migration(36, 37) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DELETE FROM `cloud_media`")
    }
}
