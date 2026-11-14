package com.volleyballlab.player.data.ai

import com.google.firebase.functions.FirebaseFunctions
import com.volleyballlab.player.BuildConfig
import com.volleyballlab.player.data.cloud.awaitCloud
import com.volleyballlab.player.domain.model.*
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.withTimeout

interface TrainingSetupPersonalizer {
    suspend fun nextCard(answers: SetupAnswers): SetupFollowUp
    suspend fun summary(answers: SetupAnswers): SetupSuggestion
}

/** Callable SDK sends authentication only to our service; the provider receives setup answers only. */
class CloudTrainingSetupRepository : TrainingSetupPersonalizer {
    override suspend fun nextCard(answers: SetupAnswers) = TrainingSetupDecoder.followUp(call("next_card", answers), answers)
    override suspend fun summary(answers: SetupAnswers) = TrainingSetupDecoder.suggestion(call("summary", answers), answers)
    private suspend fun call(mode: String, answers: SetupAnswers): Any? = withTimeout(8_000) {
        val endpoint = URI(BuildConfig.SESSION_AI_URL.takeIf { it.isNotBlank() } ?: error("AI unavailable"))
            .resolve("/personalizeTrainingSetup").toURL()
        val callable = FirebaseFunctions.getInstance("asia-southeast1").getHttpsCallableFromUrl(endpoint)
        callable.setTimeout(8, TimeUnit.SECONDS)
        callable.call(mapOf("version" to 1, "mode" to mode, "answers" to mapOf(
            "goalSkill" to (answers.goalSkill?.name ?: "NotSure"), "experience" to answers.experience.name,
            "note" to answers.note, "followUpQuestionId" to answers.followUpQuestionId,
            "followUpOptionId" to answers.followUpOptionId, "weeklySessionGoal" to answers.weeklySessionGoal,
        ))).awaitCloud().data
    }
}

internal object TrainingSetupDecoder {
    private fun map(value: Any?, status: String): Map<*, *> = requireNotNull(value as? Map<*, *>).also {
        require(it["status"] == status && it["source"] in setOf("groq", "local"))
    }
    private fun text(value: Any?, max: Int) = requireNotNull(value as? String).trim().also {
        require(it.isNotBlank() && it.length <= max && it.none { char -> char.isISOControl() })
    }
    private fun focus(row: Map<*, *>, answers: SetupAnswers) = requireNotNull(Skill.All.firstOrNull { it.name == row["focusSkill"] }).also {
        require(answers.goalSkill == null || it == answers.goalSkill)
        answers.followUpQuestionId?.takeIf { id -> id != "general_focus" }?.let { id ->
            val question = requireNotNull(TrainingSetupQuestions.question(id))
            require(TrainingSetupQuestions.skillForQuestion(question.id) == it)
        }
        if (answers.followUpQuestionId == "general_focus" && answers.followUpOptionId in Skill.All.map(Skill::name)) require(it.name == answers.followUpOptionId)
    }
    fun followUp(value: Any?, answers: SetupAnswers): SetupFollowUp {
        val row = map(value, "question")
        require(row.keys == setOf("status", "source", "focusSkill", "questionId", "acknowledgment"))
        val skill = focus(row, answers)
        val question = requireNotNull(TrainingSetupQuestions.question(row["questionId"] as? String ?: ""))
        require(TrainingSetupQuestions.skillForQuestion(question.id) == skill || (answers.goalSkill == null && question.id == "general_focus"))
        return SetupFollowUp(skill, question.id, text(row["acknowledgment"], 160), row["source"] as String)
    }
    fun suggestion(value: Any?, answers: SetupAnswers): SetupSuggestion {
        val row = map(value, "summary")
        require(row.keys == setOf("status", "source", "focusSkill", "summary", "reason"))
        return SetupSuggestion(focus(row, answers), text(row["summary"], 240), text(row["reason"], 160), row["source"] as String)
    }
}
