package com.flint.peakfocus.feature.blocklist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.flint.peakfocus.blocking.engine.OneShotScheduleResolver
import com.flint.peakfocus.core.model.OneShotSchedule
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Form preparation only; the leased rule editor does not call this component yet. */
internal data class OneShotScheduleInput(
    val startDate: String = "",
    val startTime: String = "09:00",
    val endDate: String = "",
    val endTime: String = "17:00",
    val zoneId: String = "UTC",
) {
    fun resolve(nowEpochMs: Long): OneShotScheduleResolver.Result = try {
        if (!startTime.matches(Regex("[0-9]{2}:[0-9]{2}")) ||
            !endTime.matches(Regex("[0-9]{2}:[0-9]{2}"))) {
            OneShotScheduleResolver.Result.Rejected(OneShotScheduleResolver.Error.INVALID_TIME)
        } else OneShotScheduleResolver.resolve(
            LocalDate.parse(startDate).atTime(LocalTime.parse(startTime)),
            LocalDate.parse(endDate).atTime(LocalTime.parse(endTime)), zoneId, nowEpochMs)
    } catch (_: DateTimeException) {
        OneShotScheduleResolver.Result.Rejected(OneShotScheduleResolver.Error.INVALID_TIME)
    }
}

internal fun oneShotResolvedText(schedule: OneShotSchedule): String {
    val format = DateTimeFormatter.ofPattern("EEE, MMM d, uuuu HH:mm XXX")
    fun endpoint(epoch: Long, offset: Int) =
        Instant.ofEpochMilli(epoch).atOffset(ZoneOffset.ofTotalSeconds(offset)).format(format)
    return "${endpoint(schedule.startsAtEpochMs, schedule.startOffsetSeconds)} to " +
        "${endpoint(schedule.endsAtEpochMs, schedule.endOffsetSeconds)} · ${schedule.zoneId}"
}

@Composable
internal fun OneShotScheduleFields(input: OneShotScheduleInput, nowEpochMs: Long,
    onChange: (OneShotScheduleInput) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("One-time schedule", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(input.startDate, { onChange(input.copy(startDate = it)) },
            label = { Text("Start date (YYYY-MM-DD)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(input.startTime, { onChange(input.copy(startTime = it)) },
            label = { Text("Start time (24-hour HH:MM)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(input.endDate, { onChange(input.copy(endDate = it)) },
            label = { Text("End date (YYYY-MM-DD)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(input.endTime, { onChange(input.copy(endTime = it)) },
            label = { Text("End time (24-hour HH:MM)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(input.zoneId, { onChange(input.copy(zoneId = it)) },
            label = { Text("Time zone (for example America/New_York)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        Text("Starts within 365 elapsed days. Lasts at most 26 hours, the same limit as recurring schedules. Runs once.")
        when (val result = input.resolve(nowEpochMs)) {
            is OneShotScheduleResolver.Result.Resolved -> Text("Scheduled for ${oneShotResolvedText(result.schedule)}")
            is OneShotScheduleResolver.Result.Rejected -> Text(when (result.error) {
                OneShotScheduleResolver.Error.INVALID_ZONE -> "Choose a valid time zone."
                OneShotScheduleResolver.Error.INVALID_TIME -> "Enter valid start and end dates and times."
                OneShotScheduleResolver.Error.INVALID_CLOCK -> "Time is unavailable. Try again before saving."
                OneShotScheduleResolver.Error.START_NOT_FUTURE -> "Choose a start in the future."
                OneShotScheduleResolver.Error.START_TOO_FAR -> "Choose a start within 365 elapsed days."
                OneShotScheduleResolver.Error.INVALID_DURATION -> "End must follow start by at most 26 hours."
            }, color = MaterialTheme.colorScheme.error)
        }
    }
}
