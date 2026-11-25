package com.volleyballlab.player.feature.assessment

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.SportsHandball
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.volleyballlab.player.R
import com.volleyballlab.player.domain.model.*
import com.volleyballlab.player.feature.home.ScoreBar
import com.volleyballlab.player.feature.home.ThinBar
import com.volleyballlab.player.ui.component.SkillAccentRule
import com.volleyballlab.player.ui.component.StudentCard
import com.volleyballlab.player.ui.component.skillVisual
import com.volleyballlab.player.ui.theme.VbMotion
import com.volleyballlab.player.ui.theme.vbSpring
import com.volleyballlab.player.ui.theme.vbTween
import kotlinx.coroutines.delay

internal fun goalIcon(goal: MainGoal): ImageVector = when (goal) {
    MainGoal.LearnBasics -> Icons.Rounded.School
    MainGoal.BeConsistent -> Icons.Rounded.Repeat
    MainGoal.MakeTheTeam -> Icons.Rounded.Groups
    MainGoal.CompeteBetter -> Icons.Rounded.EmojiEvents
    MainGoal.StayActive -> Icons.Rounded.Favorite
}

private fun traitIcon(statement: SkillStatement): ImageVector = when {
    statement.id == "serving_moving" -> Icons.Rounded.Bolt
    statement.id.endsWith("_ready") -> Icons.Rounded.AccessibilityNew
    statement.id.endsWith("_movement") -> Icons.Rounded.SportsHandball
    statement.id.endsWith("_control") -> Icons.Rounded.GpsFixed
    else -> Icons.AutoMirrored.Rounded.DirectionsRun
}

/** One choice as a full-width card. [multiple] when several can be on at once. */
@Composable
internal fun ChoiceCard(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    body: String? = null,
    multiple: Boolean = false,
    leading: @Composable (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(18.dp)
    val fill by animateColorAsState(if (selected) accent.copy(alpha = .12f) else MaterialTheme.colorScheme.surfaceContainerLowest, vbTween(), label = "choiceFill")
    val line by animateColorAsState(if (selected) accent else MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f), vbTween(), label = "choiceLine")
    val role = if (multiple) Role.Checkbox else Role.RadioButton
    Surface(modifier.fillMaxWidth().clip(shape).let {
        if (multiple) it.toggleable(selected, enabled = enabled, role = role, onValueChange = { onClick() }) else it.selectable(selected, enabled = enabled, role = role, onClick = onClick)
    }, shape = shape, color = fill, contentColor = MaterialTheme.colorScheme.onSurface, border = BorderStroke(if (selected) 2.dp else 1.dp, line)) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            leading?.invoke()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                if (body != null) Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Tick(selected, accent)
        }
    }
}

/** Springs in when chosen. Its space is always kept, so nothing moves. */
@Composable
private fun Tick(selected: Boolean, accent: Color) {
    val scale by animateFloatAsState(if (selected) 1f else 0f, vbSpring(), label = "tick")
    Box(Modifier.size(24.dp).graphicsLayer { scaleX = scale; scaleY = scale; alpha = scale.coerceIn(0f, 1f) }.clip(CircleShape).background(accent), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = if (accent.luminance() > .4f) Color.Black else Color.White)
    }
}

@Composable
internal fun IconDisc(icon: ImageVector, accent: Color) {
    Box(Modifier.size(40.dp).clip(CircleShape).background(accent.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(22.dp), tint = accent)
    }
}

/** How much of the time an answer means, as a ring. Not trying is a dash, not zero. */
@Composable
private fun AnswerRing(share: Float?, accent: Color) {
    Canvas(Modifier.size(26.dp)) {
        val stroke = 3.dp.toPx()
        val inset = stroke / 2
        if (share == null) {
            drawLine(accent, Offset(size.width * .25f, center.y), Offset(size.width * .75f, center.y), stroke, StrokeCap.Round)
        } else {
            drawArc(accent.copy(alpha = .25f), 0f, 360f, false, Offset(inset, inset), Size(size.width - stroke, size.height - stroke), style = Stroke(stroke))
            drawArc(accent, -90f, 360f * share, false, Offset(inset, inset), Size(size.width - stroke, size.height - stroke), style = Stroke(stroke, cap = StrokeCap.Round))
        }
    }
}

/**
 * One characteristic of one skill. What comes after an answer depends on it: something
 * that is not there yet asks what happens, and something never tried offers to leave the
 * rest of the skill out.
 */
@Composable
internal fun CharacteristicCard(state: AssessmentUiState, statement: SkillStatement, model: AssessmentViewModel) {
    val skill = statement.skill
    val accent = skillVisual(skill).accent
    val all = SkillCheck.forSkill(skill)
    val position = all.indexOf(statement)
    val answer = state.statements[statement.id]
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(skill.displayName), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.assessment_card_of, position + 1, all.size), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                all.forEachIndexed { index, other ->
                    val color by animateColorAsState(when {
                        index == position -> accent
                        other.id in state.statements -> accent.copy(alpha = .45f)
                        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = .12f)
                    }, vbTween(), label = "pip")
                    Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(color))
                }
            }
        }
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest,
            border = BorderStroke(1.dp, accent.copy(alpha = .3f)), shadowElevation = 3.dp) {
            Column(Modifier.background(Brush.linearGradient(listOf(accent.copy(alpha = .16f), accent.copy(alpha = .03f)))).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconDisc(traitIcon(statement), accent)
                    Text(statement.trait, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                Text(statement.text, style = MaterialTheme.typography.headlineSmall)
            }
        }
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CanDo.entries.forEach { option ->
                ChoiceCard(option.label, answer == option, !state.busy, { model.onAnswer(statement.id, option) }, accent = accent) { AnswerRing(option.share, accent) }
            }
        }
        Appear(SkillCheck.asksDetails(answer)) {
            // It opens below the answers, which can be under the edge of a small screen.
            val view = remember { BringIntoViewRequester() }
            LaunchedEffect(Unit) { delay(VbMotion.Medium.toLong()); view.bringIntoView() }
            Column(Modifier.bringIntoViewRequester(view), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.assessment_detail), style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val chosen = state.details[statement.id].orEmpty()
                    statement.details.forEach { detail ->
                        FilterChip(selected = detail.id in chosen, enabled = !state.busy, onClick = { model.onDetail(statement.id, detail.id) }, label = { Text(detail.text) },
                            leadingIcon = if (detail.id in chosen) ({ Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) }) else null)
                    }
                }
            }
        }
        Appear(answer == CanDo.NotTried && position < all.lastIndex) {
            OutlinedButton(model::onNeverTried, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !state.busy) {
                Text(stringResource(R.string.assessment_never_tried, stringResource(skill.displayName).lowercase()))
            }
        }
    }
}

@Composable
private fun Appear(visible: Boolean, content: @Composable () -> Unit) =
    AnimatedVisibility(visible, enter = fadeIn(vbTween()) + expandVertically(vbTween()), exit = fadeOut(vbTween()) + shrinkVertically(vbTween())) { content() }

/** What the athlete said about one skill. It repeats their answers and gives no score. */
@Composable
internal fun SkillProfileCard(skill: Skill, state: AssessmentUiState, model: AssessmentViewModel) {
    val accent = skillVisual(skill).accent
    val name = stringResource(skill.displayName)
    val edit = stringResource(R.string.assessment_edit_skill, name)
    val remove = stringResource(R.string.assessment_remove_skill, name)
    StudentCard(Modifier.fillMaxWidth(), accent = accent) {
        Column(Modifier.padding(start = 18.dp, end = 10.dp, top = 8.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SkillAccentRule(skill, height = 24)
                Text(name, Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                IconButton({ model.onEditSkill(skill) }, enabled = !state.busy) { Icon(Icons.Rounded.Edit, edit, Modifier.size(20.dp)) }
                if (skill in state.extra) IconButton({ model.onRemoveSkill(skill) }, enabled = !state.busy) { Icon(Icons.Rounded.Close, remove, Modifier.size(20.dp)) }
            }
            Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SkillCheck.forSkill(skill).forEach { statement ->
                    val answer = state.statements[statement.id]
                    val said = state.details[statement.id].orEmpty().let { ids -> statement.details.filter { it.id in ids } }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(statement.trait, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Text(answer?.label.orEmpty(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        ScoreBar(answer?.share ?: 0f, accent, ThinBar)
                        if (said.isNotEmpty()) Text(said.joinToString(" · ") { it.text }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** A skill that can be added at the end. */
@Composable
internal fun AddSkillCard(skill: Skill, answered: Boolean, enabled: Boolean, onAdd: () -> Unit) {
    val name = stringResource(skill.displayName)
    val label = stringResource(R.string.assessment_add_skill, name)
    Surface(onAdd, Modifier.fillMaxWidth().semantics { contentDescription = label }, enabled = enabled, shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            SkillAccentRule(skill, height = 28)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(name, style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(if (answered) R.string.assessment_add_answered else R.string.assessment_add_new), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/** A number in a circle. [filled] ones lead up to the chosen number. */
@Composable
internal fun DayDot(day: Int, selected: Boolean, filled: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val fill by animateColorAsState(when { selected -> primary; filled -> primary.copy(alpha = .18f); else -> MaterialTheme.colorScheme.surfaceContainerLowest }, vbTween(), label = "day")
    val ink by animateColorAsState(if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, vbTween(), label = "dayInk")
    Box(Modifier.size(48.dp).clip(CircleShape).background(fill).selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick), contentAlignment = Alignment.Center) {
        Text("$day", color = ink, style = MaterialTheme.typography.titleMedium, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}
