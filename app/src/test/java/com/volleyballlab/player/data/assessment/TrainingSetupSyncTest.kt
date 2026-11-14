package com.volleyballlab.player.data.assessment

import com.volleyballlab.player.data.profile.*
import com.volleyballlab.player.domain.model.*
import com.volleyballlab.player.domain.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrainingSetupSyncTest {
    private class Auth : AuthRepository {
        override val currentUser = MutableStateFlow<AuthUser?>(AuthUser("a", "a@example.com", "A"))
        override val currentUid get() = currentUser.value?.uid
        override suspend fun restoreSession() = currentUser.value
        override suspend fun signIn(email: String, password: String, rememberSession: Boolean) = AuthOutcome.Success
        override suspend fun signUp(name: String, email: String, password: String) = AuthOutcome.Success
        override suspend fun signInWithGoogle(idToken: String, rememberSession: Boolean) = AuthOutcome.Success
        override suspend fun sendPasswordReset(email: String) = AuthOutcome.Success
        override suspend fun signOut() { currentUser.value = null }
    }
    private class Local : ScopedAssessmentStore {
        val answers = mutableMapOf<String, MutableStateFlow<SkillAssessment>>()
        val seen = mutableMapOf<String, MutableStateFlow<Boolean>>()
        override fun assessmentFor(uid: String) = answers.getOrPut(uid) { MutableStateFlow(SkillAssessment.Unanswered) }
        override fun hasOnboardedFor(uid: String) = seen.getOrPut(uid) { MutableStateFlow(false) }
        override suspend fun saveFor(uid: String, assessment: SkillAssessment) {
            assessmentFor(uid).value = assessment; hasOnboardedFor(uid).value = true
        }
        override suspend fun skipFor(uid: String) { hasOnboardedFor(uid).value = true }
    }
    private class Profiles(private val auth: Auth) : ProfileRepository {
        val values = mutableMapOf<String, AthleteProfile>()
        override val profile = MutableStateFlow(AthleteProfile.Anonymous)
        var restore: suspend () -> Unit = {}
        var updates = 0
        override suspend fun current() = values[auth.currentUid] ?: AthleteProfile.Anonymous
        override suspend fun awaitInitialRestore() = restore()
        override suspend fun save(profile: AthleteProfile) = update { profile }
        override suspend fun update(transform: (AthleteProfile) -> AthleteProfile) {
            val uid = auth.currentUid ?: return
            values[uid] = transform(current()); profile.value = values.getValue(uid); updates++
        }
    }
    private fun plan() = TrainingSetup(Skill.Serving, 3, "Keep my serves in", ExperienceLevel.New,
        "Aiming", "Start with serving practice.", "groq", "serve-standing-float", 123L,
        reason = "You chose serving.", questionId = "serving", optionId = "aim")

    @Test fun freshInstallWaitsForCloudAndRestoresConfirmedSetup() = runTest {
        val auth = Auth(); val local = Local(); val profiles = Profiles(auth)
        val loaded = CompletableDeferred<Unit>()
        profiles.restore = { loaded.await() }
        val repo = SyncedAssessmentRepository(local, profiles, auth)
        val reading = async { repo.currentHasOnboarded() }; runCurrent()
        assertFalse(reading.isCompleted)
        profiles.values["a"] = AthleteProfile(trainingSetup = SavedTrainingSetup(plan = plan()))
        loaded.complete(Unit)
        assertTrue(reading.await())
        assertTrue(local.hasOnboardedFor("a").value)
        assertEquals(plan(), repo.trainingSetup.first())
    }
    @Test fun confirmationKeepsIdentityAndExplicitRatingsWithoutInventingOthers() = runTest {
        val auth = Auth(); val local = Local(); val profiles = Profiles(auth)
        local.saveFor("a", SkillAssessment(mapOf(Skill.Receiving to SelfRating.Confident), ExperienceLevel.Developing, 20L))
        profiles.values["a"] = AthleteProfile(name = "Player", position = CourtPosition.Libero,
            dominantHand = DominantHand.Left, photoPath = "private.jpg")
        val repo = SyncedAssessmentRepository(local, profiles, auth)
        repo.saveTrainingSetup(plan())
        val saved = profiles.values.getValue("a")
        assertEquals("Player", saved.name); assertEquals("private.jpg", saved.photoPath)
        assertEquals(CourtPosition.Libero, saved.position); assertEquals(DominantHand.Left, saved.dominantHand)
        assertEquals(Skill.Serving, saved.goalSkill); assertEquals(3, saved.weeklySessionGoal)
        assertEquals(mapOf(Skill.Receiving to SelfRating.Confident), saved.trainingSetup?.assessment?.ratings)
        assertEquals(ExperienceLevel.New, saved.trainingSetup?.assessment?.experience)
        repo.skip()
        assertEquals(saved, profiles.values.getValue("a"))
    }
    @Test fun accountChangeDuringRestoreCannotSaveAnotherAccountsPlan() = runTest {
        val auth = Auth(); val profiles = Profiles(auth); val local = Local()
        profiles.restore = { auth.currentUser.value = AuthUser("b", "b@example.com", "B") }
        val repo = SyncedAssessmentRepository(local, profiles, auth)
        try { repo.saveTrainingSetup(plan()); fail("Expected account switch rejection") } catch (_: CancellationException) { }
        assertEquals(0, profiles.updates)
        assertFalse(local.hasOnboardedFor("b").value)
        // A stale A profile StateFlow must never leak to B.
        profiles.profile.value = AthleteProfile(trainingSetup = SavedTrainingSetup(plan = plan()))
        assertNull(repo.trainingSetup.first())
    }
    @Test fun legacyCompletionDoesNotWaitForNetworkOrForceSetupAgain() = runTest {
        val auth = Auth(); val local = Local(); val profiles = Profiles(auth)
        local.skipFor("a")
        profiles.restore = { error("Legacy completion should work offline") }
        assertTrue(SyncedAssessmentRepository(local, profiles, auth).currentHasOnboarded())
    }
    @Test fun schemaRoundTripsPlanAndNeverSeedsDefaultWeeklyGoal() {
        assertFalse(ProfileCloudSchema.seed(AthleteProfile.Anonymous).containsKey("weeklySessionGoal"))
        assertFalse(ProfileCloudSchema.seed(AthleteProfile(goalSkill = Skill.Serving, goalText = "Practice")).containsKey("weeklySessionGoal"))
        val record = SavedTrainingSetup(assessment = SkillAssessment(mapOf(Skill.Setting to SelfRating.Okay), ExperienceLevel.New, 123L), plan = plan())
        assertEquals(record, TrainingSetupCloudSchema.read(TrainingSetupCloudSchema.fields(record)))
        val fresh = ProfileCloudSchema.overlay(AthleteProfile.Anonymous, ProfileCloudSchema.fields(AthleteProfile(
            goalSkill = Skill.Serving, weeklySessionGoal = 5, goalText = "Practice", trainingSetup = record)))
        assertEquals(record, fresh.trainingSetup); assertEquals(5, fresh.weeklySessionGoal)
        assertNull(TrainingSetupCloudSchema.read(mapOf("version" to 99)))
    }
}
