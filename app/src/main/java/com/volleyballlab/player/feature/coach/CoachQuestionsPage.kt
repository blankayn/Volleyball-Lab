package com.volleyballlab.player.feature.coach

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** A quiet, full-page inbox: fixed back/title bar above one column of question cards. */
@Composable
internal fun CoachQuestionsPage(
    onBack: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    title: String = "Player questions",
    unread: Int = 0,
    content: LazyListScope.() -> Unit,
) {
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest)
        .padding(contentPadding)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", Modifier.size(26.dp))
            }
            Text(title, Modifier.weight(1f).padding(horizontal = 8.dp),
                style = if (title == "Inbox") MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = if (title == "Inbox") TextAlign.Start else TextAlign.Center)
            if (unread > 0) CoachingUnreadBadge(unread, summary = true) else Spacer(Modifier.width(48.dp))
        }
        LazyColumn(modifier = Modifier.weight(1f).widthIn(max = 640.dp).fillMaxWidth()
            .align(Alignment.CenterHorizontally),
            contentPadding = PaddingValues(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(if (title == "Inbox") 0.dp else 24.dp), content = content)
    }
}
