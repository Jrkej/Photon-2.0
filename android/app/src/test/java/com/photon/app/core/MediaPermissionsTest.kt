package com.photon.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaPermissionsTest {
    private val images = "android.permission.READ_MEDIA_IMAGES"
    private val selected = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
    private val legacy = "android.permission.READ_EXTERNAL_STORAGE"

    private fun access(sdk: Int, vararg granted: String) = MediaPermissions.access(sdk) { it in granted }

    @Test
    fun android14PartialAccessIsDetected() {
        assertEquals(MediaAccess.PARTIAL, access(34, selected))
        assertEquals(MediaAccess.FULL, access(34, images, selected))
        assertEquals(MediaAccess.DENIED, access(34))
    }

    @Test
    fun android13() {
        assertEquals(MediaAccess.FULL, access(33, images))
        assertEquals(MediaAccess.DENIED, access(33, legacy))
    }

    @Test
    fun android12AndLowerUseStorage() {
        assertEquals(MediaAccess.FULL, access(32, legacy))
        assertEquals(MediaAccess.DENIED, access(26))
    }

    @Test
    fun requestListsMatchTheSdk() {
        assertEquals(listOf(images, selected), MediaPermissions.toRequest(34).toList())
        assertEquals(listOf(images), MediaPermissions.toRequest(33).toList())
        assertEquals(listOf(legacy), MediaPermissions.toRequest(30).toList())
        assertEquals(2, MediaPermissions.toRequest(28).size)
    }
}
