package com.khatwa.app.ui.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.khatwa.app.AppContainer
import com.khatwa.app.groups.Account
import com.khatwa.app.groups.Group
import com.khatwa.app.groups.GroupsException
import com.khatwa.app.groups.GroupsSync
import com.khatwa.app.groups.Member
import com.khatwa.app.i18n.Strings
import com.khatwa.core.groups.GroupSort
import com.khatwa.core.groups.GroupTotals
import com.khatwa.core.groups.MemberRow
import com.khatwa.core.groups.MemberSort
import com.khatwa.core.groups.MemberStats
import com.khatwa.core.groups.Period
import com.khatwa.core.groups.Ranking
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Public ranking filters (SPEC §10: default this week, by total, descending). */
data class PublicFilters(val period: Period = Period.WEEK, val sort: GroupSort = GroupSort.TOTAL_STEPS, val descending: Boolean = true, val query: String = "")

data class MemberFilters(val period: Period = Period.WEEK, val sort: MemberSort = MemberSort.STEPS, val descending: Boolean = true)

data class RankedGroup(val group: Group, val totals: GroupTotals)

data class LeaderboardRow(val row: MemberRow, val member: Member?, val isMe: Boolean)

data class GroupDetailState(
    val group: Group? = null,
    val members: List<Member> = emptyList(),
    val rows: List<LeaderboardRow> = emptyList(),
    val totals: GroupTotals = GroupTotals.EMPTY,
    val isOwner: Boolean = false,
    val inviteCode: String? = null,
    val error: GroupsException.Kind? = null,
)

class GroupsViewModel(private val c: AppContainer) : ViewModel() {
    /** The signed-in account (null: nobody is signed in on this phone). */
    val account: StateFlow<Account?> = c.groups.observeAccount().stateIn(viewModelScope, SharingStarted.Eagerly, c.groups.account)
    private val groupsOn: StateFlow<Boolean> = c.settings.flow.map { it.groupsEnabled }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    /** The uid whose groups are shown: groups turned on and someone signed in, else null. */
    private val session: StateFlow<String?> = combine(groupsOn, account) { on, acc -> if (on) acc?.uid else null }
        .distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val enabled: StateFlow<Boolean> = session.map { it != null }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val isAdmin: StateFlow<Boolean> = c.groups.observeIsAdmin().catch { emit(false) }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val nickname: StateFlow<String> = c.settings.flow.map { it.groupsNickname }.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val configured: Boolean get() = c.groups.configured

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private val _message = MutableStateFlow<GroupsException.Kind?>(null)
    val message: StateFlow<GroupsException.Kind?> = _message
    private val _lastSync = MutableStateFlow<Long?>(null)
    val lastSync: StateFlow<Long?> = _lastSync

    val publicFilters = MutableStateFlow(PublicFilters())
    val memberFilters = MutableStateFlow(MemberFilters())
    private val today: LocalDate get() = c.tracker.today.value.date

    val myGroups: StateFlow<List<Group>> = session.flatMapLatest { uid ->
        if (uid == null || !configured) flowOf(emptyList()) else c.groups.observeMyGroups().catch { e -> fail(e); emit(emptyList()) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val publicGroups: StateFlow<List<RankedGroup>> = session.flatMapLatest { uid ->
        if (uid == null || !configured) flowOf(emptyList()) else c.groups.observePublicGroups().catch { e -> fail(e); emit(emptyList()) }
    }.combine(publicFilters) { groups, f ->
        val ranked = groups
            .filter { f.query.isBlank() || it.name.contains(f.query, ignoreCase = true) }
            .map { g -> g to Ranking.publicTotals(g.summary, g.memberCount, f.period, today) }
        Ranking.rankGroups(ranked, f.sort, f.descending).map { (g, t) -> RankedGroup(g, t) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val selected = MutableStateFlow<String?>(null)

    val detail: StateFlow<GroupDetailState> = selected.flatMapLatest { gid ->
        if (gid == null) flowOf(GroupDetailState())
        else combine(
            myGroups.map { list -> list.firstOrNull { it.id == gid } },
            c.groups.observeMembers(gid).catch { e -> fail(e); emit(emptyList()) },
            c.groups.observeStats(gid).catch { e -> fail(e); emit(emptyList()) },
            memberFilters,
            inviteCode,
        ) { group, members, stats, f, code ->
            val me = c.groups.uid
            val byUid = members.associateBy { it.uid }
            // Members who have not published yet still appear (with zeros).
            val all: List<MemberStats> = members.map { m ->
                stats.firstOrNull { it.uid == m.uid } ?: MemberStats(m.uid, 0, com.khatwa.core.groups.PeriodStats.EMPTY, com.khatwa.core.groups.PeriodStats.EMPTY, com.khatwa.core.groups.PeriodStats.EMPTY)
            }
            val rows = Ranking.rankMembers(all, f.period, f.sort, f.descending, today).map { r -> LeaderboardRow(r, byUid[r.uid], r.uid == me) }
            GroupDetailState(
                group = group,
                members = members,
                rows = rows,
                totals = Ranking.totals(all, group?.memberCount ?: members.size, f.period, today),
                isOwner = group != null && group.ownerUid == me,
                inviteCode = code,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GroupDetailState())

    private val inviteCode = MutableStateFlow<String?>(null)

    /** The open group's totals for its last days (7-day chart). */
    val groupDays: StateFlow<List<com.khatwa.app.groups.GroupDay>> = selected.flatMapLatest { gid ->
        if (gid == null) flowOf(emptyList()) else c.groups.observeGroupDays(gid).catch { emit(emptyList()) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun open(gid: String) {
        inviteCode.value = null
        selected.value = gid
        viewModelScope.launch { inviteCode.value = runCatching { c.groups.inviteCode(gid) }.getOrNull() }
    }

    fun close() { selected.value = null }

    /** Turns groups on for the signed-in account (they were turned off on this phone). */
    fun enable() = run { turnOn() }

    /** Turns groups on right away (after signing in from the Groups tab). */
    suspend fun turnOnNow() = turnOn()

    private suspend fun turnOn() {
        c.settings.setGroupsEnabled(true)
        GroupsSync.schedule(c.app, true)
        sync()
    }

    fun disable() = run {
        c.settings.setGroupsEnabled(false)
        GroupsSync.schedule(c.app, false)
    }

    fun rename(nickname: String) = run {
        val name = nickname.trim().take(24)
        if (name.isBlank()) return@run
        c.settings.setGroupsNickname(name)
        c.groups.ensureProfile(name)
    }

    fun create(name: String, description: String, onDone: (String) -> Unit) = run {
        val gid = c.groups.createGroup(name, description)
        sync()
        onDone(gid)
    }

    fun join(code: String, onDone: (String) -> Unit) = run {
        val gid = c.groups.joinByCode(code)
        sync()
        onDone(gid)
    }

    fun leave(gid: String, onDone: () -> Unit) = run { c.groups.leave(gid); onDone() }
    fun delete(gid: String, onDone: () -> Unit) = run { c.groups.deleteGroup(gid); onDone() }
    fun removeMember(gid: String, uid: String) = run { c.groups.removeMember(gid, uid) }
    fun regenerate(gid: String) = run { inviteCode.value = c.groups.regenerateCode(gid) }
    fun setHidden(gid: String, hidden: Boolean) = run { c.groups.setHidden(gid, hidden) }

    fun sync() {
        viewModelScope.launch {
            if (GroupsSync.syncNow(c)) _lastSync.value = System.currentTimeMillis()
        }
    }

    fun clearMessage() { _message.value = null }

    private fun run(block: suspend () -> Unit): Job = viewModelScope.launch {
        _busy.value = true
        try { block() } catch (e: Exception) { fail(e) } finally { _busy.value = false }
    }

    private fun fail(e: Throwable) {
        _message.value = (e as? GroupsException)?.kind ?: GroupsException.Kind.UNKNOWN
    }
}

fun Strings.errorText(kind: GroupsException.Kind): String = when (kind) {
    GroupsException.Kind.NOT_CONFIGURED -> errorNotConfigured
    GroupsException.Kind.OFFLINE -> errorOffline
    GroupsException.Kind.NOT_SIGNED_IN -> errorNotSignedIn
    GroupsException.Kind.INVALID_CODE -> errorInvalidCode
    GroupsException.Kind.UNKNOWN_CODE -> errorUnknownCode
    GroupsException.Kind.ALREADY_MEMBER -> errorAlreadyMember
    GroupsException.Kind.GROUP_FULL -> errorGroupFull
    GroupsException.Kind.TOO_MANY_GROUPS -> errorTooManyGroups
    GroupsException.Kind.OWNER_CANNOT_LEAVE -> errorOwnerCannotLeave
    GroupsException.Kind.DENIED -> errorDenied
    GroupsException.Kind.UNKNOWN -> errorUnknown
    GroupsException.Kind.EMAIL_IN_USE -> errorEmailInUse
    GroupsException.Kind.INVALID_EMAIL -> errorInvalidEmail
    GroupsException.Kind.WEAK_PASSWORD -> errorWeakPassword
    GroupsException.Kind.WRONG_CREDENTIALS -> errorWrongCredentials
    GroupsException.Kind.SIGN_IN_DISABLED -> errorSignInDisabled
    GroupsException.Kind.TOO_MANY_ATTEMPTS -> errorTooManyAttempts
    GroupsException.Kind.WRONG_ADMIN_KEY -> errorWrongAdminKey
    GroupsException.Kind.REMOVE_OWNER_FIRST -> errorRemoveOwnerFirst
}
