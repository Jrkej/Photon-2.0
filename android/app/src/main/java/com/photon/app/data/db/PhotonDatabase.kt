package com.photon.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
    version = 2,
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
            return builder.addMigrations(MIGRATION_1_2).build()
        }

        /**
         * End-to-end encryption: photo tags and original hashes on the upload queue, the tag and epoch on
         * received photos. Unfinished uploads were prepared as plaintext, so they are prepared again, encrypted.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pending_photos ADD COLUMN photo_tag TEXT")
                db.execSQL("ALTER TABLE pending_photos ADD COLUMN original_sha256 TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_photos_photo_tag ON pending_photos (photo_tag)")
                db.execSQL("ALTER TABLE received_photos ADD COLUMN photo_tag TEXT")
                db.execSQL("ALTER TABLE received_photos ADD COLUMN key_epoch INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "UPDATE pending_photos SET state = 'PENDING', sha256 = NULL, copy_path = NULL, upload_id = NULL, " +
                        "upload_url = NULL, chunk_size = 0 WHERE state IN ('HASHED', 'UPLOADING', 'WAITING')",
                )
            }
        }
    }
}
