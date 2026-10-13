package com.photon.app.core

import android.content.Context
import java.io.File

/**
 * App-private cache locations. Thumbnails and previews live only here, never in the gallery;
 * upload copies live here until their row reaches a final state.
 */
class MediaCache(context: Context) {
    private val root = context.cacheDir

    val uploads: File get() = dir("uploads")
    val thumbs: File get() = dir("thumbs")
    val previews: File get() = dir("previews")
    val originals: File get() = dir("originals")

    fun uploadCopy(mediaId: Long) = File(uploads, "$mediaId.bin")
    fun thumb(sha256: String) = File(thumbs, "$sha256.webp")
    fun preview(sha256: String) = File(previews, "$sha256.webp")
    fun original(sha256: String) = File(originals, sha256)

    fun deleteCachedCopies(sha256: String) {
        thumb(sha256).delete()
        preview(sha256).delete()
        original(sha256).delete()
        File(original(sha256).path + ".part").delete()
    }

    /** 7.12: everything session-related goes when the session does. */
    fun clearSessionFiles() {
        listOf("uploads", "thumbs", "previews", "originals").forEach { File(root, it).deleteRecursively() }
    }

    private fun dir(name: String) = File(root, name).apply { mkdirs() }
}
