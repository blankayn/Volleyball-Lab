package com.volleyballlab.player.data.assessment

import com.volleyballlab.player.di.AppContainer
import com.volleyballlab.player.domain.model.AdaptiveMemory
import com.volleyballlab.player.domain.model.Drill
import com.volleyballlab.player.domain.model.DrillPracticeRecord
import com.volleyballlab.player.domain.model.RepMark
import com.volleyballlab.player.domain.model.SessionResult
import com.volleyballlab.player.domain.model.Skill
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal class ContainerPlannerSources(private val container: AppContainer) : PlannerSources {
    override val uid: String? get() = container.authRepository.currentUid
    override suspend fun sessions(): List<SessionResult> = container.sessionRepository.sessions.first()
    override suspend fun memory(): AdaptiveMemory = container.adaptiveCoachRepository.memory.first()
    /** The first value is an empty start, so a moment is allowed for the real one. */
    override suspend fun marks(): List<RepMark> = withTimeoutOrNull(1_500) { container.adaptiveCoachRepository.marks.first { it.isNotEmpty() } }.orEmpty()
    override suspend fun workouts(): List<DrillPracticeRecord> = container.drillPracticeRepository.history.first()
    override suspend fun openDrills(): List<Drill> {
        val entitlement = withTimeoutOrNull(3_000) { container.drillAccessController.entitlement.first { it.resolved } }
            ?: container.drillAccessController.entitlement.first()
        return Skill.All.flatMap { container.drillRepository.drillsFor(it) }.filter(entitlement::canStart)
    }
    override suspend fun onPlanned(uid: String, focus: List<Skill>) = container.assessmentRepository.applyFocus(uid, focus)
}
