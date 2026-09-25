package com.orangexp.feature.holstrom.voice

import android.content.Context
import com.orangexp.core.voice.SpeechFailure
import com.orangexp.feature.holstrom.R

internal fun speechFailureText(context: Context, reason: SpeechFailure): String = context.getString(
    when (reason) {
        SpeechFailure.NO_PERMISSION -> R.string.speech_no_permission
        SpeechFailure.NOT_AVAILABLE -> R.string.speech_not_available
        SpeechFailure.NOTHING_HEARD -> R.string.speech_nothing_heard
        SpeechFailure.NETWORK -> R.string.speech_network
        SpeechFailure.BUSY -> R.string.speech_busy
        SpeechFailure.OTHER -> R.string.speech_other
    },
)
