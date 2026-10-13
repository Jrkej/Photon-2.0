package com.photon.app.receive

import com.photon.app.core.EventIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiveRulesTest {
    private val now = 1_700_000_000_000L
    private val nothing = LocalHashState(inHashes = false, inReceivedPhotos = false, inOwnQueue = false)

    private fun decide(
        eventId: String = "1700000000000-1",
        last: String = "1699999999999-0",
        uploader: String = "bob",
        self: String? = "alice",
        expires: Long = now + 60_000,
        local: LocalHashState = nothing,
    ) = ReceiveDedupe.decideStored(eventId, last, uploader, self, expires, now, local)

    @Test
    fun streamIdsCompareAsNumbersNotStrings() {
        assertTrue(EventIds.isAfter("10-0", "9-5"))
        assertTrue(EventIds.isAfter("5-10", "5-9"))
        assertFalse(EventIds.isAfter("5-9", "5-10"))
        assertFalse(EventIds.isAfter("5-9", "5-9"))
        assertTrue(EventIds.isAfter("1-0", ""))
        assertEquals("10-0", EventIds.max("9-9", "10-0"))
        assertEquals("10-0", EventIds.max("10-0", ""))
    }

    @Test
    fun eventsAtOrBelowTheCursorAreDropped() {
        assertEquals(StoredDecision.DROP_SEEN_EVENT, decide(eventId = "5-0", last = "5-0"))
        assertEquals(StoredDecision.DROP_SEEN_EVENT, decide(eventId = "4-9", last = "5-0"))
        assertEquals(StoredDecision.FETCH, decide(eventId = "5-1", last = "5-0"))
    }

    @Test
    fun snapshotEntriesWithoutIdsAreDedupedByHashOnly() {
        assertTrue(ReceiveDedupe.isNewEvent("", "99-0"))
        assertEquals(StoredDecision.FETCH, decide(eventId = "", last = "99-0"))
        assertEquals(StoredDecision.DROP_KNOWN_HASH, decide(eventId = "", last = "99-0", local = nothing.copy(inHashes = true)))
    }

    @Test
    fun knownHashesAreDropped() {
        assertEquals(StoredDecision.DROP_KNOWN_HASH, decide(local = nothing.copy(inHashes = true)))
        assertEquals(StoredDecision.DROP_KNOWN_HASH, decide(local = nothing.copy(inReceivedPhotos = true)))
    }

    @Test
    fun ownPhotosAreNeverDownloadedBack() {
        assertEquals(StoredDecision.OWN_PHOTO, decide(uploader = "alice", self = "alice"))
        // Another member uploaded bytes our queue holds (the WAITING case).
        assertEquals(StoredDecision.OWN_PHOTO, decide(local = nothing.copy(inOwnQueue = true)))
        // Even when the hash is also known: the queue row must still be settled.
        assertEquals(StoredDecision.OWN_PHOTO, decide(local = LocalHashState(true, true, true)))
    }

    @Test
    fun expiredPhotosAreSkipped() {
        assertEquals(StoredDecision.DROP_EXPIRED, decide(expires = now))
        assertEquals(StoredDecision.DROP_EXPIRED, decide(expires = now - 1))
        assertEquals(StoredDecision.FETCH, decide(expires = 0)) // unknown expiry
    }

    @Test
    fun previewNeedsUnmeteredOrFastNetwork() {
        assertTrue(ReceivePolicy.fetchPreview(NetQuality(true, unmetered = true, downstreamKbps = 100, charging = false)))
        assertTrue(ReceivePolicy.fetchPreview(NetQuality(true, unmetered = false, downstreamKbps = 5000, charging = false)))
        assertFalse(ReceivePolicy.fetchPreview(NetQuality(true, unmetered = false, downstreamKbps = 4999, charging = false)))
        assertFalse(ReceivePolicy.fetchPreview(NetQuality(false, unmetered = true, downstreamKbps = 10_000, charging = false)))
    }

    @Test
    fun originalsFollowTheWifiOrChargingSetting() {
        val cellular = NetQuality(true, unmetered = false, downstreamKbps = 20_000, charging = false)
        assertEquals(OriginalTiming.NOW, ReceivePolicy.original(wifiOrChargingOnly = false, q = cellular))
        assertEquals(OriginalTiming.DEFERRED, ReceivePolicy.original(wifiOrChargingOnly = true, q = cellular))
        assertEquals(OriginalTiming.NOW, ReceivePolicy.original(true, cellular.copy(unmetered = true)))
        assertEquals(OriginalTiming.NOW, ReceivePolicy.original(true, cellular.copy(charging = true)))
    }
}
