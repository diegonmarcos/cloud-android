package com.diegonmarcos.mediacenter.feature_node.domain.model

import androidx.compose.runtime.Stable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.diegonmarcos.mediacenter.feature_node.domain.util.MediaOrder
import com.diegonmarcos.mediacenter.feature_node.domain.util.OrderType

/**
 * Timeline settings
 *
 * This entity contains all settings of the app that
 * affects the media display.
 */
@Stable
@Entity(tableName = "timeline_settings")
data class TimelineSettings(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val groupTimelineByMonth: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val groupTimelineByYear: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val groupTimelineInAlbums: Boolean = false,
    @ColumnInfo(defaultValue = "{\"orderType\":{\"type\":\"com.diegonmarcos.mediacenter.feature_node.domain.util.OrderType.Descending\"},\"orderType_date\":{\"type\":\"com.diegonmarcos.mediacenter.feature_node.domain.util.OrderType.Descending\"}}")
    val timelineMediaOrder: MediaOrder = MediaOrder.Date(OrderType.Descending),
    @ColumnInfo(defaultValue = "{\"orderType\":{\"type\":\"com.diegonmarcos.mediacenter.feature_node.domain.util.OrderType.Descending\"},\"orderType_date\":{\"type\":\"com.diegonmarcos.mediacenter.feature_node.domain.util.OrderType.Descending\"}}")
    val albumMediaOrder: MediaOrder = MediaOrder.Date(OrderType.Descending),
)
