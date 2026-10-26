package com.volleyballlab.player.data.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.google.firebase.firestore.FirebaseFirestore
import com.volleyballlab.player.MainActivity
import com.volleyballlab.player.R
import com.volleyballlab.player.data.UserScope
import com.volleyballlab.player.data.cloud.CloudSchema
import com.volleyballlab.player.data.cloud.awaitCloud
import com.volleyballlab.player.data.cloud.snapshots
import kotlinx.coroutines.flow.map
import com.volleyballlab.player.domain.model.*
import com.volleyballlab.player.domain.repository.AuthRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.LocalDate

private val Context.notificationStore by preferencesDataStore("training_notifications")

class TrainingNotificationRepository(private val context: Context, private val auth: AuthRepository, private val db: FirebaseFirestore) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val data = context.notificationStore.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }
    val inbox = combine(auth.currentUser, data) { user, p -> UserScope.of(user?.uid)?.let { decode(p[it.string("inbox")]) } ?: emptyList() }
    val deliveryEnabled = combine(auth.currentUser, data) { user, p -> UserScope.of(user?.uid)?.let { p[it.string("delivery")] == "true" } ?: false }
    fun connectionEvents(uid: String) = membershipQuery(uid).snapshots().map { snapshot ->
        snapshot.documents.mapNotNull { doc ->
            val coachUid = doc.reference.parent.parent?.id ?: return@mapNotNull null
            doc.data?.let { NotificationEvents.connection(coachUid, CloudSchema.memberOf(it)) }
        }
    }
    private fun membershipQuery(uid: String) = db.collectionGroup(CloudSchema.MEMBERS).whereEqualTo("playerUid", uid)
    suspend fun setDeliveryEnabled(enabled: Boolean) {
        val scope = UserScope.of(auth.currentUid) ?: return
        context.notificationStore.edit {
            it[scope.string("delivery")] = enabled.toString()
            if (enabled) it[scope.string("inbox")] = json.encodeToString(decode(it[scope.string("inbox")]).map { n -> n.copy(delivered = true) })
        }
        if (!enabled) NotificationManagerCompat.from(context).cancelAll()
    }
    suspend fun markRead(id: String? = null) {
        val scope = UserScope.of(auth.currentUid) ?: return
        context.notificationStore.edit { p -> val key = scope.string("inbox"); p[key] = json.encodeToString(decode(p[key]).map { if (id == null || it.id == id) it.copy(isRead = true) else it }) }
        if (id == null) NotificationManagerCompat.from(context).cancelAll() else NotificationManagerCompat.from(context).cancel(id.hashCode())
    }
    suspend fun ingest(uid: String, incoming: List<TrainingNotification>, allowDelivery: Boolean = true) {
        if (auth.currentUid != uid) return
        val scope = UserScope.of(uid) ?: return
        val deliver = mutableListOf<TrainingNotification>()
        context.notificationStore.edit { p ->
            if (auth.currentUid != uid) return@edit
            val key = scope.string("inbox")
            val initialized = p[scope.string("initialized")] == "true"
            val enabled = p[scope.string("delivery")] == "true" && allowDelivery
            val previous = decode(p[key])
            val merged = NotificationEvents.merge(previous, incoming).map { n ->
                if (!n.delivered && enabled && initialized && !n.isRead && System.currentTimeMillis() - n.createdAtEpochMs in 0..86_400_000L) {
                    deliver += n
                    n.copy(delivered = true)
                } else if (!initialized) n.copy(delivered = true) else n
            }
            p[key] = json.encodeToString(merged)
            p[scope.string("initialized")] = "true"
        }
        if (auth.currentUid == uid && deliveryEnabled.first()) deliver.take(3).forEach(::post)
    }
    /** Fetches cloud events even when screens are closed; WorkManager controls background cadence. */
    suspend fun refresh() {
        val uid = auth.currentUid ?: return
        val events = mutableListOf<TrainingNotification>()
        membershipQuery(uid).get().awaitCloud().documents.forEach { doc ->
            val coachUid = doc.reference.parent.parent?.id ?: return@forEach
            doc.data?.let { NotificationEvents.connection(coachUid, CloudSchema.memberOf(it)) }?.let(events::add)
        }
        suspend fun records(collection: String, field: String) = db.collection(collection).whereEqualTo(field, uid).get().awaitCloud().documents.mapNotNull { it.data }
        records(CloudSchema.NOTES, "playerUid").map(CloudSchema::noteOf).forEach { events += NotificationEvents.feedback(it) }
        records(CloudSchema.ASSIGNMENTS, "playerUid").mapNotNull(CloudSchema::assignmentOf).forEach { events += NotificationEvents.assignment(it) }
        val sessions = records(CloudSchema.SESSIONS, "ownerUid").mapNotNull(CloudSchema::sessionOf)
        events += sessions.map(NotificationEvents::session) + NotificationEvents.milestones(sessions)
        val coach = db.collection(CloudSchema.COACHES).document(uid).get().awaitCloud()
        if (coach.exists()) {
            val profile = CloudSchema.profileOf(coach.data.orEmpty())
            val submissions = records(CloudSchema.SUBMISSIONS, "coachUid").map(CloudSchema::submissionOf)
            val coachEvents = mutableListOf<TrainingNotification>()
            if (profile.notifySubmissions) coachEvents += submissions.map(NotificationEvents::submission)
            if (profile.notifyWeeklyDigest) {
                val week = ReportAllowance.week(LocalDate.now())
                coachEvents += TrainingNotification("digest:$week", TrainingNotificationType.WeeklyDigest, "Weekly review overview",
                    "${submissions.count { it.isPending }} sessions awaiting review.", week.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
            }
            events += coachEvents
        }
        ingest(uid, events)
    }
    private fun post(item: TrainingNotification) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Training updates", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(context, MainActivity::class.java).putExtra("open_notifications", true)
        val pending = PendingIntent.getActivity(context, item.id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_splash_brand)
            .setContentTitle(item.title).setContentText(item.message).setStyle(NotificationCompat.BigTextStyle().bigText(item.message))
            .setContentIntent(pending).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
        runCatching { NotificationManagerCompat.from(context).notify(item.id.hashCode(), notification) }
    }
    private fun decode(raw: String?): List<TrainingNotification> = runCatching { json.decodeFromString<List<TrainingNotification>>(raw ?: "[]") }.getOrDefault(emptyList())
    private companion object { const val CHANNEL = "training_updates" }
}

internal suspend fun clearNotificationsAccount(context: Context, uid: String) =
    com.volleyballlab.player.data.account.clearAccountPreferences(context.notificationStore, uid)
