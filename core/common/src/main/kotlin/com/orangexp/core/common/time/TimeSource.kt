package com.orangexp.core.common.time

import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The only way application code learns the current time and zone. Injecting it
 * keeps date-boundary logic testable and deterministic.
 */
interface TimeSource {
    fun now(): Instant
    fun zone(): ZoneId
}

@Singleton
class SystemTimeSource @Inject constructor() : TimeSource {
    override fun now(): Instant = Instant.now()
    override fun zone(): ZoneId = ZoneId.systemDefault()
}
