package com.volleyballlab.player.data.ai

import com.volleyballlab.player.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class TrainingSetupDecoderTest {
    private val question = mapOf("status" to "question", "source" to "groq", "focusSkill" to "Serving", "questionId" to "serving_focus", "acknowledgment" to "Let's narrow down your serving goal.")
    @Test fun variantQuestionsResolveToTheirOwnSkills() {
        TrainingSetupQuestions.variants.forEach { q ->
            val skill = requireNotNull(TrainingSetupQuestions.skillForQuestion(q.id))
            val decoded = TrainingSetupDecoder.followUp(question + mapOf("focusSkill" to skill.name, "questionId" to q.id), SetupAnswers(goalSkill = skill))
            assertEquals(skill, decoded.focusSkill)
            val local = TrainingSetupQuestions.localSuggestion(SetupAnswers(followUpQuestionId = q.id, followUpOptionId = "not_sure"))
            assertEquals(skill, local.focusSkill)
        }
    }
    @Test fun approvedQuestionIsDecoded() {
        assertEquals("serving_focus", TrainingSetupDecoder.followUp(question, SetupAnswers(goalSkill = Skill.Serving)).questionId)
    }
    @Test fun explicitGoalCannotBeSilentlyReplaced() {
        assertThrows(IllegalArgumentException::class.java) { TrainingSetupDecoder.followUp(question, SetupAnswers(goalSkill = Skill.Setting)) }
    }
    @Test fun unapprovedQuestionAndExtraScoreFieldsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { TrainingSetupDecoder.followUp(question + ("questionId" to "invented"), SetupAnswers()) }
        assertThrows(IllegalArgumentException::class.java) { TrainingSetupDecoder.followUp(question + ("score" to 80), SetupAnswers()) }
    }
    @Test fun generalAnswerCannotBeOverruledByASummary() {
        assertThrows(IllegalArgumentException::class.java) { TrainingSetupDecoder.suggestion(mapOf("status" to "summary", "source" to "groq", "focusSkill" to "Serving", "summary" to "Start with serving.", "reason" to "Practice first."),
            SetupAnswers(followUpQuestionId = "general_focus", followUpOptionId = "Setting")) }
    }
}
