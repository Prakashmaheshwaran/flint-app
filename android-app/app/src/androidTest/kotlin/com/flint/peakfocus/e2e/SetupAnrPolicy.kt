package com.flint.peakfocus.e2e

/** Only system/launcher ANRs identified during fixture setup may be dismissed. */
internal object SetupAnrPolicy {
    enum class Action { WAIT, CLOSE, FAIL }

    fun action(title: String, launcher: String, wait: Boolean, close: Boolean): Action {
        val normalized = title.replace(Regex("\\p{Cf}"), "").replace('’', '\'').trim()
        val match = Regex("(?i)^(.+?) isn't responding[.]?$").matchEntire(normalized) ?: return Action.FAIL
        val owner = match.groupValues[1]
        val allowed = setOf("System UI", "com.android.systemui", launcher).filter { it.isNotBlank() }
        if (owner.contains("flint", ignoreCase = true) || allowed.none { it.equals(owner, ignoreCase = true) }) {
            return Action.FAIL
        }
        return when {
            wait -> Action.WAIT
            close -> Action.CLOSE
            else -> Action.FAIL
        }
    }
}
