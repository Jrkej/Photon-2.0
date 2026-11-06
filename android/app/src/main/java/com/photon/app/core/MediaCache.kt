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

    /** The GPS-stripped original, until the encrypted file is built from it. */
    fun uploadCopy(mediaId: Long) = File(uploads, "$mediaId.bin")

    /** The encrypted file (e2ee.md 6), kept until the upload finishes so a re-announce sends the same bytes. */
    fun encryptedUpload(mediaId: Long) = File(uploads, "$mediaId.phe")
    fun thumb(sha256: String) = File(thumbs, "$sha256.webp")
    fun preview(sha256: String) = File(previews, "$sha256.webp")

    /** The downloaded encrypted file (the name is its hash). */
    fun original(sha256: String) = File(originals, sha256)

    /** A downloaded prefix of the encrypted file (`thumb` or `preview`), until it is decrypted. */
    fun prefix(sha256: String, size: String) = File(originals, "$sha256.$size")

    /** The decrypted original, until it is in the gallery. */
    fun decrypted(sha256: String) = File(originals, "$sha256.plain")

    fun deleteCachedCopies(sha256: String) {
        thumb(sha256).delete()
        preview(sha256).delete()
        originals.listFiles()?.forEach { if (it.name.startsWith(sha256)) it.delete() }
    }

    /** 7.12: everything session-related goes when the session does. */
    fun clearSessionFiles() {
        listOf("uploads", "thumbs", "previews", "originals").forEach { File(root, it).deleteRecursively() }
    }

    private fun dir(name: String) = File(root, name).apply { mkdirs() }
}
