package com.volleyballlab.player.data.drill

import com.volleyballlab.player.domain.model.*
import com.volleyballlab.player.domain.repository.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withTimeoutOrNull

data class DrillEntitlement(val uid: String? = null, val plan: UserPlan? = null, val resolved: Boolean = false) {
    fun canStart(drill: Drill?) = uid != null && DrillAccessPolicy.canStart(drill, plan)
}

/** The same policy is checked at presentation and at every actual start/resume. */
@OptIn(ExperimentalCoroutinesApi::class)
class DrillAccessController(
    private val auth: AuthRepository,
    private val plans: PlanRepository,
    private val drills: DrillRepository,
) {
    val currentUid: String? get() = auth.currentUid
    val entitlement: Flow<DrillEntitlement> = auth.currentUser.map { it?.uid }.distinctUntilChanged().flatMapLatest { uid ->
        flow {
            emit(DrillEntitlement(uid))
            if (uid != null) plans.plan.collect { plan ->
                if (auth.currentUid == uid) emit(DrillEntitlement(uid, plan, true))
            } else emit(DrillEntitlement(resolved = true))
        }.catch { failure ->
            if (failure is CancellationException) throw failure
            emit(DrillEntitlement(uid, UserPlan.Free, true))
        }
    }

    suspend fun requireAccess(drillId: String): Pair<String, Drill> {
        val uid = auth.currentUid ?: error("Sign in to practice drills.")
        val drill = drills.byId(drillId) ?: error("Drill not found.")
        val plan = if (drill.id in DrillAccessPolicy.starterIds) null else {
            withTimeoutOrNull(10_000) { plans.plan.firstOrNull() }
                ?: error("Pro access could not be verified. Check your connection and retry.")
        }
        check(auth.currentUid == uid) { "Your account changed. Open the drill again." }
        check(DrillAccessPolicy.canStart(drill, plan)) { "This drill requires Pro. Five starter drills are included with Free." }
        return uid to drill
    }
}
