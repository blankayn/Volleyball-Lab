package com.volleyballlab.player.domain.repository

import com.volleyballlab.player.domain.model.Drill
import com.volleyballlab.player.domain.model.Skill

/**
 * Source of the drill catalogue.
 *
 * An interface rather than a concrete class because the eventual source is not
 * decided. Conflict 2 in the capstone document is still open: the network model
 * specifies Cloud Firestore, while the entire data dictionary is written as MySQL
 * tables with `INT(11)` keys. Until the team settles that, committing to either a
 * Room schema or a Firestore collection shape would bake in a decision nobody has
 * actually made, and would have to be unpicked later.
 *
 * Everything above this line already works against the interface, so resolving
 * the conflict means adding one implementation, not editing the training feature.
 */
interface DrillRepository {

    /** Drills for one skill, ordered easiest first. */
    suspend fun drillsFor(skill: Skill): List<Drill>

    suspend fun byId(id: String): Drill?
}
