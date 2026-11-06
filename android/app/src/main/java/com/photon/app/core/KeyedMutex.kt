package com.photon.app.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** One lock per key: per photo (saving an original) or per session (key maintenance and rotation). */
class KeyedMutex {
    private val locks = ConcurrentHashMap<String, Mutex>()
    suspend fun <T> withLock(key: String, block: suspend () -> T): T =
        locks.getOrPut(key) { Mutex() }.withLock { block() }
}
