package com.photon.app.core

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

enum class MediaAccess { FULL, PARTIAL, DENIED }

/**
 * Gallery permission state. On Android 14 "Select photos" grants only
 * READ_MEDIA_VISUAL_USER_SELECTED, which hides every photo taken afterwards, so it counts as
 * [MediaAccess.PARTIAL] and the app asks for full access.
 */
@SuppressLint("InlinedApi") // permission names are plain strings, used only behind SDK checks
object MediaPermissions {
    private const val USER_SELECTED = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"

    fun access(sdk: Int, granted: (String) -> Boolean): MediaAccess = when {
        sdk >= 33 && granted(Manifest.permission.READ_MEDIA_IMAGES) -> MediaAccess.FULL
        sdk >= 34 && granted(USER_SELECTED) -> MediaAccess.PARTIAL
        sdk >= 33 -> MediaAccess.DENIED
        granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> MediaAccess.FULL
        else -> MediaAccess.DENIED
    }

    fun access(context: Context): MediaAccess = access(Build.VERSION.SDK_INT) { isGranted(context, it) }

    /** What to request. Asking for USER_SELECTED as well lets the system offer the choice and tells us about it. */
    fun toRequest(sdk: Int = Build.VERSION.SDK_INT): Array<String> = when {
        sdk >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, USER_SELECTED)
        sdk >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        sdk >= 29 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        // Android 8 and 9 save received originals through the file system.
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    fun canReadLocation(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 29 && isGranted(context, Manifest.permission.ACCESS_MEDIA_LOCATION)

    fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
