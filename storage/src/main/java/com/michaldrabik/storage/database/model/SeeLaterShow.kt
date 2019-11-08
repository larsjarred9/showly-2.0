package com.michaldrabik.storage.database.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
  tableName = "shows_see_later",
  foreignKeys = [ForeignKey(
    entity = Show::class,
    parentColumns = arrayOf("id_trakt"),
    childColumns = arrayOf("id_trakt"),
    onDelete = ForeignKey.CASCADE
  )]
)
data class SeeLaterShow(
  @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") var id: Long = 0,
  @ColumnInfo(name = "id_trakt", defaultValue = "-1") var idTrakt: Long,
  @ColumnInfo(name = "created_at", defaultValue = "-1") var createdAt: Long,
  @ColumnInfo(name = "updated_at", defaultValue = "-1") var updatedAt: Long
) {

  companion object {
    fun fromTraktId(traktId: Long, nowUtcMillis: Long): SeeLaterShow {
      return SeeLaterShow(idTrakt = traktId, createdAt = nowUtcMillis, updatedAt = nowUtcMillis)
    }
  }
}