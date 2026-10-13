package com.photon.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SessionState::class,
        SessionMember::class,
        PauseWindow::class,
        PendingPhoto::class,
        ReceivedAsset::class,
        KnownHash::class,
        ReceivedPhoto::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class PhotonDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao
    abstract fun pending(): PendingPhotoDao
    abstract fun receivedAssets(): ReceivedAssetDao
    abstract fun hashes(): HashDao
    abstract fun received(): ReceivedPhotoDao

    companion object {
        fun create(context: Context, inMemory: Boolean = false): PhotonDatabase {
            val builder = if (inMemory) {
                Room.inMemoryDatabaseBuilder(context, PhotonDatabase::class.java)
            } else {
                Room.databaseBuilder(context, PhotonDatabase::class.java, "photon.db")
            }
            return builder.build()
        }
    }
}
