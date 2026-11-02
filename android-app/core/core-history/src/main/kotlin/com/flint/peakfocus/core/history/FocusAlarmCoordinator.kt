package com.flint.peakfocus.core.history

interface FocusAlarmRegistry {
    suspend fun scheduled(): Map<String, Long>
    suspend fun setScheduled(alarms: Map<String, Long>)
}

/** Journal all affected identities before Android effects; recover/retry idempotently. */
class FocusAlarmCoordinator(
    private val registry: FocusAlarmRegistry,
    private val register: (String, Long) -> Unit,
    private val cancel: (String) -> Unit,
) {
    private var forceRegistration = true

    fun invalidate() { forceRegistration = true }

    suspend fun reconcile(targets: Map<String, Long>, now: Long) {
        try {
            val previous = registry.scheduled()
            // An interrupted cycle retains every identity whose OS state may have changed.
            // The first cycle in a new process cancels/re-registers this complete union.
            registry.setScheduled(previous + targets)
            previous.keys.filter { it !in targets }.forEach(cancel)
            targets.forEach { (id, end) ->
                if (forceRegistration || previous[id] != end) register(id, maxOf(end, now))
            }
            registry.setScheduled(targets)
            forceRegistration = false
        } catch (e: Exception) {
            invalidate()
            throw e
        }
    }
}
