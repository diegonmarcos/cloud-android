/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.data.data_source

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.dot.gallery.feature_node.domain.model.AlbumGroup
import com.dot.gallery.feature_node.domain.model.AlbumGroupMember
import kotlinx.coroutines.flow.Flow

@Dao
interface AlbumGroupDao {

    // Group CRUD
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroup(group: AlbumGroup): Long

    @Update
    suspend fun updateGroup(group: AlbumGroup)

    @Query("DELETE FROM album_groups WHERE id = :groupId")
    suspend fun deleteGroup(groupId: Long)

    @Query("SELECT * FROM album_groups ORDER BY timestamp DESC")
    fun getAllGroups(): Flow<List<AlbumGroup>>

    @Query("SELECT * FROM album_groups WHERE id = :groupId")
    fun getGroup(groupId: Long): Flow<AlbumGroup?>

    @Query("SELECT * FROM album_groups WHERE id = :groupId")
    suspend fun getGroupAsync(groupId: Long): AlbumGroup?

    // Group members
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addAlbumToGroup(member: AlbumGroupMember)

    @Delete
    suspend fun removeAlbumFromGroup(member: AlbumGroupMember)

    @Query("DELETE FROM album_group_members WHERE groupId = :groupId")
    suspend fun removeAllAlbumsFromGroup(groupId: Long)

    @Query("SELECT albumId FROM album_group_members WHERE groupId = :groupId")
    fun getAlbumIdsInGroup(groupId: Long): Flow<List<Long>>

    @Query("SELECT albumId FROM album_group_members WHERE groupId = :groupId")
    suspend fun getAlbumIdsInGroupAsync(groupId: Long): List<Long>

    @Query("SELECT * FROM album_group_members")
    fun getAllGroupMembers(): Flow<List<AlbumGroupMember>>

    @Query("SELECT groupId FROM album_group_members WHERE albumId = :albumId LIMIT 1")
    suspend fun getGroupIdForAlbum(albumId: Long): Long?

    @Query("SELECT EXISTS(SELECT 1 FROM album_group_members WHERE albumId = :albumId)")
    fun isAlbumInAnyGroup(albumId: Long): Flow<Boolean>
}
