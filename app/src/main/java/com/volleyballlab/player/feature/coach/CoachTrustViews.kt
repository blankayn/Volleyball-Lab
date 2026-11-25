package com.volleyballlab.player.feature.coach

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.volleyballlab.player.R
import com.volleyballlab.player.VolleyballLabApplication
import com.volleyballlab.player.domain.model.CoachPreview
import com.volleyballlab.player.domain.model.CoachTrust
import com.volleyballlab.player.domain.model.CoachTrustEvidence

/** Null while it is being read. */
@Composable
internal fun rememberCoachTrust(coach: CoachPreview): CoachTrust? {
    val context = LocalContext.current
    val store = remember(context) { (context.applicationContext as VolleyballLabApplication).container.coachTrustStore }
    val flow = remember(store, coach.coachUid) { store.evidence(coach.coachUid) }
    val evidence by flow.collectAsStateWithLifecycle(initialValue = null as CoachTrustEvidence?)
    return evidence?.let { known -> remember(coach.profile, known) { CoachTrust.of(coach.profile, known, System.currentTimeMillis()) } }
}

/** Verified or not, and nothing more. Shown only once it is known. */
@Composable
internal fun CoachVerifiedBadge(trust: CoachTrust?, onDark: Boolean = false) {
    val verified = trust?.verified ?: return
    val tint = when {
        verified -> if (onDark) Color(0xFF8FD6A8) else FeedbackDoneColor
        else -> if (onDark) Color(0xFFDEDCD2) else MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        if (verified) Icon(Icons.Rounded.Verified, null, Modifier.size(17.dp), tint = tint)
        Text(stringResource(if (verified) R.string.coach_verified else R.string.coach_not_verified),
            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, color = tint)
    }
}

/** Asked before a request to a coach who is not verified. */
@Composable
internal fun UnconfirmedCoachDialog(onSend: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.trust_confirm_title)) },
        text = { Text(stringResource(R.string.trust_confirm_body)) },
        confirmButton = { Button(onClick = onSend) { Text(stringResource(R.string.trust_confirm_send)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.trust_confirm_cancel)) } },
    )
}
