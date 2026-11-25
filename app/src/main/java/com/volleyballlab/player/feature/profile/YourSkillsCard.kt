package com.volleyballlab.player.feature.profile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.volleyballlab.player.R
import com.volleyballlab.player.VolleyballLabApplication
import com.volleyballlab.player.domain.model.AthleteState
import com.volleyballlab.player.domain.model.SkillAssessment
import com.volleyballlab.player.domain.model.SkillEvidence
import com.volleyballlab.player.feature.assessment.AssessmentSummary
import com.volleyballlab.player.ui.component.SkillEvidenceBars

/** All five skills and the whole assessment. Home shows only this week's plan. */
@Composable
internal fun YourSkillsCard(onAssessment: () -> Unit) {
    val graph = (LocalContext.current.applicationContext as VolleyballLabApplication).container
    val athlete by graph.athleteAssessmentRepository.state.collectAsStateWithLifecycle(null as AthleteState?)
    val assessment by graph.assessmentRepository.assessment.collectAsStateWithLifecycle(null as SkillAssessment?)
    val sessions by graph.sessionRepository.sessions.collectAsStateWithLifecycle(emptyList())
    val evidence = assessment?.let { known -> remember(known, sessions) { SkillEvidence.from(known.answers, sessions) } }
    val started = evidence?.any { it.strength != null } == true
    Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.your_skills_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (started) SkillEvidenceBars(evidence.orEmpty())
            else Text(stringResource(R.string.assessment_start_body), style = MaterialTheme.typography.bodyMedium)
            athlete?.plan?.let { AssessmentSummary(it, athlete?.answers) }
            OutlinedButton(onClick = onAssessment, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(if (athlete?.answers != null) R.string.assessment_retake else R.string.assessment_start))
            }
            ProfileSyncCard(Modifier.fillMaxWidth())
        }
    }
}
