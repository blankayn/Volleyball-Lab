package com.volleyballlab.player.data.assessment

import com.volleyballlab.player.domain.model.*
import com.volleyballlab.player.domain.repository.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

interface ScopedAssessmentStore {
    fun assessmentFor(uid: String): Flow<SkillAssessment>
    fun hasOnboardedFor(uid: String): Flow<Boolean>
    suspend fun saveFor(uid: String, assessment: SkillAssessment)
    suspend fun skipFor(uid: String)
}

/** Restores private setup through the profile; legacy explicit ratings remain a local cache. */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncedAssessmentRepository(
    private val local: ScopedAssessmentStore,
    private val profile: ProfileRepository,
    private val auth: AuthRepository,
) : AssessmentRepository {
    override val trainingSetup: Flow<TrainingSetup?> = auth.currentUser.flatMapLatest { user ->
        if (user == null) flowOf(null) else profile.profile.map {
            if (auth.currentUid == user.uid) profile.current().trainingSetup?.plan else null
        }
    }
    override val assessment: Flow<SkillAssessment> = auth.currentUser.flatMapLatest { user ->
        if (user == null) flowOf(SkillAssessment.Unanswered) else
            combine(local.assessmentFor(user.uid), profile.profile) { cached, athlete ->
                val remote = profile.current().trainingSetup?.assessment
                if (auth.currentUid != user.uid) SkillAssessment.Unanswered else merge(cached, remote)
            }
    }
    override val hasOnboarded: Flow<Boolean> = auth.currentUser.flatMapLatest { user ->
        if (user == null) flowOf(false) else flow {
            // No false is emitted until a bounded initial restore has completed.
            if (!local.hasOnboardedFor(user.uid).first()) profile.awaitInitialRestore()
            if (auth.currentUid != user.uid) return@flow
            emitAll(combine(local.hasOnboardedFor(user.uid), profile.profile) { seen, athlete ->
                auth.currentUid == user.uid && (seen || profile.current().trainingSetup?.hasOnboarded == true)
            })
        }
    }
    override suspend fun currentHasOnboarded(): Boolean {
        val uid = auth.currentUid ?: return false
        if (local.hasOnboardedFor(uid).first()) return true
        profile.awaitInitialRestore()
        requireAccount(uid)
        val state = profile.current().trainingSetup ?: return false
        cache(uid, state)
        return state.hasOnboarded
    }
    override suspend fun saveTrainingSetup(setup: TrainingSetup) {
        val uid = auth.currentUid ?: throw IllegalStateException("Sign in to save your plan.")
        profile.awaitInitialRestore()
        requireAccount(uid)
        val existing = merge(local.assessmentFor(uid).first(), profile.current().trainingSetup?.assessment)
        val answers = existing.copy(experience = setup.experience ?: existing.experience,
            completedAtEpochMs = setup.completedAtEpochMs)
        requireAccount(uid)
        profile.update { athlete ->
            requireAccount(uid)
            athlete.copy(goalSkill = setup.focusSkill, weeklySessionGoal = setup.weeklySessionGoal,
                goalText = setup.goalText, trainingSetup = SavedTrainingSetup(assessment = answers, plan = setup))
        }
        requireAccount(uid)
        local.saveFor(uid, answers)
    }
    override suspend fun save(assessment: SkillAssessment) {
        val uid = auth.currentUid ?: return
        profile.awaitInitialRestore()
        requireAccount(uid)
        profile.update { athlete ->
            requireAccount(uid)
            athlete.copy(trainingSetup = SavedTrainingSetup(assessment = assessment, plan = athlete.trainingSetup?.plan))
        }
        requireAccount(uid)
        local.saveFor(uid, assessment)
    }
    override suspend fun skip() {
        val uid = auth.currentUid ?: return
        profile.awaitInitialRestore()
        requireAccount(uid)
        val answers = merge(local.assessmentFor(uid).first(), profile.current().trainingSetup?.assessment)
        profile.update { athlete ->
            requireAccount(uid)
            // Retaking then skipping must not replace the confirmed plan or goals.
            if (athlete.trainingSetup?.hasOnboarded == true) athlete else
                athlete.copy(trainingSetup = SavedTrainingSetup(assessment = answers))
        }
        requireAccount(uid)
        local.skipFor(uid)
    }
    private suspend fun cache(uid: String, state: SavedTrainingSetup) {
        requireAccount(uid)
        val answers = merge(local.assessmentFor(uid).first(), state.assessment)
        requireAccount(uid)
        if (answers != SkillAssessment.Unanswered) local.saveFor(uid, answers) else local.skipFor(uid)
    }
    private fun requireAccount(uid: String) {
        if (auth.currentUid != uid) throw CancellationException("Account changed during setup.")
    }
    private fun merge(cached: SkillAssessment, remote: SkillAssessment?): SkillAssessment {
        if (remote == null) return cached
        return remote.copy(ratings = cached.ratings + remote.ratings,
            experience = remote.experience ?: cached.experience)
    }
}
