package com.volleyballlab.player.pose.analysis

import com.volleyballlab.player.domain.model.CameraAngle
import com.volleyballlab.player.domain.model.FormCueCatalog
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.domain.model.isPoseCue
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
        // One row per saved rep: file, rep, start/contact/end seconds, then id,value,inRange per cue.
        val rows = mutableListOf<String>()
        for (file in files) {
            val skill = when (file.name.substringBefore('_')) {
                "serve" -> Skill.Serving; "receive" -> Skill.Receiving; "set" -> Skill.Setting
                "spike" -> Skill.Spiking; "block" -> Skill.Blocking
                else -> continue
            }
            val frames = read(file).ifEmpty { continue }
            val analyzer = SkillAnalyzers.forSkill(skill)
            if (System.getenv("VL_DUMP_SIGNAL") == "1") {
                // Per frame: seconds, the segmentation signal, mean observed knee and elbow angle.
                File(dir, "signals").mkdirs()
                File(dir, "signals/${file.nameWithoutExtension}.csv").writeText(frames.joinToString("\n") { f ->
                    val knees = Side.entries.filter { AnalyzerSupport.legObserved(f, it) }.mapNotNull { AnalyzerSupport.kneeAngle(f, it) }
                    listOf((f.timestampMs - frames.first().timestampMs) / 1000.0, analyzer.segmentationSignal(f) ?: "",
                        knees.takeIf { it.isNotEmpty() }?.average() ?: "", AnalyzerSupport.observedElbowAngle(f) ?: "").joinToString(",")
                })
            }
            // The live after-rep verdict for every rep the live counter counts, assessed
            // with the camera view in the file name, as the capture screen assesses it
            // with the guided stage's view. "PARTIAL" is the "only part of your form
            // was visible" rep. (No receiving model here: it needs an Android context.)
            val view = when (file.nameWithoutExtension.substringAfterLast("__")) {
                "side" -> CameraAngle.Side; "back" -> CameraAngle.Rear; else -> CameraAngle.Front
            }
            val coach = LivePostureCoach(skill, analyzer)
            // The app's one decision, with the drill each skill starts on.
            val judge = RepJudge(skill, if (skill == Skill.Spiking) "spike-standing-swing" else null, analyzer)
            val decide = { window: RepWindow -> judge.judge(window, view) }
            val live = LiveRepCounter(analyzer, judge = decide)
            var partial = 0
            var counted = 0
            frames.forEach { frame ->
                live.onFrame(frame)
                live.lastCompletedWindow?.let { window ->
                    val assessment = coach.assessCompletedRep(window, view)
                    counted++
                    if (!assessment.isAssessable && !assessment.viewLimited) partial++
                    println("    live rep $counted: measured %.2f of %.2f %s, unread %s".format(
                        assessment.measurableWeight, assessment.applicableWeight,
                        if (assessment.isAssessable) "assessed" else if (assessment.viewLimited) "view-limited" else "PARTIAL",
                        assessment.statuses.filter { !it.isMeasurable && it.cue.id.isPoseCue }.map { status ->
                            // Why: the view, a hidden joint, or the analyzer had nothing to read.
                            val at = analyzer.cueFrame(status.cue, window)
                            val why = when {
                                at == null -> "no frame"
                                analyzer.measure(status.cue, window) == null -> "no value"
                                else -> AngleCuePolicy.support(status.cue.id, view, at,
                                    AngleCuePolicy.completedSide(status.cue.id, window)).name
                            }
                            "${status.cue.id}:$why"
                        }))
                }
            }
            println("LIVE ${file.nameWithoutExtension}: view $view, counted $counted, partial $partial")
            val saved = RepSegmenter().segment(frames, analyzer, decide)
            fun at(ms: Long) = (ms - frames.first().timestampMs) / 100 / 10.0
            println("SESSION ${file.nameWithoutExtension}: frames ${frames.size}, live count ${live.count}, " +
                "saved reps ${saved.size}, contacts at ${saved.map { at(it.contactMs) }}")
            // Every candidate the segmenter cut, before the skill's verdict, with that verdict:
            // tells a missed movement apart from one the analyzer refused.
            val everything = object : SkillAnalyzer by analyzer {
                override fun verdict(window: RepWindow) = MovementVerdict.Counted
            }
            RepSegmenter().segment(frames, everything).forEach { window ->
                println("    candidate contact ${at(window.contactMs)}s -> ${decide(window)}")
            }
            // What the app measures on each saved rep, against each cue's practice range.
            val cues = FormCueCatalog.forSkill(skill)
            saved.forEachIndexed { i, rep ->
                val values = cues.map { cue -> cue to analyzer.measure(cue, rep) }
                println("    rep ${i + 1} @ ${at(rep.contactMs)}s " + values.joinToString(" ") { (cue, v) ->
                    "${cue.id}=${v?.let { "%.2f".format(it) } ?: "-"}${if (v == null) "" else if (v in cue.target) "(in)" else "(OUT)"}"
                })
                rows += listOf(file.nameWithoutExtension, i + 1, at(rep.startMs), at(rep.contactMs), at(rep.endMs)).joinToString(",") +
                    values.joinToString("") { (cue, v) -> ",${cue.id},${v ?: ""},${v?.let { it in cue.target } ?: ""}" }
            }
        }
        File(dir, "session_reps.csv").writeText(rows.joinToString("\n"))
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
