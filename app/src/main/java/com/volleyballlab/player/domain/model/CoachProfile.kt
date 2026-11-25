package com.volleyballlab.player.domain.model

import androidx.annotation.StringRes
import com.volleyballlab.player.R

/**
 * What the coach does on the team. It replaced the specialisation field on
 * 25 November 2026: every coach here coaches all five skills, so a specialisation
 * said nothing. Names are stored and shared with the website.
 */
enum class CoachingRole(@param:StringRes val displayName: Int) {
    HeadCoach(R.string.coaching_role_head),
    AssistantCoach(R.string.coaching_role_assistant),
    Trainer(R.string.coaching_role_trainer),
    PETeacher(R.string.coaching_role_pe_teacher);

    companion object {
        val All: List<CoachingRole> = entries
        fun of(name: String?): CoachingRole? = entries.firstOrNull { it.name == name }
    }
}

/**
 * Who the coach is, and what team they run.
 *
 * Combines `Coach_Experience` from Table 14 with the fields
 * the storyboard's team-setup step asks for - team name, organisation or school,
 * and the level being coached. The registration screen in Figure 24 additionally
 * collects a certification licence number; [licenceNumber] is that field.
 *
 * ## Everything here is optional
 *
 * Same principle as [AthleteProfile]: none of this gates the coach shell. A coach
 * who signs up mid-training-session should be able to read the review queue before
 * filling in a form about themselves.
 *
 * It is not, however, invisible. Everything on this record except [licenceNumber]
 * is shown to players browsing for a coach, so a coach who fills in nothing
 * appears in that list as a blank card - which is a fair representation of an
 * account that has told nobody anything about itself.
 */
data class CoachProfile(
    val name: String = "",
    val teamName: String = "",
    val organisation: String = "",
    val coachingRole: CoachingRole? = null,
    val yearsExperience: Int? = null,
    val licenceNumber: String = "",
    /** Push alerts for player submissions. `Coach_Settings` in Figure 29. */
    val notifySubmissions: Boolean = true,
    /** Weekly analytic overviews, also Figure 29. */
    val notifyWeeklyDigest: Boolean = true,
    /** Biomechanical risk warnings - the safety alerts in the storyboard. */
    val notifySafetyAlerts: Boolean = true,
) {
    val hasName: Boolean get() = name.isNotBlank()

    /** What to call the team on screen, falling back to something non-empty. */
    fun teamLabel(fallback: String): String = teamName.ifBlank { fallback }

    /** Up to two initials, first and last word. Mirrors [AthleteProfile.initials]. */
    fun initials(): String? {
        val words = name.trim().split(WHITESPACE).filter { it.isNotBlank() }
        if (words.isEmpty()) return null
        val first = words.first().first().uppercaseChar()
        val last = words.last().takeIf { words.size > 1 }?.first()?.uppercaseChar()
        return if (last == null) first.toString() else "$first$last"
    }

    companion object {
        val Empty = CoachProfile()

        const val MAX_NAME_LENGTH = AthleteProfile.MAX_NAME_LENGTH
        const val MAX_TEAM_LENGTH = 48
        const val MAX_EXPERIENCE_YEARS = 70

        private val WHITESPACE = Regex("\\s+")
    }
}

/**
 * A coach as the player sees them while choosing. Figures 17 and 21.
 *
 * ## The player picks from a list; the coach answers
 *
 * This is the direction the capstone document specifies, and it is worth stating
 * plainly because earlier builds tried both alternatives. Figure 6.2 is explicit:
 * *players* send a connection request and *coaches* accept or decline it. There is
 * no invite code any more - a code made the player prove they already knew which
 * coach they wanted, which is exactly backwards for a feature sold as "find a
 * coach". They browse, they read a profile, then they ask.
 *
 * ## Only what helps someone choose
 *
 * Name, team, role, experience and how many athletes are already on the
 * roster. Not a contact address, not a licence number, and nothing at all about
 * the coach.s other players - a directory that leaked squad names would be handing
 * out other athletes. details to anyone who opened the tab.
 *
 * @param rosterSize how many players the coach currently has. The one number here
 *   that is not self-reported, which is why it is worth showing: everything else
 *   on this card is what the coach typed about themselves.
 */
data class CoachPreview(
    val coachUid: String,
    val profile: CoachProfile,
    val rosterSize: Int,
) {
    /** What to call this coach on the card, however little they filled in. */
    fun displayName(fallback: String): String =
        profile.name.ifBlank { profile.teamName.ifBlank { fallback } }

    fun initials(): String? = profile.initials()
}
