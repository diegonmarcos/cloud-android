/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.diegonmarcos.mediacenter.feature_node.data.data_source.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration from version 40 to 41: repoint the `timeline_settings` order-type
 * discriminator at the renamed package.
 *
 * The 2026-08-30 rename off `com.dot.gallery` rewrote every occurrence of the old
 * package name, including the two `@ColumnInfo(defaultValue = ...)` literals on
 * [com.diegonmarcos.mediacenter.feature_node.domain.model.TimelineSettings]. Those
 * literals are part of the table's DDL, so the schema changed — but the database
 * version stayed at 40. Every device carrying a pre-rename database therefore opened
 * a version-40 file whose stored identity hash (07ab1d23...) no longer matched the one
 * the compiled entities produce (c26ac2e1...), and Room threw
 * `IllegalStateException: Room cannot verify the data integrity`. That throw happens on
 * the unguarded `db-warmup` thread in
 * [com.diegonmarcos.mediacenter.core.encryption.EncryptedDatabaseFactory], i.e. during
 * Application.onCreate and before any UI — which is why the app read as "does not open".
 *
 * The rename also silently invalidated the DATA: `OrderType` is a `@Serializable` sealed
 * class with no `@SerialName`, so its polymorphic discriminator IS the fully-qualified
 * class name. Rows written before the rename still carry the old FQN and would fail to
 * deserialize even once the schema matched, so they are rewritten here before the table
 * is rebuilt.
 *
 * The created table mirrors exactly what Room generates for `TimelineSettings` so schema
 * validation passes.
 */
val MIGRATION_40_41 = object : Migration(40, 41) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Data first: rewrite the polymorphic discriminator persisted in existing rows.
        db.execSQL(
            """
            UPDATE `timeline_settings` SET
                `timelineMediaOrder` = replace(`timelineMediaOrder`, '$OLD_PACKAGE', '$NEW_PACKAGE'),
                `albumMediaOrder` = replace(`albumMediaOrder`, '$OLD_PACKAGE', '$NEW_PACKAGE')
            """.trimIndent()
        )
        // Then the DDL: SQLite cannot alter a column's DEFAULT, so rebuild the table.
        db.execSQL("CREATE TABLE IF NOT EXISTS `timeline_settings_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `groupTimelineByMonth` INTEGER NOT NULL DEFAULT 0, `groupTimelineByYear` INTEGER NOT NULL DEFAULT 0, `groupTimelineInAlbums` INTEGER NOT NULL DEFAULT 0, `timelineMediaOrder` TEXT NOT NULL DEFAULT '{\"orderType\":{\"type\":\"com.diegonmarcos.mediacenter.feature_node.domain.util.OrderType.Descending\"},\"orderType_date\":{\"type\":\"com.diegonmarcos.mediacenter.feature_node.domain.util.OrderType.Descending\"}}', `albumMediaOrder` TEXT NOT NULL DEFAULT '{\"orderType\":{\"type\":\"com.diegonmarcos.mediacenter.feature_node.domain.util.OrderType.Descending\"},\"orderType_date\":{\"type\":\"com.diegonmarcos.mediacenter.feature_node.domain.util.OrderType.Descending\"}}')")
        db.execSQL(
            "INSERT INTO `timeline_settings_new` (`id`, `groupTimelineByMonth`, `groupTimelineByYear`, `groupTimelineInAlbums`, `timelineMediaOrder`, `albumMediaOrder`) " +
                "SELECT `id`, `groupTimelineByMonth`, `groupTimelineByYear`, `groupTimelineInAlbums`, `timelineMediaOrder`, `albumMediaOrder` FROM `timeline_settings`"
        )
        db.execSQL("DROP TABLE `timeline_settings`")
        db.execSQL("ALTER TABLE `timeline_settings_new` RENAME TO `timeline_settings`")
    }
}

/** The package the order-type discriminator used before the 2026-08-30 rename. */
private const val OLD_PACKAGE = "com.dot.gallery.feature_node.domain.util"

/** The package it uses now. */
private const val NEW_PACKAGE = "com.diegonmarcos.mediacenter.feature_node.domain.util"
