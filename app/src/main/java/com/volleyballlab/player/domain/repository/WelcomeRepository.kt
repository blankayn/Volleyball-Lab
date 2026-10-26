package com.volleyballlab.player.domain.repository

import kotlinx.coroutines.flow.Flow

/** Install-level onboarding shown before the first sign-in. */
interface WelcomeRepository {
    val hasSeenWelcome: Flow<Boolean>

    suspend fun markWelcomeSeen()
}
