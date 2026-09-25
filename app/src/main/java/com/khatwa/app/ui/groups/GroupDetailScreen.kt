package com.khatwa.app.ui.groups

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.khatwa.app.i18n.strings
import com.khatwa.app.ui.components.CardTone
import com.khatwa.app.ui.components.DangerButton
import com.khatwa.app.ui.components.KCard
import com.khatwa.app.ui.components.Muted
import com.khatwa.app.ui.components.SecondaryButton
import com.khatwa.app.ui.components.SectionTitle
import com.khatwa.app.ui.components.VSpace
import com.khatwa.app.ui.settings.SwitchRow
import com.khatwa.app.util.Clip
import com.khatwa.app.util.Fmt
import com.khatwa.core.groups.MemberSort

/** One group: totals, the ranked members with filters, and the owner's tools. */
@Composable
fun GroupDetailScreen(vm: GroupsViewModel, gid: String, onBack: () -> Unit) {
    val s = strings
    val context = LocalContext.current
    val d by vm.detail.collectAsStateWithLifecycle()
    val f by vm.memberFilters.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<String?>(null) } // "leave" | "delete" | "regen" | "remove:<uid>"

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("group_detail").padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = s.back) }
            Column(Modifier.weight(1f)) {
                Text(d.group?.name ?: "…", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("group_title"))
                d.group?.description?.takeIf { it.isNotBlank() }?.let { Muted(it) }
            }
        }
        VSpace(8.dp)
        message?.let { kind ->
            KCard(tone = CardTone.Warning) { Text(s.errorText(kind)); TextButton(onClick = { vm.clearMessage() }) { Text(s.done) } }
            VSpace()
        }
        val memberCount = d.group?.memberCount ?: d.members.size
        Muted("${s.membersCount(Fmt.n(memberCount))} · ${s.goalMetToday(Fmt.n(d.totals.goalMet), Fmt.n(memberCount))}")
        VSpace(8.dp)
        MembersSummary(d.totals, s)
        VSpace()
        val days by vm.groupDays.collectAsStateWithLifecycle()
        GroupCharts(d, days, s)
        VSpace()

        KCard {
            Muted(s.period)
            PeriodChips(f.period, s) { vm.memberFilters.value = f.copy(period = it) }
            VSpace(6.dp)
            Muted(s.sortBy)
            ChipRow(
                listOf(MemberSort.STEPS to s.sortSteps, MemberSort.GOAL_DAYS to s.sortGoalDays, MemberSort.STREAK to s.sortStreak, MemberSort.LONGEST_SESSION to s.sortLongestSession),
                f.sort,
            ) { vm.memberFilters.value = f.copy(sort = it) }
            VSpace(4.dp)
            ChipRow(listOf(true to s.descending, false to s.ascending), f.descending) { vm.memberFilters.value = f.copy(descending = it) }
            VSpace(10.dp)
            d.rows.forEachIndexed { i, r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("member_row_$i"), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}.", style = MaterialTheme.typography.titleMedium)
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                r.member?.nickname ?: "?",
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (r.isMe) FontWeight.Bold else FontWeight.Normal),
                            )
                            if (r.isMe) Muted(" · ${s.you}")
                            if (r.member?.uid == d.group?.ownerUid) Muted(" · ${s.owner}")
                        }
                        Muted("${s.sortSteps} ${Fmt.n(r.row.steps)} · ${s.sortGoalDays} ${Fmt.n(r.row.goalDays)} · ${s.sortStreak} ${Fmt.n(r.row.streak)}")
                    }
                    Text(
                        when (f.sort) {
                            MemberSort.STEPS -> Fmt.n(r.row.steps)
                            MemberSort.GOAL_DAYS -> Fmt.n(r.row.goalDays)
                            MemberSort.STREAK -> Fmt.n(r.row.streak)
                            MemberSort.LONGEST_SESSION -> if (r.row.longestMs > 0) Fmt.duration(r.row.longestMs) else "—"
                        },
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    )
                    if (d.isOwner && !r.isMe && r.member != null) {
                        TextButton(onClick = { confirm = "remove:${r.member.uid}" }) { Text(s.removeMember) }
                    }
                }
            }
        }
        VSpace()

        if (d.isOwner) {
            KCard(tone = CardTone.Soft) {
                SectionTitle(s.inviteCode)
                Muted(s.youAreOwner)
                VSpace(6.dp)
                Text(
                    d.inviteCode?.let { it.substring(0, 4) + "-" + it.substring(4) } ?: "…",
                    style = MaterialTheme.typography.displayLarge.copy(fontFamily = FontFamily.Monospace, fontSize = 32.sp, letterSpacing = 3.sp),
                    modifier = Modifier.fillMaxWidth().testTag("group_invite_code"),
                )
                VSpace(6.dp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(s.copy, Modifier.weight(1f), enabled = d.inviteCode != null) { d.inviteCode?.let { Clip.copy(context, "khatwa invite", it, s.copied) } }
                    SecondaryButton(s.regenerateCode, Modifier.weight(1f)) { confirm = "regen" }
                }
                VSpace(6.dp)
                SwitchRow(s.hideFromPublic, d.group?.hidden ?: false) { on -> vm.setHidden(gid, on) }
                VSpace(6.dp)
                DangerButton(s.deleteGroup, Modifier.fillMaxWidth()) { confirm = "delete" }
            }
        } else {
            SecondaryButton(s.leaveGroup, Modifier.fillMaxWidth()) { confirm = "leave" }
        }
        Box(Modifier.padding(bottom = 24.dp))
    }

    confirm?.let { which ->
        val (title, text, action) = when {
            which == "leave" -> Triple(s.leaveGroup, s.leaveGroupQuestion) { vm.leave(gid, onBack) }
            which == "delete" -> Triple(s.deleteGroup, s.deleteGroupQuestion) { vm.delete(gid, onBack) }
            which == "regen" -> Triple(s.regenerateCode, s.regenerateCodeHint) { vm.regenerate(gid) }
            else -> {
                val uid = which.removePrefix("remove:")
                val name = d.members.firstOrNull { it.uid == uid }?.nickname ?: "?"
                Triple(s.removeMember, s.removeMemberQuestion(name)) { vm.removeMember(gid, uid) }
            }
        }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(title) },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { confirm = null; action() }) { Text(title) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(s.cancel) } },
        )
    }
}

/** Short step numbers for chart labels: 950, 12.4k. */
private fun compact(v: Long): String = if (v < 1_000) v.toString() else String.format(java.util.Locale.US, "%.1fk", v / 1000.0).replace(".0k", "k")

/**
 * The group in pictures: how many reached today's goal (a ring), the group's total over the last
 * 7 days (columns; kept from the day this version runs), and each member's steps for the chosen
 * period (bars). Bars and columns grow in when they appear.
 */
@Composable
private fun GroupCharts(d: GroupDetailState, days: List<com.khatwa.app.groups.GroupDay>, s: com.khatwa.app.i18n.Strings) {
    val today = java.time.LocalDate.now()
    val count = (d.group?.memberCount ?: d.members.size).coerceAtLeast(1)
    val todayTotals = com.khatwa.core.groups.Ranking.publicTotals(d.group?.summary, count, com.khatwa.core.groups.Period.TODAY, today)
    val primary = MaterialTheme.colorScheme.primary
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val grow by androidx.compose.animation.core.animateFloatAsState(if (shown) 1f else 0f, androidx.compose.animation.core.tween(900), label = "grow")

    KCard(modifier = Modifier.testTag("group_charts")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val ratio = todayTotals.goalMet.toFloat() / count
            com.khatwa.app.ui.components.ProgressRing(progress = ratio * grow, size = 84.dp, stroke = 9.dp) {
                Text("${(ratio * 100).toInt()}%", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
            }
            Column(Modifier.padding(start = 14.dp)) {
                Text(s.goalMetToday(Fmt.n(todayTotals.goalMet), Fmt.n(count)), style = MaterialTheme.typography.titleMedium)
                Muted("${s.groupTotal} ${Fmt.n(todayTotals.steps)}")
            }
        }
        VSpace()
        SectionTitle(s.groupWeekTrend)
        val byDate = days.associateBy { it.date }
        val week = (6 downTo 0).map { today.minusDays(it.toLong()) }
        val max = week.maxOf { byDate[it.toString()]?.steps ?: 0L }.coerceAtLeast(1L)
        Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
            week.forEach { date ->
                val v = byDate[date.toString()]?.steps ?: 0L
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (v > 0) compact(v) else "", style = MaterialTheme.typography.labelSmall)
                    Box(
                        Modifier.width(22.dp).height((100f * grow * v / max).coerceAtLeast(3f).dp)
                            .background(if (date == today) primary else primary.copy(alpha = 0.55f), RoundedCornerShape(6.dp)),
                    )
                    VSpace(4.dp)
                    Text(Fmt.dayShort(date.dayOfWeek.value), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (days.size < 2) Muted(s.trendStartsToday)
        if (d.rows.isNotEmpty()) {
            VSpace()
            SectionTitle(s.membersChart)
            val top = d.rows.take(10)
            val most = top.maxOf { it.row.steps }.coerceAtLeast(1L)
            top.forEach { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(r.member?.nickname ?: "?", maxLines = 1, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (r.isMe) FontWeight.Bold else FontWeight.Normal), modifier = Modifier.width(84.dp))
                    Box(Modifier.weight(1f).height(14.dp)) {
                        Box(Modifier.fillMaxWidth((grow * r.row.steps / most).coerceIn(0.02f, 1f)).height(14.dp).background(if (r.isMe) primary else primary.copy(alpha = 0.6f), RoundedCornerShape(7.dp)))
                    }
                    Text(compact(r.row.steps), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}
