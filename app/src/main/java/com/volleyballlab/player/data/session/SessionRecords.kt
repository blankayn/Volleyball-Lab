package com.volleyballlab.player.data.session

import com.volleyballlab.player.domain.model.CameraAngle
import com.volleyballlab.player.domain.model.CueId
import com.volleyballlab.player.domain.model.CueScore
import com.volleyballlab.player.domain.model.RepResult
import com.volleyballlab.player.domain.model.SessionResult
import com.volleyballlab.player.domain.model.SessionAngleStage
import com.volleyballlab.player.domain.model.Skill
import kotlinx.serialization.Serializable

/**
 * The on-disk shape of a stored session.
 *
 * ## Why these exist instead of `@Serializable` on the domain models
 *
 * Conflict 2 in the capstone document is still open: the network model specifies
 * Cloud Firestore and the data dictionary specifies MySQL tables. Persisting
 * sessions at all is a step toward that decision, and keeping the serialised shape
 * in `data/` rather than annotating `SessionResult` is what stops it from becoming
 * the decision. The domain model stays a description of a training session; this
 * file is the only place that says how one is written down, and whichever store the
 * team lands on replaces this file rather than unpicking the domain.
 *
 * ## Enum names are a wire format here
 *
 * [Skill] and [CueId] are stored by [Enum.name], which the KDoc on both already
 * warns about: renaming a constant orphans every session that referenced it. The
 * read path is deliberately tolerant to match - see [CueScoreRecord.toDomain].
 */
@Serializable
internal data class SessionRecord(
    val id: String,
    val skill: String,
    val drillId: String,
    val startedAtEpochMs: Long,
    val durationMs: Long,
    val reps: List<RepRecord>,
    val videoPath: String? = null,
    val angleStages: List<AngleStageRecord> = emptyList(),
    val assignmentId: String? = null,
)

@Serializable
internal data class AngleStageRecord(
    val stageIndex: Int,
    val angle: String,
    val startMs: Long,
    val endMs: Long,
    val liveRepCount: Int,
    val validRepCount: Int,
    val bestStreak: Int,
    val estimatedFormScore: Int? = null,
)

@Serializable
internal data class RepRecord(
    val index: Int,
    val startMs: Long,
    val contactMs: Long,
    val endMs: Long,
    val cueScores: List<CueScoreRecord>,
)

@Serializable
internal data class CueScoreRecord(
    val cueId: String,
    val measured: Float?,
    val score: Float?,
)

internal fun SessionResult.toRecord(): SessionRecord = SessionRecord(
    id = id,
    skill = skill.name,
    drillId = drillId,
    startedAtEpochMs = startedAtEpochMs,
    durationMs = durationMs,
    reps = reps.map { it.toRecord() },
    videoPath = videoPath,
    assignmentId = assignmentId,
    angleStages = angleStages.map { stage ->
        AngleStageRecord(
            stage.stageIndex, stage.angle.name, stage.startMs, stage.endMs,
            stage.liveRepCount, stage.validRepCount, stage.bestStreak, stage.estimatedFormScore,
        )
    },
)

private fun RepResult.toRecord(): RepRecord = RepRecord(
    index = index,
    startMs = startMs,
    contactMs = contactMs,
    endMs = endMs,
    cueScores = cueScores.map { CueScoreRecord(it.cueId.name, it.measured, it.score) },
)

/**
 * Reads a record back, or returns null if it names a skill this build does not have.
 *
 * A session whose skill cannot be resolved has nothing left to be - it cannot be
 * scored, drawn or grouped - so it is dropped. A session whose *cue* cannot be
 * resolved is different: the rest of the reps are still valid measurements, so only
 * the unknown cue is dropped and the session survives with a slightly narrower
 * score. Both paths exist so that a file written by a later version of the app
 * degrades on this one instead of crashing it on launch, which is the same bargain
 * `DataStoreProfileRepository` already makes.
 */
internal fun SessionRecord.toDomain(): SessionResult? {
    val resolvedSkill = enumOrNull<Skill>(skill) ?: return null
    return SessionResult(
        id = id,
        skill = resolvedSkill,
        drillId = drillId,
        startedAtEpochMs = startedAtEpochMs,
        durationMs = durationMs,
        reps = reps.map { it.toDomain() },
        videoPath = videoPath,
        assignmentId = assignmentId,
        angleStages = angleStages.map { stage ->
            SessionAngleStage(
                stage.stageIndex, enumOrNull<CameraAngle>(stage.angle) ?: CameraAngle.Unknown,
                stage.startMs, stage.endMs, stage.liveRepCount, stage.validRepCount,
                stage.bestStreak, stage.estimatedFormScore,
            )
        },
    )
}

private fun RepRecord.toDomain(): RepResult = RepResult(
    index = index,
    startMs = startMs,
    contactMs = contactMs,
    endMs = endMs,
    cueScores = cueScores.mapNotNull { record ->
        val cueId = enumOrNull<CueId>(record.cueId) ?: return@mapNotNull null
        CueScore(cueId = cueId, measured = record.measured, score = record.score)
    },
)

private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
    enumValues<T>().firstOrNull { it.name == name }
