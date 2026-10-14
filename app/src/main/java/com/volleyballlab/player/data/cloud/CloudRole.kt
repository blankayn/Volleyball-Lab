package com.volleyballlab.player.data.cloud

import com.google.firebase.firestore.FirebaseFirestore
import com.volleyballlab.player.domain.model.UserRole
import com.volleyballlab.player.domain.repository.AuthRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * The account's role as the cloud knows it — `users/{uid}.role`, written by the
 * coach web portal at registration.
 *
 * Null while the doc does not exist, which is the common case for player
 * accounts (the app never writes `users` rows): null means "the cloud has no
 * opinion", not "player". Callers fall back to the device store, where an
 * explicit in-app switch always wins over this — a deliberate choice on this
 * phone beats a row written somewhere else.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun FirebaseFirestore.cloudRoleOf(auth: AuthRepository): Flow<UserRole?> =
    auth.currentUser.flatMapLatest { user ->
        val uid = user?.uid?.takeIf { it.isNotBlank() } ?: return@flatMapLatest flowOf(null)
        collection(CloudSchema.USERS).document(uid).snapshots()
            .map { CloudSchema.userRoleOf(it?.data) }
    }
