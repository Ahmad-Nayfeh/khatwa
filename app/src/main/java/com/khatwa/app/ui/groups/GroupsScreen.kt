package com.khatwa.app.ui.groups

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.khatwa.app.AppContainer
import com.khatwa.app.i18n.Strings
import com.khatwa.app.i18n.strings
import com.khatwa.app.ui.components.CardTone
import com.khatwa.app.ui.components.KCard
import com.khatwa.app.ui.components.Muted
import com.khatwa.app.ui.components.PrimaryButton
import com.khatwa.app.ui.components.SecondaryButton
import com.khatwa.app.ui.components.SectionTitle
import com.khatwa.app.ui.components.StatPill
import com.khatwa.app.ui.components.VSpace
import com.khatwa.app.ui.containerViewModel
import com.khatwa.app.util.Fmt
import com.khatwa.core.groups.GroupSort
import com.khatwa.core.groups.Period

/** Groups tab: opt-in card, then "my groups" / "all groups" with the public ranking. */
@Composable
fun GroupsScreen(container: AppContainer) {
    val vm = containerViewModel { GroupsViewModel(it) }
    val s = strings
    val enabled by vm.enabled.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var openGroup by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(enabled) { if (enabled) vm.sync() }

    if (openGroup != null) {
        BackHandler { openGroup = null; vm.close() }
        GroupDetailScreen(vm, openGroup!!, onBack = { openGroup = null; vm.close() })
        return
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("groups_scroll").padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text(s.groupsTitle, style = MaterialTheme.typography.headlineMedium)
        VSpace()
        if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); VSpace(8.dp) }
        message?.let { kind ->
            KCard(tone = CardTone.Warning) {
                Text(s.errorText(kind))
                TextButton(onClick = { vm.clearMessage() }) { Text(s.done) }
            }
            VSpace()
        }
        when {
            !vm.configured -> KCard(tone = CardTone.Soft) { Text(s.groupsNotConfigured) }
            !enabled -> OptInCard(vm, s)
            else -> EnabledContent(vm, s) { gid -> vm.open(gid); openGroup = gid }
        }
        Box(Modifier.padding(bottom = 24.dp))
    }
}

@Composable
private fun OptInCard(vm: GroupsViewModel, s: Strings) {
    var nickname by rememberSaveable { mutableStateOf("") }
    KCard {
        SectionTitle(s.groupsTitle)
        Text(s.groupsIntro)
        VSpace(8.dp)
        Muted(s.groupsWhatIsSent)
        VSpace(6.dp)
        Muted(s.groupsPrivacy)
        VSpace()
        OutlinedTextField(
            value = nickname, onValueChange = { nickname = it.take(24) }, label = { Text(s.nickname) },
            supportingText = { Text(s.nicknameHint) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("groups_nickname"),
        )
        VSpace(8.dp)
        PrimaryButton(s.enableGroups, Modifier.fillMaxWidth().testTag("groups_enable"), enabled = nickname.isNotBlank()) { vm.enable(nickname) }
    }
    VSpace()
    Muted(s.groupsLimitation)
}

@Composable
private fun EnabledContent(vm: GroupsViewModel, s: Strings, open: (String) -> Unit) {
    val nickname by vm.nickname.collectAsStateWithLifecycle()
    val myGroups by vm.myGroups.collectAsStateWithLifecycle()
    val publicGroups by vm.publicGroups.collectAsStateWithLifecycle()
    val filters by vm.publicFilters.collectAsStateWithLifecycle()
    val lastSync by vm.lastSync.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) } // "create" | "join" | "nickname" | "disable"

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(nickname, style = MaterialTheme.typography.titleMedium)
            Muted(lastSync?.let { s.lastPublished(Fmt.time(java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).let { t -> t.hour * 60 + t.minute })) } ?: s.notPublishedYet)
        }
        TextButton(onClick = { dialog = "nickname" }) { Text(s.edit) }
        TextButton(onClick = { vm.sync() }) { Text(s.refresh) }
    }
    VSpace(8.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PrimaryButton(s.createGroup, Modifier.weight(1f).testTag("groups_create")) { dialog = "create" }
        SecondaryButton(s.joinWithCode, Modifier.weight(1f).testTag("groups_join")) { dialog = "join" }
    }
    VSpace()
    TabRow(selectedTabIndex = tab) {
        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(s.myGroups) }, modifier = Modifier.testTag("groups_tab_mine"))
        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(s.allGroups) }, modifier = Modifier.testTag("groups_tab_all"))
    }
    VSpace()
    if (tab == 0) {
        if (myGroups.isEmpty()) Muted(s.noGroupsYet)
        myGroups.forEach { g ->
            KCard(Modifier.clickable { open(g.id) }.testTag("group_card_${g.id}")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(g.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (g.hidden) Muted(s.hiddenBadge)
                }
                if (g.description.isNotBlank()) Muted(g.description)
                VSpace(4.dp)
                val t = com.khatwa.core.groups.Ranking.publicTotals(g.summary, g.memberCount, filters.period, java.time.LocalDate.now())
                Muted("${s.membersCount(Fmt.n(g.memberCount))} · ${s.goalMetToday(Fmt.n(t.goalMet), Fmt.n(g.memberCount))}")
            }
            VSpace(8.dp)
        }
        VSpace()
        SecondaryButton(s.disableGroups, Modifier.fillMaxWidth()) { dialog = "disable" }
        Muted(s.disableGroupsHint)
    } else {
        OutlinedTextField(
            value = filters.query, onValueChange = { vm.publicFilters.value = filters.copy(query = it) },
            label = { Text(s.searchGroups) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        VSpace(8.dp)
        PeriodChips(filters.period, s) { vm.publicFilters.value = filters.copy(period = it) }
        VSpace(4.dp)
        ChipRow(
            listOf(GroupSort.TOTAL_STEPS to s.sortTotalSteps, GroupSort.AVERAGE_STEPS to s.sortAverageSteps, GroupSort.GOAL_RATIO to s.sortGoalRatio, GroupSort.MEMBERS to s.sortMembers),
            filters.sort,
        ) { vm.publicFilters.value = filters.copy(sort = it) }
        VSpace(4.dp)
        ChipRow(listOf(true to s.descending, false to s.ascending), filters.descending) { vm.publicFilters.value = filters.copy(descending = it) }
        VSpace()
        if (publicGroups.isEmpty()) Muted(s.noPublicGroups)
        publicGroups.forEachIndexed { i, rg ->
            val g = rg.group
            val mine = myGroups.any { it.id == g.id }
            KCard(Modifier.then(if (mine) Modifier.clickable { open(g.id) } else Modifier), tone = if (mine) CardTone.Soft else CardTone.Normal) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}.", style = MaterialTheme.typography.titleMedium)
                    Text(g.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                    Text(
                        when (filters.sort) {
                            GroupSort.TOTAL_STEPS -> Fmt.n(rg.totals.steps)
                            GroupSort.AVERAGE_STEPS -> Fmt.n(rg.totals.averageSteps)
                            GroupSort.GOAL_RATIO -> "${(rg.totals.goalRatio * 100).toInt()}%"
                            GroupSort.MEMBERS -> Fmt.n(g.memberCount)
                        },
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    )
                }
                Muted("${s.membersCount(Fmt.n(g.memberCount))} · ${s.groupTotal} ${Fmt.n(rg.totals.steps)} · ${s.average} ${Fmt.n(rg.totals.averageSteps)}")
            }
            VSpace(8.dp)
        }
    }

    when (dialog) {
        "create" -> {
            var name by remember { mutableStateOf("") }
            var desc by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(s.createGroup) },
                text = {
                    Column {
                        OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, label = { Text(s.groupName) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("group_name"))
                        VSpace(6.dp)
                        OutlinedTextField(value = desc, onValueChange = { desc = it.take(120) }, label = { Text(s.groupDescription) }, modifier = Modifier.fillMaxWidth())
                    }
                },
                confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { dialog = null; vm.create(name, desc) { open(it) } }, modifier = Modifier.testTag("group_create_confirm")) { Text(s.create) } },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text(s.cancel) } },
            )
        }
        "join" -> {
            var code by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(s.joinWithCode) },
                text = {
                    OutlinedTextField(value = code, onValueChange = { code = it.take(12) }, label = { Text(s.inviteCodeLabel) }, supportingText = { Text(s.inviteCodeHint) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("group_code"))
                },
                confirmButton = { TextButton(enabled = code.isNotBlank(), onClick = { dialog = null; vm.join(code) { open(it) } }, modifier = Modifier.testTag("group_join_confirm")) { Text(s.join) } },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text(s.cancel) } },
            )
        }
        "nickname" -> {
            var name by remember { mutableStateOf(nickname) }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(s.nickname) },
                text = { OutlinedTextField(value = name, onValueChange = { name = it.take(24) }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
                confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { dialog = null; vm.rename(name) }) { Text(s.save) } },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text(s.cancel) } },
            )
        }
        "disable" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(s.disableGroups) },
            text = { Text(s.disableGroupsHint) },
            confirmButton = { TextButton(onClick = { dialog = null; vm.disable() }) { Text(s.disableGroups) } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(s.cancel) } },
        )
    }
}

@Composable
fun PeriodChips(selected: Period, s: Strings, onChange: (Period) -> Unit) =
    ChipRow(listOf(Period.TODAY to s.periodToday, Period.WEEK to s.periodWeek, Period.MONTH to s.periodMonth), selected, onChange)

@Composable
fun <T> ChipRow(options: List<Pair<T, String>>, selected: T, onChange: (T) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (value, label) ->
            FilterChip(selected = value == selected, onClick = { onChange(value) }, label = { Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium) }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
fun MembersSummary(totals: com.khatwa.core.groups.GroupTotals, s: Strings) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatPill(s.groupTotal, Fmt.n(totals.steps), Modifier.weight(1f))
        StatPill(s.average, Fmt.n(totals.averageSteps), Modifier.weight(1f))
        StatPill(s.sortGoalRatio, "${(totals.goalRatio * 100).toInt()}%", Modifier.weight(1f))
    }
}
