package com.diegonmarcos.mediacenter.feature_node.domain.model

import androidx.room.Entity

@Entity(tableName = "media_version", primaryKeys = ["version"])
data class MediaVersion(
    val version: String
)
