package com.volleyballlab.player.pose

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.google.android.gms.tasks.Tasks
import com.volleyballlab.player.data.cloud.CloudSchema
import org.json.JSONArray
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only identity lookup for the user-authorized session audit; never exports credentials. */
@RunWith(AndroidJUnit4::class)
class StudentSessionIdentityDeviceTest {
    @Test fun recordCurrentAccountScopeWithoutCredentials() {
        val user = FirebaseAuth.getInstance().currentUser
        val record = JSONObject().put("signed_in", user != null)
            .put("uid", user?.uid ?: "")
            .put("username_hint", user?.email?.substringBefore('@') ?: "")
            .put("display_name", user?.displayName ?: "")
        InstrumentationRegistry.getInstrumentation().targetContext.filesDir
            .resolve("student-session-identity-audit.json").writeText(record.toString(2))
    }

    @Test fun inspectOnlySignedInStudentsCloudSessionsAndExistingCoachNotes() {
        val uid = requireNotNull(FirebaseAuth.getInstance().currentUser?.uid)
        val db = FirebaseFirestore.getInstance()
        val sessions = Tasks.await(db.collection(CloudSchema.SESSIONS).whereEqualTo("ownerUid", uid).get(Source.SERVER), 25, TimeUnit.SECONDS)
        val notes = Tasks.await(db.collection(CloudSchema.NOTES).whereEqualTo("playerUid", uid).get(Source.SERVER), 25, TimeUnit.SECONDS)
        val memberships = Tasks.await(db.collectionGroup(CloudSchema.MEMBERS).whereEqualTo("playerUid", uid).get(Source.SERVER), 25, TimeUnit.SECONDS)
        val sessionRows = JSONArray()
        for (doc in sessions) sessionRows.put(JSONObject().put("id", doc.id).put("skill", doc.getString("skill"))
            .put("drillId", doc.getString("drillId")).put("durationMs", doc.getLong("durationMs"))
            .put("recording", Tasks.await(db.collection("recordings").document(doc.id).get(Source.SERVER), 25, TimeUnit.SECONDS).data?.let { JSONObject(it) }))
        val noteRows = JSONArray()
        for (doc in notes) noteRows.put(JSONObject().put("id", doc.id).put("coachUid", doc.getString("coachUid"))
            .put("sessionId", doc.getString("sessionId")).put("text", doc.getString("text")))
        val links = JSONArray()
        for (doc in memberships) {
            val coachUid = doc.reference.parent.parent?.id ?: continue
            val profile = Tasks.await(db.collection(CloudSchema.DIRECTORY).document(coachUid).get(Source.SERVER), 25, TimeUnit.SECONDS)
            links.put(JSONObject().put("coachUid", coachUid).put("name", profile.getString("name"))
                .put("teamName", profile.getString("teamName")).put("status", doc.getString("status")))
        }
        check(FirebaseAuth.getInstance().currentUser?.uid == uid) { "Account changed during read-only audit" }
        val record = JSONObject().put("ownerUid", uid).put("sessions", sessionRows).put("notes", noteRows).put("coachLinks", links)
        InstrumentationRegistry.getInstrumentation().targetContext.filesDir
            .resolve("student-cloud-session-audit.json").writeText(record.toString(2))
    }
}
