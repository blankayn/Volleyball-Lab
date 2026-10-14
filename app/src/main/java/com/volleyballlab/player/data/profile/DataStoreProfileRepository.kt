package com.volleyballlab.player.data.profile

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.volleyballlab.player.data.UserScope
import com.volleyballlab.player.domain.model.AthleteProfile
import com.volleyballlab.player.domain.model.CourtPosition
import com.volleyballlab.player.domain.model.DominantHand
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.domain.model.ProfileAddress
import com.volleyballlab.player.domain.repository.AuthRepository
import com.volleyballlab.player.domain.repository.ProfileRepository
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

/**
 * Single DataStore instance for the process, as the API requires.
 *
 * Creating two `DataStore`s over one file throws at runtime, so this is a
 * top-level delegate rather than something constructed per repository.
 */
private val Context.athleteProfileStore: DataStore<Preferences> by preferencesDataStore(
    name = "athlete_profile",
)

/**
 * [ProfileRepository] backed by Preferences DataStore, scoped to the signed-in
 * athlete.
 *
 * ## Why every key carries a uid
 *
 * This store was originally keyed by name alone, which made it a property of the
 * *phone* rather than of the athlete: sign out, sign in as someone else, and their
 * profile was your profile. See [UserScope] for the full account. With no session
 * there is no scope, so reads give [AthleteProfile.Anonymous] and writes are
 * dropped - there is nobody for them to belong to.
 *
 * Preferences rather than Proto: three optional scalars do not justify a schema
 * definition and a code generation step, and the migration risk that would buy is
 * not worth avoiding for a name and two enums.
 *
 * Enums are stored by [Enum.name]. That makes the constant names a wire format -
 * see the note on [CourtPosition] - and it is why reading one back is tolerant:
 * an unrecognised value becomes null rather than throwing, so a profile written by
 * a later version of the app degrades to "not set" instead of crashing this one
 * on launch.
 */
class DataStoreProfileRepository(
    private val context: Context,
    private val authRepository: AuthRepository,
) : ProfileRepository, ScopedProfileStore {

    override val profile: Flow<AthleteProfile> = combine(
        // Re-reads on every sign-in and sign-out, so switching accounts switches
        // the profile rather than leaving the previous athlete's on screen.
        authRepository.currentUser,
        context.athleteProfileStore.data
            // A corrupt or unreadable file must not take the app down on a screen
            // the athlete only opened to change their name. An unreadable profile
            // is an empty profile.
            .catch { failure ->
                if (failure is IOException) emit(emptyPreferences()) else throw failure
            },
    ) { user, preferences ->
        val scope = UserScope.of(user?.uid) ?: return@combine AthleteProfile.Anonymous
        AthleteProfile(
            name = preferences[scope.string(KEY_NAME)].orEmpty(),
            position = preferences[scope.string(KEY_POSITION)]?.toEnumOrNull<CourtPosition>(),
            dominantHand = preferences[scope.string(KEY_HAND)]?.toEnumOrNull<DominantHand>(),
            goalSkill = preferences[scope.string("goal_skill")]?.toEnumOrNull<Skill>(),
            weeklySessionGoal = preferences[scope.string("weekly_goal")]?.toIntOrNull()?.coerceIn(1, 14) ?: 3,
            goalText = preferences[scope.string("goal_text")].orEmpty(),
            photoPath = preferences[scope.string("photo_path")],
            firstName = preferences[scope.string("first_name")].orEmpty(),
            lastName = preferences[scope.string("last_name")].orEmpty(),
            address = preferences[scope.string("address")]?.toAddressOrNull(),
        )
    }

    override suspend fun save(profile: AthleteProfile) = update { profile }

    override suspend fun update(transform: (AthleteProfile) -> AthleteProfile) {
        val uid = authRepository.currentUid ?: return
        updateFor(uid, transform)
    }

    override fun profileFor(uid: String): Flow<AthleteProfile> = context.athleteProfileStore.data
        .catch { failure -> if (failure is IOException) emit(emptyPreferences()) else throw failure }
        .map { preferences ->
            val scope = requireNotNull(UserScope.of(uid))
            AthleteProfile(
                name = preferences[scope.string(KEY_NAME)].orEmpty(),
                position = preferences[scope.string(KEY_POSITION)]?.toEnumOrNull<CourtPosition>(),
                dominantHand = preferences[scope.string(KEY_HAND)]?.toEnumOrNull<DominantHand>(),
                goalSkill = preferences[scope.string("goal_skill")]?.toEnumOrNull<Skill>(),
                weeklySessionGoal = preferences[scope.string("weekly_goal")]?.toIntOrNull()?.coerceIn(1, 14) ?: 3,
                goalText = preferences[scope.string("goal_text")].orEmpty(),
                photoPath = preferences[scope.string("photo_path")],
                firstName = preferences[scope.string("first_name")].orEmpty(),
                lastName = preferences[scope.string("last_name")].orEmpty(),
                address = preferences[scope.string("address")]?.toAddressOrNull(),
            )
        }

    override suspend fun updateFor(uid: String, transform: (AthleteProfile) -> AthleteProfile) {
        val scope = requireNotNull(UserScope.of(uid))
        context.athleteProfileStore.edit { preferences ->
            val profile = transform(AthleteProfile(
                name = preferences[scope.string(KEY_NAME)].orEmpty(),
                position = preferences[scope.string(KEY_POSITION)]?.toEnumOrNull<CourtPosition>(),
                dominantHand = preferences[scope.string(KEY_HAND)]?.toEnumOrNull<DominantHand>(),
                goalSkill = preferences[scope.string("goal_skill")]?.toEnumOrNull<Skill>(),
                weeklySessionGoal = preferences[scope.string("weekly_goal")]?.toIntOrNull()?.coerceIn(1, 14) ?: 3,
                goalText = preferences[scope.string("goal_text")].orEmpty(),
                photoPath = preferences[scope.string("photo_path")],
                firstName = preferences[scope.string("first_name")].orEmpty(),
                lastName = preferences[scope.string("last_name")].orEmpty(),
                address = preferences[scope.string("address")]?.toAddressOrNull(),
            ))
            val name = profile.name.trim().take(AthleteProfile.MAX_NAME_LENGTH)
            if (name.isEmpty()) {
                preferences.remove(scope.string(KEY_NAME))
            } else {
                preferences[scope.string(KEY_NAME)] = name
            }

            // Cleared fields are removed rather than written as an empty string,
            // so "never set" and "set then cleared" are the same state on disk.
            profile.position
                ?.let { preferences[scope.string(KEY_POSITION)] = it.name }
                ?: preferences.remove(scope.string(KEY_POSITION))
            profile.dominantHand
                ?.let { preferences[scope.string(KEY_HAND)] = it.name }
                ?: preferences.remove(scope.string(KEY_HAND))
            profile.goalSkill?.let { preferences[scope.string("goal_skill")] = it.name }
                ?: preferences.remove(scope.string("goal_skill"))
            preferences[scope.string("weekly_goal")] = profile.weeklySessionGoal.coerceIn(1, 14).toString()
            preferences[scope.string("goal_text")] = profile.goalText.trim().take(160)
            profile.photoPath?.let { preferences[scope.string("photo_path")] = it }
                ?: preferences.remove(scope.string("photo_path"))
            profile.firstName.trim().take(AthleteProfile.MAX_NAME_PART_LENGTH).let {
                if (it.isEmpty()) preferences.remove(scope.string("first_name"))
                else preferences[scope.string("first_name")] = it
            }
            profile.lastName.trim().take(AthleteProfile.MAX_NAME_PART_LENGTH).let {
                if (it.isEmpty()) preferences.remove(scope.string("last_name"))
                else preferences[scope.string("last_name")] = it
            }
            profile.address?.let { preferences[scope.string("address")] = Json.encodeToString(it) }
                ?: preferences.remove(scope.string("address"))
        }
    }

    private companion object {
        const val KEY_NAME = "name"
        const val KEY_POSITION = "position"
        const val KEY_HAND = "dominant_hand"
    }
}

private inline fun <reified T : Enum<T>> String.toEnumOrNull(): T? =
    enumValues<T>().firstOrNull { it.name == this }

private val AddressJson = Json { ignoreUnknownKeys = true }
private fun String.toAddressOrNull(): ProfileAddress? =
    runCatching { AddressJson.decodeFromString<ProfileAddress>(this) }.getOrNull()

internal suspend fun clearProfileAccount(context: Context, uid: String) =
    com.volleyballlab.player.data.account.clearAccountPreferences(context.athleteProfileStore, uid)
