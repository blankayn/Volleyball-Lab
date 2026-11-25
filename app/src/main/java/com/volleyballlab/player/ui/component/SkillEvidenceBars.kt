package com.volleyballlab.player.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.volleyballlab.player.R
import com.volleyballlab.player.domain.model.EvidenceSource
import com.volleyballlab.player.domain.model.SkillEvidence
import com.volleyballlab.player.feature.home.ScoreBar
import com.volleyballlab.player.feature.home.ThinBar
import com.volleyballlab.player.ui.theme.VbTabularNumerals

/** All five skills side by side, each saying where its number comes from. No level or grade. */
@Composable
fun SkillEvidenceBars(evidence: List<SkillEvidence>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        evidence.forEach { item ->
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(item.skill.displayName), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Text(item.strength?.let { "$it" } ?: "—", style = MaterialTheme.typography.titleSmall.merge(VbTabularNumerals), fontWeight = FontWeight.SemiBold)
                }
                ScoreBar((item.strength ?: 0) / 100f, skillVisual(item.skill).accent, ThinBar)
                Text(when (item.source) {
                    EvidenceSource.Measured -> pluralStringResource(R.plurals.evidence_measured, item.scoredSessions, item.scoredSessions)
                    EvidenceSource.Answers -> stringResource(R.string.evidence_answers, item.yes, item.sometimes, item.notYet)
                    EvidenceSource.Unknown -> stringResource(R.string.evidence_unknown)
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
