package com.volleyballlab.player.data.auth

import com.volleyballlab.player.domain.model.DemoAccount
import com.volleyballlab.player.domain.model.UserRole
import com.volleyballlab.player.domain.repository.AuthError
import com.volleyballlab.player.domain.repository.AuthOutcome
import com.volleyballlab.player.domain.repository.AuthRepository
import com.volleyballlab.player.domain.repository.CoachRepository
import com.volleyballlab.player.domain.repository.ProfileRepository
import com.volleyballlab.player.domain.repository.RoleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first

/**
 * Puts one of the standing test accounts on screen in a single tap.
 *
 * ## Why this is one class rather than a method on a ViewModel
 *
 * Three screens need it and they are in three different shells: the login form,
 * the athlete's Profile tab, and the coach's Account tab. The last two are the
 * ones that matter - switching accounts *from inside the app* is what makes the
 * coach relationship demonstrable, because showing it means being the player who
 * asks and then the coach who answers, four times over. Duplicating the sequence
 * below into three ViewModels would mean three places to get the ordering wrong.
 *
 * ## What it does, and why in this order
 *
 * 1. **Sign in, and only create the account if that fails.** The ordinary case is
 *    one network call. Firebase deliberately answers "no such user" and "wrong
 *    password" with the same error - see [AuthError.WrongCredentials] - so a failed
 *    sign-in is followed by an attempted sign-up. If *that* comes back
 *    [AuthError.EmailAlreadyUsed] then the account exists with a different password
 *    and somebody has changed it by hand; that is reported rather than retried.
 * 2. **Prepare the account to be seen from the other side.** A name seeded into
 *    whichever profile is empty, and for a coach a directory listing published. The
 *    whole point of two fixtures is that each shows up on the other's screen, and
 *    an unprepared one shows up as a blank roster row or a coach who is not in the
 *    list at all. Both seeds are guarded, so a name typed on either Profile screen
 *    is never overwritten.
 * 3. **Write the role, last.** Both directions, unlike the address rule - see
 *    [com.volleyballlab.player.domain.model.DemoAccounts] for the argument. Last
 *    because this is the write that swaps the shell, and anything left to do after
 *    it would be racing the teardown of the screen that asked.
 *
 * ## Why this runs in its own scope
 *
 * Signing in *is* the teardown. The role flow is a function of the signed-in uid,
 * so the moment step 1 lands, the destination account's role starts being reported
 * and `VolleyballLabApp` may rebuild the whole graph - which clears the ViewModel of
 * the screen that called this, and with it that screen's `viewModelScope`. Running
 * on the caller's scope means steps 2 and 3 are cancelled halfway on precisely the
 * path they exist to serve, and the symptom is a coach who never appears in the
 * directory. So the work is launched here, on a scope that lives as long as the
 * process, and the caller merely awaits it. A caller that goes away stops hearing
 * the answer; it does not stop the work.
 *
 * ## There is no sign-out first, deliberately
 *
 * Firebase replaces the current session on a successful sign-in and leaves it
 * untouched on a failed one. Signing out first would mean that a switch attempted
 * with no network drops the user to the login screen instead of leaving them where
 * they were with an error to read.
 */
class DemoSignIn(
    private val authRepository: AuthRepository,
    private val roleRepository: RoleRepository,
    private val profileRepository: ProfileRepository,
    private val coachRepository: CoachRepository,
) {

    /**
     * Process-lifetime, and deliberately not the caller's.
     *
     * A [SupervisorJob] so one failed switch cannot poison the next, which matters
     * because the only thing that would notice is the button going permanently
     * inert. See the class KDoc for why this cannot be the calling ViewModel's
     * scope.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Signs into [account], creating it on first use, and prepares it to be seen.
     *
     * Returns before the shell has swapped. The caller does not navigate: the role
     * flow emits and `VolleyballLabApp` rebuilds the graph, which is the same path
     * the switches in Profile and Account already take.
     *
     * Suspends until the work is done, but does not own it - see the class KDoc.
     */
    suspend fun enter(account: DemoAccount): AuthOutcome =
        scope.async { signInAndPrepare(account) }.await()

    private suspend fun signInAndPrepare(account: DemoAccount): AuthOutcome {
        val signedIn = authRepository.signIn(
            email = account.email,
            password = account.password,
            rememberSession = true,
        )

        val outcome = if (signedIn is AuthOutcome.Failure &&
            signedIn.error == AuthError.WrongCredentials
        ) {
            authRepository.signUp(account.name, account.email, account.password)
        } else {
            signedIn
        }

        if (outcome !is AuthOutcome.Success) return outcome

        when (account.role) {
            UserRole.Coach -> prepareCoach(account)
            UserRole.Player -> prepareAthlete(account)
        }
        roleRepository.setRole(account.role)
        return outcome
    }

    /**
     * Makes this coach findable, and gives them a name to be found by.
     *
     * [CoachRepository.publishListing] writes an empty profile only when there is
     * none, which is the right contract for the general case and not quite enough
     * here: an empty listing renders as a blank card, and a player picking from a
     * list of blank cards is not a demonstration of anything. So the row is created
     * first and then named, but only if the coach has not named themselves.
     *
     * `CoachAccountViewModel.seedNameFromAccount` makes the same guarded copy when
     * the Account tab opens. That is not redundant with this: the point of doing it
     * here is that the coach becomes findable *without* ever visiting their own
     * Account tab, which is exactly the path a two-tap demo takes. Both are guarded
     * on a blank name, so they cannot disagree.
     */
    private suspend fun prepareCoach(account: DemoAccount) {
        coachRepository.publishListing()
        val profile = coachRepository.coachProfile.first()
        if (!profile.hasName) {
            coachRepository.saveCoachProfile(profile.copy(name = account.name))
        }
    }

    /** The same guarded seeding, for the name that lands on the coach's roster. */
    private suspend fun prepareAthlete(account: DemoAccount) {
        val profile = profileRepository.profile.first()
        if (!profile.hasName) {
            profileRepository.save(profile.copy(name = account.name))
        }
    }
}
