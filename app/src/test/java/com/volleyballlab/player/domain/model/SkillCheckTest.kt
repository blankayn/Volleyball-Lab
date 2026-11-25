package com.volleyballlab.player.domain.model

import com.volleyballlab.player.data.profile.TrainingSetupCloudSchema
import org.junit.Assert.*
import org.junit.Test

class SkillCheckTest {
    private fun answers(skill: Skill, vararg given: CanDo) = SkillCheck.forSkill(skill).map { it.id }.zip(given.toList()).toMap()
    private fun all(answer: CanDo) = SkillCheck.statements.associate { it.id to answer }
    private fun session(skill: Skill, score: Float, at: Long) = SessionResult("$skill$at", skill, "d", at, 1_000,
        List(5) { RepResult(it, 0, 500, 1_000, FormCueCatalog.poseCues(skill).map { cue -> CueScore(cue.id, 1f, score) }) })

    @Test
    fun everyStatementHasItsOwnThreeDetailsAndAName() {
        SkillCheck.statements.forEach { statement ->
            assertTrue(statement.id, statement.trait.isNotBlank())
            assertEquals(List(3) { "${statement.id}_${it + 1}" }, statement.details.map { it.id })
            assertEquals(3, statement.details.map { it.text }.distinct().size)
            assertTrue(statement.id, statement.details.all { it.text.length <= 32 && !Regex("\\d").containsMatchIn(it.text) })
        }
        Skill.All.forEach { skill -> assertEquals(4, SkillCheck.forSkill(skill).map { it.trait }.distinct().size) }
        assertEquals(listOf(true, true, false, false), listOf(CanDo.Sometimes, CanDo.NotYet, CanDo.Yes, CanDo.NotTried).map(SkillCheck::asksDetails))
    }

    @Test fun everySkillIsAskedTheSameNumberOfStatementsAboutTheSameThings() {
        Skill.All.forEach { skill ->
            assertEquals(SkillCheck.PER_SKILL, SkillCheck.forSkill(skill).size)
            assertEquals(listOf("ready", "movement", "control", "moving"), SkillCheck.forSkill(skill).map { it.id.substringAfter('_') })
        }
        assertEquals(SkillCheck.statements.size, SkillCheck.statements.map { it.id }.distinct().size)
        // One skill must not sound longer or harder than another.
        val lengths = Skill.All.map { skill -> SkillCheck.forSkill(skill).sumOf { it.text.length } }
        assertTrue("$lengths", lengths.max() <= lengths.min() * 1.25)
    }

    @Test fun noStatementAsksForALevelAStyleOrWhoThePlayerIs() {
        val loaded = Regex("beginner|intermediate|advanced|expert|level|style|position|libero|setter|hitter|year|age|male|female|height|strong|weak|good|bad|best", RegexOption.IGNORE_CASE)
        SkillCheck.statements.forEach { assertFalse(it.text, loaded.containsMatchIn(it.text)) }
    }

    @Test fun theOrderDiffersBetweenPlayersAndNoSkillIsAlwaysFirst() {
        val orders = (1..200).map { SkillCheck.orderFor("player$it") }
        assertEquals(Skill.All.toSet(), orders.map { it.first() }.toSet())
        Skill.All.forEach { skill -> assertTrue("$skill", orders.count { it.first() == skill } in 20..70) }
        assertEquals(SkillCheck.orderFor("a"), SkillCheck.orderFor("a"))
    }

    @Test fun theSameAnswersGiveEverySkillTheSameResult() {
        for (given in listOf(CanDo.Yes, CanDo.Sometimes, CanDo.NotYet)) {
            assertEquals(1, SkillEvidence.from(all(given), emptyList()).map { it.strength }.distinct().size)
        }
        assertEquals(listOf(100), SkillEvidence.from(all(CanDo.Yes), emptyList()).map { it.strength }.distinct())
        assertEquals(listOf(50), SkillEvidence.from(all(CanDo.Sometimes), emptyList()).map { it.strength }.distinct())
    }

    @Test fun notHavingTriedSomethingIsNotCountedAgainstThePlayer() {
        val evidence = SkillEvidence.from(answers(Skill.Serving, CanDo.Yes, CanDo.Yes, CanDo.NotTried, CanDo.NotTried) +
            answers(Skill.Blocking, CanDo.NotTried, CanDo.NotTried, CanDo.NotTried, CanDo.Yes), emptyList())
        assertEquals(100, evidence.first { it.skill == Skill.Serving }.strength)
        assertNull("one answer is too little to compare", evidence.first { it.skill == Skill.Blocking }.strength)
        assertEquals(EvidenceSource.Unknown, evidence.first { it.skill == Skill.Blocking }.source)
    }

    @Test fun aMeasuredSessionReplacesTheAnswersForThatSkillOnly() {
        val evidence = SkillEvidence.from(all(CanDo.NotYet), listOf(session(Skill.Serving, .9f, 10)))
        val serving = evidence.first { it.skill == Skill.Serving }
        assertEquals(EvidenceSource.Measured, serving.source)
        assertEquals(90, serving.strength)
        assertTrue(evidence.filter { it.skill != Skill.Serving }.all { it.source == EvidenceSource.Answers && it.strength == 0 })
        assertFalse(NextSkills.suggest(evidence).items.any { it.skill == Skill.Serving })
    }

    @Test fun theLowestEvidenceIsSuggestedAndTheReasonIsTheEvidence() {
        val evidence = SkillEvidence.from(all(CanDo.Yes) + answers(Skill.Setting, CanDo.NotYet, CanDo.NotYet, CanDo.Sometimes, CanDo.Yes) +
            answers(Skill.Blocking, CanDo.Sometimes, CanDo.Sometimes, CanDo.Yes, CanDo.Yes), emptyList())
        val suggestions = NextSkills.suggest(evidence)
        assertEquals(listOf(Skill.Setting, Skill.Blocking), suggestions.items.map { it.skill })
        assertEquals("You answered Yes to 1 of 4 statements you could answer.", suggestions.items.first().reason)
        assertFalse(suggestions.fromAi)
        assertTrue(NextSkills.suggest(SkillEvidence.from(emptyMap(), emptyList())).items.isEmpty())
    }

    @Test fun aFullTieDoesNotAlwaysGoToTheSameSkill() {
        val tied = SkillEvidence.from(all(CanDo.Sometimes), emptyList())
        val winners = (0..4).map { turn -> NextSkills.rank(tied.map { it.copy(scoredSessions = if (it.skill == Skill.Serving) turn else 0, measuredScore = null) })
            .first { it.skill != Skill.Serving }.skill }
        assertTrue("$winners", winners.distinct().size >= 3)
    }

    @Test fun positionNeverChangesWhatIsSuggested() {
        val assessment = SkillAssessment(answers = all(CanDo.Yes) + answers(Skill.Blocking, CanDo.NotYet, CanDo.NotYet, CanDo.NotYet, CanDo.NotYet))
        val picks = (CourtPosition.entries + null).map { SkillRecommendation.from(assessment, AthleteProfile(position = it), emptyList())?.skill }
        assertEquals(setOf(Skill.Blocking), picks.toSet())
    }

    @Test fun answersAndChosenSkillsSurviveTheCloudFormAndOldAccountsStayUnchanged() {
        val saved = SavedTrainingSetup(assessment = SkillAssessment(answers = all(CanDo.NotTried), focus = listOf(Skill.Setting, Skill.Serving)))
        assertEquals(saved, TrainingSetupCloudSchema.read(TrainingSetupCloudSchema.fields(saved)))
        @Suppress("UNCHECKED_CAST")
        val old = TrainingSetupCloudSchema.fields(SavedTrainingSetup(assessment = SkillAssessment(mapOf(Skill.Serving to SelfRating.Okay))))["assessment"] as Map<String, Any?>
        assertEquals(setOf("ratings", "experience", "completedAtEpochMs"), old.keys)
    }
}
