package com.volleyballlab.player.feature.train.skill

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.volleyballlab.player.domain.model.*

@Composable
fun SkillLearningDialog(skill: Skill, onDismiss: () -> Unit) {
    val content = remember(skill) { SkillLearningCatalog.forSkill(skill) }
    val context = LocalContext.current
    var phase by remember { mutableIntStateOf(0) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("${stringResource(skill.displayName)} technique") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(content.overview)
            Text("Posture reference", style = MaterialTheme.typography.labelLarge)
            TechniqueIllustration(skill, phase, Modifier.fillMaxWidth().height(220.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                content.steps.forEachIndexed { index, _ -> FilterChip(phase == index, { phase = index }, label = { Text("${index + 1}") }) }
            }
            Text(content.steps[phase].title, style = MaterialTheme.typography.titleSmall)
            Text(content.steps[phase].action)
            Text("Common form mistakes", style = MaterialTheme.typography.titleSmall)
            content.mistakes.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            Text("What the camera checks", style = MaterialTheme.typography.titleSmall)
            FormCueCatalog.forSkill(skill).forEach { cue -> Text("${stringResource(cue.label)}: ${stringResource(cue.coachingTip)}", style = MaterialTheme.typography.bodySmall) }
            TextButton({ context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://usavolleyball.org/resources-for-athletes/volleyball-specific-skills/"))) }) { Text("USA Volleyball learning resources") }
        }
    }, confirmButton = { TextButton(onDismiss) { Text("Back to drills") } })
}

/** Starter-drill posture references are independent of pose scoring. */
@Composable
internal fun TechniqueIllustration(skill: Skill, phase: Int, modifier: Modifier) {
    DrillPostureFrame(DrillPostureSamples.starterFor(skill), phase, modifier)
}
