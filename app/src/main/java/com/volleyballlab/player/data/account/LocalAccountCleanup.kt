package com.volleyballlab.player.data.account

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit

internal suspend fun clearAccountPreferences(store: DataStore<Preferences>, uid: String) {
    store.edit { preferences ->
        preferences.asMap().keys.filter { it.name.endsWith(":$uid") }.forEach { preferences.remove(it) }
    }
}

internal suspend fun clearLocalAccount(context: Context, uid: String) {
    com.volleyballlab.player.data.profile.clearProfileAccount(context, uid)
    com.volleyballlab.player.data.session.clearSessionAccount(context, uid)
    com.volleyballlab.player.data.assessment.clearAssessmentAccount(context, uid)
    com.volleyballlab.player.data.coaching.clearCoachingAccount(context, uid)
    com.volleyballlab.player.data.drill.clearDrillAccount(context, uid)
    com.volleyballlab.player.data.notifications.clearNotificationsAccount(context, uid)
    com.volleyballlab.player.data.training.clearTrainingAccount(context, uid)
    com.volleyballlab.player.data.role.clearRoleAccount(context, uid)
    com.volleyballlab.player.data.plan.clearPlanAccount(context, uid)
    com.volleyballlab.player.data.coach.clearCoachAccount(context, uid)
}
