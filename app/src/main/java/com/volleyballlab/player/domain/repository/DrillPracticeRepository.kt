package com.volleyballlab.player.domain.repository

import com.volleyballlab.player.domain.model.DrillPracticeDraft
import com.volleyballlab.player.domain.model.DrillPracticeRecord
import kotlinx.coroutines.flow.Flow

interface DrillPracticeRepository {
    val history: Flow<List<DrillPracticeRecord>>
    val draft: Flow<DrillPracticeDraft?>
    suspend fun draftFor(uid: String): DrillPracticeDraft?
    suspend fun checkpoint(uid: String, draft: DrillPracticeDraft)
    suspend fun finish(uid: String, record: DrillPracticeRecord)
    suspend fun discard(uid: String, workoutId: String)
}
