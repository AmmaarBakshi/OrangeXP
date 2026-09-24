package com.orangexp.core.engine

/**
 * Metric keys understood by the engine. Mirrors `orangexp-core/src/metrics.rs`;
 * `MetricKeysTest` fails if the two drift apart.
 */
object MetricKeys {
    const val SLEEP_MINUTES = "sleep.minutes"
    const val AWAKE_MINUTES = "awake.minutes"
    const val STEPS = "walking.steps"
    const val WALKING_METERS = "walking.meters"
    const val WALKING_MINUTES = "walking.minutes"
    const val VEHICLE_MINUTES = "travel.vehicle_minutes"
    const val VEHICLE_METERS = "travel.vehicle_meters"
    const val ACTIVE_MINUTES = "activity.active_minutes"
    const val STUDY_MINUTES = "study.minutes"
    const val SYLLABUS_COMPLETED_MINUTES = "syllabus.completed_minutes"
    const val TOPICS_COMPLETED = "syllabus.topics_completed"
    const val CLASSES_ATTENDED = "attendance.classes_attended"
    const val CLASSES_SCHEDULED = "attendance.classes_scheduled"
    const val ATTENDANCE_PERCENT = "attendance.percent"
    const val SCHEDULE_ADHERENCE_PERCENT = "schedule.adherence_percent"
    const val ON_TIME_DEPARTURES = "travel.on_time_departures"
    const val TASKS_COMPLETED = "tasks.completed"
    const val SCREEN_MINUTES = "phone.screen_minutes"
    const val UNLOCKS = "phone.unlocks"

    val ALL = listOf(
        SLEEP_MINUTES, AWAKE_MINUTES, STEPS, WALKING_METERS, WALKING_MINUTES, VEHICLE_MINUTES, VEHICLE_METERS,
        ACTIVE_MINUTES, STUDY_MINUTES,
        SYLLABUS_COMPLETED_MINUTES, TOPICS_COMPLETED, CLASSES_ATTENDED, CLASSES_SCHEDULED,
        ATTENDANCE_PERCENT, SCHEDULE_ADHERENCE_PERCENT, ON_TIME_DEPARTURES, TASKS_COMPLETED,
        SCREEN_MINUTES, UNLOCKS,
    )
}
