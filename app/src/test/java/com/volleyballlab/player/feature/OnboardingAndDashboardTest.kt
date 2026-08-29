package com.volleyballlab.player.feature

import com.volleyballlab.player.data.session.SessionRecord
import com.volleyballlab.player.data.session.toDomain
import com.volleyballlab.player.data.session.toRecord
import com.volleyballlab.player.domain.model.AthleteProfile
import com.volleyballlab.player.domain.model.CourtPosition
import com.volleyballlab.player.domain.model.CueId
import com.volleyballlab.player.domain.model.CueScore
import com.volleyballlab.player.domain.model.FormCueCatalog
import com.volleyballlab.player.domain.model.RepResult
import com.volleyballlab.player.domain.model.SelfRating
import com.volleyballlab.player.domain.model.SessionResult
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.domain.model.SkillAssessment
import com.volleyballlab.player.domain.model.SkillLevel
import com.volleyballlab.player.domain.model.SkillRecommendation
import com.volleyballlab.player.domain.model.TrainingTotals
import com.volleyballlab.player.feature.auth.AuthViewModel
import com.volleyballlab.player.feature.home.TrainingPeriod
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the onboarding questionnaire, what the dashboard derives from it, and
 * the session store that now has to survive the process dying.
 *
 * Every rule pinned here is one that is invisible when wrong. A recommendation that
 * quietly ignores position looks like a recommendation. An accuracy tile reading 0%
 * looks like a measurement. A session store that silently drops a rep looks like a
 * session store. None of these fail loudly on a device.
 */
class OnboardingAndDashboardTest {

    // ── Which skill the assessment points at ────────────────────────────────

    @Test
    fun `the lowest self-rating is the one recommended`() {
        val assessment = SkillAssessment(
            ratings = mapOf(
                Skill.Serving to SelfRating.Confident,
                Skill.Receiving to SelfRating.Struggling,
                Skill.Setting to SelfRating.Okay,
            ),
        )

        assertEquals(Skill.Receiving, assessment.weakestSkill())
    }

    /**
     * The case `AthleteProfile`'s KDoc has always named: a libero cannot legally
     * block, so however badly they rate themselves at it, sending them to drill it
     * is wasted training.
     */
    @Test
    fun `a libero is never sent to drill blocking`() {
        val assessment = SkillAssessment(
            ratings = mapOf(
                Skill.Blocking to SelfRating.Struggling,
                Skill.Receiving to SelfRating.Okay,
                Skill.Setting to SelfRating.Confident,
            ),
        )

        assertEquals(Skill.Receiving, assessment.weakestSkill(CourtPosition.Libero))
        // Without the position, the honest answer is still blocking.
        assertEquals(Skill.Blocking, assessment.weakestSkill())
    }

    /**
     * Filtering must not be able to produce "no advice at all". An athlete who only
     * rated skills their position excludes still gets something actionable.
     */
    @Test
    fun `position filtering falls back rather than returning nothing`() {
        val assessment = SkillAssessment(ratings = mapOf(Skill.Blocking to SelfRating.Struggling))

        assertEquals(Skill.Blocking, assessment.weakestSkill(CourtPosition.Libero))
    }

    @Test
    fun `ties break in the document's skill order, not at random`() {
        val assessment = SkillAssessment(
            ratings = Skill.All.associateWith { SelfRating.Struggling },
        )

        repeat(5) { assertEquals(Skill.Serving, assessment.weakestSkill()) }
    }

    @Test
    fun `an unanswered assessment recommends nothing`() {
        assertNull(SkillAssessment.Unanswered.weakestSkill())
        assertNull(
            SkillRecommendation.from(
                SkillAssessment.Unanswered,
                AthleteProfile.Anonymous,
                emptyList(),
            ),
        )
    }

    // ── How that skill gets levelled ────────────────────────────────────────

    /**
     * The self-rating chooses the skill; measured reps decide how good they are at
     * it. An athlete who rated their serving Struggling but scores 85 should be told
     * they are Advanced at it, not have their own guess repeated back to them.
     */
    @Test
    fun `measured sessions outrank the self-rating for the level`() {
        val assessment = SkillAssessment(ratings = mapOf(Skill.Serving to SelfRating.Struggling))
        val sessions = listOf(session(skill = Skill.Serving, score = 1f))

        val recommendation =
            SkillRecommendation.from(assessment, AthleteProfile.Anonymous, sessions)

        assertNotNull(recommendation)
        assertEquals(Skill.Serving, recommendation!!.skill)
        assertEquals(SkillLevel.Advanced, recommendation.level)
        assertTrue(recommendation.isMeasured)
    }

    @Test
    fun `with nothing measured the level comes from the rating and says so`() {
        val assessment = SkillAssessment(ratings = mapOf(Skill.Serving to SelfRating.Okay))

        val recommendation =
            SkillRecommendation.from(assessment, AthleteProfile.Anonymous, emptyList())!!

        assertEquals(SkillLevel.Intermediate, recommendation.level)
        assertEquals(false, recommendation.isMeasured)
        assertNull(recommendation.measuredScore)
    }

    /**
     * Sessions of other skills must not leak into this skill's score, or a strong
     * spiker would be told their serving is fine.
     */
    @Test
    fun `only sessions of the recommended skill count toward its score`() {
        val sessions = listOf(
            session(id = "a", skill = Skill.Spiking, score = 1f),
            session(id = "b", skill = Skill.Serving, score = 0.2f),
        )

        assertEquals(100, SkillRecommendation.meanScoreFor(Skill.Spiking, sessions))
        assertEquals(20, SkillRecommendation.meanScoreFor(Skill.Serving, sessions))
        assertNull(SkillRecommendation.meanScoreFor(Skill.Blocking, sessions))
    }

    // ── The Daily progress row ──────────────────────────────────────────────

    /**
     * The tile must read as a dash, not as 0%. A set filmed from an angle that hid
     * the athlete is a camera problem, and reporting it as nought accuracy blames
     * the athlete's technique for the tripod.
     */
    @Test
    fun `accuracy is absent rather than zero when nothing was measurable`() {
        val totals = TrainingTotals.from(listOf(session(score = null)))

        assertEquals(1, totals.sessionCount)
        assertNull(totals.averageScore)
        assertNull(totals.bestScore)
    }

    @Test
    fun `minutes round down from whole milliseconds`() {
        val totals = TrainingTotals.from(
            listOf(
                session(id = "a", durationMs = 90_000),
                session(id = "b", durationMs = 90_000),
            )
        )

        assertEquals(3, totals.totalMinutes)
        assertEquals(100, totals.averageScore)
    }

    @Test
    fun `this week excludes a session from before the week started`() {
        val zone = ZoneId.of("Asia/Manila")
        // A Wednesday, so the assertion holds whether the locale starts weeks on
        // Sunday or Monday - both leave today inside and a month ago outside.
        val today = LocalDate.of(2026, 8, 26)
        val sessions = listOf(
            session(id = "inside", startedAt = epoch(today, zone)),
            session(id = "outside", startedAt = epoch(today.minusDays(30), zone)),
        )

        val kept = TrainingPeriod.ThisWeek.filter(sessions, zone, today)

        assertEquals(listOf("inside"), kept.map { it.id })
    }

    @Test
    fun `all time keeps everything`() {
        val zone = ZoneId.of("Asia/Manila")
        val today = LocalDate.of(2026, 8, 26)
        val sessions = listOf(
            session(id = "old", startedAt = epoch(today.minusYears(2), zone)),
            session(id = "new", startedAt = epoch(today, zone)),
        )

        assertEquals(2, TrainingPeriod.AllTime.filter(sessions, zone, today).size)
    }

    @Test
    fun `this month keeps the first of the month and drops the last of the previous`() {
        val zone = ZoneId.of("Asia/Manila")
        val today = LocalDate.of(2026, 8, 26)
        val sessions = listOf(
            session(id = "first", startedAt = epoch(LocalDate.of(2026, 8, 1), zone)),
            session(id = "previous", startedAt = epoch(LocalDate.of(2026, 7, 31), zone)),
        )

        assertEquals(listOf("first"), TrainingPeriod.ThisMonth.filter(sessions, zone, today).map { it.id })
    }

    // ── The session store ───────────────────────────────────────────────────

    @Test
    fun `a session survives being written and read back`() {
        val original = session(
            id = "round-trip",
            skill = Skill.Setting,
            startedAt = 1_700_000_000_000,
            durationMs = 42_000,
            score = 0.75f,
            reps = 2,
        )

        val restored = json.decodeFromString<List<SessionRecord>>(
            json.encodeToString(listOf(original.toRecord())),
        ).mapNotNull { it.toDomain() }.single()

        assertEquals(original, restored)
    }

    /**
     * A file written by a later build must degrade, not crash. `CueId`'s KDoc calls
     * these names a wire format for exactly this reason.
     */
    @Test
    fun `an unknown cue is dropped and the rest of the session survives`() {
        val stored = """
            [{"id":"s","skill":"Setting","drillId":"d","startedAtEpochMs":0,"durationMs":0,
              "reps":[{"index":0,"startMs":0,"contactMs":0,"endMs":0,"cueScores":[
                {"cueId":"SetKneeFlexion","measured":1.0,"score":1.0},
                {"cueId":"CueFromTheFuture","measured":1.0,"score":1.0}]}]}]
        """.trimIndent()

        val restored = json.decodeFromString<List<SessionRecord>>(stored)
            .mapNotNull { it.toDomain() }
            .single()

        assertEquals(1, restored.reps.single().cueScores.size)
        assertEquals(CueId.SetKneeFlexion, restored.reps.single().cueScores.single().cueId)
    }

    @Test
    fun `a session naming an unknown skill is dropped entirely`() {
        val stored = """
            [{"id":"s","skill":"Bunting","drillId":"d","startedAtEpochMs":0,"durationMs":0,"reps":[]}]
        """.trimIndent()

        val restored = json.decodeFromString<List<SessionRecord>>(stored).mapNotNull { it.toDomain() }

        assertTrue(restored.isEmpty())
    }

    // ── Form validation ─────────────────────────────────────────────────────

    @Test
    fun `email validation catches the obvious typos and passes the rest`() {
        assertNull(AuthViewModel.validateEmail("kirt@example.com"))
        assertNull(AuthViewModel.validateEmail("  kirt@example.com  "))
        assertNotNull(AuthViewModel.validateEmail("kirt@example"))
        assertNotNull(AuthViewModel.validateEmail("kirtexample.com"))
        assertNotNull(AuthViewModel.validateEmail(""))
        assertNotNull(AuthViewModel.validateEmail("two@at@example.com"))
    }

    @Test
    fun `password validation matches the provider's own floor`() {
        assertNotNull(AuthViewModel.validatePassword("12345"))
        assertNull(AuthViewModel.validatePassword("123456"))
    }

    // ── Builders ────────────────────────────────────────────────────────────

    /** An hour past midnight, so the session sits unambiguously inside its day. */
    private fun epoch(date: LocalDate, zone: ZoneId): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli() + 3_600_000

    /**
     * Cues come from [FormCueCatalog] for the session's own skill.
     *
     * Not a fixed list: `RepResult.formScore` looks each cue up in the catalog for
     * the session's skill and skips anything it does not find, so a rep carrying
     * setting cues inside a serving session scores null rather than what the test
     * meant. That failure looks like a broken assertion about averages, several
     * steps from the actual mistake.
     */
    private fun session(
        id: String = "session",
        skill: Skill = Skill.Setting,
        startedAt: Long = 0L,
        durationMs: Long = 10_000L,
        score: Float? = 1f,
        reps: Int = 1,
    ) = SessionResult(
        id = id,
        skill = skill,
        drillId = "drill",
        startedAtEpochMs = startedAt,
        durationMs = durationMs,
        reps = (0 until reps).map { index ->
            RepResult(
                index = index,
                startMs = 0L,
                contactMs = 0L,
                endMs = 0L,
                cueScores = FormCueCatalog.forSkill(skill).map { cue ->
                    CueScore(cueId = cue.id, measured = score?.let { 0f }, score = score)
                },
            )
        },
    )

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
