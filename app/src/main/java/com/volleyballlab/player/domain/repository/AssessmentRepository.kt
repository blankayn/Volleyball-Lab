package com.volleyballlab.player.domain.repository

import com.volleyballlab.player.domain.model.SkillAssessment
import com.volleyballlab.player.domain.model.TrainingSetup
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.Flow

/**
 * The onboarding questionnaire's answers.
 *
 * ## Why "answered" and "seen" are separate
 *
 * [hasOnboarded] is not derivable from [assessment]. An athlete who skipped the
 * questionnaire has no ratings, and so does an athlete who has never been shown it -
 * but the first must not be asked again on every launch, and the second must be
 * asked once. Collapsing the two would either nag the first athlete forever or
 * silently never onboard the second.
 *
 * Same shape as [ProfileRepository] for the same reason: when accounts arrive the
 * local store becomes a cache behind this interface rather than something to tear
 * out.
 */
interface AssessmentRepository {

    val trainingSetup: Flow<TrainingSetup?> get() = flowOf(null)
    suspend fun saveTrainingSetup(setup: TrainingSetup) { error("Training setup is unavailable.") }
    suspend fun currentHasOnboarded(): Boolean = hasOnboarded.first()

    /** The stored answers. [SkillAssessment.Unanswered] when there are none. */
    val assessment: Flow<SkillAssessment>

    /** Whether the athlete has been through onboarding, by answering or by skipping. */
    val hasOnboarded: Flow<Boolean>

    /** Saves answers and marks onboarding done. Overwrites, so retaking is a save. */
    suspend fun save(assessment: SkillAssessment)

    /** Marks onboarding done without answers, for an athlete who skipped it. */
    suspend fun skip()
}
