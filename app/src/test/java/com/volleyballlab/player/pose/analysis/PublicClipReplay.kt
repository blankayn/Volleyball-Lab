package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.FormCueCatalog
import com.volleyballlab.player.domain.model.RepPhase
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.pose.model.Landmark
import com.volleyballlab.player.pose.model.PoseFrame
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Replays pose landmarks from public coaching clips through the app's own rep
 * pipeline: the segmenter, the movement check and the cue scoring.
 *
 * It is an evaluation tool, not a regression test, so it runs only on request:
 *
 *     VL_CLIP_REPLAY=1 ./gradlew :app:testDebugUnitTest --tests '*PublicClipReplay*'
 *
 * Input: tools/skill-clip-eval/landmarks/<clip>.csv, written by
 * tools/skill-clip-eval/extract_landmarks.py. The skill comes from the clip-name
 * prefix (serve_, set_, spike_, block_).
 *
 * Output: tools/skill-clip-eval/results/candidates.csv, one row per movement the
 * segmenter found. The movement check is applied after segmentation rather than
 * inside it, so movements the app refuses are listed too, with the reason.
 */
class PublicClipReplay {

    @Test
    fun replayPublicClips() {
        assumeTrue(System.getenv("VL_CLIP_REPLAY") == "1")
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "tools/skill-clip-eval") }
            .firstOrNull { it.isDirectory }
        assumeTrue(root != null)
        val clips = File(root, "landmarks").listFiles { f -> f.extension == "csv" }.orEmpty().sortedBy { it.name }
        assumeTrue(clips.isNotEmpty())

        val rows = mutableListOf(
            listOf("clip", "skill", "start_ms", "contact_ms", "end_ms", "view", "verdict", "form_score") +
                CUE_COLUMNS,
        )
        val scorer = SessionScorer()
        for (clip in clips) {
            val skill = skillOf(clip.name) ?: continue
            val frames = read(clip)
            val real = SkillAnalyzers.forSkill(skill)
            // Same analyzer, but every candidate is let through so refusals can be reported.
            val permissive = object : SkillAnalyzer by real {
                override fun verdict(window: RepWindow): MovementVerdict = MovementVerdict.Counted
            }
            for (window in RepSegmenter().segment(frames, permissive)) {
                val verdict = real.verdict(window)
                val contact = window.frameFor(RepPhase.Contact) ?: window.frames.first()
                val view = CameraOrientationDetector().detect(contact).angle
                val rep = if (verdict == MovementVerdict.Counted) scorer.scoreRep(window, skill, real) else null
                val cues = CUE_COLUMNS.map { column ->
                    rep?.cueScores?.firstOrNull { it.cueId.name == column }?.score?.let { "%.2f".format(it) } ?: ""
                }
                rows += listOf(
                    clip.nameWithoutExtension, skill.name,
                    window.startMs.toString(), window.contactMs.toString(), window.endMs.toString(),
                    view.name,
                    when (verdict) {
                        MovementVerdict.Counted -> "Counted"
                        MovementVerdict.TooSmall -> "TooSmall"
                        is MovementVerdict.NotCounted -> "NotCounted:${verdict.fault.name}"
                    },
                    rep?.formScore(skill)?.toString() ?: "",
                ) + cues
            }
        }
        val out = File(root, "results").apply { mkdirs() }
        File(out, "candidates.csv").writeText(rows.joinToString("\n") { it.joinToString(",") } + "\n")
        println("PublicClipReplay: ${rows.size - 1} movements from ${clips.size} clips")
    }

    private fun skillOf(name: String): Skill? = when {
        name.startsWith("serve_") -> Skill.Serving
        name.startsWith("set_") -> Skill.Setting
        name.startsWith("spike_") -> Skill.Spiking
        name.startsWith("block_") -> Skill.Blocking
        else -> null
    }

    private fun read(file: File): List<PoseFrame> = file.readLines().filter { it.isNotBlank() }.map { line ->
        val v = line.split(',')
        fun points(offset: Int) = List(PoseFrame.EXPECTED_LANDMARK_COUNT) { i ->
            val b = offset + i * 5
            Landmark(v[b].toFloat(), v[b + 1].toFloat(), v[b + 2].toFloat(), v[b + 3].toFloat(), v[b + 4].toFloat())
        }
        PoseFrame(
            timestampMs = v[0].toLong(),
            image = points(3),
            world = points(3 + PoseFrame.EXPECTED_LANDMARK_COUNT * 5),
            imageWidth = v[1].toInt(),
            imageHeight = v[2].toInt(),
            mirrored = false,
        )
    }

    private companion object {
        val CUE_COLUMNS: List<String> = Skill.entries
            .filter { it != Skill.Receiving }
            .flatMap { FormCueCatalog.forSkill(it) }
            .map { it.id.name }
            .distinct()
    }
}
