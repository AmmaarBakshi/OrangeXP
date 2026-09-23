package com.orangexp.core.ui

import com.orangexp.core.common.time.EpochDay
import com.orangexp.core.common.time.toLocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** "Thursday, 24 September 2026" in the user's locale. */
fun EpochDay.formatFull(locale: Locale = Locale.getDefault()): String =
    toLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale))

/** "24 Sept 2026" in the user's locale. */
fun EpochDay.formatMedium(locale: Locale = Locale.getDefault()): String =
    toLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))

/** "Thu 24" — compact day label for lists. */
fun EpochDay.formatShort(locale: Locale = Locale.getDefault()): String =
    toLocalDate().format(DateTimeFormatter.ofPattern("EEE d", locale))
