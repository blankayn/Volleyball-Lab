package com.volleyballlab.player.drilltest

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.volleyballlab.player.domain.model.UserPlan
import com.volleyballlab.player.domain.repository.*
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DrillTestAuth(uid: String = "athlete-a") : AuthRepository {
    override val currentUser = MutableStateFlow<AuthUser?>(AuthUser(uid, "$uid@example.com", uid))
    override val currentUid get() = currentUser.value?.uid
    fun switch(uid: String?) { currentUser.value = uid?.let { AuthUser(it, "$it@example.com", it) } }
    override suspend fun restoreSession() = currentUser.value
    override suspend fun signIn(email: String, password: String, rememberSession: Boolean) = AuthOutcome.Success
    override suspend fun signInWithGoogle(idToken: String, rememberSession: Boolean) = AuthOutcome.Success
    override suspend fun signUp(name: String, email: String, password: String) = AuthOutcome.Success
    override suspend fun sendPasswordReset(email: String) = AuthOutcome.Success
    override suspend fun signOut() { switch(null) }
}
class DrillTestPlans(initial: UserPlan = UserPlan.Free) : PlanRepository {
    override val plan = MutableStateFlow(initial)
    override suspend fun upgradeToPro() = Unit
    override suspend fun downgradeToFree() = Unit
}
class DrillTestStore : DataStore<Preferences> {
    override val data = MutableStateFlow(emptyPreferences())
    var failNextWrite = false
    private val mutex = Mutex()
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = mutex.withLock {
        if (failNextWrite) { failNextWrite = false; throw IOException("Disk write failed") }
        transform(data.value).also { data.value = it }
    }
}
