package com.volleyballlab.player.domain.model

import androidx.annotation.Keep
import androidx.annotation.StringRes
import com.volleyballlab.player.R
import kotlinx.serialization.Serializable

/**
 * The five fundamentals in the capstone's MVP scope.
 *
 * The document is explicit that the MVP covers these and only these. Adding a
 * sixth here is not a small change: every entry needs a cue set in
 * [FormCueCatalog], a [com.volleyballlab.player.pose.analysis.SkillAnalyzer]
 * implementation, and a rep-segmentation signal. The compiler will point at all
 * three, which is the reason this is an enum and not a string.
 *
 * `Receiving` is the document's term for forearm passing, kept as-is so the code
 * and the paper use the same vocabulary during the defence.
 */
/**
 * [Keep] is load-bearing, not decorative. This enum is a type-safe navigation
 * argument, and Navigation resolves enum arguments by name at runtime. In a
 * minified release build R8 would rename the constants, and every navigation into
 * the drill or capture screen would fail - in release only, where it is hardest
 * to notice before a defence.
 */
@Keep
@Serializable
enum class Skill(
    @param:StringRes val displayName: Int,
    @param:StringRes val description: Int,
) {
    Serving(R.string.skill_serving, R.string.skill_serving_desc),
    Receiving(R.string.skill_receiving, R.string.skill_receiving_desc),
    Setting(R.string.skill_setting, R.string.skill_setting_desc),
    Spiking(R.string.skill_spiking, R.string.skill_spiking_desc),
    Blocking(R.string.skill_blocking, R.string.skill_blocking_desc);

    companion object {
        val All: List<Skill> = entries
    }
}
