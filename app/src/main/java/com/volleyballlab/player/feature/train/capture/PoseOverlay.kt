package com.volleyballlab.player.feature.train.capture

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.volleyballlab.player.R
import com.volleyballlab.player.pose.analysis.LivePostureUi
import com.volleyballlab.player.pose.model.Landmark
import com.volleyballlab.player.pose.model.BodyPoint
import com.volleyballlab.player.pose.model.PoseFrame
import com.volleyballlab.player.pose.model.SkeletonBones
import com.volleyballlab.player.ui.theme.VbMotion
import com.volleyballlab.player.ui.theme.vbColors
import com.volleyballlab.player.ui.theme.vbTween

/**
 * Draws the tracked skeleton over the camera preview.
 *
 * The bones tint with the live posture verdict while recording: green when the
 * form reads good, amber/red when the coach card is showing a correction. The
 * athlete reads the skeleton's colour peripherally while moving, and the card's
 * words when they glance over - two channels, one verdict.
 *
 * ## Matching the preview's crop
 *
 * Landmarks arrive normalised to the *analysis image*, while this Canvas is the
 * size of the *preview view*. Those two rarely share an aspect ratio, and
 * [androidx.camera.view.PreviewView.ScaleType.FILL_CENTER] crops the overflow
 * rather than letterboxing it. So the same centre-crop transform is reproduced
 * here. Naively stretching normalised coordinates across the Canvas instead is
 * the standard bug in this kind of overlay: the skeleton lines up in the middle
 * of the frame and drifts progressively further off the body toward the edges.
 *
 * ## Temporal smoothing is not done here
 *
 * MediaPipe's Pose Landmarker already smooths landmarks across frames in
 * live-stream mode. Adding a second filter on top would add visible lag between
 * the athlete and their skeleton for no gain in stability.
 */
@Composable
fun PoseOverlay(
    frame: PoseFrame?,
    posture: LivePostureUi? = null,
    modifier: Modifier = Modifier,
) {
    val extended = MaterialTheme.vbColors
    val measuredStatuses = posture?.takeUnless {
        it.trackingLost || it.modelWarmingUp || it.modelUncertain
    }?.statuses.orEmpty().filter { it.isMeasurable }
    fun regionColor(points: Set<BodyPoint>, default: Color): Color {
        val score = measuredStatuses.filter { status -> points.all { it in status.affectedPoints } }
            .mapNotNull { it.score }.minOrNull() ?: return default
        return when {
            score < SEVERE_POSTURE_SCORE -> Color(0xFFFF6B5E)
            score < 0.8f -> extended.cueWarn
            else -> extended.cueGood
        }
    }
    val jointColor = extended.poseJoint
    val description = stringResource(R.string.cd_pose_overlay)

    // Fades the whole skeleton rather than letting it blink out when tracking
    // drops for a frame or two, which is common when the athlete turns away.
    val alpha by animateFloatAsState(
        targetValue = if (frame == null) 0f else 1f,
        animationSpec = vbTween(durationMillis = VbMotion.Short),
        label = "poseOverlayAlpha",
    )

    Canvas(
        modifier = modifier.semantics { contentDescription = description },
    ) {
        if (frame == null || alpha == 0f) return@Canvas
        val transform = CenterCropTransform.from(
            canvas = size,
            imageWidth = frame.imageWidth.toFloat(),
            imageHeight = frame.imageHeight.toFloat(),
        )

        SkeletonBones.forEach { (from, to) ->
            val a = frame.image(from)
            val b = frame.image(to)
            if (!a.isReliable() || !b.isReliable()) return@forEach
            drawLine(
                color = regionColor(setOf(from, to), extended.poseBone).copy(alpha = alpha * BONE_ALPHA),
                start = transform.map(a, frame.mirrored),
                end = transform.map(b, frame.mirrored),
                strokeWidth = BONE_WIDTH_DP.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }

        // Joints drawn after bones so the dots sit on top of the segments rather
        // than being half-covered by them.
        frame.image.forEachIndexed { index, landmark ->
            if (!landmark.isReliable()) return@forEachIndexed
            // Face landmarks are skipped: at overlay scale the eleven head points
            // merge into an unreadable blob, and no cue reads from them.
            if (index in FACE_LANDMARK_RANGE) return@forEachIndexed
            drawJoint(
                centre = transform.map(landmark, frame.mirrored),
                color = regionColor(setOf(BodyPoint.All[index]), jointColor).copy(alpha = alpha),
            )
        }
    }
}

private fun DrawScope.drawJoint(centre: Offset, color: Color) {
    drawCircle(
        color = color,
        radius = JOINT_RADIUS_DP.dp.toPx(),
        center = centre,
        style = Stroke(width = JOINT_STROKE_DP.dp.toPx()),
    )
}

/**
 * The skeleton's colour for the current posture verdict.
 *
 * Idle (no live judging yet, e.g. framing) and tracking-lost keep the default
 * bone colour: tinting on an unjudged frame would claim knowledge the app does
 * not have. While judging, green means the card reads "looks good" and amber/red
 * means it is showing a correction - severe misses (< 0.35) take the error tone.
 */
private fun postureBoneColor(
    posture: LivePostureUi?,
    default: Color,
    good: Color,
    warn: Color,
): Color {
    if (
        posture == null || posture.trackingLost || posture.modelWarmingUp ||
        posture.modelUncertain || posture.statuses.isEmpty()
    ) return default
    if (posture.needsCorrection) {
        return if ((posture.score ?: 1f) < SEVERE_POSTURE_SCORE) Color(0xFFFF6B5E) else warn
    }
    return good
}

private const val SEVERE_POSTURE_SCORE = 0.35f

/**
 * The centre-crop mapping from normalised image space to Canvas space.
 *
 * Extracted as a value class rather than inlined so it is computed once per draw
 * instead of once per landmark, and so the transform can be reasoned about (and
 * corrected) in one place.
 */
private data class CenterCropTransform(
    val scaledWidth: Float,
    val scaledHeight: Float,
    val offsetX: Float,
    val offsetY: Float,
) {
    /**
     * @param mirrored horizontally flips the x coordinate. The front camera's
     *   preview is mirrored so the athlete sees themselves as in a mirror, and
     *   the overlay has to match. The landmark's anatomical identity is
     *   unaffected: a left wrist stays the left wrist, it is just drawn on the
     *   other side of the screen.
     */
    fun map(landmark: Landmark, mirrored: Boolean): Offset {
        val x = if (mirrored) 1f - landmark.x else landmark.x
        return Offset(
            x = offsetX + x * scaledWidth,
            y = offsetY + landmark.y * scaledHeight,
        )
    }

    companion object {
        fun from(canvas: Size, imageWidth: Float, imageHeight: Float): CenterCropTransform {
            if (imageWidth <= 0f || imageHeight <= 0f) {
                return CenterCropTransform(canvas.width, canvas.height, 0f, 0f)
            }
            // max, not min: FILL_CENTER scales until both axes are covered and
            // lets the longer one overflow. min would letterbox instead.
            val scale = maxOf(canvas.width / imageWidth, canvas.height / imageHeight)
            val scaledWidth = imageWidth * scale
            val scaledHeight = imageHeight * scale
            return CenterCropTransform(
                scaledWidth = scaledWidth,
                scaledHeight = scaledHeight,
                offsetX = (canvas.width - scaledWidth) / 2f,
                offsetY = (canvas.height - scaledHeight) / 2f,
            )
        }
    }
}

/** Indices 0 through 10 are the face cluster in the BlazePose topology. */
private val FACE_LANDMARK_RANGE = 0..10
private const val BONE_WIDTH_DP = 4
private const val BONE_ALPHA = 0.9f
private const val JOINT_RADIUS_DP = 5
private const val JOINT_STROKE_DP = 3
