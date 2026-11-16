package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.CameraAngle
import com.volleyballlab.player.domain.model.CueId
import com.volleyballlab.player.domain.model.ExperienceLevel
import com.volleyballlab.player.domain.model.RepPhase

/** Counted facts about the session, supplied by the capture screen's own metrics. */
data class RepContext(
    val stageReps: Int,
    val stageTarget: Int?,
    val stageIndex: Int,
    val streak: Int,
    val bestStreak: Int,
    val validReps: Int,
)

/** What one finished view left behind, for the debrief and the closing. Words only. */
data class StageNote(
    val angle: CameraAngle,
    val reps: Int,
    val validReps: Int,
    val bestStreak: Int,
    val resolvedCues: Set<CueId>,
    val openCue: CueId?,
)

/** Only completed reps enter this director; frame-level posture never emits speech. */
class CoachDirector {
    private data class CueKey(val id: CueId, val phase: RepPhase, val side: Side?)
    private data class Observation(val rep: Int, val cues: Map<CueKey, CueLiveStatus>)
    private data class Focus(
        val key: CueKey,
        val startedRep: Int,
        val baselineDistance: Float,
        var direction: Int,
        var nextInstruction: Int = 0,
        var missingReps: Int = 0,
        var progressAcknowledged: Boolean = false,
    )
    private data class Spoken(val rep: Int, val timestampMs: Long)
    /** Enough to undo one emitted sentence the athlete never heard. */
    private data class Emitted(
        val text: String,
        val rep: Int,
        val previousSpoken: Spoken?,
        val correction: CueKey? = null,
        val instructionIndex: Int = 0,
        val previousCue: Pair<Int, Long>? = null,
    )

    /** Chooses where the correction ladder starts. Wording only. */
    var experience: ExperienceLevel? = null

    private var sequence = 0L
    private var stageReps = 0
    private var rep = 0
    private var lastTurnRep = -100
    private var lastConfirmationRep = -100
    private var trackingAt: Long? = null
    private var trackingRestoredFor: Long? = null
    private val corrections = ArrayDeque<Int>()
    private val lastSpoken = mutableMapOf<String, Spoken>()
    private val emitted = LinkedHashMap<Long, Emitted>()
    private val lastCue = mutableMapOf<CueId, Pair<Int, Long>>()
    private val observations = ArrayDeque<Observation>()
    private var angle: CameraAngle? = null
    private var focus: Focus? = null

    // Session-arc state. None of it changes what counts as evidence.
    private val stageScores = ArrayDeque<Float>()
    private var stageHadGoodRep = false
    private var cleanStreak = 0
    private var strongCues: Set<CueKey> = emptySet()
    private val confirmedInRange = mutableSetOf<CueKey>()
    private var regroupSpoken = false
    private var budgetSpoken = false
    private var budgetBlocked = false
    private var carryFocus: CueId? = null
    private var stageOpenCue: CueId? = null
    private val stageResolved = mutableSetOf<CueId>()
    private val stageCorrectedCues = mutableSetOf<CueId>()
    private val notes = mutableListOf<StageNote>()
    private var lastCorrectionText: String? = null

    fun reset() {
        stageReps = 0
        rep = 0
        lastTurnRep = -100
        lastConfirmationRep = -100
        trackingAt = null
        trackingRestoredFor = null
        corrections.clear()
        lastSpoken.clear()
        emitted.clear()
        lastCue.clear()
        carryFocus = null
        notes.clear()
        lastCorrectionText = null
        clearStage()
        interruptObservation()
    }

    /** Record the finished view before [beginStage] discards its evidence. */
    fun endStage(angle: CameraAngle, reps: Int, validReps: Int, bestStreak: Int) {
        notes += StageNote(angle, reps, validReps, bestStreak, stageResolved.toSet(), stageOpenCue)
    }

    fun stageNotes(): List<StageNote> = notes.toList()

    /** Cues the athlete was already corrected on in the current view. */
    fun stageCorrected(): Set<CueId> = stageCorrectedCues.toSet()

    /** A cue inside its target, with a high score, on every rep of the current view. */
    fun heldAllStage(preferredCueIds: Set<CueId> = emptySet()): CueId? {
        if (stageReps < EVIDENCE_REPS || cleanStreak < stageReps) return null
        return strongCues.sortedWith(compareByDescending<CueKey> { it.id in preferredCueIds }.thenBy { it.id.ordinal })
            .firstOrNull()?.id
    }
    fun lastCorrection(): String? = lastCorrectionText

    fun beginStage() {
        stageReps = 0
        // Only the name of the open focus crosses the view change. Its
        // measurements do not: the new view must earn the correction again.
        carryFocus = stageOpenCue
        clearStage()
        interruptObservation()
    }

    private fun clearStage() {
        stageOpenCue = null
        stageResolved.clear()
        stageCorrectedCues.clear()
        regroupSpoken = false
        budgetSpoken = false
    }

    /** Tracking gaps, backgrounding and view changes cannot bridge evidence. */
    fun interruptObservation() {
        observations.clear()
        angle = null
        focus = null
        stageScores.clear()
        stageHadGoodRep = false
        cleanStreak = 0
        strongCues = emptySet()
        confirmedInRange.clear()
    }

    fun onRep(
        assessment: LiveRepAssessment,
        timestampMs: Long,
        cameraAngle: CameraAngle,
        preferredCueIds: Set<CueId> = emptySet(),
        context: RepContext? = null,
    ): CoachingEvent? {
        rep++
        stageReps++
        if (cameraAngle != angle || cameraAngle == CameraAngle.Unknown) interruptObservation()
        angle = cameraAngle
        val measured = assessment.statuses.filter {
            cameraAngle != CameraAngle.Unknown && it.measured?.isFinite() == true &&
                it.score?.isFinite() == true && it.score in 0f..1f && it.cue.id != CueId.SpikeJumpHeight
        }.associateBy { CueKey(it.cue.id, it.cue.phase, it.side) }
        observations.addLast(Observation(rep, measured))
        while (observations.size > EVIDENCE_REPS) observations.removeFirst()
        observeArc(assessment, measured)

        // Observe even during the quiet retry; silence must not discard evidence.
        val active = focus
        if (active != null) {
            active.missingReps = if (active.key in measured) 0 else active.missingReps + 1
            if (active.missingReps >= EVIDENCE_REPS) focus = null
        }
        if (rep - lastTurnRep <= 1) return null
        if (stageReps <= 2) return context?.let { watchTurn(it, measured, preferredCueIds, timestampMs) }

        budgetBlocked = false
        val formTurn = formTurn(active, assessment, measured, timestampMs, cameraAngle, preferredCueIds)
        if (formTurn != null || context == null) return formTurn
        return streakTurn(assessment, context, preferredCueIds, timestampMs)
            ?: regroupTurn(measured, timestampMs)
            ?: budgetTurn(timestampMs)
    }

    /** Corrections, improvements and missing-evidence guidance: the grounded form turns. */
    private fun formTurn(
        active: Focus?,
        assessment: LiveRepAssessment,
        measured: Map<CueKey, CueLiveStatus>,
        timestampMs: Long,
        cameraAngle: CameraAngle,
        preferredCueIds: Set<CueId>,
    ): CoachingEvent? {
        // Missing evidence is neither a failed attempt nor an improvement.
        if (measured.isEmpty() || active != null && active.key !in measured) {
            val unavailable = active?.key?.id ?: assessment.statuses.firstOrNull {
                it.cue.id != CueId.SpikeJumpHeight
            }?.cue?.id
            val guidance = unavailableGuidance(unavailable, cameraAngle, timestampMs)
            if (guidance != null || measured.isEmpty() || focus != null) return guidance
        }

        focus?.let { current ->
            val status = measured[current.key] ?: return null
            val retries = observations.filter { it.rep > current.startedRep }.takeLast(2)
                .mapNotNull { it.cues[current.key] }
            if (retries.size == 2 && rep - lastConfirmationRep >= 3) {
                val inRange = retries.all { it.measured!! in it.cue.target && it.score!! >= 0.92f }
                // This is a coaching policy, not a new scoring or accuracy threshold.
                val meaningfulChange = maxOf(status.cue.warnTolerance * 0.2f, current.baselineDistance * 0.25f)
                val closer = !current.progressAcknowledged && retries.all {
                    distance(it) <= current.baselineDistance - meaningfulChange
                }
                if (inRange || closer) {
                    if (inRange) {
                        focus = null
                        confirmedInRange += current.key
                        stageResolved += current.key.id
                        if (stageOpenCue == current.key.id) stageOpenCue = null
                    } else current.progressAcknowledged = true
                    val text = CoachPhrasebook.improvement(current.key.id, inRange).firstOrNull { available(it, timestampMs) }
                    if (text != null) {
                        lastConfirmationRep = rep
                        return emit(CoachingEventType.Improvement, text, timestampMs)
                    }
                }
            }
        }

        while (corrections.firstOrNull()?.let { rep - it >= 10 } == true) corrections.removeFirst()
        val selected = focus?.key ?: measured.keys.filter { key ->
            repeatedFault(key) && correctionDue(key.id, timestampMs) &&
                ladder(key, measured.getValue(key)).any { available(it, timestampMs) }
        }.maxWithOrNull(compareBy<CueKey> { it.id in preferredCueIds }.thenBy { key ->
            observations.map { it.cues.getValue(key) }.sumOf { (it.cue.weight * (1f - it.score!!)).toDouble() }
        }) ?: return null
        // A different fault becoming worse cannot steal the athlete's current focus.
        if (!repeatedFault(selected)) return null
        if (corrections.size >= 4) { budgetBlocked = true; return null }
        val status = measured.getValue(selected)
        if (!correctionDue(selected.id, timestampMs)) return null
        val faultDirection = direction(status)
        val carried = focus == null && selected.id == carryFocus
        val current = focus ?: Focus(selected, rep,
            observations.map { distance(it.cues.getValue(selected)) }.sorted()[1], faultDirection,
            // The athlete already heard the direct cue for this in the last view.
            nextInstruction = if (carried) 1 else 0)
        if (faultDirection != current.direction) {
            current.direction = faultDirection
            current.nextInstruction = 0
        }
        // Walk the ladder from the next rung, wrapping, skipping sentences still on cooldown.
        val rungs = ladder(selected, status)
        val index = rungs.indices.map { (current.nextInstruction + it) % rungs.size }
            .firstOrNull { available(rungs[it], timestampMs) } ?: return null
        current.nextInstruction = (index + 1) % rungs.size
        focus = current
        stageOpenCue = selected.id
        stageCorrectedCues += selected.id
        if (carried) carryFocus = null
        val previousCue = lastCue[selected.id]
        corrections += rep
        lastCue[selected.id] = rep to timestampMs
        val text = if (carried) CoachPhrasebook.CARRIED_FOCUS_PREFIX + rungs[index] else rungs[index]
        lastCorrectionText = text
        return emit(CoachingEventType.Correction, text, timestampMs, ledgerKey = rungs[index],
            correction = selected, instructionIndex = index, previousCue = previousCue)
    }

    private fun ladder(key: CueKey, status: CueLiveStatus): List<String> =
        CoachPhrasebook.instructions(key.id, status.measured!!, key.side, experience)

    /** A sentence may return only after it has rested for both reps and time. */
    private fun available(text: String, timestampMs: Long): Boolean {
        val last = lastSpoken[text] ?: return true
        return rep - last.rep >= REPEAT_REPS && timestampMs - last.timestampMs >= REPEAT_MS
    }

    private fun observeArc(assessment: LiveRepAssessment, measured: Map<CueKey, CueLiveStatus>) {
        val score = assessment.score?.takeIf { assessment.isAssessable && it.isFinite() }
        if (score != null) stageScores.addLast(score)
        val clean = assessment.isAssessable && assessment.isGood && measured.isNotEmpty() &&
            measured.values.none { it.score!! < 0.8f }
        if (clean) {
            val strong = measured.filterValues { it.measured!! in it.cue.target && it.score!! >= 0.92f }.keys
            strongCues = if (cleanStreak == 0) strong else strongCues intersect strong
            cleanStreak++
            stageHadGoodRep = true
        } else {
            cleanStreak = 0
            strongCues = emptySet()
        }
    }

    private fun watchTurn(
        context: RepContext,
        measured: Map<CueKey, CueLiveStatus>,
        preferredCueIds: Set<CueId>,
        timestampMs: Long,
    ): CoachingEvent? {
        // A first rep with a measured fault keeps its own cue-naming line.
        if (stageReps != 1 || measured.values.any { it.score!! < 0.8f }) return null
        val text = CoachPhrasebook.watching(preferredCueIds.firstOrNull(), context.stageIndex)
        return emit(CoachingEventType.RepMilestone, text, timestampMs)
    }

    private fun streakTurn(
        assessment: LiveRepAssessment,
        context: RepContext,
        preferredCueIds: Set<CueId>,
        timestampMs: Long,
    ): CoachingEvent? {
        if (!assessment.isAssessable || !assessment.isGood || context.streak !in STREAKS) return null
        // The director's own count must cover the whole run in this view.
        if (cleanStreak < context.streak || focus != null || rep - lastConfirmationRep < 3) return null
        val strong = strongCues.sortedWith(compareByDescending<CueKey> { it.id in preferredCueIds }
            .thenByDescending { key -> observations.last().cues[key]?.cue?.weight ?: 0f }
            .thenBy { it.id.ordinal }).firstOrNull()?.id
        return emit(CoachingEventType.RepMilestone, CoachPhrasebook.streak(context.streak, strong), timestampMs)
    }

    private fun regroupTurn(measured: Map<CueKey, CueLiveStatus>, timestampMs: Long): CoachingEvent? {
        if (regroupSpoken) return null
        val recent = observations.toList().takeLast(2)
        val slipping = confirmedInRange.firstOrNull { key ->
            val last = recent.mapNotNull { it.cues[key] }
            last.size == 2 && key in measured && last.all { it.score!! < 0.8f } &&
                direction(last[0]) != 0 && direction(last[0]) == direction(last[1])
        }
        val dropped = stageHadGoodRep && stageScores.size >= 6 &&
            stageScores.take(3).average() - stageScores.takeLast(3).average() >= REGROUP_DROP
        if (slipping == null && !dropped) return null
        regroupSpoken = true
        return emit(CoachingEventType.RepMilestone, CoachPhrasebook.regroup(slipping?.id), timestampMs)
    }

    private fun budgetTurn(timestampMs: Long): CoachingEvent? {
        if (!budgetBlocked || budgetSpoken) return null
        budgetSpoken = true
        return emit(CoachingEventType.RepMilestone, CoachPhrasebook.BUDGET_SPENT, timestampMs)
    }

    /** Three consecutive comparable measurements, with two misses in one direction. */
    private fun repeatedFault(key: CueKey): Boolean {
        if (observations.size < EVIDENCE_REPS) return false
        val samples = observations.map { it.cues[key] ?: return false }
        val latest = samples.last()
        val faultDirection = direction(latest)
        return faultDirection != 0 && latest.score!! < 0.8f && samples.count {
            it.score!! < 0.8f && direction(it) == faultDirection
        } >= 2
    }

    private fun correctionDue(id: CueId, timestampMs: Long): Boolean {
        val previous = lastCue[id] ?: return true
        return rep - previous.first >= 2 && timestampMs - previous.second >= 15_000
    }

    private fun direction(status: CueLiveStatus): Int = when {
        status.measured!! < status.cue.target.start -> -1
        status.measured > status.cue.target.endInclusive -> 1
        else -> 0
    }

    private fun distance(status: CueLiveStatus): Float = when (direction(status)) {
        -1 -> status.cue.target.start - status.measured!!
        1 -> status.measured!! - status.cue.target.endInclusive
        else -> 0f
    }

    private fun unavailableGuidance(id: CueId?, cameraAngle: CameraAngle, timestampMs: Long): CoachingEvent? {
        if (stageReps % 3 != 0) return null
        val text = CoachPhrasebook.unavailable(id, cameraAngle).firstOrNull { available(it, timestampMs) } ?: return null
        return emit(CoachingEventType.RepMilestone, text, timestampMs)
    }

    fun onTracking(timestampMs: Long, requestedAngle: CameraAngle? = null): CoachingEvent? {
        interruptObservation()
        if (trackingAt?.let { timestampMs - it < 10_000 } == true) return null
        trackingAt = timestampMs
        val text = when (requestedAngle) {
            CameraAngle.Front -> "Face the camera again so I can assess this view."
            CameraAngle.Side -> "Turn sideways again so I can assess this view."
            CameraAngle.Rear -> "Face away from the camera again for this view."
            CameraAngle.Unknown, null -> "Step back into frame so your whole body is visible."
        }
        return CoachingEvent(++sequence, CoachingEventType.Tracking, text, timestampMs, 2)
    }

    /** Acknowledge a recovery once, and only soon after the athlete was asked to fix it. */
    fun onTrackingRestored(timestampMs: Long): CoachingEvent? {
        val prompted = trackingAt ?: return null
        if (trackingRestoredFor == prompted || timestampMs - prompted !in 0..20_000) return null
        trackingRestoredFor = prompted
        return CoachingEvent(++sequence, CoachingEventType.RepMilestone, CoachPhrasebook.TRACKING_RESTORED, timestampMs, 0)
    }

    /**
     * The athlete never heard this event. Put its sentence back so it can be
     * offered again instead of being counted as teaching that happened.
     */
    fun onSpeechDropped(sequence: Long) {
        val dropped = emitted.remove(sequence) ?: return
        if (dropped.previousSpoken == null) lastSpoken.remove(dropped.text) else lastSpoken[dropped.text] = dropped.previousSpoken
        val key = dropped.correction ?: return
        corrections.remove(dropped.rep)
        if (dropped.previousCue == null) lastCue.remove(key.id) else lastCue[key.id] = dropped.previousCue
        focus?.takeIf { it.key == key }?.nextInstruction = dropped.instructionIndex
    }

    private fun emit(
        type: CoachingEventType,
        text: String,
        timestampMs: Long,
        ledgerKey: String = text,
        correction: CueKey? = null,
        instructionIndex: Int = 0,
        previousCue: Pair<Int, Long>? = null,
    ): CoachingEvent {
        val id = ++sequence
        emitted[id] = Emitted(ledgerKey, rep, lastSpoken[ledgerKey], correction, instructionIndex, previousCue)
        while (emitted.size > 8) emitted.remove(emitted.keys.first())
        lastSpoken[ledgerKey] = Spoken(rep, timestampMs)
        lastTurnRep = rep
        return CoachingEvent(id, type, text, timestampMs,
            if (type == CoachingEventType.Correction) 1 else 0)
    }

    private companion object {
        const val EVIDENCE_REPS = 3
        const val REPEAT_REPS = 8
        const val REPEAT_MS = 45_000L
        const val REGROUP_DROP = 15.0
        val STREAKS = setOf(3, 5, 8)
    }
}
