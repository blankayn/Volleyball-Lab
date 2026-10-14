package com.volleyballlab.player.domain.model

import androidx.annotation.Keep
import androidx.annotation.StringRes
import com.volleyballlab.player.R

/**
 * Which tier an account is on.
 *
 * This is conflict 6 in the capstone document finally given a shape. The document
 * gates features behind a Pro plan in the UI mockups (Figures 17 and 19) and puts
 * subscriptions in the Business Model Canvas, but defines no plan or quota entity
 * anywhere in the data dictionary. Until the backend exists this enum is that
 * entity, stored per uid on the device.
 *
 * [Keep] because it is persisted by [Enum.name]; R8 renaming the constants in a
 * release build would strand every stored plan.
 */
@Keep
enum class UserPlan(@param:StringRes val displayName: Int) {
    Free(R.string.plan_free),
    Pro(R.string.plan_pro);

    val isPro: Boolean get() = this == Pro

    companion object {
        /**
         * Reads a stored name back, treating anything unrecognised as [Free].
         *
         * Erring toward the *lower* tier is the only safe direction: a corrupt file
         * that read as Pro would hand out a paid feature, whereas one that reads as
         * Free costs the user an upgrade tap they have already made. The second is a
         * bug; the first is giving away the product.
         */
        fun fromNameOrFree(name: String?): UserPlan =
            entries.firstOrNull { it.name == name } ?: Free
    }
}

/**
 * What Pro actually unlocks, as the upgrade sheet lists it.
 *
 * Held as data rather than as copy baked into the sheet because the *first* entry
 * is a real gate enforced in code - coach connection - and the rest are claims the
 * document makes that this build does not yet implement. Keeping them in one list
 * with [enforced] on each is what stops the sheet quietly implying that all five
 * are live.
 *
 * @param enforced true when this build genuinely gates the feature. False entries
 *   are roadmap, and the sheet marks them as such rather than listing them beside
 *   the real one as though they were equivalent.
 */
enum class ProFeature(
    @param:StringRes val title: Int,
    @param:StringRes val body: Int,
    val enforced: Boolean,
) {
    CoachConnection(
        R.string.pro_feature_coach_title,
        R.string.pro_feature_coach_body,
        enforced = true,
    ),
    SessionComments(
        R.string.pro_feature_comments_title,
        R.string.pro_feature_comments_body,
        enforced = true,
    ),
    DetailedCorrections(
        R.string.pro_feature_corrections_title,
        R.string.pro_feature_corrections_body,
        enforced = false,
    ),
    FullDrillLibrary(
        R.string.pro_feature_drills_title,
        R.string.pro_feature_drills_body,
        enforced = true,
    ),
    AdvancedBiomechanics(
        R.string.pro_feature_biomech_title,
        R.string.pro_feature_biomech_body,
        enforced = false,
    );

    companion object {
        val All: List<ProFeature> = entries

        /** The ones this build actually enforces. Shown first, and unqualified. */
        val Live: List<ProFeature> = entries.filter { it.enforced }

        /** Claimed by the document, not built yet. Shown marked. */
        val Planned: List<ProFeature> = entries.filterNot { it.enforced }
    }
}
