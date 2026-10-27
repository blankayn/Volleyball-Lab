package com.volleyballlab.player.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.AssignmentTurnedIn
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Home
import androidx.compose.ui.graphics.vector.ImageVector
import com.volleyballlab.player.R
import com.volleyballlab.player.domain.model.Skill
import kotlin.reflect.KClass
import kotlinx.serialization.Serializable

/**
 * Every destination in the app, as type-safe routes.
 *
 * These are `@Serializable` classes rather than route strings. The difference is
 * not cosmetic: with strings, a destination that expects a drill id and receives
 * a skill name is a runtime crash on a screen three taps deep, and the compiler
 * cannot see it. Here, `navigate(CaptureRoute(skill, drillId))` either compiles
 * or it does not.
 */

@Serializable
data object SplashRoute

/**
 * The account screens.
 *
 * Outside the tab graph on purpose: they have no bottom bar, and an athlete who has
 * not signed in has nothing to switch tabs to.
 */
@Serializable
data object LoginRoute

@Serializable
data object SignUpRoute

/** First launch only: the product introduction shown before an account exists. */
@Serializable
data object WelcomeRoute

/** Onboarding. Shown once per install, then never again unless retaken. */
@Serializable
data object AssessmentRoute

@Serializable
data object HomeRoute

/** Parent graph for the Train tab, so the whole flow shares one back stack. */
@Serializable
data object TrainGraph

@Serializable
data object SkillSelectRoute

@Serializable
data class DrillSelectRoute(val skill: Skill)

@Serializable data object DrillLibraryRoute
@Serializable data class DrillDetailRoute(val drillId: String)
@Serializable data class DrillPracticeRoute(val drillId: String, val sets: Int = 3, val reps: Int = 10, val restSeconds: Int = 30, val resumeId: String? = null)
@Serializable data class DrillPracticeSummaryRoute(val workoutId: String)

@Serializable
data class CaptureRoute(val skill: Skill, val drillId: String, val returnToDrillId: String? = null, val assignmentId: String? = null)

@Serializable
data class SessionResultRoute(val sessionId: String, val returnToDrillId: String? = null,
    val assignmentId: String? = null, val returnToSummary: Boolean = false,
    val summaryFromProgress: Boolean = false)

@Serializable
data class SessionSummaryRoute(val sessionId: String, val fromProgress: Boolean = false)

@Serializable
data object ProgressRoute

@Serializable
data object CoachRoute

@Serializable
data object CoachDirectoryRoute

@Serializable
data class CoachProfileRoute(val coachUid: String)

@Serializable
data class PlayerAssignmentRoute(val assignmentId: String)

@Serializable
data object ProfileRoute

@Serializable
data object EditAccountRoute

// ---------------------------------------------------------------------------
// Coach mode
//
// A parallel set of destinations, not a branch inside the athlete's. The two
// shells share the splash and the auth screens and nothing after them: a coach
// has no capture flow and a player has no review queue, and a single graph
// holding both would need every screen to ask which kind of account it was
// serving. Which graph gets built is decided once, from the role, in
// `VolleyballLabApp`.
// ---------------------------------------------------------------------------

/** Figure 25. The review queue and the team's numbers. */
@Serializable
data object CoachDashboardRoute

/** Figures 26 and 27: the roster, and the join requests waiting on it. */
@Serializable
data object CoachRosterRoute

/** One athlete in full. The storyboard's player-detail screen. */
@Serializable
data class CoachPlayerRoute(val playerUid: String)

/**
 * One submitted session, scored, with the box to answer it in.
 *
 * Carries the player uid as well as the session id because a session id alone is
 * not enough to find a recording: sessions live in per-athlete stores, and the
 * lookup needs to know whose.
 */
@Serializable
data class CoachReviewRoute(val playerUid: String, val sessionId: String)

/** Parent graph for the Assign tab, so the form and the list share a back stack. */
@Serializable
data object CoachAssignGraph

@Serializable
data object CoachAssignListRoute

/**
 * Figure 28's assignment form.
 *
 * @param playerUid pre-selects the athlete when the form was opened from their
 *   profile rather than from the Assign tab. Empty means "ask".
 * @param assignmentId non-empty when editing an existing assignment.
 */
@Serializable
data class CoachAssignEditRoute(val playerUid: String = "", val assignmentId: String = "")

/** Figure 29. The coach's own account, credentials and notification switches. */
@Serializable
data object CoachAccountRoute

/**
 * The five tabs, in the order Figure 11 of the capstone document specifies.
 *
 * The document contradicts itself here - the storyboard instead lists Home,
 * Capture, Drills, Progress, Profile - and Figure 11 was chosen. The deciding
 * argument is that the storyboard splits skill selection and camera capture
 * across two separate tabs, which breaks the training flow into two entry points
 * for what is one task, and leaves no home for the results screen.
 *
 * @param titleRes the tab label, also used as its accessibility label.
 */
enum class TopLevelDestination(
    val route: Any,
    val graphClass: KClass<*>,
    @param:StringRes val titleRes: Int,
    val icon: ImageVector,
) {
    Home(HomeRoute, HomeRoute::class, R.string.nav_home, Icons.Rounded.Home),
    Train(TrainGraph, TrainGraph::class, R.string.nav_train, Icons.Rounded.FitnessCenter),
    Progress(ProgressRoute, ProgressRoute::class, R.string.nav_progress, Icons.Rounded.BarChart),
    Coach(CoachRoute, CoachRoute::class, R.string.nav_coach, Icons.Rounded.Group),
    Profile(ProfileRoute, ProfileRoute::class, R.string.nav_profile, Icons.Rounded.AccountCircle);

    companion object {
        val All: List<TopLevelDestination> = entries
    }
}

/**
 * The coach's tabs.
 *
 * Four rather than the athlete's five, and the difference is the point: a coach
 * does not capture, so there is no Train tab, and the two things a coach does that
 * an athlete does not - triaging a queue and setting work - get one each.
 *
 * The document draws these as separate pages of a web portal (Figures 25 to 29)
 * with a sidebar. A sidebar does not survive being 390dp wide, so the same five
 * pages become four tabs plus a detail stack: Figures 26 and 27 are one tab,
 * because a roster and the requests to join it are the same question asked twice.
 */
enum class CoachTabDestination(
    val route: Any,
    val graphClass: KClass<*>,
    @param:StringRes val titleRes: Int,
    val icon: ImageVector,
) {
    Dashboard(
        CoachDashboardRoute,
        CoachDashboardRoute::class,
        R.string.nav_coach_dashboard,
        Icons.Rounded.Dashboard,
    ),
    Roster(
        CoachRosterRoute,
        CoachRosterRoute::class,
        R.string.nav_coach_roster,
        Icons.Rounded.Group,
    ),
    Assign(
        CoachAssignGraph,
        CoachAssignGraph::class,
        R.string.nav_coach_assign,
        Icons.Rounded.AssignmentTurnedIn,
    ),
    Account(
        CoachAccountRoute,
        CoachAccountRoute::class,
        R.string.nav_coach_account,
        Icons.Rounded.AccountCircle,
    );

    companion object {
        val All: List<CoachTabDestination> = entries
    }
}
