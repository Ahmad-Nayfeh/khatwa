package com.khatwa.app.ui.groups

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.khatwa.app.AppContainer
import com.khatwa.app.groups.Group
import com.khatwa.app.groups.GroupsException
import com.khatwa.app.groups.Member
import com.khatwa.app.groups.UserProfile
import com.khatwa.app.i18n.Strings
import com.khatwa.app.i18n.strings
import com.khatwa.app.ui.components.CardTone
import com.khatwa.app.ui.components.DangerButton
import com.khatwa.app.ui.components.KCard
import com.khatwa.app.ui.components.Muted
import com.khatwa.app.ui.components.SecondaryButton
import com.khatwa.app.ui.components.SectionTitle
import com.khatwa.app.ui.components.VSpace
import com.khatwa.app.ui.containerViewModel
import com.khatwa.app.ui.settings.SubScreen
import com.khatwa.app.ui.settings.SwitchRow
import com.khatwa.app.util.Fmt
import com.khatwa.core.groups.MemberStats
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One member of a group as the admin sees it: name and this week's steps. */
data class AdminMemberRow(val member: Member, val weekSteps: Long, val isOwner: Boolean)

data class AdminGroupState(val group: Group? = null, val rows: List<AdminMemberRow> = emptyList(), val inviteCode: String? = null)

/**
 * The admin panel's data. Everything goes through the normal repository calls; the security rules
 * are what give admins/{uid} access to all groups and profiles.
 */
class AdminViewModel(private val c: AppContainer) : ViewModel() {
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private val _message = MutableStateFlow<GroupsException.Kind?>(null)
    val message: StateFlow<GroupsException.Kind?> = _message

    val groups: StateFlow<List<Group>> = c.groups.observeAllGroups().catch { fail(it); emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val users: StateFlow<List<UserProfile>> = c.groups.observeUsers().catch { fail(it); emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val selectedGroup = MutableStateFlow<String?>(null)
    private val inviteCode = MutableStateFlow<String?>(null)

    val group: StateFlow<AdminGroupState> = selectedGroup.flatMapLatest { gid ->
        if (gid == null) flowOf(AdminGroupState())
        else combine(
            groups,
            c.groups.observeMembers(gid).catch { fail(it); emit(emptyList()) },
            c.groups.observeStats(gid).catch { fail(it); emit(emptyList<MemberStats>()) },
            inviteCode,
        ) { all, members, stats, code ->
            val g = all.firstOrNull { it.id == gid }
            val rows = members.map { m ->
                AdminMemberRow(m, stats.firstOrNull { it.uid == m.uid }?.week?.steps ?: 0L, g?.ownerUid == m.uid)
            }.sortedByDescending { it.weekSteps }
            AdminGroupState(g, rows, code)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AdminGroupState())

    private val _userGroups = MutableStateFlow<List<String>>(emptyList())
    /** Group ids of the user opened in the panel. */
    val userGroups: StateFlow<List<String>> = _userGroups

    fun openGroup(gid: String?) {
        inviteCode.value = null
        selectedGroup.value = gid
        if (gid != null) viewModelScope.launch { inviteCode.value = runCatching { c.groups.inviteCode(gid) }.getOrNull() }
    }

    fun openUser(uid: String?) {
        _userGroups.value = emptyList()
        if (uid != null) run { _userGroups.value = c.groups.groupIdsOf(uid) }
    }

    fun updateGroup(gid: String, name: String, description: String, hidden: Boolean) = run { c.groups.adminUpdateGroup(gid, name, description, hidden) }
    fun newCode(gid: String) = run { inviteCode.value = c.groups.regenerateCode(gid) }
    fun removeMember(gid: String, uid: String) = run { c.groups.adminRemoveMember(gid, uid) }
    fun makeOwner(gid: String, uid: String) = run { c.groups.adminMakeOwner(gid, uid) }
    fun deleteGroup(gid: String, onDone: () -> Unit) = run { c.groups.adminDeleteGroup(gid); onDone() }
    fun renameUser(uid: String, name: String) = run { c.groups.adminRenameUser(uid, name) }
    fun deleteUser(uid: String, onDone: () -> Unit) = run { c.groups.adminDeleteUserData(uid); onDone() }

    private val _passwordSaved = MutableStateFlow(false)
    val passwordSaved: StateFlow<Boolean> = _passwordSaved

    /** Only the SHA-256 of the password is stored; from now on the temporary key stops working. */
    fun setAdminPassword(password: String) = run {
        c.groups.setAdminPassword(password)
        _passwordSaved.value = true
    }

    fun clearMessage() { _message.value = null; _passwordSaved.value = false }

    private fun run(block: suspend () -> Unit): Job = viewModelScope.launch {
        _busy.value = true
        try { block() } catch (e: Exception) { fail(e) } finally { _busy.value = false }
    }

    private fun fail(e: Throwable) {
        _message.value = (e as? GroupsException)?.kind ?: GroupsException.Kind.UNKNOWN
    }
}

/** Admin panel: every group (hidden ones too) and every profile, with full control. */
@Composable
fun AdminScreen(container: AppContainer, onBack: () -> Unit) {
    val vm = containerViewModel { AdminViewModel(it) }
    val s = strings
    val groups by vm.groups.collectAsStateWithLifecycle()
    val users by vm.users.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val passwordSaved by vm.passwordSaved.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var changingPassword by rememberSaveable { mutableStateOf(false) }
    var openGroup by rememberSaveable { mutableStateOf<String?>(null) }
    var openUser by rememberSaveable { mutableStateOf<String?>(null) }
    val names = users.associate { it.uid to it.nickname }

    val back = {
        when {
            openGroup != null -> { openGroup = null; vm.openGroup(null) }
            openUser != null -> { openUser = null; vm.openUser(null) }
            else -> onBack()
        }
    }
    BackHandler(onBack = back)

    SubScreen(title = s.adminPanel, onBack = back) {
        if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); VSpace(8.dp) }
        message?.let { kind ->
            KCard(tone = CardTone.Warning) {
                Text(s.errorText(kind))
                TextButton(onClick = { vm.clearMessage() }) { Text(s.done) }
            }
            VSpace()
        }
        if (passwordSaved) {
            KCard(tone = CardTone.Accent, modifier = Modifier.testTag("admin_password_saved")) {
                Text(s.adminPasswordSet)
                TextButton(onClick = { vm.clearMessage() }) { Text(s.done) }
            }
            VSpace()
        }
        val g = openGroup
        val u = openUser
        when {
            g != null -> AdminGroupDetail(vm, s, g, names) { openGroup = null; vm.openGroup(null) }
            u != null -> AdminUserDetail(vm, s, users.firstOrNull { it.uid == u }, groups) { openUser = null; vm.openUser(null) }
            else -> {
                SecondaryButton(s.changeAdminPassword, Modifier.fillMaxWidth().testTag("admin_change_password")) { changingPassword = true }
                VSpace()
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(s.adminGroups(Fmt.n(groups.size))) }, modifier = Modifier.testTag("admin_tab_groups"))
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(s.adminUsers(Fmt.n(users.size))) }, modifier = Modifier.testTag("admin_tab_users"))
                }
                VSpace()
                if (tab == 0) {
                    if (groups.isEmpty()) Muted(s.adminNoData)
                    groups.forEach { grp ->
                        KCard(Modifier.clickable { openGroup = grp.id; vm.openGroup(grp.id) }.testTag("admin_group_${grp.id}")) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(grp.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                if (grp.hidden) Muted(s.hiddenBadge)
                            }
                            Muted("${s.membersCount(Fmt.n(grp.memberCount))} · ${s.owner}: ${names[grp.ownerUid] ?: "?"}")
                        }
                        VSpace(8.dp)
                    }
                } else {
                    if (users.isEmpty()) Muted(s.adminNoData)
                    users.forEach { usr ->
                        KCard(Modifier.clickable { openUser = usr.uid; vm.openUser(usr.uid) }.testTag("admin_user_${usr.uid}")) {
                            Text(usr.nickname, style = MaterialTheme.typography.titleMedium)
                            Muted(s.adminOwns(Fmt.n(usr.ownedGroups)))
                        }
                        VSpace(8.dp)
                    }
                }
            }
        }
    }
    if (changingPassword) ChangeAdminPasswordDialog(s, onDismiss = { changingPassword = false }) { pw -> changingPassword = false; vm.setAdminPassword(pw) }
}

/** New admin password, typed twice; at least 8 characters. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun ChangeAdminPasswordDialog(s: Strings, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val long = first.trim().length >= 8
    val same = first == second
    AlertDialog(
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        onDismissRequest = onDismiss,
        title = { Text(s.changeAdminPassword) },
        text = {
            Column {
                Muted(s.adminPasswordRules)
                VSpace(6.dp)
                OutlinedTextField(
                    value = first, onValueChange = { first = it.take(64) }, label = { Text(s.newPassword) }, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag("admin_new_password"),
                )
                VSpace(4.dp)
                OutlinedTextField(
                    value = second, onValueChange = { second = it.take(64) }, label = { Text(s.repeatPassword) }, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    isError = second.isNotEmpty() && !same,
                    supportingText = { if (second.isNotEmpty() && !same) Text(s.passwordsDontMatch) },
                    modifier = Modifier.fillMaxWidth().testTag("admin_repeat_password"),
                )
            }
        },
        confirmButton = { TextButton(enabled = long && same, onClick = { onSave(first) }, modifier = Modifier.testTag("admin_password_save")) { Text(s.save) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } },
    )
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun AdminGroupDetail(vm: AdminViewModel, s: Strings, gid: String, names: Map<String, String>, close: () -> Unit) {
    val state by vm.group.collectAsStateWithLifecycle()
    val grp = state.group
    var confirm by remember { mutableStateOf<String?>(null) } // "delete" | "code" | "remove:<uid>" | "owner:<uid>"
    var editing by remember { mutableStateOf(false) }

    Column(Modifier.testTag("admin_group_detail")) {
        Text(grp?.name ?: "…", style = MaterialTheme.typography.headlineSmall)
        grp?.description?.takeIf { it.isNotBlank() }?.let { Muted(it) }
        Muted("${s.membersCount(Fmt.n(grp?.memberCount ?: 0))} · ${s.owner}: ${grp?.ownerUid?.let { names[it] } ?: "?"}")
        VSpace()
        KCard(tone = CardTone.Soft) {
            SectionTitle(s.inviteCode)
            Text(state.inviteCode?.let { it.take(4) + "-" + it.drop(4) } ?: "…", style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace))
            VSpace(6.dp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(s.regenerateCode, Modifier.weight(1f)) { confirm = "code" }
                SecondaryButton(s.adminEditGroup, Modifier.weight(1f).testTag("admin_edit_group"), enabled = grp != null) { editing = true }
            }
            if (grp != null) SwitchRow(s.adminShowInPublic, !grp.hidden) { on -> vm.updateGroup(gid, grp.name, grp.description, hidden = !on) }
        }
        VSpace()
        state.rows.forEach { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("admin_member_${r.member.uid}"), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.member.nickname + if (r.isOwner) " · ${s.owner}" else "", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (r.isOwner) FontWeight.Bold else FontWeight.Normal))
                    Muted(s.adminWeekSteps(Fmt.n(r.weekSteps)))
                }
                if (!r.isOwner) {
                    TextButton(onClick = { confirm = "owner:${r.member.uid}" }) { Text(s.adminMakeOwner) }
                    TextButton(onClick = { confirm = "remove:${r.member.uid}" }, modifier = Modifier.testTag("admin_remove_${r.member.uid}")) { Text(s.removeMember) }
                }
            }
        }
        VSpace()
        DangerButton(s.deleteGroup, Modifier.fillMaxWidth().testTag("admin_delete_group")) { confirm = "delete" }
    }

    if (editing && grp != null) {
        var name by remember { mutableStateOf(grp.name) }
        var desc by remember { mutableStateOf(grp.description) }
        AlertDialog(
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            onDismissRequest = { editing = false },
            title = { Text(s.adminEditGroup) },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, label = { Text(s.groupName) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("admin_group_name"))
                    VSpace(6.dp)
                    OutlinedTextField(value = desc, onValueChange = { desc = it.take(120) }, label = { Text(s.groupDescription) }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { editing = false; vm.updateGroup(gid, name, desc, grp.hidden) }, modifier = Modifier.testTag("admin_group_save")) { Text(s.save) } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text(s.cancel) } },
        )
    }

    confirm?.let { which ->
        val who = which.substringAfter(':', "")
        val whoName = state.rows.firstOrNull { it.member.uid == who }?.member?.nickname ?: "?"
        val (title, text, action) = when {
            which == "delete" -> Triple(s.deleteGroup, s.deleteGroupQuestion) { vm.deleteGroup(gid, close) }
            which == "code" -> Triple(s.regenerateCode, s.regenerateCodeHint) { vm.newCode(gid) }
            which.startsWith("owner:") -> Triple(s.adminMakeOwner, whoName) { vm.makeOwner(gid, who) }
            else -> Triple(s.removeMember, s.removeMemberQuestion(whoName)) { vm.removeMember(gid, who) }
        }
        AlertDialog(
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            onDismissRequest = { confirm = null },
            title = { Text(title) },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { confirm = null; action() }, modifier = Modifier.testTag("admin_confirm")) { Text(s.confirm) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(s.cancel) } },
        )
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun AdminUserDetail(vm: AdminViewModel, s: Strings, user: UserProfile?, groups: List<Group>, close: () -> Unit) {
    val ids by vm.userGroups.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    if (user == null) { Muted(s.adminNoData); return }

    Column(Modifier.testTag("admin_user_detail")) {
        Text(user.nickname, style = MaterialTheme.typography.headlineSmall)
        Muted(s.adminOwns(Fmt.n(user.ownedGroups)))
        VSpace()
        SectionTitle(s.adminTheirGroups)
        if (ids.isEmpty()) Muted(s.adminNoData)
        ids.forEach { gid ->
            val g = groups.firstOrNull { it.id == gid }
            Text("• " + (g?.name ?: gid) + if (g?.ownerUid == user.uid) " · ${s.owner}" else "", style = MaterialTheme.typography.bodyLarge)
        }
        VSpace()
        SecondaryButton(s.adminRename, Modifier.fillMaxWidth()) { renaming = true }
        VSpace(8.dp)
        DangerButton(s.adminDeleteUser, Modifier.fillMaxWidth().testTag("admin_delete_user")) { deleting = true }
    }

    if (renaming) {
        var name by remember { mutableStateOf(user.nickname) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(s.adminRename) },
            text = { OutlinedTextField(value = name, onValueChange = { name = it.take(24) }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { renaming = false; vm.renameUser(user.uid, name) }) { Text(s.save) } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(s.cancel) } },
        )
    }
    if (deleting) {
        AlertDialog(
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            onDismissRequest = { deleting = false },
            title = { Text(s.adminDeleteUser) },
            text = { Text(s.adminDeleteUserQuestion) },
            confirmButton = { TextButton(onClick = { deleting = false; vm.deleteUser(user.uid, close) }, modifier = Modifier.testTag("admin_confirm")) { Text(s.confirm) } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(s.cancel) } },
        )
    }
}
