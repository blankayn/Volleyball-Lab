package com.volleyballlab.player.data.account

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class LocalAccountCleanupTest {
    @Test fun clearingAccountKeepsOtherUsersAndUnscopedPreferences() = runTest {
        val flow = MutableStateFlow<Preferences>(preferencesOf(
            stringPreferencesKey("name:owner") to "Delete me",
            booleanPreferencesKey("voice:owner") to true,
            stringPreferencesKey("name:other") to "Keep me",
            stringPreferencesKey("name:prefix-owner") to "Keep prefix",
            stringPreferencesKey("global") to "Keep global",
        ))
        val store = object : DataStore<Preferences> {
            override val data = flow
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                flow.value = transform(flow.value); return flow.value
            }
        }
        clearAccountPreferences(store, "owner")
        assertNull(flow.value[stringPreferencesKey("name:owner")])
        assertNull(flow.value[booleanPreferencesKey("voice:owner")])
        assertEquals("Keep me", flow.value[stringPreferencesKey("name:other")])
        assertEquals("Keep prefix", flow.value[stringPreferencesKey("name:prefix-owner")])
        assertEquals("Keep global", flow.value[stringPreferencesKey("global")])
    }
}
