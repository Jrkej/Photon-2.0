package com.photon.app.receive

import com.photon.app.core.EventIds

/** What the receive pipeline knows locally about an incoming photo's hash. */
data class LocalHashState(
    /** In `hashes` (received or uploaded before). */
    val inHashes: Boolean,
    /** In `received_photos`, including recall tombstones. */
    val inReceivedPhotos: Boolean,
    /** Some row of our own upload queue has these bytes. */
    val inOwnQueue: Boolean,
)

enum class StoredDecision {
    /** Already processed (id at or below the cursor). */
    DROP_SEEN_EVENT,

    /** Our own photo, or bytes our queue already holds: mark them synced, never download. */
    OWN_PHOTO,

    /** Already held or tombstoned. */
    DROP_KNOWN_HASH,

    /** Expired before we got to it. */
    DROP_EXPIRED,

    FETCH,
}

/** Dedupe rules for incoming events (implementation plan 7.9 step 1). */
object ReceiveDedupe {

    /** Snapshot entries carry no event id and are deduped by hash only. */
    fun isNewEvent(eventId: String, lastEventId: String): Boolean =
        eventId.isEmpty() || EventIds.isAfter(eventId, lastEventId)

    fun decideStored(
        eventId: String,
        lastEventId: String,
        uploaderId: String,
        selfUserId: String?,
        expiresAtMs: Long,
        nowMs: Long,
        local: LocalHashState,
    ): StoredDecision = when {
        !isNewEvent(eventId, lastEventId) -> StoredDecision.DROP_SEEN_EVENT
        // Our own upload's event can arrive before `complete` answers, so it is recognised by
        // uploader and by queue contents, not only by the `hashes` table.
        (selfUserId != null && uploaderId == selfUserId) || local.inOwnQueue -> StoredDecision.OWN_PHOTO
        local.inHashes || local.inReceivedPhotos -> StoredDecision.DROP_KNOWN_HASH
        expiresAtMs in 1..nowMs -> StoredDecision.DROP_EXPIRED
        else -> StoredDecision.FETCH
    }
}

/** Network conditions for the receive policy. */
data class NetQuality(
    val connected: Boolean,
    val unmetered: Boolean,
    val downstreamKbps: Int,
    val charging: Boolean,
)

enum class OriginalTiming { NOW, DEFERRED }

/** When to fetch the preview and the original (implementation plan 7.9 steps 3 and 4). */
object ReceivePolicy {
    const val PREVIEW_MIN_KBPS = 5000

    fun fetchPreview(q: NetQuality): Boolean = q.connected && (q.unmetered || q.downstreamKbps >= PREVIEW_MIN_KBPS)

    /**
     * Without the "originals on Wi-Fi or while charging" setting the original is fetched at once.
     * With it, only when unmetered or charging now; otherwise it is deferred to constrained work.
     */
    fun original(wifiOrChargingOnly: Boolean, q: NetQuality): OriginalTiming =
        if (!wifiOrChargingOnly || q.unmetered || q.charging) OriginalTiming.NOW else OriginalTiming.DEFERRED
}
