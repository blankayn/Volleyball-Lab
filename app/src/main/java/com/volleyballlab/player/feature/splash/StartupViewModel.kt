package com.volleyballlab.player.feature.splash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.volleyballlab.player.appContainer
import com.volleyballlab.player.domain.model.UserRole
import com.volleyballlab.player.domain.repository.AssessmentRepository
import com.volleyballlab.player.domain.repository.AuthRepository
import com.volleyballlab.player.domain.repository.RoleRepository
import com.volleyballlab.player.domain.repository.WelcomeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Where the app should open, once startup has worked out which it is. */
enum class StartDestination {
    /** First launch on this install: explain the product before asking to sign in. */
    Welcome,

    /** No session, or one the athlete asked not to be remembered. */
    Login,

    /** Signed in, but has never been through onboarding. */
    Assessment,

    /** Signed in and onboarded. The ordinary case after the first launch. */
    Home,

    /**
     * Signed in as a coach.
     *
     * Skips onboarding entirely, and that is deliberate: the assessment asks how
     * confident the *athlete* feels at five skills in order to pick what to
     * suggest they train, and a coach is not going to train any of them here.
     */
    CoachHome,
}

/**
 * Decides when the splash may leave, and where it should leave to.
 *
 * ## The one rule that must survive every future change
 *
 * [isReady] becomes true on **every** path, including failure. Section 5 of
 * `docs/rive/splash-contract.md` is emphatic about this and it is worth restating:
 * if readiness is only set on the success path, then a failed auth call, an expired
 * token or an offline launch leaves the user staring at a looping logo with no way
 * out. The correct behaviour when startup fails is to proceed and let the
 * destination screen report the problem, not to hold the splash hostage.
 *
 * That is why [resolve] is wrapped in `runCatching` and why the fallback is
 * [StartDestination.Login] rather than nothing: a startup that cannot determine
 * whether there is a session should ask for one, which is recoverable, rather than
 * open a signed-out app, which is not.
 *
 * ## Offline
 *
 * Firebase restores a cached credential from disk during its own initialisation, so
 * the session check below needs no network. An athlete opening the app in a gym
 * basement goes straight to their dashboard.
 */
class StartupViewModel(
    private val authRepository: AuthRepository,
    private val assessmentRepository: AssessmentRepository,
    private val roleRepository: RoleRepository,
    private val welcomeRepository: WelcomeRepository,
) : ViewModel() {

    private val _startDestination = MutableStateFlow<StartDestination?>(null)
    val startDestination: StateFlow<StartDestination?> = _startDestination.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    /**
     * Kept live rather than sampled once, because it is also what decides where the
     * login screen hands off to - and by then the athlete may have completed
     * onboarding in this same session.
     */
    val hasOnboarded: StateFlow<Boolean> = assessmentRepository.hasOnboarded
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Which shell to build, kept live rather than sampled once.
     *
     * Live because the role can change while the app is running - the switch in
     * the athlete's Profile tab and the one in the coach's Account tab both write
     * it - and the whole navigation graph is rebuilt when it does. Sampling it at
     * startup would leave a coach who just switched looking at the player app
     * until they killed the process.
     *
     * Defaults to [UserRole.Player] before the first read lands, which is the same
     * safe direction [RoleRepository] documents.
     */
    val role: StateFlow<UserRole> = roleRepository.role
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserRole.Player)

    /**
     * Whether there is a session *right now*.
     *
     * [startDestination] is resolved once, during startup, and that is correct for
     * what it is for - it decides where the app opens and it must not change under
     * a live `NavHost`, because the start destination is part of the graph and
     * changing it resets the back stack.
     *
     * It is also, by the time the player shell is rebuilt, capable of being stale in
     * exactly one way that matters: the shell is rebuilt whenever [role] changes,
     * and an account that signed in *during* this run was not signed in when
     * startup ran. Without this check, switching from the coach shell back to an
     * athlete account lands on the login form while signed in.
     *
     * Deliberately a synchronous read of the provider rather than a flow. The role
     * change and the session change come from the same event, so anything that has
     * to catch up could still be behind at the moment the shell is built; this
     * cannot be.
     */
    val hasSession: Boolean
        get() = authRepository.currentUid != null

    init {
        viewModelScope.launch {
            _startDestination.value = runCatching { resolve() }.getOrDefault(StartDestination.Login)
            _isReady.value = true
        }
    }

    private suspend fun resolve(): StartDestination {
        if (authRepository.restoreSession() == null) {
            return if (welcomeRepository.hasSeenWelcome.first()) {
                StartDestination.Login
            } else {
                StartDestination.Welcome
            }
        }
        // Read after the session is restored, not before: the role is scoped per
        // uid, and asking for it while `currentUser` is still null would get the
        // default for nobody rather than the answer for this account.
        if (roleRepository.current() == UserRole.Coach) return StartDestination.CoachHome
        return if (assessmentRepository.hasOnboarded.first()) {
            StartDestination.Home
        } else {
            StartDestination.Assessment
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                StartupViewModel(
                    authRepository = appContainer.authRepository,
                    assessmentRepository = appContainer.assessmentRepository,
                    roleRepository = appContainer.roleRepository,
                    welcomeRepository = appContainer.welcomeRepository,
                )
            }
        }
    }
}
