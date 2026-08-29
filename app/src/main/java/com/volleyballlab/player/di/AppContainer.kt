package com.volleyballlab.player.di

import android.content.Context
import com.volleyballlab.player.R
import com.google.firebase.auth.FirebaseAuth
import com.volleyballlab.player.data.assessment.DataStoreAssessmentRepository
import com.volleyballlab.player.data.auth.DemoSignIn
import com.volleyballlab.player.data.auth.FirebaseAuthRepository
import com.volleyballlab.player.data.auth.GoogleCredentialClient
import com.volleyballlab.player.data.coach.DataStoreCoachRepository
import com.volleyballlab.player.data.profile.DataStoreProfileRepository
import com.volleyballlab.player.data.plan.DataStorePlanRepository
import com.volleyballlab.player.data.role.DataStoreRoleRepository
import com.volleyballlab.player.data.session.DataStoreSessionRepository
import com.volleyballlab.player.data.skill.StaticDrillRepository
import com.volleyballlab.player.domain.repository.AssessmentRepository
import com.volleyballlab.player.domain.repository.AuthRepository
import com.volleyballlab.player.domain.repository.CoachRepository
import com.volleyballlab.player.domain.repository.DrillRepository
import com.volleyballlab.player.domain.repository.ProfileRepository
import com.volleyballlab.player.domain.repository.PlanRepository
import com.volleyballlab.player.domain.repository.RoleRepository
import com.volleyballlab.player.domain.repository.SessionDirectory
import com.volleyballlab.player.domain.repository.SessionRepository
import com.volleyballlab.player.pose.MediaPipePoseDetector
import com.volleyballlab.player.pose.PoseDetector
import com.volleyballlab.player.pose.SyntheticPoseDetector
import com.volleyballlab.player.pose.analysis.SessionScorer

/**
 * How a [PoseDetector] gets built.
 *
 * A factory rather than a shared instance because a detector owns native
 * resources and a GPU context. It is created when the camera screen opens and
 * closed when it leaves, and holding one for the process lifetime would keep the
 * GPU delegate alive behind every other screen in the app.
 */
fun interface PoseDetectorFactory {
    /** @throws com.volleyballlab.player.pose.PoseDetectorUnavailableException */
    fun create(): PoseDetector
}

/**
 * The application's object graph, wired by hand.
 *
 * ## Why not Hilt
 *
 * Hilt is the industry default and would work here. It is left out for two
 * reasons specific to this project:
 *
 * 1. **It costs an annotation processor.** Hilt means KSP in the build, and this
 *    project deliberately has none. On a student laptop that is a real difference
 *    in build time on every single edit, and it is one more moving part that can
 *    break a Gradle sync the week of a defence.
 * 2. **The graph is this small.** A handful of repositories, a scorer and a
 *    factory. Hand wiring is under a hundred lines and every dependency is visible
 *    in one file, with no generated code to explain to a panel.
 *
 * The tradeoff is real and worth stating plainly: if this app grows past roughly
 * a dozen dependencies, or gains scoped graphs beyond process and screen, hand
 * wiring stops paying for itself and this should become a Hilt module. Coach mode
 * took it from four repositories to seven, which is the closest this has come to
 * that line. Because every consumer already depends on interfaces and takes them
 * through a constructor, that migration is mechanical.
 */
class AppContainer(private val context: Context) {

    val drillRepository: DrillRepository by lazy { StaticDrillRepository() }

    /**
     * Persisted, as of the dashboard work. `InMemorySessionRepository` is still in
     * the tree and is what the unit tests use - it needs no Android context - but
     * the app no longer forgets the athlete's training when the process dies.
     */
    val sessionRepository: SessionRepository by lazy {
        DataStoreSessionRepository(context, authRepository)
    }

    /**
     * The same object as [sessionRepository], exposed through its other interface.
     *
     * One instance, because two `DataStore`s over one file throws. Two properties,
     * because the two capabilities are not the same permission: everything reads
     * [sessionRepository] and only the coach shell reads this. See [SessionDirectory]
     * for what that distinction is protecting.
     */
    val sessionDirectory: SessionDirectory by lazy {
        sessionRepository as DataStoreSessionRepository
    }

    /**
     * Takes the application context, not an activity one. DataStore outlives any
     * single screen, and holding an activity here would leak it for the life of
     * the process.
     */
    val profileRepository: ProfileRepository by lazy {
        DataStoreProfileRepository(context, authRepository)
    }

    val assessmentRepository: AssessmentRepository by lazy {
        DataStoreAssessmentRepository(context, authRepository)
    }

    /**
     * Which tier the account is on, and therefore whether coach connection is
     * unlocked. Conflict 6 in the capstone document, modelled on the device until
     * there is a billing system to be the real source of truth.
     */
    val planRepository: PlanRepository by lazy {
        DataStorePlanRepository(context, authRepository)
    }

    /**
     * Which shell the account sees. Read on the cold-start path, before the
     * navigation graph exists, so it is deliberately backed by its own small file.
     */
    val roleRepository: RoleRepository by lazy {
        DataStoreRoleRepository(context, authRepository)
    }

    /**
     * The team, its roster, its assignments and its feedback.
     *
     * Takes [profileRepository] because a join request copies the player's name and
     * position onto the roster row at the moment they ask - see `RosterMember` for
     * why that is a copy rather than a lookup.
     */
    val coachRepository: CoachRepository by lazy {
        DataStoreCoachRepository(context, authRepository, profileRepository)
    }

    /**
     * Firebase initialises itself from `app/google-services.json` through its own
     * ContentProvider before any of this runs, so there is nothing to start here.
     */
    val authRepository: AuthRepository by lazy {
        FirebaseAuthRepository(FirebaseAuth.getInstance(), context)
    }

    /**
     * One-tap entry into either standing test account.
     *
     * The only collaborator here that is not a repository, and it earns that by
     * being the one place three screens in two shells share - see [DemoSignIn].
     * Debug-only in effect: every control that reaches it is behind
     * `BuildConfig.DEBUG`.
     */
    val demoSignIn: DemoSignIn by lazy {
        DemoSignIn(
            authRepository = authRepository,
            roleRepository = roleRepository,
            profileRepository = profileRepository,
            coachRepository = coachRepository,
        )
    }

    /**
     * The web client id is a generated resource, not a pasted constant, so it
     * cannot drift from `app/google-services.json`.
     */
    val googleCredentialClient: GoogleCredentialClient by lazy {
        GoogleCredentialClient(context.getString(R.string.default_web_client_id))
    }

    val sessionScorer: SessionScorer by lazy { SessionScorer() }

    /** The real detector. Throws if the model asset is missing. */
    val poseDetectorFactory = PoseDetectorFactory {
        MediaPipePoseDetector.create(context)
    }

    /**
     * A scripted detector that ignores the camera entirely.
     *
     * Offered to the capture screen only as an explicit, user-chosen fallback
     * after the real detector has failed, and only in debug builds. It is never
     * substituted silently: an app that quietly switches to fake pose data would
     * produce scores that look real and mean nothing, which is far worse than an
     * error message.
     */
    val syntheticDetectorFactory = PoseDetectorFactory { SyntheticPoseDetector() }
}
