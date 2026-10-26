package com.volleyballlab.player.di

import android.content.Context
import com.volleyballlab.player.R
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.volleyballlab.player.data.assessment.DataStoreAssessmentRepository
import com.volleyballlab.player.data.ai.FirebaseAiCoachRepository
import com.volleyballlab.player.data.auth.FirebaseAuthRepository
import com.volleyballlab.player.data.auth.GoogleCredentialClient
import com.volleyballlab.player.data.cloud.cloudRoleOf
import com.volleyballlab.player.data.coach.FirestoreCoachRepository
import com.volleyballlab.player.data.coaching.DataStoreCoachingPreferencesRepository
import com.volleyballlab.player.data.profile.DataStoreProfileRepository
import com.volleyballlab.player.data.profile.AssetAddressCatalog
import com.volleyballlab.player.domain.repository.AddressCatalog
import com.volleyballlab.player.data.role.DataStoreRoleRepository
import com.volleyballlab.player.data.session.DataStoreSessionRepository
import com.volleyballlab.player.data.session.VideoReviewCache
import com.volleyballlab.player.data.session.FirestoreSessionCloudStore
import com.volleyballlab.player.data.session.FirestoreSessionDirectory
import com.volleyballlab.player.data.skill.StaticDrillRepository
import com.volleyballlab.player.domain.repository.AssessmentRepository
import com.volleyballlab.player.domain.repository.AiCoachRepository
import com.volleyballlab.player.domain.repository.AuthRepository
import com.volleyballlab.player.domain.repository.CoachRepository
import com.volleyballlab.player.domain.repository.CoachingPreferencesRepository
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
import com.volleyballlab.player.pose.analysis.LiteRtReceivingReadyModel
import com.volleyballlab.player.pose.analysis.ReceivingReadyPostureEvaluator
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

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
    val videoReviewStore = VideoReviewCache(context)
    val accountDeletionService by lazy {
        com.volleyballlab.player.data.account.AccountDeletionService(context, authRepository, profileRepository, sessionRepository)
    }
    val sharedRecordingStore by lazy {
        com.volleyballlab.player.data.session.SharedRecordingStore(context, authRepository, firestore, videoReviewStore)
    }
    val pushTokenRegistrar by lazy {
        com.volleyballlab.player.data.push.PushTokenRegistrar({ authRepository.currentUid }, firestore)
    }
    suspend fun observeNotifications() {
        authRepository.currentUser.map { it?.uid }.distinctUntilChanged().collectLatest { uid ->
            androidx.core.app.NotificationManagerCompat.from(context).cancelAll()
            if (uid != null) kotlinx.coroutines.coroutineScope {
                launch {
                    combine(profileRepository.profile.map { it.name }, profileRepository.syncStatus, ::Pair)
                        .distinctUntilChanged().collect { (name, status) ->
                        if (name.isNotBlank() && status == com.volleyballlab.player.domain.repository.ProfileSyncStatus.Saved
                            && authRepository.currentUid == uid) syncAccountName(uid)
                    }
                }
                launch { sessionRepository.sessions.collect { sessions -> notificationRepository.ingest(uid,
                    sessions.map(com.volleyballlab.player.domain.model.NotificationEvents::session) + com.volleyballlab.player.domain.model.NotificationEvents.milestones(sessions)) } }
                launch { notificationRepository.connectionEvents(uid).collect { events -> notificationRepository.ingest(uid, events) } }
                launch { coachRepository.notesForMe.collect { notes -> notificationRepository.ingest(uid, notes.map(com.volleyballlab.player.domain.model.NotificationEvents::feedback)) } }
                launch { coachRepository.assignmentsForMe.collect { assignments -> notificationRepository.ingest(uid, assignments.map(com.volleyballlab.player.domain.model.NotificationEvents::assignment)) } }
                launch { combine(coachRepository.coachProfile, coachRepository.submissions, ::Pair).collect { (profile, submissions) ->
                    notificationRepository.ingest(uid, submissions.map(com.volleyballlab.player.domain.model.NotificationEvents::submission), profile.notifySubmissions) } }
                launch { runCatching { kotlinx.coroutines.withTimeout(25_000) { notificationRepository.refresh() } } }
                launch { runCatching { pushTokenRegistrar.registerCurrentUser() } }
            }
        }
    }
    val notificationRepository by lazy {
        com.volleyballlab.player.data.notifications.TrainingNotificationRepository(context, authRepository, firestore)
    }
    val trainingWorkspaceRepository by lazy {
        com.volleyballlab.player.data.training.TrainingWorkspaceRepository(context, authRepository)
    }

    val drillRepository: DrillRepository by lazy { StaticDrillRepository() }
    val drillPracticeRepository: com.volleyballlab.player.domain.repository.DrillPracticeRepository by lazy {
        com.volleyballlab.player.data.drill.DataStoreDrillPracticeRepository(context, authRepository)
    }
    private fun syncAccountName(uid: String) {
        // Existing public copies follow the private identity; no role or address is published.
        val refs = listOf("users" to "displayName", "coaches" to "name", "coachDirectory" to "name")
            .map { (collection, key) -> firestore.collection(collection).document(uid) to key }
        firestore.runTransaction { transaction ->
            val name = transaction.get(firestore.collection("profiles").document(uid)).getString("name")
                ?.takeIf { it.isNotBlank() }?.take(60) ?: return@runTransaction
            val rows = refs.map { (ref, key) -> Triple(ref, key, transaction.get(ref)) }
            check(authRepository.currentUid == uid)
            rows.forEach { (ref, key, row) ->
                if (row.exists() && row.getString(key) != name) transaction.update(ref, key, name)
            }
        }
    }
    val drillAccessController by lazy {
        com.volleyballlab.player.data.drill.DrillAccessController(authRepository, planRepository, drillRepository)
    }

    /**
     * Persisted, as of the dashboard work. `InMemorySessionRepository` is still in
     * the tree and is what the unit tests use - it needs no Android context - but
     * the app no longer forgets the athlete's training when the process dies.
     */
    val sessionRepository: SessionRepository by lazy {
        DataStoreSessionRepository(context, authRepository, sessionCloudStore)
    }

    /**
     * The coach shell's read path into athletes' training.
     *
     * Cloud-backed, not a cast of [sessionRepository]: a coach reads sessions
     * another account submitted, and those rows live in Firestore once the
     * athlete's phone mirrors them there. The rules grant exactly the sessions
     * naming this coach in `sharedWith`.
     */
    val sessionDirectory: SessionDirectory by lazy {
        FirestoreSessionDirectory(firestore, authRepository)
    }

    /**
     * Takes the application context, not an activity one. DataStore outlives any
     * single screen, and holding an activity here would leak it for the life of
     * the process.
     */
    val profileRepository: ProfileRepository by lazy {
        com.volleyballlab.player.data.profile.SyncedProfileRepository(
            DataStoreProfileRepository(context, authRepository), authRepository,
            com.volleyballlab.player.data.profile.FirestoreProfileCloudStore(firestore),
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate),
        )
    }
    val addressCatalog: AddressCatalog by lazy { AssetAddressCatalog(context.assets) }

    val coachingPreferencesRepository: CoachingPreferencesRepository by lazy {
        DataStoreCoachingPreferencesRepository(context, authRepository)
    }

    val assessmentRepository: AssessmentRepository by lazy {
        DataStoreAssessmentRepository(context, authRepository)
    }

    /** The install-level welcome, before any account exists. */
    val welcomeRepository: com.volleyballlab.player.domain.repository.WelcomeRepository by lazy {
        com.volleyballlab.player.data.welcome.DataStoreWelcomeRepository(context)
    }

    /**
     * Which tier the account is on, and therefore whether coach connection is
     * unlocked. The confirmed owner UID has Pro; other accounts read trusted
     * Firestore entitlements. Legacy local demonstration flags are ignored.
     */
    val planRepository: PlanRepository by lazy {
        com.volleyballlab.player.data.plan.FirestorePlanRepository(firestore, authRepository)
    }

    /**
     * Which shell the account sees. Read on the cold-start path, before the
     * navigation graph exists, so it is deliberately backed by its own small file.
     *
     * The cloud row (`users/{uid}.role`, written when a coach registers on the
     * web portal) is the tiebreaker for accounts this phone has never seen: a
     * real coach account lands in the coach shell on a fresh install, no test
     * account and no Profile detour. An explicit in-app switch always wins.
     */
    val roleRepository: RoleRepository by lazy {
        DataStoreRoleRepository(context, authRepository, firestore.cloudRoleOf(authRepository))
    }

    /**
     * The team, its roster, its assignments and its feedback.
     *
     * Cloud-backed: the same collections the coach web portal reads and writes
     * (`docs/coach-cloud-contract.md`), so a request sent from the app appears
     * in the portal and an assignment written at a desk appears on the phone.
     * Firestore's offline cache keeps these screens working without signal, and
     * [DataStoreCoachRepository] remains in the tree for unit tests.
     *
     * Takes [profileRepository] because a join request copies the player's name and
     * position onto the roster row at the moment they ask - see `RosterMember` for
     * why that is a copy rather than a lookup.
     */
    val coachRepository: CoachRepository by lazy {
        FirestoreCoachRepository(firestore, authRepository, profileRepository, prepareRecording = { id, uid ->
            val session = sessionRepository.sessions.first().firstOrNull { it.id == id }
                ?: error("This session is no longer on this phone.")
            sharedRecordingStore.upload(session, uid)
        })
    }

    /** Best-effort mirror of the athlete's own sessions for coach review. */
    private val sessionCloudStore by lazy {
        FirestoreSessionCloudStore(firestore, authRepository)
    }

    /** The shared project the app and the web portal both talk to. */
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    /**
     * Firebase initialises itself from `app/google-services.json` through its own
     * ContentProvider before any of this runs, so there is nothing to start here.
     */
    val authRepository: AuthRepository by lazy {
        FirebaseAuthRepository(FirebaseAuth.getInstance(), context)
    }

    /**
     * The web client id is a generated resource, not a pasted constant, so it
     * cannot drift from `app/google-services.json`.
     */
    val googleCredentialClient: GoogleCredentialClient by lazy {
        GoogleCredentialClient(context.getString(R.string.default_web_client_id))
    }

    val sessionScorer: SessionScorer by lazy { SessionScorer() }

    /** A fresh native LiteRT model for each capture screen; the screen owns and closes it. */
    fun createReceivingReadyPostureEvaluator(): ReceivingReadyPostureEvaluator =
        ReceivingReadyPostureEvaluator(LiteRtReceivingReadyModel.create(context))

    /** Gemini writes grounded copy; measured scores and cue choice stay on-device. */
    val aiCoachRepository: AiCoachRepository by lazy { FirebaseAiCoachRepository(context) }

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
