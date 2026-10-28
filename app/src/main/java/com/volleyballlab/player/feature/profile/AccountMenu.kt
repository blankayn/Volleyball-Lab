package com.volleyballlab.player.feature.profile

import androidx.compose.foundation.background
import androidx.compose.animation.animateContentSize
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.saveable.rememberSaveable
import com.volleyballlab.player.BuildConfig
import com.volleyballlab.player.ui.theme.vbTween

enum class AccountSection(val title: String) {
    Personal("Personal information"), Plan("Plan & limits"), Team("Team & coaching"),
    Settings("Settings"), Training("Training preferences"), Notifications("Notifications"),
    Security("Security"), Privacy("Privacy & data"), Help("Get help")
}

internal data class AccountMenuEntry(val label: String, val icon: ImageVector, val onClick: () -> Unit)

internal val AccountMenuSurface = Color(0xFFEDF1F2)
private val AccountMenuInk = Color(0xFF2C3039)

/** The same simple ordered profile menu for coach and student accounts. */
@Composable
internal fun AccountMenuScreen(
    coach: Boolean,
    onLogout: () -> Unit, contentPadding: PaddingValues, modifier: Modifier = Modifier,
    banner: @Composable () -> Unit,
    sectionContent: @Composable (AccountSection) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf<AccountSection?>(null) }
    BackHandler(enabled = expanded != null) { expanded = null }
    fun toggle(section: AccountSection) { expanded = if (expanded == section) null else section }
    LazyColumn(modifier.fillMaxSize().background(Color.White).imePadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = contentPadding.calculateTopPadding() + 16.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { Text("Profile", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold) }
        item(key = "profile-banner") { banner() }
        item {
            AccountMenuGroup(listOf(
                AccountMenuEntry(AccountSection.Personal.title, Icons.Rounded.Person) { toggle(AccountSection.Personal) },
                if (coach) AccountMenuEntry(AccountSection.Team.title, Icons.Rounded.Groups) { toggle(AccountSection.Team) }
                else AccountMenuEntry(AccountSection.Plan.title, Icons.Rounded.Speed) { toggle(AccountSection.Plan) },
                AccountMenuEntry(AccountSection.Settings.title, Icons.Rounded.Settings) { toggle(AccountSection.Settings) },
                AccountMenuEntry(AccountSection.Help.title, Icons.Rounded.HeadsetMic) { toggle(AccountSection.Help) },
            ), expandedLabel = expanded?.title, expandedContent = { expanded?.let { sectionContent(it) } })
        }
        item {
            Surface(onClick = onLogout, shape = RoundedCornerShape(22.dp), color = AccountMenuSurface,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 62.dp)) {
                Box(Modifier.padding(18.dp), contentAlignment = Alignment.Center) {
                    Text("Log out", color = Color(0xFFB01854), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                }
            }
        }
        item {
            Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun AccountMenuGroup(entries: List<AccountMenuEntry>, modifier: Modifier = Modifier,
    expandedLabel: String? = null, expandedContent: (@Composable () -> Unit)? = null) {
    Surface(modifier.fillMaxWidth().animateContentSize(vbTween()), shape = RoundedCornerShape(24.dp), color = AccountMenuSurface) {
        Column {
            entries.forEachIndexed { index, entry ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = Color(0xFFD4E0E2))
                Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = entry.onClick)
                    .heightIn(min = 64.dp).padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Icon(entry.icon, null, Modifier.size(26.dp), tint = AccountMenuInk)
                    Text(entry.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Normal, color = AccountMenuInk)
                    Icon(if (entry.label == expandedLabel) Icons.Rounded.ExpandMore else Icons.Rounded.ChevronRight, null, Modifier.size(24.dp), tint = AccountMenuInk)
                }
                if (entry.label == expandedLabel && expandedContent != null) {
                    Column(Modifier.fillMaxWidth().background(Color.White).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { expandedContent() }
                }
            }
        }
    }
}

@Composable
internal fun InlineAccountSettings(coach: Boolean, content: @Composable (AccountSection) -> Unit) {
    var selected by rememberSaveable { mutableStateOf<AccountSection?>(null) }
    BackHandler(enabled = selected != null) { selected = null }
    fun toggle(section: AccountSection) { selected = if (selected == section) null else section }
    AccountMenuGroup(buildList {
        if (!coach) add(AccountMenuEntry(AccountSection.Training.title, Icons.Rounded.VolumeUp) { toggle(AccountSection.Training) })
        add(AccountMenuEntry(AccountSection.Notifications.title, Icons.Rounded.Notifications) { toggle(AccountSection.Notifications) })
        add(AccountMenuEntry(AccountSection.Security.title, Icons.Rounded.Shield) { toggle(AccountSection.Security) })
        add(AccountMenuEntry(AccountSection.Privacy.title, Icons.Rounded.Person) { toggle(AccountSection.Privacy) })
    }, expandedLabel = selected?.title, expandedContent = { selected?.let { content(it) } })
}

@Composable
internal fun AccountPageHeader(title: String, onBack: () -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, enabled = enabled, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", Modifier.size(26.dp), tint = AccountMenuInk)
        }
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = AccountMenuInk)
        Spacer(Modifier.width(48.dp))
    }
}

@Composable
internal fun PersonalInformationSummary(name: String, email: String?, onEdit: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = AccountMenuSurface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Name", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            email?.takeIf { it.isNotBlank() }?.let {
                Text("Email", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = RoundedCornerShape(12.dp)) { Text("Edit name & address") }
        }
    }
}

@Composable
internal fun NotificationInboxEntry() {
    var open by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(22.dp), color = AccountMenuSurface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Notification inbox", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("View your alerts and manage Android delivery.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = RoundedCornerShape(12.dp)) { Text("Open notification inbox") }
        }
    }
    if (open) com.volleyballlab.player.feature.notifications.NotificationInboxDialog { open = false }
}

/** Help stays inside the app and explains the workflows the app actually supports. */
@Composable
internal fun AccountHelpContent(coach: Boolean) {
    val topics = if (coach) listOf(
        "How do players join my team?" to "Players open Find a coach in the student app and request to join. Review their request in Players, then choose Approve or Decline.",
        "Where do I review training?" to "Open the review queue on Team, or a player’s Sessions page. Analysis, Recording and Feedback are separate views within a session.",
        "How do I reply to a question?" to "Open Player questions from Team. Select a pending introduction or session question, write your training guidance, and send the response.",
        "How does my status work?" to "Your status updates automatically while you use the workspace. Reviewing or preparing guidance shows Busy. Background activity and idle time change your status to Offline.",
    ) else listOf(
        "How do I connect with a coach?" to "Open Find a coach from the Coach tab or Plan & limits. Coach connection requires Pro. After approval, open My coach to see assignments and ask about getting started.",
        "How do I share a session?" to "After recording a training session, open My coach, choose Training, and send the session for review. Your coach’s feedback appears in Feedback.",
        "Where can I see my plan limits?" to "Open Plan & limits from Profile. It shows your current plan and the detailed reports remaining for the week.",
        "Why can’t I hear voice coaching?" to "Check Training preferences in Settings. Voice coaching uses an installed English voice; Speech settings lets you check the voices on your phone.",
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        topics.forEach { (question, answer) -> HelpTopic(question, answer) }
    }
}

@Composable
private fun HelpTopic(question: String, answer: String) {
    var open by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(20.dp), color = AccountMenuSurface, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { open = !open }.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(question, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, Modifier.size(24.dp))
            }
            if (open) Text(answer, Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
