package com.volleyballlab.player.feature.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.SportsVolleyball
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.volleyballlab.player.R
import com.volleyballlab.player.domain.model.SessionResult
import com.volleyballlab.player.domain.model.SessionReview
import com.volleyballlab.player.ui.component.StudentCard
import com.volleyballlab.player.ui.component.scoreColor
import com.volleyballlab.player.ui.component.skillVisual
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A compact, measured preview that opens the full summary for this recording. */
@Composable
fun RecentSessionSummaryCard(
    session: SessionResult,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val review = remember(session) { SessionReview.from(session) }
    val visual = skillVisual(session.skill)
    val locale = LocalLocale.current.platformLocale
    val zone = ZoneId.systemDefault()
    val date = remember(session.startedAtEpochMs, locale, zone) {
        Instant.ofEpochMilli(session.startedAtEpochMs)
            .atZone(zone)
            .format(DateTimeFormatter.ofPattern("d MMM, HH:mm", locale))
    }
    val preview = when {
        review.formScore == null -> stringResource(R.string.session_card_no_estimate)
        review.corrections.isNotEmpty() -> stringResource(
            R.string.session_card_focus,
            stringResource(review.corrections.first().cue.label),
        )
        review.strengths.isNotEmpty() -> stringResource(
            R.string.session_card_consistent,
            stringResource(review.strengths.first().cue.label),
        )
        session.repCount < 2 -> stringResource(R.string.session_card_limited_capture)
        else -> stringResource(R.string.session_card_open_technique)
    }

    StudentCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier.size(40.dp)
                        .background(visual.wash, RoundedCornerShape(13.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SportsVolleyball,
                        contentDescription = null,
                        modifier = Modifier.size(23.dp),
                        tint = visual.accent,
                    )
                }
                Text(
                    text = stringResource(session.skill.displayName),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Column(
                    modifier = Modifier.widthIn(min = 64.dp, max = 92.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = stringResource(R.string.session_card_estimated_form),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                    )
                    val score = review.formScore
                    Text(
                        text = if (score == null) {
                            stringResource(R.string.session_card_not_assessed)
                        } else {
                            stringResource(R.string.session_card_form_percent, score)
                        },
                        style = if (score == null) MaterialTheme.typography.labelMedium
                            else MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = score?.let { scoreColor(it) }
                            ?: MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                    )
                }
            }
            Text(
                text = stringResource(
                    R.string.session_card_metadata,
                    pluralStringResource(R.plurals.session_card_reps, session.repCount, session.repCount),
                    date,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = preview,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.session_card_view_summary),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
