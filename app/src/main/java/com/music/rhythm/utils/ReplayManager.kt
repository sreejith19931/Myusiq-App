package com.music.rhythm.utils

import android.content.Context
import java.util.*

enum class ReplayType {
    WEEKLY, MONTHLY, YEARLY
}

data class ReplayInfo(
    val type: ReplayType,
    val startTime: Long,
    val endTime: Long,
    val title: String,
    val subtitle: String,
    val periodKey: String,
)

object ReplayManager {
    private const val PREFS_NAME = "replay_prefs"
    private const val KEY_DISMISSED_PREFIX = "dismissed_"

    fun getAvailableReplay(context: Context): ReplayInfo? {
        val replays = getActiveReplays()
        if (replays.isEmpty()) return null
        
        return replays.firstOrNull { !isDismissed(context, it.periodKey) }
    }

    fun getActiveReplays(calendar: Calendar = Calendar.getInstance()): List<ReplayInfo> {
        val replays = mutableListOf<ReplayInfo>()
        
        // 1. Check Yearly (Jan 1st - Jan 15th)
        if ((calendar.get(Calendar.MONTH) == Calendar.JANUARY) && (calendar.get(Calendar.DAY_OF_MONTH) <= 15)) {
            val lastYear = calendar.get(Calendar.YEAR) - 1
            val start = (calendar.clone() as Calendar).apply { set(lastYear, Calendar.JANUARY, 1, 0, 0, 0) }.timeInMillis
            val end = (calendar.clone() as Calendar).apply { set(lastYear, Calendar.DECEMBER, 31, 23, 59, 59) }.timeInMillis
            replays.add(
                ReplayInfo(
                    ReplayType.YEARLY, start, end,
                    "Your $lastYear Wrapped", "Relive your top tracks of the year",
                    "YEARLY_$lastYear",
                ),
            )
        }

        // 2. Check Monthly (1st - 5th of month)
        if (calendar.get(Calendar.DAY_OF_MONTH) <= 5) {
            val prevMonth = (calendar.clone() as Calendar).apply { add(Calendar.MONTH, -1) }
            val monthName = prevMonth.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.getDefault()) ?: "Month"
            val start = (calendar.clone() as Calendar).apply {
                add(Calendar.MONTH, -1)
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
            }.timeInMillis
            val end = (calendar.clone() as Calendar).apply {
                set(Calendar.DAY_OF_MONTH, 1)
                add(Calendar.DAY_OF_MONTH, -1)
                set(Calendar.HOUR_OF_DAY, 23)
                set(Calendar.MINUTE, 59)
                set(Calendar.SECOND, 59)
            }.timeInMillis
            replays.add(ReplayInfo(
                ReplayType.MONTHLY, start, end,
                "$monthName Recap", "Your top tracks from last month",
                "MONTHLY_${prevMonth.get(Calendar.MONTH)}_${prevMonth.get(Calendar.YEAR)}"
            ))
        }

        // 3. Check Weekly (Monday - Wednesday)
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
        if (dayOfWeek == Calendar.MONDAY || dayOfWeek == Calendar.TUESDAY || dayOfWeek == Calendar.WEDNESDAY) {
            val prevWeek = (calendar.clone() as Calendar).apply { add(Calendar.WEEK_OF_YEAR, -1) }
            val weekOfYear = prevWeek.get(Calendar.WEEK_OF_YEAR)
            val year = prevWeek.get(Calendar.YEAR)
            val start = (calendar.clone() as Calendar).apply {
                add(Calendar.WEEK_OF_YEAR, -1)
                set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
            }.timeInMillis
            val end = (calendar.clone() as Calendar).apply {
                set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                add(Calendar.DAY_OF_YEAR, -1)
                set(Calendar.HOUR_OF_DAY, 23)
                set(Calendar.MINUTE, 59)
                set(Calendar.SECOND, 59)
            }.timeInMillis
            replays.add(ReplayInfo(
                ReplayType.WEEKLY, start, end,
                "Weekly Discovery", "Your favorites from the past 7 days",
                "WEEKLY_${weekOfYear}_$year"
            ))
        }

        return replays.distinctBy { it.periodKey }
    }

    fun isDismissed(context: Context, periodKey: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_DISMISSED_PREFIX + periodKey, false)
    }

    fun markAsDismissed(context: Context, periodKey: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_DISMISSED_PREFIX + periodKey, true).apply()
    }
    @Suppress("unused")
    fun isPopupDismissed(context: Context, periodKey: String): Boolean {
        return isDismissed(context, periodKey)
    }
}
