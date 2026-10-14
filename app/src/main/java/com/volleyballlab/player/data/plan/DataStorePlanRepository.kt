package com.volleyballlab.player.data.plan

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.volleyballlab.player.data.UserScope
import com.volleyballlab.player.domain.model.UserPlan
import com.volleyballlab.player.domain.repository.AuthRepository
import com.volleyballlab.player.domain.repository.PlanRepository
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine

/** One DataStore per file for the process, as the API requires. */
private val Context.planStore: DataStore<Preferences> by preferencesDataStore(name = "user_plan")

/**
 * The account's tier, on this device, per uid.
 *
 * ## Scoped, like everything else
 *
 * Via [UserScope], for the reason that type exists: a phone handed round a team
 * must not have one athlete's Pro plan follow whoever signs in next. With no
 * session there is no scope, reads give [UserPlan.Free] and writes are dropped.
 *
 * ## Its own file
 *
 * Rather than a key in the profile store, because the two have completely
 * different write patterns - a plan changes approximately never, a profile changes
 * whenever someone edits their name - and because a corrupt profile must not be
 * able to revoke a plan.
 *
 * ## This is not entitlement, and the difference matters
 *
 * A flag in local storage is a *record* of an upgrade, not proof of one. Anyone
 * who can write to the app's data directory can set it. That is fine for a
 * prototype whose upgrade button is itself a mockup, and it stops being fine the
 * day money changes hands - at which point the source of truth has to move to the
 * billing library and the server, and this becomes a cache of their answer.
 */
class DataStorePlanRepository(
    private val context: Context,
    private val authRepository: AuthRepository,
) : PlanRepository {

    override val plan: Flow<UserPlan> = combine(
        authRepository.currentUser,
        context.planStore.data.catch { failure ->
            // An unreadable plan file must not take the app down. The account reads
            // as Free, which is recoverable in one tap; a crash loop is not.
            if (failure is IOException) emit(emptyPreferences()) else throw failure
        },
    ) { user, preferences ->
        val scope = UserScope.of(user?.uid) ?: return@combine UserPlan.Free
        UserPlan.fromNameOrFree(preferences[scope.string(KEY_PLAN)])
    }

    override suspend fun upgradeToPro() = write(UserPlan.Pro)

    override suspend fun downgradeToFree() = write(UserPlan.Free)

    private suspend fun write(plan: UserPlan) {
        val scope = UserScope.of(authRepository.currentUid) ?: return
        context.planStore.edit { preferences ->
            preferences[scope.string(KEY_PLAN)] = plan.name
        }
    }

    private companion object {
        const val KEY_PLAN = "plan"
    }
}

internal suspend fun clearPlanAccount(context: Context, uid: String) =
    com.volleyballlab.player.data.account.clearAccountPreferences(context.planStore, uid)
