package com.volleyballlab.player.data.role

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.volleyballlab.player.data.UserScope
import com.volleyballlab.player.domain.model.UserRole
import com.volleyballlab.player.domain.repository.AuthRepository
import com.volleyballlab.player.domain.repository.RoleRepository
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/** One DataStore per file for the process, as the API requires. */
private val Context.roleStore: DataStore<Preferences> by preferencesDataStore(name = "user_role")

/**
 * The account's role, on this device, per uid.
 *
 * ## Its own file rather than a key in the profile store
 *
 * Startup reads this before anything else - it decides which navigation graph is
 * even built - and the profile store is comparatively large and rewritten on every
 * name edit. A one-key file is a cheaper read on the cold-start path, and it keeps
 * a corrupted profile from being able to strand a coach in the player shell.
 *
 * ## Scoped, like everything else
 *
 * Via [UserScope], for the reason that type exists: a phone handed round a team
 * must not have the coach's role follow whoever signs in next. With no session
 * there is no scope, reads give [UserRole.Player] and writes are dropped.
 */
class DataStoreRoleRepository(
    private val context: Context,
    private val authRepository: AuthRepository,
) : RoleRepository {

    override val role: Flow<UserRole> = combine(
        authRepository.currentUser,
        context.roleStore.data.catch { failure ->
            // An unreadable role file must not take the app down on launch. The
            // account lands in the player shell and the switch in Profile is right
            // there, which is a recoverable state; a crash loop is not.
            if (failure is IOException) emit(emptyPreferences()) else throw failure
        },
    ) { user, preferences ->
        val scope = UserScope.of(user?.uid) ?: return@combine UserRole.Player
        UserRole.fromNameOrPlayer(preferences[scope.string(KEY_ROLE)])
    }

    override suspend fun current(): UserRole = role.first()

    override suspend fun setRole(role: UserRole) {
        val scope = UserScope.of(authRepository.currentUid) ?: return
        context.roleStore.edit { preferences ->
            preferences[scope.string(KEY_ROLE)] = role.name
        }
    }

    private companion object {
        const val KEY_ROLE = "role"
    }
}
