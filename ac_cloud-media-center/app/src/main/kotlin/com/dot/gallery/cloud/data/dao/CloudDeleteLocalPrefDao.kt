/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.cloud.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dot.gallery.cloud.data.entity.CloudDeleteLocalPrefEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CloudDeleteLocalPrefDao {

    @Query("SELECT * FROM cloud_delete_local_pref")
    fun getAll(): Flow<List<CloudDeleteLocalPrefEntity>>

    /** Album ids for which local copies should be removed once fully backed up. */
    @Query("SELECT albumId FROM cloud_delete_local_pref WHERE enabled = 1")
    suspend fun getEnabledAlbumIds(): List<Long>

    @Query("SELECT * FROM cloud_delete_local_pref WHERE albumId = :albumId")
    suspend fun get(albumId: Long): CloudDeleteLocalPrefEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CloudDeleteLocalPrefEntity)

    @Query("DELETE FROM cloud_delete_local_pref WHERE albumId = :albumId")
    suspend fun delete(albumId: Long)
}
