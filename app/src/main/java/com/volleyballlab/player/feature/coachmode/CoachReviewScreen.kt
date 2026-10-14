package com.volleyballlab.player.feature.coachmode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.StickyNote2
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.volleyballlab.player.R
import com.volleyballlab.player.appContainer
import com.volleyballlab.player.data.coach.DataStoreCoachRepository
import com.volleyballlab.player.domain.model.CoachNote
import com.volleyballlab.player.domain.model.ReviewPoint
import com.volleyballlab.player.domain.model.SafetyCheck
import com.volleyballlab.player.domain.model.SafetyVerdict
import com.volleyballlab.player.domain.model.SessionReview
import com.volleyballlab.player.domain.repository.AuthRepository
import com.volleyballlab.player.domain.repository.CoachRepository
import com.volleyballlab.player.domain.repository.SessionDirectory
import com.volleyballlab.player.ui.component.EntranceAnimation
import com.volleyballlab.player.ui.component.GhostLayout
import com.volleyballlab.player.ui.component.GhostLoadingScreen
import com.volleyballlab.player.ui.component.ScoreArc
import com.volleyballlab.player.ui.component.scoreColor
import com.volleyballlab.player.ui.theme.VbTabularNumerals
import com.volleyballlab.player.ui.theme.vbColors
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CoachReviewViewModel(
    private val coachRepository: CoachRepository,
    private val authRepository: AuthRepository,
    sessionDirectory: SessionDirectory,
) : ViewModel() {

    val state: StateFlow<CoachTeamState> =
        coachTeamState(coachRepository, sessionDirectory).stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = CoachTeamState(),
        )

    /**
     * Writes the note and clears the submission from the queue.
     *
     * The coach uid is read here rather than taken from the screen, so a note can
     * only ever be attributed to whoever is actually signed in.
     */
    fun postNote(playerUid: String, sessionId: String, text: String, onSent: () -> Unit) {
        val trimmed = text.trim().take(CoachNote.MAX_LENGTH)
        if (trimmed.isEmpty()) return
        val coachUid = authRepository.currentUid ?: return

        viewModelScope.launch {
            coachRepository.postNote(
                CoachNote(
                    id = DataStoreCoachRepository.newId(),
                    coachUid = coachUid,
                    playerUid = playerUid,
                    sessionId = sessionId,
                    text = trimmed,
                    createdAtEpochMs = System.currentTimeMillis(),
                ),
            )
            onSent()
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                CoachReviewViewModel(
                    coachRepository = appContainer.coachRepository,
                    authRepository = appContainer.authRepository,
                    sessionDirectory = appContainer.sessionDirectory,
                )
            }
        }
    }
}

/**
 * One submitted session, scored, with the box to answer it in.
 *
 * This is the right-hand panel of Figure 25, given a screen of its own. It has the
 * four things that figure lists - a scorecard, strengths, next corrections and a
 * safety check - plus the cue breakdown, and the comment box at the bottom.
 *
 * ## The analysis is arithmetic, and the screen says so
 *
 * The figure labels this panel "AI-Assisted Analysis". What actually produces it
 * is [SessionReview], which pools the per-rep cue scores already stored with the
 * session and sorts them. There is no model here and the footnote under the
 * breakdown says exactly that, along with the fact that the thresholds are
 * first-pass coaching values rather than measured ones. A coach about to write
 * advice to a teenager is entitled to know how much the number they are reading is
 * worth.
 *
 * ## Why this screen has no dark feature card
 *
 * Every other coach page reserves one [CoachFeatureCard] for the single thing the
 * coach is being asked to do. This page *is* that thing - the coach arrived here by
 * tapping a queued submission - so the prompt would be pointing at the screen it is
 * already on. The comment section carries the only action instead.
 */
@Composable
fun CoachReviewScreen(
    playerUid: String,
    sessionId: String,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val viewModel: CoachReviewViewModel = viewModel(factory = CoachReviewViewModel.Factory)
    val state by viewModel.state.collectAsStateWithLifecycle()

    if (!state.loaded) {
        GhostLoadingScreen(GhostLayout.Detail, contentPadding, modifier)
        return
    }

    var draft by rememberSaveable(sessionId) { mutableStateOf("") }
    var sent by rememberSaveable(sessionId) { mutableStateOf(false) }

    val snapshot = state.snapshotOf(playerUid)
    // Resolved from the full history, not from the snapshot's capped list, so a
    // submission the athlete made a month and thirty sessions ago still opens.
    val session = state.sessionOf(playerUid, sessionId)
    val review = session?.let(SessionReview::from)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            CoachDetailHeader(
                title = snapshot?.member?.playerName?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.coach_unnamed),
                subtitle = session?.let {
                    stringResource(
                        R.string.coach_queue_item,
                        stringResource(it.skill.displayName),
                        sessionTimestampLabel(it.startedAtEpochMs),
                    )
                },
                onBack = onBack,
                // The same disc the roster and player pages put beside this name, so
                // a coach two taps deep can still see whose session they are reading.
                leading = {
                    InitialsAvatar(
                        initials = snapshot?.member?.initials(),
                        diameter = 40,
                        accent = CoachAccent.Rust,
                    )
                },
            )
        }

        if (review == null) {
            // The athlete deleted the recording between submitting it and the coach
            // opening it. Nothing here is recoverable, so the screen says so plainly
            // rather than drawing an empty scorecard that looks like a zero.
            item {
                CoachEmptyCard(
                    title = stringResource(R.string.coach_review_missing),
                    body = stringResource(R.string.coach_dash_queue_empty_body),
                    icon = Icons.Rounded.WarningAmber,
                    modifier = Modifier.padding(horizontal = CoachGutter),
                )
            }
            return@LazyColumn
        }

        item {
            EntranceAnimation(index = 1) {
                Scorecard(review, Modifier.padding(horizontal = CoachGutter))
            }
        }

        item {
            SharedRecordingCard(review.session, playerUid, Modifier.padding(horizontal = CoachGutter))
        }

        if (review.framingIsPoor) {
            item {
                EntranceAnimation(index = 2) {
                    FramingWarning(
                        fraction = review.unmeasuredFraction,
                        modifier = Modifier.padding(horizontal = CoachGutter),
                    )
                }
            }
        }

        item {
            EntranceAnimation(index = 3) {
                PointPanel(
                    title = stringResource(R.string.coach_review_strengths),
                    empty = stringResource(R.string.coach_review_no_strengths),
                    icon = Icons.Rounded.ThumbUp,
                    accent = CoachAccent.Green,
                    points = review.strengths,
                    modifier = Modifier.padding(horizontal = CoachGutter),
                )
            }
        }

        item {
            EntranceAnimation(index = 4) {
                PointPanel(
                    title = stringResource(R.string.coach_review_corrections),
                    empty = stringResource(R.string.coach_review_no_corrections),
                    icon = Icons.Rounded.Build,
                    // Rust rather than green: this is the half of the breakdown the
                    // coach is expected to act on, and rust is the shell's attention
                    // tone wherever something is waiting to be done.
                    accent = CoachAccent.Rust,
                    points = review.corrections,
                    modifier = Modifier.padding(horizontal = CoachGutter),
                )
            }
        }

        if (review.safety.isNotEmpty()) {
            item {
                EntranceAnimation(index = 5) {
                    SafetyPanel(review.safety, Modifier.padding(horizontal = CoachGutter))
                }
            }
        }

        item {
            CommentBox(
                draft = draft,
                sent = sent,
                onDraftChange = {
                    draft = it.take(CoachNote.MAX_LENGTH)
                    sent = false
                },
                onSend = {
                    viewModel.postNote(playerUid, sessionId, draft) {
                        draft = ""
                        sent = true
                    }
                },
                modifier = Modifier.padding(horizontal = CoachGutter),
            )
        }

        val existing = state.notesFor(playerUid).filter { it.sessionId == sessionId }
        if (existing.isNotEmpty()) {
            item {
                CoachSectionTitle(text = stringResource(R.string.coach_player_notes))
            }
            // Left as real list items rather than folded into one section card, so a
            // coach who has written to this session a dozen times still scrolls a
            // keyed, lazily composed list.
            items(items = existing, key = { it.id }) { note ->
                NoteRow(note, Modifier.padding(horizontal = CoachGutter))
            }
        }

        item {
            CoachFootnote(
                text = stringResource(R.string.coach_review_analysis_source),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * The session's headline number and its two supporting counts.
 *
 * The arc is reused from the athlete's own results screen on purpose. A coach and
 * an athlete looking at the same session should be looking at the same figure
 * drawn the same way; two representations of one score is how a conversation about
 * it goes wrong.
 *
 * No icon chip beside it, unlike every other card here: the arc is already the
 * largest, most coloured thing on the page and a chip next to it would be a second
 * mark competing for the same glance.
 */
@Composable
private fun Scorecard(review: SessionReview, modifier: Modifier = Modifier) {
    CoachCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (review.formScore != null) {
                ScoreArc(
                    score = review.formScore,
                    diameter = 108.dp,
                    label = stringResource(R.string.coach_review_scorecard),
                )
                HorizontalGap(18)
            }
            Column {
                Text(
                    text = pluralStringResource(
                        R.plurals.coach_reps_measured,
                        review.repCount,
                        review.repCount,
                    ),
                    style = MaterialTheme.typography.titleMedium.merge(VbTabularNumerals),
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = sessionTimestampLabel(review.session.startedAtEpochMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The "most of this recording could not be measured" caution.
 *
 * Deliberately the one surface on the page that is not white. It keeps the shell's
 * card geometry - [CoachCardShape] and the same 2dp lift - so it sits in the same
 * plane as everything around it, but it keeps the warning container fill because it
 * is a caveat about the number directly above it rather than another readout. A
 * white card here would let a coach scroll past the reason the score is unreliable.
 */
@Composable
private fun FramingWarning(fraction: Float, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CoachCardShape,
        color = MaterialTheme.vbColors.warningContainer,
        shadowElevation = CoachCardElevation,
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Rounded.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.vbColors.onWarningContainer,
                modifier = Modifier.size(20.dp),
            )
            HorizontalGap(12)
            Text(
                text = stringResource(
                    R.string.coach_review_framing,
                    (fraction * 100).roundToInt(),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.vbColors.onWarningContainer,
            )
        }
    }
}

/**
 * Strengths or corrections: same shape, different list, different empty line.
 *
 * The [icon] and [accent] are passed in rather than derived, because the two calls
 * are the only ones that will ever exist and a `isStrengths: Boolean` that secretly
 * picks a colour is harder to read than the colour itself.
 *
 * An empty list swaps the whole section card for the dashed well rather than
 * nesting one inside the other. The well carries a title of its own, and a section
 * header directly above it would print the same three words twice forty dips apart,
 * which reads as a rendering bug rather than as emphasis.
 */
@Composable
private fun PointPanel(
    title: String,
    empty: String,
    icon: ImageVector,
    accent: CoachAccent,
    points: List<ReviewPoint>,
    modifier: Modifier = Modifier,
) {
    if (points.isEmpty()) {
        CoachEmptyCard(
            title = title,
            body = empty,
            icon = icon,
            accent = accent,
            modifier = modifier,
        )
        return
    }
    CoachSectionCard(title = title, icon = icon, accent = accent, modifier = modifier) {
        points.forEachIndexed { index, point ->
            if (index > 0) Spacer(Modifier.height(14.dp))
            PointRow(point)
        }
    }
}

@Composable
private fun PointRow(point: ReviewPoint) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(point.cue.label),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = pluralStringResource(
                    R.plurals.coach_reps_measured,
                    point.measuredReps,
                    point.measuredReps,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalGap(10)
            Text(
                text = point.scoreOutOfHundred.toString(),
                style = MaterialTheme.typography.titleMedium.merge(VbTabularNumerals),
                fontWeight = FontWeight.ExtraBold,
                color = scoreColor(point.scoreOutOfHundred),
            )
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { point.meanScore.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                // Clipped to a pill so the bar matches the rounded geometry of the
                // card it sits in; a square-ended bar reads as a different toolkit.
                .clip(CoachPillShape),
            color = scoreColor(point.scoreOutOfHundred),
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(point.cue.coachingTip),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The safety panel.
 *
 * Every line carries its own verdict and the whole panel carries a disclaimer,
 * because this is the part of the screen most likely to be quoted out of context.
 * The disclaimer stays *inside* the card, under the checks it qualifies, rather
 * than being demoted to the page footnote: a caveat a screenshot can crop out is
 * not a caveat. See [SafetyCheck] for what these are and, more importantly, what
 * they are not.
 *
 * The verdict glyphs keep the semantic success/warning colours rather than taking
 * the card's indigo accent. Indigo says "this section is about review"; green and
 * amber say what a particular joint actually did, and those are different claims.
 */
@Composable
private fun SafetyPanel(checks: List<SafetyCheck>, modifier: Modifier = Modifier) {
    CoachSectionCard(
        title = stringResource(R.string.coach_review_safety),
        icon = Icons.Rounded.HealthAndSafety,
        accent = CoachAccent.Indigo,
        modifier = modifier,
    ) {
        checks.forEach { check ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = when (check.verdict) {
                        SafetyVerdict.Clear -> Icons.Rounded.CheckCircle
                        SafetyVerdict.Watch -> Icons.Rounded.WarningAmber
                        SafetyVerdict.Unknown -> Icons.AutoMirrored.Rounded.HelpOutline
                    },
                    contentDescription = null,
                    tint = when (check.verdict) {
                        SafetyVerdict.Clear -> MaterialTheme.vbColors.success
                        SafetyVerdict.Watch -> MaterialTheme.vbColors.warning
                        SafetyVerdict.Unknown -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(18.dp),
                )
                HorizontalGap(10)
                Text(
                    text = stringResource(check.cue.label),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(check.verdict.label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        CoachFootnote(stringResource(R.string.review_safety_disclaimer))
    }
}

/**
 * The box the coach answers in.
 *
 * Given a section card of its own, with the label that used to be a free-standing
 * heading now the card's title, so the one place on this page that accepts input
 * looks like a place rather than a field left at the bottom of a scroll.
 */
@Composable
private fun CommentBox(
    draft: String,
    sent: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CoachSectionCard(
        title = stringResource(R.string.coach_review_comment_label),
        icon = Icons.Rounded.EditNote,
        accent = CoachAccent.Violet,
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            placeholder = { Text(stringResource(R.string.coach_review_comment_hint)) },
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
            shape = CoachInnerShape,
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CoachPillButton(
                label = stringResource(R.string.coach_review_send),
                onClick = onSend,
                enabled = draft.isNotBlank(),
            )
            if (sent) {
                HorizontalGap(12)
                Text(
                    text = stringResource(R.string.coach_review_sent),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.vbColors.success,
                )
            }
        }
    }
}

/**
 * One note the coach already wrote about this session.
 *
 * Violet, like every other piece of written material in the shell, and a row card
 * rather than a section entry so the list reads as a thread of separate things
 * said on separate days.
 */
@Composable
private fun NoteRow(note: CoachNote, modifier: Modifier = Modifier) {
    CoachRowCard(modifier) {
        CoachIconChip(
            icon = Icons.AutoMirrored.Rounded.StickyNote2,
            accent = CoachAccent.Violet,
            size = 34.dp,
        )
        HorizontalGap(12)
        Column(Modifier.weight(1f)) {
            Text(
                text = note.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = sessionTimestampLabel(note.createdAtEpochMs),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
