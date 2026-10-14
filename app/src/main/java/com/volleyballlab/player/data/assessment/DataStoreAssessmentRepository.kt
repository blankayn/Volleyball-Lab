package com.volleyballlab.player.data.assessment

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.volleyballlab.player.data.UserScope
import com.volleyballlab.player.domain.model.ExperienceLevel
import com.volleyballlab.player.domain.model.SelfRating
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.domain.model.SkillAssessment
import com.volleyballlab.player.domain.repository.AssessmentRepository
import com.volleyballlab.player.domain.repository.AuthRepository
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** One DataStore per file for the process, as the API requires. */
private val Context.assessmentStore: DataStore<Preferences> by preferencesDataStore(
    name = "skill_assessment",
)

/**
 * [AssessmentRepository] backed by Preferences DataStore.
 *
 * Ratings are stored one key per skill rather than as a single encoded string. It
 * costs five keys instead of one and buys the property that a skill this build does
 * not recognise is simply a key nobody reads, rather than a parse failure that
 * discards the other four answers with it.
 *
 * Enums are stored by [Enum.name] and read back tolerantly, exactly as
 * `DataStoreProfileRepository` does, so a file written by a later version of the app
 * degrades to "not answered" instead of crashing this one during onboarding.
 */
class DataStoreAssessmentRepository(
    private val context: Context,
    private val authRepository: AuthRepository,
) : AssessmentRepository {

    private val preferences: Flow<Preferences> = context.assessmentStore.data
        .catch { failure ->
            if (failure is IOException) emit(emptyPreferences()) else throw failure
        }

    /** Re-reads on sign-in and sign-out, so switching accounts switches answers. */
    private val scoped: Flow<Pair<UserScope?, Preferences>> =
        combine(authRepository.currentUser, preferences) { user, stored ->
            UserScope.of(user?.uid) to stored
        }

    override val assessment: Flow<SkillAssessment> = scoped.map { (scope, stored) ->
        if (scope == null) return@map SkillAssessment.Unanswered
        SkillAssessment(
            ratings = Skill.All.mapNotNull { skill ->
                val rating = stored[scope.string(ratingKey(skill))]?.toEnumOrNull<SelfRating>()
                rating?.let { skill to it }
            }.toMap(),
            experience = stored[scope.string(KEY_EXPERIENCE)]?.toEnumOrNull<ExperienceLevel>(),
            completedAtEpochMs = stored[scope.long(KEY_COMPLETED_AT)] ?: 0L,
        )
    }

    /**
     * False for an account that has never been through onboarding, which is what
     * sends a newly created Google account to the questionnaire even though this
     * phone has onboarded somebody else before.
     */
    override val hasOnboarded: Flow<Boolean> = scoped.map { (scope, stored) ->
        scope != null && stored[scope.boolean(KEY_ONBOARDED)] == true
    }

    override suspend fun save(assessment: SkillAssessment) {
        val scope = UserScope.of(authRepository.currentUid) ?: return
        context.assessmentStore.edit { stored ->
            Skill.All.forEach { skill ->
                // Cleared answers are removed rather than written as a default, so
                // "never answered" and "answered then cleared on a retake" are the
                // same state on disk. Without this, retaking and skipping a skill
                // would leave the previous answer behind and silently keep driving
                // the recommendation.
                assessment.ratings[skill]
                    ?.let { stored[scope.string(ratingKey(skill))] = it.name }
                    ?: stored.remove(scope.string(ratingKey(skill)))
            }
            assessment.experience
                ?.let { stored[scope.string(KEY_EXPERIENCE)] = it.name }
                ?: stored.remove(scope.string(KEY_EXPERIENCE))
            stored[scope.long(KEY_COMPLETED_AT)] = assessment.completedAtEpochMs
            stored[scope.boolean(KEY_ONBOARDED)] = true
        }
    }

    override suspend fun skip() {
        val scope = UserScope.of(authRepository.currentUid) ?: return
        context.assessmentStore.edit { stored -> stored[scope.boolean(KEY_ONBOARDED)] = true }
    }

    private companion object {
        const val KEY_ONBOARDED = "onboarded"
        const val KEY_EXPERIENCE = "experience"
        const val KEY_COMPLETED_AT = "completed_at"

        fun ratingKey(skill: Skill) = "rating_${skill.name}"
    }
}

private inline fun <reified T : Enum<T>> String.toEnumOrNull(): T? =
    enumValues<T>().firstOrNull { it.name == this }

internal suspend fun clearAssessmentAccount(context: Context, uid: String) =
    com.volleyballlab.player.data.account.clearAccountPreferences(context.assessmentStore, uid)
