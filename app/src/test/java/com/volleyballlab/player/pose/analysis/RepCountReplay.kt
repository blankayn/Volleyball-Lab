package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.pose.model.Landmark
import com.volleyballlab.player.pose.model.PoseFrame
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Replays labelled single-rep clips (`<skill>_<label>_<id>_<start>__<view>.csv`,
 * PublicClipReplay's format) through the live [LiveRepCounter], frame by frame as
 * the capture screen feeds it, and reports how many clips produce a counted rep.
 *
 *     VL_ORIENTATION_DIR=<dir with landmarks/> ./gradlew :app:testDebugUnitTest --tests '*RepCountReplay*'
 *
 * Each clip is a 2-6 s cut around one demonstrated rep, so a counted rep per clip
 * is the expectation. Writes `<dir>/rep_counts.csv` and prints a summary.
 */
class RepCountReplay {

    @Test
    fun replayLiveCounting() {
        val dir = System.getenv("VL_ORIENTATION_DIR")?.let(::File)
        assumeTrue(dir != null && File(dir, "landmarks").isDirectory)
        val clips = File(dir, "landmarks").listFiles { f -> f.extension == "csv" }.orEmpty().sortedBy { it.name }
        val rows = mutableListOf("clip,skill,view,frames,counted,rejections")
        data class Tally(var clips: Int = 0, var withRep: Int = 0, var reps: Int = 0, val refusals: MutableMap<String, Int> = sortedMapOf())
        val tallies = sortedMapOf<String, Tally>()
        for (clip in clips) {
            val skill = when (clip.name.substringBefore('_')) {
                "serve" -> Skill.Serving; "spike" -> Skill.Spiking; "set" -> Skill.Setting; "block" -> Skill.Blocking
                else -> continue
            }
            val view = clip.nameWithoutExtension.substringAfterLast("__")
            val raw = read(clip).ifEmpty { continue }
            // VL_PAD=1 holds the first and last pose for a second either side, as a live
            // camera would keep running before and after a rep that a cut clip trims away.
            val frames = if (System.getenv("VL_PAD") == "1") {
                val step = 33L
                (20 downTo 1).map { raw.first().copy(timestampMs = raw.first().timestampMs - it * step) } + raw +
                    (1..30).map { raw.last().copy(timestampMs = raw.last().timestampMs + it * step) }
            } else raw
            val counter = LiveRepCounter(SkillAnalyzers.forSkill(skill))
            val refusals = mutableListOf<String>()
            frames.forEach { frame ->
                counter.onFrame(frame)
                counter.lastRejection?.let { refusals += it.name }
            }
            rows += "${clip.nameWithoutExtension},${skill.name},$view,${frames.size},${counter.count},${refusals.joinToString("|")}"
            val t = tallies.getOrPut("${skill.name} $view") { Tally() }
            t.clips++; t.reps += counter.count
            if (counter.count > 0) t.withRep++
            refusals.distinct().forEach { t.refusals[it] = (t.refusals[it] ?: 0) + 1 }
        }
        File(dir, "rep_counts.csv").writeText(rows.joinToString("\n"))
        tallies.forEach { (key, t) ->
            println("REPS $key: clips ${t.clips}, clips with a counted rep ${t.withRep} (${100 * t.withRep / t.clips}%), " +
                "reps counted ${t.reps}, clips with a refused movement ${t.refusals}")
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
