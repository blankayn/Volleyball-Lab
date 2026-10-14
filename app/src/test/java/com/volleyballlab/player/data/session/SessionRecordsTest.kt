package com.volleyballlab.player.data.session

import com.volleyballlab.player.domain.model.SessionResult
import com.volleyballlab.player.domain.model.CameraAngle
import com.volleyballlab.player.domain.model.SessionAngleStage
import com.volleyballlab.player.domain.model.Skill
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionRecordsTest {
    @Test fun assignmentRecordingCanBeResumedAfterSerialization() {
        val original = SessionResult("task-recording", Skill.Setting, "set-basic", 100, 1_000, emptyList(), assignmentId = "coach-task-1")
        val restored = Json.decodeFromString<SessionRecord>(Json.encodeToString(original.toRecord())).toDomain()
        assertEquals(original, restored)
    }

    @Test fun videoPathSurvivesSessionSerialization() {
        val session = SessionResult(
            id = "recording-1",
            skill = Skill.Serving,
            drillId = "serve-basic",
            startedAtEpochMs = 100,
            durationMs = 1_000,
            reps = emptyList(),
            videoPath = "/private/training_videos/recording-1.mp4",
        )
        val decoded = Json.decodeFromString<SessionRecord>(
            Json.encodeToString(session.toRecord())
        ).toDomain()
        assertEquals(session.videoPath, decoded?.videoPath)
    }

    @Test fun oldSessionWithoutVideoPathStillLoads() {
        val oldJson = """{"id":"old","skill":"Serving","drillId":"serve-basic","startedAtEpochMs":100,"durationMs":1000,"reps":[]}"""
        assertNull(Json.decodeFromString<SessionRecord>(oldJson).toDomain()?.videoPath)
        assertNull(Json.decodeFromString<SessionRecord>(oldJson).toDomain()?.assignmentId)
        assertEquals(emptyList<SessionAngleStage>(), Json.decodeFromString<SessionRecord>(oldJson).toDomain()?.angleStages)
    }

    @Test fun guidedStagesSurviveSerializationWithMonotonicTimesAndUnassessedScore() {
        val session = SessionResult(
            "guided", Skill.Setting, "set-basic", 1_700_000_000_000L, 40_000L, emptyList(),
            angleStages = listOf(
                SessionAngleStage(0, CameraAngle.Front, 510_000L, 525_000L, 5, 3, 2, 78),
                SessionAngleStage(1, CameraAngle.Side, 530_000L, 550_000L, 2, 0, 0, null),
            ),
        )
        val restored = Json.decodeFromString<SessionRecord>(
            Json.encodeToString(session.toRecord())
        ).toDomain()
        assertEquals(session, restored)
    }

    @Test fun futureCameraAngleLoadsAsUnknownWithoutDroppingStage() {
        val json = """{"id":"future","skill":"Serving","drillId":"serve-basic","startedAtEpochMs":100,"durationMs":1000,"reps":[],"angleStages":[{"stageIndex":0,"angle":"Diagonal","startMs":8000,"endMs":9000,"liveRepCount":1,"validRepCount":0,"bestStreak":0}]}"""
        val restored = Json.decodeFromString<SessionRecord>(json).toDomain()
        assertEquals(CameraAngle.Unknown, restored?.angleStages?.single()?.angle)
        assertNull(restored?.angleStages?.single()?.estimatedFormScore)
    }
}
