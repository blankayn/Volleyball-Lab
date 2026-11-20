package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.pose.model.Landmark
import com.volleyballlab.player.pose.model.PoseFrame
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Replays whole recorded sessions (not cut single-rep clips) through both rep
 * counters: [LiveRepCounter], frame by frame as the capture screen feeds it, and
 * [RepSegmenter], which the saved session and results screen use. A session that
 * counts reps live but saves none is the failure this exists to catch.
 *
 *     VL_SESSION_DIR=<dir with landmarks/> ./gradlew :app:testDebugUnitTest --tests '*SessionReplay*'
 *
 * Files are `<skill>_<anything>.csv` in PublicClipReplay's format, skill one of
 * serve, receive, set, spike, block. Prints one line per session.
 */
class SessionReplay {

    @Test
    fun replayWholeSessions() {
        val dir = System.getenv("VL_SESSION_DIR")?.let(::File)
        assumeTrue(dir != null && File(dir, "landmarks").isDirectory)
        val files = File(dir, "landmarks").listFiles { f -> f.extension == "csv" }.orEmpty().sortedBy { it.name }
        for (file in files) {
            val skill = when (file.name.substringBefore('_')) {
                "serve" -> Skill.Serving; "receive" -> Skill.Receiving; "set" -> Skill.Setting
                "spike" -> Skill.Spiking; "block" -> Skill.Blocking
                else -> continue
            }
            val frames = read(file).ifEmpty { continue }
            val analyzer = SkillAnalyzers.forSkill(skill)
            val live = LiveRepCounter(analyzer)
            frames.forEach { live.onFrame(it) }
            val saved = RepSegmenter().segment(frames, analyzer)
            fun at(ms: Long) = (ms - frames.first().timestampMs) / 100 / 10.0
            println("SESSION ${file.nameWithoutExtension}: frames ${frames.size}, live count ${live.count}, " +
                "saved reps ${saved.size}, contacts at ${saved.map { at(it.contactMs) }}")
            // Every candidate the segmenter cut, before the skill's verdict, with that verdict:
            // tells a missed movement apart from one the analyzer refused.
            val everything = object : SkillAnalyzer by analyzer {
                override fun verdict(window: RepWindow) = MovementVerdict.Counted
            }
            RepSegmenter().segment(frames, everything).forEach { window ->
                println("    candidate contact ${at(window.contactMs)}s -> ${analyzer.verdict(window)}")
            }
        }
    }

    private fun read(file: File): List<PoseFrame> = file.readLines().filter { it.isNotBlank() }.map { line ->
        val v = line.split(',')
        fun points(offset: Int) = List(PoseFrame.EXPECTED_LANDMARK_COUNT) { i ->
            val b = offset + i * 5
            Landmark(v[b].toFloat(), v[b + 1].toFloat(), v[b + 2].toFloat(), v[b + 3].toFloat(), v[b + 4].toFloat())
        }
        PoseFrame(v[0].toLong(), points(3), points(3 + PoseFrame.EXPECTED_LANDMARK_COUNT * 5), v[1].toInt(), v[2].toInt(), false)
    }
}
