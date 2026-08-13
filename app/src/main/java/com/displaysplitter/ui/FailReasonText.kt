package com.displaysplitter.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.displaysplitter.R
import com.displaysplitter.split.FailReason

/** Shared, user-facing message for each engagement failure — used by both the settings
 *  status card and the floating quick panel so the guidance is identical everywhere. */
@Composable
fun failReasonText(reason: FailReason): String = stringResource(
    when (reason) {
        FailReason.NO_SERVICE -> R.string.fail_no_service
        FailReason.NO_TARGET_APP -> R.string.fail_no_target
        FailReason.NOT_INNER_DISPLAY -> R.string.fail_not_inner
        FailReason.FLEX_MODE -> R.string.fail_flex
        FailReason.RATIO_OFF -> R.string.fail_ratio_off
        FailReason.SPLIT_UNAVAILABLE -> R.string.fail_split_unavailable
        FailReason.DIVIDER_LOST, FailReason.ADJUST_FAILED -> R.string.fail_split
    }
)
