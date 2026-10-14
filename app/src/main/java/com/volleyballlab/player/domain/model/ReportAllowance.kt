package com.volleyballlab.player.domain.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlinx.serialization.Serializable

@Serializable
data class SavedDetailedReport(val sessionId: String, val week: String, val headline: String,
    val explanation: String, val nextAction: String, val generatedAtEpochMs: Long, val aiGenerated: Boolean)

data class ReportUsage(val used: Int, val limit: Int, val resetDate: LocalDate, val unlimited: Boolean) {
    val remaining: Int get() = (limit - used).coerceAtLeast(0)
}

object ReportAllowance {
    const val FREE_WEEKLY_LIMIT = 5
    fun week(today: LocalDate) = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    fun usage(reports: List<SavedDetailedReport>, plan: UserPlan, today: LocalDate = LocalDate.now(), limit: Int = FREE_WEEKLY_LIMIT): ReportUsage {
        val start = week(today)
        return ReportUsage(reports.count { it.week == start.toString() }, limit, start.plusWeeks(1), plan == UserPlan.Pro)
    }
    fun canCreate(sessionId: String, reports: List<SavedDetailedReport>, plan: UserPlan, today: LocalDate = LocalDate.now()): Boolean =
        reports.any { it.sessionId == sessionId } || usage(reports, plan, today).let { it.unlimited || it.remaining > 0 }
}
