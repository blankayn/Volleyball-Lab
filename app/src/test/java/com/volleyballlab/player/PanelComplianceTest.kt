package com.volleyballlab.player

import com.volleyballlab.player.domain.model.AthleteProfile
import com.volleyballlab.player.domain.model.CoachAssessmentEngine
import com.volleyballlab.player.domain.model.CoachPlan
import com.volleyballlab.player.domain.model.CoachProfile
import com.volleyballlab.player.domain.model.CoachTrust
import com.volleyballlab.player.domain.model.CoachTrustEvidence
import com.volleyballlab.player.domain.model.CoachTrustStatus
import com.volleyballlab.player.domain.model.CueScore
import com.volleyballlab.player.domain.model.FormCueCatalog
import com.volleyballlab.player.domain.model.RepResult
import com.volleyballlab.player.domain.model.ScoreBreakdown
import com.volleyballlab.player.domain.model.SessionResult
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.domain.model.TrustCheckId
import com.volleyballlab.player.domain.model.UserPlan
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelComplianceTest {
    private val now = 1_800_000_000_000L
    private val coach = CoachProfile(name = "Alex Rivera", organisation = "City Club")
    private fun status(evidence: CoachTrustEvidence, profile: CoachProfile = coach) = CoachTrust.of(profile, evidence, now).status

    @Test fun onlyRestrictedSchoolAndGovernmentEndingsCount() {
        listOf("uc.edu.ph", "mail.school.edu", "deped.gov.ph", "x.ac.uk", "x.sch.id", "UC.EDU.PH").forEach { assertTrue(it, CoachTrust.isInstitution(it)) }
        listOf("gmail.com", "edu.com", "uc.edu.ph.evil.com", "fakeedu.ph", "edu.ph.co", "").forEach { assertFalse(it, CoachTrust.isInstitution(it)) }
    }

    @Test fun everyCoachIsDecidedByTheSameChecks() {
        val email = CoachTrustEvidence(now)
        assertEquals(CoachTrustStatus.NotConfirmed, status(email))
        assertEquals(CoachTrustStatus.InstitutionConfirmed, status(email.copy(institutionDomain = "uc.edu.ph")))
        assertEquals(CoachTrustStatus.ChecksPassed, status(email.copy(vouches = 2)))
        assertEquals(CoachTrustStatus.ChecksPassed, status(email.copy(reviewers = 3)))
        assertEquals(CoachTrustStatus.NotConfirmed, status(email.copy(vouches = 1, reviewers = 2)))
        assertEquals(CoachTrustStatus.NotConfirmed, status(CoachTrustEvidence(null, "uc.edu.ph", 9, 9)))
        assertEquals(CoachTrustStatus.NotConfirmed, status(email.copy(vouches = 2), coach.copy(organisation = " ")))
        assertEquals(CoachTrustStatus.NotConfirmed, status(email.copy(vouches = 2), coach.copy(name = "Alex")))
    }

    @Test fun theOwnerCanVerifyACoachAndPlayersSeeOnlyVerifiedOrNot() {
        val known = CoachTrust.of(CoachProfile(), CoachTrustEvidence(verifiedByTeam = true), now)
        assertEquals(CoachTrustStatus.TeamVerified, known.status)
        assertTrue(known.verified)
        assertFalse(CoachTrust.of(coach, CoachTrustEvidence(), now).verified)
        assertTrue(CoachTrust.of(coach, CoachTrustEvidence(now, vouches = 2), now).verified)
    }

    @Test fun anOldConfirmationOrAnUnlistedDomainDoesNotCount() {
        assertEquals(CoachTrustStatus.NotConfirmed, status(CoachTrustEvidence(now - CoachTrust.EMAIL_VALID_MS - 1, "uc.edu.ph")))
        val trust = CoachTrust.of(coach, CoachTrustEvidence(now, "gmail.com"), now)
        assertEquals("", trust.institutionDomain)
        assertFalse(trust.checks.first { it.id == TrustCheckId.Institution }.passed)
    }

    @Test fun aFreeCoachHasThreePlacesAndProHasNoLimit() {
        assertTrue(CoachPlan.canAccept(UserPlan.Free, 2))
        assertFalse(CoachPlan.canAccept(UserPlan.Free, 3))
        assertTrue(CoachPlan.canAccept(UserPlan.Pro, 300))
        assertEquals(listOf("a", "b"), CoachPlan.slots(null, setOf("a", "b"), accepting = true))
        assertNull(CoachPlan.slots(null, setOf("a"), accepting = false))
        assertEquals(listOf("b", "c"), CoachPlan.slots(listOf("a", "b"), setOf("b", "c"), accepting = true))
        assertEquals("ending never adds a place", listOf("b"), CoachPlan.slots(listOf("a", "b"), setOf("b", "legacy"), accepting = false))
    }

    @Test fun aNameIsShownWithTheMiddleInitialOnly() {
        assertEquals("Maria C. Santos", AthleteProfile.displayName("Maria", "cruz", "Santos"))
        assertEquals("Maria Santos", AthleteProfile.displayName(" Maria ", " ", " Santos "))
        assertTrue(AthleteProfile.displayName("x".repeat(40), "y", "z".repeat(40)).length <= AthleteProfile.MAX_NAME_LENGTH)
    }

    private fun session(skill: Skill, measure: (Int) -> Float?): SessionResult = SessionResult(
        id = "s", skill = skill, drillId = "any", startedAtEpochMs = 1, durationMs = 10_000,
        reps = List(4) { index ->
            RepResult(index, index * 2000L, index * 2000L + 500, index * 2000L + 1000,
                FormCueCatalog.forSkill(skill).mapIndexed { i, cue ->
                    val value = measure(i)?.let { cue.target.start - cue.warnTolerance * it }
                    CueScore(cue.id, value, cue.score(value))
                })
        },
    )

    @Test fun thePointsShownAddUpToTheScoreShown() {
        Skill.All.forEach { skill ->
            val assessment = CoachAssessmentEngine.assess(session(skill) { it * 0.2f })
            val shown = ScoreBreakdown.of(assessment)
            assertEquals(1.0, shown.rows.sumOf { it.share }, 1e-6)
            assertEquals(skill.name, assessment.overallScore, shown.total)
            assertTrue(skill.name, abs(shown.rows.sumOf { it.points } - shown.total!!) <= 0.5001)
            assertEquals(shown.total, shown.rows.sumOf { it.points }.roundToInt())
        }
    }

    @Test fun tooLittleEvidenceGivesNoTotal() {
        val shown = ScoreBreakdown.of(CoachAssessmentEngine.assess(session(Skill.Serving) { if (it == 0) 0f else null }))
        assertNull(shown.total)
        assertEquals(1, shown.rows.size)
        assertTrue(shown.coverage < CoachAssessmentEngine.MIN_COVERAGE)
    }
}
