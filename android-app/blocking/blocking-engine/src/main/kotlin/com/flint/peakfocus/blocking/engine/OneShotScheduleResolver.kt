package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.core.model.OneShotSchedule
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** D3 / ADR-012 §9: resolve endpoints at save, never when a callback happens to arrive. */
object OneShotScheduleResolver {
    const val MAX_ADVANCE_MS: Long = 365L * 24 * 3_600_000

    enum class Error {
        INVALID_ZONE, INVALID_TIME, INVALID_CLOCK, START_NOT_FUTURE, START_TOO_FAR, INVALID_DURATION,
    }

    sealed interface Result {
        data class Resolved(val schedule: OneShotSchedule) : Result
        data class Rejected(val error: Error) : Result
    }

    fun resolve(start: LocalDateTime, end: LocalDateTime, zoneId: String, nowEpochMs: Long): Result {
        if (zoneId !in ZoneId.getAvailableZoneIds()) return Result.Rejected(Error.INVALID_ZONE)
        if (nowEpochMs < 0) return Result.Rejected(Error.INVALID_CLOCK)
        return try {
            val zone = ZoneId.of(zoneId)
            val from = endpoint(start, zone, start = true)
            val to = endpoint(end, zone, start = false)
            val startMs = from.toInstant().toEpochMilli()
            val endMs = to.toInstant().toEpochMilli()
            when {
                startMs <= nowEpochMs -> Result.Rejected(Error.START_NOT_FUTURE)
                startMs - nowEpochMs > MAX_ADVANCE_MS -> Result.Rejected(Error.START_TOO_FAR)
                endMs <= startMs || !isValidOccurrenceDuration(endMs - startMs) ->
                    Result.Rejected(Error.INVALID_DURATION)
                else -> Result.Resolved(OneShotSchedule(startMs, endMs, zoneId,
                    from.offset.totalSeconds, to.offset.totalSeconds))
            }
        } catch (_: DateTimeException) {
            Result.Rejected(Error.INVALID_TIME)
        } catch (_: ArithmeticException) {
            Result.Rejected(Error.INVALID_TIME)
        }
    }

    private fun endpoint(local: LocalDateTime, zone: ZoneId, start: Boolean): ZonedDateTime {
        val offsets = zone.rules.getValidOffsets(local)
        if (offsets.isEmpty()) return local.atZone(zone) // Move forward by the entire gap.
        val offset = if (start) offsets.minBy { local.toInstant(it) }
            else offsets.maxBy { local.toInstant(it) }
        return ZonedDateTime.ofLocal(local, zone, offset)
    }
}
