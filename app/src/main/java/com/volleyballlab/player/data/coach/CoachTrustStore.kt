package com.volleyballlab.player.data.coach

import com.google.firebase.firestore.FirebaseFirestore
import com.volleyballlab.player.data.cloud.awaitCloud
import com.volleyballlab.player.domain.model.CoachTrust
import com.volleyballlab.player.domain.model.CoachTrustEvidence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Reads the published facts behind [CoachTrust]. Coaches write them on the website; the rules check each one. */
class CoachTrustStore(private val db: FirebaseFirestore) {

    /** Emits once. Anything that cannot be read counts as not confirmed. */
    fun evidence(coachUid: String): Flow<CoachTrustEvidence> = flow {
        emit(try { load(coachUid) } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            CoachTrustEvidence()
        })
    }

    private suspend fun load(coachUid: String): CoachTrustEvidence {
        val (confirmedAt, domain) = record(coachUid)
        val now = System.currentTimeMillis()
        val vouchers = db.collection(VOUCHES).whereEqualTo("coachUid", coachUid).limit(MAX_VOUCHES).get().awaitCloud().documents
            .mapNotNull { it.getString("voucherUid") }.distinct().filter { it != coachUid }
        // A vouch only counts while the coach who gave it still has a current institution email.
        val counted = vouchers.count { uid ->
            val (at, voucherDomain) = record(uid)
            CoachTrust.emailIsCurrent(CoachTrustEvidence(at), now) && CoachTrust.isInstitution(voucherDomain)
        }
        val reviewers = db.collection("coachReviews").whereEqualTo("coachUid", coachUid).limit(100).get().awaitCloud().documents
            .mapNotNull { it.getString("playerUid") }.distinct().size
        return CoachTrustEvidence(confirmedAt, domain, counted, reviewers, verifiedByTeam(coachUid))
    }

    private suspend fun verifiedByTeam(coachUid: String): Boolean = try {
        db.collection(VERIFIED).document(coachUid).get().awaitCloud().getBoolean("verified") == true
    } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        false
    }

    private suspend fun record(uid: String): Pair<Long?, String> {
        val document = db.collection(TRUST).document(uid).get().awaitCloud()
        return document.getTimestamp("confirmedAt")?.toDate()?.time to document.getString("institutionDomain").orEmpty()
    }

    private companion object {
        const val TRUST = "coachTrust"
        const val VOUCHES = "coachVouches"
        const val VERIFIED = "coachVerified"
        const val MAX_VOUCHES = 20L
    }
}
