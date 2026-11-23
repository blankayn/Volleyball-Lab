package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.AthleteProfile
import com.volleyballlab.player.domain.model.CameraAngle
import com.volleyballlab.player.domain.model.DrillFocus
import com.volleyballlab.player.domain.model.CueId
import com.volleyballlab.player.domain.model.ExperienceLevel
import com.volleyballlab.player.domain.model.SessionResult
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.domain.model.TrainingSetupQuestions

/** Local wording and cue priorities, never a change to measurement or scoring. */
data class LiveCoachingPlan(
    val athleteName: String = "",
    val focusCueIds: Set<CueId> = emptySet(),
    val focusLabel: String = "Your visible form",
    val experience: ExperienceLevel? = null,
    // Local-only session memory. None of these fields is ever sent to the cloud.
    val sessionsOnSkill: Int = 0,
    val lastSessionFocus: CueId? = null,
    val lastValidReps: Int? = null,
    val lastTotalReps: Int? = null,
    val assignmentTitle: String? = null,
    val humanCoachName: String? = null,
) {
    val title: String get() = if (athleteName.isEmpty()) "Your live coach" else "$athleteName’s live coach"

    /**
     * The opening brief, one sentence per segment so speech can pause between
     * them. Every optional segment appears only when its stored fact exists.
     */
    fun opening(skill: Skill, repsPerAngle: Int? = null): List<String> = buildList {
        val returning = sessionsOnSkill > 0
        if (athleteName.isNotEmpty()) add(if (returning) "Welcome back, $athleteName." else "Hi $athleteName.")
        else if (returning) add("Welcome back.")
        if (returning && lastSessionFocus != null) {
            add("Last time your ${CoachPhrasebook.focusName(lastSessionFocus)} needed the most work, so let's see it early.")
        }
        // A coach's title for the drill is read out. A free-text note never is.
        assignmentTitle?.let { title ->
            val who = humanCoachName?.let { "Coach $it" } ?: "Your coach"
            add("$who set this one: $title.")
        }
        focusCueIds.firstOrNull()?.let { add("Today's about your ${CoachPhrasebook.focusName(it)}.") }
        add(CoachPhrasebook.opening(skill, sessionsOnSkill))
        // Which way to face, before anything can be counted.
        add(FacingGuide.opening(repsPerAngle))
        // True: the director stays silent on form for the first two reps of a view.
        add("I'll watch the first couple before I say anything.")
    }

    fun openingText(skill: Skill, repsPerAngle: Int? = null): String =
        opening(skill, repsPerAngle).joinToString(" ")

    /** Adds what this device remembers about earlier sessions on the same skill and drill. */
    fun withHistory(sessions: List<SessionResult>, skill: Skill, drillId: String): LiveCoachingPlan {
        val earlier = sessions.filter { it.skill == skill && it.drillId == drillId }.sortedBy { it.startedAtEpochMs }
        val last = earlier.lastOrNull() ?: return copy(sessionsOnSkill = 0, lastSessionFocus = null,
            lastValidReps = null, lastTotalReps = null)
        return copy(sessionsOnSkill = earlier.size, lastSessionFocus = last.weakestCue(),
            lastValidReps = last.angleStages.takeIf { it.isNotEmpty() }?.sumOf { it.validRepCount },
            lastTotalReps = last.repCount)
    }

    /**
     * Puts the drill's own checks ([DrillFocus]) first in the focus, ahead of any
     * focus from the athlete's training setup, which stays in the set.
     */
    fun withDrill(drillId: String): LiveCoachingPlan {
        val drill = DrillFocus.cues(drillId).takeIf { it.isNotEmpty() } ?: return this
        return copy(focusCueIds = LinkedHashSet(drill + (focusCueIds - DrillFocus.excluded(drillId))),
            focusLabel = "Your focus: ${CoachPhrasebook.focusName(drill.first())}")
    }

    /** Only a short, speakable drill title and a first name come from the linked coach. */
    fun withCoach(coachName: String?, assignmentTitle: String?): LiveCoachingPlan = copy(
        humanCoachName = coachName?.let(::speakableName)?.takeIf { it.isNotEmpty() },
        assignmentTitle = assignmentTitle?.filter { it.isLetterOrDigit() || it in " -'&" }?.trim()
            ?.replace(Regex("\\s+"), " ")?.take(60)?.takeIf { it.isNotEmpty() },
    )

    companion object {
        fun forAthlete(skill: Skill, profile: AthleteProfile): LiveCoachingPlan {
            val plan = profile.trainingSetup?.plan
            // Only closed, confirmed choices for this skill can prioritize a cue.
            // Free-text goals and names never become cloud coaching inputs.
            val option = plan?.takeIf {
                it.focusSkill == skill && TrainingSetupQuestions.skillForQuestion(it.questionId) == skill
            }?.optionId
            val cues = when (skill to option) {
                Skill.Serving to "toss" -> setOf(CueId.ServeTossArmLift)
                Skill.Serving to "contact" -> setOf(CueId.ServeContactArmExtension, CueId.ServeReachHeight)
                Skill.Receiving to "ready_position" -> setOf(CueId.PassKneeFlexion, CueId.PassTrunkAngle)
                Skill.Receiving to "platform" -> setOf(CueId.PassPlatformStraight, CueId.PassPlatformHeight, CueId.PassShouldersLevel)
                Skill.Setting to "hand_position" -> setOf(CueId.SetHandsAboveForehead, CueId.SetElbowFlexion)
                Skill.Setting to "contact" -> setOf(CueId.SetElbowFlexion, CueId.SetExtensionFollowThrough)
                Skill.Spiking to "approach" -> setOf(CueId.SpikeApproachLoad)
                Skill.Spiking to "contact" -> setOf(CueId.SpikeArmExtension, CueId.SpikeElbowHeight)
                Skill.Blocking to "ready_position" -> setOf(CueId.BlockKneeLoad)
                Skill.Blocking to "hand_position" -> setOf(CueId.BlockHandSeparation, CueId.BlockArmExtension)
                else -> emptySet()
            }
            return LiveCoachingPlan(
                athleteName = speakableName(profile.firstName.ifBlank { profile.name }),
                focusCueIds = cues,
                focusLabel = cues.firstOrNull()?.let { "Your focus: ${CoachPhrasebook.focusName(it)}" }
                    ?: "Your focus: ${skill.name.lowercase()} form",
                experience = plan?.experience ?: profile.trainingSetup?.assessment?.experience,
            )
        }

        private fun speakableName(value: String): String =
            value.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
                .filter { it.isLetter() || it == '-' || it == '\'' }.take(40)
    }
}

/** How a **counted** rep went. A rep that was wrong is not counted and has no verdict here; see [RepJudge]. */
enum class LiveRepVerdict(val label: String) {
    Good("Form target met"), Adjust("Counted, can improve"), Partial("Partly assessed"), Unrated("Not assessed"),
}

data class LiveRepFeedback(
    val totalReps: Int,
    val stageReps: Int,
    val target: Int?,
    val verdict: LiveRepVerdict,
    val detail: String,
    val type: CoachingEventType,
    /** What the coach says aloud. The card keeps [countText] and [detail]. */
    val spokenText: String = "",
    /** The reaction part of [spokenText], remembered so the next rep does not repeat it. */
    val ack: String? = null,
    /** True when the director took this turn; the next rep is then a quiet count. */
    val directed: Boolean = false,
) {
    val countText: String get() = if (target == null) "Rep $totalReps" else "Rep $stageReps of $target"
}

/** Every completed movement gets a count; only observed cues get a form verdict. */
object PersonalizedLiveCoaching {
    fun feedback(
        assessment: LiveRepAssessment,
        angle: CameraAngle,
        plan: LiveCoachingPlan,
        totalReps: Int,
        stageReps: Int,
        target: Int?,
        direction: CoachingEvent?,
        previous: LiveRepFeedback? = null,
    ): LiveRepFeedback {
        val measured = assessment.statuses.filter {
            angle != CameraAngle.Unknown && it.cue.id != CueId.SpikeJumpHeight &&
                it.measured?.isFinite() == true && it.score?.isFinite() == true && it.score in 0f..1f
        }
        val fault = measured.filter { it.score!! < 0.8f }.sortedWith(
            compareByDescending<CueLiveStatus> { it.cue.id in plan.focusCueIds }.thenBy { it.score },
        ).firstOrNull()
        val verdict = when {
            // Everything this view reads was read; it just reads too little to judge.
            // "Only part of your form was visible" would blame a body that was in view.
            measured.isEmpty() -> LiveRepVerdict.Unrated
            angle == CameraAngle.Side && assessment.viewLimited && fault == null -> LiveRepVerdict.Partial
            assessment.viewLimited && fault == null -> LiveRepVerdict.Unrated
            assessment.viewLimited -> LiveRepVerdict.Adjust
            !assessment.isAssessable || assessment.score?.isFinite() != true -> LiveRepVerdict.Partial
            fault != null || !assessment.isGood -> LiveRepVerdict.Adjust
            else -> LiveRepVerdict.Good
        }
        val sideCue = if (angle == CameraAngle.Side && !assessment.isAssessable) {
            when {
                measured.isEmpty() -> "Keep your whole body in frame for the next rep."
                fault != null -> "Next rep: " + CoachPhrasebook.action(fault.cue.id, fault.measured!!)
                    .replaceFirstChar { it.lowercase() } + "."
                else -> {
                    val cue = measured.firstOrNull { it.cue.id in plan.focusCueIds } ?: measured.first()
                    "Your ${CoachPhrasebook.focusName(cue.cue.id)} looked good from the side."
                }
            }
        } else null
        val detail = direction?.text ?: sideCue ?: when (verdict) {
            LiveRepVerdict.Unrated -> "Keep your hands and feet in frame for the next rep."
            LiveRepVerdict.Partial -> if (fault != null) "I could only check part of your form. Watch your ${CoachPhrasebook.focusName(fault.cue.id)}."
                else "Counted. Only part of your form was visible."
            LiveRepVerdict.Adjust -> if (fault != null) "Counted. Next rep, work on your ${CoachPhrasebook.focusName(fault.cue.id)}."
                else "Counted. Keep refining your form."
            LiveRepVerdict.Good -> "Form target met."
        }
        val count = CoachPhrasebook.repCount(stageReps, target, totalReps)
        // The rep after a director turn is a quiet retry: the count and nothing else.
        val quiet = direction == null && previous?.directed == true
        val ack = if (direction != null || quiet) null else sideCue ?: CoachPhrasebook.repAck(
            verdict, fault?.cue?.id, totalReps, previous?.ack, firstOfStage = stageReps == 1,
            stayWithFocus = fault != null && fault.cue.id in plan.focusCueIds)
        return LiveRepFeedback(totalReps, stageReps, target, verdict, detail,
            direction?.type ?: CoachingEventType.RepMilestone,
            // A correction names a fault, so it says first that this rep was counted.
            spokenText = CoachPhrasebook.repTurn(count, direction?.let {
                if (it.type == CoachingEventType.Correction) "Counted. ${it.text}" else it.text
            } ?: ack), ack = ack, directed = direction != null)
    }
}
