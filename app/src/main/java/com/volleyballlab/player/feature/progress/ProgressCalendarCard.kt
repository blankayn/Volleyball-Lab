package com.volleyballlab.player.feature.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.volleyballlab.player.R
import com.volleyballlab.player.domain.model.SessionResult
import com.volleyballlab.player.domain.model.isValidFor
import com.volleyballlab.player.ui.component.StudentCard
import com.volleyballlab.player.ui.component.scoreColor
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import kotlin.math.roundToInt

private val MonthTitle = DateTimeFormatter.ofPattern("MMMM yyyy")
private val SelectedDateTitle = DateTimeFormatter.ofPattern("EEEE, d MMMM")
private val SessionTime = DateTimeFormatter.ofPattern("h:mm a")

/**
 * A month of training, with the selected day's sessions underneath.
 *
 * Days are marked from real session timestamps. The summary is deliberately
 * computed from the same [SessionResult] rows the history list uses, so a day
 * total can never disagree with the session it opens.
 */
@Composable
fun ProgressCalendarCard(
    sessions: List<SessionResult>,
    onOpenSession: (String) -> Unit,
    onStartTraining: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = LocalLocale.current.platformLocale
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }
    var monthKey by rememberSaveable { mutableStateOf(YearMonth.now(zone).toString()) }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(today.toString()) }
    val month = remember(monthKey) { YearMonth.parse(monthKey) }
    val selectedDate = selectedKey?.let(LocalDate::parse)?.takeIf { YearMonth.from(it) == month }

    val sessionsByDay = remember(sessions, zone) {
        sessions.groupBy { Instant.ofEpochMilli(it.startedAtEpochMs).atZone(zone).toLocalDate() }
    }
    val selectedSessions = selectedDate?.let { sessionsByDay[it].orEmpty() }
        .orEmpty()
        .sortedByDescending { it.startedAtEpochMs }

    StudentCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.progress_calendar_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = month.format(MonthTitle.withLocale(locale)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = {
                        monthKey = month.minusMonths(1).toString()
                        selectedKey = null
                    },
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                        contentDescription = stringResource(R.string.progress_calendar_previous_month),
                    )
                }
                IconButton(
                    onClick = {
                        monthKey = month.plusMonths(1).toString()
                        selectedKey = null
                    },
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.progress_calendar_next_month),
                    )
                }
            }

            Row(Modifier.fillMaxWidth()) {
                DayOfWeek.entries.forEach { day ->
                    Text(
                        text = day.getDisplayName(TextStyle.SHORT, locale),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            MonthGrid(
                month = month,
                today = today,
                selected = selectedDate,
                sessionsByDay = sessionsByDay,
                onSelect = { selectedKey = it.toString() },
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))

            if (selectedDate == null) {
                Text(
                    text = stringResource(R.string.progress_calendar_pick_day),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = selectedDate.format(SelectedDateTitle.withLocale(locale)),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                DayTotals(selectedDate, selectedSessions)
                if (selectedSessions.isEmpty()) {
                    Text(
                        text = stringResource(R.string.progress_calendar_empty_day),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onStartTraining) {
                        Text(stringResource(R.string.progress_calendar_start_training))
                    }
                } else {
                    selectedSessions.forEach { session ->
                        DaySessionRow(session, zone, locale) { onOpenSession(session.id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    today: LocalDate,
    selected: LocalDate?,
    sessionsByDay: Map<LocalDate, List<SessionResult>>,
    onSelect: (LocalDate) -> Unit,
) {
    val first = month.atDay(1)
    val leading = first.dayOfWeek.value - DayOfWeek.MONDAY.value
    val dayCount = month.lengthOfMonth()
    val rowCount = ((leading + dayCount + 6) / 7).coerceAtLeast(5)

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(rowCount) { row ->
            Row(Modifier.fillMaxWidth()) {
                repeat(7) { column ->
                    val dayNumber = row * 7 + column - leading + 1
                    if (dayNumber !in 1..dayCount) {
                        Spacer(Modifier.weight(1f).height(44.dp))
                    } else {
                        val date = month.atDay(dayNumber)
                        CalendarDay(
                            date = date,
                            isToday = date == today,
                            isSelected = date == selected,
                            sessions = sessionsByDay[date].orEmpty(),
                            onClick = { onSelect(date) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarDay(
    date: LocalDate,
    isToday: Boolean,
    isSelected: Boolean,
    sessions: List<SessionResult>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val meanScore = sessions.mapNotNull { it.formScore() }
        .takeIf { it.isNotEmpty() }?.average()?.roundToInt()
    val description = stringResource(
        R.string.progress_calendar_day_description,
        date.dayOfMonth,
        sessions.size,
    )
    val markerColor = when {
        isSelected -> MaterialTheme.colorScheme.onPrimary
        meanScore != null -> scoreColor(meanScore)
        sessions.isNotEmpty() -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outlineVariant
    }

    Box(
        modifier.height(44.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .then(
                    if (isSelected) {
                        Modifier.background(MaterialTheme.colorScheme.primary)
                    } else if (isToday) {
                        Modifier.border(1.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    } else {
                        Modifier
                    },
                )
                .clickable(onClick = onClick)
                .semantics {
                    contentDescription = description
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = date.dayOfMonth.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
            )
            if (sessions.isNotEmpty()) {
                Box(Modifier.size(4.dp).clip(CircleShape).background(markerColor))
            }
        }
    }
}

@Composable
private fun DayTotals(date: LocalDate, sessions: List<SessionResult>) {
    val validReps = sessions.sumOf { session -> session.reps.count { it.isValidFor(session.skill) } }
    val minutes = sessions.sumOf { it.durationMs.coerceAtLeast(0) } / 60_000
    val meanScore = sessions.mapNotNull { it.formScore() }
        .takeIf { it.isNotEmpty() }?.average()?.roundToInt()

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DayMetric(
            value = sessions.size.toString(),
            label = stringResource(R.string.progress_calendar_sessions),
            modifier = Modifier.weight(1f),
        )
        DayMetric(
            value = validReps.toString(),
            label = stringResource(R.string.progress_calendar_valid_reps),
            modifier = Modifier.weight(1f),
        )
        DayMetric(
            value = minutes.toString(),
            label = stringResource(R.string.progress_calendar_minutes),
            modifier = Modifier.weight(1f),
        )
        DayMetric(
            value = meanScore?.toString() ?: "—",
            label = stringResource(R.string.progress_calendar_average),
            valueColor = meanScore?.let { scoreColor(it) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DayMetric(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    valueColor: androidx.compose.ui.graphics.Color? = null,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DaySessionRow(
    session: SessionResult,
    zone: ZoneId,
    locale: java.util.Locale,
    onClick: () -> Unit,
) {
    val time = remember(session.startedAtEpochMs, zone, locale) {
        Instant.ofEpochMilli(session.startedAtEpochMs).atZone(zone).format(SessionTime.withLocale(locale))
    }
    val score = session.formScore()
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(session.skill.displayName),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "$time · ${pluralStringResource(R.plurals.session_card_reps, session.repCount, session.repCount)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = score?.let { "$it%" } ?: stringResource(R.string.session_card_not_assessed),
                style = MaterialTheme.typography.titleSmall,
                color = score?.let { scoreColor(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
