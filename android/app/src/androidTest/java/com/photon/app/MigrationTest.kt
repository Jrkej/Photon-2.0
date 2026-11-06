package com.photon.app

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.photon.app.data.db.PhotonDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Room 1 → 2 (end-to-end encryption): the new columns, and plaintext uploads in flight prepared again. */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), PhotonDatabase::class.java)

    @Test
    fun migrate1To2() {
        helper.createDatabase(DB, 1).apply {
            execSQL(
                "INSERT INTO pending_photos (media_id, uri, session_id, taken_at_ms, mime, state, sha256, size_bytes, " +
                    "copy_path, upload_id, upload_url, chunk_size, attempts, next_attempt_ms, last_error, created_at_ms) " +
                    "VALUES (1, 'content://x/1', 'sess-1', 0, 'image/jpeg', 'UPLOADING', 'ab', 10, '/c', 'u1', 'url', 262144, 0, 0, NULL, 0)",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(DB, 2, true, PhotonDatabase.MIGRATION_1_2)
        db.query("SELECT state, sha256, upload_id, chunk_size, photo_tag FROM pending_photos").use { c ->
            c.moveToFirst()
            assertEquals("PENDING", c.getString(0))
            assertNull(c.getString(1))
            assertNull(c.getString(2))
            assertEquals(0, c.getInt(3))
            assertNull(c.getString(4))
        }
        db.close()
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}
