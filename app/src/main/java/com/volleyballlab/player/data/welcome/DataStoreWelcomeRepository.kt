package com.volleyballlab.player.data.welcome

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.volleyballlab.player.domain.repository.WelcomeRepository
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.welcomeStore: DataStore<Preferences> by preferencesDataStore(name = "welcome")

/**
 * Install-level, deliberately not account-scoped.
 *
 * The welcome explains the product before anyone has an account. Signing out must
 * not replay it, and clearing one account must not make the next person sit
 * through it again on the same phone.
 */
class DataStoreWelcomeRepository(private val context: Context) : WelcomeRepository {

    override val hasSeenWelcome: Flow<Boolean> = context.welcomeStore.data
        .catch { failure ->
            if (failure is IOException) emit(emptyPreferences()) else throw failure
        }
        .map { it[KEY_SEEN] == true }

    override suspend fun markWelcomeSeen() {
        context.welcomeStore.edit { it[KEY_SEEN] = true }
    }

    private companion object {
        val KEY_SEEN = booleanPreferencesKey("seen")
    }
}
