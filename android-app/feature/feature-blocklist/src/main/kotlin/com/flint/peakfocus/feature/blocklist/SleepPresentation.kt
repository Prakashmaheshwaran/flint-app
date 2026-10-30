package com.flint.peakfocus.feature.blocklist

import androidx.annotation.StringRes
import com.flint.peakfocus.core.model.BreakLevel

/** Presentation only. Saved tiers come from SleepConfig, never the assist label. */
internal data class SleepPresentation(
    @StringRes val tier: Int,
    @StringRes val offExplanation: Int,
    @StringRes val nights: Int?,
)

internal fun sleepPresentation(config: SleepConfig): SleepPresentation = SleepPresentation(
    tier = when (config.breakLevel) {
        BreakLevel.EASY -> R.string.sleep_tier_easy
        BreakLevel.HARDER -> R.string.sleep_tier_harder
        BreakLevel.HARDCORE -> R.string.sleep_tier_hardcore
    },
    offExplanation = when (if (config.morningEnabled) maxOf(config.breakLevel, config.morningBreakLevel) else config.breakLevel) {
        BreakLevel.EASY -> R.string.sleep_off_easy
        BreakLevel.HARDER -> R.string.sleep_off_harder
        BreakLevel.HARDCORE -> R.string.sleep_off_hardcore
    },
    nights = when {
        config.nights.isEmpty() || config.nights.size == 7 -> R.string.sleep_every_night
        config.nights == IsoDays.WEEKDAYS -> R.string.sleep_weeknights
        config.nights == IsoDays.WEEKEND -> R.string.sleep_weekend_nights
        else -> null
    },
)

internal fun sleepTimeFieldsStack(widthDp: Float, fontScale: Float): Boolean =
    widthDp < 360f || fontScale >= 1.5f

@StringRes
internal fun sleepErrorText(error: SleepConfigError): Int = when (error) {
    SleepConfigError.INVALID_TIME -> R.string.sleep_invalid_time
    SleepConfigError.ZERO_LENGTH_WINDOW -> R.string.sleep_zero_length
    SleepConfigError.MORNING_NOT_AFTER_WAKE -> R.string.sleep_morning_after_wake
}
