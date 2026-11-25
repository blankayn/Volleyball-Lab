package com.volleyballlab.player.feature.train.result

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.volleyballlab.player.R
import com.volleyballlab.player.VolleyballLabApplication
import com.volleyballlab.player.domain.model.AdaptiveCoaching
import com.volleyballlab.player.domain.model.AdaptiveMemory
import com.volleyballlab.player.domain.model.CoachAssessment
import com.volleyballlab.player.domain.model.Drill
import com.volleyballlab.player.domain.model.NextStep
import com.volleyballlab.player.domain.model.NextStepAdvisor
import com.volleyballlab.player.domain.model.NextStepReason
import com.volleyballlab.player.domain.model.RepMark
import com.volleyballlab.player.domain.model.SessionResult
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.domain.model.SkillAssessment
import com.volleyballlab.player.domain.model.SkillEvidence
import com.volleyballlab.player.data.drill.DrillEntitlement
import com.volleyballlab.player.pose.analysis.CoachPhrasebook

/** The suggestion and, when one is known, its advice in the words that helped this player most. */
internal data class NextStepAdvice(val step: NextStep, val instruction: String?)

/** Null until everything it needs has been read, and when there is nothing to suggest. */
@Composable
internal fun rememberNextStep(session: SessionResult, history: List<SessionResult>, assessment: CoachAssessment): NextStepAdvice? {
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as VolleyballLabApplication).container }
    val memory by container.adaptiveCoachRepository.memory.collectAsStateWithLifecycle(null as AdaptiveMemory?)
    val marks by container.adaptiveCoachRepository.marks.collectAsStateWithLifecycle(emptyList<RepMark>())
    val answers by container.assessmentRepository.assessment.collectAsStateWithLifecycle(null as SkillAssessment?)
    val entitlement by container.drillAccessController.entitlement.collectAsStateWithLifecycle(DrillEntitlement())
    val drills by produceState(null as List<Drill>?) {
        value = runCatching { Skill.All.flatMap { container.drillRepository.drillsFor(it) } }.getOrDefault(emptyList())
    }
    val known = memory ?: return null
    val check = answers ?: return null
    val all = drills ?: return null
    return remember(session, history, assessment, known, marks, check, all, entitlement) {
        val upTo = (history.filter { it.id != session.id } + session).filter { it.startedAtEpochMs <= session.startedAtEpochMs }
        val end = session.startedAtEpochMs + session.durationMs + AFTER_MS
        val learned = AdaptiveCoaching.learn(session.skill, upTo, known.copy(misses = known.misses.filter { it.atEpochMs <= end }), marks, session.drillId)
        NextStepAdvisor.suggest(session, history, assessment, learned, SkillEvidence.from(check.answers, upTo), all.filter(entitlement::canStart))?.let { step ->
            val measured = step.cue?.let { cue -> assessment.observedCues.firstOrNull { it.cue.id == cue.id && it.medianMeasured !in cue.target }?.medianMeasured }
            val style = step.cue?.let { known.bestStyle(it.id)?.first }
            NextStepAdvice(step, if (measured != null && style != null) CoachPhrasebook.inStyle(step.cue!!.id, measured, style) else null)
        }
    }
}

/** What to do next in one or two sentences. Null where the report's own correction already says it. */
@Composable
internal fun nextStepText(step: NextStep, practised: Skill, hasCorrection: Boolean): String? {
    val skill = stringResource(practised.displayName)
    return when (step.reason) {
        NextStepReason.OtherSkill -> stringResource(R.string.next_step_other, skill, stringResource(step.skill.displayName))
        NextStepReason.WorkOn -> when {
            step.drill != null -> stringResource(R.string.next_step_drill_for, step.drill.name, stringResource(step.cue!!.label).lowercase())
            hasCorrection -> stringResource(R.string.next_step_same_drill)
            else -> stringResource(R.string.next_step_work_on, skill, stringResource(step.cue!!.label).lowercase())
        }
        NextStepReason.MoveUp -> stringResource(R.string.next_step_move_up, skill, step.drill!!.name)
        NextStepReason.Repeat -> null
    }
}

@Composable
internal fun NextStepButton(step: NextStep, onOpen: (NextStep) -> Unit) {
    val label = when {
        step.drill != null && step.reason != NextStepReason.OtherSkill -> stringResource(R.string.next_step_open_drill, step.drill.name)
        step.reason == NextStepReason.OtherSkill -> stringResource(R.string.next_step_open_skill, stringResource(step.skill.displayName))
        else -> return
    }
    FilledTonalButton({ onOpen(step) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/** Refused attempts are stamped while recording, so a little slack covers the finish. */
private const val AFTER_MS = 120_000L
