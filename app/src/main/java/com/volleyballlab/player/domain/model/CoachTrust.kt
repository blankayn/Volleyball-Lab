package com.volleyballlab.player.domain.model

/**
 * What can be checked about a coach without anyone deciding by hand.
 *
 * @param emailConfirmedAtEpochMs when the coach last signed in with a confirmed email, or null.
 * @param institutionDomain the school or government domain of that email, or empty.
 * @param vouches other coaches, each with a confirmed institution email, who said they know this one.
 * @param reviewers different players who reviewed a session this coach gave feedback on.
 */
data class CoachTrustEvidence(
    val emailConfirmedAtEpochMs: Long? = null,
    val institutionDomain: String = "",
    val vouches: Int = 0,
    val reviewers: Int = 0,
    /** Set by the project owner in the Firebase console; no app or website can write it. */
    val verifiedByTeam: Boolean = false,
)

enum class TrustCheckId { Email, Institution, Details, Vouches, Players }

data class TrustCheck(val id: TrustCheckId, val passed: Boolean)

enum class CoachTrustStatus { TeamVerified, InstitutionConfirmed, ChecksPassed, NotConfirmed }

/**
 * The same five checks for every coach, and what they add up to.
 *
 * ## What this does and does not say
 *
 * Each check is a fact the system can see. Name, gender, age, school and team are
 * never compared with a list. It cannot check an identity document, and a school email
 * shows someone belongs to that school, not that they coach there. The project owner
 * may also verify a coach they know ([CoachTrustEvidence.verifiedByTeam]). Players see
 * only [verified]; the coach sees the checks on the website.
 *
 * A coach without a school email is not shut out: other coaches or their own players
 * can stand in for it ([CoachTrustStatus.ChecksPassed]).
 */
data class CoachTrust(val checks: List<TrustCheck>, val institutionDomain: String, val verifiedByTeam: Boolean = false) {
    val verified: Boolean get() = status != CoachTrustStatus.NotConfirmed
    private fun has(id: TrustCheckId) = checks.any { it.id == id && it.passed }
    val passed: Int get() = checks.count { it.passed }
    val status: CoachTrustStatus get() = when {
        verifiedByTeam -> CoachTrustStatus.TeamVerified
        !has(TrustCheckId.Email) || !has(TrustCheckId.Details) -> CoachTrustStatus.NotConfirmed
        has(TrustCheckId.Institution) -> CoachTrustStatus.InstitutionConfirmed
        has(TrustCheckId.Vouches) || has(TrustCheckId.Players) -> CoachTrustStatus.ChecksPassed
        else -> CoachTrustStatus.NotConfirmed
    }

    companion object {
        const val MIN_VOUCHES = 2
        const val MIN_REVIEWERS = 3

        /** The record goes stale after a year without the coach signing in, so an abandoned account stops counting. */
        const val EMAIL_VALID_MS = 365L * 24 * 60 * 60 * 1000

        /** Restricted endings: a person cannot simply buy one. Matches firestore.rules and the coach website. */
        private val institution = Regex("([a-z0-9-]+[.])+((edu|gov)([.][a-z]{2})?|(ac|sch)[.][a-z]{2})")
        fun isInstitution(domain: String): Boolean = institution.matches(domain.lowercase())

        fun emailIsCurrent(evidence: CoachTrustEvidence, nowEpochMs: Long): Boolean =
            evidence.emailConfirmedAtEpochMs?.let { nowEpochMs - it in -86_400_000L..EMAIL_VALID_MS } == true

        fun of(profile: CoachProfile, evidence: CoachTrustEvidence, nowEpochMs: Long): CoachTrust {
            val email = emailIsCurrent(evidence, nowEpochMs)
            val domain = evidence.institutionDomain.takeIf { email && isInstitution(it) }.orEmpty()
            return CoachTrust(listOf(
                TrustCheck(TrustCheckId.Email, email),
                TrustCheck(TrustCheckId.Institution, domain.isNotEmpty()),
                TrustCheck(TrustCheckId.Details, profile.name.trim().split(Regex("\\s+")).count { it.isNotBlank() } >= 2 && profile.organisation.isNotBlank()),
                TrustCheck(TrustCheckId.Vouches, evidence.vouches >= MIN_VOUCHES),
                TrustCheck(TrustCheckId.Players, evidence.reviewers >= MIN_REVIEWERS),
            ), domain, evidence.verifiedByTeam)
        }
    }
}
