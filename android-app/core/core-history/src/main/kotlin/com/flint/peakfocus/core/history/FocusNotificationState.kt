package com.flint.peakfocus.core.history

import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Separate from rules/preferences and history clear; corrupt delivery state fails closed. */
class FocusNotificationState(private val file: File) : FocusCompletionDeliveryState {
    override suspend fun wasDelivered(sessionId: String): Boolean = locked { it.containsKey("d:$sessionId") }

    override suspend fun markDelivered(sessionId: String, at: Long): Boolean = locked { fields ->
        if (fields.containsKey("d:$sessionId")) return@locked false
        fields["d:$sessionId"] = at
        fields.remove("s:$sessionId")
        val cutoff = at - FocusHistoryStore.RETENTION_MS
        fields.entries.removeAll { it.key.startsWith("d:") && it.value < cutoff }
        fields.entries.filter { it.key.startsWith("d:") }.sortedByDescending { it.value }.drop(10_000)
            .forEach { fields.remove(it.key) }
        write(fields)
        true
    }

    suspend fun delivered(): Set<String> = locked { fields -> fields.keys.filter { it.startsWith("d:") }.map { it.removePrefix("d:") }.toSet() }
    suspend fun scheduled(): Map<String, Long> = locked { fields -> fields.filterKeys { it.startsWith("s:") }.mapKeys { it.key.removePrefix("s:") } }
    suspend fun setScheduled(alarms: Map<String, Long>) = locked { fields ->
        fields.keys.filter { it.startsWith("s:") }.forEach { fields.remove(it) }
        alarms.forEach { (id, at) -> if (!fields.containsKey("d:$id")) fields["s:$id"] = at }
        write(fields)
    }

    private suspend fun <T> locked(block: (MutableMap<String, Long>) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(!file.exists() || file.length() <= 2 * 1024 * 1024) { "Notification state exceeds safety bound" }
            val fields = if (file.exists()) FlatJson.decode(file.readText(Charsets.UTF_8)).mapValues { (key, value) ->
                require(key == "v" || key.startsWith("d:") || key.startsWith("s:"))
                value as? Long ?: error("Unreadable notification state")
            }.toMutableMap().also { require(it.remove("v") == 1L) } else mutableMapOf()
            block(fields)
        }
    }
    private fun write(fields: Map<String, Long>) {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val bytes = FlatJson.encode(linkedMapOf<String, Any?>("v" to 1L).apply { putAll(fields) }).toByteArray(Charsets.UTF_8)
        val temp = File(file.parentFile, file.name + ".tmp")
        try {
            FileOutputStream(temp).use { it.write(bytes); it.flush(); it.fd.sync() }
            check(temp.renameTo(file))
        } finally { temp.delete() }
    }
    private companion object { val mutex = Mutex() }
}
