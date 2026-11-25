package com.volleyballlab.player.feature.coachmode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.volleyballlab.player.VolleyballLabApplication
import com.volleyballlab.player.domain.model.UserPlan

/** The signed-in coach's plan. Free until the entitlement has been read. */
@Composable
internal fun rememberCoachPlan(): UserPlan {
    val context = LocalContext.current
    val repository = remember(context) { (context.applicationContext as VolleyballLabApplication).container.planRepository }
    val plan by repository.plan.collectAsStateWithLifecycle(UserPlan.Free)
    return plan
}
