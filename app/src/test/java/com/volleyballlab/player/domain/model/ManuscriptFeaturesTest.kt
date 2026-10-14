package com.volleyballlab.player.domain.model

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ManuscriptFeaturesTest {
    private val today = LocalDate.of(2026, 9, 28)
    private val zone = ZoneId.of("Asia/Manila")
    private fun rep(vararg cues: Pair<CueId, Float>) = RepResult(1, 0, 500, 1000,
        cues.map { CueScore(it.first, 1f, it.second) })
    private fun session(id: String, date: LocalDate, reps: List<RepResult> = emptyList()) = SessionResult(id, Skill.Receiving, "pass", date.atStartOfDay(zone).toInstant().toEpochMilli(), 90_000, reps)
    private fun report(id: String, date: LocalDate) = SavedDetailedReport(id, ReportAllowance.week(date).toString(), "h", "e", "a", 0, true)

    @Test fun `seven day aggregation retains missing days and excludes older and future dates`() {
        val result = WeeklyProgress.from(listOf(session("a", today), session("b", today.minusDays(6)), session("c", today.minusDays(7)), session("future", today.plusDays(1))), today, zone)
        assertEquals(7, result.size); assertEquals(today.minusDays(6), result.first().date)
        assertEquals(2, result.sumOf { it.sessions }); assertEquals(180_000L, result.sumOf { it.durationMs })
        assertNull(result[2].meanScore)
    }
    @Test fun `high score without sufficient measured cue weight is not a valid rep`() {
        val underCovered = rep(CueId.PassPlatformStraight to 1f, CueId.PassKneeFlexion to 1f)
        assertEquals(100, underCovered.formScore(Skill.Receiving)); assertFalse(underCovered.isValidFor(Skill.Receiving))
        val covered = rep(CueId.PassPlatformStraight to 1f, CueId.PassKneeFlexion to 1f, CueId.PassTrunkAngle to 1f)
        assertTrue(covered.isValidFor(Skill.Receiving))
        assertEquals(1, WeeklyProgress.from(listOf(session("a", today, listOf(underCovered, covered))), today, zone).last().validReps)
    }
    @Test fun `free allowance is five and reopening a report remains allowed`() {
        val reports = (1..5).map { report("s$it", today) }
        assertEquals(0, ReportAllowance.usage(reports, UserPlan.Free, today).remaining)
        assertFalse(ReportAllowance.canCreate("new", reports, UserPlan.Free, today))
        assertTrue(ReportAllowance.canCreate("s1", reports, UserPlan.Free, today))
        assertTrue(ReportAllowance.canCreate("new", reports, UserPlan.Pro, today))
    }
    @Test fun `weekly report allowance resets on Monday`() {
        val previous = report("s1", today.minusDays(1))
        assertEquals(0, ReportAllowance.usage(listOf(previous), UserPlan.Free, today).used)
        assertEquals(today.plusWeeks(1), ReportAllowance.usage(listOf(previous), UserPlan.Free, today).resetDate)
    }
    @Test fun `today includes overdue open coach work but excludes future and completed assignments`() {
        fun assignment(id: String, date: LocalDate, status: AssignmentStatus) = CoachAssignment(id, "c", "p", "Pass", Skill.Receiving, DrillLevel.Beginner,
            "", "", 2, 10, date.atStartOfDay(zone).toInstant().toEpochMilli(), status, 0)
        val agenda = TrainingAgenda.today(listOf(ScheduledTraining("local", today.toString(), "17:00", Skill.Setting)),
            listOf(assignment("late", today.minusDays(1), AssignmentStatus.Pending), assignment("future", today.plusDays(1), AssignmentStatus.Pending),
                assignment("done", today, AssignmentStatus.Completed)), today, zone)
        assertEquals(setOf("local", "late"), agenda.map { it.id }.toSet()); assertTrue(agenda.first { it.id == "late" }.overdue)
    }
    @Test fun `saved goal determines recommendation even when assessment is empty`() {
        val result = SkillRecommendation.from(SkillAssessment.Unanswered, AthleteProfile(goalSkill = Skill.Setting), emptyList())
        assertEquals(Skill.Setting, result?.skill)
    }
    @Test fun `owner and active paid accounts alone have Pro`() {
        assertEquals(UserPlan.Pro, ProEntitlement.plan(ProEntitlement.OWNER_UID, null, null, 1000))
        assertEquals(UserPlan.Free, ProEntitlement.plan("other", null, null, 1000))
        assertEquals(UserPlan.Free, ProEntitlement.plan("other", "active", 1000, 1000))
        assertEquals(UserPlan.Pro, ProEntitlement.plan("other", "active", 1001, 1000))
        assertEquals(UserPlan.Pro, ProEntitlement.plan("other", "active", 0, 1000))
        assertEquals(UserPlan.Free, ProEntitlement.plan("other", "revoked", 0, 1000))
    }
    @Test fun `every skill has four teaching phases and common mistakes`() {
        Skill.All.forEach { val content = SkillLearningCatalog.forSkill(it); assertEquals(4, content.steps.size); assertTrue(content.mistakes.isNotEmpty()) }
    }
    @Test fun `notification refresh preserves read and delivered flags without duplicates`() {
        val event = NotificationEvents.session(session("a", today))
        val merged = NotificationEvents.merge(listOf(event.copy(isRead = true, delivered = true)), listOf(event))
        assertEquals(1, merged.size); assertTrue(merged.single().isRead); assertTrue(merged.single().delivered)
    }
}
